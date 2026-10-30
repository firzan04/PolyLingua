package com.polylingua.edge.domain

/**
 * The 44 phonemes of Received Pronunciation (12 monophthongs, 8 diphthongs, 24 consonants) with
 * articulatory descriptions, coaching instructions, Indonesian-L1 interference notes and minimal
 * pairs, plus ARPAbet (CMUdict, 39 symbols) <-> IPA conversion with RP non-rhotic rendering.
 * Phonetic facts follow standard descriptions (place/manner/voicing; vowel height/backness).
 */
object PhoneticArticulationDatabase {

    enum class Kind { MONOPHTHONG, DIPHTHONG, CONSONANT }

    data class Phoneme(
        val ipa: String,
        val arpabet: String?,          // null for RP-only centring diphthongs and /ɒ/
        val kind: Kind,
        val description: String,       // e.g. "voiceless dental fricative"
        val instruction: String,       // tongue / lips / teeth / voicing / airflow
        val indonesianL1Note: String,
        val examples: List<String>,
        val minimalPairs: List<Pair<String, String>>,
        val confusableWith: List<String>,  // ARPAbet symbols commonly substituted
    )

    val PHONEMES: List<Phoneme> = listOf(
        // ---------------------------------------------------------------- monophthongs (12)
        Phoneme("iː", "IY", Kind.MONOPHTHONG, "long close front unrounded vowel",
            "Spread the lips into a slight smile, raise the front of the tongue high towards the hard palate and hold the sound longer than /ɪ/.",
            "Indonesian has one /i/; learners often make 'sheep' and 'ship' identical. Lengthen and tense /iː/.",
            listOf("see", "team", "people"), listOf("sheep" to "ship", "leave" to "live", "feet" to "fit"), listOf("IH")),
        Phoneme("ɪ", "IH", Kind.MONOPHTHONG, "short near-close near-front unrounded vowel",
            "Relax the lips and tongue; the tongue is slightly lower and further back than for /iː/. Keep it short.",
            "Often replaced by Indonesian /i/ (too tense). Keep it short and lax.",
            listOf("sit", "big", "busy"), listOf("ship" to "sheep", "bit" to "beat", "fill" to "feel"), listOf("IY", "EH")),
        Phoneme("e", "EH", Kind.MONOPHTHONG, "short close-mid front unrounded vowel",
            "Open the jaw slightly more than for /ɪ/, tongue front and mid-high, lips neutral.",
            "Generally close to Indonesian /e/ (é); keep it distinct from /æ/.",
            listOf("bed", "head", "said"), listOf("bed" to "bad", "men" to "man", "pen" to "pan"), listOf("AE", "IH")),
        Phoneme("æ", "AE", Kind.MONOPHTHONG, "short open front unrounded vowel",
            "Drop the jaw well open, spread the lips, keep the tongue low and forward; it is wider than /e/.",
            "Not in Indonesian - commonly replaced by /e/ ('bad' -> 'bed'). Open the mouth much wider.",
            listOf("cat", "black", "happen"), listOf("bad" to "bed", "man" to "men", "sat" to "set"), listOf("EH", "AH")),
        Phoneme("ʌ", "AH", Kind.MONOPHTHONG, "short open-mid back unrounded vowel (central in modern RP)",
            "Relax the lips, open the jaw moderately and keep the tongue central-low; short and unrounded.",
            "Often pronounced as Indonesian /a/ (too open/long) or spelled-o /o/ ('love' as 'lov'). Keep it short and central.",
            listOf("cup", "love", "money"), listOf("cup" to "cap", "cut" to "cart", "hut" to "heart"), listOf("AA", "AE", "AO")),
        Phoneme("ɑː", "AA", Kind.MONOPHTHONG, "long open back unrounded vowel",
            "Open the jaw wide, pull the tongue low and back, lips relaxed; hold it long.",
            "Usually fine but too short; make it longer than /ʌ/.",
            listOf("car", "father", "start"), listOf("cart" to "cut", "heart" to "hut", "barn" to "bun"), listOf("AH")),
        Phoneme("ɒ", null, Kind.MONOPHTHONG, "short open back rounded vowel (RP 'lot'; GA uses /ɑ/)",
            "Open the jaw, round the lips slightly, tongue low and back; short.",
            "Indonesian /o/ is too close; open the mouth more.",
            listOf("hot", "what", "knowledge"), listOf("pot" to "port", "cot" to "caught", "shot" to "short"), listOf("AO", "AA")),
        Phoneme("ɔː", "AO", Kind.MONOPHTHONG, "long open-mid back rounded vowel",
            "Round the lips firmly, tongue back and mid-low; hold it long.",
            "Often too short, or merged with /ɒ/. Lengthen and round.",
            listOf("door", "thought", "law"), listOf("caught" to "cot", "port" to "pot", "law" to "low"), listOf("AA", "OW")),
        Phoneme("ʊ", "UH", Kind.MONOPHTHONG, "short near-close near-back rounded vowel",
            "Lips loosely rounded, tongue high-back but relaxed; short.",
            "Merged with Indonesian /u/ ('full' = 'fool'). Relax and shorten it.",
            listOf("book", "could", "woman"), listOf("pull" to "pool", "full" to "fool", "look" to "Luke"), listOf("UW")),
        Phoneme("uː", "UW", Kind.MONOPHTHONG, "long close back rounded vowel",
            "Round and push the lips forward, tongue high and back; hold it long.",
            "Fine in quality but often too short; lengthen it.",
            listOf("food", "blue", "two"), listOf("pool" to "pull", "fool" to "full", "Luke" to "look"), listOf("UH")),
        Phoneme("ɜː", "ER", Kind.MONOPHTHONG, "long open-mid central unrounded vowel",
            "Keep the lips neutral and the tongue resting in the centre of the mouth; no /r/ in RP. Hold it long.",
            "Not in Indonesian - often said as /e/ + trilled r ('bird' -> 'berd'). Keep the tongue flat and central.",
            listOf("bird", "work", "learn"), listOf("bird" to "bed", "word" to "wed", "hurt" to "heart"), listOf("EH", "AH", "AA")),
        Phoneme("ə", "AH0", Kind.MONOPHTHONG, "schwa - mid central unstressed vowel",
            "Completely relax the jaw, lips and tongue; very short. It appears only in unstressed syllables.",
            "Indonesian pronounces every vowel fully ('a-bout', 'com-pu-ter'); reduce unstressed vowels to schwa for natural rhythm.",
            listOf("about", "teacher", "banana"), listOf("a'ccept" to "e'xcept"), listOf("AA", "EH")),
        // ------------------------------------------------------------------ diphthongs (8)
        Phoneme("eɪ", "EY", Kind.DIPHTHONG, "closing diphthong from /e/ towards /ɪ/",
            "Start at /e/ and glide the tongue upwards to /ɪ/.",
            "Often flattened to a pure /e/ ('late' -> 'let'). Make the glide audible.",
            listOf("day", "late", "great"), listOf("late" to "let", "pain" to "pen", "wait" to "wet"), listOf("EH")),
        Phoneme("aɪ", "AY", Kind.DIPHTHONG, "closing diphthong from /a/ towards /ɪ/",
            "Start with an open /a/ and glide up to /ɪ/.",
            "Usually fine (Indonesian 'ai' in 'pantai').",
            listOf("time", "fly", "high"), listOf("bite" to "bait", "buy" to "bay", "light" to "late"), listOf("EY")),
        Phoneme("ɔɪ", "OY", Kind.DIPHTHONG, "closing diphthong from /ɔ/ towards /ɪ/",
            "Start with rounded /ɔ/ and glide up and forward to /ɪ/.",
            "Usually fine (Indonesian 'oi' in 'amboi').",
            listOf("boy", "choice", "noise"), listOf("boil" to "bowl", "toy" to "tie"), listOf("OW")),
        Phoneme("əʊ", "OW", Kind.DIPHTHONG, "closing diphthong from /ə/ towards /ʊ/ (GA /oʊ/)",
            "Start with a neutral schwa and round the lips as you glide to /ʊ/.",
            "Often replaced by pure Indonesian /o/ ('coat' -> 'cot'). Add the rounding glide.",
            listOf("go", "home", "know"), listOf("coat" to "caught", "low" to "law", "boat" to "bought"), listOf("AO", "AA")),
        Phoneme("aʊ", "AW", Kind.DIPHTHONG, "closing diphthong from /a/ towards /ʊ/",
            "Start open and round the lips as the tongue rises back to /ʊ/.",
            "Usually fine (Indonesian 'au' in 'pulau').",
            listOf("now", "house", "about"), listOf("found" to "fond", "town" to "tone"), listOf("OW")),
        Phoneme("ɪə", null, Kind.DIPHTHONG, "centring diphthong from /ɪ/ to /ə/ (RP; ARPAbet IH/IY + R)",
            "Start at /ɪ/ and relax towards schwa; do not pronounce a strong /r/ in RP.",
            "Often said as /i/ + trilled r ('here' -> 'hir'). Glide to schwa instead.",
            listOf("here", "ear", "idea"), listOf("beer" to "bear", "fear" to "fair"), listOf("EH")),
        Phoneme("eə", null, Kind.DIPHTHONG, "centring diphthong from /e/ to /ə/ (RP; ARPAbet EH + R)",
            "Start at /e/ and relax towards schwa; in modern RP it is often a long /ɛː/.",
            "Often said as /e/ + trilled r. Lengthen the vowel and drop the trill.",
            listOf("hair", "where", "care"), listOf("bear" to "beer", "fair" to "fear"), listOf("IH")),
        Phoneme("ʊə", null, Kind.DIPHTHONG, "centring diphthong from /ʊ/ to /ə/ (RP; often /ɔː/ today)",
            "Start at /ʊ/ and relax to schwa ('pure', 'tour').",
            "Often /u/ + trilled r; glide to schwa.",
            listOf("tour", "pure", "sure"), listOf("poor" to "paw", "tour" to "tore"), listOf("AO")),
        // ------------------------------------------------------------------- consonants (24)
        Phoneme("p", "P", Kind.CONSONANT, "voiceless bilabial plosive",
            "Close both lips, build air pressure, release with a puff of air (aspiration) before stressed vowels.",
            "Indonesian /p/ is unaspirated; English 'pin' needs a clear puff or it sounds like 'bin'.",
            listOf("pen", "happy", "cup"), listOf("pin" to "bin", "pea" to "bee", "cap" to "cab"), listOf("B", "F")),
        Phoneme("b", "B", Kind.CONSONANT, "voiced bilabial plosive",
            "Close the lips, vibrate the vocal folds, release gently without a puff.",
            "Final /b/ is devoiced to /p/ in Indonesian ('cab' -> 'cap'). Keep voicing and lengthen the vowel before it.",
            listOf("bad", "rubber", "job"), listOf("bin" to "pin", "rib" to "rip", "berry" to "very"), listOf("P", "V")),
        Phoneme("t", "T", Kind.CONSONANT, "voiceless alveolar plosive",
            "Tongue tip on the alveolar ridge (just behind the upper teeth), aspirate before stressed vowels.",
            "Indonesian /t/ is dental and unaspirated; place the tongue tip on the ridge, not the teeth.",
            listOf("tea", "better", "cat"), listOf("tin" to "thin", "time" to "dime", "bet" to "bed"), listOf("TH", "D")),
        Phoneme("d", "D", Kind.CONSONANT, "voiced alveolar plosive",
            "Tongue tip on the alveolar ridge with voicing; no puff.",
            "Final /d/ becomes /t/ ('bed' -> 'bet'). Keep the voicing and the longer vowel.",
            listOf("day", "ladder", "good"), listOf("day" to "they", "bed" to "bet", "den" to "then"), listOf("T", "DH")),
        Phoneme("k", "K", Kind.CONSONANT, "voiceless velar plosive",
            "Back of the tongue against the soft palate, release with aspiration before stressed vowels.",
            "Final /k/ is often replaced by a glottal stop (as in 'bapak'). Release it audibly.",
            listOf("key", "school", "back"), listOf("coat" to "goat", "back" to "bag", "class" to "glass"), listOf("G")),
        Phoneme("ɡ", "G", Kind.CONSONANT, "voiced velar plosive",
            "Back of the tongue against the soft palate with voicing.",
            "Final /ɡ/ devoices to /k/ ('bag' -> 'back').",
            listOf("go", "bigger", "dog"), listOf("goat" to "coat", "bag" to "back", "glass" to "class"), listOf("K")),
        Phoneme("f", "F", Kind.CONSONANT, "voiceless labiodental fricative",
            "Lightly touch the lower lip to the upper teeth and push air through continuously.",
            "Some speakers replace /f/ with /p/ ('coffee' -> 'kopi'). Keep the lip-teeth friction.",
            listOf("fish", "coffee", "laugh"), listOf("fast" to "vast", "fan" to "van", "fine" to "pine"), listOf("P", "V")),
        Phoneme("v", "V", Kind.CONSONANT, "voiced labiodental fricative",
            "Lower lip against upper teeth with voicing: you should feel a buzz on the lip.",
            "Very often replaced by /f/ or /p/ ('very' -> 'fery'). Add the vocal-fold buzz.",
            listOf("very", "never", "five"), listOf("vast" to "fast", "van" to "fan", "vest" to "best"), listOf("F", "B", "W")),
        Phoneme("θ", "TH", Kind.CONSONANT, "voiceless dental fricative",
            "Put the tongue tip lightly between or just behind the upper front teeth and blow air through - no voicing.",
            "Not in Indonesian: replaced by /t/ ('three' -> 'tree') or /s/ ('think' -> 'sink'). Show the tongue tip.",
            listOf("think", "nothing", "month"), listOf("think" to "sink", "three" to "tree", "thin" to "tin"), listOf("S", "T", "F")),
        Phoneme("ð", "DH", Kind.CONSONANT, "voiced dental fricative",
            "Same position as /θ/ but with the vocal folds vibrating.",
            "Replaced by /d/ ('they' -> 'day'). Tongue between the teeth with voicing.",
            listOf("this", "mother", "breathe"), listOf("they" to "day", "then" to "den", "breathe" to "breed"), listOf("D", "Z")),
        Phoneme("s", "S", Kind.CONSONANT, "voiceless alveolar fricative",
            "Tongue tip close to the alveolar ridge, a narrow groove for a hissing stream of air.",
            "Usually fine; but /s/ is often used instead of /ʃ/, /θ/ and /z/.",
            listOf("sun", "listen", "bus"), listOf("sip" to "ship", "sink" to "think", "rice" to "rise"), listOf("SH", "TH", "Z")),
        Phoneme("z", "Z", Kind.CONSONANT, "voiced alveolar fricative",
            "As for /s/ but with voicing - a buzzing 'zzz'.",
            "Indonesian lacks a native /z/ in most words: 'zoo' -> 'soo', plural -s on 'dogs' devoiced.",
            listOf("zoo", "busy", "dogs"), listOf("zip" to "sip", "rise" to "rice", "eyes" to "ice"), listOf("S", "JH")),
        Phoneme("ʃ", "SH", Kind.CONSONANT, "voiceless post-alveolar fricative",
            "Tongue a little further back than /s/, lips rounded and pushed forward ('shh').",
            "Often replaced by /s/ ('she' -> 'see'). Round the lips and pull the tongue back.",
            listOf("she", "nation", "wash"), listOf("ship" to "sip", "she" to "see", "wash" to "watch"), listOf("S", "CH")),
        Phoneme("ʒ", "ZH", Kind.CONSONANT, "voiced post-alveolar fricative",
            "As for /ʃ/ with voicing ('measure').",
            "Replaced by /s/, /ʃ/ or /dʒ/. Keep it a smooth, voiced fricative.",
            listOf("measure", "vision", "usual"), listOf("leisure" to "ledger", "vision" to "fission"), listOf("SH", "JH", "Z")),
        Phoneme("h", "HH", Kind.CONSONANT, "voiceless glottal fricative",
            "Open the vocal tract and breathe out audibly before the vowel.",
            "Silent 'h' words (hour, honest) should have no /h/; elsewhere do not drop it.",
            listOf("hat", "behind", "whole"), listOf("heat" to "eat", "hair" to "air", "hold" to "old"), emptyList()),
        Phoneme("tʃ", "CH", Kind.CONSONANT, "voiceless post-alveolar affricate",
            "Start with a /t/ closure further back on the ridge and release into /ʃ/; lips slightly rounded.",
            "Close to Indonesian 'c' but needs more friction release.",
            listOf("chair", "teacher", "watch"), listOf("chair" to "share", "watch" to "wash", "cheap" to "jeep"), listOf("SH", "JH")),
        Phoneme("dʒ", "JH", Kind.CONSONANT, "voiced post-alveolar affricate",
            "Voiced version of /tʃ/: /d/ closure released into /ʒ/.",
            "Close to Indonesian 'j'; final /dʒ/ ('bridge') devoices to /tʃ/.",
            listOf("job", "danger", "bridge"), listOf("jeep" to "cheap", "jet" to "yet", "ridge" to "rich"), listOf("CH", "Y", "Z")),
        Phoneme("m", "M", Kind.CONSONANT, "voiced bilabial nasal",
            "Close the lips and let the air flow through the nose with voicing.",
            "Usually fine.",
            listOf("man", "summer", "home"), listOf("mail" to "nail", "sum" to "sun"), listOf("N")),
        Phoneme("n", "N", Kind.CONSONANT, "voiced alveolar nasal",
            "Tongue tip on the alveolar ridge, air through the nose.",
            "Usually fine; do not replace final /n/ with /ŋ/.",
            listOf("no", "dinner", "sun"), listOf("sin" to "sing", "thin" to "thing", "ban" to "bang"), listOf("NG", "M")),
        Phoneme("ŋ", "NG", Kind.CONSONANT, "voiced velar nasal",
            "Back of the tongue against the soft palate, air through the nose; do not add /ɡ/ after it.",
            "Exists in Indonesian ('ng') but learners sometimes add a hard /ɡ/ ('sing-g').",
            listOf("sing", "thinking", "long"), listOf("sing" to "sin", "thing" to "thin", "bang" to "ban"), listOf("N")),
        Phoneme("l", "L", Kind.CONSONANT, "voiced alveolar lateral approximant",
            "Tongue tip on the ridge, air flows round the sides of the tongue; final /l/ is 'dark' (tongue back raised).",
            "Clear Indonesian /l/ everywhere; practise the dark /ɫ/ in 'feel', 'world'.",
            listOf("light", "hello", "feel"), listOf("light" to "right", "collect" to "correct", "glass" to "grass"), listOf("R")),
        Phoneme("r", "R", Kind.CONSONANT, "voiced post-alveolar approximant (ɹ)",
            "Curl the tongue tip up towards (but not touching) the back of the ridge; lips slightly rounded. No trill. In RP it is only pronounced before a vowel.",
            "Indonesian /r/ is trilled or tapped. Use a smooth approximant and drop it after vowels in RP ('car' = /kɑː/).",
            listOf("red", "sorry", "around"), listOf("right" to "light", "correct" to "collect", "grass" to "glass"), listOf("L")),
        Phoneme("w", "W", Kind.CONSONANT, "voiced labial-velar approximant",
            "Round the lips tightly then open them into the vowel.",
            "Sometimes confused with /v/ ('wine' vs 'vine').",
            listOf("we", "away", "quick"), listOf("wine" to "vine", "west" to "vest", "wet" to "vet"), listOf("V")),
        Phoneme("j", "Y", Kind.CONSONANT, "voiced palatal approximant",
            "Raise the front of the tongue towards the hard palate, then glide into the vowel ('yes').",
            "Usually fine (Indonesian 'y'); keep it distinct from /dʒ/.",
            listOf("yes", "use", "beyond"), listOf("yet" to "jet", "yes" to "jess", "year" to "ear"), listOf("JH")),
    )

    private val byArpabet: Map<String, Phoneme> by lazy {
        PHONEMES.filter { it.arpabet != null }.associateBy { it.arpabet!! }
    }
    private val byIpa: Map<String, Phoneme> by lazy { PHONEMES.associateBy { it.ipa } }

    /** Strips CMUdict stress digits ("AH0" -> "AH"). */
    fun baseSymbol(arpabet: String): String = arpabet.trimEnd('0', '1', '2')

    fun stressOf(arpabet: String): Int = arpabet.lastOrNull()?.let { if (it.isDigit()) it - '0' else -1 } ?: -1

    fun isVowel(arpabet: String): Boolean = baseSymbol(arpabet) in VOWELS

    val VOWELS = setOf("AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW")

    /** The 39 ARPAbet phonemes of CMUdict (no stress). */
    val ARPABET_39: List<String> = listOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "B", "CH", "D", "DH", "EH", "ER", "EY", "F", "G", "HH", "IH", "IY", "JH",
        "K", "L", "M", "N", "NG", "OW", "OY", "P", "R", "S", "SH", "T", "TH", "UH", "UW", "V", "W", "Y", "Z", "ZH",
    )

    /** Info for an ARPAbet symbol (stress-aware: unstressed AH/ER map to schwa). */
    fun lookup(arpabet: String): Phoneme? {
        val base = baseSymbol(arpabet)
        val stress = stressOf(arpabet)
        if (base == "AH" && stress == 0) return byIpa["ə"]
        if (base == "ER" && stress == 0) return byIpa["ə"]
        return byArpabet[base]
    }

    fun ipaFor(arpabet: String, rp: Boolean = true): String {
        val base = baseSymbol(arpabet)
        val stress = stressOf(arpabet)
        return when (base) {
            "AA" -> if (rp) "ɑː" else "ɑ"
            "AE" -> "æ"
            "AH" -> if (stress == 0) "ə" else "ʌ"
            "AO" -> if (rp) "ɔː" else "ɔ"
            "AW" -> "aʊ"
            "AY" -> "aɪ"
            "EH" -> if (rp) "e" else "ɛ"
            "ER" -> if (stress == 0) (if (rp) "ə" else "ɚ") else (if (rp) "ɜː" else "ɝ")
            "EY" -> "eɪ"
            "IH" -> "ɪ"
            "IY" -> if (stress == 0) "i" else if (rp) "iː" else "i"
            "OW" -> if (rp) "əʊ" else "oʊ"
            "OY" -> "ɔɪ"
            "UH" -> "ʊ"
            "UW" -> if (rp) "uː" else "u"
            "B" -> "b"; "CH" -> "tʃ"; "D" -> "d"; "DH" -> "ð"; "F" -> "f"; "G" -> "ɡ"; "HH" -> "h"; "JH" -> "dʒ"
            "K" -> "k"; "L" -> "l"; "M" -> "m"; "N" -> "n"; "NG" -> "ŋ"; "P" -> "p"; "R" -> "r"; "S" -> "s"
            "SH" -> "ʃ"; "T" -> "t"; "TH" -> "θ"; "V" -> "v"; "W" -> "w"; "Y" -> "j"; "Z" -> "z"; "ZH" -> "ʒ"
            else -> base.lowercase()
        }
    }

    private val LEGAL_ONSETS = setOf(
        "P R", "B R", "T R", "D R", "K R", "G R", "F R", "TH R", "SH R", "P L", "B L", "K L", "G L", "F L", "S L",
        "S P", "S T", "S K", "S M", "S N", "S W", "T W", "D W", "K W", "G W", "P Y", "B Y", "F Y", "K Y", "M Y", "HH Y",
        "S P R", "S T R", "S K R", "S P L", "S K W",
    )

    /**
     * Converts an ARPAbet pronunciation into IPA with primary (ˈ) and secondary (ˌ) stress marks
     * placed before the maximal legal onset. RP mode renders non-prevocalic /r/ as length or a
     * centring diphthong (car /kɑː/, here /hɪə/, hair /heə/, tour /tʊə/).
     */
    fun toIpa(arpabet: List<String>, rp: Boolean = true, stressMarks: Boolean = true): String {
        val symbols = arpabet.map { it.uppercase() }
        val pieces = ArrayList<String>(symbols.size)
        var i = 0
        while (i < symbols.size) {
            val s = symbols[i]
            val base = baseSymbol(s)
            val next = symbols.getOrNull(i + 1)
            val nextIsR = next != null && baseSymbol(next) == "R"
            val afterR = symbols.getOrNull(i + 2)
            val rIsCoda = nextIsR && (afterR == null || !isVowel(afterR))
            if (rp && isVowel(s) && rIsCoda && base in setOf("IH", "IY", "EH", "UH", "UW", "AA", "AO")) {
                pieces += when (base) {
                    "IH", "IY" -> "ɪə"; "EH" -> "eə"; "UH", "UW" -> "ʊə"; "AA" -> "ɑː"; else -> "ɔː"
                }
                i += 2
                continue
            }
            if (rp && base == "R" && i > 0 && isVowel(symbols[i - 1]) && (next == null || !isVowel(next))) {
                i++
                continue  // non-rhotic: drop coda /r/
            }
            pieces += ipaFor(s, rp)
            i++
        }
        if (!stressMarks) return toIpaNoMarks(symbols, rp)
        // Insert stress marks: map piece index back to source symbols is approximate after RP
        // merges, so recompute on the original sequence and splice into the rendered string.
        val marks = HashMap<Int, String>()
        var vowelOrdinal = 0
        for ((idx, s) in symbols.withIndex()) {
            if (!isVowel(s)) continue
            val st = stressOf(s)
            if (st == 1 || st == 2) {
                var onsetStart = idx
                var k = idx - 1
                while (k >= 0 && !isVowel(symbols[k])) k--
                val consonants = symbols.subList(k + 1, idx).map { baseSymbol(it) }
                for (len in minOf(3, consonants.size) downTo 1) {
                    val cluster = consonants.takeLast(len).joinToString(" ")
                    if (len == 1 || cluster in LEGAL_ONSETS) { onsetStart = idx - len; break }
                }
                if (k < 0) onsetStart = 0
                marks[onsetStart] = if (st == 1) "ˈ" else "ˌ"
            }
            vowelOrdinal++
        }
        if (vowelOrdinal <= 1) return pieces.joinToString("")  // monosyllables: no stress mark
        // Rebuild with marks, skipping symbols consumed by RP merges in the same way as above.
        val sb = StringBuilder()
        i = 0
        while (i < symbols.size) {
            marks[i]?.let { sb.append(it) }
            val s = symbols[i]
            val base = baseSymbol(s)
            val next = symbols.getOrNull(i + 1)
            val nextIsR = next != null && baseSymbol(next) == "R"
            val afterR = symbols.getOrNull(i + 2)
            val rIsCoda = nextIsR && (afterR == null || !isVowel(afterR))
            if (rp && isVowel(s) && rIsCoda && base in setOf("IH", "IY", "EH", "UH", "UW", "AA", "AO")) {
                sb.append(when (base) { "IH", "IY" -> "ɪə"; "EH" -> "eə"; "UH", "UW" -> "ʊə"; "AA" -> "ɑː"; else -> "ɔː" })
                i += 2
                continue
            }
            if (rp && base == "R" && i > 0 && isVowel(symbols[i - 1]) && (next == null || !isVowel(next))) { i++; continue }
            if (rp && base == "ER" && next != null && isVowel(next)) {
                // r-coloured vowel before a vowel: the /r/ is pronounced (curriculum /kəˈrɪkjələm/)
                sb.append(ipaFor(s, rp))
                marks.remove(i + 1)?.let { sb.append(it) }
                sb.append("r")
                i++
                continue
            }
            sb.append(ipaFor(s, rp))
            i++
        }
        return sb.toString()
    }

    private fun toIpaNoMarks(symbols: List<String>, rp: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < symbols.size) {
            val s = symbols[i]
            val base = baseSymbol(s)
            val next = symbols.getOrNull(i + 1)
            val nextIsR = next != null && baseSymbol(next) == "R"
            val afterR = symbols.getOrNull(i + 2)
            val rIsCoda = nextIsR && (afterR == null || !isVowel(afterR))
            if (rp && isVowel(s) && rIsCoda && base in setOf("IH", "IY", "EH", "UH", "UW", "AA", "AO")) {
                sb.append(when (base) { "IH", "IY" -> "ɪə"; "EH" -> "eə"; "UH", "UW" -> "ʊə"; "AA" -> "ɑː"; else -> "ɔː" })
                i += 2
                continue
            }
            if (rp && base == "R" && i > 0 && isVowel(symbols[i - 1]) && (next == null || !isVowel(next))) { i++; continue }
            sb.append(ipaFor(s, rp))
            if (rp && base == "ER" && next != null && isVowel(next)) sb.append("r")
            i++
        }
        return sb.toString()
    }

    /** Stress pattern in pronunciation-book notation: "Oo" (TEAcher), "oOo" (comPUter). */
    fun stressPattern(arpabet: List<String>): String {
        val primary = primaryStressSyllable(arpabet)
        val n = syllableCount(arpabet)
        return (0 until n).joinToString("") { if (it == primary) "O" else "o" }
    }

    /** Index (0-based syllable) of the primary stress, or -1. */
    fun primaryStressSyllable(arpabet: List<String>): Int {
        var syl = 0
        for (s in arpabet) {
            if (isVowel(s)) {
                if (stressOf(s) == 1) return syl
                syl++
            }
        }
        return -1
    }

    fun syllableCount(arpabet: List<String>): Int = arpabet.count { isVowel(it) }

    /** Remediation sentence such as "Detected /s/ instead of /θ/ - common Indonesian L1 interference". */
    fun remediationHeadline(expected: String, detected: String?): String {
        val exp = lookup(expected)
        val expIpa = exp?.ipa ?: ipaFor(expected)
        if (detected == null || baseSymbol(detected) == baseSymbol(expected)) return "Target /$expIpa/ was unclear - tighten the articulation."
        val detIpa = ipaFor(detected)
        val common = exp?.confusableWith?.contains(baseSymbol(detected)) == true
        return "Detected /$detIpa/ instead of /$expIpa/" + if (common) " - common Indonesian L1 interference" else ""
    }

    fun byIpaSymbol(ipa: String): Phoneme? = byIpa[ipa]
}
