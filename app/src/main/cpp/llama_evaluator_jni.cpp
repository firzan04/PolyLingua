// Offline small-language-model evaluator (llama.cpp, pinned tag b12000).
//
// Loads a 4-bit Qwen2.5-1.5B-Instruct (Q4_0) or SmolLM2-360M-Instruct (Q8_0) GGUF, applies the
// model's chat template, and generates with an optional GBNF grammar so the IELTS evaluator
// always receives syntactically valid JSON. KV-cache prefix reuse keeps multi-turn persona chats
// fast: only the new suffix of each prompt is evaluated.
//
// Threading: n_threads = n_threads_batch = 2 and every call arrives on the Kotlin big-core
// executor thread; ggml spawns its workers from that pinned thread, so they inherit the A76
// affinity mask (see cpu_affinity_manager.h).
#include "jni_common.h"

#include "llama.h"

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

using namespace polylingua;

namespace {

std::once_flag g_backend_once;

void LlamaLog(enum ggml_log_level level, const char* text, void*) {
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "PolyLinguaLlama", "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, "PolyLinguaLlama", "%s", text);
    }
}

struct LlamaHandle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    std::string chatTemplate;
    std::vector<llama_token> cached;  // tokens currently resident in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
    int nCtx = 2048;
    int nBatch = 512;
};

LlamaHandle* H(jlong h) { return reinterpret_cast<LlamaHandle*>(static_cast<intptr_t>(h)); }

std::string TokenPiece(const llama_vocab* vocab, llama_token token) {
    std::vector<char> buf(64);
    int32_t n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, false);
    if (n < 0) {
        buf.resize(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, false);
    }
    if (n <= 0) return std::string();
    return std::string(buf.data(), static_cast<size_t>(n));
}

bool Tokenize(const llama_vocab* vocab, const std::string& text, std::vector<llama_token>* out) {
    int32_t n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    if (n < 0) n = -n;
    out->resize(static_cast<size_t>(n));
    int32_t got = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), out->data(), n, true, true);
    if (got < 0) return false;
    out->resize(static_cast<size_t>(got));
    return true;
}

std::string ApplyTemplate(LlamaHandle* h, const std::vector<std::string>& roles,
                          const std::vector<std::string>& contents) {
    std::vector<llama_chat_message> msgs;
    size_t total = 0;
    for (size_t i = 0; i < roles.size(); ++i) {
        msgs.push_back({roles[i].c_str(), contents[i].c_str()});
        total += roles[i].size() + contents[i].size() + 32;
    }
    std::vector<char> buf(total * 2 + 512);
    const char* tmpl = h->chatTemplate.empty() ? nullptr : h->chatTemplate.c_str();
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(),
                                          static_cast<int32_t>(buf.size()));
    if (n < 0 && tmpl != nullptr) {
        // Template not recognised by the built-in detector: Qwen2.5 and SmolLM2 are ChatML.
        tmpl = "chatml";
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(),
                                      static_cast<int32_t>(buf.size()));
    }
    if (n < 0) return std::string();
    if (static_cast<size_t>(n) > buf.size()) {
        buf.resize(static_cast<size_t>(n) + 1);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(),
                                      static_cast<int32_t>(buf.size()));
        if (n < 0) return std::string();
    }
    return std::string(buf.data(), static_cast<size_t>(n));
}

std::vector<std::string> StringArray(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(JStringToStd(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeBackendInit(JNIEnv*, jobject) {
    std::call_once(g_backend_once, [] {
        llama_log_set(LlamaLog, nullptr);
        llama_backend_init();
    });
}

JNIEXPORT jstring JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeSystemInfo(JNIEnv* env, jobject) {
    const char* info = llama_print_system_info();
    return NewStringUtf8Safe(env, info ? info : "");
}

JNIEXPORT jlong JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeLoadModel(JNIEnv* env, jobject, jstring jPath, jint nCtx,
                                                                  jint nThreads, jint nBatch) {
    Java_com_polylingua_edge_nativebridge_LlamaNative_nativeBackendInit(env, nullptr);
    std::string path = JStringToStd(env, jPath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        PL_LOGE("llama_model_load_from_file failed: %s", path.c_str());
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx > 0 ? nCtx : 2048);
    cp.n_batch = static_cast<uint32_t>(nBatch > 0 ? nBatch : 512);
    cp.n_ubatch = std::min<uint32_t>(cp.n_batch, 256);
    cp.n_seq_max = 1;
    cp.n_threads = nThreads > 0 ? nThreads : 2;
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        PL_LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }
    auto* h = new LlamaHandle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    const char* tmpl = llama_model_chat_template(model, nullptr);
    h->chatTemplate = tmpl ? tmpl : "chatml";
    h->nCtx = static_cast<int>(llama_n_ctx(ctx));
    h->nBatch = static_cast<int>(cp.n_batch);
    PL_LOGI("LLM loaded: %s (ctx=%d, threads=%d)", path.c_str(), h->nCtx, cp.n_threads);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(h));
}

JNIEXPORT jstring JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeModelInfo(JNIEnv* env, jobject, jlong jh) {
    LlamaHandle* h = H(jh);
    if (h == nullptr) return NewStringUtf8Safe(env, "{}");
    std::string json = "{\"params\":" + std::to_string(llama_model_n_params(h->model)) +
                       ",\"ctx\":" + std::to_string(h->nCtx) + "}";
    return NewStringUtf8Safe(env, json);
}

// Generates a reply for the chat `roles`/`contents`. `grammar` (GBNF, root rule "root") may be
// null/empty. `callback` (nullable) implements LlamaNative.TokenCallback#onToken(byte[]).
JNIEXPORT jstring JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeGenerate(
        JNIEnv* env, jobject, jlong jh, jobjectArray jRoles, jobjectArray jContents, jstring jGrammar,
        jint maxTokens, jfloat temperature, jfloat topP, jint seed, jobject callback) {
    LlamaHandle* h = H(jh);
    if (h == nullptr) return NewStringUtf8Safe(env, "");
    h->cancel.store(false);

    std::vector<std::string> roles = StringArray(env, jRoles);
    std::vector<std::string> contents = StringArray(env, jContents);
    if (roles.size() != contents.size() || roles.empty()) return NewStringUtf8Safe(env, "");
    std::string prompt = ApplyTemplate(h, roles, contents);
    if (prompt.empty()) {
        PL_LOGE("chat template application failed");
        return NewStringUtf8Safe(env, "");
    }
    std::vector<llama_token> tokens;
    if (!Tokenize(h->vocab, prompt, &tokens) || tokens.empty()) return NewStringUtf8Safe(env, "");
    const int budget = std::max(16, static_cast<int>(maxTokens));
    if (static_cast<int>(tokens.size()) + budget + 8 > h->nCtx) {
        PL_LOGE("prompt too long: %zu tokens (ctx %d)", tokens.size(), h->nCtx);
        return NewStringUtf8Safe(env, "");
    }

    // ---- KV-cache prefix reuse
    llama_memory_t mem = llama_get_memory(h->ctx);
    size_t common = 0;
    while (common < h->cached.size() && common < tokens.size() && h->cached[common] == tokens[common]) ++common;
    if (common == tokens.size()) common = tokens.size() - 1;  // always re-evaluate the last token
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    h->cached.resize(common);

    for (size_t off = common; off < tokens.size();) {
        const int32_t n = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(h->nBatch), tokens.size() - off));
        llama_batch batch = llama_batch_get_one(tokens.data() + off, n);
        if (llama_decode(h->ctx, batch) != 0) {
            PL_LOGE("llama_decode(prompt) failed at offset %zu", off);
            llama_memory_clear(mem, true);
            h->cached.clear();
            return NewStringUtf8Safe(env, "");
        }
        h->cached.insert(h->cached.end(), tokens.begin() + static_cast<long>(off),
                         tokens.begin() + static_cast<long>(off + n));
        off += static_cast<size_t>(n);
        if (h->cancel.load()) return NewStringUtf8Safe(env, "");
    }

    // ---- sampler chain: grammar (optional) -> repetition penalty -> top-k/top-p/temp -> draw
    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    std::string grammar = JStringToStd(env, jGrammar);
    if (!grammar.empty()) {
        llama_sampler* g = llama_sampler_init_grammar(h->vocab, grammar.c_str(), "root");
        if (g == nullptr) {
            PL_LOGE("GBNF grammar failed to parse");
            llama_sampler_free(chain);
            return NewStringUtf8Safe(env, "");
        }
        llama_sampler_chain_add(chain, g);
    }
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(h->vocab), 64, 1.10f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    jmethodID onToken = nullptr;
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        onToken = env->GetMethodID(cls, "onToken", "([B)V");
        env->DeleteLocalRef(cls);
    }

    std::string output;
    std::string pending;
    for (int i = 0; i < budget; ++i) {
        if (h->cancel.load()) break;
        llama_token tok = llama_sampler_sample(chain, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        std::string piece = TokenPiece(h->vocab, tok);
        output += piece;
        pending += piece;
        size_t complete = CompleteUtf8Prefix(pending);
        if (onToken != nullptr && complete > 0) {
            jbyteArray arr = env->NewByteArray(static_cast<jsize>(complete));
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(complete), reinterpret_cast<const jbyte*>(pending.data()));
            env->CallVoidMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                onToken = nullptr;
            }
            pending.erase(0, complete);
        }
        llama_token next = tok;
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(h->ctx, batch) != 0) {
            PL_LOGE("llama_decode(gen) failed");
            break;
        }
        h->cached.push_back(tok);
        if (static_cast<int>(h->cached.size()) + 2 >= h->nCtx) break;
    }
    llama_sampler_free(chain);
    return NewStringUtf8Safe(env, output);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeCancel(JNIEnv*, jobject, jlong jh) {
    LlamaHandle* h = H(jh);
    if (h != nullptr) h->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeResetContext(JNIEnv*, jobject, jlong jh) {
    LlamaHandle* h = H(jh);
    if (h == nullptr) return;
    llama_memory_clear(llama_get_memory(h->ctx), true);
    h->cached.clear();
}

JNIEXPORT void JNICALL
Java_com_polylingua_edge_nativebridge_LlamaNative_nativeFree(JNIEnv*, jobject, jlong jh) {
    LlamaHandle* h = H(jh);
    if (h == nullptr) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

}  // extern "C"
