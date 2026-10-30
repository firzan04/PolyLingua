package com.polylingua.edge.domain

/** Lightweight, allocation-conscious text utilities shared by the rubric engines. */
object TextAnalysis {

    data class Token(val text: String, val lower: String, val start: Int, val end: Int)

    data class SentenceSpan(val text: String, val start: Int, val end: Int)

    private val wordRegex = Regex("[A-Za-z0-9]+(?:['’\\-][A-Za-z0-9]+)*")

    fun normalize(s: String): String = s.replace('’', '\'').replace('‘', '\'').lowercase()

    fun tokenize(text: String): List<Token> =
        wordRegex.findAll(text).map { m ->
            Token(m.value, normalize(m.value), m.range.first, m.range.last + 1)
        }.toList()

    fun words(text: String): List<String> = tokenize(text).map { it.lower }

    /**
     * Sentence spans. Uses terminal punctuation when present (punctuation-restored ASR); for raw
     * unpunctuated ASR text it segments before clause-initial connectors, max ~20 words a chunk.
     */
    fun sentences(text: String): List<SentenceSpan> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val hasPunct = trimmed.count { it == '.' || it == '!' || it == '?' } >= 1 &&
            (trimmed.count { it == '.' || it == '!' || it == '?' } > 1 || tokenize(trimmed).size <= 25 ||
                trimmed.last() in ".!?")
        val out = mutableListOf<SentenceSpan>()
        if (hasPunct) {
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val terminal = c == '.' || c == '!' || c == '?' || c == '\n'
                if (terminal) {
                    // avoid splitting decimals like 6.5
                    val decimal = c == '.' && i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()
                    if (!decimal) {
                        val seg = text.substring(start, i + 1)
                        if (seg.any { it.isLetterOrDigit() }) out += SentenceSpan(seg.trim(), start, i + 1)
                        start = i + 1
                    }
                }
                i++
            }
            if (start < text.length) {
                val seg = text.substring(start)
                if (seg.any { it.isLetterOrDigit() }) out += SentenceSpan(seg.trim(), start, text.length)
            }
            return out
        }
        val tokens = tokenize(text)
        val breakers = setOf("so", "but", "because", "and", "then", "however", "also", "actually", "well")
        var chunkStart = 0
        var count = 0
        for ((idx, tok) in tokens.withIndex()) {
            val shouldBreak = count >= 6 && (tok.lower in breakers) || count >= 20
            if (shouldBreak && idx > 0) {
                val s = tokens[chunkStart].start
                val e = tokens[idx - 1].end
                out += SentenceSpan(text.substring(s, e), s, e)
                chunkStart = idx
                count = 0
            }
            count++
        }
        if (chunkStart < tokens.size) {
            val s = tokens[chunkStart].start
            val e = tokens.last().end
            out += SentenceSpan(text.substring(s, e), s, e)
        }
        return out
    }

    val FILLED_PAUSES: Set<String> = setOf("uh", "um", "er", "erm", "ah", "eh", "hmm", "mm", "uhm", "umm", "uhh", "ehm")

    /** Function words excluded from lexical sophistication statistics. */
    val STOPWORDS: Set<String> = """
        a an the and or but if then so because as of at by for with about against between into through
        during before after above below to from up down in out on off over under again further once here
        there when where why how all any both each few more most other some such no nor not only own same
        than too very s t can will just don should now i me my myself we our ours ourselves you your yours
        yourself yourselves he him his himself she her hers herself it its itself they them their theirs
        themselves what which who whom this that these those am is are was were be been being have has had
        having do does did doing would could ought i'm you're he's she's it's we're they're i've you've we've
        they've i'd you'd he'd she'd we'd they'd i'll you'll he'll she'll we'll they'll isn't aren't wasn't
        weren't hasn't haven't hadn't doesn't don't didn't won't wouldn't shan't shouldn't can't cannot couldn't
        mustn't let's that's who's what's here's there's when's where's why's how's also yes yeah okay ok oh
        well like really actually maybe might must shall may get got go going gone one two three lot lots
        thing things much many even still yet ever never always often sometimes usually
    """.trim().split(Regex("\\s+")).toSet()

    fun isContentWord(lower: String): Boolean =
        lower.length > 1 && lower.any { it.isLetter() } && lower !in STOPWORDS && lower !in FILLED_PAUSES

    /** Moving-average type-token ratio over [window]-token windows (Covington & McFall 2010). */
    fun mattr(tokens: List<String>, window: Int = 50): Double {
        if (tokens.isEmpty()) return 0.0
        if (tokens.size <= window) return tokens.toSet().size.toDouble() / tokens.size
        val counts = HashMap<String, Int>()
        for (i in 0 until window) counts[tokens[i]] = (counts[tokens[i]] ?: 0) + 1
        var sum = counts.size.toDouble() / window
        var windows = 1
        for (i in window until tokens.size) {
            val out = tokens[i - window]
            val c = (counts[out] ?: 1) - 1
            if (c == 0) counts.remove(out) else counts[out] = c
            counts[tokens[i]] = (counts[tokens[i]] ?: 0) + 1
            sum += counts.size.toDouble() / window
            windows++
        }
        return sum / windows
    }

    /** Candidate base forms for dictionary lookup (very small rule-based lemmatiser). */
    fun lemmaCandidates(word: String): List<String> {
        val w = normalize(word).trim('\'')
        val out = LinkedHashSet<String>()
        out += w
        if (w.endsWith("'s")) out += w.dropLast(2)
        if (w.endsWith("ies") && w.length > 4) out += w.dropLast(3) + "y"
        if (w.endsWith("es") && w.length > 3) out += w.dropLast(2)
        if (w.endsWith("s") && w.length > 3) out += w.dropLast(1)
        if (w.endsWith("ied") && w.length > 4) out += w.dropLast(3) + "y"
        if (w.endsWith("ed") && w.length > 3) {
            out += w.dropLast(2); out += w.dropLast(1)
            if (w.length > 5 && w[w.length - 3] == w[w.length - 4]) out += w.dropLast(3)
        }
        if (w.endsWith("ing") && w.length > 4) {
            out += w.dropLast(3); out += w.dropLast(3) + "e"
            if (w.length > 6 && w[w.length - 4] == w[w.length - 5]) out += w.dropLast(4)
        }
        if (w.endsWith("ier") && w.length > 4) out += w.dropLast(3) + "y"
        if (w.endsWith("iest") && w.length > 5) out += w.dropLast(4) + "y"
        if (w.endsWith("er") && w.length > 4) out += w.dropLast(2)
        if (w.endsWith("est") && w.length > 5) out += w.dropLast(3)
        if (w.endsWith("ly") && w.length > 4) out += w.dropLast(2)
        if (w.endsWith("ily") && w.length > 5) out += w.dropLast(3) + "y"
        return out.toList()
    }

    fun capitalizeFirst(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    fun ensureTerminalPunctuation(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        return if (t.last() in ".!?") t else "$t."
    }
}
