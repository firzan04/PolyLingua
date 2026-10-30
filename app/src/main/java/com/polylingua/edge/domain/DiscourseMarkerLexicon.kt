package com.polylingua.edge.domain

/**
 * Categorised lexicon of spoken discourse markers, connectives and cohesive devices used for the
 * Fluency & Coherence "variety" metric (D_markers). Basic connectives (and, but, so, because...)
 * count half towards variety because the band descriptors reward a *range* of cohesive devices.
 */
object DiscourseMarkerLexicon {

    enum class Category { ADDITION, CONTRAST, CAUSE_EFFECT, EXAMPLE, SEQUENCE, EMPHASIS, CLARIFICATION, SUMMARY, OPINION, HEDGING, CONDITION, TIME, COMPARISON, CONVERSATIONAL }

    data class Marker(val phrase: String, val category: Category, val basic: Boolean, val tokens: List<String>)

    private val BASIC = setOf("and", "but", "so", "because", "also", "then", "like", "or", "if", "when", "too", "well")

    private val RAW: Map<Category, String> = mapOf(
        Category.ADDITION to "furthermore|moreover|in addition|additionally|besides|what's more|on top of that|not only|as well as|also|too|plus|and|apart from that|another point is|along with|coupled with",
        Category.CONTRAST to "however|nevertheless|nonetheless|on the other hand|on the flip side|conversely|in contrast|by contrast|whereas|while|although|even though|though|despite|in spite of|yet|still|that said|having said that|admittedly|even so|all the same|regardless|but|instead|rather|on the contrary|then again",
        Category.CAUSE_EFFECT to "because|since|due to|owing to|as a result|consequently|therefore|thus|hence|so|accordingly|for this reason|that's why|which means|leading to|resulting in|thereby|as a consequence|because of|given that|in light of",
        Category.EXAMPLE to "for example|for instance|such as|to illustrate|a case in point|in particular|particularly|especially|namely|take for example|like when|a good example is",
        Category.SEQUENCE to "firstly|first of all|to begin with|secondly|thirdly|then|next|after that|afterwards|subsequently|finally|lastly|last but not least|eventually|meanwhile|in the meantime|simultaneously|initially|at first|ultimately|to start with",
        Category.EMPHASIS to "indeed|in fact|as a matter of fact|above all|crucially|notably|significantly|undoubtedly|without a doubt|certainly|clearly|obviously|needless to say|it goes without saying|more importantly|most importantly|especially important",
        Category.CLARIFICATION to "in other words|that is to say|to put it another way|to put it simply|i mean|specifically|more precisely|to clarify|what i mean is|put differently",
        Category.SUMMARY to "in conclusion|to sum up|all in all|overall|in short|in brief|to conclude|on the whole|by and large|all things considered|at the end of the day|in a nutshell|taking everything into account|to summarise|to summarize",
        Category.OPINION to "in my view|from my perspective|personally|as far as i'm concerned|to my mind|i'd argue that|it seems to me that|from my point of view|if you ask me|i'm convinced that|i'd say|in my opinion|i strongly believe",
        Category.HEDGING to "to some extent|to a certain degree|arguably|presumably|it could be argued that|more or less|in a way|broadly speaking|generally speaking|as a rule|in most cases|it's fair to say|up to a point",
        Category.CONDITION to "if|unless|provided that|as long as|in case|otherwise|assuming that|on condition that|whether or not|even if|supposing",
        Category.TIME to "when|whenever|as soon as|once|until|by the time|nowadays|these days|currently|at present|in the past|in the long run|in the short term|back then|from then on",
        Category.COMPARISON to "compared to|in comparison with|similarly|likewise|in the same way|just as|unlike|equally|by comparison",
        Category.CONVERSATIONAL to "well|actually|to be honest|frankly|anyway|that being said|as i mentioned|going back to|speaking of|on that note|interestingly|surprisingly|fortunately|unfortunately|funnily enough|believe it or not|you see|mind you",
    )

    val markers: List<Marker> by lazy {
        val seen = HashSet<String>()
        val out = ArrayList<Marker>()
        for ((cat, joined) in RAW) {
            for (p in joined.split('|')) {
                val phrase = p.trim()
                if (phrase.isEmpty() || !seen.add(phrase)) continue
                out += Marker(phrase, cat, phrase in BASIC, phrase.split(' '))
            }
        }
        out.sortedByDescending { it.tokens.size }
    }

    val size: Int get() = markers.size

    private val byFirstToken: Map<String, List<Marker>> by lazy { markers.groupBy { it.tokens.first() } }

    /** Finds markers in a lower-cased token stream, longest match first, non-overlapping. */
    fun findMarkers(tokens: List<String>): List<Marker> {
        val found = ArrayList<Marker>()
        var i = 0
        while (i < tokens.size) {
            val candidates = byFirstToken[tokens[i]]
            var matched: Marker? = null
            if (candidates != null) {
                for (m in candidates) {
                    if (i + m.tokens.size <= tokens.size) {
                        var ok = true
                        for (k in m.tokens.indices) if (tokens[i + k] != m.tokens[k]) { ok = false; break }
                        if (ok) { matched = m; break }
                    }
                }
            }
            if (matched != null) {
                found += matched
                i += matched.tokens.size
            } else {
                i++
            }
        }
        return found
    }

    /** D_markers: unique non-basic markers count 1.0, unique basic connectives 0.5. */
    fun weightedVariety(found: List<Marker>): Double {
        val unique = found.distinctBy { it.phrase }
        return unique.sumOf { if (it.basic) 0.5 else 1.0 }
    }

    /** Suggested sophisticated alternatives for a category (used by the coaching text). */
    fun suggestionsFor(category: Category, count: Int = 3): List<String> =
        markers.filter { it.category == category && !it.basic }.map { it.phrase }.take(count)
}
