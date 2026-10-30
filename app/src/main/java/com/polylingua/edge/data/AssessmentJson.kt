package com.polylingua.edge.data

import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.CriterionScore
import com.polylingua.edge.domain.model.DetectedStructure
import com.polylingua.edge.domain.model.FluencyMetrics
import com.polylingua.edge.domain.model.GrammarIssue
import com.polylingua.edge.domain.model.GrammarMetrics
import com.polylingua.edge.domain.model.IeltsCriterion
import com.polylingua.edge.domain.model.LexicalMetrics
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PhonemeScore
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.PronunciationMetrics
import com.polylingua.edge.domain.model.PronunciationResult
import com.polylingua.edge.domain.model.SpeakingAssessment
import com.polylingua.edge.domain.model.SpeechSample
import com.polylingua.edge.domain.model.SpeechSegment
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.domain.model.VocabularyUpgrade
import com.polylingua.edge.domain.model.WordPronunciation
import com.polylingua.edge.domain.model.WordTiming
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** A saved session, as reloaded from "IELTS_Speaking_Tech_test N.json". */
data class StoredSession(
    val stem: String,
    val createdAt: Long,
    val context: TaskContext,
    val assessment: SpeakingAssessment,
    val sample: SpeechSample,
    val transcriptEngine: String,
    val wavFile: String,
)

/** Lossless-enough JSON codec for assessments (schema "polylingua.speaking.v1"). */
object AssessmentJson {
    const val SCHEMA = "polylingua.speaking.v1"

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun strings(list: List<String>) = JSONArray().apply { list.forEach { put(it) } }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
    private inline fun <T> JSONArray?.objects(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }
    private fun r3(x: Double) = if (x.isNaN() || x.isInfinite()) 0.0 else (x * 1000).roundToInt() / 1000.0

    fun encode(
        stem: String,
        createdAt: Long,
        context: TaskContext,
        a: SpeakingAssessment,
        sample: SpeechSample,
        engine: String,
        wavFile: String,
        csvFile: String,
    ): JSONObject {
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("stem", stem)
        o.put("createdAt", iso(createdAt))
        o.put("createdAtMs", createdAt)
        o.put("app", "PolyLingua Edge")
        o.put("task", JSONObject().apply {
            put("module", context.module.name)
            put("moduleLabel", context.module.label)
            put("prompt", context.prompt)
            put("topic", context.topic)
            put("targetBand", context.targetBand)
            put("allocatedSec", context.allocatedSec)
        })
        o.put("transcript", JSONObject().apply {
            put("text", sample.transcript)
            put("engine", engine)
            put("durationSec", r3(sample.totalDurationSec))
            put("referenceText", sample.referenceText ?: JSONObject.NULL)
            put("acousticFilledPauses", sample.acousticFilledPauses)
            put("words", JSONArray().apply {
                sample.words.forEach { put(JSONObject().put("w", it.word).put("s", r3(it.startSec)).put("e", r3(it.endSec))) }
            })
            put("segments", JSONArray().apply {
                sample.speechSegments.forEach { put(JSONArray().put(r3(it.startSec)).put(r3(it.endSec))) }
            })
        })
        o.put("scores", JSONObject().apply {
            put("overall", a.overallBand)
            put("cefr", a.cefr.name)
            a.criteria.forEach { c -> put(c.criterion.code, criterion(c)) }
        })
        o.put("cefrRationale", strings(a.cefrRationale))
        o.put("nextLevelChecklist", strings(a.nextLevelChecklist))
        o.put("fluency", JSONObject().apply {
            val f = a.fluency
            put("wordCount", f.wordCount); put("wpm", r3(f.wpm)); put("phonationTimeRatio", r3(f.phonationTimeRatio))
            put("silentPauseCount", f.silentPauseCount); put("silentPausesPerMin", r3(f.silentPausesPerMin))
            put("filledPauseCount", f.filledPauseCount); put("filledPauseRatio", r3(f.filledPauseRatio))
            put("discourseMarkers", strings(f.discourseMarkers)); put("weightedMarkerVariety", r3(f.weightedMarkerVariety))
            put("meanLengthOfRun", r3(f.meanLengthOfRun)); put("speakingTimeSec", r3(f.speakingTimeSec))
        })
        o.put("lexical", JSONObject().apply {
            val l = a.lexical
            put("tokenCount", l.tokenCount); put("mattr", r3(l.mattr)); put("advancedRatio", r3(l.advancedRatio))
            put("c1PlusRatio", r3(l.c1PlusRatio)); put("collocationsPer100", r3(l.collocationsPer100))
            put("collocationHits", strings(l.collocationHits)); put("basicOveruseRatio", r3(l.basicOveruseRatio))
            put("cefrDistribution", JSONObject().apply { l.cefrDistribution.forEach { (k, v) -> put(k.name, r3(v)) } })
            put("upgrades", JSONArray().apply {
                l.upgrades.forEach {
                    put(JSONObject().put("original", it.original).put("start", it.start).put("end", it.end)
                        .put("replacements", strings(it.replacements)).put("topic", it.topic).put("autoApply", it.autoApply))
                }
            })
        })
        o.put("grammar", JSONObject().apply {
            val g = a.grammar
            put("sentenceCount", g.sentenceCount); put("clauseCount", g.clauseCount)
            put("complexSentenceRatio", r3(g.complexSentenceRatio)); put("errorFreeClauseRatio", r3(g.errorFreeClauseRatio))
            put("distinctStructureCount", g.distinctStructureCount)
            put("structures", JSONArray().apply { g.structures.forEach { put(JSONObject().put("type", it.type).put("excerpt", it.excerpt)) } })
            put("issues", JSONArray().apply { g.issues.forEach { put(issue(it)) } })
        })
        o.put("pronunciation", JSONObject().apply {
            val p = a.pronunciation
            put("meanPhonemeScore", r3(p.meanPhonemeScore)); put("wordStressAccuracy", r3(p.wordStressAccuracy))
            put("pitchSdSemitones", r3(p.pitchSdSemitones)); put("meanF0Hz", r3(p.meanF0Hz))
            put("monotone", p.monotone); put("method", p.method)
            sample.pronunciation?.let { pr ->
                put("meanScore", r3(pr.meanScore))
                put("words", JSONArray().apply {
                    pr.words.forEach { w ->
                        put(JSONObject().put("word", w.word).put("score", w.score).put("s", r3(w.startSec)).put("e", r3(w.endSec))
                            .put("expStress", w.expectedStressSyllable).put("detStress", w.detectedStressSyllable)
                            .put("phonemes", JSONArray().apply {
                                w.phonemes.forEach { ph ->
                                    put(JSONObject().put("a", ph.arpabet).put("ipa", ph.ipa).put("score", ph.score).put("gop", r3(ph.gop))
                                        .put("s", r3(ph.startSec)).put("e", r3(ph.endSec)).put("det", ph.detectedArpabet ?: JSONObject.NULL)
                                        .put("wi", ph.wordIndex))
                                }
                            }))
                    }
                })
            }
        })
        o.put("pitch", JSONObject().apply {
            val hop = if (sample.pitch.size >= 2) sample.pitch[1].timeSec - sample.pitch[0].timeSec else 0.016
            put("hopSec", r3(hop))
            put("t0", r3(sample.pitch.firstOrNull()?.timeSec ?: 0.0))
            put("f0", JSONArray().apply { sample.pitch.forEach { put(it.f0Hz.roundToInt()) } })
            put("db", JSONArray().apply { sample.pitch.forEach { put(it.rmsDb.roundToInt()) } })
        })
        o.put("modelAnswers", JSONObject().put("naturalPolish", a.naturalPolish).put("band9", a.band9Version))
        o.put("warnings", strings(a.warnings))
        o.put("files", JSONObject().put("wav", wavFile).put("csv", csvFile))
        return o
    }

    private fun criterion(c: CriterionScore) = JSONObject()
        .put("band", c.band).put("raw", r3(c.raw)).put("title", c.criterion.title).put("descriptor", c.descriptor)
        .put("evidence", strings(c.evidence)).put("nextSteps", strings(c.nextSteps))

    private fun issue(i: GrammarIssue) = JSONObject()
        .put("excerpt", i.excerpt).put("suggestion", i.suggestion).put("explanation", i.explanation)
        .put("start", i.start).put("end", i.end).put("source", i.source)

    fun decode(o: JSONObject): StoredSession {
        val task = o.optJSONObject("task") ?: JSONObject()
        val ctx = TaskContext(
            module = ModuleType.parse(task.optString("module")) ?: ModuleType.STIMULER_PART1,
            prompt = task.optString("prompt"),
            topic = task.optString("topic"),
            targetBand = task.optDouble("targetBand", 6.5),
            allocatedSec = task.optInt("allocatedSec", 60),
        )
        val tr = o.optJSONObject("transcript") ?: JSONObject()
        val words = tr.optJSONArray("words").objects { WordTiming(it.optString("w"), it.optDouble("s"), it.optDouble("e")) }
        val segs = tr.optJSONArray("segments")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { SpeechSegment(it.optDouble(0), it.optDouble(1)) } }
        } ?: emptyList()
        val pitchObj = o.optJSONObject("pitch")
        val pitch = if (pitchObj != null) {
            val hop = pitchObj.optDouble("hopSec", 0.016)
            val t0 = pitchObj.optDouble("t0", 0.0)
            val f0 = pitchObj.optJSONArray("f0")
            val db = pitchObj.optJSONArray("db")
            if (f0 == null) emptyList() else (0 until f0.length()).map { i ->
                val hz = f0.optInt(i).toFloat()
                PitchFrame(t0 + i * hop, hz, if (hz > 0f) 0.9f else 0f, (db?.optInt(i, -60) ?: -60).toFloat())
            }
        } else emptyList()

        val pronObj = o.optJSONObject("pronunciation") ?: JSONObject()
        val pronWords = pronObj.optJSONArray("words").objects { w ->
            WordPronunciation(
                word = w.optString("word"),
                phonemes = w.optJSONArray("phonemes").objects { p ->
                    PhonemeScore(p.optString("a"), p.optString("ipa"), p.optInt("score"), p.optDouble("gop"),
                        p.optDouble("s"), p.optDouble("e"), if (p.isNull("det")) null else p.optString("det"), p.optInt("wi"))
                },
                score = w.optInt("score"),
                startSec = w.optDouble("s"),
                endSec = w.optDouble("e"),
                expectedStressSyllable = w.optInt("expStress", -1),
                detectedStressSyllable = w.optInt("detStress", -1),
            )
        }
        val pronResult = if (pronWords.isNotEmpty()) PronunciationResult(pronWords, pronObj.optDouble("meanScore", 0.0), pronObj.optString("method")) else null
        val sample = SpeechSample(
            transcript = tr.optString("text"),
            words = words,
            speechSegments = segs,
            totalDurationSec = tr.optDouble("durationSec", 0.0),
            pitch = pitch,
            pronunciation = pronResult,
            referenceText = if (tr.isNull("referenceText")) null else tr.optString("referenceText"),
            acousticFilledPauses = tr.optInt("acousticFilledPauses", 0),
        )

        val scores = o.optJSONObject("scores") ?: JSONObject()
        fun crit(c: IeltsCriterion): CriterionScore {
            val j = scores.optJSONObject(c.code) ?: JSONObject()
            return CriterionScore(c, j.optDouble("band", 0.0), j.optDouble("raw", 0.0), j.optString("descriptor"),
                j.optJSONArray("evidence").strings(), j.optJSONArray("nextSteps").strings())
        }
        val f = o.optJSONObject("fluency") ?: JSONObject()
        val l = o.optJSONObject("lexical") ?: JSONObject()
        val g = o.optJSONObject("grammar") ?: JSONObject()
        val dist = l.optJSONObject("cefrDistribution")
        val assessment = SpeakingAssessment(
            fc = crit(IeltsCriterion.FC), lr = crit(IeltsCriterion.LR), gra = crit(IeltsCriterion.GRA), p = crit(IeltsCriterion.P),
            overallBand = scores.optDouble("overall", 0.0),
            cefr = CefrLevel.parse(scores.optString("cefr")) ?: CefrLevel.B1,
            cefrRationale = o.optJSONArray("cefrRationale").strings(),
            nextLevelChecklist = o.optJSONArray("nextLevelChecklist").strings(),
            fluency = FluencyMetrics(
                f.optInt("wordCount"), f.optDouble("wpm"), f.optDouble("phonationTimeRatio"), f.optInt("silentPauseCount"),
                f.optDouble("silentPausesPerMin"), f.optInt("filledPauseCount"), f.optDouble("filledPauseRatio"),
                f.optJSONArray("discourseMarkers").strings(), f.optDouble("weightedMarkerVariety"),
                f.optDouble("meanLengthOfRun"), f.optDouble("speakingTimeSec"),
            ),
            lexical = LexicalMetrics(
                l.optInt("tokenCount"), l.optDouble("mattr"),
                CefrLevel.entries.associateWith { dist?.optDouble(it.name, 0.0) ?: 0.0 },
                l.optDouble("advancedRatio"), l.optDouble("c1PlusRatio"), l.optDouble("collocationsPer100"),
                l.optJSONArray("collocationHits").strings(), l.optDouble("basicOveruseRatio"),
                l.optJSONArray("upgrades").objects {
                    VocabularyUpgrade(it.optString("original"), it.optInt("start"), it.optInt("end"),
                        it.optJSONArray("replacements").strings(), it.optString("topic"), it.optBoolean("autoApply", true))
                },
            ),
            grammar = GrammarMetrics(
                g.optInt("sentenceCount"), g.optInt("clauseCount"), g.optDouble("complexSentenceRatio"),
                g.optDouble("errorFreeClauseRatio"),
                g.optJSONArray("structures").objects { DetectedStructure(it.optString("type"), it.optString("excerpt")) },
                g.optInt("distinctStructureCount"),
                g.optJSONArray("issues").objects {
                    GrammarIssue(it.optString("excerpt"), it.optString("suggestion"), it.optString("explanation"),
                        it.optInt("start"), it.optInt("end"), it.optString("source", "rule"))
                },
            ),
            pronunciation = PronunciationMetrics(
                pronObj.optDouble("meanPhonemeScore"), pronObj.optDouble("wordStressAccuracy"), pronObj.optDouble("pitchSdSemitones"),
                pronObj.optDouble("meanF0Hz"), pronObj.optBoolean("monotone"), pronObj.optString("method"),
            ),
            naturalPolish = o.optJSONObject("modelAnswers")?.optString("naturalPolish").orEmpty(),
            band9Version = o.optJSONObject("modelAnswers")?.optString("band9").orEmpty(),
            warnings = o.optJSONArray("warnings").strings(),
        )
        return StoredSession(
            stem = o.optString("stem"),
            createdAt = o.optLong("createdAtMs", 0L),
            context = ctx,
            assessment = assessment,
            sample = sample,
            transcriptEngine = tr.optString("engine"),
            wavFile = o.optJSONObject("files")?.optString("wav").orEmpty(),
        )
    }

    /** Two-column metric CSV (opens directly in Excel / Sheets). */
    fun csv(stem: String, context: TaskContext, a: SpeakingAssessment, engine: String): String {
        val rows = ArrayList<Pair<String, String>>()
        rows += "Session" to stem
        rows += "Module" to context.module.label
        rows += "Prompt" to context.prompt
        rows += "Transcript engine" to engine
        rows += "Overall band" to a.overallBand.toString()
        rows += "CEFR" to a.cefr.name
        a.criteria.forEach { rows += "${it.criterion.title} (${it.criterion.code})" to it.band.toString() }
        val f = a.fluency
        rows += "Words" to f.wordCount.toString()
        rows += "WPM" to "%.1f".format(Locale.US, f.wpm)
        rows += "Phonation-time ratio" to "%.3f".format(Locale.US, f.phonationTimeRatio)
        rows += "Silent pauses >600ms per min" to "%.2f".format(Locale.US, f.silentPausesPerMin)
        rows += "Filled pauses" to f.filledPauseCount.toString()
        rows += "Discourse markers" to f.discourseMarkers.joinToString("; ")
        rows += "Weighted marker variety" to "%.2f".format(Locale.US, f.weightedMarkerVariety)
        val l = a.lexical
        rows += "MATTR(50)" to "%.3f".format(Locale.US, l.mattr)
        CefrLevel.entries.forEach { lv -> rows += "Lexis $lv %" to "%.1f".format(Locale.US, 100 * (l.cefrDistribution[lv] ?: 0.0)) }
        rows += "Collocations per 100 words" to "%.2f".format(Locale.US, l.collocationsPer100)
        val g = a.grammar
        rows += "Complex sentence ratio" to "%.3f".format(Locale.US, g.complexSentenceRatio)
        rows += "Error-free clause ratio" to "%.3f".format(Locale.US, g.errorFreeClauseRatio)
        rows += "Distinct structures" to g.distinctStructureCount.toString()
        val p = a.pronunciation
        rows += "Mean phoneme score" to "%.1f".format(Locale.US, p.meanPhonemeScore)
        rows += "Word-stress accuracy" to "%.3f".format(Locale.US, p.wordStressAccuracy)
        rows += "Pitch SD (semitones)" to "%.2f".format(Locale.US, p.pitchSdSemitones)
        return buildString {
            append("metric,value\n")
            rows.forEach { (k, v) -> append(esc(k)).append(',').append(esc(v)).append('\n') }
        }
    }

    private fun esc(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
