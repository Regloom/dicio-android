package org.stypox.dicio.io.input.whisper

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Speech to text with [whisper.cpp](https://github.com/ggml-org/whisper.cpp), fully offline.
 *
 * Whisper is not a streaming model: the microphone is recorded until the user stops talking (a
 * simple energy-based voice activity detector decides when), and only then the whole utterance is
 * transcribed. While transcribing, the state is [SttState.WaitingForResult].
 *
 * @param modelUrl the URL of a ggml whisper model (e.g. from huggingface.co/ggerganov/whisper.cpp),
 * or an empty string to use [DEFAULT_MODEL_URL]
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhisperInputDevice(
    appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val localeManager: LocaleManager,
    private val silencesBeforeStop: StateFlow<Int>,
    modelUrl: StateFlow<String>,
) : SttInputDevice {

    private val _uiState = MutableStateFlow<SttState>(SttState.NotInitialized)
    override val uiState: StateFlow<SttState> = _uiState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // all calls to whisper.cpp go through here, since a context is not thread safe
    private val nativeDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val filesDir: File = appContext.filesDir
    private val cacheDir: File = appContext.cacheDir
    private val modelFile: File get() = File(filesDir, "whisper-model.bin")

    private val lock = Any()
    private var currentModelUrl: String = modelUrl.value.ifBlank { DEFAULT_MODEL_URL }
    private var job: Job? = null // download, load or listen job
    private var contextPtr = 0L // only accessed from nativeDispatcher
    private var thenStartListening: ((InputEvent) -> Unit)? = null
    private var eventListener: ((InputEvent) -> Unit)? = null
    @Volatile private var finishRequested = false
    @Volatile private var cancelRequested = false

    init {
        _uiState.value = initialState()
        scope.launch {
            modelUrl.drop(1).distinctUntilChanged().collect { url ->
                reinit(url.ifBlank { DEFAULT_MODEL_URL })
            }
        }
    }

    private fun initialState(): SttState {
        return when {
            !WhisperNative.isAvailable -> SttState.NotAvailable
            FileToDownload(currentModelUrl, modelFile).needsToBeDownloaded() -> SttState.NotDownloaded
            else -> SttState.NotLoaded
        }
    }

    private suspend fun reinit(newModelUrl: String) {
        synchronized(lock) {
            if (newModelUrl == currentModelUrl) return
            currentModelUrl = newModelUrl
        }
        cancelRequested = true
        job?.cancelAndJoin()
        freeModel()
        _uiState.value = initialState()
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        synchronized(lock) {
            return when (_uiState.value) {
                SttState.NotLoaded, is SttState.ErrorLoading -> {
                    load(thenStartListeningEventListener)
                    true
                }
                SttState.Loaded -> {
                    thenStartListeningEventListener?.let { startListening(it) }
                    thenStartListeningEventListener != null
                }
                is SttState.Loading -> {
                    if (thenStartListeningEventListener != null) {
                        thenStartListening = thenStartListeningEventListener
                        _uiState.value = SttState.Loading(true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        synchronized(lock) {
            when (val s = _uiState.value) {
                SttState.NotDownloaded, is SttState.ErrorDownloading -> download()
                SttState.NotLoaded, is SttState.ErrorLoading -> load(eventListener)
                is SttState.Loading -> {
                    // toggle whether to start listening once loading finishes
                    thenStartListening = if (s.thenStartListening) null else eventListener
                    _uiState.value = SttState.Loading(!s.thenStartListening)
                }
                SttState.Loaded -> startListening(eventListener)
                // the user is done talking: stop recording and transcribe right away
                SttState.Listening -> finishRequested = true
                else -> {} // downloading, transcribing, not available...
            }
        }
    }

    override fun stopListening() {
        if (_uiState.value == SttState.Listening) {
            cancelRequested = true
        }
    }

    private fun download() {
        val url = currentModelUrl
        _uiState.value = SttState.Downloading(Progress.UNKNOWN)
        job = scope.launch(Dispatchers.IO) {
            try {
                downloadBinaryFilesWithPartial(
                    urlsFiles = listOf(FileToDownload(url, modelFile)),
                    httpClient = okHttpClient,
                    cacheDir = cacheDir,
                ) { progress ->
                    _uiState.value = SttState.Downloading(progress)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Can't download whisper model $url", e)
                _uiState.value = SttState.ErrorDownloading(e)
                return@launch
            }
            synchronized(lock) {
                _uiState.value = SttState.NotLoaded
                load(null)
            }
        }
    }

    private fun load(thenStartListeningEventListener: ((InputEvent) -> Unit)?) {
        thenStartListening = thenStartListeningEventListener
        _uiState.value = SttState.Loading(thenStartListeningEventListener != null)
        job = scope.launch(nativeDispatcher) {
            if (contextPtr != 0L) {
                WhisperNative.freeContext(contextPtr)
            }
            contextPtr = WhisperNative.initContext(modelFile.absolutePath)
            synchronized(lock) {
                if (contextPtr == 0L) {
                    _uiState.value = SttState.ErrorLoading(
                        IOException("Can't load whisper model ${modelFile.name}")
                    )
                    return@launch
                }
                _uiState.value = SttState.Loaded
                thenStartListening?.let { startListening(it) }
                thenStartListening = null
            }
        }
    }

    private fun startListening(listener: (InputEvent) -> Unit) {
        eventListener = listener
        finishRequested = false
        cancelRequested = false
        _uiState.value = SttState.Listening

        job = scope.launch(Dispatchers.IO) {
            val event = try {
                listenAndTranscribe()
            } catch (e: Throwable) {
                Log.e(TAG, "Whisper speech recognition failed", e)
                InputEvent.Error(e)
            }
            synchronized(lock) {
                if (_uiState.value == SttState.Listening ||
                    _uiState.value == SttState.WaitingForResult) {
                    _uiState.value = SttState.Loaded
                }
                eventListener = null
            }
            listener(event)
        }
    }

    private suspend fun listenAndTranscribe(): InputEvent {
        val samples = record() ?: return InputEvent.None
        _uiState.value = SttState.WaitingForResult

        val language = localeManager.locale.value.language.takeIf { it.isNotBlank() } ?: "auto"
        val startTime = System.currentTimeMillis()
        val bytes = withContext(nativeDispatcher) {
            if (contextPtr == 0L) {
                throw IOException("Whisper model is not loaded")
            }
            WhisperNative.transcribe(
                contextPtr, samples, language, WhisperNative.preferredThreadCount
            )
        } ?: throw IOException("Whisper could not transcribe the audio")

        val raw = String(bytes, Charsets.UTF_8)
        Log.d(TAG, "Transcribed ${samples.size / SAMPLE_RATE.toFloat()}s of audio in " +
                "${System.currentTimeMillis() - startTime}ms: \"$raw\"")
        val text = cleanTranscription(raw)
        return if (text.isEmpty()) InputEvent.None else InputEvent.Final(listOf(Pair(text, 1.0f)))
    }

    /**
     * Records from the microphone until the user stops talking.
     *
     * @return the recorded audio, or `null` if the user did not say anything (or listening was
     * cancelled)
     */
    @SuppressLint("MissingPermission") // the UI layer only starts listening with the permission
    private fun record(): FloatArray? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBufferSize, SAMPLE_RATE), // half a second of 16-bit audio
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Can't open the microphone")
        }

        val noSpeechTimeoutMs = silencesBeforeStop.value * NO_SPEECH_TIMEOUT_PER_SILENCE_MS
        val frame = ShortArray(FRAME_SAMPLES)
        var audio = ShortArray(SAMPLE_RATE * 10)
        var audioLength = 0
        var elapsedMs = 0
        var noiseFloor = 0.0
        var speechFrames = 0
        var silenceAfterSpeechMs = 0

        recorder.startRecording()
        try {
            while (!cancelRequested && !finishRequested) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) {
                    throw IOException("Can't read from the microphone: $read")
                }
                if (audioLength + read > audio.size) {
                    audio = audio.copyOf(audio.size * 2)
                }
                frame.copyInto(audio, audioLength, 0, read)
                audioLength += read
                elapsedMs += read * 1000 / SAMPLE_RATE

                var sum = 0.0
                for (i in 0 until read) {
                    sum += frame[i].toDouble() * frame[i]
                }
                val rms = sqrt(sum / read)

                if (elapsedMs <= CALIBRATION_MS) {
                    // the first few frames measure the background noise level
                    noiseFloor = if (noiseFloor == 0.0) rms else (noiseFloor + rms) / 2
                    continue
                }

                if (rms > max(noiseFloor * SPEECH_TO_NOISE_RATIO, MIN_SPEECH_RMS)) {
                    speechFrames += 1
                    silenceAfterSpeechMs = 0
                } else {
                    // slowly adapt to changes in background noise
                    noiseFloor = noiseFloor * 0.95 + rms * 0.05
                    if (speechFrames >= MIN_SPEECH_FRAMES) {
                        silenceAfterSpeechMs += read * 1000 / SAMPLE_RATE
                    }
                }

                if (speechFrames >= MIN_SPEECH_FRAMES) {
                    if (silenceAfterSpeechMs >= END_OF_SPEECH_SILENCE_MS ||
                        elapsedMs >= MAX_RECORDING_MS) {
                        break
                    }
                } else if (elapsedMs >= noSpeechTimeoutMs) {
                    return null // the user did not say anything
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        if (cancelRequested || speechFrames < MIN_SPEECH_FRAMES) {
            return null
        }

        // whisper.cpp refuses inputs shorter than one second, so pad with silence
        val samples = FloatArray(max(audioLength, SAMPLE_RATE + SAMPLE_RATE / 10))
        for (i in 0 until audioLength) {
            samples[i] = audio[i] / 32768.0f
        }
        return samples
    }

    private suspend fun freeModel() {
        withContext(nativeDispatcher) {
            if (contextPtr != 0L) {
                WhisperNative.freeContext(contextPtr)
                contextPtr = 0L
            }
        }
    }

    override suspend fun destroy() {
        cancelRequested = true
        job?.cancelAndJoin()
        freeModel()
        scope.cancel()
    }

    companion object {
        private val TAG = WhisperInputDevice::class.simpleName

        /** Multilingual Whisper small, 5-bit quantized (~190MB) */
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"

        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = SAMPLE_RATE * 30 / 1000 // 30ms
        private const val CALIBRATION_MS = 240
        private const val SPEECH_TO_NOISE_RATIO = 3.0
        private const val MIN_SPEECH_RMS = 300.0
        private const val MIN_SPEECH_FRAMES = 5 // 150ms
        private const val END_OF_SPEECH_SILENCE_MS = 900
        private const val NO_SPEECH_TIMEOUT_PER_SILENCE_MS = 3000
        private const val MAX_RECORDING_MS = 20000

        /**
         * Phrases that Whisper is known to hallucinate on noise, since it was trained on subtitles.
         */
        private val HALLUCINATIONS = listOf(
            "субтитр", "продолжение следует", "спасибо за просмотр", "подписывайтесь на канал",
            "thanks for watching", "thank you for watching", "subtitles by",
        )

        internal fun cleanTranscription(raw: String): String {
            val text = raw
                .replace(Regex("\\[[^]]*]|\\([^)]*\\)|\\*[^*]*\\*"), " ") // [музыка], (смех)...
                .replace(Regex("[\"«»“”„.,!?;:…]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase()
            return if (HALLUCINATIONS.any { text.contains(it) }) "" else text
        }
    }
}
