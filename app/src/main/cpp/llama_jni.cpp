#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <random>
#include <memory>
#include <string>
#include <vector>

#include <android/log.h>

#include "llama.h"

namespace {

void log_err(const char* msg) {
    __android_log_print(ANDROID_LOG_ERROR, "LocalAgentLlama", "%s", msg);
}

thread_local std::mt19937 g_rng{std::random_device{}()};

struct LlamaBundle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_ctx = 0;
};

bool complete_internal(LlamaBundle* b, const std::string& prompt, int max_new_tokens, bool add_bos,
                       const std::function<void(const std::string&)>& on_token,
                       std::string* out_err, std::string* out_text) {
    if (!b || !b->model || !b->ctx || !b->sampler || !b->vocab) {
        *out_err = "uninitialized";
        return false;
    }

    const llama_vocab* vocab = b->vocab;

    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), static_cast<int>(prompt.size()), nullptr, 0, add_bos, true);
    if (n_prompt <= 0) {
        *out_err = "tokenize sizing failed";
        return false;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int>(prompt.size()), prompt_tokens.data(),
                       static_cast<int>(prompt_tokens.size()), add_bos, true) < 0) {
        *out_err = "tokenize failed";
        return false;
    }

    const int n_predict = std::max(1, max_new_tokens);
    const int need_ctx = n_prompt + n_predict + 32;
    if (need_ctx > b->n_ctx) {
        *out_err = "context overflow";
        return false;
    }

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));

    if (llama_model_has_encoder(b->model)) {
        if (llama_encode(b->ctx, batch)) {
            *out_err = "encode failed";
            return false;
        }
        llama_token decoder_start_token_id = llama_model_decoder_start_token(b->model);
        if (decoder_start_token_id == LLAMA_TOKEN_NULL) {
            decoder_start_token_id = llama_vocab_bos(vocab);
        }
        batch = llama_batch_get_one(&decoder_start_token_id, 1);
    }

    std::string acc;
    for (int n_pos = 0; n_pos + batch.n_tokens < n_prompt + n_predict;) {
        if (llama_decode(b->ctx, batch)) {
            *out_err = "decode failed";
            return false;
        }
        n_pos += batch.n_tokens;

        const llama_token new_token_id = llama_sampler_sample(b->sampler, b->ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char buf[256];
        const int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n < 0) {
            *out_err = "detokenize failed";
            return false;
        }
        std::string piece(buf, static_cast<size_t>(n));
        acc.append(piece);
        if (on_token) {
            on_token(piece);
        }

        llama_token mutable_last = new_token_id;
        batch = llama_batch_get_one(&mutable_last, 1);
    }

    *out_text = std::move(acc);
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    ggml_backend_load_all();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localagent_llm_LlamaNative_nativeLoad(JNIEnv* env, jclass, jstring jPath, jint nGpuLayers, jint nCtx, jint nThreads) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) {
        return 0;
    }

    auto* bundle = new LlamaBundle();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    bundle->model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);

    if (!bundle->model) {
        delete bundle;
        log_err("model load failed");
        return 0;
    }

    bundle->vocab = llama_model_get_vocab(bundle->model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx);
    cparams.n_batch = std::min(static_cast<uint32_t>(2048), static_cast<uint32_t>(nCtx));
    cparams.n_threads = std::max(1, static_cast<int>(nThreads));
    cparams.n_threads_batch = cparams.n_threads;

    bundle->ctx = llama_init_from_model(bundle->model, cparams);
    if (!bundle->ctx) {
        llama_model_free(bundle->model);
        delete bundle;
        log_err("context init failed");
        return 0;
    }

    bundle->sampler = nullptr;

    bundle->n_ctx = static_cast<int>(nCtx);
    return reinterpret_cast<jlong>(bundle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localagent_llm_LlamaNative_nativeUnload(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) {
        return;
    }
    auto* bundle = reinterpret_cast<LlamaBundle*>(handle);
    if (bundle->sampler) {
        llama_sampler_free(bundle->sampler);
    }
    if (bundle->ctx) {
        llama_free(bundle->ctx);
    }
    if (bundle->model) {
        llama_model_free(bundle->model);
    }
    delete bundle;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localagent_llm_LlamaNative_nativeComplete(JNIEnv* env, jclass, jlong handle, jstring jPrompt, jint maxNewTokens,
                                                   jboolean addBos, jfloat temperature, jfloat topP) {
    if (handle == 0) {
        return env->NewStringUTF("");
    }
    auto* bundle = reinterpret_cast<LlamaBundle*>(handle);

    const char* prompt_utf = env->GetStringUTFChars(jPrompt, nullptr);
    if (!prompt_utf) {
        return env->NewStringUTF("");
    }
    const std::string prompt(prompt_utf);
    env->ReleaseStringUTFChars(jPrompt, prompt_utf);

    if (bundle->sampler) {
        llama_sampler_free(bundle->sampler);
        bundle->sampler = nullptr;
    }
    auto chain_params = llama_sampler_chain_default_params();
    bundle->sampler = llama_sampler_chain_init(chain_params);
    const float temp = std::max(0.0f, temperature);
    const float top_p = std::clamp(topP, 0.0f, 1.0f);
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(bundle->sampler,
                           llama_sampler_init_dist(static_cast<uint32_t>(g_rng())));

    std::string err;
    std::string text;
    const bool ok = complete_internal(bundle, prompt, std::max(1, static_cast<int>(maxNewTokens)), addBos == JNI_TRUE, nullptr, &err, &text);
    if (!ok) {
        log_err(err.c_str());
        return env->NewStringUTF(("ERROR: " + err).c_str());
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localagent_llm_LlamaNative_nativeStream(JNIEnv* env, jclass, jlong handle, jstring jPrompt, jint maxNewTokens,
                                                 jboolean addBos, jfloat temperature, jfloat topP, jobject onToken) {
    if (handle == 0) {
        return env->NewStringUTF("");
    }
    auto* bundle = reinterpret_cast<LlamaBundle*>(handle);

    const char* prompt_utf = env->GetStringUTFChars(jPrompt, nullptr);
    if (!prompt_utf) {
        return env->NewStringUTF("");
    }
    const std::string prompt(prompt_utf);
    env->ReleaseStringUTFChars(jPrompt, prompt_utf);

    if (bundle->sampler) {
        llama_sampler_free(bundle->sampler);
        bundle->sampler = nullptr;
    }
    auto chain_params = llama_sampler_chain_default_params();
    bundle->sampler = llama_sampler_chain_init(chain_params);
    const float temp = std::max(0.0f, temperature);
    const float top_p = std::clamp(topP, 0.0f, 1.0f);
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(bundle->sampler,
                           llama_sampler_init_dist(static_cast<uint32_t>(g_rng())));

    jclass callback_class = env->GetObjectClass(onToken);
    jmethodID invoke_method = env->GetMethodID(callback_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");

    auto token_callback = [&](const std::string& piece) {
        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallObjectMethod(onToken, invoke_method, jpiece);
        env->DeleteLocalRef(jpiece);
    };

    std::string err;
    std::string text;
    const bool ok = complete_internal(bundle, prompt, std::max(1, static_cast<int>(maxNewTokens)), addBos == JNI_TRUE, token_callback, &err, &text);
    if (!ok) {
        log_err(err.c_str());
        return env->NewStringUTF(("ERROR: " + err).c_str());
    }
    return env->NewStringUTF(text.c_str());
}
