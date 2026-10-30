package com.polylingua.edge.data

import com.polylingua.edge.data.seed.CurriculumContent
import com.polylingua.edge.data.seed.Domain
import com.polylingua.edge.data.seed.PromptEcho
import com.polylingua.edge.domain.CmuDictionary
import com.polylingua.edge.domain.GrammarPatternDetector
import com.polylingua.edge.domain.LexicalUpgradeDictionary
import com.polylingua.edge.domain.ModelAnswerComposer
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase

/**
 * Deterministic curriculum generator: 34 IELTS domains x 30 cases = 1,020 unique practice cases.
 *
 * Per domain: 1 flashcard, 2 quiz MCQs, 5 Part-1 questions, 4 ELSA phoneme drills, 5 Cake
 * shadowing lines, 3 HelloTalk roleplays, 2 Part-2 cue cards, 5 Part-3 questions, 3 Tandem
 * debates. Level index = (case position + domain index) mod 6, so every CEFR level receives
 * exactly 170 cases and every module appears at every level across the 34 domains. IELTS target
 * bands span 4.0-9.0. IPA columns are rendered from CMUdict (RP), model answers are composed at
 * Band 6 (B2), Band 8 (C1) and Band 9 (C2) from each domain's idea bank.
 */
class Procedural1000CaseSeeder(private val dictionary: CmuDictionary) {

    private data class CueCardFirstPerson(val topic: String, val bullets: List<String>)

    /** Second -> first person for model answers ("a teacher who influenced you" -> "... me"). */
    private fun swapPronouns(s: String): String = com.polylingua.edge.data.seed.Pronouns.toFirstPerson(s)

    companion object {
        const val CASES_PER_DOMAIN = 30
        const val SHEET_PREFIX = "Built-in"
        val LEVELS = CefrLevel.entries

        /** Module order inside each domain (positions 0..29). */
        val LAYOUT: List<ModuleType> = buildList {
            add(ModuleType.FLASHCARD)
            repeat(2) { add(ModuleType.QUIZ_MCQ) }
            repeat(5) { add(ModuleType.STIMULER_PART1) }
            repeat(4) { add(ModuleType.ELSA_PHONEME_DRILL) }
            repeat(5) { add(ModuleType.CAKE_SHADOWING) }
            repeat(3) { add(ModuleType.HELLOTALK_ROLEPLAY) }
            repeat(2) { add(ModuleType.STIMULER_PART2_CUECARD) }
            repeat(5) { add(ModuleType.STIMULER_PART3) }
            repeat(3) { add(ModuleType.TANDEM_DEBATE) }
        }

        fun sheetNameFor(module: ModuleType): String = "$SHEET_PREFIX - ${module.label}"

        fun bandFor(level: CefrLevel, variant: Int): Double = when (level) {
            CefrLevel.A1 -> 4.0
            CefrLevel.A2 -> 4.5
            CefrLevel.B1 -> if (variant % 2 == 0) 5.0 else 5.5
            CefrLevel.B2 -> if (variant % 2 == 0) 6.0 else 6.5
            CefrLevel.C1 -> listOf(7.0, 7.5, 8.0)[variant % 3]
            CefrLevel.C2 -> if (variant % 2 == 0) 8.5 else 9.0
        }
    }

    private val usedStressWords = HashSet<String>()

    fun generate(): List<PracticeCase> {
        usedStressWords.clear()
        val domains = CurriculumContent.ALL
        require(domains.size == 34) { "Expected 34 domains, found ${domains.size}" }
        val out = ArrayList<PracticeCase>(domains.size * CASES_PER_DOMAIN)
        var number = 1
        for ((di, d) in domains.withIndex()) {
            val counters = HashMap<ModuleType, Int>()
            for ((pos, module) in LAYOUT.withIndex()) {
                val k = counters.getOrDefault(module, 0)
                counters[module] = k + 1
                val level = LEVELS[(pos + di) % 6]
                val band = bandFor(level, di + pos)
                out += build(d, di, module, k, level, band, number++)
            }
        }
        return out
    }

    private fun build(d: Domain, di: Int, module: ModuleType, k: Int, level: CefrLevel, band: Double, number: Int): PracticeCase {
        val sheet = sheetNameFor(module)
        return when (module) {
            ModuleType.STIMULER_PART1 -> speaking(d, sheet, number, d.part1[k], module, level, band, 0, 45)
            ModuleType.STIMULER_PART3 -> speaking(d, sheet, number, d.part3[k], module, level, band, 0, 60)
            ModuleType.STIMULER_PART2_CUECARD -> cueCard(d, sheet, number, k, level, band)
            ModuleType.HELLOTALK_ROLEPLAY -> speaking(d, sheet, number, d.roleplay[k], module, level, band, 0, 60)
            ModuleType.TANDEM_DEBATE -> speaking(d, sheet, number, "Debate motion: ${d.debate[k]}", module, level, band, 30, 90)
            ModuleType.CAKE_SHADOWING -> shadowing(d, sheet, number, d.shadowing[k].first, d.shadowing[k].second, module, level, band)
            ModuleType.ELSA_PHONEME_DRILL -> shadowing(d, sheet, number, d.elsa[k], "", module, level, band)
            ModuleType.QUIZ_MCQ -> if (k == 0) vocabQuiz(d, di, sheet, number, level, band) else stressQuiz(d, sheet, number, level, band)
            ModuleType.FLASHCARD -> flashcard(d, sheet, number, level, band)
        }
    }

    // ------------------------------------------------------------------------- speaking
    private fun speaking(
        d: Domain, sheet: String, number: Int, prompt: String, module: ModuleType,
        level: CefrLevel, band: Double, prep: Int, speak: Int,
    ): PracticeCase {
        val (b6, b8, b9) = modelAnswers(d, prompt, module)
        return PracticeCase(
            sheetName = sheet, number = number, prompt = prompt,
            colC = keywordIpa(d),
            colD = b6, colE = b8, colF = b9,
            colG = b8,
            colH = fluencyTip(module),
            colI = "Topic vocabulary (C1/C2): " + d.ideas.c1.joinToString(", ") + topicUpgrades(d),
            colJ = grammarTargets(module, level),
            colK = phonemeTraps(b8) + " Intonation: " + intonationTip(prompt, module),
            cefr = level, targetBand = band, module = module, prepSec = prep, speakSec = speak,
            topic = d.title,
        )
    }

    private fun cueCard(d: Domain, sheet: String, number: Int, k: Int, level: CefrLevel, band: Double): PracticeCase {
        val rawCard = d.cueCards[k]
        val prompt = buildString {
            val card = rawCard
            append("Describe ").append(card.topic).append(".\nYou should say:")
            card.bullets.forEachIndexed { i, b -> append("\n• ").append(if (i == card.bullets.lastIndex && !b.startsWith("and")) "and $b" else b) }
        }
        val card = CueCardFirstPerson(swapPronouns(rawCard.topic), rawCard.bullets.map { swapPronouns(it) })
        val i = d.ideas
        val b = card.bullets.map { it.removePrefix("and ").removePrefix("explain ") }
        val b6 = "I want to talk about ${card.topic}. First, ${b[0]}: I think ${i.subject} is really important to me. " +
            "About ${b[1]}, I remember that ${i.example}. As for ${b[2]}, the main point is that ${i.pos1}. " +
            "Finally, ${b[3]} - I think it is because ${i.pos2}, even if ${i.neg1}."
        val b8 = "I'd like to describe ${card.topic}, which has stayed with me for quite a while. To begin with, ${b[0]}: " +
            "I ${i.personal}, so ${i.subject} has always mattered to me. When it comes to ${b[1]}, a moment that springs to mind is when ${i.example}. " +
            "In terms of ${b[2]}, what stood out was that ${i.pos1}, and ${i.pos2}. As for ${b[3]}, I'd say it shaped my outlook because, " +
            "although ${i.neg1}, it taught me the value of ${i.c1.first()}."
        val b9 = "The example I'd like to dwell on is ${card.topic}, and it's one I return to surprisingly often. Starting with ${b[0]}, " +
            "having ${i.personal}, I've always had a soft spot for anything connected with ${i.subject}. Turning to ${b[1]}, what immediately comes to mind is the time ${i.example}. " +
            "As for ${b[2]}, not only ${invertedClause(i.pos1)}, but ${i.pos2} as well. And why does it matter so much? Admittedly, ${i.neg2}; " +
            "nevertheless, it was precisely this experience that made me appreciate ${i.c1.getOrElse(1) { i.c1.first() }}. As the saying goes, ${i.idiom}."
        return PracticeCase(
            sheetName = sheet, number = number, prompt = prompt,
            colC = keywordIpa(d), colD = b6, colE = b8, colF = b9, colG = b8,
            colH = fluencyTip(ModuleType.STIMULER_PART2_CUECARD),
            colI = "Topic vocabulary (C1/C2): " + i.c1.joinToString(", ") + topicUpgrades(d),
            colJ = grammarTargets(ModuleType.STIMULER_PART2_CUECARD, level),
            colK = phonemeTraps(b8) + " Intonation: fall at the end of each bullet point; use a fall-rise to signal 'there's more'.",
            cefr = level, targetBand = band, module = ModuleType.STIMULER_PART2_CUECARD, prepSec = 60, speakSec = 120,
            topic = d.title,
        )
    }

    private fun modelAnswers(d: Domain, prompt: String, module: ModuleType): Triple<String, String, String> {
        val i = d.ideas
        val echo = PromptEcho.echo(prompt, module)
        val (o6, o8, o9) = Triple(echo.b6, echo.b8, echo.b9)
        val body6 = "I think ${i.subject} is really important for most people. For example, ${i.example}. " +
            "One good thing is that ${i.pos1}, but on the other hand ${i.neg1}. In general, I believe ${i.future}."
        val body8 = "I'd say ${i.subject} plays a pivotal role in modern life. In my case, I ${i.personal}, and I've noticed that ${i.pos1}. " +
            "Another benefit is that ${i.pos2}. That said, ${i.neg1}, which is a genuine concern. " +
            "Looking ahead, I suspect ${i.future}, particularly as ${i.c1.first()} becomes a priority."
        val body9 = "few things shape our prospects quite as profoundly as ${i.subject}. Having ${i.personal}, I've come to appreciate that ${i.pos2}; " +
            "indeed, ${i.pos1}. Admittedly, ${i.neg2}, and it would be naive to ignore that. Nevertheless, were governments to ${i.policy}, " +
            "I'm convinced ${i.future}. As the saying goes, ${i.idiom}."
        return Triple(join(o6, body6), join(o8, body8), join(o9, body9))
    }

    private fun join(opener: String, body: String): String {
        if (opener.isEmpty()) return TextAnalysis.capitalizeFirst(body)
        val joinsLower = opener.endsWith(", ") || opener.endsWith("- ") || opener.endsWith("but ") || opener.endsWith("and ")
        val b = if (joinsLower) {
            if (body.startsWith("I ") || body.startsWith("I'")) body else body.replaceFirstChar { it.lowercaseChar() }
        } else TextAnalysis.capitalizeFirst(body)
        return opener + b
    }

    /** "it opens doors to better jobs" -> "does it open doors to better jobs" (best effort). */
    private fun invertedClause(clause: String): String {
        val words = clause.split(' ')
        if (words.size < 2) return "is it true that $clause"
        val subj = words[0]
        val verb = words[1]
        val rest = words.drop(2).joinToString(" ")
        val aux = setOf("is", "are", "was", "were", "can", "could", "will", "would", "has", "have", "should", "may", "might")
        return when {
            verb in aux -> "$verb $subj $rest".trim()
            verb.endsWith("s") && subj.lowercase() in setOf("it", "he", "she", "this", "that") -> "does $subj ${GrammarPatternDetector.baseForm(verb)} $rest".trim()
            else -> "do $subj $verb $rest".trim()
        }
    }

    // ------------------------------------------------------------------------- shadowing / ELSA
    private fun shadowing(
        d: Domain, sheet: String, number: Int, sentence: String, translation: String,
        module: ModuleType, level: CefrLevel, band: Double,
    ): PracticeCase {
        val words = TextAnalysis.words(sentence)
        val prons = words.map { dictionary.pronounce(it) }
        val ipa = "/" + prons.joinToString(" ") { PhoneticArticulationDatabase.toIpa(it) } + "/"
        val stressLine = TextAnalysis.tokenize(sentence).zip(prons).joinToString("  ") { (tok, pron) ->
            val syl = PhoneticArticulationDatabase.syllableCount(pron)
            val pattern = if (tok.lower in TextAnalysis.STOPWORDS && syl <= 1) "o" else PhoneticArticulationDatabase.stressPattern(pron).ifEmpty { "O" }
            "${tok.text} ($pattern)"
        }
        val focus = focusPhonemes(prons)
        val focusInfo = focus.mapNotNull { PhoneticArticulationDatabase.lookup(it) }
        val pairs = focusInfo.flatMap { ph -> ph.minimalPairs.take(2).map { "${it.first} / ${it.second}" } }
        val upgraded = ModelAnswerComposer.naturalPolish(sentence, emptyList(), LexicalUpgradeDictionary.findUpgrades(sentence, d.key))
        return if (module == ModuleType.ELSA_PHONEME_DRILL) {
            PracticeCase(
                sheetName = sheet, number = number, prompt = sentence, colC = ipa,
                colD = "Focus phonemes: " + focusInfo.joinToString { "/${it.ipa}/ (${it.description})" }.ifEmpty { "connected speech and word stress" },
                colE = "Articulation: " + focusInfo.joinToString(" ") { "/${it.ipa}/: ${it.instruction}" }.ifEmpty { "Keep vowels in unstressed syllables reduced to schwa /ə/." },
                colF = "Minimal pairs: " + pairs.joinToString(", ").ifEmpty { "ship / sheep, bad / bed, think / sink" },
                colG = sentence,
                colH = "Read once slowly (0.75x), then at natural speed. Keep one breath group per comma.",
                colI = "Stress pattern: $stressLine",
                colJ = "Weak forms: " + weakForms(words),
                colK = "Indonesian L1 traps: " + focusInfo.joinToString(" ") { it.indonesianL1Note }.ifEmpty { "Do not pronounce every vowel fully; reduce unstressed syllables." },
                cefr = level, targetBand = band, module = module, prepSec = 0, speakSec = 20, topic = d.title,
            )
        } else {
            PracticeCase(
                sheetName = sheet, number = number, prompt = sentence, colC = ipa,
                colD = "Stress pattern: $stressLine",
                colE = "Connected speech: " + linking(sentence) + " Weak forms: " + weakForms(words),
                colF = "C1/C2 paraphrase: " + if (upgraded.trim() == sentence.trim()) "already natural and idiomatic - keep the wording and focus on rhythm." else upgraded,
                colG = sentence,
                colH = "Shadow with a 0.5 s delay: match rhythm first, then intonation, then individual sounds.",
                colI = "Useful chunks: " + chunks(sentence),
                colJ = "Notice the structure: " + (GrammarPatternDetector.detectStructures(sentence).firstOrNull()?.type ?: "simple clause with a clear subject-verb core"),
                colK = phonemeTraps(sentence),
                cefr = level, targetBand = band, module = module, prepSec = 0, speakSec = 20, topic = d.title,
                translationId = translation,
            )
        }
    }

    private val TRAP_PHONES = listOf("TH", "DH", "V", "Z", "SH", "ZH", "AE", "ER", "R", "L", "F", "IH", "UH", "CH")

    private fun focusPhonemes(prons: List<List<String>>): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (p in prons) for (ph in p) {
            val b = PhoneticArticulationDatabase.baseSymbol(ph)
            if (b in TRAP_PHONES) counts[b] = (counts[b] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }.take(2)
    }

    private fun weakForms(words: List<String>): String {
        val weak = mapOf("to" to "/tə/", "for" to "/fə/", "of" to "/əv/", "and" to "/ən/", "can" to "/kən/", "have" to "/həv/",
            "a" to "/ə/", "the" to "/ðə/", "at" to "/ət/", "was" to "/wəz/", "were" to "/wə/", "from" to "/frəm/", "some" to "/səm/", "than" to "/ðən/")
        val found = words.filter { it in weak }.distinct().map { "$it ${weak[it]}" }
        return if (found.isEmpty()) "none - every word here carries content." else found.joinToString(", ")
    }

    private fun linking(sentence: String): String {
        val toks = TextAnalysis.tokenize(sentence)
        val links = ArrayList<String>()
        for (i in 0 until toks.size - 1) {
            val a = toks[i].lower
            val b = toks[i + 1].lower
            val gap = sentence.substring(toks[i].end, toks[i + 1].start)
            if (gap != " ") continue
            if (a.last() !in "aeiouy" && b.first() in "aeiou") links += "${toks[i].text}_${toks[i + 1].text}"
            if (links.size >= 4) break
        }
        return if (links.isEmpty()) "keep a smooth legato between words." else "link " + links.joinToString(", ") + "."
    }

    private fun chunks(sentence: String): String {
        val words = sentence.trimEnd('.', '!', '?').split(Regex("\\s+"))
        return words.chunked(maxOf(2, (words.size + 2) / 3)).joinToString(" | ") { it.joinToString(" ") }
    }

    // ------------------------------------------------------------------------- quiz / flashcards
    private fun vocabQuiz(d: Domain, di: Int, sheet: String, number: Int, level: CefrLevel, band: Double): PracticeCase {
        val topicEntries = LexicalUpgradeDictionary.entries.filter { it.topic == d.key }
        val entry = topicEntries.firstOrNull() ?: LexicalUpgradeDictionary.entries[di % LexicalUpgradeDictionary.size]
        val all = CurriculumContent.ALL
        val other1 = LexicalUpgradeDictionary.entries.firstOrNull { it.topic == all[(di + 7) % all.size].key }
        val other2 = LexicalUpgradeDictionary.entries.lastOrNull { it.topic == all[(di + 13) % all.size].key }
        val correct = entry.upgrades.first()
        val wrongTopic = other1?.upgrades?.first() ?: "a breathtaking view"
        val wrongTopic2 = other2?.upgrades?.last() ?: "a household name"
        val basic = entry.source
        val options = listOf(correct, wrongTopic, basic, wrongTopic2)
        val rot = di % 4
        val shuffled = options.indices.map { options[(it + rot) % 4] }
        val correctIdx = shuffled.indexOf(correct)
        val letter = ('A' + correctIdx).toString()
        fun why(opt: String): String = when (opt) {
            correct -> "Correct: \"$correct\" is a precise C1-level collocation that examiners reward under Lexical Resource for ${d.title} topics."
            basic -> "\"$basic\" is the basic B1 wording we want to upgrade - it is accurate but too plain for Band 7+."
            wrongTopic -> "\"$wrongTopic\" is sophisticated but belongs to a different topic, so it would sound unnatural here."
            else -> "\"$opt\" does not collocate with this idea in a ${d.title} answer."
        }
        return PracticeCase(
            sheetName = sheet, number = number,
            prompt = "In an IELTS answer about ${d.title}, which is the most natural Band 7+ alternative to \"${entry.source}\"?",
            colC = shuffled[0], colD = shuffled[1], colE = shuffled[2], colF = shuffled[3], colG = letter,
            colH = why(shuffled[0]), colI = why(shuffled[1]), colJ = why(shuffled[2]), colK = why(shuffled[3]),
            cefr = level, targetBand = band, module = ModuleType.QUIZ_MCQ, prepSec = 0, speakSec = 0, topic = d.title,
        )
    }

    private fun stressQuiz(d: Domain, sheet: String, number: Int, level: CefrLevel, band: Double): PracticeCase {
        val candidates = d.keywords + TextAnalysis.words(d.ideas.c1.joinToString(" ")) + TextAnalysis.words(d.ideas.pos1 + " " + d.ideas.pos2)
        val word = candidates.firstOrNull { it !in usedStressWords && PhoneticArticulationDatabase.syllableCount(dictionary.pronounce(it)) in 3..4 && dictionary.isKnown(it) }
            ?: candidates.firstOrNull { it !in usedStressWords && PhoneticArticulationDatabase.syllableCount(dictionary.pronounce(it)) >= 2 && dictionary.isKnown(it) }
            ?: "education"
        usedStressWords += word
        val pron = dictionary.pronounce(word)
        val syl = PhoneticArticulationDatabase.syllableCount(pron).coerceIn(2, 4)
        val stress = PhoneticArticulationDatabase.primaryStressSyllable(pron).coerceIn(0, syl - 1)
        val labels = listOf("1st syllable", "2nd syllable", "3rd syllable", "4th syllable")
        val fillers = listOf("All syllables are stressed equally", "There is no stressed syllable")
        val opts = ((0 until syl).map { labels[it] } + fillers).take(4)
        val ipa = PhoneticArticulationDatabase.toIpa(pron)
        val letter = ('A' + stress).toString()
        val pattern = PhoneticArticulationDatabase.stressPattern(pron)
        fun why(i: Int): String = if (i == stress) "Correct: /$ipa/ ($pattern) - the stress mark ˈ sits before the ${labels[stress]}. Stressed syllables are louder, longer and higher in pitch."
        else if (i < syl) "Not here: in /$ipa/ ($pattern) the ${labels[i]} is unstressed (often reduced to schwa /ə/)." else "English words of two or more syllables always have exactly one primary stress."
        return PracticeCase(
            sheetName = sheet, number = number,
            prompt = "Pronunciation check: where is the primary stress in \"$word\"?",
            colC = opts[0], colD = opts[1], colE = opts[2], colF = opts[3], colG = letter,
            colH = why(0), colI = why(1), colJ = why(2), colK = why(3),
            cefr = level, targetBand = band, module = ModuleType.QUIZ_MCQ, prepSec = 0, speakSec = 0, topic = d.title,
        )
    }

    private fun flashcard(d: Domain, sheet: String, number: Int, level: CefrLevel, band: Double): PracticeCase {
        val entry = LexicalUpgradeDictionary.entries.lastOrNull { it.topic == d.key }
        val front = entry?.source ?: d.ideas.c1.first()
        val back = entry?.upgrades?.joinToString(" / ") ?: d.ideas.c1.joinToString(" / ")
        return PracticeCase(
            sheetName = sheet, number = number,
            prompt = "Upgrade this ${d.title} phrase: \"$front\"",
            colC = "", colD = "", colE = "", colF = "", colG = back,
            colH = "Try it: in your next ${d.title} answer, replace \"$front\" with \"${back.substringBefore(" / ")}\" and notice how much more precise it sounds.",
            colI = "Related collocations: " + d.ideas.c1.joinToString(", "),
            colJ = "", colK = "IPA: /" + TextAnalysis.words(back.substringBefore(" / ")).joinToString(" ") { PhoneticArticulationDatabase.toIpa(dictionary.pronounce(it)) } + "/",
            cefr = level, targetBand = band, module = ModuleType.FLASHCARD, prepSec = 0, speakSec = 0, topic = d.title,
        )
    }

    // ------------------------------------------------------------------------- feedback texts
    private fun keywordIpa(d: Domain): String =
        d.keywords.take(3).joinToString("  ") { w -> "$w /${PhoneticArticulationDatabase.toIpa(dictionary.pronounce(w))}/" }

    private fun topicUpgrades(d: Domain): String {
        val ups = LexicalUpgradeDictionary.entries.filter { it.topic == d.key }.take(3)
        return if (ups.isEmpty()) "" else ". Upgrades: " + ups.joinToString("; ") { "${it.source} -> ${it.upgrades.first()}" }
    }

    private fun fluencyTip(module: ModuleType): String = when (module) {
        ModuleType.STIMULER_PART1 -> "Answer directly, then add a reason and an example (Answer-Reason-Example) in 3-4 sentences, about 30-40 seconds. Avoid one-word replies."
        ModuleType.STIMULER_PART2_CUECARD -> "Use the 60-second preparation to note one keyword per bullet. Structure: intro -> bullets 1-3 -> explanation; keep talking for the full 2 minutes at 130-150 WPM."
        ModuleType.STIMULER_PART3 -> "Position -> justification -> example -> concession -> conclusion. Hedge with 'arguably', 'to some extent'; aim for 45-60 seconds."
        ModuleType.HELLOTALK_ROLEPLAY -> "Keep turns natural: react ('Oh really?'), answer, then ask a follow-up question to keep the exchange going."
        ModuleType.TANDEM_DEBATE -> "State your side, give two reasons with evidence, rebut the strongest counter-argument, and close with a clear verdict."
        else -> "Speak in thought groups of 5-8 words and pause only at punctuation."
    }

    private fun grammarTargets(module: ModuleType, level: CefrLevel): String {
        val base = when (level) {
            CefrLevel.A1, CefrLevel.A2 -> "present simple for habits; past simple for experiences; 'because' and 'but' to link ideas"
            CefrLevel.B1 -> "present perfect for experience; comparatives; first conditional"
            CefrLevel.B2 -> "relative clauses; passive voice; second conditional for hypotheticals"
            CefrLevel.C1 -> "participle clauses; modal perfects (should have done); cleft sentences for emphasis"
            CefrLevel.C2 -> "negative inversion (Not only..., Rarely...); mixed conditionals; subjunctive (It is vital that he be...)"
        }
        val extra = when (module) {
            ModuleType.STIMULER_PART2_CUECARD -> "; narrative tenses (past continuous + past perfect) for the story"
            ModuleType.STIMULER_PART3, ModuleType.TANDEM_DEBATE -> "; hedging modals (might, could) and concessive clauses (although, whereas)"
            ModuleType.HELLOTALK_ROLEPLAY -> "; question tags and indirect questions ('Do you know whether...?')"
            else -> ""
        }
        return "Target structures: $base$extra."
    }

    private fun phonemeTraps(text: String): String {
        val traps = ArrayList<String>()
        val seen = HashSet<String>()
        for (w in TextAnalysis.words(text).distinct()) {
            if (w.length < 3) continue
            val pron = dictionary.lookup(w)?.firstOrNull() ?: continue
            val trap = pron.map { PhoneticArticulationDatabase.baseSymbol(it) }.firstOrNull { it in setOf("TH", "DH", "V", "Z", "SH", "ZH", "AE", "ER") } ?: continue
            if (!seen.add(trap)) continue
            val info = PhoneticArticulationDatabase.lookup(trap) ?: continue
            traps += "'$w' /${PhoneticArticulationDatabase.toIpa(pron)}/ - /${info.ipa}/: ${info.indonesianL1Note}"
            if (traps.size >= 3) break
        }
        return if (traps.isEmpty()) "Phoneme focus: reduce unstressed vowels to schwa /ə/ and release final consonants." else "Phoneme traps: " + traps.joinToString(" | ")
    }

    private fun intonationTip(prompt: String, module: ModuleType): String = when {
        module == ModuleType.TANDEM_DEBATE -> "stress the contrastive words (but, however) and use a firm fall on your verdict."
        prompt.trim().endsWith("?") && Regex("^(do|does|did|are|is|have|would|can|will)\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt.trim()) ->
            "start with a clear falling 'Yes/No', then a fall on the key content word of each reason."
        else -> "fall on the key content word of each idea; rise slightly on list items before the last one."
    }
}
