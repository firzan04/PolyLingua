package com.polylingua.edge.domain

import com.polylingua.edge.domain.model.CefrLevel

/**
 * CEFR (Council of Europe, Companion Volume 2020) descriptors for oral production, restated
 * concisely for learner-facing explanations, plus measurable transition checklists that turn
 * each "next level" into concrete targets tracked by the rubric engine.
 */
object CefrDescriptorMatrix {

    enum class Scale(val title: String) {
        OVERALL_ORAL_PRODUCTION("Overall oral production"),
        SUSTAINED_MONOLOGUE("Sustained monologue: describing experience"),
        SPOKEN_FLUENCY("Spoken fluency"),
        PHONOLOGICAL_CONTROL("Phonological control"),
        VOCABULARY_RANGE("Vocabulary range"),
        GRAMMATICAL_ACCURACY("Grammatical accuracy"),
        COHERENCE_COHESION("Coherence and cohesion"),
    }

    private val D: Map<Scale, Map<CefrLevel, String>> = mapOf(
        Scale.OVERALL_ORAL_PRODUCTION to mapOf(
            CefrLevel.A1 to "Produces short, mostly isolated phrases about people and places.",
            CefrLevel.A2 to "Gives simple descriptions of people, routines, likes and dislikes as a short list of linked phrases and sentences.",
            CefrLevel.B1 to "Sustains a reasonably fluent, straightforward description of familiar subjects as a linear sequence of points.",
            CefrLevel.B2 to "Gives clear, detailed descriptions on a wide range of familiar subjects, expanding ideas with supporting points and relevant examples.",
            CefrLevel.C1 to "Gives clear, detailed descriptions of complex subjects, integrating sub-themes, developing particular points and closing with a suitable conclusion.",
            CefrLevel.C2 to "Produces clear, smoothly flowing, well-structured discourse whose logical organisation helps the listener notice and remember key points.",
        ),
        Scale.SUSTAINED_MONOLOGUE to mapOf(
            CefrLevel.A1 to "Describes themselves, what they do and where they live with simple words and set phrases, ideally after preparing.",
            CefrLevel.A2 to "Describes everyday surroundings, plans, habits and past activities as a simple list of points.",
            CefrLevel.B1 to "Relates experiences, feelings, plans and stories as a sequence of points and gives reasons for opinions.",
            CefrLevel.B2 to "Describes a wide range of subjects in detail and explains the personal significance of events and experiences.",
            CefrLevel.C1 to "Gives elaborate descriptions and narratives, weaving in sub-themes and rounding them off appropriately.",
            CefrLevel.C2 to "Gives clear, smoothly flowing, elaborate and often memorable descriptions.",
        ),
        Scale.SPOKEN_FLUENCY to mapOf(
            CefrLevel.A1 to "Manages very short, isolated, mostly memorised utterances with frequent pauses to search for expressions.",
            CefrLevel.A2 to "Is understood in very short utterances, though pauses, false starts and reformulation are very noticeable.",
            CefrLevel.B1 to "Keeps going comprehensibly, but pauses for planning and repair are evident, especially in longer stretches.",
            CefrLevel.B2 to "Speaks at a fairly even tempo; may hesitate while searching for expressions but long pauses are rare.",
            CefrLevel.C1 to "Speaks fluently and spontaneously, almost effortlessly; only conceptually difficult topics slow the natural flow.",
            CefrLevel.C2 to "Speaks at length with a natural, effortless flow, pausing only to choose the most precise idea or wording.",
        ),
        Scale.PHONOLOGICAL_CONTROL to mapOf(
            CefrLevel.A1 to "A very small repertoire of learnt words can be understood with effort by listeners used to the speaker's language group.",
            CefrLevel.A2 to "Generally clear enough to be understood, but listeners sometimes need repetition; strong first-language influence on stress, rhythm and intonation.",
            CefrLevel.B1 to "Generally intelligible; stress and intonation do not block understanding, though individual sounds and less familiar words are regularly mispronounced.",
            CefrLevel.B2 to "Generally uses appropriate stress and intonation and articulates sounds clearly; accent shows first-language influence but has little effect on intelligibility.",
            CefrLevel.C1 to "Controls the full range of phonological features well enough to be intelligible throughout and can vary stress and intonation to express precise meaning.",
            CefrLevel.C2 to "Controls the full range of phonological features, including prosody, with precision so that finer shades of meaning are clear; retained accent never affects intelligibility.",
        ),
        Scale.VOCABULARY_RANGE to mapOf(
            CefrLevel.A1 to "Has a basic repertoire of isolated words and phrases for concrete situations.",
            CefrLevel.A2 to "Has enough vocabulary for routine everyday transactions on familiar topics.",
            CefrLevel.B1 to "Has enough vocabulary to talk, with some circumlocution, about most topics of everyday life.",
            CefrLevel.B2 to "Has a good range of vocabulary for general topics and can vary formulation to avoid repetition, though gaps still cause hesitation.",
            CefrLevel.C1 to "Has a broad lexical repertoire, readily overcomes gaps with paraphrase and commands idiomatic and colloquial expressions.",
            CefrLevel.C2 to "Has a very broad repertoire including idioms and colloquialisms and is aware of connotative levels of meaning.",
        ),
        Scale.GRAMMATICAL_ACCURACY to mapOf(
            CefrLevel.A1 to "Shows limited control of a few simple, memorised structures.",
            CefrLevel.A2 to "Uses some simple structures correctly but still makes basic mistakes systematically.",
            CefrLevel.B1 to "Communicates with reasonable accuracy in familiar contexts, with noticeable first-language influence.",
            CefrLevel.B2 to "Shows relatively high grammatical control; mistakes do not cause misunderstanding and most slips are self-corrected.",
            CefrLevel.C1 to "Consistently maintains high accuracy; errors are rare, hard to spot and usually corrected.",
            CefrLevel.C2 to "Maintains consistent control of complex language even while attention is focused on ideas.",
        ),
        Scale.COHERENCE_COHESION to mapOf(
            CefrLevel.A1 to "Links words or groups of words with very basic connectors such as 'and' or 'then'.",
            CefrLevel.A2 to "Links groups of words with simple connectors such as 'and', 'but' and 'because'.",
            CefrLevel.B1 to "Links a series of shorter elements into a connected, linear sequence of points.",
            CefrLevel.B2 to "Uses a limited number of cohesive devices to build clear, coherent discourse, with some jumpiness in long turns.",
            CefrLevel.C1 to "Produces clear, smoothly flowing, well-structured speech with controlled use of connectors and cohesive devices.",
            CefrLevel.C2 to "Builds fully coherent discourse using a wide range of organisational patterns and cohesive devices.",
        ),
    )

    fun descriptor(scale: Scale, level: CefrLevel): String = D[scale]?.get(level) ?: ""

    fun allFor(level: CefrLevel): List<Pair<String, String>> = Scale.entries.map { it.title to descriptor(it, level) }

    /** Measurable milestones for moving from [level] to the next one. */
    fun transitionChecklist(level: CefrLevel): List<String> = when (level) {
        CefrLevel.A1 -> listOf(
            "Speak in complete simple sentences (subject + verb + object) for at least 20 seconds without stopping.",
            "Link ideas with 'and', 'but' and 'because' instead of listing single words.",
            "Reach about 60 words per minute on familiar topics (family, daily routine, hometown).",
            "Learn 300+ everyday A2 words for routines, shopping, food and travel.",
            "Attempt the /θ/ and /ð/ sounds (tongue between the teeth) in 'think', 'this', 'three'.",
        )
        CefrLevel.A2 -> listOf(
            "Sustain 45-60 seconds on a familiar topic at 80-100 words per minute.",
            "Give a reason and an example for every opinion ('because...', 'for example...').",
            "Use the past simple and present perfect accurately when narrating experiences.",
            "Cut unfilled pauses longer than 600 ms to fewer than 6 per minute.",
            "Use at least three different linking words beyond 'and' and 'then'.",
        )
        CefrLevel.B1 -> listOf(
            "Speak at 110-130 WPM with a phonation-time ratio of at least 0.65.",
            "Put complex structures (relative clauses, conditionals, passives) in 35%+ of your sentences.",
            "Keep at least 70% of clauses error-free (watch agreement and articles).",
            "Make at least 10% of your content words B2-level vocabulary.",
            "Vary your intonation: pitch variation of at least 2 semitones, avoiding a flat monotone.",
            "Use four or more discourse markers beyond and/but/so (however, for instance, as a result...).",
        )
        CefrLevel.B2 -> listOf(
            "Speak at 130-165 WPM with a phonation-time ratio of at least 0.75.",
            "Limit unfilled pauses to 2 per minute or fewer, keeping them at clause boundaries.",
            "Use 4%+ C1/C2 vocabulary and natural idiomatic collocations ('a double-edged sword', 'pressing concern').",
            "Keep 85%+ of clauses error-free.",
            "Show range: passives, modal perfects ('should have done') and participle clauses.",
            "Place word stress correctly in 80%+ of multi-syllable words.",
        )
        CefrLevel.C1 -> listOf(
            "Keep a phonation-time ratio of 0.80+ and pause only to plan content.",
            "Use 6%+ C1/C2 vocabulary with precise collocation and register control.",
            "Deploy advanced structures naturally: inversion, cleft sentences, mixed conditionals.",
            "Reach a mean phoneme (GOP) score of 88%+ and use intonation to signal nuance and attitude.",
            "Round off long turns with a clear, memorable conclusion.",
        )
        CefrLevel.C2 -> listOf(
            "You are at the top of the scale: maintain precision across unfamiliar abstract topics.",
            "Experiment with stylistic variation (understatement, hedging, rhetorical questions).",
            "Keep connected-speech features (linking, weak forms, assimilation) consistent at speed.",
        )
    }
}
