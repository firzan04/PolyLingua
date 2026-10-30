package com.polylingua.edge.network

import com.polylingua.edge.data.seed.CurriculumContent
import com.polylingua.edge.data.seed.Domain
import com.polylingua.edge.domain.GrammarPatternDetector
import com.polylingua.edge.domain.LexicalUpgradeDictionary
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.speech.ChatTurn
import com.polylingua.edge.speech.LocalLlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** An offline AI conversation partner. */
data class Persona(
    val id: String,
    val name: String,
    val city: String,
    val flag: String,
    val accentLabel: String,
    val accentTag: String,
    val female: Boolean,
    val role: String,
    val bio: String,
    val avatar: String,
    val domains: List<String>,
    val dialect: List<String>,      // signature interjections
    val voicePrefs: List<String>,   // SynthVoice ids, best first
    val greeting: String,
    val anecdotes: List<String>,
    val bilingualIndonesian: Boolean = false,
)

object PersonaCatalog {
    val ALL: List<Persona> = listOf(
        Persona("liam", "Liam", "London", "🇬🇧", "British RP", "en-GB", false, "Barista & Premier League fanatic",
            "Twenty-six, works in a coffee shop in Shoreditch, plays five-a-side every Thursday.", "☕",
            listOf("sports", "food", "hometown", "leisure"), listOf("Brilliant!", "Fair enough.", "Cheers for that.", "Proper good."),
            listOf("kokoro:bm_george", "piper:alan", "kokoro:bm_lewis", "system:en-GB"),
            "Alright? I'm Liam, from London. Grab a cuppa and let's have a proper natter.",
            listOf("I actually grew up in a tiny flat above a bakery, so I've always been a morning person.",
                "Last weekend I queued for two hours just to see Arsenal lose. Classic.",
                "Honestly, a decent flat white is the only thing that gets me through a Monday.")),
        Persona("sarah", "Sarah", "San Francisco, California", "🇺🇸", "General American", "en-US", true, "Product manager at a tech start-up",
            "Thirty, ships mobile apps by day and hikes the Bay Area trails at weekends.", "💻",
            listOf("technology", "ai", "work", "socialmedia"), listOf("Awesome!", "Totally.", "That's super interesting.", "For sure."),
            listOf("kokoro:af_sarah", "kokoro:af_nicole", "piper:lessac", "system:en-US"),
            "Hey there! I'm Sarah from San Francisco. I'm super curious about life in Indonesia - want to chat?",
            listOf("At my company we ran a whole sprint just on making our app work offline - it was wild.",
                "I try to take one full day off screens every month. It's harder than it sounds!",
                "My first job was at a tiny start-up that ran out of money in six months, but I learned a ton.")),
        Persona("chloe", "Chloe", "Melbourne", "🇦🇺", "Australian", "en-AU", true, "Marine biologist",
            "Twenty-eight, studies coral reefs and surfs whenever the swell is good.", "🐠",
            listOf("wildlife", "environment", "climate", "travel"), listOf("No worries!", "Heaps good.", "Too right.", "Fair dinkum?"),
            listOf("system:en-AU", "kokoro:af_sky", "kokoro:af_bella"),
            "G'day! Chloe here, from Melbourne. Reckon you could tell me about your favourite place in nature?",
            listOf("I spent three months on a research boat near the Great Barrier Reef - no Wi-Fi at all!",
                "Last summer I volunteered to rescue sea turtles tangled in plastic nets.",
                "Melbourne has four seasons in one day, so I always carry a jumper and sunscreen.")),
        Persona("budi_emma", "Budi & Emma", "Jakarta ↔ Bristol", "🇮🇩🇬🇧", "Bilingual EN↔ID", "en-GB", true, "Language-exchange duo",
            "Emma teaches English in Bristol; Budi is a Jakarta tour guide who explains tricky points in Bahasa Indonesia.", "🤝",
            listOf("language", "family", "food", "heritage"), listOf("Lovely!", "Mantap!", "Spot on.", "Keren!"),
            listOf("kokoro:bf_emma", "system:en-GB"),
            "Hi, I'm Emma from Bristol - and Budi from Jakarta is here too! Budi: Santai saja, kita latihan pelan-pelan ya.",
            listOf("Emma: My first Indonesian word was 'terima kasih' - I said it to everyone for a week!",
                "Emma: Budi once took me to eat soto Betawi at six in the morning. Best breakfast ever.",
                "Emma: In Bristol we say 'cheers' for thank you, goodbye and almost everything else."),
            bilingualIndonesian = true),
        Persona("eleanor", "Dr Eleanor Hughes", "Cambridge", "🇬🇧", "British RP (formal)", "en-GB", true, "Retired IELTS examiner",
            "Spent twenty years examining speaking tests; precise, kind and demanding.", "🎓",
            listOf("education", "science", "government", "media"), listOf("Very good.", "Indeed.", "Splendid.", "Quite so."),
            listOf("kokoro:bf_isabella", "kokoro:bf_emma", "system:en-GB"),
            "Good afternoon. I'm Dr Hughes. Shall we practise as if this were the real speaking test?",
            listOf("In my experience, candidates who extend their answers with reasons and examples score markedly higher.",
                "I once examined a candidate who answered every question with a proverb - charming, but not quite relevant.",
                "Cambridge in spring is lovely; the college gardens are full of daffodils.")),
        Persona("marcus", "Marcus", "New York", "🇺🇸", "New York American", "en-US", false, "Investment analyst & interviewer",
            "Thirty-five, interviews graduates for a Wall Street bank and runs marathons.", "📈",
            listOf("economics", "work", "globalization", "shopping"), listOf("Got it.", "Makes sense.", "Love that.", "Fair point."),
            listOf("kokoro:am_michael", "kokoro:am_adam", "system:en-US"),
            "Hi, Marcus here from New York. Let's run a quick job-interview style chat - ready?",
            listOf("I ran the New York City Marathon last year and walked funny for a week.",
                "The best candidates I interview always tell a short story with a clear result.",
                "My grandfather opened a deli in Brooklyn in 1962 - it's still there.")),
        Persona("priya", "Priya", "Toronto", "🇨🇦", "Canadian English", "en-US", true, "Emergency-room nurse",
            "Thirty-two, works night shifts and loves cooking her grandmother's recipes.", "🩺",
            listOf("healthcare", "psychology", "food", "family"), listOf("Oh, nice!", "Totally get that.", "That's lovely.", "Right on."),
            listOf("kokoro:af_nicole", "kokoro:af_bella", "system:en-US"),
            "Hi! I'm Priya, a nurse in Toronto. How are you feeling today, honestly?",
            listOf("Night shifts taught me to appreciate a really good breakfast.",
                "My grandmother's dal recipe is the cure for every cold in our family.",
                "In winter here it can hit minus twenty, so we live in our parkas.")),
        Persona("aiden", "Aiden", "Dublin", "🇮🇪", "Irish English", "en-GB", false, "Walking-tour guide",
            "Twenty-nine, tells ghost stories on Dublin walking tours and plays the tin whistle.", "🍀",
            listOf("travel", "heritage", "music", "hometown"), listOf("Grand!", "Ah, deadly.", "Sure look.", "Fair play to you."),
            listOf("system:en-IE", "kokoro:bm_lewis", "piper:alan"),
            "Howya! Aiden from Dublin. Fancy telling me about a place worth visiting in your country?",
            listOf("I once lost a whole tour group in Temple Bar - found them all in a pub singing.",
                "My granny can name every saint in Ireland and the weather on their feast day.",
                "I learned the tin whistle at seven and I'm still not very good at it.")),
        Persona("grace", "Grace", "Singapore", "🇸🇬", "Singapore English", "en-US", true, "Hotel concierge",
            "Twenty-seven, solves impossible guest requests at a Marina Bay hotel.", "🏨",
            listOf("travel", "shopping", "food", "urbanization"), listOf("Wonderful!", "Certainly.", "So nice.", "Absolutely."),
            listOf("kokoro:af_sky", "system:en-US"),
            "Welcome! I'm Grace from Singapore. How may I help you plan something special today?",
            listOf("Yesterday a guest asked me to find a violinist at midnight - and I did!",
                "Hawker centres here have the best chicken rice for about three dollars.",
                "Singapore is so compact that I can visit three neighbourhoods in an afternoon.")),
        Persona("oliver", "Oliver", "Edinburgh", "🏴", "Scottish English", "en-GB", false, "Environmental scientist & debater",
            "Thirty-four, researches renewable energy and loves a friendly argument.", "🌬️",
            listOf("energy", "climate", "environment", "transport"), listOf("Aye, good point.", "Cracking.", "Nae bother.", "Fair enough."),
            listOf("kokoro:bm_lewis", "kokoro:bm_george", "system:en-GB"),
            "Hiya, I'm Oliver from Edinburgh. I warn you - I love a good debate. Pick a side?",
            listOf("Scotland sometimes produces more wind power than it can actually use.",
                "I cycle to work even in sideways rain - it builds character, apparently.",
                "My PhD was on tidal energy, so I've spent many cold days on boats.")),
        Persona("kate", "Kate Walker", "Auckland", "🇳🇿", "New Zealand English", "en-GB", true, "Academic writing tutor",
            "Forty, helps international students polish essays and loves tramping in the hills.", "📚",
            listOf("education", "language", "art", "science"), listOf("Sweet as!", "Good on you.", "Choice!", "Lovely."),
            listOf("system:en-NZ", "kokoro:bf_emma", "system:en-GB"),
            "Kia ora! I'm Kate from Auckland. Tell me what you're learning at the moment?",
            listOf("I've marked so many essays that I dream in topic sentences.",
                "Last summer I walked the Tongariro Crossing - volcanoes, emerald lakes, sore legs.",
                "My students teach me as much as I teach them, honestly.")),
        Persona("jake", "Jake", "Austin, Texas", "🇺🇸", "Southern American", "en-US", false, "High-school basketball coach",
            "Thirty-one, coaches teenagers and smokes the best barbecue on the block.", "🏀",
            listOf("sports", "family", "leisure", "fashion"), listOf("Heck yeah!", "Y'all are awesome.", "Right on.", "No kidding?"),
            listOf("kokoro:am_adam", "kokoro:am_michael", "system:en-US"),
            "Howdy! Coach Jake from Austin. What do you do to stay active, partner?",
            listOf("My team lost the state final by one point - I still replay that last shot.",
                "Texas barbecue is basically a religion here; brisket takes fourteen hours.",
                "I tell my players effort beats talent when talent doesn't work hard.")),
    )

    fun byId(id: String): Persona? = ALL.firstOrNull { it.id == id }
}

// =============================================================================== feedback

enum class DiffKind { EQUAL, DELETE, INSERT }

data class DiffToken(val kind: DiffKind, val text: String)

/** Word-level LCS diff used to draw red (removed) / green (added) corrections. */
object WordDiff {
    fun diff(original: String, corrected: String): List<DiffToken> {
        val a = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = corrected.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (norm(a[i]) == norm(b[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<DiffToken>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                norm(a[i]) == norm(b[j]) -> { out += DiffToken(DiffKind.EQUAL, b[j]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffToken(DiffKind.DELETE, a[i]); i++ }
                else -> { out += DiffToken(DiffKind.INSERT, b[j]); j++ }
            }
        }
        while (i < n) out += DiffToken(DiffKind.DELETE, a[i++])
        while (j < m) out += DiffToken(DiffKind.INSERT, b[j++])
        return out
    }

    private fun norm(w: String) = w.lowercase().trim('.', ',', '!', '?', ';', ':', '"')
}

/** Red/green feedback for one learner utterance. */
data class UtteranceFeedback(
    val original: String,
    val corrected: String,
    val diff: List<DiffToken>,
    val notes: List<String>,
    val vocabularyTips: List<String>,
    val source: String,
) {
    val hasCorrections: Boolean get() = diff.any { it.kind != DiffKind.EQUAL }
}

/**
 * Offline conversation engine for the 12 personas. With a GGUF model installed, replies come from
 * the on-device LLM conditioned on the persona; otherwise a curriculum-driven dialogue manager
 * reacts to the learner's content, shares persona anecdotes and moves through Part 1 -> Part 3
 * style questions of the persona's domains. Feedback is always computed locally.
 */
@Singleton
class OfflinePersonaEngine @Inject constructor(private val llm: LocalLlmEngine) {

    inner class Conversation(val persona: Persona, private val level: CefrLevel, val motion: String? = null) {
        private val debateDomain: Domain? = motion?.let { m -> CurriculumContent.ALL.firstOrNull { d -> d.debate.any { it == m } } }
        private var debateTurn = 0
        private val domains: List<Domain> = (listOfNotNull(debateDomain) + persona.domains.mapNotNull { key -> CurriculumContent.ALL.firstOrNull { it.key == key } })
            .distinctBy { it.key }
            .ifEmpty { CurriculumContent.ALL.take(3) }
        private val asked = HashSet<String>()
        private var domainIndex = 0
        private var turnsInDomain = 0
        private var anecdoteIndex = 0
        private val random = Random(persona.id.hashCode())
        val history = ArrayList<ChatTurn>()

        val currentTopic: String get() = domains[domainIndex % domains.size].title

        fun opening(): String {
            if (motion != null) {
                val text = "${persona.greeting} Today's motion: \"$motion\" Which side are you on - for or against - and what's your strongest reason?"
                history += ChatTurn.assistant(text)
                return text
            }
            val q = nextQuestion()
            val text = "${persona.greeting} $q"
            history += ChatTurn.assistant(text)
            return text
        }

        /** Streams the persona's reply to [userText]. */
        fun reply(userText: String): Flow<String> = flow {
            history += ChatTurn.user(userText)
            val useLlm = llm.isAvailable
            val sb = StringBuilder()
            if (useLlm) {
                val turns = listOf(ChatTurn.system(systemPrompt())) + history.takeLast(8)
                // catch {} only intercepts upstream (model) failures, keeping exception transparency.
                emitAll(
                    llm.generate(turns, maxTokens = 110, temperature = 0.8f)
                        .map { piece -> sb.append(piece); sb.toString() }
                        .catch { sb.setLength(0) },
                )
            }
            var text = sb.toString().trim()
            if (text.isEmpty()) {
                text = ruleBasedReply(userText)
                emit(text)
            }
            history += ChatTurn.assistant(text)
        }

        private fun systemPrompt(): String {
            val topics = domains.joinToString { it.title }
            val base = "You are ${persona.name}, ${persona.role} from ${persona.city}. ${persona.bio} " +
                "You speak natural ${persona.accentLabel} English and sometimes use expressions like ${persona.dialect.joinToString { "\"$it\"" }}. " +
                "You are chatting by voice with an Indonesian learner of English (CEFR ${level.name}) who is practising for IELTS speaking. " +
                "Reply in 1-3 short spoken sentences (max 45 words), react to what they said, share a brief personal detail sometimes, " +
                "and end with exactly one open follow-up question. Do not correct their grammar (feedback is shown separately). " +
                "Use vocabulary suitable for ${level.name}${if (level.rank < CefrLevel.B2.rank) ", keep it simple" else ", include some natural idiomatic phrases"}. " +
                "Favourite topics: $topics."
            val withDebate = if (motion != null) {
                "$base This is a friendly Tandem debate on the motion \"$motion\". Take the opposite side to the learner, " +
                    "give one concise counter-argument with a reason or example, acknowledge any good point they make, and ask them to respond."
            } else base
            return if (persona.bilingualIndonesian) {
                "$withDebate You are actually two hosts: Emma (English teacher from Bristol) speaks first; then Budi (tour guide from Jakarta) adds one short helpful tip in Bahasa Indonesia starting with \"Budi:\"."
            } else withDebate
        }

        // ------------------------------------------------------------- rule-based dialogue

        private fun nextQuestion(): String {
            repeat(domains.size * 2) {
                val d = domains[domainIndex % domains.size]
                val pool = (d.part1 + d.part3 + d.roleplay.map { r -> r.substringAfterLast(": '", r).trimEnd('\'') })
                    .filter { it.endsWith("?") && it !in asked }
                if (pool.isNotEmpty() && turnsInDomain < 4) {
                    val q = if (turnsInDomain < 2) pool.first() else pool[random.nextInt(pool.size)]
                    asked += q
                    turnsInDomain++
                    return q
                }
                domainIndex++
                turnsInDomain = 0
            }
            asked.clear()
            return "What else would you like to talk about today?"
        }

        private fun debateReply(user: String): String {
            val d = debateDomain ?: domains.first()
            val i = d.ideas
            val lower = user.lowercase()
            val userAgainst = listOf("against", "disagree", "shouldn't", "should not", "don't think", "do not think", "oppose", "not a good").any { lower.contains(it) }
            val counters = if (userAgainst) listOf(i.pos1, i.pos2, i.future, "governments could ${i.policy}")
            else listOf(i.neg1, i.neg2, "it isn't that simple - ${i.example}, which shows the other side of the coin", "even ${i.idiom.lowercase()} has its limits here")
            val c = counters[debateTurn % counters.size]
            val opener = listOf("I see your point, but", "That's fair; however,", "Let me push back a little:", "Interesting argument - and yet")[debateTurn % 4]
            debateTurn++
            val ask = listOf("How would you answer that?", "Can you give an example that proves me wrong?", "What would you say to someone who disagrees?", "Is there any compromise you'd accept?")[debateTurn % 4]
            val tip = if (TextAnalysis.words(user).size < 12) " (Tip: state your claim, give a reason, then an example.)" else ""
            return "$opener $c. $ask$tip"
        }

        private fun ruleBasedReply(user: String): String {
            if (motion != null) return debateReply(user)
            val tokens = TextAnalysis.words(user)
            val wordCount = tokens.size
            val lower = user.lowercase()
            val askedBack = lower.contains("you?") || lower.contains("what about you") || lower.contains("and you") ||
                (lower.trim().endsWith("?") && (tokens.contains("you") || tokens.contains("your")))
            val positive = tokens.any { it in POSITIVE }
            val negative = tokens.any { it in NEGATIVE }
            val keyword = tokens.filter { TextAnalysis.isContentWord(it) && it.length >= 5 && it !in POSITIVE && it !in NEGATIVE }
                .maxByOrNull { it.length }
            val parts = ArrayList<String>()
            val interjection = persona.dialect[random.nextInt(persona.dialect.size)]
            when {
                askedBack -> {
                    parts += "Good question!"
                    parts += persona.anecdotes[anecdoteIndex++ % persona.anecdotes.size]
                }
                wordCount < 6 -> {
                    parts += if (positive) interjection else "I see."
                    parts += "Could you tell me a bit more? Try adding a reason and a quick example."
                }
                negative -> parts += "Oh no, that sounds tough." + (keyword?.let { " The $it part especially." } ?: "")
                positive -> parts += interjection + (keyword?.let { " I can tell $it really matters to you." } ?: "")
                else -> parts += listOf("Interesting!", "I see what you mean.", "That's a fair point.", interjection)[random.nextInt(4)] +
                    (keyword?.let { " Tell me - how did $it become part of your life?" }?.takeIf { random.nextInt(3) == 0 } ?: "")
            }
            if (!askedBack && random.nextInt(4) == 0) parts += persona.anecdotes[anecdoteIndex++ % persona.anecdotes.size]
            parts += nextQuestion()
            if (persona.bilingualIndonesian) {
                parts += if (wordCount < 6) "Budi: Coba jawab dengan 2-3 kalimat: pendapat, alasan, lalu contoh."
                else "Budi: Bagus! Perhatikan koreksi merah-hijau di bawah pesanmu ya."
            }
            return parts.joinToString(" ")
        }
    }

    fun start(persona: Persona, level: CefrLevel, motion: String? = null): Conversation = Conversation(persona, level, motion)

    /** Local red/green feedback (rules + vocabulary upgrades); refined by the LLM when present. */
    suspend fun feedback(text: String, topic: String, level: CefrLevel, useLlm: Boolean): UtteranceFeedback {
        val issues = GrammarPatternDetector.detectIssues(text).sortedBy { it.start }
        var corrected = text
        var shift = 0
        val notes = ArrayList<String>()
        for (iss in issues) {
            val s = iss.start + shift
            val e = iss.end + shift
            if (s < 0 || e > corrected.length || s > e) continue
            corrected = corrected.substring(0, s) + iss.suggestion + corrected.substring(e)
            shift += iss.suggestion.length - (iss.end - iss.start)
            notes += "${iss.excerpt} → ${iss.suggestion}: ${iss.explanation}"
        }
        val topicKey = CurriculumContent.ALL.firstOrNull { it.title.equals(topic, true) }?.key ?: topic
        val tips = LexicalUpgradeDictionary.findUpgrades(text, topicKey, limit = 3).map { "\"${it.original}\" → ${it.replacements.take(3).joinToString(" / ")}" }
        var source = "rules"
        if (useLlm && llm.isAvailable && TextAnalysis.words(text).size >= 4) {
            llm.correct(text, level.name)?.let { c ->
                if (c.corrected.isNotBlank()) {
                    corrected = c.corrected
                    source = "on-device AI"
                    if (c.explanation.isNotBlank()) notes += c.explanation
                    if (c.nativeAlternative.isNotBlank() && !c.nativeAlternative.equals(c.corrected, true)) notes += "Native-like: \"${c.nativeAlternative}\""
                }
            }
        }
        val finalCorrected = TextAnalysis.ensureTerminalPunctuation(TextAnalysis.capitalizeFirst(corrected.trim()))
        val diff = WordDiff.diff(text.trim(), finalCorrected)
        return UtteranceFeedback(text, finalCorrected, diff, notes.distinct(), tips, source)
    }

    companion object {
        private val POSITIVE = setOf("love", "like", "enjoy", "great", "amazing", "fun", "happy", "favourite", "favorite", "best", "wonderful", "excited", "nice", "beautiful", "awesome", "fantastic", "interesting")
        private val NEGATIVE = setOf("hate", "bad", "sad", "difficult", "hard", "stress", "stressful", "tired", "boring", "worst", "problem", "angry", "expensive", "dangerous", "sick", "worried")
    }
}
