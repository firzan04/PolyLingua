// JNI bridge over the sherpa-onnx C API (libsherpa-onnx-c-api.so, sherpa-onnx v1.13.8).
//
// Exposes: streaming Zipformer transducer ASR (OnlineRecognizer), Silero VAD, offline second-pass
// ASR (Moonshine / Whisper), offline neural TTS (Kokoro / Kitten / VITS-Piper / Matcha) and the
// English online punctuation + casing model. Every handle is an opaque jlong.
//
// Threading contract: the Kotlin layer calls create*/decode*/generate* only from its single
// big-core inference thread (pinned to the Cortex-A76 pair). ONNX Runtime creates its intra-op
// worker threads during session creation, so they inherit that affinity mask.
#include "jni_common.h"

#include "c-api.h"

#include <cstring>
#include <sstream>
#include <exception>
#include <string>
#include <vector>

using namespace polylingua;

namespace {

template <typename T>
T* FromHandle(jlong h) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(h));
}

template <typename T>
jlong ToHandle(const T* p) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(p));
}

std::string TokensJson(const char* const* tokens, const float* timestamps, int32_t count) {
    std::ostringstream o;
    o << "\"tokens\":[";
    for (int32_t i = 0; i < count; ++i) {
        o << (i ? "," : "") << "\"" << JsonEscape(tokens && tokens[i] ? tokens[i] : "") << "\"";
    }
    o << "],\"timestamps\":[";
    if (timestamps != nullptr) {
        for (int32_t i = 0; i < count; ++i) {
            char buf[32];
            std::snprintf(buf, sizeof(buf), "%.3f", timestamps[i]);
            o << (i ? "," : "") << buf;
        }
    }
    o << "]";
    return o.str();
}

}  // namespace

extern "C" {

// ------------------------------------------------------------------ Streaming ASR (Zipformer)

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeCreateOnlineRecognizer(
        JNIEnv* env, jobject, jstring jEncoder, jstring jDecoder, jstring jJoiner, jstring jTokens,
        jint numThreads, jstring jDecodingMethod, jboolean enableEndpoint, jfloat rule1,
        jfloat rule2, jfloat rule3, jstring jModelType) {
    std::string modelType = JStringToStd(env, jModelType);
    std::string encoder = JStringToStd(env, jEncoder);
    std::string decoder = JStringToStd(env, jDecoder);
    std::string joiner = JStringToStd(env, jJoiner);
    std::string tokens = JStringToStd(env, jTokens);
    std::string method = JStringToStd(env, jDecodingMethod);
    if (method.empty()) method = "greedy_search";

    SherpaOnnxOnlineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.model_config.transducer.encoder = encoder.c_str();
    config.model_config.transducer.decoder = decoder.c_str();
    config.model_config.transducer.joiner = joiner.c_str();
    config.model_config.tokens = tokens.c_str();
    config.model_config.num_threads = numThreads > 0 ? numThreads : 2;
    config.model_config.provider = "cpu";
    config.model_config.debug = 0;
    // "" lets sherpa-onnx detect the architecture from model metadata (zipformer, NeMo
    // FastConformer, Nemotron); "zipformer2" is required for some community exports.
    if (!modelType.empty()) config.model_config.model_type = modelType.c_str();
    config.decoding_method = method.c_str();
    config.max_active_paths = 4;
    config.enable_endpoint = enableEndpoint ? 1 : 0;
    config.rule1_min_trailing_silence = rule1;
    config.rule2_min_trailing_silence = rule2;
    config.rule3_min_utterance_length = rule3;

    const SherpaOnnxOnlineRecognizer* rec = nullptr;
    try {
        rec = SherpaOnnxCreateOnlineRecognizer(&config);
    } catch (const std::exception& e) {
        PL_LOGE("SherpaOnnxCreateOnlineRecognizer threw: %s", e.what());
        rec = nullptr;
    } catch (...) {
        PL_LOGE("SherpaOnnxCreateOnlineRecognizer threw an unknown exception");
        rec = nullptr;
    }
    if (rec == nullptr) PL_LOGE("SherpaOnnxCreateOnlineRecognizer failed (encoder=%s)", encoder.c_str());
    return ToHandle(rec);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeDestroyOnlineRecognizer(JNIEnv*, jobject, jlong h) {
    if (h != 0) SherpaOnnxDestroyOnlineRecognizer(FromHandle<const SherpaOnnxOnlineRecognizer>(h));
}

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeCreateOnlineStream(JNIEnv*, jobject, jlong rec) {
    if (rec == 0) return 0;
    return ToHandle(SherpaOnnxCreateOnlineStream(FromHandle<const SherpaOnnxOnlineRecognizer>(rec)));
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeDestroyOnlineStream(JNIEnv*, jobject, jlong s) {
    if (s != 0) SherpaOnnxDestroyOnlineStream(FromHandle<const SherpaOnnxOnlineStream>(s));
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeOnlineAcceptWaveform(
        JNIEnv* env, jobject, jlong s, jint sampleRate, jfloatArray samples, jint n) {
    if (s == 0) return;
    std::vector<float> buf = CopyFloatArray(env, samples, n);
    if (buf.empty()) return;
    SherpaOnnxOnlineStreamAcceptWaveform(FromHandle<const SherpaOnnxOnlineStream>(s), sampleRate,
                                        buf.data(), static_cast<int32_t>(buf.size()));
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeOnlineDecode(JNIEnv*, jobject, jlong rec, jlong s) {
    if (rec == 0 || s == 0) return 0;
    auto* r = FromHandle<const SherpaOnnxOnlineRecognizer>(rec);
    auto* st = FromHandle<const SherpaOnnxOnlineStream>(s);
    int steps = 0;
    while (SherpaOnnxIsOnlineStreamReady(r, st)) {
        SherpaOnnxDecodeOnlineStream(r, st);
        ++steps;
    }
    return steps;
}

JNIEXPORT jstring JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeOnlineGetResult(JNIEnv* env, jobject, jlong rec, jlong s) {
    if (rec == 0 || s == 0) return NewStringUtf8Safe(env, "{\"text\":\"\",\"tokens\":[],\"timestamps\":[]}");
    const SherpaOnnxOnlineRecognizerResult* r = SherpaOnnxGetOnlineStreamResult(
            FromHandle<const SherpaOnnxOnlineRecognizer>(rec), FromHandle<const SherpaOnnxOnlineStream>(s));
    if (r == nullptr) return NewStringUtf8Safe(env, "{\"text\":\"\",\"tokens\":[],\"timestamps\":[]}");
    std::string json = std::string("{\"text\":\"") + JsonEscape(r->text ? r->text : "") + "\"," +
                       TokensJson(r->tokens_arr, r->timestamps, r->count) + "}";
    SherpaOnnxDestroyOnlineRecognizerResult(r);
    return NewStringUtf8Safe(env, json);
}

JNIEXPORT jboolean JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeOnlineIsEndpoint(JNIEnv*, jobject, jlong rec, jlong s) {
    if (rec == 0 || s == 0) return JNI_FALSE;
    return SherpaOnnxOnlineStreamIsEndpoint(FromHandle<const SherpaOnnxOnlineRecognizer>(rec),
                                            FromHandle<const SherpaOnnxOnlineStream>(s)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeOnlineReset(JNIEnv*, jobject, jlong rec, jlong s) {
    if (rec == 0 || s == 0) return;
    SherpaOnnxOnlineStreamReset(FromHandle<const SherpaOnnxOnlineRecognizer>(rec),
                                FromHandle<const SherpaOnnxOnlineStream>(s));
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeOnlineInputFinished(JNIEnv*, jobject, jlong s) {
    if (s != 0) SherpaOnnxOnlineStreamInputFinished(FromHandle<const SherpaOnnxOnlineStream>(s));
}

// ------------------------------------------------------------------------------ Silero VAD

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeCreateVad(
        JNIEnv* env, jobject, jstring jModel, jfloat threshold, jfloat minSilence, jfloat minSpeech,
        jint windowSize, jfloat maxSpeech, jint sampleRate, jint numThreads, jfloat bufferSeconds) {
    std::string model = JStringToStd(env, jModel);
    SherpaOnnxVadModelConfig config;
    std::memset(&config, 0, sizeof(config));
    config.silero_vad.model = model.c_str();
    config.silero_vad.threshold = threshold;
    config.silero_vad.min_silence_duration = minSilence;
    config.silero_vad.min_speech_duration = minSpeech;
    config.silero_vad.window_size = windowSize;
    config.silero_vad.max_speech_duration = maxSpeech;
    config.sample_rate = sampleRate;
    config.num_threads = numThreads > 0 ? numThreads : 1;
    config.provider = "cpu";
    config.debug = 0;
    const SherpaOnnxVoiceActivityDetector* vad =
            SherpaOnnxCreateVoiceActivityDetector(&config, bufferSeconds > 0 ? bufferSeconds : 60.0f);
    if (vad == nullptr) PL_LOGE("SherpaOnnxCreateVoiceActivityDetector failed (%s)", model.c_str());
    return ToHandle(vad);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeVadAcceptWaveform(JNIEnv* env, jobject, jlong h,
                                                                           jfloatArray samples, jint n) {
    if (h == 0) return;
    std::vector<float> buf = CopyFloatArray(env, samples, n);
    if (buf.empty()) return;
    SherpaOnnxVoiceActivityDetectorAcceptWaveform(FromHandle<const SherpaOnnxVoiceActivityDetector>(h),
                                                  buf.data(), static_cast<int32_t>(buf.size()));
}

JNIEXPORT jboolean JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeVadIsSpeech(JNIEnv*, jobject, jlong h) {
    if (h == 0) return JNI_FALSE;
    return SherpaOnnxVoiceActivityDetectorDetected(FromHandle<const SherpaOnnxVoiceActivityDetector>(h))
                   ? JNI_TRUE : JNI_FALSE;
}

// Returns completed speech segments as [start0, length0, start1, length1, ...] in samples.
JNIEXPORT jintArray JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeVadPopSegments(JNIEnv* env, jobject, jlong h) {
    std::vector<jint> out;
    if (h != 0) {
        auto* vad = FromHandle<const SherpaOnnxVoiceActivityDetector>(h);
        while (!SherpaOnnxVoiceActivityDetectorEmpty(vad)) {
            const SherpaOnnxSpeechSegment* seg = SherpaOnnxVoiceActivityDetectorFront(vad);
            if (seg != nullptr) {
                out.push_back(seg->start);
                out.push_back(seg->n);
                SherpaOnnxDestroySpeechSegment(seg);
            }
            SherpaOnnxVoiceActivityDetectorPop(vad);
        }
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (arr != nullptr && !out.empty()) env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeVadFlush(JNIEnv*, jobject, jlong h) {
    if (h != 0) SherpaOnnxVoiceActivityDetectorFlush(FromHandle<const SherpaOnnxVoiceActivityDetector>(h));
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeVadReset(JNIEnv*, jobject, jlong h) {
    if (h != 0) SherpaOnnxVoiceActivityDetectorReset(FromHandle<const SherpaOnnxVoiceActivityDetector>(h));
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeDestroyVad(JNIEnv*, jobject, jlong h) {
    if (h != 0) SherpaOnnxDestroyVoiceActivityDetector(FromHandle<const SherpaOnnxVoiceActivityDetector>(h));
}

// ------------------------------------------------------- Offline second-pass ASR (Moonshine/Whisper)

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeCreateOfflineRecognizer(
        JNIEnv* env, jobject, jstring jKind, jstring jP1, jstring jP2, jstring jP3, jstring jP4,
        jstring jTokens, jint numThreads) {
    std::string kind = JStringToStd(env, jKind);
    std::string p1 = JStringToStd(env, jP1), p2 = JStringToStd(env, jP2);
    std::string p3 = JStringToStd(env, jP3), p4 = JStringToStd(env, jP4);
    std::string tokens = JStringToStd(env, jTokens);

    SherpaOnnxOfflineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.model_config.tokens = tokens.c_str();
    config.model_config.num_threads = numThreads > 0 ? numThreads : 2;
    config.model_config.provider = "cpu";
    config.model_config.debug = 0;
    config.decoding_method = "greedy_search";
    if (kind == "moonshine") {
        config.model_config.moonshine.preprocessor = p1.c_str();
        config.model_config.moonshine.encoder = p2.c_str();
        config.model_config.moonshine.uncached_decoder = p3.c_str();
        config.model_config.moonshine.cached_decoder = p4.c_str();
    } else if (kind == "moonshine2") {
        // Moonshine v2 exports: encoder + merged decoder (.ort)
        config.model_config.moonshine.encoder = p1.c_str();
        config.model_config.moonshine.merged_decoder = p2.c_str();
    } else if (kind == "nemo_transducer") {
        // NVIDIA Parakeet TDT / NeMo RNN-T: encoder, decoder (prediction net), joiner
        config.model_config.transducer.encoder = p1.c_str();
        config.model_config.transducer.decoder = p2.c_str();
        config.model_config.transducer.joiner = p3.c_str();
        config.model_config.model_type = "nemo_transducer";
    } else if (kind == "nemo_ctc") {
        // NeMo FastConformer CTC (e.g. Parakeet TDT-CTC 110M exported as CTC)
        config.model_config.nemo_ctc.model = p1.c_str();
    } else if (kind == "transducer") {
        // Zipformer offline transducer
        config.model_config.transducer.encoder = p1.c_str();
        config.model_config.transducer.decoder = p2.c_str();
        config.model_config.transducer.joiner = p3.c_str();
    } else if (kind == "whisper") {
        config.model_config.whisper.encoder = p1.c_str();
        config.model_config.whisper.decoder = p2.c_str();
        config.model_config.whisper.language = "en";
        config.model_config.whisper.task = "transcribe";
        config.model_config.whisper.tail_paddings = -1;
    } else {
        PL_LOGE("Unknown offline recognizer kind: %s", kind.c_str());
        return 0;
    }
    const SherpaOnnxOfflineRecognizer* rec = nullptr;
    try {
        rec = SherpaOnnxCreateOfflineRecognizer(&config);
    } catch (const std::exception& e) {
        PL_LOGE("SherpaOnnxCreateOfflineRecognizer threw: %s", e.what());
        rec = nullptr;
    } catch (...) {
        PL_LOGE("SherpaOnnxCreateOfflineRecognizer threw an unknown exception");
        rec = nullptr;
    }
    if (rec == nullptr) PL_LOGE("SherpaOnnxCreateOfflineRecognizer failed (%s)", kind.c_str());
    return ToHandle(rec);
}

JNIEXPORT jstring JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeOfflineDecode(JNIEnv* env, jobject, jlong h,
                                                                       jfloatArray samples, jint sampleRate) {
    if (h == 0) return NewStringUtf8Safe(env, "{\"text\":\"\",\"tokens\":[],\"timestamps\":[]}");
    auto* rec = FromHandle<const SherpaOnnxOfflineRecognizer>(h);
    std::vector<float> buf = CopyFloatArray(env, samples, -1);
    const SherpaOnnxOfflineStream* stream = SherpaOnnxCreateOfflineStream(rec);
    if (stream == nullptr) return NewStringUtf8Safe(env, "{\"text\":\"\",\"tokens\":[],\"timestamps\":[]}");
    if (!buf.empty()) {
        SherpaOnnxAcceptWaveformOffline(stream, sampleRate, buf.data(), static_cast<int32_t>(buf.size()));
    }
    SherpaOnnxDecodeOfflineStream(rec, stream);
    const SherpaOnnxOfflineRecognizerResult* r = SherpaOnnxGetOfflineStreamResult(stream);
    std::string json = "{\"text\":\"\",\"tokens\":[],\"timestamps\":[]}";
    if (r != nullptr) {
        json = std::string("{\"text\":\"") + JsonEscape(r->text ? r->text : "") + "\"," +
               TokensJson(r->tokens_arr, r->timestamps, r->count) + "}";
        SherpaOnnxDestroyOfflineRecognizerResult(r);
    }
    SherpaOnnxDestroyOfflineStream(stream);
    return NewStringUtf8Safe(env, json);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeDestroyOfflineRecognizer(JNIEnv*, jobject, jlong h) {
    if (h != 0) SherpaOnnxDestroyOfflineRecognizer(FromHandle<const SherpaOnnxOfflineRecognizer>(h));
}

// ------------------------------------------------------------------------------ Offline TTS

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeCreateTts(
        JNIEnv* env, jobject, jstring jKind, jstring jModel, jstring jVoices, jstring jTokens,
        jstring jDataDir, jstring jLexicon, jstring jVocoder, jfloat lengthScale, jint numThreads) {
    std::string kind = JStringToStd(env, jKind);
    std::string model = JStringToStd(env, jModel), voices = JStringToStd(env, jVoices);
    std::string tokens = JStringToStd(env, jTokens), dataDir = JStringToStd(env, jDataDir);
    std::string lexicon = JStringToStd(env, jLexicon), vocoder = JStringToStd(env, jVocoder);
    const float ls = lengthScale > 0 ? lengthScale : 1.0f;

    SherpaOnnxOfflineTtsConfig config;
    std::memset(&config, 0, sizeof(config));
    config.model.num_threads = numThreads > 0 ? numThreads : 2;
    config.model.provider = "cpu";
    config.model.debug = 0;
    config.max_num_sentences = 1;
    config.silence_scale = 0.2f;
    if (kind == "kokoro") {
        config.model.kokoro.model = model.c_str();
        config.model.kokoro.voices = voices.c_str();
        config.model.kokoro.tokens = tokens.c_str();
        config.model.kokoro.data_dir = dataDir.c_str();
        config.model.kokoro.length_scale = ls;
        if (!lexicon.empty()) config.model.kokoro.lexicon = lexicon.c_str();
    } else if (kind == "kitten") {
        config.model.kitten.model = model.c_str();
        config.model.kitten.voices = voices.c_str();
        config.model.kitten.tokens = tokens.c_str();
        config.model.kitten.data_dir = dataDir.c_str();
        config.model.kitten.length_scale = ls;
    } else if (kind == "vits") {
        config.model.vits.model = model.c_str();
        config.model.vits.tokens = tokens.c_str();
        config.model.vits.data_dir = dataDir.c_str();
        if (!lexicon.empty()) config.model.vits.lexicon = lexicon.c_str();
        config.model.vits.noise_scale = 0.667f;
        config.model.vits.noise_scale_w = 0.8f;
        config.model.vits.length_scale = ls;
    } else if (kind == "matcha") {
        config.model.matcha.acoustic_model = model.c_str();
        config.model.matcha.vocoder = vocoder.c_str();
        config.model.matcha.tokens = tokens.c_str();
        config.model.matcha.data_dir = dataDir.c_str();
        config.model.matcha.noise_scale = 0.667f;
        config.model.matcha.length_scale = ls;
    } else {
        PL_LOGE("Unknown TTS kind: %s", kind.c_str());
        return 0;
    }
    const SherpaOnnxOfflineTts* tts = nullptr;
    try {
        tts = SherpaOnnxCreateOfflineTts(&config);
    } catch (const std::exception& e) {
        PL_LOGE("SherpaOnnxCreateOfflineTts threw: %s", e.what());
        tts = nullptr;
    } catch (...) {
        PL_LOGE("SherpaOnnxCreateOfflineTts threw an unknown exception");
        tts = nullptr;
    }
    if (tts == nullptr) PL_LOGE("SherpaOnnxCreateOfflineTts failed (%s, %s)", kind.c_str(), model.c_str());
    return ToHandle(tts);
}

JNIEXPORT jfloatArray JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeTtsGenerate(JNIEnv* env, jobject, jlong h,
                                                                     jstring jText, jint sid, jfloat speed) {
    if (h == 0) return env->NewFloatArray(0);
    std::string text = JStringToStd(env, jText);
    SherpaOnnxGenerationConfig gen;
    std::memset(&gen, 0, sizeof(gen));
    gen.silence_scale = 0.2f;
    gen.speed = speed > 0 ? speed : 1.0f;
    gen.sid = sid < 0 ? 0 : sid;
    const SherpaOnnxGeneratedAudio* audio = SherpaOnnxOfflineTtsGenerateWithConfig(
            FromHandle<const SherpaOnnxOfflineTts>(h), text.c_str(), &gen, nullptr, nullptr);
    if (audio == nullptr) return env->NewFloatArray(0);
    jfloatArray arr = env->NewFloatArray(audio->n);
    if (arr != nullptr && audio->n > 0) env->SetFloatArrayRegion(arr, 0, audio->n, audio->samples);
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeTtsSampleRate(JNIEnv*, jobject, jlong h) {
    return h == 0 ? 0 : SherpaOnnxOfflineTtsSampleRate(FromHandle<const SherpaOnnxOfflineTts>(h));
}

JNIEXPORT jint JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeTtsNumSpeakers(JNIEnv*, jobject, jlong h) {
    return h == 0 ? 0 : SherpaOnnxOfflineTtsNumSpeakers(FromHandle<const SherpaOnnxOfflineTts>(h));
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeDestroyTts(JNIEnv*, jobject, jlong h) {
    if (h != 0) SherpaOnnxDestroyOfflineTts(FromHandle<const SherpaOnnxOfflineTts>(h));
}

// ------------------------------------------------------------- Online punctuation + truecasing

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeCreateOnlinePunctuation(
        JNIEnv* env, jobject, jstring jModel, jstring jBpeVocab, jint numThreads) {
    std::string model = JStringToStd(env, jModel);
    std::string vocab = JStringToStd(env, jBpeVocab);
    SherpaOnnxOnlinePunctuationConfig config;
    std::memset(&config, 0, sizeof(config));
    config.model.cnn_bilstm = model.c_str();
    config.model.bpe_vocab = vocab.c_str();
    config.model.num_threads = numThreads > 0 ? numThreads : 1;
    config.model.provider = "cpu";
    config.model.debug = 0;
    const SherpaOnnxOnlinePunctuation* p = SherpaOnnxCreateOnlinePunctuation(&config);
    if (p == nullptr) PL_LOGE("SherpaOnnxCreateOnlinePunctuation failed (%s)", model.c_str());
    return ToHandle(p);
}

JNIEXPORT jstring JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeAddPunctuation(JNIEnv* env, jobject, jlong h, jstring jText) {
    std::string text = JStringToStd(env, jText);
    if (h == 0 || text.empty()) return NewStringUtf8Safe(env, text);
    const char* out = SherpaOnnxOnlinePunctuationAddPunct(FromHandle<const SherpaOnnxOnlinePunctuation>(h), text.c_str());
    if (out == nullptr) return NewStringUtf8Safe(env, text);
    std::string result(out);
    SherpaOnnxOnlinePunctuationFreeText(out);
    return NewStringUtf8Safe(env, result);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_SherpaNative_nativeDestroyOnlinePunctuation(JNIEnv*, jobject, jlong h) {
    if (h != 0) SherpaOnnxDestroyOnlinePunctuation(FromHandle<const SherpaOnnxOnlinePunctuation>(h));
}

}  // extern "C"
