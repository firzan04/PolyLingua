package com.polylingua.edge.data.seed

import com.polylingua.edge.domain.model.ModuleType

/** Second-person -> first-person conversion that respects subject vs. object "you". */
object Pronouns {
    private val OBJECT_TRIGGERS = setOf(
        "on", "to", "for", "with", "at", "about", "from", "by", "of", "like", "into", "than", "taught", "gave", "told",
        "helped", "showed", "let", "made", "asked", "influenced", "inspired", "impressed", "surprised", "give", "tell",
        "help", "show", "make", "ask", "teach", "taught", "brought", "sent", "offered",
    )

    /**
     * "you" after a preposition or a transitive verb is an object ("taught you" -> "taught me");
     * anywhere else it is the subject of a clause ("a place you visited" -> "a place I visited").
     */
    fun toFirstPerson(s: String): String {
        val tokens = s.split(' ')
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val raw = tokens[i]
            val core = raw.trimEnd('?', ',', '.', '!', ';', ':')
            val tail = raw.substring(core.length)
            val lower = core.lowercase()
            val prev = out.lastOrNull()?.trimEnd('?', ',', '.', '!')?.lowercase()
            val next = tokens.getOrNull(i + 1)?.trimEnd('?', ',', '.', '!')?.lowercase()
            when (lower) {
                "you" -> {
                    val isObject = next == null || tail.isNotEmpty() || (prev != null && (prev in OBJECT_TRIGGERS || (prev.endsWith("ed") && prev !in setOf("need", "used"))))
                    if (!isObject && next == "were") { out += "I"; out += "was" + tokens[i + 1].substring(4); i += 2; continue }
                    if (!isObject && next == "are") { out += "I"; out += "am" + tokens[i + 1].substring(3); i += 2; continue }
                    out += (if (isObject) "me" else "I") + tail
                }
                "your" -> out += "my$tail"
                "yours" -> out += "mine$tail"
                "yourself" -> out += "myself$tail"
                else -> out += raw
            }
            i++
        }
        return out.joinToString(" ")
    }

    /** Question polarity words become affirmative ("anything" -> "something"). */
    fun affirmative(s: String): String = s
        .replace(Regex("\\banything\\b"), "something")
        .replace(Regex("\\banyone\\b"), "someone")
        .replace(Regex("\\bany\\b"), "some")
        .replace(Regex("\\bever\\s"), "")
}

/**
 * Builds the opening sentence of a model answer from the question itself so Band 6/8/9 answers
 * respond directly ("Do you enjoy cooking?" -> "Yes, I really enjoy cooking.") before developing
 * the topic. Covers the frequent IELTS Part 1/3 question forms; falls back to neutral openers.
 */
object PromptEcho {

    data class Echo(val b6: String, val b8: String, val b9: String)

    private val VERBS = setOf(
        "be", "have", "make", "create", "replace", "survive", "disappear", "become", "live", "pay", "fund", "ban", "allow",
        "teach", "require", "invest", "take", "give", "own", "wear", "limit", "protect", "encourage", "run", "introduce",
        "control", "spend", "learn", "use", "help", "change", "keep", "build", "stop", "go", "get", "solve", "improve",
        "reduce", "increase", "return", "move", "work", "stay", "play", "cost", "include", "offer", "destroy", "lose", "save",
        "choose", "provide", "support", "prepare", "follow", "communicate", "attract", "tax", "punish", "publish", "preserve",
        "restore", "rely", "compete", "cause", "harm", "benefit", "affect", "matter", "spread", "require", "send", "make",
        "solve", "measure", "close", "pay", "share", "feel", "produce", "earn", "decide", "influence", "shape", "power",
    )

    private val PAST_PARTICIPLE = Regex("^(\\w+ed|been|done|gone|seen|taken|given|grown|become|made|had|got|changed|known|shown|written)$")

    private fun clean(s: String): String = s.trim().trimEnd('?', '.', '!').trim()

    private fun cap(s: String): String = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    /** Splits "NP verb..." at the first known verb; a trailing -ly adverb moves into the VP. */
    private fun splitNpVp(rest: String): Pair<String, String>? {
        val words = rest.split(' ').filter { it.isNotBlank() }
        val idx = words.indexOfFirst { it.lowercase() in VERBS || PAST_PARTICIPLE.matches(it.lowercase()) }
        if (idx <= 0) return null
        var np = words.subList(0, idx).toMutableList()
        val vp = words.subList(idx, words.size).toMutableList()
        while (np.isNotEmpty() && (np.last().endsWith("ly") || np.last() in setOf("ever", "really", "still", "always", "even"))) {
            val adv = np.removeAt(np.lastIndex)
            if (adv != "ever") vp.add(0, adv)
        }
        if (np.isEmpty()) return null
        return np.joinToString(" ") to vp.joinToString(" ")
    }

    fun echo(prompt: String, module: ModuleType): Echo {
        val fallback = Echo("Well, ", "To be honest, ", "If I'm being completely candid, ")
        if (module == ModuleType.TANDEM_DEBATE) {
            return Echo("I agree with this motion. ", "I'm inclined to support this motion. ", "I would argue firmly in favour of the motion. ")
        }
        if (module == ModuleType.HELLOTALK_ROLEPLAY) {
            return Echo("Oh, that's interesting! ", "Great question! ", "What a fascinating thing to compare! ")
        }
        val original = clean(prompt)
        val p = original.lowercase()
        val fp = { s: String -> Pronouns.affirmative(Pronouns.toFirstPerson(s)) }
        fun after(prefix: String): String = original.substring(prefix.length).trim()

        Regex("^(?:do|would) you (?:prefer|rather) (.+?) or (.+)$").find(p)?.let {
            val x = fp(original.substring(it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1))
            return Echo("I prefer $x, to be honest. ", "I'd say I generally prefer $x. ", "On balance, I'd opt for $x. ")
        }
        Regex("^would you rather (.+?) or (.+)$").find(p)?.let {
            val x = fp(original.substring(it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1))
            return Echo("I'd rather $x, to be honest. ", "I think I'd rather $x. ", "Without hesitation, I'd rather $x. ")
        }
        Regex("^are you (?:an? )?(.+?) or (?:an? )?(.+)$").find(p)?.let {
            val x = fp(original.substring(it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1))
            val article = if (p.startsWith("are you a ")) "a " else if (p.startsWith("are you an ")) "an " else ""
            return Echo("I'm $article$x, actually. ", "I'm definitely $article$x. ", "I'm very much $article$x. ")
        }
        Regex("^do you (.+?) or (.+)$").find(p)?.let {
            val x = fp(original.substring(it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1))
            return Echo("I $x, actually. ", "At the moment, I $x. ", "As things stand, I $x. ")
        }
        if (p.startsWith("do you ")) {
            val x = fp(after("do you"))
            val emph = if (Regex("^(like|enjoy|love)\\b").containsMatchIn(x.lowercase())) "really " else "definitely "
            return Echo("Yes, I $x. ", "Yes, I $emph$x. ", "I certainly do. ")
        }
        if (p.startsWith("did you ")) return Echo("Yes, I did. ", "Yes, I did, especially when I was younger. ", "I certainly did. ")
        if (p.startsWith("have you ever ")) {
            val x = fp(after("have you ever"))
            return Echo("Yes, I have $x once or twice. ", "Yes, I have $x on a few occasions. ", "As it happens, I have $x more than once. ")
        }
        Regex("^(is|are) there (?:any |much |many )?(.+)$").find(p)?.let {
            val verb = it.groupValues[1]
            val x = fp(original.substring(it.groups[2]!!.range.first))
            val quant = when {
                p.contains(" much ") -> "quite a lot of "
                verb == "is" && p.startsWith("is there any ") -> "one "
                else -> ""
            }
            return Echo("Yes, there $verb $quant$x. ", "Yes, there $verb certainly $quant$x. ", "Indeed, there $verb $quant$x. ")
        }
        Regex("^is it (better|more important|easier|harder) (?:to )?(.+?) or (.+)$").find(p)?.let {
            val x = fp(original.substring(it.groups[2]!!.range.first, it.groups[2]!!.range.last + 1))
            val cmp = it.groupValues[1]
            return Echo("In my view, it is $cmp to $x. ", "On the whole, I'd say it's $cmp to $x. ", "All things considered, it is $cmp to $x. ")
        }
        if (p.startsWith("is it ") && p.contains(" or ")) return Echo("It depends, but ", "It's a complex question, but ", "There is no simple answer, but ")
        if (p.startsWith("is it ")) {
            val x = fp(after("is it"))
            return Echo("Yes, it is $x. ", "Yes, I'd say it's $x. ", "Broadly speaking, it is $x. ")
        }
        if (p.startsWith("are you ")) {
            val x = fp(after("are you"))
            return Echo("Yes, I'm $x. ", "Yes, I'm definitely $x. ", "I'd say I'm very much $x. ")
        }
        if (p.startsWith("would you like to ")) {
            val x = fp(after("would you like to"))
            val tail = if (x.contains("one day") || x.contains("in the future")) "" else " one day"
            return Echo("Yes, I'd love to $x$tail. ", "Yes, I'd really like to $x$tail. ", "I'd jump at the chance to $x$tail. ")
        }
        if (p.startsWith("would you ")) {
            val x = fp(after("would you"))
            return Echo("Yes, I would $x. ", "Yes, I think I would $x. ", "Without a doubt, I would $x. ")
        }
        Regex("^(should|will|can|could|does|do) (.+)$").find(p)?.let { m ->
            val modal = m.groupValues[1]
            if (p.contains(" or ")) return Echo("It depends, but ", "It's a complex question, but ", "There is no simple answer, but ")
            val rest = fp(original.substring(m.groups[2]!!.range.first))
            val split = splitNpVp(rest) ?: return fallback
            val np = split.first
            val vp = split.second
            return when (modal) {
                "should" -> Echo("I think ${np} should $vp. ", "I'd argue that $np should $vp, at least to some extent. ", "I'm firmly convinced that $np should $vp. ")
                "will" -> Echo("I think $np will $vp eventually. ", "I suspect $np will $vp in the long run. ", "In all likelihood, $np will $vp. ")
                "can", "could" -> Echo("I think $np $modal $vp. ", "I'd say $np $modal $vp, to some extent. ", "There is little doubt that $np $modal $vp. ")
                else -> Echo("I think $np does $vp. ", "I'd say $np really does $vp. ", "In my view, $np undoubtedly does $vp. ")
            }.let { e -> Echo(cap(e.b6), cap(e.b8), cap(e.b9)) }
        }
        Regex("^has (.+)$").find(p)?.let { m ->
            if (p.contains(" or ")) return Echo("It depends, but ", "It's a complex question, but ", "There is no simple answer, but ")
            val rest = fp(original.substring(m.groups[1]!!.range.first)).replace(" much ", " a lot ")
            val split = splitNpVp(rest) ?: return fallback
            return Echo(cap("Yes, ${split.first} has ${split.second}. "), cap("Yes, ${split.first} has certainly ${split.second}. "), cap("Undeniably, ${split.first} has ${split.second}. "))
        }
        if (p.startsWith("why do ") || p.startsWith("why does ")) {
            val x = fp(original.substringAfter(' ').substringAfter(' '))
            return Echo("There are several reasons why $x. ", "I can think of a few reasons why $x. ", "I suspect there are several overlapping reasons why $x. ")
        }
        if (p.startsWith("why ")) return Echo("There are several reasons for this. ", "I can think of a few reasons for this. ", "Several overlapping factors explain this. ")
        if (p.startsWith("how often do you ")) {
            val x = fp(after("how often do you"))
            return Echo("I $x fairly regularly, maybe once or twice a week. ", "I $x quite often - probably a couple of times a week. ", "I'd say I $x more often than I probably should. ")
        }
        Regex("^what (?:is|was) your favourite (.+?)(?:,? and why)?$").find(p)?.let { m ->
            val x = fp(original.substring(m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1))
            return Echo("It's hard to choose just one favourite $x. ", "Choosing a single favourite $x is tricky. ", "Picking just one favourite $x is genuinely difficult. ")
        }
        if (p.startsWith("to what extent")) return Echo("To a large extent, I agree with this. ", "I'd say this is true to a considerable extent. ", "Largely, yes - though with some important caveats. ")
        if (Regex("^how (can|could|might|should)\\b").containsMatchIn(p)) return Echo("There are several ways to approach this. ", "I can think of a few practical solutions. ", "Several strategies spring to mind. ")
        if (p.startsWith("who should")) return Echo("I think it should be a shared responsibility. ", "I'd say responsibility should be shared. ", "Ultimately, I believe the burden should be shared. ")
        if (p.startsWith("what") || p.startsWith("which") || p.startsWith("how")) return Echo("Well, that's a good question. ", "That's an interesting question. ", "That's a fascinating question. ")
        return fallback
    }
}
