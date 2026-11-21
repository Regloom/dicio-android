// JNI bridge between WhisperNative.kt and whisper.cpp
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"
#include "ggml-backend.h"

#define TAG "DicioWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define JNI_FN(name) Java_org_stypox_dicio_io_input_whisper_WhisperNative_##name

static void log_callback(ggml_log_level level, const char *text, void * /* user_data */) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, TAG, text);
}

extern "C" {

JNIEXPORT void JNICALL
JNI_FN(loadBackends)(JNIEnv *env, jobject /* thiz */, jstring nativeLibDir) {
    // the ggml CPU backend is built in several variants (libggml-cpu-android_armv*.so); ggml scores
    // the CPU features and loads the best one available in the app's native library directory
    const char *dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(nativeLibDir, dir);
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("ggml backend: %s (%s)", ggml_backend_dev_name(dev),
             ggml_backend_dev_description(dev));
    }
}

JNIEXPORT jlong JNICALL
JNI_FN(initContext)(JNIEnv *env, jobject /* thiz */, jstring modelPath) {
    whisper_log_set(log_callback, nullptr);
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    // the state is created anew for each transcription, see transcribe()
    whisper_context *ctx = whisper_init_from_file_with_params_no_state(path, cparams);
    if (ctx == nullptr) {
        LOGW("Could not load model %s", path);
    }
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
JNI_FN(freeContext)(JNIEnv * /* env */, jobject /* thiz */, jlong contextPtr) {
    whisper_free(reinterpret_cast<whisper_context *>(contextPtr));
}

JNIEXPORT jbyteArray JNICALL
JNI_FN(transcribe)(JNIEnv *env, jobject /* thiz */, jlong contextPtr, jfloatArray samples,
                   jstring language, jstring prompt, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const jsize length = env->GetArrayLength(samples);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang;
    // examples of typical commands: biases the decoder towards the words they contain
    params.initial_prompt = promptChars[0] == '\0' ? nullptr : promptChars;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;  // voice commands are short
    params.suppress_blank = true;
    params.suppress_nst = true;    // no "[music]"-like non-speech tokens
    // Whisper always encodes a 30s window (1500 audio frames, 50 per second) by default, even for a
    // 2s command; limiting the encoder context to the actual audio length (plus some margin) makes
    // short utterances several times faster, as in whisper.cpp's stream example
    const int audioCtx = static_cast<int>(length / 16000.0 * 50.0) + 128;
    params.audio_ctx = audioCtx < 1500 ? audioCtx : 0;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;

    // A fresh state for every utterance: reusing one state across calls with a different
    // audio_ctx corrupts the following transcriptions (e.g. "Который час" -> "Катерина, которые
    // час" for the very same audio). Creating a state takes a few tens of ms, the model weights
    // stay loaded in the context.
    std::string text;
    int result = -1;
    whisper_state *state = whisper_init_state(ctx);
    if (state == nullptr) {
        LOGW("Could not create whisper state");
    } else {
        const int64_t start = ggml_time_us();
        result = whisper_full_with_state(ctx, state, params, data, length);
        LOGI("whisper_full: %d samples, audio_ctx %d, %d threads, %lld ms",
             length, params.audio_ctx, threads, (long long) ((ggml_time_us() - start) / 1000));
        if (result == 0) {
            const int segments = whisper_full_n_segments_from_state(state);
            for (int i = 0; i < segments; ++i) {
                text += whisper_full_get_segment_text_from_state(state, i);
            }
        } else {
            LOGW("whisper_full failed with code %d", result);
        }
        whisper_free_state(state);
    }

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(prompt, promptChars);
    if (result != 0) {
        return nullptr;
    }
    // return raw bytes: NewStringUTF() expects modified UTF-8 and aborts on invalid sequences
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    return bytes;
}

JNIEXPORT jstring JNICALL
JNI_FN(systemInfo)(JNIEnv *env, jobject /* thiz */) {
    return env->NewStringUTF(whisper_print_system_info());
}

// ---- Silero voice activity detection, streaming: one 512-sample window (32 ms) at a time ----

JNIEXPORT jlong JNICALL
JNI_FN(vadInit)(JNIEnv *env, jobject /* thiz */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = 1;
    params.use_gpu = false;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, params);
    if (vctx == nullptr) {
        LOGW("Could not load VAD model %s", path);
    }
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(vctx);
}

JNIEXPORT void JNICALL
JNI_FN(vadFree)(JNIEnv * /* env */, jobject /* thiz */, jlong vadPtr) {
    whisper_vad_free(reinterpret_cast<whisper_vad_context *>(vadPtr));
}

JNIEXPORT void JNICALL
JNI_FN(vadReset)(JNIEnv * /* env */, jobject /* thiz */, jlong vadPtr) {
    whisper_vad_reset_state(reinterpret_cast<whisper_vad_context *>(vadPtr));
}

/** @return the speech probability (0..1) of the samples, keeping the LSTM state across calls */
JNIEXPORT jfloat JNICALL
JNI_FN(vadSpeechProbability)(JNIEnv *env, jobject /* thiz */, jlong vadPtr, jfloatArray samples) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(vadPtr);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const jsize length = env->GetArrayLength(samples);
    float prob = 0.0f;
    if (whisper_vad_detect_speech_no_reset(vctx, data, length)) {
        const int n = whisper_vad_n_probs(vctx);
        const float *probs = whisper_vad_probs(vctx);
        for (int i = 0; i < n; ++i) {
            if (probs[i] > prob) prob = probs[i];
        }
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return prob;
}

} // extern "C"
