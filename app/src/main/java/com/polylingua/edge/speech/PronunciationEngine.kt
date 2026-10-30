package com.polylingua.edge.speech

import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.PronunciationResult
import com.polylingua.edge.domain.model.WordPronunciation
import com.polylingua.edge.domain.model.WordTiming
import com.polylingua.edge.dsp.PhonemeGopAnalyzer
import com.polylingua.edge.dsp.PhonemePosteriorProvider
import com.polylingua.edge.dsp.PosteriorMatrix
import com.polylingua.edge.nativebridge.GopNative
import com.polylingua.edge.nativebridge.NativeLoader
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Phoneme-level pronunciation scoring. With the charsiu wav2vec2 pack installed this is true GOP
 * (forced alignment over frame posteriors, see [PhonemeGopAnalyzer]); without it, words are
 * graded by aligning the reference text against the recogniser output.
 */
@Singleton
class PronunciationEngine @Inject constructor(
    private val models: ModelManager,
    private val resources: LinguisticResources,
    private val residency: ModelResidency,
) : ResidentModel {

    private val lock = Any()
    private var handle = 0L

    override val residencyKey = "gop"
    override val residentMb: Int get() = if (handle != 0L) ModelCatalog.GOP_CHARSIU.residentMb else 0

    val hasAcousticModel: Boolean get() = models.isInstalled(ModelCatalog.GOP_CHARSIU) && NativeLoader.ensureLoaded()

    private val provider = object : PhonemePosteriorProvider {
        override val labels: List<String> get() = resources.gopLabels
        override fun logPosteriors(audio16k: FloatArray): PosteriorMatrix? {
            val h = synchronized(lock) { ensureHandleLocked() }
            if (h == 0L) return null
            val raw = synchronized(lock) { GopNative.nativeComputeLogPosteriors(h, audio16k) }
            if (raw.size < 2) return null
            val t = raw[0].toInt()
            val c = raw[1].toInt()
            if (t <= 0 || c <= 0 || raw.size < 2 + t * c) return null
            return PosteriorMatrix(t, c, raw.copyOfRange(2, 2 + t * c))
        }
    }

    private fun ensureHandleLocked(): Long {
        if (handle != 0L) {
            residency.touch(residencyKey)
            return handle
        }
        val file = models.file(ModelCatalog.GOP_CHARSIU, "model_quantized.onnx") ?: return 0L
        residency.reserve(residencyKey, ModelCatalog.GOP_CHARSIU.residentMb, pinned = setOf("asr"))
        handle = GopNative.nativeCreate(file.absolutePath, CoreExecutors.inferenceThreads)
        if (handle != 0L) residency.register(this)
        return handle
    }

    override fun releaseForMemory() {
        synchronized(lock) {
            if (handle != 0L) GopNative.nativeDestroy(handle)
            handle = 0L
        }
        residency.unregister(residencyKey)
    }

    /** Reading task (known reference sentence, <= ~12 s of audio). */
    suspend fun analyzeReading(
        audio16k: FloatArray,
        referenceText: String,
        pitch: List<PitchFrame>,
        recognized: List<WordTiming>,
    ): PronunciationResult? = withContext(CoreExecutors.inference) {
        val viaGop = if (hasAcousticModel) runCatching {
            PhonemeGopAnalyzer(resources.cmu, provider).analyze(audio16k, referenceText, pitch)
        }.getOrNull() else null
        viaGop ?: PhonemeGopAnalyzer.asrAlignmentFallback(resources.cmu, referenceText, recognized)
    }

    /**
     * Spontaneous speech (IELTS answers up to 2 min): the recogniser's words are grouped into
     * <= 8 s chunks split at natural gaps, each chunk is GOP-scored against its own words, and the
     * results are merged on the global timeline. Chunking bounds wav2vec2 attention memory.
     */
    suspend fun analyzeSpontaneous(
        audio16k: FloatArray,
        words: List<WordTiming>,
        pitch: List<PitchFrame>,
    ): PronunciationResult? = withContext(CoreExecutors.inference) {
        if (!hasAcousticModel || words.isEmpty() || audio16k.isEmpty()) return@withContext null
        val analyzer = PhonemeGopAnalyzer(resources.cmu, provider)
        val chunks = chunkWords(words.sortedBy { it.startSec })
        val merged = ArrayList<WordPronunciation>()
        val durationSec = audio16k.size / 16000.0
        for (chunk in chunks) {
            val a = max(0.0, chunk.first().startSec - 0.15)
            val b = min(durationSec, chunk.last().endSec + 0.20)
            if (b - a < 0.3) continue
            val slice = audio16k.copyOfRange((a * 16000).toInt(), (b * 16000).toInt().coerceAtMost(audio16k.size))
            val localPitch = pitch.filter { it.timeSec in a..b }.map { it.copy(timeSec = it.timeSec - a) }
            val text = chunk.joinToString(" ") { it.word }
            val res = runCatching { analyzer.analyze(slice, text, localPitch) }.getOrNull() ?: continue
            val base = merged.size
            for (w in res.words) {
                merged += w.copy(
                    startSec = w.startSec + a,
                    endSec = w.endSec + a,
                    phonemes = w.phonemes.map { p -> p.copy(startSec = p.startSec + a, endSec = p.endSec + a, wordIndex = p.wordIndex + base) },
                )
            }
        }
        if (merged.isEmpty()) return@withContext null
        val scores = merged.flatMap { it.phonemes }.map { it.score }
        PronunciationResult(merged, if (scores.isEmpty()) 0.0 else scores.average(), "gop")
    }

    private fun chunkWords(words: List<WordTiming>): List<List<WordTiming>> {
        val out = ArrayList<List<WordTiming>>()
        var cur = ArrayList<WordTiming>()
        for (w in words) {
            if (cur.isNotEmpty()) {
                val span = w.endSec - cur.first().startSec
                val gap = w.startSec - cur.last().endSec
                if (span > 8.0 || (span > 4.0 && gap > 0.3)) {
                    out += cur
                    cur = ArrayList()
                }
            }
            cur += w
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}
