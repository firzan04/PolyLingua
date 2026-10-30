package com.polylingua.edge.data.seed

/** One IELTS Part 2 task card: topic + four prompts ("You should say ..."). */
data class CueCard(val topic: String, val bullets: List<String>)

/**
 * Idea bank used to compose Band 6 / 8 / 9 model answers.
 * pos1 starts with "it" + verb so it can be inverted ("Not only does it ...").
 * personal is a past-participle phrase that also reads as past simple ("studied at ...").
 */
data class Ideas(
    val subject: String,
    val pos1: String,
    val pos2: String,
    val neg1: String,
    val neg2: String,
    val example: String,
    val personal: String,
    val future: String,
    val c1: List<String>,
    val policy: String,
    val idiom: String,
)

data class Domain(
    val key: String,
    val title: String,
    val keywords: List<String>,
    val part1: List<String>,
    val cueCards: List<CueCard>,
    val part3: List<String>,
    val shadowing: List<Pair<String, String>>,   // English line to Bahasa Indonesia gloss
    val roleplay: List<String>,
    val debate: List<String>,
    val elsa: List<String>,
    val ideas: Ideas,
) {
    init {
        require(part1.size == 5 && cueCards.size == 2 && part3.size == 5 && shadowing.size == 5 &&
            roleplay.size == 3 && debate.size == 3 && elsa.size == 4 && ideas.c1.size >= 2) { "Incomplete domain $key" }
        require(cueCards.all { it.bullets.size == 4 }) { "Cue cards need 4 bullets ($key)" }
    }
}

/** The 34 IELTS thematic domains of the built-in 1,020-case curriculum. */
object CurriculumContent {
    val ALL: List<Domain> by lazy { CurriculumDomainsA.LIST + CurriculumDomainsB.LIST }
}
