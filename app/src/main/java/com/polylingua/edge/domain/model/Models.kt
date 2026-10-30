package com.polylingua.edge.domain.model

import kotlin.math.floor

/** CEFR common reference levels, mapped to IELTS speaking bands exactly as specified. */
enum class CefrLevel(val rank: Int, val minBand: Double, val maxBand: Double) {
    A1(0, 0.0, 2.5),
    A2(1, 3.0, 3.5),
    B1(2, 4.0, 5.0),
    B2(3, 5.5, 6.5),
    C1(4, 7.0, 8.0),
    C2(5, 8.5, 9.0);

    /** Representative IELTS target band for content authored at this level. */
    val midBand: Double
        get() = when (this) {
            A1 -> 2.5; A2 -> 3.5; B1 -> 4.5; B2 -> 6.0; C1 -> 7.5; C2 -> 9.0
        }

    fun next(): CefrLevel? = entries.getOrNull(ordinal + 1)

    companion object {
        /** A1: 1.0-2.5, A2: 3.0-3.5, B1: 4.0-5.0, B2: 5.5-6.5, C1: 7.0-8.0, C2: 8.5-9.0. */
        fun fromBand(band: Double): CefrLevel = when {
            band < 2.75 -> A1
            band < 3.75 -> A2
            band < 5.25 -> B1
            band < 6.75 -> B2
            band < 8.25 -> C1
            else -> C2
        }

        fun parse(raw: String?): CefrLevel? {
            val s = raw?.trim()?.uppercase()?.replace(" ", "") ?: return null
            if (s.isEmpty()) return null
            return entries.firstOrNull { s == it.name || s.startsWith(it.name) }
        }
    }
}

/** Practice module / exercise type (Excel column 14). */
enum class ModuleType(val label: String, val speaking: Boolean) {
    STIMULER_PART1("IELTS Part 1 Interview", true),
    STIMULER_PART2_CUECARD("IELTS Part 2 Cue Card", true),
    STIMULER_PART3("IELTS Part 3 Discussion", true),
    ELSA_PHONEME_DRILL("ELSA Phoneme Drill", true),
    HELLOTALK_ROLEPLAY("HelloTalk Roleplay", true),
    TANDEM_DEBATE("Tandem Debate", true),
    CAKE_SHADOWING("Cake Shadowing", true),
    QUIZ_MCQ("Quiz MCQ", false),
    FLASHCARD("Flashcard", false);

    val defaultPrepSec: Int get() = if (this == STIMULER_PART2_CUECARD) 60 else 0
    val defaultSpeakSec: Int
        get() = when (this) {
            STIMULER_PART2_CUECARD -> 120
            STIMULER_PART1 -> 45
            ELSA_PHONEME_DRILL, CAKE_SHADOWING -> 20
            QUIZ_MCQ, FLASHCARD -> 0
            else -> 60
        }

    companion object {
        fun parse(raw: String?): ModuleType? {
            val s = raw?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_') ?: return null
            if (s.isEmpty()) return null
            entries.firstOrNull { it.name == s }?.let { return it }
            return when {
                s.contains("CUE") || s.contains("PART2") || s.contains("PART_2") -> STIMULER_PART2_CUECARD
                s.contains("PART3") || s.contains("PART_3") -> STIMULER_PART3
                s.contains("PART1") || s.contains("PART_1") || s.contains("STIMULER") -> STIMULER_PART1
                s.contains("ELSA") || s.contains("PHONEME") -> ELSA_PHONEME_DRILL
                s.contains("HELLO") || s.contains("ROLE") -> HELLOTALK_ROLEPLAY
                s.contains("TANDEM") || s.contains("DEBATE") -> TANDEM_DEBATE
                s.contains("CAKE") || s.contains("SHADOW") -> CAKE_SHADOWING
                s.contains("FLASH") -> FLASHCARD
                s.contains("QUIZ") || s.contains("MCQ") -> QUIZ_MCQ
                else -> null
            }
        }
    }
}

/**
 * One practice case = one row of the 11/16-column master workbook. In quiz mode columns C-F are
 * the options and H-K the per-option explanations; in speaking mode the same columns carry the
 * IPA target, three model answers, and the four criterion-specific coaching notes.
 */
data class PracticeCase(
    val id: Long = 0,
    val sheetName: String,
    val number: Int,
    val prompt: String,
    val colC: String,              // Pilihan_A | Target_IPA_Phonemes
    val colD: String,              // Pilihan_B | Model_Answer_Band_6_B2
    val colE: String,              // Pilihan_C | Model_Answer_Band_8_C1
    val colF: String,              // Pilihan_D | Model_Answer_Band_9_C2
    val colG: String,              // Jawaban_Benar | Primary_Target_Transcript
    val colH: String,              // Pembahasan_A | Feedback_Fluency_Rubric
    val colI: String,              // Pembahasan_B | Feedback_Lexical_Vocabulary
    val colJ: String,              // Pembahasan_C | Feedback_Grammar_Structures
    val colK: String,              // Pembahasan_D | Feedback_Pronunciation_Intonation
    val cefr: CefrLevel,
    val targetBand: Double,
    val module: ModuleType,
    val prepSec: Int,
    val speakSec: Int,
    val topic: String = "",
    val translationId: String = "",   // Bahasa Indonesia gloss (shadowing subtitles)
) {
    val options: List<String> get() = listOf(colC, colD, colE, colF)

    /** Index 0..3 of the correct quiz option, resolved from "A".."D", "1".."4" or the option text. */
    fun correctOptionIndex(): Int {
        val g = colG.trim()
        if (g.length == 1) {
            val c = g.uppercase()[0]
            if (c in 'A'..'D') return c - 'A'
            if (c in '1'..'4') return c - '1'
        }
        val lowered = g.lowercase()
        if (lowered.startsWith("pilihan_") || lowered.startsWith("option ")) {
            val c = lowered.last().uppercaseChar()
            if (c in 'A'..'D') return c - 'A'
        }
        val idx = options.indexOfFirst { it.trim().equals(g, ignoreCase = true) }
        return if (idx >= 0) idx else 0
    }

    fun explanationFor(optionIndex: Int): String = when (optionIndex) {
        0 -> colH; 1 -> colI; 2 -> colJ; 3 -> colK; else -> ""
    }

    val referenceTranscript: String get() = colG.ifBlank { prompt }
}

enum class IeltsCriterion(val code: String, val title: String) {
    FC("FC", "Fluency & Coherence"),
    LR("LR", "Lexical Resource"),
    GRA("GRA", "Grammatical Range & Accuracy"),
    P("P", "Pronunciation"),
}

/** IELTS rounding helpers. */
object BandMath {
    /** Criterion scores: nearest 0.5 (ties round up), clamped to 0..9. */
    fun roundHalf(x: Double): Double = (floor(x * 2.0 + 0.5 + 1e-9) / 2.0).coerceIn(0.0, 9.0)

    /**
     * Overall band = mean of the four criteria; .25 rounds up to .5 and .75 rounds up to the next
     * whole band (e.g. 6.125 -> 6.0, 6.25 -> 6.5, 6.625 -> 6.5, 6.75 -> 7.0).
     */
    fun overall(fc: Double, lr: Double, gra: Double, p: Double): Double = roundHalf((fc + lr + gra + p) / 4.0)

    /** Piecewise-linear interpolation over (x, y) knots sorted by x; clamped at both ends. */
    fun interp(x: Double, knots: List<Pair<Double, Double>>): Double {
        if (knots.isEmpty()) return 0.0
        if (x <= knots.first().first) return knots.first().second
        if (x >= knots.last().first) return knots.last().second
        for (i in 1 until knots.size) {
            val (x1, y1) = knots[i]
            val (x0, y0) = knots[i - 1]
            if (x <= x1) {
                val t = if (x1 == x0) 0.0 else (x - x0) / (x1 - x0)
                return y0 + t * (y1 - y0)
            }
        }
        return knots.last().second
    }
}

// ------------------------------------------------------------------ speech analysis inputs

data class WordTiming(val word: String, val startSec: Double, val endSec: Double)

data class SpeechSegment(val startSec: Double, val endSec: Double) {
    val duration: Double get() = (endSec - startSec).coerceAtLeast(0.0)
}

/** One analysis frame of the prosody tracker. f0Hz = 0 for unvoiced frames. */
data class PitchFrame(val timeSec: Double, val f0Hz: Float, val probability: Float, val rmsDb: Float) {
    val voiced: Boolean get() = f0Hz > 0f
}

/** Per-phoneme GOP verdict. score 0..100; band GREEN >= 80, AMBER 50..79, RED < 50. */
data class PhonemeScore(
    val arpabet: String,
    val ipa: String,
    val score: Int,
    val gop: Double,
    val startSec: Double,
    val endSec: Double,
    val detectedArpabet: String?,
    val wordIndex: Int,
) {
    val grade: PhonemeGrade
        get() = when {
            score >= 80 -> PhonemeGrade.GREEN
            score >= 50 -> PhonemeGrade.AMBER
            else -> PhonemeGrade.RED
        }
}

enum class PhonemeGrade { GREEN, AMBER, RED }

data class WordPronunciation(
    val word: String,
    val phonemes: List<PhonemeScore>,
    val score: Int,
    val startSec: Double,
    val endSec: Double,
    val expectedStressSyllable: Int,      // -1 if monosyllabic/unknown
    val detectedStressSyllable: Int,      // -1 if not measurable
)

data class PronunciationResult(
    val words: List<WordPronunciation>,
    val meanScore: Double,
    val method: String,                    // "gop" | "asr-alignment"
)

/** Everything the rubric engine needs from one recorded response. */
data class SpeechSample(
    val transcript: String,
    val words: List<WordTiming>,
    val speechSegments: List<SpeechSegment>,
    val totalDurationSec: Double,
    val pitch: List<PitchFrame>,
    val pronunciation: PronunciationResult?,
    val referenceText: String? = null,
    /** Hesitation sounds ("uh", "erm") found acoustically; ASR models usually drop them from text. */
    val acousticFilledPauses: Int = 0,
)

data class TaskContext(
    val module: ModuleType,
    val prompt: String,
    val topic: String = "",
    val targetBand: Double = 6.5,
    val allocatedSec: Int = 60,
)

// --------------------------------------------------------------------- rubric engine outputs

data class FluencyMetrics(
    val wordCount: Int,
    val wpm: Double,
    val phonationTimeRatio: Double,
    val silentPauseCount: Int,
    val silentPausesPerMin: Double,
    val filledPauseCount: Int,
    val filledPauseRatio: Double,
    val discourseMarkers: List<String>,
    val weightedMarkerVariety: Double,
    val meanLengthOfRun: Double,
    val speakingTimeSec: Double,
)

data class VocabularyUpgrade(
    val original: String,
    val start: Int,
    val end: Int,
    val replacements: List<String>,
    val topic: String,
    val autoApply: Boolean = true,
)

data class LexicalMetrics(
    val tokenCount: Int,
    val mattr: Double,
    val cefrDistribution: Map<CefrLevel, Double>,
    val advancedRatio: Double,
    val c1PlusRatio: Double,
    val collocationsPer100: Double,
    val collocationHits: List<String>,
    val basicOveruseRatio: Double,
    val upgrades: List<VocabularyUpgrade>,
)

data class DetectedStructure(val type: String, val excerpt: String)

data class GrammarIssue(
    val excerpt: String,
    val suggestion: String,
    val explanation: String,
    val start: Int,
    val end: Int,
    val source: String = "rule",
)

data class GrammarMetrics(
    val sentenceCount: Int,
    val clauseCount: Int,
    val complexSentenceRatio: Double,
    val errorFreeClauseRatio: Double,
    val structures: List<DetectedStructure>,
    val distinctStructureCount: Int,
    val issues: List<GrammarIssue>,
)

data class PronunciationMetrics(
    val meanPhonemeScore: Double,
    val wordStressAccuracy: Double,
    val pitchSdSemitones: Double,
    val meanF0Hz: Double,
    val monotone: Boolean,
    val method: String,
)

data class CriterionScore(
    val criterion: IeltsCriterion,
    val band: Double,
    val raw: Double,
    val descriptor: String,
    val evidence: List<String>,
    val nextSteps: List<String>,
)

data class SpeakingAssessment(
    val fc: CriterionScore,
    val lr: CriterionScore,
    val gra: CriterionScore,
    val p: CriterionScore,
    val overallBand: Double,
    val cefr: CefrLevel,
    val cefrRationale: List<String>,
    val nextLevelChecklist: List<String>,
    val fluency: FluencyMetrics,
    val lexical: LexicalMetrics,
    val grammar: GrammarMetrics,
    val pronunciation: PronunciationMetrics,
    val naturalPolish: String,
    val band9Version: String,
    val warnings: List<String>,
) {
    val criteria: List<CriterionScore> get() = listOf(fc, lr, gra, p)
}
