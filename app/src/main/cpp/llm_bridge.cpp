// JNI bridge between Kotlin and llama.cpp.
//
// Design notes for battery and memory:
//  * The GGUF is memory-mapped, so weights are only paged in when touched and
//    the kernel reclaims them as soon as the engine is unloaded.
//  * One context is reused across requests; the KV cache is cleared instead of
//    reallocating.  n_outputs_max = 1 keeps the logit buffer at a single row
//    (1 MiB instead of hundreds of MiB for a 262k vocabulary).
//  * Generation is greedy and grammar-constrained, so there are never retries.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "morrow.llm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> tokens;
    std::atomic<bool> stop{false};
    std::mutex mu;
    int n_batch = 128;
};

void android_log_callback(enum ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", text);
    }
}

jmethodID token_sink_method(JNIEnv * env, jobject sink) {
    jclass cls = env->GetObjectClass(sink);
    jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    return mid;
}

std::string jstring_to_utf8(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_morrow_llm_Llama_nativeInit(JNIEnv *, jobject) {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(android_log_callback, nullptr);
        llama_backend_init();
    });
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_morrow_llm_Llama_nativeLoad(JNIEnv * env, jobject, jstring path, jint threads, jint n_ctx, jint n_batch) {
    auto * e = new Engine();
    e->n_batch = n_batch > 0 ? n_batch : 128;

    std::string model_path = jstring_to_utf8(env, path);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;          // CPU only: no GPU driver variance, no extra driver wakeups
    mp.use_extra_bufts = false;

    e->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (e->model == nullptr) {
        LOGW("failed to load model at %s", model_path.c_str());
        delete e;
        return 0;
    }
    e->vocab = llama_model_get_vocab(e->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx > 0 ? (uint32_t) n_ctx : 1024u;
    cp.n_batch = (uint32_t) e->n_batch;
    cp.n_ubatch = (uint32_t) e->n_batch;
    cp.n_threads = std::max(1, threads / 2);   // decode is memory bound; keep it quiet
    cp.n_threads_batch = threads;               // prefill is compute bound
    cp.n_outputs_max = 1;
    cp.n_outputs_max_per_seq = 1;
    cp.no_perf = true;

    e->ctx = llama_init_from_model(e->model, cp);
    if (e->ctx == nullptr) {
        llama_model_free(e->model);
        delete e;
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_dev_morrow_llm_Llama_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return;
    std::lock_guard<std::mutex> lock(e->mu);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_dev_morrow_llm_Llama_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e) e->stop.store(true);
}

/**
 * Runs one constrained generation. Returns the generated text (special tokens
 * included, so the caller can parse <escape> markers). Tokens are streamed to
 * the sink as they are produced, which lets the UI show the first line early.
 */
JNIEXPORT jstring JNICALL
Java_dev_morrow_llm_Llama_nativeGenerate(JNIEnv * env, jobject, jlong handle,
                                         jstring prompt, jstring grammar,
                                         jint max_tokens, jobject sink, jlongArray stats) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(e->mu);
    e->stop.store(false);

    const std::string prompt_text = jstring_to_utf8(env, prompt);
    const std::string grammar_text = jstring_to_utf8(env, grammar);

    e->tokens.resize(prompt_text.size() + 8);
    int n = llama_tokenize(e->vocab, prompt_text.c_str(), (int) prompt_text.size(),
                           e->tokens.data(), (int) e->tokens.size(), true, true);
    if (n < 0) {
        e->tokens.resize(-n);
        n = llama_tokenize(e->vocab, prompt_text.c_str(), (int) prompt_text.size(),
                           e->tokens.data(), (int) e->tokens.size(), true, true);
    }
    if (n <= 0) {
        LOGW("tokenize failed (%d)", n);
        return nullptr;
    }
    e->tokens.resize(n);

    // Fresh KV cache for every request: no cross-request state, predictable latency.
    llama_memory_clear(llama_get_memory(e->ctx), true);

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(sp);
    if (!grammar_text.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(e->vocab, grammar_text.c_str(), "root");
        if (g == nullptr) {
            LOGW("invalid grammar");
            llama_sampler_free(chain);
            return nullptr;
        }
        llama_sampler_chain_add(chain, g);
    }
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    std::string out;
    const jmethodID sink_mid = sink ? token_sink_method(env, sink) : nullptr;

    const int64_t t_prefill_start = ggml_time_us();
    for (size_t pos = 0; pos < e->tokens.size(); pos += (size_t) e->n_batch) {
        size_t chunk = std::min((size_t) e->n_batch, e->tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(e->tokens.data() + pos, (int) chunk);
        if (llama_decode(e->ctx, batch) != 0) {
            llama_sampler_free(chain);
            return nullptr;
        }
    }

    const int64_t t_prefill_end = ggml_time_us();
    int n_generated = 0;

    char piece[256];
    for (int i = 0; i < max_tokens; ++i) {
        if (e->stop.load()) break;
        llama_token id = llama_sampler_sample(chain, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, id)) break;
        int len = llama_token_to_piece(e->vocab, id, piece, sizeof(piece), 0, true);
        if (len > 0) {
            out.append(piece, len);
            ++n_generated;
            if (sink_mid != nullptr) {
                jstring js = env->NewStringUTF(std::string(piece, len).c_str());
                env->CallVoidMethod(sink, sink_mid, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    break;
                }
            }
            if (out.find("<end_function_call>") != std::string::npos) break;
        }
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(e->ctx, batch) != 0) break;
    }

    llama_sampler_free(chain);

    if (stats != nullptr && env->GetArrayLength(stats) >= 4) {
        const int64_t t_end = ggml_time_us();
        jlong values[4] = {
            (jlong) e->tokens.size(),
            (jlong) n_generated,
            (jlong) ((t_prefill_end - t_prefill_start) / 1000),
            (jlong) ((t_end - t_prefill_end) / 1000),
        };
        env->SetLongArrayRegion(stats, 0, 4, values);
    }
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
