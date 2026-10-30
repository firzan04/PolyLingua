package com.polylingua.edge

import com.polylingua.edge.data.AssessmentJson
import com.polylingua.edge.data.Procedural1000CaseSeeder
import com.polylingua.edge.data.xlsx.CaseSchemaMapper
import com.polylingua.edge.data.xlsx.XlsxWorkbookReader
import com.polylingua.edge.data.xlsx.XlsxWorkbookWriter
import com.polylingua.edge.domain.GrammarPatternDetector
import com.polylingua.edge.domain.PedagogicalRubricEngine
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.PracticeCase
import com.polylingua.edge.domain.model.SpeechSample
import com.polylingua.edge.domain.model.SpeechSegment
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.domain.model.WordTiming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class CurriculumAndStorageTest {
    companion object {
        private lateinit var cases: List<PracticeCase>

        @BeforeClass
        @JvmStatic
        fun generate() {
            cases = Procedural1000CaseSeeder(TestAssets.cmu).generate()
        }
    }

    @Test
    fun builtInCurriculumHas1020BalancedCases() {
        assertEquals(1020, cases.size)
        assertEquals(1020, cases.map { it.prompt }.toSet().size)
        assertTrue(cases.groupBy { it.cefr }.values.all { it.size == 170 })
        assertTrue(cases.groupBy { it.module }.values.all { list -> list.map { it.cefr }.toSet().size == 6 })
        assertEquals(34, cases.groupBy { it.topic }.size)
        assertEquals(4.0, cases.minOf { it.targetBand }, 1e-9)
        assertEquals(9.0, cases.maxOf { it.targetBand }, 1e-9)
        val quiz = cases.filter { it.module == ModuleType.QUIZ_MCQ }
        assertTrue(quiz.all { q -> q.options.all { it.isNotBlank() } && q.colG in setOf("A", "B", "C", "D") && q.options.toSet().size == 4 })
        assertTrue(quiz.all { q -> (0..3).all { q.explanationFor(it).isNotBlank() } })
        val parts = setOf(ModuleType.STIMULER_PART1, ModuleType.STIMULER_PART2_CUECARD, ModuleType.STIMULER_PART3)
        assertTrue(cases.filter { it.module in parts }.all { it.colD.isNotBlank() && it.colE.isNotBlank() && it.colF.isNotBlank() })
    }

    @Test
    fun modelAnswersPassTheGrammarDetector() {
        val issues = cases.filter { it.module.speaking && it.module != ModuleType.ELSA_PHONEME_DRILL && it.module != ModuleType.CAKE_SHADOWING }
            .flatMap { c -> GrammarPatternDetector.detectIssues(c.colD + " " + c.colE + " " + c.colF) }
        assertTrue("model-answer rule hits: ${issues.size}", issues.size <= 3)
    }

    @Test
    fun xlsxExportImportRoundTrip() {
        val f = File.createTempFile("polylingua", ".xlsx")
        try {
            XlsxWorkbookWriter(f.outputStream()).use { w ->
                for ((m, list) in cases.groupBy { it.module }) {
                    w.addSheet(Procedural1000CaseSeeder.sheetNameFor(m), CaseSchemaMapper.EXPORT_HEADER,
                        list.asSequence().map { CaseSchemaMapper.toExportRow(it) }, CaseSchemaMapper.EXPORT_WIDTHS, CaseSchemaMapper.EXPORT_NUMERIC)
                }
            }
            val back = ArrayList<PracticeCase>()
            XlsxWorkbookReader.open(f).use { r ->
                assertEquals(ModuleType.entries.size, r.sheets.size)
                for (s in r.sheets) {
                    val rows = ArrayList<Pair<Int, List<String>>>()
                    r.readSheet(s) { i, cells -> rows += i to cells }
                    val res = CaseSchemaMapper.mapSheet(s.name, rows, TestAssets.cefr)
                    assertTrue(res.headerDetected)
                    back += res.cases
                }
            }
            assertEquals(1020, back.size)
            val orig = cases.associateBy { it.prompt }
            val mismatches = back.count { b -> orig[b.prompt]?.copy(id = 0, sheetName = "") != b.copy(id = 0, sheetName = "") }
            assertEquals(0, mismatches)
        } finally {
            f.delete()
        }
    }

    @Test
    fun schemaInferenceHandlesPositionalAndHeaderSheets() {
        val quizRows = listOf(0 to listOf("1", "What is the capital of France?", "Paris", "Rome", "Berlin", "Madrid", "A", "Correct!", "No", "No", "No"))
        val q = CaseSchemaMapper.mapSheet("Quiz Geografi", quizRows, TestAssets.cefr)
        assertEquals(1, q.cases.size)
        assertEquals(ModuleType.QUIZ_MCQ, q.cases[0].module)
        assertEquals(0, q.cases[0].correctOptionIndex())
        assertFalse(q.headerDetected)
        val rows = listOf(
            0 to listOf("No", "Soal", "Pilihan_A", "Pilihan_B", "Pilihan_C", "Pilihan_D", "Jawaban_Benar"),
            1 to listOf("1", "Describe a person who inspired you. You should say who they are", "", "", "", "", ""),
            2 to listOf("2", "Think three times", "/θɪŋk θriː/", "", "", "", "Think three times"),
        )
        val m = CaseSchemaMapper.mapSheet("Mixed", rows, TestAssets.cefr)
        assertTrue(m.headerDetected)
        assertEquals(ModuleType.STIMULER_PART2_CUECARD, m.cases[0].module)
        assertEquals(ModuleType.ELSA_PHONEME_DRILL, m.cases[1].module)
    }

    @Test
    fun sessionJsonRoundTrip() {
        val text = "Honestly, I believe public transport is essential for big cities. For instance, Jakarta's MRT has reduced congestion considerably. " +
            "However, many people still prefer motorbikes because they are cheaper and more flexible."
        val toks = TextAnalysis.tokenize(text)
        var t = 0.3
        val words = toks.map { tk -> val s = t; t += 0.32; WordTiming(tk.text, s, s + 0.26) }
        val pitch = (0 until 300).map { PitchFrame(it * 0.016, (140 + it % 20).toFloat(), 0.9f, -22f) }
        val sample = SpeechSample(text, words, listOf(SpeechSegment(0.3, t)), t + 0.4, pitch, null, acousticFilledPauses = 2)
        val ctx = TaskContext(ModuleType.STIMULER_PART3, "Should cities invest more in public transport?", "Transport", 7.0, 60)
        val a = PedagogicalRubricEngine(TestAssets.cefr).assess(sample, ctx)
        val json = AssessmentJson.encode("IELTS_Speaking_Tech_test 7", 1_700_000_000_000L, ctx, a, sample, "zipformer", "x.wav", "x.csv")
        val back = AssessmentJson.decode(JSONObject(json.toString(2)))
        assertEquals("IELTS_Speaking_Tech_test 7", back.stem)
        assertEquals(a.overallBand, back.assessment.overallBand, 1e-9)
        assertEquals(a.cefr, back.assessment.cefr)
        assertEquals(a.fc.band, back.assessment.fc.band, 1e-9)
        assertEquals(a.lexical.upgrades.size, back.assessment.lexical.upgrades.size)
        assertEquals(a.grammar.issues.size, back.assessment.grammar.issues.size)
        assertEquals(words.size, back.sample.words.size)
        assertEquals(pitch.size, back.sample.pitch.size)
        assertEquals(2, back.sample.acousticFilledPauses)
        assertEquals(ctx.module, back.context.module)
        assertTrue(AssessmentJson.csv("s", ctx, a, "zipformer").lines().size > 20)
        assertEquals(CefrLevel.fromBand(back.assessment.overallBand), back.assessment.cefr)
    }
}
