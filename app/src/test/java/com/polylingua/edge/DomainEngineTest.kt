package com.polylingua.edge

import com.polylingua.edge.domain.DiscourseMarkerLexicon
import com.polylingua.edge.domain.GrammarPatternDetector
import com.polylingua.edge.domain.LexicalUpgradeDictionary
import com.polylingua.edge.domain.PedagogicalRubricEngine
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.BandMath
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.SpeechSample
import com.polylingua.edge.domain.model.SpeechSegment
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.domain.model.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class DomainEngineTest {

    @Test
    fun lexiconsAreComplete() {
        assertEquals(CefrLevel.A1, TestAssets.cefr.levelOf("cat"))
        assertNotNull(TestAssets.cefr.levelOf("paramount"))
        assertNotNull("lemma lookup", TestAssets.cefr.levelOf("studies"))
        assertTrue(TestAssets.cmu.wordCount > 120_000)
        assertTrue(LexicalUpgradeDictionary.size >= 600)
        assertTrue(DiscourseMarkerLexicon.size >= 180)
        assertTrue(GrammarPatternDetector.STRUCTURES.size >= 25)
    }

    @Test
    fun phonemeInventoryIs44() {
        val p = PhoneticArticulationDatabase.PHONEMES
        assertEquals(44, p.size)
        assertEquals(12, p.count { it.kind == PhoneticArticulationDatabase.Kind.MONOPHTHONG })
        assertEquals(8, p.count { it.kind == PhoneticArticulationDatabase.Kind.DIPHTHONG })
        assertEquals(24, p.count { it.kind == PhoneticArticulationDatabase.Kind.CONSONANT })
        assertEquals(1, PhoneticArticulationDatabase.primaryStressSyllable(TestAssets.cmu.pronounce("photography")))
        assertTrue(PhoneticArticulationDatabase.toIpa(TestAssets.cmu.pronounce("think")).contains("θ"))
    }

    @Test
    fun ieltsRoundingAndCefrMapping() {
        assertEquals(6.0, BandMath.overall(6.0, 6.0, 6.5, 6.0), 1e-9)   // 6.125 -> 6.0
        assertEquals(6.5, BandMath.overall(6.0, 6.5, 6.5, 6.0), 1e-9)   // 6.25  -> 6.5
        assertEquals(6.5, BandMath.overall(6.5, 6.5, 6.5, 7.0), 1e-9)   // 6.625 -> 6.5
        assertEquals(7.0, BandMath.overall(6.5, 7.0, 7.0, 6.5), 1e-9)   // 6.75  -> 7.0
        assertEquals(CefrLevel.A1, CefrLevel.fromBand(2.5))
        assertEquals(CefrLevel.A2, CefrLevel.fromBand(3.0))
        assertEquals(CefrLevel.B1, CefrLevel.fromBand(5.0))
        assertEquals(CefrLevel.B2, CefrLevel.fromBand(5.5))
        assertEquals(CefrLevel.C1, CefrLevel.fromBand(8.0))
        assertEquals(CefrLevel.C2, CefrLevel.fromBand(8.5))
    }

    @Test
    fun grammarDetectorFindsTypicalL1Errors() {
        val bad = "Yesterday I go to the market. He don't like it. There have many people in my city. I am agree with you. " +
            "I very like football. Although it was raining, but we went out. People is friendly. I enjoy to swim. " +
            "We discuss about the problem. My friend make me to laugh. You can to come. It is more better. I have many informations."
        assertTrue(GrammarPatternDetector.detectIssues(bad).size >= 12)
        val good = "If I had studied harder, I would have passed the exam. Never have I seen such a beautiful place, which is why I keep going back. " +
            "What I really love is the food. Having finished my degree, I moved to Jakarta in order to find a job. " +
            "The city has been transformed dramatically. It is essential that he be on time. The more you practise, the better you become."
        assertTrue(GrammarPatternDetector.detectStructures(good).size >= 8)
        assertTrue(GrammarPatternDetector.detectIssues(good).isEmpty())
    }

    @Test
    fun lexicalUpgradesAreTopicAware() {
        val ups = LexicalUpgradeDictionary.findUpgrades("I think technology is very important and it has a big problem, but many people use their phone a lot.", "technology")
        assertTrue(ups.any { it.original == "very important" })
        assertTrue(ups.any { it.original == "phone" && it.topic == "technology" })
        assertTrue(ups.all { it.replacements.isNotEmpty() })
    }

    private fun syntheticSample(text: String, fillers: Int = 0): SpeechSample {
        val toks = TextAnalysis.tokenize(text)
        var t = 0.4
        val words = toks.map { tk ->
            val s = t
            val dur = 0.12 + tk.text.length * 0.035
            t += dur + if (text.substring(tk.end, minOf(text.length, tk.end + 1)) == ".") 0.5 else 0.06
            WordTiming(tk.text, s, s + dur)
        }
        val pitch = (0 until (t * 62).toInt()).map { k -> val tt = k / 62.0; PitchFrame(tt, (140 + 25 * sin(tt * 2.2)).toFloat(), 0.9f, -20f) }
        return SpeechSample(text, words, listOf(SpeechSegment(0.4, t)), t + 0.5, pitch, null, acousticFilledPauses = fillers)
    }

    @Test
    fun rubricProducesPlausibleBandsAndModelAnswers() {
        val text = "Well, I think technology is very important in our lives. For example, I use my phone every day to study and to talk with my friends. " +
            "However, some people spend too much time on social media, which can be harmful. In my opinion, the government should teach children how to use technology responsibly. " +
            "Moreover, if we used technology wisely, we would have more time for our families. That said, I believe the benefits outweigh the drawbacks."
        val engine = PedagogicalRubricEngine(TestAssets.cefr)
        val ctx = TaskContext(ModuleType.STIMULER_PART1, "Do you think technology is important?", "technology")
        val res = engine.assess(syntheticSample(text), ctx)
        assertTrue("overall ${res.overallBand}", res.overallBand in 4.0..8.5)
        assertTrue(res.criteria.all { it.band in 0.0..9.0 && it.evidence.isNotEmpty() })
        assertEquals(CefrLevel.fromBand(res.overallBand), res.cefr)
        assertTrue(res.naturalPolish.isNotBlank() && res.band9Version.length > res.naturalPolish.length / 2)
        // Acoustic hesitations must lower fluency, never raise it.
        val hesitant = engine.assess(syntheticSample(text, fillers = 12), ctx)
        assertTrue(hesitant.fluency.filledPauseCount >= 12)
        assertTrue(hesitant.fc.raw <= res.fc.raw)
    }

    @Test
    fun tinySamplesAreCapped() {
        val engine = PedagogicalRubricEngine(TestAssets.cefr)
        val res = engine.assess(syntheticSample("Yes, I like it."), TaskContext(ModuleType.STIMULER_PART1, "Do you like music?"))
        assertTrue(res.criteria.all { it.band <= 2.5 })
        assertTrue(res.warnings.isNotEmpty())
    }
}
