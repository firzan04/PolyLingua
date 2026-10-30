package com.polylingua.edge.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.domain.model.SpeechSegment
import com.polylingua.edge.domain.model.WordTiming
import com.polylingua.edge.dsp.OboeAudioRecorder
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.nativebridge.NativeLoader
import com.polylingua.edge.nativebridge.SherpaNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Rolling recogniser output for live captions. Times are seconds from session start. */
data class LiveTranscript(
    val committed: String = "",
    val partial: String = "",
    val words: List<WordTiming> = emptyList(),
    val elapsedSec: Double = 0.0,
    val speaking: Boolean = false,
) {
    val text: String get() = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ")
}

/** Final recogniser output used for assessment. */
data class FinalTranscript(
    val text: String,
    val liveText: String,
    val words: List<WordTiming>,
    val speechSegments: List<SpeechSegment>,
    val durationSec: Double,
    val engine: String,
    val audio: ShortArray,
)

enum class AsrBackend(val label: String) {
    SHERPA_OFFLINE("On-device Zipformer (offline)"),
    ANDROID_SYSTEM("Android speech recogniser (limited)"),
    NONE("No speech recogniser installed"),
}

interface RecognitionSession {
    val live: StateFlow<LiveTranscript>

    /** Utterances closed by endpoint detection (conversation mode only). */
    val utterances: SharedFlow<String>
    val usesAppRecorder: Boolean
    suspend fun finish(): FinalTranscript
    fun cancel()
}

/**
 * Offline speech recognition: sherpa-onnx streaming Zipformer (live captions + token timestamps),
 * Silero VAD (speech segments for pause/phonation analysis), optional Moonshine second pass and
 * punctuation restoration. Falls back to the Android recogniser when no model pack is installed.
 */
@Singleton
class SpeechRecognitionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    private val recorder: OboeAudioRecorder,
    private val residency: ModelResidency,
) : ResidentModel {

    private val lock = Any()
    private var recognizer = 0L
    private var vad = 0L
    private var punct = 0L
    private var offline = 0L

    override val residencyKey = "asr"
    override val residentMb: Int
        get() = ModelCatalog.ASR_ZIPFORMER.residentMb + ModelCatalog.VAD_SILERO.residentMb +
            ModelCatalog.PUNCTUATION.residentMb + if (offline != 0L) ModelCatalog.ASR_MOONSHINE.residentMb else 0

    fun backend(): AsrBackend = when {
        models.isInstalled(ModelCatalog.ASR_ZIPFORMER) && NativeLoader.ensureLoaded() -> AsrBackend.SHERPA_OFFLINE
        SpeechRecognizer.isRecognitionAvailable(context) -> AsrBackend.ANDROID_SYSTEM
        else -> AsrBackend.NONE
    }

    val hasVad: Boolean get() = models.isInstalled(ModelCatalog.VAD_SILERO)

    /** Loads the streaming models (idempotent). Returns false if the offline pack is missing. */
    suspend fun prepare(): Boolean = withContext(CoreExecutors.realtime) {
        if (backend() != AsrBackend.SHERPA_OFFLINE) return@withContext false
        synchronized(lock) {
            if (recognizer == 0L) {
                residency.reserve(residencyKey, residentMb)
                val dir = models.locate(ModelCatalog.ASR_ZIPFORMER) ?: return@withContext false
                recognizer = SherpaNative.nativeCreateOnlineRecognizer(
                    encoder = "$dir/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$dir/decoder-epoch-99-avg-1.onnx",
                    joiner = "$dir/joiner-epoch-99-avg-1.int8.onnx",
                    tokens = "$dir/tokens.txt",
                    numThreads = 1,
                    decodingMethod = "greedy_search",
                    enableEndpoint = true,
                    rule1 = 2.4f, rule2 = 1.2f, rule3 = 30f,
                )
                if (recognizer == 0L) return@withContext false
                residency.register(this@SpeechRecognitionEngine)
            }
            if (vad == 0L) {
                models.file(ModelCatalog.VAD_SILERO, "silero_vad.onnx")?.let {
                    vad = SherpaNative.nativeCreateVad(it.absolutePath, 0.5f, 0.25f, 0.10f, 512, 30f, 16000, 1, 120f)
                }
            }
            if (punct == 0L) {
                models.locate(ModelCatalog.PUNCTUATION)?.let {
                    punct = SherpaNative.nativeCreateOnlinePunctuation("$it/model.int8.onnx", "$it/bpe.vocab", 1)
                }
            }
            residency.touch(residencyKey)
            true
        }
    }

    /** Lazily loads the Moonshine second-pass recogniser on the big-core inference thread. */
    private suspend fun ensureSecondPass(): Boolean = withContext(CoreExecutors.inference) {
        synchronized(lock) {
            if (offline != 0L) return@withContext true
            val dir = models.locate(ModelCatalog.ASR_MOONSHINE) ?: return@withContext false
            residency.reserve(residencyKey, residentMb + ModelCatalog.ASR_MOONSHINE.residentMb)
            offline = SherpaNative.nativeCreateOfflineRecognizer(
                "moonshine", "$dir/preprocess.onnx", "$dir/encode.int8.onnx", "$dir/uncached_decode.int8.onnx",
                "$dir/cached_decode.int8.onnx", "$dir/tokens.txt", CoreExecutors.inferenceThreads,
            )
            offline != 0L
        }
    }

    override fun releaseForMemory() {
        synchronized(lock) {
            if (offline != 0L) { SherpaNative.nativeDestroyOfflineRecognizer(offline); offline = 0L }
            if (punct != 0L) { SherpaNative.nativeDestroyOnlinePunctuation(punct); punct = 0L }
            if (vad != 0L) { SherpaNative.nativeDestroyVad(vad); vad = 0L }
            if (recognizer != 0L) { SherpaNative.nativeDestroyOnlineRecognizer(recognizer); recognizer = 0L }
        }
        residency.unregister(residencyKey)
    }

    /** Adds punctuation/casing to lower-case ASR text (no-op if the model is missing). */
    suspend fun punctuate(text: String): String = withContext(CoreExecutors.realtime) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext clean
        val out = synchronized(lock) { if (punct != 0L) SherpaNative.nativeAddPunctuation(punct, clean.lowercase()) else null }
        out?.takeIf { it.isNotBlank() } ?: basicSentenceCase(clean)
    }

    /**
     * Starts a recognition session. For the offline backend the caller must have started
     * [OboeAudioRecorder]; the Android backend opens the microphone itself.
     * [conversation] enables endpoint segmentation into [RecognitionSession.utterances].
     */
    suspend fun startSession(scope: CoroutineScope, conversation: Boolean = false): RecognitionSession? =
        when (backend()) {
            AsrBackend.SHERPA_OFFLINE -> if (prepare()) SherpaSession(scope, conversation).also { it.start() } else null
            AsrBackend.ANDROID_SYSTEM -> withContext(Dispatchers.Main) { SystemSession(scope, conversation).also { it.start() } }
            AsrBackend.NONE -> null
        }

    // =================================================================== sherpa session

    private inner class SherpaSession(private val scope: CoroutineScope, private val conversation: Boolean) : RecognitionSession {
        private val _live = MutableStateFlow(LiveTranscript())
        override val live: StateFlow<LiveTranscript> = _live
        private val _utterances = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val utterances: SharedFlow<String> = _utterances
        override val usesAppRecorder = true

        private var stream = 0L
        private val audio = PcmUtils.PcmAccumulator(16000 * 30)
        private var fed = 0L
        private var segmentStart = 0L
        private val committedWords = ArrayList<WordTiming>()
        private val committedText = StringBuilder()
        private val vadSegments = ArrayList<SpeechSegment>()
        private val energyVad = EnergyVad(16000)
        private var job: Job? = null
        private var tick = 0
        private var vadSpeaking = false

        fun start() {
            synchronized(lock) {
                stream = SherpaNative.nativeCreateOnlineStream(recognizer)
                if (vad != 0L) SherpaNative.nativeVadReset(vad)
            }
            job = scope.launch(CoreExecutors.realtime) {
                recorder.frames.collect { onChunk(it) }
            }
        }

        private fun onChunk(chunk: ShortArray) {
            audio.append(chunk, chunk.size)
            val f = PcmUtils.shortsToFloats(chunk)
            synchronized(lock) {
                if (recognizer == 0L || stream == 0L) return
                SherpaNative.nativeOnlineAcceptWaveform(stream, 16000, f, f.size)
                SherpaNative.nativeOnlineDecode(recognizer, stream)
                if (vad != 0L) {
                    SherpaNative.nativeVadAcceptWaveform(vad, f, f.size)
                    vadSpeaking = SherpaNative.nativeVadIsSpeech(vad)
                    popVadLocked()
                }
            }
            energyVad.accept(chunk, chunk.size)
            fed += chunk.size
            if (++tick % 5 == 0) refresh(checkEndpoint = conversation)
        }

        private fun popVadLocked() {
            val segs = SherpaNative.nativeVadPopSegments(vad)
            var i = 0
            while (i + 1 < segs.size) {
                val start = segs[i] / 16000.0
                vadSegments += SpeechSegment(start, start + segs[i + 1] / 16000.0)
                i += 2
            }
        }

        private fun refresh(checkEndpoint: Boolean, final: Boolean = false) {
            val (json, endpoint) = synchronized(lock) {
                if (recognizer == 0L || stream == 0L) return
                SherpaNative.nativeOnlineGetResult(recognizer, stream) to
                    (checkEndpoint && SherpaNative.nativeOnlineIsEndpoint(recognizer, stream))
            }
            val offset = segmentStart / 16000.0
            val parsed = parseOnline(json, offset, fed / 16000.0)
            if (endpoint || final) {
                if (parsed.text.isNotBlank()) {
                    if (committedText.isNotEmpty()) committedText.append(' ')
                    committedText.append(parsed.text)
                    committedWords += parsed.words
                    if (conversation) _utterances.tryEmit(parsed.text)
                }
                if (endpoint && !final) {
                    synchronized(lock) { SherpaNative.nativeOnlineReset(recognizer, stream) }
                    segmentStart = fed
                }
                publish("", emptyList())
            } else {
                publish(parsed.text, parsed.words)
            }
        }

        private fun publish(partial: String, partialWords: List<WordTiming>) {
            _live.value = LiveTranscript(
                committed = committedText.toString(),
                partial = partial,
                words = committedWords + partialWords,
                elapsedSec = fed / 16000.0,
                speaking = if (vad != 0L) vadSpeaking else energyVad.speaking,
            )
        }

        override suspend fun finish(): FinalTranscript {
            delay(140)  // let chunks already emitted by the capture thread drain
            job?.cancelAndJoin()
            val liveText: String
            val words: List<WordTiming>
            val segments: List<SpeechSegment>
            withContext(CoreExecutors.realtime) {
                synchronized(lock) {
                    if (stream != 0L && recognizer != 0L) {
                        SherpaNative.nativeOnlineInputFinished(stream)
                        SherpaNative.nativeOnlineDecode(recognizer, stream)
                    }
                }
                refresh(checkEndpoint = false, final = true)
                synchronized(lock) {
                    if (vad != 0L) {
                        SherpaNative.nativeVadFlush(vad)
                        popVadLocked()
                        SherpaNative.nativeVadReset(vad)
                    }
                    if (stream != 0L) SherpaNative.nativeDestroyOnlineStream(stream)
                    stream = 0L
                }
            }
            liveText = committedText.toString().lowercase()
            words = committedWords.map { it.copy(word = it.word.lowercase()) }
            segments = if (vadSegments.isNotEmpty()) vadSegments.toList() else energyVad.segments()
            val samples = audio.snapshot()
            val duration = samples.size / 16000.0

            var engine = "zipformer"
            var text = punctuate(liveText)
            if (samples.size > 16000 && ensureSecondPass()) {
                val second = secondPass(samples, segments)
                if (second.isNotBlank()) {
                    text = second
                    engine = "zipformer+moonshine"
                }
            }
            return FinalTranscript(text, liveText, words, segments, duration, engine, samples)
        }

        override fun cancel() {
            job?.cancel()
            scope.launch(CoreExecutors.realtime) {
                synchronized(lock) {
                    if (stream != 0L) SherpaNative.nativeDestroyOnlineStream(stream)
                    stream = 0L
                    if (vad != 0L) SherpaNative.nativeVadReset(vad)
                }
            }
        }
    }

    /** Moonshine over VAD-bounded chunks (<= 20 s each, padded by 0.25 s). */
    private suspend fun secondPass(samples: ShortArray, segments: List<SpeechSegment>): String = withContext(CoreExecutors.inference) {
        val chunks = ArrayList<Pair<Int, Int>>()
        if (segments.isEmpty()) {
            var s = 0
            while (s < samples.size) {
                val e = min(samples.size, s + 16000 * 20)
                chunks += s to e
                s = e
            }
        } else {
            var curStart = -1
            var curEnd = -1
            for (seg in segments.sortedBy { it.startSec }) {
                val a = max(0, ((seg.startSec - 0.25) * 16000).toInt())
                val b = min(samples.size, ((seg.endSec + 0.25) * 16000).toInt())
                if (curStart < 0) { curStart = a; curEnd = b; continue }
                if (b - curStart <= 16000 * 20) curEnd = max(curEnd, b) else {
                    chunks += curStart to curEnd
                    curStart = a; curEnd = b
                }
            }
            if (curStart >= 0) chunks += curStart to curEnd
        }
        val parts = ArrayList<String>()
        for ((a, b) in chunks) {
            if (b - a < 4000) continue
            val f = PcmUtils.shortsToFloats(samples.copyOfRange(a, b))
            val json = synchronized(lock) { if (offline != 0L) SherpaNative.nativeOfflineDecode(offline, f, 16000) else null } ?: break
            val t = runCatching { JSONObject(json).optString("text") }.getOrDefault("").trim()
            if (t.isNotEmpty()) parts += t
        }
        parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    // =========================================================== Android system fallback

    private inner class SystemSession(private val scope: CoroutineScope, private val conversation: Boolean) : RecognitionSession {
        private val _live = MutableStateFlow(LiveTranscript())
        override val live: StateFlow<LiveTranscript> = _live
        private val _utterances = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val utterances: SharedFlow<String> = _utterances
        override val usesAppRecorder = false

        private var sysRecognizer: SpeechRecognizer? = null
        private val t0 = System.nanoTime()
        private var consecutiveErrors = 0
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var active = true
        private val committed = StringBuilder()
        private val committedWords = ArrayList<WordTiming>()
        private var partialWords = ArrayList<WordTiming>()
        private val segments = ArrayList<SpeechSegment>()
        private var speechStart = -1.0
        private var lastLoud = 0.0
        private var finalResult: CompletableDeferred<Unit>? = null

        private fun now() = (System.nanoTime() - t0) / 1e9

        fun start() {
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            sysRecognizer = r
            r.setRecognitionListener(listener)
            listen()
        }

        private fun listen() {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            runCatching { sysRecognizer?.startListening(intent) }.onFailure { Log.w("PolyLinguaAsr", "startListening failed", it) }
        }

        private fun mergePartial(text: String) {
            val tokens = text.split(' ').filter { it.isNotBlank() }
            val t = now()
            val next = ArrayList<WordTiming>(tokens.size)
            for ((i, tok) in tokens.withIndex()) {
                val prev = partialWords.getOrNull(i)
                next += if (prev != null && prev.word.equals(tok, true)) prev else WordTiming(tok, max(0.0, t - 0.45), t - 0.1)
            }
            for (i in 0 until next.size - 1) if (next[i].endSec > next[i + 1].startSec) next[i] = next[i].copy(endSec = next[i + 1].startSec)
            partialWords = next
            publish(text)
        }

        private fun publish(partial: String) {
            _live.value = LiveTranscript(committed.toString(), partial, committedWords + partialWords, now(), speechStart >= 0)
        }

        private fun commit(text: String) {
            if (text.isBlank()) return
            mergePartial(text)
            if (committed.isNotEmpty()) committed.append(' ')
            committed.append(text.trim())
            committedWords += partialWords
            partialWords = ArrayList()
            if (conversation) _utterances.tryEmit(text.trim())
            publish("")
        }

        private val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val t = now()
                if (rmsdB > 4f) {
                    if (speechStart < 0) speechStart = t
                    lastLoud = t
                } else if (speechStart >= 0 && t - lastLoud > 0.25) {
                    segments += SpeechSegment(speechStart, lastLoud)
                    speechStart = -1.0
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || ++consecutiveErrors > 8
                if (active && !fatal) handler.postDelayed({ if (active) listen() }, 250) else finalResult?.complete(Unit)
            }
            override fun onResults(results: Bundle?) {
                consecutiveErrors = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                commit(text)
                if (active) listen() else finalResult?.complete(Unit)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) mergePartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        override suspend fun finish(): FinalTranscript {
            val done = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                active = false
                finalResult = done
                sysRecognizer?.stopListening()
            }
            withTimeoutOrNull(3500) { done.await() }
            withContext(Dispatchers.Main) {
                handler.removeCallbacksAndMessages(null)
                sysRecognizer?.destroy()
                sysRecognizer = null
                if (_live.value.partial.isNotBlank()) commit(_live.value.partial)
                if (speechStart >= 0) segments += SpeechSegment(speechStart, lastLoud)
            }
            val text = committed.toString()
            return FinalTranscript(
                text = basicSentenceCase(text),
                liveText = text.lowercase(),
                words = committedWords.toList(),
                speechSegments = segments.toList(),
                durationSec = now(),
                engine = "android-system",
                audio = ShortArray(0),
            )
        }

        override fun cancel() {
            active = false
            scope.launch(Dispatchers.Main) {
                handler.removeCallbacksAndMessages(null)
                sysRecognizer?.destroy()
                sysRecognizer = null
            }
        }
    }

    // ============================================================================ helpers

    private data class ParsedSegment(val text: String, val words: List<WordTiming>)

    /** Groups sentencepiece tokens ("▁THE", "▁CAT", "S") into words with absolute times. */
    private fun parseOnline(json: String, offsetSec: Double, nowSec: Double): ParsedSegment {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return ParsedSegment("", emptyList())
        val text = o.optString("text").trim()
        val toks = o.optJSONArray("tokens")
        val ts = o.optJSONArray("timestamps")
        if (toks == null || ts == null || toks.length() == 0) return ParsedSegment(text, emptyList())
        val starts = ArrayList<Double>()
        val words = ArrayList<String>()
        val n = min(toks.length(), ts.length())
        for (i in 0 until n) {
            val tok = toks.optString(i)
            val t = offsetSec + ts.optDouble(i, 0.0)
            val startsWord = tok.startsWith("▁") || tok.startsWith(" ") || words.isEmpty()
            val clean = tok.trimStart('▁', ' ')
            if (startsWord) {
                if (clean.isEmpty()) continue
                words += clean
                starts += t
            } else {
                words[words.size - 1] = words.last() + clean
            }
        }
        val timings = words.indices.map { i ->
            val s = starts[i]
            val e = if (i + 1 < starts.size) min(starts[i + 1], s + 1.2) else min(nowSec, s + 0.45).coerceAtLeast(s + 0.1)
            WordTiming(words[i], s, e)
        }
        return ParsedSegment(text, timings)
    }

    private fun basicSentenceCase(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return t
        val withI = t.replace(Regex("\\bi\\b"), "I")
        val cap = withI.replaceFirstChar { it.uppercaseChar() }
        return if (cap.last() in ".!?") cap else "$cap."
    }
}

/**
 * Energy VAD used when the Silero pack is absent: 20 ms frames, adaptive noise floor, speech when
 * the frame is 12 dB above the floor, 120 ms minimum speech and 250 ms hangover.
 */
class EnergyVad(private val sampleRate: Int) {
    private var floorDb = -55.0
    private var t = 0.0
    private var inSpeech = false
    private var speechStart = 0.0
    private var lastSpeech = 0.0
    private val out = ArrayList<SpeechSegment>()

    val speaking: Boolean get() = inSpeech

    fun accept(chunk: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
        val rms = sqrt(sum / max(1, n)) / 32768.0
        val db = if (rms <= 1e-7) -100.0 else 20 * log10(rms)
        val dur = n.toDouble() / sampleRate
        val loud = db > floorDb + 12.0 && db > -50.0
        if (!loud) floorDb = 0.95 * floorDb + 0.05 * db.coerceAtLeast(-80.0)
        if (loud) {
            if (!inSpeech) { inSpeech = true; speechStart = t }
            lastSpeech = t + dur
        } else if (inSpeech && t - lastSpeech > 0.25) {
            if (lastSpeech - speechStart >= 0.12) out += SpeechSegment(speechStart, lastSpeech)
            inSpeech = false
        }
        t += dur
    }

    fun segments(): List<SpeechSegment> {
        val list = ArrayList(out)
        if (inSpeech && lastSpeech - speechStart >= 0.12) list += SpeechSegment(speechStart, lastSpeech)
        return list
    }
}
