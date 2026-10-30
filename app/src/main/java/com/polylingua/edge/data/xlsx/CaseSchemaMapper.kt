package com.polylingua.edge.data.xlsx

import com.polylingua.edge.domain.CefrLookup
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase

/**
 * Maps worksheet rows to [PracticeCase]s. Accepts the classic 11-column quiz/flashcard layout
 * and the extended 16-column speaking layout (plus optional Topic / Translation columns), by
 * header name when a header row exists or by position otherwise. Missing CEFR level, IELTS band,
 * module type and timings are inferred.
 */
object CaseSchemaMapper {

    enum class Field { NUMBER, PROMPT, C, D, E, F, G, H, I, J, K, CEFR, BAND, MODULE, PREP, SPEAK, TOPIC, TRANSLATION }

    private val ALIASES: Map<Field, List<String>> = mapOf(
        Field.NUMBER to listOf("nomor", "no", "number", "id", "index", "nr", "num"),
        Field.PROMPT to listOf("soalatauPromptspeaking", "soal", "prompt", "question", "pertanyaan", "cuecard", "sentence", "promptspeaking", "soalprompt"),
        Field.C to listOf("pilihana", "targetipaphonemes", "optiona", "a", "ipa", "pilihanatargetipaphonemes"),
        Field.D to listOf("pilihanb", "modelanswerband6b2", "optionb", "b", "pilihanbmodelanswerband6b2"),
        Field.E to listOf("pilihanc", "modelanswerband8c1", "optionc", "c", "pilihancmodelanswerband8c1"),
        Field.F to listOf("pilihand", "modelanswerband9c2", "optiond", "d", "pilihandmodelanswerband9c2"),
        Field.G to listOf("jawabanbenar", "primarytargettranscript", "answer", "kunci", "correct", "kuncijawaban", "jawaban", "jawabanbenarprimarytargettranscript"),
        Field.H to listOf("pembahasanpilihana", "feedbackfluencyrubric", "explanationa", "pembahasana"),
        Field.I to listOf("pembahasanpilihanb", "feedbacklexicalvocabulary", "explanationb", "pembahasanb"),
        Field.J to listOf("pembahasanpilihanc", "feedbackgrammarstructures", "explanationc", "pembahasanc"),
        Field.K to listOf("pembahasanpilihand", "feedbackpronunciationintonation", "explanationd", "pembahasand"),
        Field.CEFR to listOf("cefrlevel", "cefr", "level", "levelcefr"),
        Field.BAND to listOf("ieltstargetband", "band", "targetband", "ieltsband"),
        Field.MODULE to listOf("moduletype", "module", "type", "mode", "tipe"),
        Field.PREP to listOf("preparationtimesec", "prep", "preparation", "preptime", "persiapan"),
        Field.SPEAK to listOf("speakingdurationsec", "duration", "speakingtime", "speak", "durasi"),
        Field.TOPIC to listOf("topic", "tema", "topik", "domain"),
        Field.TRANSLATION to listOf("translationid", "terjemahan", "translation", "indonesian", "bahasaindonesia"),
    )

    data class MappingResult(val cases: List<PracticeCase>, val warnings: List<String>, val headerDetected: Boolean)

    private fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Returns field -> column index if [row] looks like a header row, else null. */
    fun detectHeader(row: List<String>): Map<Field, Int>? {
        val map = HashMap<Field, Int>()
        for ((col, cell) in row.withIndex()) {
            val k = key(cell)
            if (k.isEmpty()) continue
            val exact = ALIASES.entries.firstOrNull { (_, aliases) -> aliases.any { key(it) == k } }?.key
            // Otherwise the longest alias contained in the header wins, so a combined header such as
            // "Pembahasan_Pilihan_A / Feedback_Fluency_Rubric" maps to H rather than to C ("pilihana").
            val field = exact ?: ALIASES.entries
                .flatMap { (f, aliases) -> aliases.map { f to key(it) } }
                .filter { (_, a) -> a.length >= 6 && k.contains(a) && true }
                .maxByOrNull { (_, a) -> a.length }?.first
            if (field != null && field !in map) map[field] = col
        }
        val firstIsNumber = row.firstOrNull()?.trim()?.toDoubleOrNull() != null
        return if (!firstIsNumber && map.size >= 3 && Field.PROMPT in map) map else null
    }

    private val POSITIONAL: Map<Field, Int> = mapOf(
        Field.NUMBER to 0, Field.PROMPT to 1, Field.C to 2, Field.D to 3, Field.E to 4, Field.F to 5, Field.G to 6,
        Field.H to 7, Field.I to 8, Field.J to 9, Field.K to 10, Field.CEFR to 11, Field.BAND to 12, Field.MODULE to 13,
        Field.PREP to 14, Field.SPEAK to 15, Field.TOPIC to 16, Field.TRANSLATION to 17,
    )

    fun mapSheet(sheetName: String, rows: List<Pair<Int, List<String>>>, cefr: CefrLookup?): MappingResult {
        val warnings = ArrayList<String>()
        val nonEmpty = rows.filter { (_, cells) -> cells.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) return MappingResult(emptyList(), listOf("Sheet '$sheetName' is empty"), false)
        val header = detectHeader(nonEmpty.first().second)
        val columns = header ?: POSITIONAL
        val dataRows = if (header != null) nonEmpty.drop(1) else nonEmpty
        val out = ArrayList<PracticeCase>(dataRows.size)
        for ((ordinal, pair) in dataRows.withIndex()) {
            val (rowIndex, cells) = pair
            fun cell(f: Field): String = columns[f]?.let { cells.getOrNull(it) }?.trim() ?: ""
            val prompt = cell(Field.PROMPT)
            if (prompt.isEmpty()) {
                if (cells.any { it.isNotBlank() }) warnings += "Row ${rowIndex + 1}: empty prompt - skipped"
                continue
            }
            val number = cell(Field.NUMBER).toDoubleOrNull()?.toInt() ?: (ordinal + 1)
            val c = cell(Field.C); val d = cell(Field.D); val e = cell(Field.E); val f = cell(Field.F); val g = cell(Field.G)
            val module = ModuleType.parse(cell(Field.MODULE)) ?: inferModule(sheetName, prompt, c, d, e, f, g)
            val bandRaw = cell(Field.BAND).replace(',', '.').toDoubleOrNull()
            val level = CefrLevel.parse(cell(Field.CEFR))
                ?: bandRaw?.let { CefrLevel.fromBand(it) }
                ?: inferLevel(listOf(prompt, e, g).joinToString(" "), cefr)
            val band = (bandRaw ?: level.midBand).coerceIn(1.0, 9.0).let { Math.round(it * 2) / 2.0 }
            val prep = cell(Field.PREP).toDoubleOrNull()?.toInt() ?: module.defaultPrepSec
            val speak = cell(Field.SPEAK).toDoubleOrNull()?.toInt() ?: module.defaultSpeakSec
            if (module == ModuleType.QUIZ_MCQ && (listOf(c, d, e, f).count { it.isNotBlank() } < 2 || g.isBlank())) {
                warnings += "Row ${rowIndex + 1}: quiz row needs options A-D and Jawaban_Benar"
            }
            out += PracticeCase(
                sheetName = sheetName, number = number, prompt = prompt,
                colC = c, colD = d, colE = e, colF = f, colG = g,
                colH = cell(Field.H), colI = cell(Field.I), colJ = cell(Field.J), colK = cell(Field.K),
                cefr = level, targetBand = band, module = module,
                prepSec = prep.coerceIn(0, 600), speakSec = speak.coerceIn(0, 1800),
                topic = cell(Field.TOPIC).ifBlank { sheetName },
                translationId = cell(Field.TRANSLATION),
            )
        }
        return MappingResult(out, warnings, header != null)
    }

    private val IPA_CHARS = Regex("[ˈˌːəɪʊʌæɑɒɔɜθðʃʒŋ]")

    fun inferModule(sheet: String, prompt: String, c: String, d: String, e: String, f: String, g: String): ModuleType {
        val s = sheet.lowercase()
        val p = prompt.lowercase()
        val options = listOf(c, d, e, f).count { it.isNotBlank() }
        val gTrim = g.trim()
        val answerIsLetter = gTrim.length == 1 && gTrim.uppercase()[0] in "ABCD"
        val answerMatchesOption = options >= 2 && listOf(c, d, e, f).any { it.isNotBlank() && it.trim().equals(gTrim, true) }
        val looksLikeIpa = c.contains('/') || IPA_CHARS.containsMatchIn(c)
        return when {
            !looksLikeIpa && options >= 3 && (answerIsLetter || answerMatchesOption) -> ModuleType.QUIZ_MCQ
            "flash" in s -> ModuleType.FLASHCARD
            "shadow" in s || "cake" in s -> ModuleType.CAKE_SHADOWING
            "elsa" in s || "phoneme" in s || "pronunciation" in s -> ModuleType.ELSA_PHONEME_DRILL
            "role" in s || "hellotalk" in s -> ModuleType.HELLOTALK_ROLEPLAY
            "debate" in s || "tandem" in s -> ModuleType.TANDEM_DEBATE
            "part 3" in s || "part3" in s -> ModuleType.STIMULER_PART3
            "part 2" in s || "part2" in s || "cue" in s -> ModuleType.STIMULER_PART2_CUECARD
            p.startsWith("describe") || "you should say" in p -> ModuleType.STIMULER_PART2_CUECARD
            looksLikeIpa && TextAnalysis.words(g.ifBlank { prompt }).size <= 8 -> ModuleType.ELSA_PHONEME_DRILL
            looksLikeIpa -> ModuleType.CAKE_SHADOWING
            options == 0 && gTrim.isNotEmpty() && d.isBlank() && TextAnalysis.words(prompt).size <= 6 -> ModuleType.FLASHCARD
            Regex("^(why|to what extent|in what ways|how might|should|what are the (advantages|disadvantages|benefits)|do you think (that )?(society|governments|people in general))").containsMatchIn(p) -> ModuleType.STIMULER_PART3
            else -> ModuleType.STIMULER_PART1
        }
    }

    /** Average CEFR rank of known content words -> level (B1 when unknown). */
    fun inferLevel(text: String, cefr: CefrLookup?): CefrLevel {
        if (cefr == null) return CefrLevel.B1
        val ranks = TextAnalysis.words(text).filter { TextAnalysis.isContentWord(it) }.mapNotNull { cefr.levelOf(it)?.rank }
        if (ranks.size < 3) return CefrLevel.B1
        val top = ranks.sortedDescending().take(maxOf(3, ranks.size / 3)).average()
        return when {
            top < 0.6 -> CefrLevel.A1
            top < 1.3 -> CefrLevel.A2
            top < 2.1 -> CefrLevel.B1
            top < 2.9 -> CefrLevel.B2
            top < 3.7 -> CefrLevel.C1
            else -> CefrLevel.C2
        }
    }

    /** Canonical 18-column header used when exporting. */
    val EXPORT_HEADER: List<String> = listOf(
        "Nomor", "Soal_Atau_Prompt_Speaking", "Pilihan_A / Target_IPA_Phonemes", "Pilihan_B / Model_Answer_Band_6_B2",
        "Pilihan_C / Model_Answer_Band_8_C1", "Pilihan_D / Model_Answer_Band_9_C2", "Jawaban_Benar / Primary_Target_Transcript",
        "Pembahasan_Pilihan_A / Feedback_Fluency_Rubric", "Pembahasan_Pilihan_B / Feedback_Lexical_Vocabulary",
        "Pembahasan_Pilihan_C / Feedback_Grammar_Structures", "Pembahasan_Pilihan_D / Feedback_Pronunciation_Intonation",
        "CEFR_Level", "IELTS_Target_Band", "Module_Type", "Preparation_Time_Sec", "Speaking_Duration_Sec", "Topic", "Translation_ID",
    )

    val EXPORT_WIDTHS: List<Int> = listOf(8, 60, 30, 50, 50, 50, 40, 40, 40, 40, 40, 10, 10, 24, 12, 12, 22, 50)
    val EXPORT_NUMERIC: Set<Int> = setOf(0, 12, 14, 15)

    fun toExportRow(c: PracticeCase): List<String> = listOf(
        c.number.toString(), c.prompt, c.colC, c.colD, c.colE, c.colF, c.colG, c.colH, c.colI, c.colJ, c.colK,
        c.cefr.name, if (c.targetBand == Math.rint(c.targetBand)) c.targetBand.toInt().toString() + ".0" else c.targetBand.toString(),
        c.module.name, c.prepSec.toString(), c.speakSec.toString(), c.topic, c.translationId,
    )
}
