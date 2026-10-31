package com.polylingua.edge.speech

import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.GrammarIssue
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.PronunciationResult
import com.polylingua.edge.domain.model.SpeakingAssessment
import com.polylingua.edge.domain.model.SpeechSample
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.domain.model.WordTiming
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.dsp.ShadowingScorer
import com.polylingua.edge.dsp.TarsosPitchAndProsodyEngine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AssessmentOutcome(
    val assessment: SpeakingAssessment,
    val sample: SpeechSample,
    val transcriptEngine: String,
    val llmIssues: List<GrammarIssue> = emptyList(),
)

data class DrillOutcome(
    val pronunciation: PronunciationResult?,
    val intonation: TarsosPitchAndProsodyEngine.IntonationComparison?,
    val userPitch: List<PitchFrame>,
    val referencePitch: List<PitchFrame>,
    val recognized: String,
)

data class ShadowingOutcome(
    val score: ShadowingScorer.Result,
    val pronunciation: PronunciationResult?,
    val intonation: TarsosPitchAndProsodyEngine.IntonationComparison?,
    val userPitch: List<PitchFrame>,
    val referencePitch: List<PitchFrame>,
    val recognized: String,
)

/**
 * Turns captured audio into rubric scores: refines word end-times from the energy envelope,
 * detects acoustic filled pauses, runs phoneme GOP, and feeds everything to the
 * [com.polylingua.edge.domain.PedagogicalRubricEngine]. Optional offline-LLM grammar review adds
 * corrections the rule engine cannot see.
 */
@Singleton
class AssessmentPipeline @Inject constructor(
    private val resources: LinguisticResources,
    private val pronunciation: PronunciationEngine,
    private val llm: com.polylingua.edge.ai.AiCoach,
) {

    suspend fun assessSpeaking(
        captured: CapturedResponse,
        context: TaskContext,
        extraIssues: List<GrammarIssue> = emptyList(),
        referenceText: String? = null,
    ): AssessmentOutcome {
        val pitch = captured.pitch
        val words = withContext(CoreExecutors.dsp) { refineWordEnds(captured.transcript.words, pitch) }
        val fillers = withContext(CoreExecutors.dsp) { countAcousticFillers(pitch, words) }
        val floats = if (captured.audio.isNotEmpty()) PcmUtils.shortsToFloats(captured.audio) else FloatArray(0)
        val pron = when {
            floats.isEmpty() -> referenceText?.let { PhonemeFallback.fromTranscript(resources, it, words) }
            referenceText != null -> pronunciation.analyzeReading(floats, referenceText, pitch, words)
            else -> pronunciation.analyzeSpontaneous(floats, words, pitch)
        }
        val sample = SpeechSample(
            transcript = captured.transcript.text,
            words = words,
            speechSegments = captured.transcript.speechSegments,
            totalDurationSec = captured.durationSec,
            pitch = pitch,
            pronunciation = pron,
            referenceText = referenceText,
            acousticFilledPauses = fillers,
        )
        val assessment = withContext(CoreExecutors.dsp) { resources.rubric.assess(sample, context, extraIssues) }
        return AssessmentOutcome(assessment, sample, captured.transcript.engine, extraIssues.filter { it.source == "llm" })
    }

    /** Re-scores a sample with additional grammar issues (e.g. after an AI deep check). */
    suspend fun reassess(sample: SpeechSample, context: TaskContext, engine: String, extraIssues: List<GrammarIssue>): AssessmentOutcome {
        val assessment = withContext(CoreExecutors.dsp) { resources.rubric.assess(sample, context, extraIssues) }
        return AssessmentOutcome(assessment, sample, engine, extraIssues.filter { it.source == "llm" })
    }

    /**
     * Offline-LLM grammar review of up to [maxSentences] sentences (longest first). Each returned
     * issue spans the sentence in the transcript and carries the corrected version.
     */
    suspend fun deepGrammarCheck(transcript: String, level: String, maxSentences: Int = if (llm.isCloudActive) 10 else 3, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<GrammarIssue> {
        if (!llm.isAvailable) return emptyList()
        val sentences = TextAnalysis.sentences(transcript)
            .filter { TextAnalysis.words(it.text).size >= 5 }
            .sortedByDescending { it.text.length }
            .take(maxSentences)
        val issues = ArrayList<GrammarIssue>()
        for ((i, s) in sentences.withIndex()) {
            onProgress(i, sentences.size)
            val c = llm.correct(s.text.trim(), level) ?: continue
            if (normalizeForCompare(c.corrected) != normalizeForCompare(s.text)) {
                issues += GrammarIssue(
                    excerpt = s.text.trim(),
                    suggestion = c.corrected,
                    explanation = c.explanation.ifBlank { "Rephrased for accuracy and naturalness." } +
                        if (c.nativeAlternative.isNotBlank() && c.nativeAlternative != c.corrected) " Native-like: \"${c.nativeAlternative}\"" else "",
                    start = s.start,
                    end = s.end,
                    source = "llm",
                )
            }
        }
        onProgress(sentences.size, sentences.size)
        return issues
    }

    /** ELSA drill: GOP against the target sentence + intonation against the model voice. */
    suspend fun assessDrill(captured: CapturedResponse, target: String, reference: AudioClip?): DrillOutcome {
        val floats = PcmUtils.shortsToFloats(captured.audio)
        val words = withContext(CoreExecutors.dsp) { refineWordEnds(captured.transcript.words, captured.pitch) }
        val pron = if (floats.isNotEmpty()) pronunciation.analyzeReading(floats, target, captured.pitch, words)
        else PhonemeFallback.fromTranscript(resources, target, words)
        val refPitch = reference?.let { pitchOf(it) } ?: emptyList()
        val inton = if (refPitch.isNotEmpty() && captured.pitch.any { it.voiced }) {
            withContext(CoreExecutors.dsp) { TarsosPitchAndProsodyEngine.compareIntonation(captured.pitch, refPitch) }
        } else null
        return DrillOutcome(pron, inton, captured.pitch, refPitch, captured.transcript.text)
    }

    /** Cake shadowing mastery: 40% phonetic accuracy, 35% intonation, 25% rhythm sync. */
    suspend fun assessShadowing(captured: CapturedResponse, target: String, reference: AudioClip?): ShadowingOutcome {
        val drill = assessDrill(captured, target, reference)
        val phon = drill.pronunciation?.meanScore ?: Double.NaN
        val inton = drill.intonation?.similarityPercent ?: Double.NaN
        val rhythm = if (drill.referencePitch.isNotEmpty()) withContext(CoreExecutors.dsp) {
            ShadowingScorer.rhythmSimilarity(
                TarsosPitchAndProsodyEngine.syllableNuclei(drill.userPitch),
                TarsosPitchAndProsodyEngine.syllableNuclei(drill.referencePitch),
            )
        } else Double.NaN
        return ShadowingOutcome(ShadowingScorer.score(phon, inton, rhythm), drill.pronunciation, drill.intonation,
            drill.userPitch, drill.referencePitch, drill.recognized)
    }

    suspend fun pitchOf(clip: AudioClip): List<PitchFrame> = withContext(CoreExecutors.dsp) {
        val audio = if (clip.sampleRate == 16000) clip.samples else PcmUtils.resample(clip.samples, clip.sampleRate, 16000)
        TarsosPitchAndProsodyEngine().analyze(audio)
    }

    private fun normalizeForCompare(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        /**
         * Streaming ASR gives word *start* times only. A word ends where the energy envelope falls
         * below the speech threshold for 60 ms, bounded by the next word's start and 1.2 s.
         */
        fun refineWordEnds(words: List<WordTiming>, pitch: List<PitchFrame>): List<WordTiming> {
            if (words.isEmpty() || pitch.size < 10) return words
            val sorted = words.sortedBy { it.startSec }
            val db = pitch.map { it.rmsDb.toDouble() }.sorted()
            val floor = db[(db.size * 0.10).toInt().coerceIn(0, db.size - 1)]
            val peak = db[(db.size * 0.95).toInt().coerceIn(0, db.size - 1)]
            val threshold = max(floor + 10.0, min(peak - 25.0, floor + 18.0))
            val hop = if (pitch.size >= 2) pitch[1].timeSec - pitch[0].timeSec else 0.016
            val quietFramesNeeded = max(2, (0.06 / max(hop, 1e-3)).toInt())
            return sorted.mapIndexed { i, w ->
                val limit = min(sorted.getOrNull(i + 1)?.startSec ?: Double.MAX_VALUE, w.startSec + 1.2)
                var idx = lowerBound(pitch, w.startSec)
                var lastLoud = w.startSec + 0.08
                var quiet = 0
                while (idx < pitch.size && pitch[idx].timeSec < limit) {
                    if (pitch[idx].rmsDb >= threshold) {
                        lastLoud = pitch[idx].timeSec
                        quiet = 0
                    } else if (++quiet >= quietFramesNeeded && pitch[idx].timeSec - w.startSec > 0.1) {
                        break
                    }
                    idx++
                }
                val end = min(limit, lastLoud + hop).coerceAtLeast(w.startSec + 0.08)
                w.copy(endSec = if (limit == Double.MAX_VALUE) end else min(end, limit))
            }
        }

        /**
         * Hesitation sounds ("uh", "erm") are voiced, pitch-flat (< 1 semitone SD), at least 250 ms
         * long and not covered by any recognised word.
         */
        fun countAcousticFillers(pitch: List<PitchFrame>, words: List<WordTiming>): Int {
            if (pitch.size < 20) return 0
            var count = 0
            var i = 0
            while (i < pitch.size) {
                if (!pitch[i].voiced) { i++; continue }
                var j = i
                while (j + 1 < pitch.size && pitch[j + 1].voiced) j++
                val start = pitch[i].timeSec
                val end = pitch[j].timeSec
                if (end - start >= 0.25) {
                    val covered = words.any { w -> w.startSec < end - 0.05 && w.endSec > start + 0.05 }
                    if (!covered) {
                        val st = (i..j).map { TarsosPitchAndProsodyEngine.hzToSemitones(pitch[it].f0Hz.toDouble()) }
                        val mean = st.average()
                        val sd = sqrt(st.sumOf { (it - mean) * (it - mean) } / st.size)
                        val loud = (i..j).map { pitch[it].rmsDb }.average() > -45.0
                        if (sd < 1.0 && loud) count++
                    }
                }
                i = j + 1
            }
            return count
        }

        private fun lowerBound(pitch: List<PitchFrame>, t: Double): Int {
            var lo = 0
            var hi = pitch.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pitch[mid].timeSec < t) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}

/** Word-level fallback when no audio is available (Android recogniser backend). */
object PhonemeFallback {
    fun fromTranscript(resources: LinguisticResources, target: String, words: List<WordTiming>): PronunciationResult? =
        com.polylingua.edge.dsp.PhonemeGopAnalyzer.asrAlignmentFallback(resources.cmu, target, words)
}
