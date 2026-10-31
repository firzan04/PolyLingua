package com.polylingua.edge

import com.polylingua.edge.ai.CloudAiCodec
import com.polylingua.edge.ai.readSse
import com.polylingua.edge.data.AssessmentJson
import com.polylingua.edge.domain.model.BodyLanguageScorer
import com.polylingua.edge.domain.model.FaceFrame
import com.polylingua.edge.domain.model.SpeechSegment
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.dsp.SpeechLevelNormalizer
import com.polylingua.edge.media.SessionAudioTimeline
import com.polylingua.edge.media.SubtitleWriter
import com.polylingua.edge.speech.ChatTurn
import com.polylingua.edge.speech.TimedSegment
import com.polylingua.edge.speech.packLines
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class CloudVideoAndAudioTest {

    // ------------------------------------------------------------------ cloud AI codecs

    @Test
    fun normalizeMergesRolesAndStartsWithUser() {
        val (system, turns) = CloudAiCodec.normalize(
            listOf(
                ChatTurn.system("You are Liam."),
                ChatTurn.assistant("Hi! What do you do?"),
                ChatTurn.user("I'm a student."),
                ChatTurn.user("I study engineering."),
            ),
        )
        assertEquals("You are Liam.", system)
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.role })
        assertTrue(turns.last().content.contains("engineering"))
    }

    @Test
    fun geminiRequestAndResponse() {
        val req = CloudAiCodec.geminiRequest(listOf(ChatTurn.system("Be brief."), ChatTurn.user("Hello")), 100, 0.7f, json = true)
        assertEquals("Be brief.", req.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("user", req.getJSONArray("contents").getJSONObject(0).getString("role"))
        val gen = req.getJSONObject("generationConfig")
        assertTrue(gen.getInt("maxOutputTokens") >= 1024)
        assertEquals("application/json", gen.getString("responseMimeType"))

        val chunk = """{"candidates":[{"content":{"parts":[{"text":"plan...","thought":true},{"text":"Hello"},{"text":" there"}]}}]}"""
        assertEquals("Hello there", CloudAiCodec.geminiText(chunk))
        assertNull(CloudAiCodec.geminiBlockReason(chunk))
        val err = CloudAiCodec.geminiError("""{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}""", 429)
        assertTrue(err.message!!.contains("rate limit"))
        assertEquals(429, err.httpStatus)
    }

    @Test
    fun claudeRequestUsesEffortOnlyForSupportedModels() {
        val turns = listOf(ChatTurn.system("Coach."), ChatTurn.user("Correct: he go school"))
        val r = CloudAiCodec.claudeRequest("claude-haiku-5-5", turns, 200, stream = true, effort = "low")
        assertEquals("claude-haiku-5-5", r.getString("model"))
        assertEquals("Coach.", r.getString("system"))
        assertTrue(r.getInt("max_tokens") >= 1024)
        assertEquals("low", r.getJSONObject("output_config").getString("effort"))
        assertFalse(r.has("temperature"))
        val old = CloudAiCodec.claudeRequest("claude-3-haiku-20240307", turns, 200, stream = false, effort = "low")
        assertFalse(old.has("output_config"))
    }

    @Test
    fun claudeAndOpenAiStreamEvents() {
        val text = CloudAiCodec.claudeEvent("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""")
        assertEquals(CloudAiCodec.ClaudeEvent.Text("Hi"), text)
        assertEquals(CloudAiCodec.ClaudeEvent.Ignore, CloudAiCodec.claudeEvent("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}"""))
        assertEquals(CloudAiCodec.ClaudeEvent.Stop, CloudAiCodec.claudeEvent("""{"type":"message_stop"}"""))
        assertTrue(CloudAiCodec.claudeEvent("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""") is CloudAiCodec.ClaudeEvent.Error)
        assertEquals("Hello", CloudAiCodec.claudeText("""{"content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"Hello"}]}"""))
        assertEquals("Hi", CloudAiCodec.openAiDelta("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertNull(CloudAiCodec.openAiDelta("[DONE]"))
        assertEquals(1, CloudAiCodec.extractJsonObject("```json\n{\"a\": 1}\n```")!!.getInt("a"))
    }

    @Test
    fun sseReaderSplitsEventsAndJoinsMultilineData() {
        val src = Buffer().writeUtf8(": ping\nevent: message_start\ndata: {\"a\":1}\n\nevent: delta\ndata: line1\ndata: line2\n\ndata: tail")
        val got = ArrayList<Pair<String?, String>>()
        readSse(src) { ev, data -> got += ev to data; true }
        assertEquals(3, got.size)
        assertEquals("message_start" to "{\"a\":1}", got[0])
        assertEquals("delta" to "line1\nline2", got[1])
        assertEquals(null to "tail", got[2])
    }

    // ---------------------------------------------------------------- audio front-end

    private fun tone(seconds: Double, amp: Double, freq: Double = 200.0, sr: Int = 16000) =
        ShortArray((seconds * sr).toInt()) { i -> (amp * 32767 * sin(2 * PI * freq * i / sr)).toInt().toShort() }

    private fun rms(x: ShortArray, from: Int = 0, to: Int = x.size): Double {
        var acc = 0.0
        for (i in from until to) acc += (x[i] / 32768.0) * (x[i] / 32768.0)
        return sqrt(acc / (to - from))
    }

    private fun process(norm: SpeechLevelNormalizer, x: ShortArray): ShortArray {
        val out = x.copyOf()
        var pos = 0
        while (pos < out.size) {
            val n = minOf(320, out.size - pos)
            val chunk = out.copyOfRange(pos, pos + n)
            norm.process(chunk, n)
            System.arraycopy(chunk, 0, out, pos, n)
            pos += n
        }
        return out
    }

    @Test
    fun normalizerLiftsQuietSpeechButNotSilence() {
        val rnd = Random(7)
        val noise = ShortArray(16000) { (rnd.nextDouble(-1.0, 1.0) * 0.0005 * 32767).toInt().toShort() }
        val quiet = noise + tone(2.0, 0.01)
        val out = process(SpeechLevelNormalizer(), quiet)
        assertTrue("quiet speech should be boosted, rms=${rms(out, out.size - 8000)}", rms(out, out.size - 8000) > 0.05)

        val silenceOut = process(SpeechLevelNormalizer(), noise + noise)
        assertTrue("silence must not be boosted", rms(silenceOut) < 0.002)

        val loudOut = process(SpeechLevelNormalizer(), tone(1.0, 0.95))
        assertTrue("no clipping", loudOut.all { abs(it.toInt()) < 32700 })
    }

    @Test
    fun streamingResamplerHasNoDrift() {
        for (rate in listOf(48000, 44100, 22050, 8000)) {
            val r = PcmUtils.StreamingResampler(rate, 16000)
            var n = 0
            repeat(rate * 10) { r.push(0.1f) { n++ } }
            assertTrue("rate $rate produced $n samples", abs(n - 160000) <= 1)
        }
    }

    @Test
    fun speechEnvelopeTrimsSilence() {
        val sr = 16000
        val clip = FloatArray(sr * 2)
        for (i in sr / 2 until sr * 3 / 2) clip[i] = (0.5 * sin(2 * PI * 220.0 * i / sr)).toFloat()
        val env = PcmUtils.speechEnvelope(clip, sr, bars = 40)
        assertEquals(40, env.size)
        assertTrue(env.first() > 0.3f && env.last() > 0.3f)
        assertEquals(1f, env.maxOrNull()!!, 1e-4f)
    }

    // ------------------------------------------------------------------ video / Cake

    @Test
    fun timelinePlacesClipsAtTheirWallClockOffsets() {
        val t = SessionAudioTimeline(16000)
        val origin = 5_000_000_000L
        t.add(origin + 500_000_000L, ShortArray(100) { 1000 })
        t.add(origin - 1_000_000_000L, ShortArray(10) { 7 }) // before the video: dropped
        val pcm = t.render(origin, 1.0)
        assertEquals(16000, pcm.size)
        assertEquals(0, pcm[7999].toInt())
        assertEquals(1000, pcm[8000].toInt())
        assertEquals(1000, pcm[8099].toInt())
        assertEquals(0, pcm[8100].toInt())
    }

    @Test
    fun srtWriterFormatsTimestamps() {
        val srt = SubtitleWriter.toSrt(listOf(TimedSegment(1.5, 3.25, "Hello there."), TimedSegment(3661.0, 3662.04, "Bye.")))
        assertEquals("1\n00:00:01,500 --> 00:00:03,250\nHello there.\n\n2\n01:01:01,000 --> 01:01:02,040\nBye.\n\n", srt)
    }

    @Test
    fun subtitleLinesMergeShortPausesAndSplitLongSpeech() {
        val merged = packLines(listOf(SpeechSegment(0.0, 1.0), SpeechSegment(1.3, 2.0), SpeechSegment(5.0, 6.0)), maxSec = 12.0, gapSec = 0.6)
        assertEquals(listOf(SpeechSegment(0.0, 2.0), SpeechSegment(5.0, 6.0)), merged)
        val split = packLines(listOf(SpeechSegment(0.0, 30.0)), maxSec = 12.0, gapSec = 0.6)
        assertEquals(3, split.size)
        assertTrue(split.all { it.endSec - it.startSec <= 15.0 })
        assertEquals(30.0, split.last().endSec, 1e-9)
    }

    @Test
    fun bodyLanguageScoring() {
        val engaged = (0 until 20).map { FaceFrame(it.toDouble(), true, yawDeg = 2.0, pitchDeg = -3.0, smileProb = 0.8, leftEyeOpenProb = 0.9, rightEyeOpenProb = 0.9, faceAreaRatio = 0.15) }
        val good = BodyLanguageScorer.score(engaged, 20.0)
        assertEquals(100.0, good.eyeContactPct, 0.01)
        assertTrue(good.presenceScore > 80)
        assertTrue(good.strengths.isNotEmpty())
        assertEquals("Well framed", good.framing)

        val wandering = (0 until 20).map { i -> FaceFrame(i.toDouble(), true, yawDeg = if (i % 2 == 0) 0.0 else 40.0, smileProb = 0.1, faceAreaRatio = 0.15) }
        val poor = BodyLanguageScorer.score(wandering, 20.0)
        assertEquals(50.0, poor.eyeContactPct, 0.01)
        assertTrue(poor.tips.any { it.contains("camera lens") })
        assertTrue(poor.lookAwayEvents >= 5)

        val none = BodyLanguageScorer.score(List(5) { FaceFrame(it.toDouble(), false) }, 5.0)
        assertEquals(0.0, none.facePresentPct, 0.0)
        assertTrue(none.tips.first().contains("not detected"))

        val json = AssessmentJson.bodyToJson(poor)
        assertEquals(poor, AssessmentJson.bodyFromJson(json))
        assertNotNull(json.getJSONArray("eyeContactTimeline"))
    }
}
