package com.polylingua.edge.dsp

import com.polylingua.edge.domain.CmuDictionary
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.PhonemeScore
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.PronunciationResult
import com.polylingua.edge.domain.model.WordPronunciation
import com.polylingua.edge.domain.model.WordTiming
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Frame-level log posteriors over phone classes (rows = frames). */
class PosteriorMatrix(val frames: Int, val classes: Int, val data: FloatArray) {
    operator fun get(t: Int, c: Int): Float = data[t * classes + c]
}

/** Supplies phone posteriors for 16 kHz audio (native ONNX model in production, fakes in tests). */
interface PhonemePosteriorProvider {
    /** Class labels by index, e.g. "AA", "AE", ..., "[SIL]", "[UNK]", "[PAD]". */
    val labels: List<String>
    fun logPosteriors(audio16k: FloatArray): PosteriorMatrix?
}

/**
 * Goodness of Pronunciation (GOP) analyser (Witt & Young 2000, frame-posterior variant):
 *
 *   GOP(p) = (1 / D_p) * sum_{t = t_s}^{t_e} [ log P(p | o_t) - max_{q in Q} log P(q | o_t) ]
 *
 * where [t_s, t_e] is the phone segment found by Viterbi forced alignment of the canonical CMUdict
 * phone sequence, D_p = t_e - t_s + 1 frames and Q is the 39-phone ARPAbet set. GOP <= 0; the
 * 0-100 score is 100 * exp(0.6 * GOP), graded Green >= 80 (native-like), Amber 50-79 (slight
 * accent deviation), Red < 50 (mispronounced). The detected (substituted) phone is the argmax of
 * the segment-averaged posterior, which drives the "Detected /s/ instead of /θ/" remediation.
 * Lexical stress is estimated per word from vowel-segment duration, energy and F0.
 */
class PhonemeGopAnalyzer(
    private val dictionary: CmuDictionary,
    private val provider: PhonemePosteriorProvider?,
) {
    private val silenceLabels = setOf("[SIL]", "SIL", "sil", "<sil>", "[PAD]", "<pad>", " ", "|", "[UNK]", "<unk>")

    data class CanonicalPhone(val arpabet: String, val wordIndex: Int, val classIndex: Int)

    private data class State(val classIndex: Int, val phoneIndex: Int, val optional: Boolean)

    /** Full pipeline. Returns null when no acoustic model is available or alignment is impossible. */
    fun analyze(audio16k: FloatArray, referenceText: String, pitch: List<PitchFrame>): PronunciationResult? {
        val p = provider ?: return null
        val words = TextAnalysis.tokenize(referenceText).map { it.text }.filter { w -> w.any { it.isLetter() } }
        if (words.isEmpty()) return null
        val post = p.logPosteriors(audio16k) ?: return null
        if (post.frames < 5) return null
        val frameSec = audio16k.size / 16000.0 / post.frames
        return analyzeWithPosteriors(post, frameSec, words, pitch, p.labels)
    }

    fun analyzeWithPosteriors(
        post: PosteriorMatrix,
        frameSec: Double,
        words: List<String>,
        pitch: List<PitchFrame>,
        labels: List<String>,
    ): PronunciationResult? {
        val labelIndex = HashMap<String, Int>()
        labels.forEachIndexed { i, l -> labelIndex[l.uppercase()] = i }
        val silIndex = labels.indexOfFirst { it in silenceLabels }.takeIf { it >= 0 } ?: return null
        val phoneClassIndices = PhoneticArticulationDatabase.ARPABET_39.mapNotNull { labelIndex[it] }
        if (phoneClassIndices.size < 30) return null

        // Canonical phone sequence from the dictionary.
        val canonical = ArrayList<CanonicalPhone>()
        val wordProns = ArrayList<List<String>>()
        for ((wi, w) in words.withIndex()) {
            val pron = dictionary.pronounce(w)
            wordProns += pron
            for (ph in pron) {
                val idx = labelIndex[PhoneticArticulationDatabase.baseSymbol(ph)] ?: continue
                canonical += CanonicalPhone(ph, wi, idx)
            }
        }
        if (canonical.isEmpty()) return null

        // State graph: [SIL?] p0a p0b [SIL? between words] p1a p1b ... [SIL?]  (2 sub-states = 20 ms minimum)
        val states = ArrayList<State>()
        states += State(silIndex, -1, optional = true)
        for ((pi, cp) in canonical.withIndex()) {
            if (pi > 0 && canonical[pi - 1].wordIndex != cp.wordIndex) states += State(silIndex, -1, optional = true)
            states += State(cp.classIndex, pi, optional = false)
            states += State(cp.classIndex, pi, optional = false)
        }
        states += State(silIndex, -1, optional = true)
        val required = states.count { !it.optional }
        val T = post.frames
        if (T < required) return null

        val S = states.size
        val negInf = Float.NEGATIVE_INFINITY
        val score = Array(T) { FloatArray(S) { negInf } }
        val back = Array(T) { IntArray(S) { -1 } }
        // initial: state 0 (optional SIL) or state 1
        score[0][0] = post[0, states[0].classIndex]
        score[0][1] = post[0, states[1].classIndex]
        for (t in 1 until T) {
            for (s in 0 until S) {
                val emit = post[t, states[s].classIndex]
                var best = score[t - 1][s]
                var arg = s
                if (s >= 1 && score[t - 1][s - 1] > best) { best = score[t - 1][s - 1]; arg = s - 1 }
                if (s >= 2 && states[s - 1].optional && score[t - 1][s - 2] > best) { best = score[t - 1][s - 2]; arg = s - 2 }
                if (best == negInf) continue
                score[t][s] = best + emit
                back[t][s] = arg
            }
        }
        var end = S - 1
        if (score[T - 1][S - 2] > score[T - 1][S - 1]) end = S - 2
        if (score[T - 1][end] == negInf) return null

        val stateAt = IntArray(T)
        var s = end
        for (t in T - 1 downTo 0) {
            stateAt[t] = s
            if (t > 0) s = back[t][s]
        }

        // Phone segments
        val segStart = IntArray(canonical.size) { -1 }
        val segEnd = IntArray(canonical.size) { -1 }
        for (t in 0 until T) {
            val pi = states[stateAt[t]].phoneIndex
            if (pi < 0) continue
            if (segStart[pi] < 0) segStart[pi] = t
            segEnd[pi] = t
        }

        val phoneScores = ArrayList<PhonemeScore>(canonical.size)
        for ((pi, cp) in canonical.withIndex()) {
            val a = segStart[pi]
            val b = segEnd[pi]
            if (a < 0 || b < a) continue
            var gopSum = 0.0
            val avgPost = DoubleArray(post.classes)
            for (t in a..b) {
                var maxQ = negInf
                for (c in phoneClassIndices) if (post[t, c] > maxQ) maxQ = post[t, c]
                gopSum += (post[t, cp.classIndex] - maxQ).toDouble()
                for (c in phoneClassIndices) avgPost[c] += exp(post[t, c].toDouble())
            }
            val d = b - a + 1
            val gop = gopSum / d
            var bestClass = cp.classIndex
            var bestVal = -1.0
            for (c in phoneClassIndices) if (avgPost[c] > bestVal) { bestVal = avgPost[c]; bestClass = c }
            val detected = labels[bestClass].uppercase()
            val sc = (100.0 * exp(0.6 * gop)).coerceIn(0.0, 100.0).roundToInt()
            phoneScores += PhonemeScore(
                arpabet = cp.arpabet,
                ipa = PhoneticArticulationDatabase.ipaFor(cp.arpabet),
                score = sc,
                gop = gop,
                startSec = a * frameSec,
                endSec = (b + 1) * frameSec,
                detectedArpabet = if (detected != PhoneticArticulationDatabase.baseSymbol(cp.arpabet)) detected else null,
                wordIndex = cp.wordIndex,
            )
        }
        if (phoneScores.isEmpty()) return null

        val wordResults = ArrayList<WordPronunciation>()
        for ((wi, w) in words.withIndex()) {
            val phs = phoneScores.filter { it.wordIndex == wi }
            if (phs.isEmpty()) continue
            val pron = wordProns[wi]
            val expected = if (PhoneticArticulationDatabase.syllableCount(pron) >= 2) PhoneticArticulationDatabase.primaryStressSyllable(pron) else -1
            val detected = if (expected >= 0) detectStressSyllable(phs, pitch) else -1
            wordResults += WordPronunciation(
                word = w,
                phonemes = phs,
                score = phs.map { it.score }.average().roundToInt(),
                startSec = phs.first().startSec,
                endSec = phs.last().endSec,
                expectedStressSyllable = expected,
                detectedStressSyllable = detected,
            )
        }
        val mean = phoneScores.map { it.score }.average()
        return PronunciationResult(wordResults, mean, "gop")
    }

    /**
     * Stressed syllable = vowel segment with the highest combined z-score of duration, mean energy
     * (RMS dB) and mean F0 - the three acoustic correlates of English lexical stress.
     */
    fun detectStressSyllable(phonemes: List<PhonemeScore>, pitch: List<PitchFrame>): Int {
        val vowels = phonemes.filter { PhoneticArticulationDatabase.isVowel(it.arpabet) }
        if (vowels.size < 2) return -1
        val dur = vowels.map { it.endSec - it.startSec }
        val energy = vowels.map { v -> framesIn(pitch, v.startSec, v.endSec).map { it.rmsDb.toDouble() }.average().let { if (it.isNaN()) -60.0 else it } }
        val f0 = vowels.map { v -> framesIn(pitch, v.startSec, v.endSec).filter { it.voiced }.map { it.f0Hz.toDouble() }.average().let { if (it.isNaN()) 0.0 else it } }
        val zd = z(dur)
        val ze = z(energy)
        val zf = z(f0)
        var best = 0
        var bestVal = Double.NEGATIVE_INFINITY
        for (i in vowels.indices) {
            val v = 0.4 * zd[i] + 0.35 * ze[i] + 0.25 * zf[i]
            if (v > bestVal) { bestVal = v; best = i }
        }
        return best
    }

    private fun framesIn(pitch: List<PitchFrame>, a: Double, b: Double) = pitch.filter { it.timeSec >= a && it.timeSec <= b }

    private fun z(v: List<Double>): List<Double> {
        val m = v.average()
        val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        return if (sd < 1e-9) v.map { 0.0 } else v.map { (it - m) / sd }
    }

    companion object {
        /**
         * Fallback when no phoneme model is installed (reading tasks only): aligns reference words
         * with ASR words (Levenshtein) and grades each word coarsely - matched 85, substituted 40,
         * missing 25. Phone entries inherit their word's score.
         */
        fun asrAlignmentFallback(
            dictionary: CmuDictionary,
            referenceText: String,
            recognized: List<WordTiming>,
        ): PronunciationResult? {
            val ref = TextAnalysis.words(referenceText)
            if (ref.isEmpty()) return null
            val hyp = recognized.map { TextAnalysis.normalize(it.word).trim('.', ',', '!', '?') }
            val n = ref.size
            val m = hyp.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in 0..n) dp[i][0] = i
            for (j in 0..m) dp[0][j] = j
            for (i in 1..n) for (j in 1..m) {
                val c = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j - 1] + c, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
            }
            val status = IntArray(n) { 0 }       // 0 missing, 1 matched, 2 substituted
            val hypIndex = IntArray(n) { -1 }
            var i = n
            var j = m
            while (i > 0 && j > 0) {
                val c = if (ref[i - 1] == hyp[j - 1]) 0 else 1
                when {
                    dp[i][j] == dp[i - 1][j - 1] + c -> { status[i - 1] = if (c == 0) 1 else 2; hypIndex[i - 1] = j - 1; i--; j-- }
                    dp[i][j] == dp[i - 1][j] + 1 -> { status[i - 1] = 0; i-- }
                    else -> j--
                }
            }
            val words = ref.mapIndexed { wi, w ->
                val sc = when (status[wi]) { 1 -> 85; 2 -> 40; else -> 25 }
                val timing = hypIndex[wi].takeIf { it >= 0 }?.let { recognized[it] }
                val pron = dictionary.pronounce(w)
                val startT = timing?.startSec ?: 0.0
                val endT = timing?.endSec ?: 0.0
                val phs = pron.mapIndexed { k, ph ->
                    val a = startT + (endT - startT) * k / max(1, pron.size)
                    val b = startT + (endT - startT) * (k + 1) / max(1, pron.size)
                    PhonemeScore(ph, PhoneticArticulationDatabase.ipaFor(ph), sc, Double.NaN, a, b, null, wi)
                }
                WordPronunciation(w, phs, sc, startT, endT, -1, -1)
            }
            return PronunciationResult(words, words.map { it.score }.average(), "asr-alignment")
        }
    }
}
