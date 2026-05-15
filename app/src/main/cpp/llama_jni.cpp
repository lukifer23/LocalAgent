#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <random>
#include <memory>
#include <string>
#include <vector>
#include <functional>

#include <android/log.h>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

void log_info(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, "LocalAgentLlama", "%s", msg);
}

void log_err(const char* msg) {
    __android_log_print(ANDROID_LOG_ERROR, "LocalAgentLlama", "%s", msg);
}

void llama_log_callback(enum ggml_log_level level, const char* text, void* user_data) {
    (void)user_data;
    int android_level = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) android_level = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) android_level = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) android_level = ANDROID_LOG_DEBUG;
    
    __android_log_print(android_level, "LlamaCpp", "%s", text);
}

thread_local std::mt19937 g_rng{std::random_device{}()};

struct LlamaBundle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
    mtmd_context* mtmd_ctx = nullptr;
    int n_ctx = 0;
    llama_pos n_past = 0;
};

bool complete_internal(LlamaBundle* b, const std::string& prompt, 
                       const uint32_t* image_pixels, int img_w, int img_h,
                       int max_new_tokens, bool add_bos,
                       const std::function<void(const std::string&)>& on_token,
                       std::string* out_err, std::string* out_text) {
    if (!b || !b->model || !b->ctx || !b->sampler || !b->vocab) {
        *out_err = "uninitialized";
        return false;
    }

    const llama_vocab* vocab = b->vocab;
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();

    if (image_pixels && b->mtmd_ctx) {
        // Convert ARGB to RGB
        std::vector<uint8_t> rgb(static_cast<size_t>(img_w * img_h * 3));
        for (int i = 0; i < img_w * img_h; ++i) {
            uint32_t p = image_pixels[i];
            rgb[i * 3 + 0] = (p >> 16) & 0xFF; // R
            rgb[i * 3 + 1] = (p >> 8) & 0xFF;  // G
            rgb[i * 3 + 2] = p & 0xFF;         // B
        }

        mtmd_bitmap* bitmap = mtmd_bitmap_init(img_w, img_h, rgb.data());
        const mtmd_bitmap* bitmaps[1] = { bitmap };

        std::string final_prompt = prompt;
        const std::string marker = mtmd_default_marker();
        if (final_prompt.find(marker) == std::string::npos) {
            final_prompt = marker + "\n" + final_prompt;
        }

        mtmd_input_text text_in;
        text_in.text = final_prompt.c_str();
        text_in.add_special = add_bos;
        text_in.parse_special = true;

        if (mtmd_tokenize(b->mtmd_ctx, chunks, &text_in, bitmaps, 1) != 0) {
            *out_err = "multimodal tokenize failed";
            mtmd_bitmap_free(bitmap);
            mtmd_input_chunks_free(chunks);
            return false;
        }
        mtmd_bitmap_free(bitmap);

        llama_pos new_n_past = 0;
        if (mtmd_helper_eval_chunks(b->mtmd_ctx, b->ctx, chunks, b->n_past, 0, 2048, true, &new_n_past) != 0) {
            *out_err = "multimodal eval failed";
            mtmd_input_chunks_free(chunks);
            return false;
        }
        b->n_past = new_n_past;
    } else {
        const int n_prompt_raw = -llama_tokenize(vocab, prompt.c_str(), static_cast<int>(prompt.size()), nullptr, 0, add_bos, true);
        if (n_prompt_raw <= 0) {
            *out_err = "tokenize sizing failed";
            mtmd_input_chunks_free(chunks);
            return false;
        }

        std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt_raw));
        if (llama_tokenize(vocab, prompt.c_str(), static_cast<int>(prompt.size()), prompt_tokens.data(),
                           static_cast<int>(prompt_tokens.size()), add_bos, true) < 0) {
            *out_err = "tokenize failed";
            mtmd_input_chunks_free(chunks);
            return false;
        }

        llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));
        if (llama_decode(b->ctx, batch)) {
            *out_err = "text decode failed";
            mtmd_input_chunks_free(chunks);
            return false;
        }
        b->n_past += static_cast<llama_pos>(prompt_tokens.size());
    }

    std::string acc;
    const int n_predict = std::max(1, max_new_tokens);

    for (int i = 0; i < n_predict; ++i) {
        const llama_token new_token_id = llama_sampler_sample(b->sampler, b->ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token_id)) break;

        char buf[256];
        const int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        std::string piece(buf, static_cast<size_t>(n));
        acc.append(piece);
        if (on_token) on_token(piece);

        llama_token mutable_last = new_token_id;
        llama_batch batch = llama_batch_get_one(&mutable_last, 1);
        if (llama_decode(b->ctx, batch)) {
            log_err("decode failed in loop");
            break;
        }
        b->n_past++;
    }

    mtmd_input_chunks_free(chunks);
    *out_text = std::move(acc);
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    ggml_backend_load_all();
    llama_log_set(llama_log_callback, nullptr);
    log_info("JNI_OnLoad: backends loaded and logging initialized");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localagent_llm_LlamaNative_nativeLoad(JNIEnv* env, jclass, jstring jPath, jint nGpuLayers, jint nCtx, jint nThreads) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return 0;

    log_info(("nativeLoad: path=" + std::string(path) + " gpu=" + std::to_string(nGpuLayers)).c_str());

    auto* bundle = new LlamaBundle();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;

    bundle->model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);

    if (!bundle->model) {
        delete bundle;
        return 0;
    }

    bundle->vocab = llama_model_get_vocab(bundle->model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx);
    cparams.n_threads = std::max(1, static_cast<int>(nThreads));
    bundle->ctx = llama_init_from_model(bundle->model, cparams);
    bundle->n_ctx = static_cast<int>(nCtx);
    bundle->n_past = 0;

    log_info("nativeLoad: success");
    return reinterpret_cast<jlong>(bundle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localagent_llm_LlamaNative_nativeLoadVision(JNIEnv* env, jclass, jlong handle, jstring jMmprojPath) {
    if (handle == 0) return JNI_FALSE;
    auto* bundle = reinterpret_cast<LlamaBundle*>(handle);
    const char* path = env->GetStringUTFChars(jMmprojPath, nullptr);
    if (!path) return JNI_FALSE;

    log_info(("nativeLoadVision: path=" + std::string(path)).c_str());

    if (bundle->mtmd_ctx) mtmd_free(bundle->mtmd_ctx);
    struct mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = true;
    bundle->mtmd_ctx = mtmd_init_from_file(path, bundle->model, mparams);
    env->ReleaseStringUTFChars(jMmprojPath, path);

    bool ok = (bundle->mtmd_ctx != nullptr);
    log_info(ok ? "nativeLoadVision: success" : "nativeLoadVision: failed");
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localagent_llm_LlamaNative_nativeUnload(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    auto* bundle = reinterpret_cast<LlamaBundle*>(handle);
    if (bundle->sampler) llama_sampler_free(bundle->sampler);
    if (bundle->mtmd_ctx) mtmd_free(bundle->mtmd_ctx);
    if (bundle->ctx) llama_free(bundle->ctx);
    if (bundle->model) llama_model_free(bundle->model);
    delete bundle;
    log_info("nativeUnload: success");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localagent_llm_LlamaNative_nativeComplete(JNIEnv* env, jclass, jlong handle, jstring jPrompt, jintArray jPixels,
                                                   jint imgW, jint imgH, jint maxNewTokens, jboolean addBos, jfloat temperature, jfloat topP) {
    if (handle == 0) return env->NewStringUTF("");
    auto* bundle = reinterpret_cast<LlamaBundle*>(handle);
    const char* prompt_utf = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string prompt(prompt_utf);
    env->ReleaseStringUTFChars(jPrompt, prompt_utf);

    uint32_t* pixels = nullptr;
    if (jPixels) pixels = reinterpret_cast<uint32_t*>(env->GetIntArrayElements(jPixels, nullptr));

    if (bundle->sampler) {
        llama_sampler_free(bundle->sampler);
        bundle->sampler = nullptr;
    }
    auto chain_params = llama_sampler_chain_default_params();
    bundle->sampler = llama_sampler_chain_init(chain_params);
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_dist(static_cast<uint32_t>(g_rng())));

    std::string err, text;
    complete_internal(bundle, prompt, pixels, imgW, imgH, maxNewTokens, addBos == JNI_TRUE, nullptr, &err, &text);

    if (jPixels) env->ReleaseIntArrayElements(jPixels, reinterpret_cast<jint*>(pixels), JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localagent_llm_LlamaNative_nativeStream(JNIEnv* env, jclass, jlong handle, jstring jPrompt, jintArray jPixels,
                                                 jint imgW, jint imgH, jint maxNewTokens, jboolean addBos, jfloat temperature, jfloat topP, jobject onToken) {
    if (handle == 0) return env->NewStringUTF("");
    auto* bundle = reinterpret_cast<LlamaBundle*>(handle);
    const char* prompt_utf = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string prompt(prompt_utf);
    env->ReleaseStringUTFChars(jPrompt, prompt_utf);

    uint32_t* pixels = nullptr;
    if (jPixels) pixels = reinterpret_cast<uint32_t*>(env->GetIntArrayElements(jPixels, nullptr));

    if (bundle->sampler) {
        llama_sampler_free(bundle->sampler);
        bundle->sampler = nullptr;
    }
    auto chain_params = llama_sampler_chain_default_params();
    bundle->sampler = llama_sampler_chain_init(chain_params);
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(bundle->sampler, llama_sampler_init_dist(static_cast<uint32_t>(g_rng())));

    jclass callback_class = env->GetObjectClass(onToken);
    jmethodID invoke_method = env->GetMethodID(callback_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    auto token_callback = [&](const std::string& piece) {
        jstring jpiece = env->NewStringUTF(piece.c_str());
        env->CallObjectMethod(onToken, invoke_method, jpiece);
        env->DeleteLocalRef(jpiece);
    };

    std::string err, text;
    complete_internal(bundle, prompt, pixels, imgW, imgH, maxNewTokens, addBos == JNI_TRUE, token_callback, &err, &text);

    if (jPixels) env->ReleaseIntArrayElements(jPixels, reinterpret_cast<jint*>(pixels), JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}
