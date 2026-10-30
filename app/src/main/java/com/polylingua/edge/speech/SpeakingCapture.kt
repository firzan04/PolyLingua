package com.polylingua.edge.speech

import com.polylingua.edge.PolyLinguaForegroundService
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.dsp.OboeAudioRecorder
import com.polylingua.edge.dsp.TarsosPitchAndProsodyEngine
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Everything recorded for one spoken response. */
class CapturedResponse(
    val transcript: FinalTranscript,
    val pitch: List<PitchFrame>,
    val audio: ShortArray,
    val durationSec: Double,
)

data class LiveCaptureState(
    val transcript: LiveTranscript = LiveTranscript(),
    /** Recent F0 values in Hz (0 = unvoiced), oldest first, ~16 ms apart. */
    val f0Recent: FloatArray = FloatArray(0),
    val elapsedSec: Double = 0.0,
    val liveWpm: Double = 0.0,
    val levelDb: Float = -90f,
    val backend: AsrBackend = AsrBackend.NONE,
)

/**
 * Orchestrates one spoken response: microphone (Oboe/AudioRecord) -> streaming ASR + VAD on the
 * big cores, live YIN pitch tracking on the little cores, and the full-take accumulator for WAV
 * export, GOP and the Moonshine second pass.
 */
@Singleton
class SpeakingCaptureController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: OboeAudioRecorder,
    private val asr: SpeechRecognitionEngine,
    private val settings: AppSettings,
) {
    inner class ActiveCapture internal constructor(
        private val scope: CoroutineScope,
        private val session: RecognitionSession,
    ) {
        private val _live = MutableStateFlow(LiveCaptureState(backend = asr.backend()))
        val live: StateFlow<LiveCaptureState> = _live
        private val pitchEngine = TarsosPitchAndProsodyEngine()
        private val rawPitch = ArrayList<PitchFrame>(8000)
        private val startNs = System.nanoTime()
        private var pitchJob: Job? = null
        private var uiJob: Job? = null
        private val f0Window = ArrayDeque<Float>()

        internal fun start() {
            if (session.usesAppRecorder) {
                pitchJob = scope.launch(CoreExecutors.dsp) {
                    recorder.frames.collect { chunk ->
                        val frames = pitchEngine.push(chunk, chunk.size)
                        if (frames.isNotEmpty()) synchronized(rawPitch) {
                            rawPitch += frames
                            for (f in frames) {
                                f0Window.addLast(f.f0Hz)
                                if (f0Window.size > F0_WINDOW) f0Window.removeFirst()
                            }
                        }
                    }
                }
            }
            uiJob = scope.launch {
                while (isActive) {
                    val t = session.live.value
                    val elapsed = (System.nanoTime() - startNs) / 1e9
                    val words = t.words.size
                    val firstWord = t.words.firstOrNull()?.startSec ?: elapsed
                    val minutes = ((elapsed - firstWord).coerceAtLeast(1.0)) / 60.0
                    val f0 = synchronized(rawPitch) { f0Window.toFloatArray() }
                    _live.value = LiveCaptureState(
                        transcript = t,
                        f0Recent = f0,
                        elapsedSec = elapsed,
                        liveWpm = if (words >= 3) words / minutes else 0.0,
                        levelDb = recorder.levelDb.value,
                        backend = _live.value.backend,
                    )
                    delay(66)
                }
            }
        }

        /** Stops capture and returns the full response for assessment. */
        suspend fun end(stopMicrophone: Boolean = true): CapturedResponse {
            uiJob?.cancel()
            val transcript = session.finish()
            pitchJob?.cancelAndJoin()
            if (stopMicrophone && session.usesAppRecorder) recorder.stop()
            PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.RECORDING)
            val pitch = withContext(CoreExecutors.dsp) {
                synchronized(rawPitch) { TarsosPitchAndProsodyEngine.postProcess(rawPitch.toList()) }
            }
            val duration = if (transcript.audio.isNotEmpty()) transcript.audio.size / 16000.0 else transcript.durationSec
            return CapturedResponse(transcript, pitch, transcript.audio, duration)
        }

        fun abort() {
            uiJob?.cancel()
            pitchJob?.cancel()
            session.cancel()
            if (session.usesAppRecorder) recorder.stop()
            PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.RECORDING)
        }
    }

    val backend: AsrBackend get() = asr.backend()

    /**
     * Starts a capture. Returns null when no recogniser is available or the microphone failed.
     * [detail] is shown in the foreground-service notification.
     */
    suspend fun begin(scope: CoroutineScope, detail: String, conversation: Boolean = false): ActiveCapture? {
        val s = settings.settings.value
        if (asr.backend() == AsrBackend.SHERPA_OFFLINE) {
            // Load the models before opening the mic so the first words are not lost.
            asr.prepare()
            if (!recorder.start(s.sidetoneEnabled, s.sidetoneGain)) return null
        }
        PolyLinguaForegroundService.acquire(context, PolyLinguaForegroundService.Reason.RECORDING, detail)
        val session = asr.startSession(scope, conversation)
        if (session == null) {
            recorder.stop()
            PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.RECORDING)
            return null
        }
        return ActiveCapture(scope, session).also { it.start() }
    }

    companion object {
        const val F0_WINDOW = 320
    }
}
