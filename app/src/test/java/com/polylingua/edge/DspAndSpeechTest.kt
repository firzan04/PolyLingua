package com.polylingua.edge

import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.WordTiming
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.dsp.PhonemeGopAnalyzer
import com.polylingua.edge.dsp.PosteriorMatrix
import com.polylingua.edge.dsp.ShadowingScorer
import com.polylingua.edge.dsp.TarsosPitchAndProsodyEngine
import com.polylingua.edge.dsp.TarsosWsolaTimeStretcher
import com.polylingua.edge.media.SubtitleParser
import com.polylingua.edge.network.DiffKind
import com.polylingua.edge.network.WordDiff
import com.polylingua.edge.speech.AssessmentPipeline
import com.polylingua.edge.speech.EnergyVad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

class DspAndSpeechTest {
    private val sr = 16000

    private fun harmonic(seconds: Double, f0: (Double) -> Double): FloatArray {
        val out = FloatArray((sr * seconds).toInt())
        var ph = 0.0
        for (i in out.indices) {
            ph += 2 * PI * f0(i.toDouble() / sr) / sr
            var v = 0.0
            for (h in 1..5) v += sin(h * ph) / h
            out[i] = (0.3 * v).toFloat()
        }
        return out
    }

    @Test
    fun pitchTrackersFindF0() {
        val sine = FloatArray(sr) { (0.5 * sin(2 * PI * 150.0 * it / sr)).toFloat() }
        for (alg in TarsosPitchAndProsodyEngine.Algorithm.entries) {
            val fr = TarsosPitchAndProsodyEngine(algorithm = alg).analyze(sine)
            val voiced = fr.filter { it.voiced }
            val med = voiced.map { it.f0Hz }.sorted()[voiced.size / 2]
            assertTrue("$alg median $med", abs(med - 150f) < 3f && voiced.size > fr.size * 0.8)
        }
        val vibrato = TarsosPitchAndProsodyEngine().analyze(harmonic(2.0) { t -> 120 + 10 * sin(2 * PI * 3 * t) }).filter { it.voiced }
        assertTrue(abs(vibrato.map { it.f0Hz }.average() - 120) < 6)
    }

    @Test
    fun intonationDtwDiscriminatesContours() {
        val rising = (0 until 120).map { PitchFrame(it * 0.016, (120 + it * 0.8).toFloat(), 0.9f, -20f) }
        val risingHigh = (0 until 140).map { PitchFrame(it * 0.016, (220 + it * 1.2).toFloat(), 0.9f, -20f) }
        val falling = (0 until 120).map { PitchFrame(it * 0.016, (220 - it * 0.8).toFloat(), 0.9f, -20f) }
        val same = TarsosPitchAndProsodyEngine.compareIntonation(risingHigh, rising)
        val opposite = TarsosPitchAndProsodyEngine.compareIntonation(falling, rising)
        assertTrue(same.similarityPercent > 70)
        assertTrue(opposite.similarityPercent < same.similarityPercent - 25)
    }

    @Test
    fun wsolaChangesTempoNotPitch() {
        val harm = harmonic(2.0) { 120.0 }
        val slow = TarsosWsolaTimeStretcher.stretch(harm, sr, 0.5)
        val fast = TarsosWsolaTimeStretcher.stretch(harm, sr, 1.5)
        assertTrue(abs(slow.size - harm.size * 2) < sr / 4)
        assertTrue(abs(fast.size - harm.size / 1.5) < sr / 4)
        val f0 = TarsosPitchAndProsodyEngine().analyze(slow).filter { it.voiced }.map { it.f0Hz }.average()
        assertTrue("pitch after stretch $f0", abs(f0 - 120) < 8)
        for (t in listOf(0.60, 0.75, 0.85, 1.00, 1.25)) assertEquals(t, TarsosWsolaTimeStretcher.quantize(t), 1e-9)
    }

    @Test
    fun wavRoundTrip() {
        val sine = FloatArray(sr) { (0.5 * sin(2 * PI * 440.0 * it / sr)).toFloat() }
        val back = PcmUtils.readWav(PcmUtils.toByteArrayWav(PcmUtils.floatsToShorts(sine), sr).inputStream())
        assertEquals(sr, back.sampleRate)
        assertEquals(sine.size, back.samples.size)
    }

    @Test
    fun gopFlagsSubstitutedPhone() {
        val labels = listOf("[SIL]", "NG", "F", "M", "AE", "R", "UW", "N", "IY", "AW", "V", "UH", "OW", "AA", "ER", "HH", "Z", "K", "CH", "W",
            "EY", "ZH", "T", "EH", "Y", "AH", "B", "P", "TH", "DH", "AO", "G", "L", "JH", "OY", "SH", "D", "AY", "S", "IH", "[UNK]", "[PAD]")
        val idx = labels.withIndex().associate { it.value to it.index }
        val seq = listOf("[SIL]", "TH", "IH", "NG", "K", "[SIL]", "S", "R", "IY", "[SIL]")   // "think three" with /s/ for /θ/
        val per = 8
        val t = seq.size * per
        val data = FloatArray(t * labels.size)
        for (f in 0 until t) {
            val target = idx.getValue(seq[f / per])
            for (c in labels.indices) data[f * labels.size + c] = ln(if (c == target) 0.86 else 0.14 / (labels.size - 1)).toFloat()
        }
        val res = PhonemeGopAnalyzer(TestAssets.cmu, null).analyzeWithPosteriors(PosteriorMatrix(t, labels.size, data), 0.01, listOf("think", "three"), emptyList(), labels)
        assertNotNull(res)
        assertTrue(res!!.words[0].phonemes.all { it.score >= 80 })
        val th = res.words[1].phonemes.first()
        assertTrue(th.score < 50)
        assertEquals("S", th.detectedArpabet)
        assertTrue(PhoneticArticulationDatabase.remediationHeadline("TH", "S").contains("instead of"))
    }

    @Test
    fun shadowingWeightsAndRhythm() {
        val r = ShadowingScorer.score(100.0, 0.0, 0.0)
        assertEquals(40.0, r.mastery, 1e-6)
        assertEquals(35.0, ShadowingScorer.score(0.0, 100.0, 0.0).mastery, 1e-6)
        assertEquals(25.0, ShadowingScorer.score(0.0, 0.0, 100.0).mastery, 1e-6)
        assertTrue(ShadowingScorer.rhythmSimilarity(listOf(0.0, 0.3, 0.55, 0.9, 1.2), listOf(0.0, 0.6, 1.1, 1.8, 2.4)) > 85)
    }

    @Test
    fun wordEndsAreRefinedFromEnergy() {
        // 0.0-0.5 s loud, 0.5-1.5 s silence, next word at 1.5 s.
        val pitch = (0 until 125).map { k ->
            val t = k * 0.016
            PitchFrame(t, if (t < 0.5 || t >= 1.5) 150f else 0f, 0.9f, if (t < 0.5 || t >= 1.5) -18f else -62f)
        }
        val words = listOf(WordTiming("hello", 0.05, 0.05), WordTiming("there", 1.5, 1.5))
        val refined = AssessmentPipeline.refineWordEnds(words, pitch)
        assertTrue("end ${refined[0].endSec}", refined[0].endSec in 0.45..0.7)
        assertTrue(refined[1].startSec - refined[0].endSec > 0.6)
    }

    @Test
    fun acousticFillerIsCountedOnlyWhenUncovered() {
        // A flat 300 ms voiced hum between two words.
        val pitch = (0 until 150).map { k ->
            val t = k * 0.016
            val hum = t in 1.0..1.32
            val speech = t < 0.5 || t > 1.8
            PitchFrame(t, if (hum) 110f else if (speech) (130 + 30 * sin(t * 9)).toFloat() else 0f, 0.9f, if (hum || speech) -20f else -60f)
        }
        val words = listOf(WordTiming("well", 0.0, 0.5), WordTiming("maybe", 1.8, 2.3))
        assertEquals(1, AssessmentPipeline.countAcousticFillers(pitch, words))
        assertEquals(0, AssessmentPipeline.countAcousticFillers(pitch, words + WordTiming("um", 1.0, 1.32)))
    }

    @Test
    fun energyVadSegmentsSpeech() {
        val vad = EnergyVad(sr)
        val chunk = 320
        val total = sr * 2
        var i = 0
        while (i < total) {
            val buf = ShortArray(chunk) { k ->
                val t = (i + k).toDouble() / sr
                if (t in 0.5..1.2) (8000 * sin(2 * PI * 200 * t)).toInt().toShort() else (20 * sin(2 * PI * 50 * t)).toInt().toShort()
            }
            vad.accept(buf, chunk)
            i += chunk
        }
        val segs = vad.segments()
        assertEquals(1, segs.size)
        assertTrue(abs(segs[0].startSec - 0.5) < 0.05 && abs(segs[0].endSec - 1.2) < 0.08)
    }

    @Test
    fun subtitlesParseSrtAndVtt() {
        val srt = "﻿1\r\n00:00:01,600 --> 00:00:04,200\r\n<i>Hello</i> there,\r\nfriend!\r\n\r\n2\r\n00:00:05,000 --> 00:00:06,500\r\nSecond line\r\n"
        val a = SubtitleParser.parse(srt)
        assertEquals(2, a.size)
        assertEquals(1600L, a[0].startMs)
        assertEquals("Hello there, friend!", a[0].text)
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.500 align:start\nShort cue\n\n01:00:00.000 --> 01:00:01.000\nHour cue\n"
        val b = SubtitleParser.parse(vtt)
        assertEquals(2, b.size)
        assertEquals(2500L, b[0].endMs)
        assertEquals(3_600_000L, b[1].startMs)
        assertEquals("Second line", SubtitleParser.bestOverlap(a, 4_900, 6_000)?.text)
    }

    @Test
    fun wordDiffMarksCorrections() {
        val d = WordDiff.diff("He don't like it", "He doesn't like it.")
        assertTrue(d.any { it.kind == DiffKind.DELETE && it.text == "don't" })
        assertTrue(d.any { it.kind == DiffKind.INSERT && it.text == "doesn't" })
        assertTrue(d.count { it.kind == DiffKind.EQUAL } >= 3)
    }
}
