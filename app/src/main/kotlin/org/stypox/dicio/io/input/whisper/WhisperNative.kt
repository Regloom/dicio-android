package org.stypox.dicio.io.input.whisper

import android.util.Log
import java.io.File

/**
 * Thin JNI wrapper around whisper.cpp (see `app/src/main/cpp/whisper_jni.cpp`). The native library
 * is only built for arm64-v8a, so [load] returns `false` on other ABIs. A whisper context must
 * not be used from more than one thread at a time.
 */
internal object WhisperNative {
    private val TAG = WhisperNative::class.simpleName

    @Volatile
    private var loaded: Boolean? = null

    /**
     * Loads the JNI library and the best ggml CPU backend variant for this phone.
     * @param nativeLibDir the app's native library directory, where ggml looks for the CPU
     * backend variants (the libraries must be extracted, see useLegacyPackaging in build.gradle)
     * @return false on ABIs without whisper.cpp (it is only built for arm64-v8a)
     */
    @Synchronized
    fun load(nativeLibDir: String): Boolean {
        loaded?.let { return it }
        val ok = try {
            System.loadLibrary("dicio_whisper")
            loadBackends(nativeLibDir)
            Log.i(TAG, "whisper.cpp loaded: ${systemInfo()}")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "whisper.cpp native library is not available on this device", e)
            false
        }
        loaded = ok
        return ok
    }

    private external fun loadBackends(nativeLibDir: String)

    /** @return a pointer to the whisper context, or 0 if the model could not be loaded */
    external fun initContext(modelPath: String): Long

    external fun freeContext(contextPtr: Long)

    /**
     * @param samples mono 16kHz PCM audio, in the range [-1, 1]
     * @param language ISO 639-1 code (e.g. "ru"), or "auto"
     * @param prompt text shown to the decoder before the audio (e.g. example commands), or ""
     * @return the UTF-8 encoded transcription, or `null` on error
     */
    external fun transcribe(
        contextPtr: Long,
        samples: FloatArray,
        language: String,
        prompt: String,
        threads: Int,
    ): ByteArray?

    external fun systemInfo(): String

    /** @return a pointer to a Silero VAD context, or 0 if the model could not be loaded */
    external fun vadInit(modelPath: String): Long

    external fun vadFree(vadPtr: Long)

    /** Clears the VAD's recurrent state, call it before each utterance */
    external fun vadReset(vadPtr: Long)

    /**
     * @param samples a 512-sample (32 ms) window of 16 kHz audio in [-1, 1]
     * @return the probability that the window contains speech
     */
    external fun vadSpeechProbability(vadPtr: Long, samples: FloatArray): Float

    /**
     * The number of "big" cores, i.e. those whose maximum frequency is above the minimum among all
     * cores, since whisper.cpp only slows down when the little cores get some of the work.
     */
    val preferredThreadCount: Int by lazy {
        val frequencies = try {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
                ?.mapNotNull {
                    File(it, "cpufreq/cpuinfo_max_freq").takeIf(File::exists)
                        ?.readText()?.trim()?.toLongOrNull()
                }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read CPU frequencies", e)
            emptyList()
        }

        val minFrequency = frequencies.minOrNull()
        val bigCores = if (minFrequency == null) {
            Runtime.getRuntime().availableProcessors() / 2
        } else {
            frequencies.count { it > minFrequency }
        }
        bigCores.coerceIn(2, 8)
    }
}
