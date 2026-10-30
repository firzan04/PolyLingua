package com.polylingua.edge.domain

import com.polylingua.edge.domain.model.DetectedStructure
import com.polylingua.edge.domain.model.GrammarIssue
import com.polylingua.edge.domain.model.GrammarMetrics
import java.util.Locale

/**
 * Regex + lexical heuristics for (a) 25 advanced grammatical structures that evidence range at
 * Band 7+ / C1+, and (b) frequent accuracy errors of Indonesian-L1 speakers of English
 * (agreement, "there have", double comparatives, uncountable plurals, modal + to, etc.).
 * Works on punctuation-restored ASR transcripts; degrades gracefully on raw text.
 */
object GrammarPatternDetector {

    private val IRREGULAR_PP = """
        been done gone seen taken given written spoken eaten known shown grown thrown drawn driven ridden
        risen chosen frozen stolen broken forgotten gotten hidden bitten beaten fallen held kept left lost made
        met paid said sold sent spent stood taught thought told understood won found bought brought caught
        built felt fought heard led meant read run sat slept become begun drunk sung swum rung worn torn sworn
        born borne shaken woken forgiven mistaken undertaken overtaken withdrawn arisen awoken bent bound bred
        burnt dealt dug fed fled flown forbidden forecast got hung hurt laid lent lit put quit set shed shot shut
        slid spun split spread struck stuck stung sunk swept swung thrust upset wept wound withheld cut cost hit
        let overlooked
    """.trim().split(Regex("\\s+")).toSet()

    private val ADJECTIVAL_ED = setOf(
        "interested", "bored", "tired", "excited", "worried", "surprised", "pleased", "scared", "annoyed",
        "amazed", "confused", "satisfied", "married", "used", "supposed", "located", "based", "frightened",
        "disappointed", "embarrassed", "exhausted", "relaxed", "shocked", "stressed", "concerned", "motivated",
        "talented", "skilled", "qualified", "experienced", "crowded", "need", "indeed", "speed", "feed", "seed",
    )

    private const val PP = "(?:\\w+ed|been|done|gone|seen|taken|given|written|spoken|eaten|known|shown|grown|thrown|drawn|driven|chosen|frozen|stolen|broken|forgotten|hidden|beaten|fallen|held|kept|left|lost|made|met|paid|said|sold|sent|spent|taught|thought|told|understood|won|found|bought|brought|caught|built|felt|heard|led|meant|read|run|become|begun|worn|born|shaken|forgiven|mistaken|undertaken|withdrawn|put|set|cut|hit|hurt|laid|lit|shut|spread|struck|stuck|got|gotten|overlooked)"

    data class StructurePattern(val name: String, val regex: Regex, val advanced: Boolean = true)

    val STRUCTURES: List<StructurePattern> = listOf(
        StructurePattern("Negative inversion", Regex("\\b(?:never|rarely|seldom|hardly|scarcely|little)\\s+(?:have|has|had|do|does|did|will|would|can|could|is|are|was|were)\\s+(?:i|we|you|they|he|she|it|the|people)\\b|\\bno sooner had\\b|\\bnot only (?:do|does|did|is|are|was|were|have|has|had|can|will)\\b|\\bunder no circumstances\\b|\\bat no time\\b|\\bonly (?:then|when|after|by|later|once) \\w+ (?:do|did|does|have|had|can|could|will|would|was|were|is|are)\\b")),
        StructurePattern("Cleft sentence (It is/was ... that/who)", Regex("\\bit (?:is|was|'s) (?:\\w+ ){1,5}(?:that|who) \\w+")),
        StructurePattern("Pseudo-cleft (What ... is/was)", Regex("(?:^|[,.;] |\\b)what (?:i|we|you|they|he|she|people|really|most) (?:\\w+ ){0,3}(?:is|was) ")),
        StructurePattern("Second conditional", Regex("\\bif (?:\\w+ ){1,6}(?:\\w+ed|were|had|did|could|went|lived|knew|was|got|became|made|took|spent|earned|studied|saw|gave|found|bought|ran|won|lost|kept|chose|became|felt)\\b[^.!?]{0,80}\\b(?:would|could|might)\\b")),
        StructurePattern("Third conditional", Regex("\\bif (?:\\w+ ){1,6}had (?:not )?$PP\\b[^.!?]{0,80}\\b(?:would|could|might)(?: not)? have $PP\\b")),
        StructurePattern("Mixed conditional", Regex("\\bif (?:\\w+ ){1,6}had (?:not )?$PP\\b[^.!?]{0,80}\\b(?:would|could|might) (?!have\\b)\\w+\\b")),
        StructurePattern("Real conditional (zero/first)", Regex("\\b(?:if|unless) (?:\\w+ ){1,6}(?:is|are|am|do|does|have|has|get|gets|go|goes|want|wants|need|needs)\\b[^.!?]{0,60}\\b(?:will|won't|can|may|is|are)\\b"), advanced = false),
        StructurePattern("Passive voice", Regex("\\b(?:am|is|are|was|were|be|been|being)\\s+(?:\\w+ly\\s+)?$PP\\b")),
        StructurePattern("Present perfect", Regex("\\b(?:have|has|'ve|'s) (?:\\w+ly )?(?:not |never |already |just |recently |always |ever )?$PP\\b"), advanced = false),
        StructurePattern("Present perfect continuous", Regex("\\b(?:have|has|'ve) been \\w+ing\\b")),
        StructurePattern("Past perfect", Regex("\\b(?:had|'d) (?:not |never |already |just )?$PP\\b")),
        StructurePattern("Future perfect / continuous", Regex("\\bwill have $PP\\b|\\bwill be \\w+ing\\b|\\bwill have been \\w+ing\\b")),
        StructurePattern("Modal perfect (should/could/might have + pp)", Regex("\\b(?:should|could|would|might|must|may)(?: not|n't)? have $PP\\b")),
        StructurePattern("Defining relative clause", Regex("\\b(?:people|person|place|thing|things|one|those|someone|something|anyone|man|woman|students|city|country|book|film|reason|time|day) (?:who|which|that|whose|whom|where|when) \\w+")),
        StructurePattern("Non-defining relative clause", Regex(", (?:which|who|whose|whom|where) \\w+")),
        StructurePattern("Participle clause", Regex("(?:^|[.!?] )(?:having \\w+|\\w+ing \\w+(?: \\w+){0,4},|(?:faced|given|compared|based|inspired|surrounded|located|built|considered|seen|known|motivated) (?:with|by|on|to|as)\\b)")),
        StructurePattern("Subjunctive", Regex("\\b(?:essential|vital|important|crucial|necessary|imperative|recommend|recommended|suggest|suggested|insist|insisted|demand|demanded|propose|proposed) that (?:he|she|it|they|we|i|you|everyone|the government|people|students|\\w+) (?:be|have|go|take|make|study|do|stay|keep|pay|consider|not)\\b|\\bif i were\\b|\\bas if (?:\\w+ )?were\\b")),
        StructurePattern("Nominalisation", Regex("\\b(?:the |a |an |this |its |their |our )\\w{4,}(?:tion|sion|ment|ness|ity|ance|ence|ism)\\s+of\\b")),
        StructurePattern("Gerund subject", Regex("(?:^|[.!?] )\\w+ing (?:\\w+ ){0,3}(?:is|was|can|has|helps|makes|gives|allows|requires)\\b")),
        StructurePattern("Infinitive of purpose", Regex("\\bin order (?:not )?to \\w+|\\bso as (?:not )?to \\w+")),
        StructurePattern("Wish / regret structure", Regex("\\b(?:i|we|they|he|she) wish(?:es|ed)? (?:i|we|they|he|she|it|that|you|people) (?:\\w+ed|were|had|could|would|knew|did|was)\\b|\\bif only\\b")),
        StructurePattern("Parallel comparative (the more ..., the more ...)", Regex("\\bthe (?:more|less|\\w+er) [^,.]{1,40},? the (?:more|less|\\w+er)\\b|\\b(?:far|much|considerably|significantly|substantially) (?:more|less|\\w+er) than\\b")),
        StructurePattern("Reported speech", Regex("\\b(?:said|told \\w+|explained|mentioned|claimed|argued|suggested|admitted|reported|announced) that \\w+")),
        StructurePattern("Concessive clause", Regex("\\b(?:although|even though|though|whereas|despite|in spite of|while|albeit|notwithstanding)\\b")),
        StructurePattern("Emphatic structure (So/Such ... that, emphatic do)", Regex("\\bso \\w+ that\\b|\\bsuch (?:a |an )?\\w+(?: \\w+)? that\\b|\\b(?:i|we|they) (?:do|did) (?:believe|think|feel|agree|hope|want|love|enjoy)\\b|^(?:so|such) \\w+ (?:was|is|were|are)\\b")),
        StructurePattern("Used to / would (past habit)", Regex("\\b(?:i|we|they|he|she|people) used to \\w+|\\b(?:i|we|they|he|she) would often \\w+"), advanced = false),
    )

    val ADVANCED_STRUCTURE_NAMES: List<String> get() = STRUCTURES.filter { it.advanced }.map { it.name }

    data class ErrorRule(val regex: Regex, val explanation: String, val fix: (MatchResult) -> String)

    private val THIRD_PERSON = mapOf(
        "go" to "goes", "want" to "wants", "like" to "likes", "need" to "needs", "think" to "thinks", "say" to "says",
        "make" to "makes", "live" to "lives", "work" to "works", "study" to "studies", "play" to "plays", "eat" to "eats",
        "watch" to "watches", "come" to "comes", "know" to "knows", "feel" to "feels", "seem" to "seems", "look" to "looks",
        "love" to "loves", "hate" to "hates", "enjoy" to "enjoys", "prefer" to "prefers", "believe" to "believes",
        "try" to "tries", "use" to "uses", "take" to "takes", "get" to "gets", "give" to "gives", "do" to "does", "have" to "has",
    )

    private val IRREGULAR_BASE = mapOf(
        "went" to "go", "saw" to "see", "came" to "come", "took" to "take", "ate" to "eat", "made" to "make", "got" to "get",
        "bought" to "buy", "said" to "say", "told" to "tell", "gave" to "give", "found" to "find", "thought" to "think",
        "knew" to "know", "wrote" to "write", "spoke" to "speak", "drove" to "drive", "ran" to "run", "began" to "begin",
        "felt" to "feel", "left" to "leave", "met" to "meet", "paid" to "pay", "sent" to "send", "spent" to "spend",
        "taught" to "teach", "understood" to "understand", "won" to "win", "was" to "be", "were" to "be", "had" to "have",
        "did" to "do", "studied" to "study", "tried" to "try", "liked" to "like", "used" to "use", "lived" to "live",
        "played" to "play", "watched" to "watch", "wanted" to "want", "worked" to "work", "visited" to "visit",
        "started" to "start", "finished" to "finish", "enjoyed" to "enjoy",
    )

    private val PAST_FORMS = mapOf(
        "go" to "went", "come" to "came", "eat" to "ate", "see" to "saw", "buy" to "bought", "make" to "made", "take" to "took",
        "meet" to "met", "visit" to "visited", "watch" to "watched", "play" to "played", "study" to "studied", "have" to "had",
        "get" to "got",
    )

    fun baseForm(word: String): String {
        val w = word.lowercase(Locale.ROOT)
        IRREGULAR_BASE[w]?.let { return it }
        THIRD_PERSON.entries.firstOrNull { it.value == w }?.let { return it.key }
        return when {
            w.endsWith("ied") -> w.dropLast(3) + "y"
            w.endsWith("ed") && w.length > 4 -> w.dropLast(2).let { if (it.endsWith("e") || it.length < 3) it else it }
            w.endsWith("ies") -> w.dropLast(3) + "y"
            w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes") || w.endsWith("oes")) -> w.dropLast(2)
            w.endsWith("s") && w.length > 3 -> w.dropLast(1)
            else -> w
        }
    }

    fun ingForm(base: String): String {
        val b = base.lowercase(Locale.ROOT)
        return when {
            b.endsWith("ie") -> b.dropLast(2) + "ying"
            b.endsWith("e") && b != "be" && !b.endsWith("ee") -> b.dropLast(1) + "ing"
            b.length in 3..4 && b[b.length - 1] !in "aeiouwxy" && b[b.length - 2] in "aeiou" && b[b.length - 3] !in "aeiou" -> b + b.last() + "ing"
            else -> b + "ing"
        }
    }

    private val UNCOUNTABLE_SINGULAR = mapOf(
        "informations" to "information", "advices" to "advice", "furnitures" to "furniture", "equipments" to "equipment",
        "knowledges" to "knowledge", "homeworks" to "homework", "luggages" to "luggage", "evidences" to "evidence",
        "feedbacks" to "feedback", "softwares" to "software", "traffics" to "traffic", "pollutions" to "pollution",
        "researches" to "research",
    )

    private val COMPARATIVES = "better|easier|bigger|cheaper|faster|happier|harder|smaller|larger|worse|older|younger|higher|lower|stronger|longer|nicer|safer|simpler|busier|healthier|richer|poorer|cleaner|quieter|warmer|colder|hotter|closer|greater|wider|deeper|cooler"
    private val SUPERLATIVES = "best|worst|biggest|easiest|cheapest|fastest|happiest|hardest|smallest|largest|oldest|youngest|highest|lowest|strongest|longest|nicest|safest|simplest|busiest|healthiest|richest|poorest|greatest"
    private val VOWEL_ADJ_NOUNS = "hour|honest|honour|heir|apple|orange|idea|example|interesting|important|old|elephant|island|umbrella|effect|issue|engineer|exam|experience|opportunity|advantage|area|activity|extra|essay|event|easy|excellent|amazing|awful|outdoor|urban|office|interview|average"

    val ERROR_RULES: List<ErrorRule> = listOf(
        ErrorRule(Regex("\\b(he|she|it) don't\\b"), "Third-person singular takes 'doesn't'.") { "${it.groupValues[1]} doesn't" },
        ErrorRule(Regex("(?<!\\blet |\\bmake |\\bhelp |\\bwatch |\\bsee |\\bhear |\\bhad |\\bdoes |\\bdid |\\bdo |\\bcan |\\bcould |\\bwill |\\bwould |\\bshould |\\bmay |\\bmight |\\bmust |\\bto |\\bnot |\\bwhy |\\bhow )\\b(he|she|it) (go|want|like|need|think|say|make|live|work|study|play|eat|watch|come|know|feel|seem|look|love|hate|enjoy|prefer|believe|try|use|take|get|give|do|have)\\b(?! to\\b)"),
            "Present simple with he/she/it needs the -s form.") { "${it.groupValues[1]} ${THIRD_PERSON[it.groupValues[2]] ?: it.groupValues[2]}" },
        ErrorRule(Regex("\\b(they|we|you) is\\b"), "Plural subjects take 'are'.") { "${it.groupValues[1]} are" },
        ErrorRule(Regex("\\b(they|we) was\\b"), "Plural subjects take 'were'.") { "${it.groupValues[1]} were" },
        ErrorRule(Regex("\\bi (is|are)\\b"), "With 'I' use 'am'.") { "I am" },
        ErrorRule(Regex("\\b(i|you|we|they) (has|does|doesn't)\\b"), "Use the base form with I/you/we/they.") {
            val v = when (it.groupValues[2]) { "has" -> "have"; "does" -> "do"; else -> "don't" }
            "${it.groupValues[1]} $v"
        },
        ErrorRule(Regex("\\bpeople (is|was|has|does)\\b"), "'People' is plural.") {
            val v = when (it.groupValues[1]) { "is" -> "are"; "was" -> "were"; "has" -> "have"; else -> "do" }
            "people $v"
        },
        ErrorRule(Regex("\\b(informations|advices|furnitures|equipments|knowledges|homeworks|luggages|evidences|feedbacks|softwares|traffics|pollutions|researches)\\b"),
            "This noun is uncountable - no plural -s.") { UNCOUNTABLE_SINGULAR[it.value] ?: it.value },
        ErrorRule(Regex("\\b(peoples|childrens|womens|mens)\\b"), "Irregular plural - no extra -s.") {
            when (it.value) { "peoples" -> "people"; "childrens" -> "children"; "womens" -> "women"; else -> "men" }
        },
        ErrorRule(Regex("\\bmore ($COMPARATIVES)\\b"), "Double comparative - drop 'more'.") { it.groupValues[1] },
        ErrorRule(Regex("\\b(?:the )?most ($SUPERLATIVES)\\b"), "Double superlative - drop 'most'.") { "the ${it.groupValues[1]}" },
        ErrorRule(Regex("\\bi am (agree|disagree)\\b"), "'Agree' is a verb, not an adjective.") { "I ${it.groupValues[1]}" },
        ErrorRule(Regex("\\bi very (like|love|enjoy|want|hate|need)\\b"), "'Very' cannot modify a verb.") { "I really ${it.groupValues[1]}" },
        ErrorRule(Regex("\\bdiscuss(?:ed|es|ing)? about\\b"), "'Discuss' takes a direct object (no 'about').") { it.value.removeSuffix(" about") },
        ErrorRule(Regex("\\bexplain(?:ed|s)? me\\b"), "Explain something TO someone.") { it.value.replace(" me", " to me") },
        ErrorRule(Regex("\\breturn(?:ed|s)? back\\b"), "'Return' already means 'go back'.") { it.value.removeSuffix(" back") },
        ErrorRule(Regex("\\brepeat(?:ed|s)? again\\b"), "'Repeat' already means 'say again'.") { it.value.removeSuffix(" again") },
        ErrorRule(Regex("\\bmarried with\\b"), "Use 'married to'.") { "married to" },
        ErrorRule(Regex("\\bdepend(?:s|ed|ing)? of\\b"), "Use 'depend on'.") { it.value.replace(" of", " on") },
        ErrorRule(Regex("\\bdifferent with\\b"), "Use 'different from'.") { "different from" },
        ErrorRule(Regex("\\baccording to me\\b"), "'According to' introduces another source; for yourself say 'in my opinion'.") { "in my opinion" },
        ErrorRule(Regex("\\b(make|makes|made|let|lets) (me|him|her|us|them|you|people) to (\\w+)\\b"), "'Make/let someone' is followed by the bare infinitive.") { "${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}" },
        ErrorRule(Regex("\\b(can|could|should|must|will|would|might|may) to (\\w+)\\b"), "Modal verbs take the bare infinitive (no 'to').") { "${it.groupValues[1]} ${it.groupValues[2]}" },
        ErrorRule(Regex("\\bwill can\\b"), "Two modals cannot combine.") { "will be able to" },
        ErrorRule(Regex("\\b(can|could) able to\\b"), "Use either 'can' or 'be able to'.") { "am able to" },
        ErrorRule(Regex("\\bsince (\\d+|two|three|four|five|six|seven|eight|nine|ten|many|several) (years?|months?|weeks?|days?)\\b"), "Use 'for' with a period of time ('since' + point in time).") { "for ${it.groupValues[1]} ${it.groupValues[2]}" },
        ErrorRule(Regex("\\bin the other hand\\b"), "The fixed phrase is 'on the other hand'.") { "on the other hand" },
        ErrorRule(Regex("\\bon my opinion\\b"), "The fixed phrase is 'in my opinion'.") { "in my opinion" },
        ErrorRule(Regex("\\bmuch (people|students|cars|things|problems|friends|countries|tourists|children|books)\\b"), "Use 'many' with countable plural nouns.") { "many ${it.groupValues[1]}" },
        ErrorRule(Regex("\\bmany (money|time|information|traffic|pollution|water|advice|work|homework|knowledge)\\b"), "Use 'much'/'a lot of' with uncountable nouns.") { "a lot of ${it.groupValues[1]}" },
        ErrorRule(Regex("\\ban (university|european|user|uniform|useful|unique|union|one-|eucalyptus)"), "Use 'a' before a /j/ or /w/ sound.") { "a ${it.groupValues[1]}" },
        ErrorRule(Regex("\\ba ($VOWEL_ADJ_NOUNS)\\b"), "Use 'an' before a vowel sound.") { "an ${it.groupValues[1]}" },
        ErrorRule(Regex("\\b(?:love|enjoy|protect|save|explore|like) the nature\\b"), "'Nature' (the natural world) takes no article.") { it.value.replace("the nature", "nature") },
        ErrorRule(Regex("\\bgo(?:es)? to (home|abroad|downtown)\\b"), "No 'to' before home/abroad/downtown.") { it.value.replace(" to ", " ") },
        ErrorRule(Regex("\\bdid(?:n't| not)? (went|saw|came|took|ate|made|got|bought|said|told|gave|found|thought|knew|wrote|spoke|drove|ran|began|felt|left|met|paid|sent|spent|taught|understood|won|played|watched|studied|liked|wanted|worked|lived|tried|visited|started|finished|enjoyed)\\b"),
            "After 'did/didn't' use the base form.") { it.value.substringBeforeLast(' ') + " " + baseForm(it.groupValues[1]) },
        ErrorRule(Regex("\\b(doesn't|does not|don't|didn't|can|can't|could|couldn't|should|shouldn't|will|won't|would|wouldn't|must|might) (likes|wants|goes|has|does|needs|thinks|makes|gets|works|lives|studies|plays|knows|says)\\b"),
            "After an auxiliary/modal use the base form.") { "${it.groupValues[1]} ${baseForm(it.groupValues[2])}" },
        ErrorRule(Regex("\\bto (went|played|studied|watched|visited|saw|wanted|liked|lived|worked|bought|took)\\b"), "After 'to' use the base form.") { "to ${baseForm(it.groupValues[1])}" },
        ErrorRule(Regex("\\b(am|is|are) (go|study|live|want|need|think|eat|play|watch|come|know|agree|enjoy|prefer|believe|stay|travel|cook|learn|teach|visit|speak)\\b"),
            "Be + base verb: use the present simple or the -ing form.") { "${it.groupValues[1]} ${ingForm(it.groupValues[2])}" },
        ErrorRule(Regex("\\bthere (have|has) (many|a lot of|some|lots of|so many|no|a|an|several)\\b"), "Existence is 'there is/are', not 'there have' (Indonesian 'ada').") {
            val plural = it.groupValues[2] in setOf("many", "a lot of", "some", "lots of", "so many", "several")
            "there ${if (plural) "are" else "is"} ${it.groupValues[2]}"
        },
        ErrorRule(Regex("(?:^|[.!?] )(is|was) (very|so|really|quite|too|good|bad|nice|great|beautiful|important|difficult|easy|interesting|hot|cold|fun|expensive|cheap|big|small)\\b"), "English sentences need a subject: start with 'It'.") { "It ${it.groupValues[1]} ${it.groupValues[2]}" },
        ErrorRule(Regex("\\balthough\\b[^.!?]{1,120}?,? but\\b"), "Use either 'although' or 'but', not both.") { it.value.replace(Regex(",? but\\b"), ",") },
        ErrorRule(Regex("\\bbecause of (i|you|he|she|we|they|it) (\\w+)"), "'Because of' is followed by a noun; before a clause use 'because'.") { "because ${it.groupValues[1]} ${it.groupValues[2]}" },
        ErrorRule(Regex("\\b(yesterday|last (?:week|month|year|night|weekend|summer)),? (i|we|they|he|she) (go|come|eat|see|buy|make|take|meet|visit|watch|play|study|have|get)\\b"), "Finished past time needs the past simple.") {
            "${it.groupValues[1]} ${it.groupValues[2]} ${PAST_FORMS[it.groupValues[3]] ?: it.groupValues[3]}"
        },
        ErrorRule(Regex("\\b(enjoy|enjoys|enjoyed|avoid|avoids|avoided|finish|finished|suggest|suggested|mind|consider|considered|keep|kept|practise|practice) to (\\w+)\\b"), "This verb is followed by the -ing form.") {
            "${it.groupValues[1]} ${ingForm(it.groupValues[2])}"
        },
        ErrorRule(Regex("\\blook(?:ing)? forward to (meet|see|hear|go|visit|work|start|study|travel)\\b"), "'Look forward to' + -ing.") { it.value.substringBeforeLast(' ') + " " + ingForm(it.groupValues[1]) },
        ErrorRule(Regex("\\b(am|is|are|was|were|be|feel|felt) interesting in\\b"), "Use 'interested in' for feelings.") { "${it.groupValues[1]} interested in" },
    )

    fun detectStructures(text: String): List<DetectedStructure> {
        val lower = text.lowercase(Locale.ROOT)
        val out = ArrayList<DetectedStructure>()
        for (s in STRUCTURES) {
            val m = s.regex.find(lower) ?: continue
            val excerpt = safeExcerpt(text, lower, m.range.first, m.range.last + 1)
            out += DetectedStructure(s.name, excerpt)
        }
        return out
    }

    fun detectIssues(text: String): List<GrammarIssue> {
        val lower = text.lowercase(Locale.ROOT)
        val issues = ArrayList<GrammarIssue>()
        val taken = BooleanArray(lower.length + 1)
        for (rule in ERROR_RULES) {
            for (m in rule.regex.findAll(lower)) {
                var start = m.range.first
                val end = m.range.last + 1
                // drop leading sentence punctuation captured by (?:^|[.!?] )
                while (start < end && (lower[start] == '.' || lower[start] == '!' || lower[start] == '?' || lower[start] == ' ')) start++
                if (start >= end || (start until end).any { taken[it] }) continue
                for (k in start until end) taken[k] = true
                val fixed = rule.fix(m).trim().replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'"), "I'")
                issues += GrammarIssue(
                    excerpt = safeExcerpt(text, lower, start, end),
                    suggestion = matchCase(safeExcerpt(text, lower, start, end), fixed),
                    explanation = rule.explanation,
                    start = start,
                    end = end,
                )
            }
        }
        return issues.sortedBy { it.start }
    }

    /** Approximate clause spans: split at punctuation and clause-introducing words. */
    fun clauseSpans(text: String): List<IntRange> {
        val tokens = TextAnalysis.tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val subordinators = setOf("because", "although", "though", "while", "whereas", "when", "if", "unless", "which", "who", "whom", "whose", "since", "until", "after", "before", "whenever", "wherever")
        val coordinators = setOf("and", "but", "so", "or", "yet")
        val clauseStarters = setOf("i", "you", "he", "she", "it", "we", "they", "there", "this", "that", "my", "his", "her", "our", "their", "the", "people", "everyone")
        val spans = ArrayList<IntRange>()
        var segStart = 0
        var wordsInSeg = 0
        for (i in tokens.indices) {
            val tok = tokens[i]
            val prevEnd = if (i > 0) tokens[i - 1].end else 0
            val punctBreak = i > 0 && text.substring(prevEnd, tok.start).any { it == ',' || it == ';' || it == ':' || it == '.' || it == '!' || it == '?' }
            val subBreak = tok.lower in subordinators
            val coordBreak = tok.lower in coordinators && i + 1 < tokens.size && tokens[i + 1].lower in clauseStarters
            if ((punctBreak || subBreak || coordBreak) && wordsInSeg >= 2) {
                spans += tokens[segStart].start until tokens[i - 1].end
                segStart = i
                wordsInSeg = 0
            }
            wordsInSeg++
        }
        if (segStart < tokens.size) spans += tokens[segStart].start until tokens.last().end
        // merge tiny trailing fragments into their predecessor
        return spans.filter { r -> TextAnalysis.tokenize(text.substring(r.first, r.last + 1)).size >= 2 }.ifEmpty { spans }
    }

    fun analyze(text: String, extraIssues: List<GrammarIssue> = emptyList()): GrammarMetrics {
        val sentences = TextAnalysis.sentences(text)
        val issues = (detectIssues(text) + extraIssues).distinctBy { it.start to it.end }.sortedBy { it.start }
        val clauses = clauseSpans(text)
        val clauseCount = clauses.size.coerceAtLeast(if (text.isBlank()) 0 else 1)
        val clausesWithErrors = clauses.count { r -> issues.any { it.start <= r.last && it.end - 1 >= r.first } }
        val efc = if (clauseCount == 0) 0.0 else (clauseCount - clausesWithErrors).toDouble() / clauseCount
        val complexNames = STRUCTURES.filter { it.advanced }.map { it.name }.toSet()
        var complexSentences = 0
        val allStructures = ArrayList<DetectedStructure>()
        for (s in sentences) {
            val found = detectStructures(s.text)
            allStructures += found
            val hasSubordination = TextAnalysis.words(s.text).any { it in setOf("because", "although", "though", "which", "who", "whose", "whereas", "while", "unless", "if", "when", "since") }
            if (hasSubordination || found.any { it.type in complexNames }) complexSentences++
        }
        val distinct = allStructures.filter { it.type in complexNames }.distinctBy { it.type }
        return GrammarMetrics(
            sentenceCount = sentences.size,
            clauseCount = clauseCount,
            complexSentenceRatio = if (sentences.isEmpty()) 0.0 else complexSentences.toDouble() / sentences.size,
            errorFreeClauseRatio = efc,
            structures = allStructures.distinctBy { it.type to it.excerpt },
            distinctStructureCount = distinct.size,
            issues = issues,
        )
    }

    private fun safeExcerpt(original: String, lower: String, start: Int, end: Int): String =
        if (original.length == lower.length) original.substring(start, end) else lower.substring(start, end)

    private fun matchCase(original: String, replacement: String): String =
        if (original.isNotEmpty() && original[0].isUpperCase()) TextAnalysis.capitalizeFirst(replacement) else replacement
}
