// JNI bridge between WhisperNative.kt and whisper.cpp
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "DicioWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define JNI_FN(name) Java_org_stypox_dicio_io_input_whisper_WhisperNative_##name

extern "C" {

JNIEXPORT jlong JNICALL
JNI_FN(initContext)(JNIEnv *env, jobject /* thiz */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
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
                   jstring language, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const jsize length = env->GetArrayLength(samples);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;  // voice commands are short
    params.suppress_blank = true;
    params.suppress_nst = true;    // no "[music]"-like non-speech tokens
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;

    std::string text;
    const int result = whisper_full(ctx, params, data, length);
    if (result == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGW("whisper_full failed with code %d", result);
    }

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
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

} // extern "C"
