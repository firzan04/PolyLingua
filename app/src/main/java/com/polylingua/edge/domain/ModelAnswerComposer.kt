package com.polylingua.edge.domain

import com.polylingua.edge.domain.model.GrammarIssue
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.domain.model.VocabularyUpgrade

/**
 * Deterministic, fully offline rewrite engine. Produces (a) a "Natural Native Polish" of the
 * learner's own answer (grammar fixes + vocabulary upgrades + cohesion) and (b) a "Band 9" version
 * that layers C2 structures (cleft opening, inversion, concession, conditional, idiomatic close)
 * around the learner's ideas. When an on-device LLM is installed its rewrites replace these.
 */
object ModelAnswerComposer {

    private val COHESION_OPENERS = listOf("What's more,", "On top of that,", "That said,", "Beyond that,", "Ultimately,")
    private val MARKER_STARTS: Set<String> by lazy { DiscourseMarkerLexicon.markers.map { it.tokens.first() }.toSet() }

    fun naturalPolish(text: String, issues: List<GrammarIssue>, upgrades: List<VocabularyUpgrade>): String {
        if (text.isBlank()) return ""
        data class Edit(val start: Int, val end: Int, val replacement: String)
        val edits = ArrayList<Edit>()
        for (i in issues) if (i.suggestion.isNotBlank()) edits += Edit(i.start, i.end, i.suggestion)
        var singleWordBudget = 4
        for (u in upgrades) {
            if (!u.autoApply) continue
            if (edits.any { it.start < u.end && u.start < it.end }) continue
            val isPhrase = u.original.contains(' ')
            if (!isPhrase) {
                if (singleWordBudget <= 0) continue
                singleWordBudget--
            }
            val rep = u.replacements.firstOrNull() ?: continue
            edits += Edit(u.start, u.end, matchCase(u.original, rep))
        }
        val sb = StringBuilder(text)
        for (e in edits.sortedByDescending { it.start }) {
            if (e.start in 0..sb.length && e.end in e.start..sb.length) sb.replace(e.start, e.end, e.replacement)
        }
        return tidy(sb.toString(), addCohesion = true)
    }

    /** Removes fillers and stutters, fixes capitalisation/punctuation, optionally adds cohesion. */
    fun tidy(raw: String, addCohesion: Boolean): String {
        var s = raw.replace(Regex("\\b(?:uh+|um+|erm|er|ah|eh|hmm+|mm+)\\b[,]?\\s*", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\b(\\w+)(\\s+\\1\\b)+", RegexOption.IGNORE_CASE), "$1")      // "I I think"
        s = s.replace(Regex("\\s+([,.!?])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
        if (s.isEmpty()) return s
        val sentences = TextAnalysis.sentences(s).map { it.text.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var opener = 0
        for ((idx, sentence) in sentences.withIndex()) {
            var t = TextAnalysis.ensureTerminalPunctuation(TextAnalysis.capitalizeFirst(sentence))
            t = t.replace(Regex("\\bi\\b"), "I")
            val first = TextAnalysis.words(t).firstOrNull() ?: ""
            if (addCohesion && idx > 0 && idx % 2 == 1 && first !in MARKER_STARTS && sentences.size > 2) {
                t = COHESION_OPENERS[opener % COHESION_OPENERS.size] + " " + t.replaceFirstChar { it.lowercaseChar() }.let { lowered ->
                    if (lowered.startsWith("i ") || lowered.startsWith("i'")) "I" + lowered.substring(1) else lowered
                }
                opener++
            }
            out += t
        }
        return out.joinToString(" ")
    }

    fun band9Version(polished: String, context: TaskContext): String {
        val sentences = TextAnalysis.sentences(polished).map { it.text.trim() }.filter { it.isNotEmpty() }
        val keywords = keywords(polished).ifEmpty { keywords(context.prompt) }
        val k1 = keywords.getOrNull(0) ?: "this issue"
        val topic = context.topic.ifBlank { k1 }
        val k2 = keywords(context.prompt).firstOrNull { it != k1 } ?: topic
        if (sentences.isEmpty()) {
            return "What I find genuinely compelling about $topic is the way it shapes everyday decisions. " +
                "Not only does it influence how we live, but it also reflects what we value as a society. " +
                "Admittedly, opinions differ; nevertheless, I am convinced its significance will only grow in the years to come."
        }
        val out = ArrayList<String>()
        out += "If I'm being completely candid, what strikes me most about $topic is this: " + lowerFirst(stripLeadingMarker(sentences[0]))
        for ((idx, s) in sentences.drop(1).withIndex()) {
            out += when (idx) {
                0 -> "Not only that, but " + lowerFirst(stripLeadingMarker(s))
                1 -> "Admittedly, there are drawbacks; nevertheless, " + lowerFirst(stripLeadingMarker(s))
                else -> s
            }
        }
        out += "Were I to give one piece of advice, it would be to treat $k2 not as a burden but as an opportunity - " +
            "because, at the end of the day, how we approach $k1 says a great deal about who we are."
        return out.joinToString(" ")
    }

    private val LIGHT_WORDS = setOf("time", "people", "thing", "things", "way", "ways", "day", "days", "year", "years",
        "lot", "think", "know", "want", "make", "really", "something", "everything", "good", "great", "would", "could")

    /** Removes a sentence-initial discourse marker ("Well,", "However,") before re-framing it. */
    fun stripLeadingMarker(sentence: String): String {
        val tokens = TextAnalysis.tokenize(sentence)
        if (tokens.isEmpty()) return sentence
        val lowers = tokens.map { it.lower }
        val found = DiscourseMarkerLexicon.findMarkers(lowers.take(6)).firstOrNull() ?: return sentence
        val n = found.tokens.size
        if (lowers.take(n) != found.tokens) return sentence
        val after = sentence.substring(tokens[n - 1].end).trimStart(',', ' ')
        return if (after.length > 3) after else sentence
    }

    /** Most frequent content words (crude keyword extraction for template slots). */
    fun keywords(text: String, n: Int = 3): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (w in TextAnalysis.words(text)) {
            if (!TextAnalysis.isContentWord(w) || w.length < 4 || w in LIGHT_WORDS) continue
            counts[w] = (counts[w] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }.take(n)
    }

    private fun lowerFirst(s: String): String {
        if (s.isEmpty()) return s
        val lowered = s.replaceFirstChar { it.lowercaseChar() }
        return if (lowered.startsWith("i ") || lowered.startsWith("i'")) "I" + lowered.substring(1) else lowered
    }

    private fun matchCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) TextAnalysis.capitalizeFirst(replacement) else replacement
}
