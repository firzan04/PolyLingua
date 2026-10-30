package com.polylingua.edge.domain

import com.polylingua.edge.domain.model.IeltsCriterion

/**
 * The 9 x 4 IELTS Speaking band descriptor matrix (public version), restated in plain coaching
 * language so every diagnostic report can explain *why* a band was awarded. Paraphrased from the
 * official descriptors (IELTS partners); see ielts.org for the authoritative wording.
 */
object IeltsBandRubricRepository {

    private val FC = mapOf(
        9 to "Speaks smoothly with hardly any repetition or self-correction; pauses serve to plan ideas, never to search for words or grammar. Responses are fully appropriate, cohesive and logically extended.",
        8 to "Speaks at length with rare repetition or self-correction. Occasional hesitation for language, but most pauses are about content. Ideas develop coherently and relevantly.",
        7 to "Produces long turns readily without visible strain. Some mid-sentence hesitation, repetition or self-correction when reaching for language, but coherence holds. Uses discourse markers and connectives flexibly.",
        6 to "Willing to speak at length, but coherence sometimes slips because of hesitation, repetition or self-correction. Uses a range of linking devices, not always appropriately.",
        5 to "Usually keeps going but leans on repetition, self-correction or slow delivery; pauses often fall mid-sentence while searching for basic words. Overuses certain connectives; simple language flows, complex language breaks down.",
        4 to "Cannot keep going without noticeable pauses; speech may be slow with frequent repetition and self-correction. Links simple sentences with the same connectives; coherence sometimes breaks down.",
        3 to "Long and frequent pauses while searching for words. Can barely link simple sentences or go beyond short answers; often fails to convey a basic message.",
        2 to "Pauses before almost every word; only isolated words are recognisable and communication is minimal.",
        1 to "No meaningful communication; speech is incoherent.",
        0 to "Did not attend or did not attempt the test.",
    )

    private val LR = mapOf(
        9 to "Uses vocabulary with complete flexibility and precision in every context; consistently accurate and idiomatic.",
        8 to "Wide vocabulary used readily to express precise meaning on any topic. Less common and idiomatic items are used skilfully, with only occasional slips in word choice or collocation. Paraphrases effectively.",
        7 to "Uses vocabulary flexibly across a variety of topics, with some less common and idiomatic language and awareness of style and collocation, though some choices are inappropriate. Paraphrases effectively.",
        6 to "Has enough vocabulary to discuss topics at length; meaning is clear despite some inappropriate choices. Generally paraphrases successfully.",
        5 to "Can discuss familiar and unfamiliar topics but with limited flexibility; attempts paraphrase with mixed success.",
        4 to "Manages familiar topics but conveys only basic meaning on unfamiliar ones; frequent errors in word choice; rarely paraphrases.",
        3 to "Relies on simple vocabulary, mainly to give personal information; inadequate for less familiar topics.",
        2 to "Very limited vocabulary: mostly isolated words or memorised phrases.",
        1 to "Only a few isolated words; no communication possible.",
        0 to "Did not attend or did not attempt the test.",
    )

    private val GRA = mapOf(
        9 to "Uses structures precisely and accurately throughout, apart from the kind of slips native speakers make.",
        8 to "Uses a wide range of structures flexibly. Most sentences are error-free; errors are occasional and non-systematic, and a few basic ones may remain.",
        7 to "Uses a range of structures flexibly; error-free sentences are frequent. Simple and complex sentences are used effectively despite some errors; a few basic errors persist.",
        6 to "Mixes short and complex sentence forms with a variety of structures but limited flexibility. Errors are frequent in complex structures yet rarely impede communication.",
        5 to "Controls basic sentence forms fairly accurately. Complex structures are attempted but limited in range, nearly always contain errors and may need reformulating.",
        4 to "Produces basic sentences and some error-free short utterances. Subordinate clauses are rare, turns are short and repetitive, and errors are frequent.",
        3 to "Attempts basic sentence forms, but errors are numerous except in apparently memorised phrases.",
        2 to "No evidence of basic sentence forms.",
        1 to "No rateable language unless memorised.",
        0 to "Did not attend or did not attempt the test.",
    )

    private val P = mapOf(
        9 to "Uses the full range of pronunciation features to convey precise or subtle meaning; connected speech is flexible throughout. Effortlessly understood; accent has no effect on intelligibility.",
        8 to "Uses a wide range of pronunciation features for precise meaning; sustains appropriate rhythm with flexible stress and intonation across long utterances, with occasional lapses. Easily understood; accent has minimal effect.",
        7 to "Shows all the positive features of Band 6 and some, but not all, of Band 8.",
        6 to "Uses a range of pronunciation features with variable control. Chunking is generally appropriate, but rhythm may suffer from weak stress-timing or a rushed pace; intonation and stress are effective at times but not sustained. Occasional mispronounced words or sounds reduce clarity, yet the speaker is generally understood without much effort.",
        5 to "Shows all the positive features of Band 4 and some, but not all, of Band 6.",
        4 to "Uses some acceptable features but the range is limited; frequent lapses in rhythm and limited control of intonation and stress. Frequent mispronunciation reduces clarity; the listener must make an effort and some stretches may be unintelligible.",
        3 to "Shows some features of Band 2 and some, but not all, of the positive features of Band 4.",
        2 to "Few acceptable pronunciation features (possibly because the sample is too short). Delivery problems disrupt connected speech; most words are mispronounced and speech is often unintelligible.",
        1 to "Occasional recognisable words or sounds, but no overall meaning is conveyed; unintelligible.",
        0 to "Did not attend or did not attempt the test.",
    )

    fun descriptor(criterion: IeltsCriterion, band: Double): String {
        val whole = band.toInt().coerceIn(0, 9)
        val table = when (criterion) {
            IeltsCriterion.FC -> FC
            IeltsCriterion.LR -> LR
            IeltsCriterion.GRA -> GRA
            IeltsCriterion.P -> P
        }
        val base = table[whole] ?: ""
        return if (band - whole >= 0.5 && whole < 9) "$base (Between Band $whole and Band ${whole + 1}: some features of the higher band are present.)" else base
    }

    fun matrix(): Map<IeltsCriterion, Map<Int, String>> =
        mapOf(IeltsCriterion.FC to FC, IeltsCriterion.LR to LR, IeltsCriterion.GRA to GRA, IeltsCriterion.P to P)

    /** Official test structure and examiner timing protocol. */
    data class PartProtocol(val part: Int, val title: String, val minSec: Int, val maxSec: Int, val prepSec: Int, val description: String)

    val TEST_PROTOCOL: List<PartProtocol> = listOf(
        PartProtocol(1, "Introduction and interview", 240, 300, 0,
            "The examiner confirms your identity, then asks short questions on about three familiar topics such as home, work or study, and interests."),
        PartProtocol(2, "Individual long turn (cue card)", 180, 240, 60,
            "You receive a task card with a topic and prompts, get one minute to prepare notes, then speak for up to two minutes. The examiner may ask one or two rounding-off questions."),
        PartProtocol(3, "Two-way discussion", 240, 300, 0,
            "The examiner asks more abstract questions linked to the Part 2 topic, inviting you to justify opinions, speculate and analyse."),
    )

    const val TOTAL_TEST_MINUTES = "11-14"

    val RATING_NOTES: List<String> = listOf(
        "A band is awarded only when a speaker fully fits the positive features of that band's descriptor.",
        "Ratings reflect average performance across all three parts of the test.",
        "Overall band = mean of the four criteria; .25 rounds up to the next half band and .75 to the next whole band.",
    )
}
