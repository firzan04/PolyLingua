// Phoneme posterior extractor for Goodness-of-Pronunciation (GOP) scoring.
//
// Runs a wav2vec2 phone classifier (charsiu en_w2v2_fc_10ms, INT8 ONNX, 42 CMU phone classes at
// 10 ms/frame) through the ONNX Runtime C API that ships inside the sherpa-onnx prebuilt
// libonnxruntime.so (ORT 1.28.x; compiled here against the ORT 1.22 header, API version 22,
// which newer runtimes still serve). Output: per-frame log-softmax posteriors, consumed by
// PhonemeGopAnalyzer.kt for forced alignment and GOP computation.
#include "jni_common.h"

#include "onnxruntime_c_api.h"

#include <cmath>
#include <mutex>
#include <string>
#include <vector>

using namespace polylingua;

namespace {

const OrtApi* g_ort = nullptr;
OrtEnv* g_env = nullptr;
std::mutex g_init_mutex;

bool EnsureOrt() {
    std::lock_guard<std::mutex> lock(g_init_mutex);
    if (g_ort != nullptr && g_env != nullptr) return true;
    const OrtApiBase* base = OrtGetApiBase();
    if (base == nullptr) return false;
    g_ort = base->GetApi(ORT_API_VERSION);
    if (g_ort == nullptr) {
        PL_LOGE("ONNX Runtime does not provide API version %d", ORT_API_VERSION);
        return false;
    }
    OrtStatus* st = g_ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "polylingua-gop", &g_env);
    if (st != nullptr) {
        PL_LOGE("CreateEnv failed: %s", g_ort->GetErrorMessage(st));
        g_ort->ReleaseStatus(st);
        g_env = nullptr;
        return false;
    }
    return true;
}

bool Check(OrtStatus* st, const char* what) {
    if (st == nullptr) return true;
    PL_LOGE("%s failed: %s", what, g_ort->GetErrorMessage(st));
    g_ort->ReleaseStatus(st);
    return false;
}

struct GopSession {
    OrtSession* session = nullptr;
    OrtSessionOptions* options = nullptr;
    std::vector<std::string> inputNames;
    std::vector<ONNXTensorElementDataType> inputTypes;
    std::string outputName;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_GopNative_nativeCreate(JNIEnv* env, jobject, jstring jPath, jint numThreads) {
    if (!EnsureOrt()) return 0;
    std::string path = JStringToStd(env, jPath);
    auto* s = new GopSession();
    if (!Check(g_ort->CreateSessionOptions(&s->options), "CreateSessionOptions")) { delete s; return 0; }
    Check(g_ort->SetIntraOpNumThreads(s->options, numThreads > 0 ? numThreads : 2), "SetIntraOpNumThreads");
    Check(g_ort->SetInterOpNumThreads(s->options, 1), "SetInterOpNumThreads");
    Check(g_ort->SetSessionGraphOptimizationLevel(s->options, ORT_ENABLE_ALL), "SetGraphOptimizationLevel");
    Check(g_ort->SetSessionExecutionMode(s->options, ORT_SEQUENTIAL), "SetSessionExecutionMode");
    // Spinning intra-op threads burn battery between frames; the 2 A76 workers sleep instead.
    Check(g_ort->AddSessionConfigEntry(s->options, "session.intra_op.allow_spinning", "0"), "allow_spinning");

    if (!Check(g_ort->CreateSession(g_env, path.c_str(), s->options, &s->session), "CreateSession")) {
        g_ort->ReleaseSessionOptions(s->options);
        delete s;
        return 0;
    }
    OrtAllocator* allocator = nullptr;
    Check(g_ort->GetAllocatorWithDefaultOptions(&allocator), "GetAllocator");
    size_t nIn = 0, nOut = 0;
    Check(g_ort->SessionGetInputCount(s->session, &nIn), "SessionGetInputCount");
    Check(g_ort->SessionGetOutputCount(s->session, &nOut), "SessionGetOutputCount");
    for (size_t i = 0; i < nIn; ++i) {
        char* name = nullptr;
        if (Check(g_ort->SessionGetInputName(s->session, i, allocator, &name), "SessionGetInputName") && name) {
            s->inputNames.emplace_back(name);
            Check(g_ort->AllocatorFree(allocator, name), "AllocatorFree");
        }
        OrtTypeInfo* typeInfo = nullptr;
        ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT;
        if (Check(g_ort->SessionGetInputTypeInfo(s->session, i, &typeInfo), "SessionGetInputTypeInfo") && typeInfo) {
            const OrtTensorTypeAndShapeInfo* tinfo = nullptr;
            if (Check(g_ort->CastTypeInfoToTensorInfo(typeInfo, &tinfo), "CastTypeInfoToTensorInfo") && tinfo) {
                Check(g_ort->GetTensorElementType(tinfo, &type), "GetTensorElementType");
            }
            g_ort->ReleaseTypeInfo(typeInfo);
        }
        s->inputTypes.push_back(type);
    }
    if (nOut > 0) {
        char* name = nullptr;
        if (Check(g_ort->SessionGetOutputName(s->session, 0, allocator, &name), "SessionGetOutputName") && name) {
            s->outputName = name;
            Check(g_ort->AllocatorFree(allocator, name), "AllocatorFree");
        }
    }
    if (s->inputNames.empty() || s->outputName.empty()) {
        PL_LOGE("GOP model has no usable input/output");
        g_ort->ReleaseSession(s->session);
        g_ort->ReleaseSessionOptions(s->options);
        delete s;
        return 0;
    }
    PL_LOGI("GOP model loaded: %zu input(s), first=%s, output=%s", s->inputNames.size(),
            s->inputNames[0].c_str(), s->outputName.c_str());
    return static_cast<jlong>(reinterpret_cast<intptr_t>(s));
}

// Returns [T, C, logp(0,0) ... logp(T-1,C-1)] or an empty array on failure.
JNIEXPORT jfloatArray JNICALL
Java_com_polylingua_edge_nativebridge_GopNative_nativeComputeLogPosteriors(JNIEnv* env, jobject, jlong h,
                                                                           jfloatArray jAudio) {
    auto* s = reinterpret_cast<GopSession*>(static_cast<intptr_t>(h));
    if (s == nullptr || g_ort == nullptr) return env->NewFloatArray(0);
    std::vector<float> audio = CopyFloatArray(env, jAudio, -1);
    if (audio.size() < 1600) return env->NewFloatArray(0);  // need >= 100 ms

    // wav2vec2 feature extractor normalisation: zero mean, unit variance.
    double mean = 0.0;
    for (float v : audio) mean += v;
    mean /= static_cast<double>(audio.size());
    double var = 0.0;
    for (float v : audio) var += (v - mean) * (v - mean);
    var /= static_cast<double>(audio.size());
    const double inv = 1.0 / std::sqrt(var + 1e-7);
    for (float& v : audio) v = static_cast<float>((v - mean) * inv);

    OrtMemoryInfo* memInfo = nullptr;
    if (!Check(g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memInfo), "CreateCpuMemoryInfo")) {
        return env->NewFloatArray(0);
    }
    const int64_t n = static_cast<int64_t>(audio.size());
    const int64_t shape[2] = {1, n};
    std::vector<int64_t> mask(static_cast<size_t>(n), 1);
    std::vector<OrtValue*> inputs;
    std::vector<const char*> inputNames;
    bool ok = true;
    for (size_t i = 0; i < s->inputNames.size() && ok; ++i) {
        OrtValue* value = nullptr;
        const bool isMask = s->inputNames[i].find("mask") != std::string::npos ||
                            s->inputTypes[i] == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64;
        if (isMask) {
            ok = Check(g_ort->CreateTensorWithDataAsOrtValue(memInfo, mask.data(), mask.size() * sizeof(int64_t),
                                                             shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &value),
                       "CreateTensor(mask)");
        } else {
            ok = Check(g_ort->CreateTensorWithDataAsOrtValue(memInfo, audio.data(), audio.size() * sizeof(float),
                                                             shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value),
                       "CreateTensor(audio)");
        }
        if (ok) {
            inputs.push_back(value);
            inputNames.push_back(s->inputNames[i].c_str());
        }
    }
    jfloatArray result = nullptr;
    OrtValue* output = nullptr;
    const char* outName = s->outputName.c_str();
    if (ok) {
        ok = Check(g_ort->Run(s->session, nullptr, inputNames.data(), inputs.data(), inputs.size(), &outName, 1, &output),
                   "Run");
    }
    if (ok && output != nullptr) {
        OrtTensorTypeAndShapeInfo* info = nullptr;
        size_t dimsCount = 0;
        std::vector<int64_t> dims;
        ONNXTensorElementDataType outType = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
        if (Check(g_ort->GetTensorTypeAndShape(output, &info), "GetTensorTypeAndShape")) {
            Check(g_ort->GetDimensionsCount(info, &dimsCount), "GetDimensionsCount");
            dims.resize(dimsCount);
            if (dimsCount > 0) Check(g_ort->GetDimensions(info, dims.data(), dimsCount), "GetDimensions");
            Check(g_ort->GetTensorElementType(info, &outType), "GetTensorElementType");
            g_ort->ReleaseTensorTypeAndShapeInfo(info);
        }
        float* data = nullptr;
        if (outType == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && dimsCount >= 2 &&
            Check(g_ort->GetTensorMutableData(output, reinterpret_cast<void**>(&data)), "GetTensorMutableData") &&
            data != nullptr) {
            const int64_t T = dims[dimsCount - 2];
            const int64_t C = dims[dimsCount - 1];
            if (T > 0 && C > 0 && T * C < 50'000'000) {
                std::vector<float> out(static_cast<size_t>(2 + T * C));
                out[0] = static_cast<float>(T);
                out[1] = static_cast<float>(C);
                for (int64_t t = 0; t < T; ++t) {
                    const float* row = data + t * C;
                    float mx = row[0];
                    for (int64_t c = 1; c < C; ++c) mx = std::max(mx, row[c]);
                    double sum = 0.0;
                    for (int64_t c = 0; c < C; ++c) sum += std::exp(static_cast<double>(row[c] - mx));
                    const float logZ = mx + static_cast<float>(std::log(sum));
                    float* dst = out.data() + 2 + t * C;
                    for (int64_t c = 0; c < C; ++c) dst[c] = row[c] - logZ;
                }
                result = env->NewFloatArray(static_cast<jsize>(out.size()));
                if (result != nullptr) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
            }
        } else {
            PL_LOGE("Unexpected GOP output (type=%d dims=%zu)", static_cast<int>(outType), dimsCount);
        }
    }
    if (output != nullptr) g_ort->ReleaseValue(output);
    for (OrtValue* v : inputs) g_ort->ReleaseValue(v);
    g_ort->ReleaseMemoryInfo(memInfo);
    return result != nullptr ? result : env->NewFloatArray(0);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_GopNative_nativeDestroy(JNIEnv*, jobject, jlong h) {
    auto* s = reinterpret_cast<GopSession*>(static_cast<intptr_t>(h));
    if (s == nullptr || g_ort == nullptr) return;
    if (s->session) g_ort->ReleaseSession(s->session);
    if (s->options) g_ort->ReleaseSessionOptions(s->options);
    delete s;
}

}  // extern "C"
