package com.polylingua.edge.domain

import com.polylingua.edge.domain.model.BandMath
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.CriterionScore
import com.polylingua.edge.domain.model.FluencyMetrics
import com.polylingua.edge.domain.model.GrammarIssue
import com.polylingua.edge.domain.model.GrammarMetrics
import com.polylingua.edge.domain.model.IeltsCriterion
import com.polylingua.edge.domain.model.LexicalMetrics
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.PronunciationMetrics
import com.polylingua.edge.domain.model.SpeakingAssessment
import com.polylingua.edge.domain.model.SpeechSample
import com.polylingua.edge.domain.model.TaskContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * IELTS Speaking (Band 1-9) and CEFR (A1-C2) scoring engine.
 *
 * Each criterion is computed from measurable features, mapped to a band with piecewise-linear
 * calibration curves, rounded to the nearest half band, and explained with the paraphrased
 * official descriptor plus metric evidence:
 *  - FC  : WPM = N_words / (T/60); PTR = T_speech / T; unfilled pauses > 600 ms per minute;
 *          filled-pause ratio (uh, um, er, ah) / N_words; weighted discourse-marker variety;
 *          mean length of run. Band 8+ requires 130 <= WPM <= 165, PTR >= 0.78,
 *          pauses <= 1.5/min and D_markers >= 5, otherwise FC is capped at 7.5.
 *  - LR  : MATTR(50); share of B2+ and C1+ content words (CEFR-J + Octanove); idiomatic /
 *          collocational items per 100 words; penalty for basic-word overuse.
 *  - GRA : complex-sentence ratio; error-free clause ratio R_efc = C_error_free / C_total;
 *          number of distinct advanced structures.
 *  - P   : mean phoneme GOP score, word-stress accuracy and pitch variation sigma_F0 in semitones
 *          (monotone delivery below 1.8 st is penalised).
 * Overall = mean of the four, with IELTS rounding (.25 -> .5, .75 -> next whole band).
 */
class PedagogicalRubricEngine(private val cefr: CefrLookup) {

    // ---------------------------------------------------------------- calibration curves
    private val wpmKnots = listOf(0.0 to 1.0, 40.0 to 2.5, 60.0 to 3.5, 80.0 to 4.5, 95.0 to 5.5, 110.0 to 6.5,
        122.0 to 7.0, 130.0 to 8.0, 148.0 to 9.0, 165.0 to 9.0, 185.0 to 7.5, 210.0 to 6.0, 240.0 to 5.0)
    private val ptrKnots = listOf(0.20 to 2.0, 0.35 to 3.0, 0.45 to 4.0, 0.55 to 5.0, 0.63 to 6.0, 0.70 to 7.0, 0.78 to 8.0, 0.88 to 9.0)
    private val pauseKnots = listOf(0.0 to 9.0, 1.5 to 8.5, 2.5 to 7.5, 3.5 to 6.5, 5.0 to 5.5, 7.0 to 4.5, 9.0 to 3.5, 12.0 to 2.5, 16.0 to 1.5)
    private val fillerKnots = listOf(0.0 to 9.0, 0.01 to 8.5, 0.02 to 7.5, 0.04 to 6.5, 0.06 to 5.5, 0.09 to 4.5, 0.13 to 3.5, 0.20 to 2.0)
    private val markerKnots = listOf(0.0 to 3.5, 1.0 to 4.5, 2.0 to 5.5, 3.0 to 6.0, 4.0 to 6.5, 5.0 to 8.0, 6.0 to 8.5, 8.0 to 9.0)
    private val runKnots = listOf(1.0 to 2.5, 2.0 to 3.0, 4.0 to 5.0, 6.0 to 6.0, 8.0 to 7.0, 11.0 to 8.0, 14.0 to 9.0)

    private val mattrKnots = listOf(0.40 to 3.0, 0.50 to 4.0, 0.58 to 5.0, 0.64 to 6.0, 0.69 to 7.0, 0.74 to 8.0, 0.80 to 9.0)
    private val advancedKnots = listOf(0.0 to 3.5, 0.03 to 4.5, 0.06 to 5.5, 0.10 to 6.5, 0.14 to 7.0, 0.18 to 8.0, 0.24 to 9.0)
    private val c1Knots = listOf(0.0 to 4.0, 0.01 to 5.0, 0.02 to 6.0, 0.04 to 7.0, 0.06 to 8.0, 0.09 to 9.0)
    private val collocKnots = listOf(0.0 to 4.5, 0.5 to 5.5, 1.0 to 6.5, 2.0 to 7.5, 3.0 to 8.5, 4.0 to 9.0)
    private val overuseKnots = listOf(0.0 to 0.0, 0.05 to 0.25, 0.10 to 0.75, 0.15 to 1.25, 0.25 to 1.75)

    private val complexKnots = listOf(0.0 to 3.0, 0.10 to 4.0, 0.20 to 5.0, 0.35 to 6.0, 0.50 to 7.0, 0.65 to 8.0, 0.80 to 9.0)
    private val efcKnots = listOf(0.20 to 2.5, 0.40 to 3.5, 0.55 to 4.5, 0.65 to 5.5, 0.75 to 6.5, 0.85 to 7.5, 0.93 to 8.5, 0.98 to 9.0)
    private val rangeKnots = listOf(0.0 to 3.5, 1.0 to 4.5, 2.0 to 5.5, 3.0 to 6.5, 4.0 to 7.0, 6.0 to 8.0, 8.0 to 9.0)

    private val phonemeKnots = listOf(20.0 to 2.0, 35.0 to 3.0, 45.0 to 4.0, 55.0 to 5.0, 63.0 to 6.0, 70.0 to 6.5, 77.0 to 7.0, 83.0 to 8.0, 90.0 to 9.0)
    private val stressKnots = listOf(0.30 to 4.0, 0.50 to 5.0, 0.65 to 6.0, 0.75 to 7.0, 0.85 to 8.0, 0.95 to 9.0)
    private val prosodyKnots = listOf(0.5 to 3.5, 1.0 to 4.5, 1.8 to 6.0, 2.5 to 7.5, 3.2 to 8.5, 4.0 to 9.0, 6.5 to 8.5, 9.0 to 7.0)

    fun assess(
        sample: SpeechSample,
        context: TaskContext,
        extraGrammarIssues: List<GrammarIssue> = emptyList(),
    ): SpeakingAssessment {
        val warnings = ArrayList<String>()
        val fluency = fluencyMetrics(sample, warnings)
        val lexical = lexicalMetrics(sample.transcript, context.topic)
        val grammar = GrammarPatternDetector.analyze(sample.transcript, extraGrammarIssues)
        val pron = pronunciationMetrics(sample, warnings)

        val fc = scoreFc(fluency)
        val lr = scoreLr(lexical)
        val gra = scoreGra(grammar)
        val p = scoreP(pron, sample)

        // Insufficient-sample caps (the descriptors note band 1-2 behaviour for tiny samples).
        val capped = listOf(fc, lr, gra, p).map { c ->
            val cap = when {
                fluency.wordCount < 10 -> 2.5
                fluency.wordCount < 25 -> 4.5
                fluency.wordCount < 40 -> 6.0
                else -> 9.0
            }
            if (c.band > cap) c.copy(band = cap, evidence = c.evidence + "Capped at Band $cap: only ${fluency.wordCount} words were produced.") else c
        }
        if (fluency.wordCount < 40) warnings += "Short sample (${fluency.wordCount} words): speak for the full time to get a reliable estimate."

        val overall = BandMath.overall(capped[0].band, capped[1].band, capped[2].band, capped[3].band)
        val level = CefrLevel.fromBand(overall)
        val polished = ModelAnswerComposer.naturalPolish(sample.transcript, grammar.issues, lexical.upgrades)
        val band9 = ModelAnswerComposer.band9Version(polished, context)

        return SpeakingAssessment(
            fc = capped[0], lr = capped[1], gra = capped[2], p = capped[3],
            overallBand = overall,
            cefr = level,
            cefrRationale = cefrRationale(level, overall, fluency, lexical, grammar, pron),
            nextLevelChecklist = CefrDescriptorMatrix.transitionChecklist(level),
            fluency = fluency,
            lexical = lexical,
            grammar = grammar,
            pronunciation = pron,
            naturalPolish = polished,
            band9Version = band9,
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------------------- FC
    fun fluencyMetrics(sample: SpeechSample, warnings: MutableList<String>): FluencyMetrics {
        val tokens = TextAnalysis.words(sample.transcript)
        val textFillers = tokens.count { it in TextAnalysis.FILLED_PAUSES }
        val nWords = tokens.size - textFillers
        val fillers = textFillers + sample.acousticFilledPauses.coerceAtLeast(0)
        val segments = sample.speechSegments.sortedBy { it.startSec }
        val words = sample.words.sortedBy { it.startSec }

        val firstSpeech = segments.firstOrNull()?.startSec ?: words.firstOrNull()?.startSec ?: 0.0
        val lastSpeech = segments.lastOrNull()?.endSec ?: words.lastOrNull()?.endSec ?: sample.totalDurationSec
        // Count up to 3 s of initial hesitation; ignore trailing silence after the last word.
        val windowStart = min(firstSpeech, 3.0).coerceAtLeast(0.0)
        var totalSec = (lastSpeech - windowStart).coerceAtLeast(0.0)
        if (totalSec < 1.0) totalSec = sample.totalDurationSec.coerceAtLeast(1.0)

        val speechSec = when {
            segments.isNotEmpty() -> mergedDuration(segments.map { it.startSec to it.endSec })
            words.isNotEmpty() -> mergedDuration(words.map { it.startSec to it.endSec + 0.12 })
            else -> totalSec * 0.6
        }.coerceAtMost(totalSec)
        if (segments.isEmpty() && words.isEmpty()) warnings += "No voice-activity or word timings: fluency timing is estimated."

        // Unfilled pauses > 600 ms. Word timings let us skip pauses at sentence boundaries.
        val pauseThreshold = 0.6
        var pauses = 0
        if (words.size >= 2) {
            val punctAfter = punctuationAfterWords(sample.transcript)
            for (i in 1 until words.size) {
                val gap = words[i].startSec - words[i - 1].endSec
                if (gap > pauseThreshold) {
                    val atBoundary = punctAfter.getOrNull(i - 1) == true
                    if (!atBoundary || gap > 2.0) pauses++
                }
            }
        } else if (segments.size >= 2) {
            for (i in 1 until segments.size) if (segments[i].startSec - segments[i - 1].endSec > pauseThreshold) pauses++
        }
        val minutes = totalSec / 60.0
        val markers = DiscourseMarkerLexicon.findMarkers(tokens)
        return FluencyMetrics(
            wordCount = nWords,
            wpm = if (minutes > 0) nWords / minutes else 0.0,
            phonationTimeRatio = (speechSec / totalSec).coerceIn(0.0, 1.0),
            silentPauseCount = pauses,
            silentPausesPerMin = if (minutes > 0) pauses / minutes else 0.0,
            filledPauseCount = fillers,
            filledPauseRatio = if (nWords + fillers <= 0) 0.0 else fillers.toDouble() / (nWords + fillers),
            discourseMarkers = markers.map { it.phrase }.distinct(),
            weightedMarkerVariety = DiscourseMarkerLexicon.weightedVariety(markers),
            meanLengthOfRun = nWords.toDouble() / (pauses + 1),
            speakingTimeSec = totalSec,
        )
    }

    private fun scoreFc(f: FluencyMetrics): CriterionScore {
        val raw = 0.22 * BandMath.interp(f.wpm, wpmKnots) +
            0.22 * BandMath.interp(f.phonationTimeRatio, ptrKnots) +
            0.18 * BandMath.interp(f.silentPausesPerMin, pauseKnots) +
            0.10 * BandMath.interp(f.filledPauseRatio, fillerKnots) +
            0.18 * BandMath.interp(f.weightedMarkerVariety, markerKnots) +
            0.10 * BandMath.interp(f.meanLengthOfRun, runKnots)
        val meetsBand8 = f.wpm in 130.0..165.0 && f.phonationTimeRatio >= 0.78 && f.silentPausesPerMin <= 1.5 && f.weightedMarkerVariety >= 5.0
        val gated = if (raw >= 8.0 && !meetsBand8) 7.5 else raw
        val band = BandMath.roundHalf(gated)
        val evidence = listOf(
            "Speech rate ${f.wpm.r0()} WPM (Band 8-9 target 130-165)",
            "Phonation-time ratio ${f.phonationTimeRatio.r2()} (target >= 0.78)",
            "${f.silentPauseCount} unfilled pauses > 600 ms = ${f.silentPausesPerMin.r1()}/min (target <= 1.5)",
            "Filled pauses ${f.filledPauseCount} (${(f.filledPauseRatio * 100).r1()}% of words)",
            "Discourse-marker variety ${f.weightedMarkerVariety.r1()} (target >= 5): ${f.discourseMarkers.take(8).joinToString().ifEmpty { "none" }}",
            "Mean length of run ${f.meanLengthOfRun.r1()} words between pauses",
        )
        val steps = ArrayList<String>()
        if (f.wpm < 130) steps += "Raise your pace towards 130 WPM: rehearse answers aloud in 'idea chunks' of 6-8 words."
        if (f.wpm > 170) steps += "Slow down slightly (target 130-165 WPM) and pause at clause boundaries instead of mid-phrase."
        if (f.phonationTimeRatio < 0.78) steps += "Increase speaking time vs. silence (PTR ${f.phonationTimeRatio.r2()} -> 0.78): use fillers like 'That's an interesting question...' instead of silence."
        if (f.silentPausesPerMin > 1.5) steps += "Reduce mid-sentence pauses: plan the next idea while finishing the current one."
        if (f.filledPauseRatio > 0.02) steps += "Replace 'uh/um' with natural thinking phrases ('Let me think...', 'How can I put it...')."
        if (f.weightedMarkerVariety < 5) steps += "Add more varied linkers: " + DiscourseMarkerLexicon.suggestionsFor(DiscourseMarkerLexicon.Category.CONTRAST, 2).joinToString() + ", " +
            DiscourseMarkerLexicon.suggestionsFor(DiscourseMarkerLexicon.Category.EXAMPLE, 2).joinToString() + "."
        return CriterionScore(IeltsCriterion.FC, band, raw, IeltsBandRubricRepository.descriptor(IeltsCriterion.FC, band), evidence, steps)
    }

    // ------------------------------------------------------------------------------- LR
    fun lexicalMetrics(transcript: String, topic: String): LexicalMetrics {
        val tokens = TextAnalysis.words(transcript).filter { it !in TextAnalysis.FILLED_PAUSES }
        val content = tokens.filter { TextAnalysis.isContentWord(it) }
        val counts = CefrLevel.entries.associateWith { 0 }.toMutableMap()
        var known = 0
        for (w in content) {
            val lvl = cefr.levelOf(w) ?: continue
            counts[lvl] = (counts[lvl] ?: 0) + 1
            known++
        }
        val dist = counts.mapValues { (_, v) -> if (known == 0) 0.0 else v.toDouble() / known }
        val advanced = (dist[CefrLevel.B2] ?: 0.0) + (dist[CefrLevel.C1] ?: 0.0) + (dist[CefrLevel.C2] ?: 0.0)
        val c1Plus = (dist[CefrLevel.C1] ?: 0.0) + (dist[CefrLevel.C2] ?: 0.0)
        val hits = LexicalUpgradeDictionary.advancedItemsUsed(tokens, cefr)
        val overuse = tokens.count { it in LexicalUpgradeDictionary.BASIC_OVERUSE }
        return LexicalMetrics(
            tokenCount = tokens.size,
            mattr = TextAnalysis.mattr(tokens, 50),
            cefrDistribution = dist,
            advancedRatio = advanced,
            c1PlusRatio = c1Plus,
            collocationsPer100 = if (tokens.isEmpty()) 0.0 else hits.size * 100.0 / tokens.size,
            collocationHits = hits.distinct(),
            basicOveruseRatio = if (tokens.isEmpty()) 0.0 else overuse.toDouble() / tokens.size,
            upgrades = LexicalUpgradeDictionary.findUpgrades(transcript, topic),
        )
    }

    private fun scoreLr(l: LexicalMetrics): CriterionScore {
        val penalty = BandMath.interp(l.basicOveruseRatio, overuseKnots)
        val raw = (0.30 * BandMath.interp(l.mattr, mattrKnots) +
            0.25 * BandMath.interp(l.advancedRatio, advancedKnots) +
            0.20 * BandMath.interp(l.c1PlusRatio, c1Knots) +
            0.25 * BandMath.interp(l.collocationsPer100, collocKnots)) - penalty
        val gated = if (raw >= 8.0 && (l.c1PlusRatio < 0.04 || l.collocationsPer100 < 2.0)) 7.5 else raw
        val band = BandMath.roundHalf(gated)
        val evidence = listOf(
            "Lexical diversity MATTR(50) = ${l.mattr.r2()} (Band 8 ~ 0.74)",
            "B2+ content words ${(l.advancedRatio * 100).r1()}%, C1/C2 ${(l.c1PlusRatio * 100).r1()}% (Band 8 needs >= 4% C1/C2)",
            "Idiomatic / collocational items: ${l.collocationsPer100.r1()} per 100 words" + if (l.collocationHits.isNotEmpty()) " (${l.collocationHits.take(5).joinToString()})" else "",
            "Basic-word overuse ${(l.basicOveruseRatio * 100).r1()}% (good, bad, very, thing, really...)",
        )
        val steps = ArrayList<String>()
        l.upgrades.take(3).forEach { steps += "Upgrade \"${it.original}\" -> ${it.replacements.joinToString(" / ")}" }
        if (l.c1PlusRatio < 0.04) steps += "Aim for at least one C1/C2 word or collocation per sentence."
        if (l.mattr < 0.69) steps += "Avoid repeating the same nouns/verbs - paraphrase key ideas with synonyms."
        return CriterionScore(IeltsCriterion.LR, band, raw, IeltsBandRubricRepository.descriptor(IeltsCriterion.LR, band), evidence, steps)
    }

    // ------------------------------------------------------------------------------ GRA
    private fun scoreGra(g: GrammarMetrics): CriterionScore {
        val raw = 0.30 * BandMath.interp(g.complexSentenceRatio, complexKnots) +
            0.45 * BandMath.interp(g.errorFreeClauseRatio, efcKnots) +
            0.25 * BandMath.interp(g.distinctStructureCount.toDouble(), rangeKnots)
        val gated = if (raw >= 8.0 && (g.errorFreeClauseRatio < 0.85 || g.distinctStructureCount < 5)) 7.5 else raw
        val band = BandMath.roundHalf(gated)
        val evidence = listOf(
            "Complex-sentence ratio ${(g.complexSentenceRatio * 100).r0()}% of ${g.sentenceCount} sentences",
            "Error-free clause ratio R_efc = ${(g.errorFreeClauseRatio * 100).r0()}% (${g.clauseCount} clauses, ${g.issues.size} issue(s))",
            "Distinct advanced structures: ${g.distinctStructureCount} - " +
                g.structures.map { it.type }.distinct().take(6).joinToString().ifEmpty { "none detected" },
        )
        val steps = ArrayList<String>()
        g.issues.take(3).forEach { steps += "\"${it.excerpt}\" -> \"${it.suggestion}\" (${it.explanation})" }
        val used = g.structures.map { it.type }.toSet()
        val missing = GrammarPatternDetector.ADVANCED_STRUCTURE_NAMES.filter { it !in used }
        if (g.distinctStructureCount < 5) steps += "Try adding: " + missing.take(3).joinToString("; ") + "."
        return CriterionScore(IeltsCriterion.GRA, band, raw, IeltsBandRubricRepository.descriptor(IeltsCriterion.GRA, band), evidence, steps)
    }

    // -------------------------------------------------------------------------------- P
    fun pronunciationMetrics(sample: SpeechSample, warnings: MutableList<String>): PronunciationMetrics {
        val voiced = sample.pitch.filter { it.voiced && it.f0Hz in 50f..600f }
        val semis = voiced.map { 12.0 * ln(it.f0Hz / 100.0) / ln(2.0) }
        val sd = robustSd(semis)
        val meanF0 = if (voiced.isEmpty()) 0.0 else voiced.sumOf { it.f0Hz.toDouble() } / voiced.size
        val result = sample.pronunciation
        val stressPairs = result?.words?.filter { it.expectedStressSyllable >= 0 && it.detectedStressSyllable >= 0 } ?: emptyList()
        val stressAcc = if (stressPairs.isEmpty()) Double.NaN else stressPairs.count { it.expectedStressSyllable == it.detectedStressSyllable }.toDouble() / stressPairs.size
        if (result == null) warnings += "Phoneme (GOP) model not installed: pronunciation estimated from prosody and intelligibility only."
        if (voiced.size < 20) warnings += "Too little voiced speech for a reliable intonation measurement."
        return PronunciationMetrics(
            meanPhonemeScore = result?.meanScore ?: Double.NaN,
            wordStressAccuracy = stressAcc,
            pitchSdSemitones = sd,
            meanF0Hz = meanF0,
            monotone = voiced.size >= 20 && sd < 1.8,
            method = result?.method ?: "prosody-only",
        )
    }

    private fun scoreP(p: PronunciationMetrics, sample: SpeechSample): CriterionScore {
        val prosody = BandMath.interp(p.pitchSdSemitones, prosodyKnots)
        val phoneme = if (p.meanPhonemeScore.isNaN()) intelligibilityProxy(sample) else BandMath.interp(p.meanPhonemeScore, phonemeKnots)
        val stress = if (p.wordStressAccuracy.isNaN()) phoneme else BandMath.interp(p.wordStressAccuracy, stressKnots)
        var raw = 0.60 * phoneme + 0.15 * stress + 0.25 * prosody
        if (p.monotone) raw -= 0.5
        if (p.meanPhonemeScore.isNaN()) raw = min(raw, 6.5)
        val band = BandMath.roundHalf(raw)
        val evidence = ArrayList<String>()
        if (!p.meanPhonemeScore.isNaN()) evidence += "Mean phoneme accuracy (GOP) ${p.meanPhonemeScore.r0()}% via ${p.method}"
        else evidence += "Phoneme accuracy estimated from speech-recognition intelligibility (install the GOP model for phoneme scores)"
        if (!p.wordStressAccuracy.isNaN()) evidence += "Word-stress accuracy ${(p.wordStressAccuracy * 100).r0()}%"
        evidence += "Pitch variation sigma_F0 = ${p.pitchSdSemitones.r1()} semitones" + if (p.monotone) " - monotone (< 1.8 st), -0.5 band" else ""
        if (p.meanF0Hz > 0) evidence += "Mean F0 ${p.meanF0Hz.r0()} Hz"
        val steps = ArrayList<String>()
        sample.pronunciation?.words?.flatMap { it.phonemes }?.filter { it.score < 50 }
            ?.groupBy { it.arpabet.trimEnd('0', '1', '2') }?.entries?.sortedByDescending { it.value.size }?.take(3)
            ?.forEach { (ph, list) ->
                val info = PhoneticArticulationDatabase.lookup(ph)
                steps += "/${info?.ipa ?: ph}/ was weak ${list.size}x: ${info?.instruction ?: ""}"
            }
        if (p.monotone) steps += "Use falling intonation on key words and rising tones on lists/questions to avoid a robotic delivery."
        if (!p.wordStressAccuracy.isNaN() && p.wordStressAccuracy < 0.8) steps += "Stress the right syllable in multi-syllable words (pho-TO-gra-phy, e-CO-no-my)."
        return CriterionScore(IeltsCriterion.P, band, raw, IeltsBandRubricRepository.descriptor(IeltsCriterion.P, band), evidence, steps)
    }

    /** Fallback when no GOP model: share of recognised tokens that are real dictionary/CEFR words. */
    private fun intelligibilityProxy(sample: SpeechSample): Double {
        val tokens = TextAnalysis.words(sample.transcript).filter { it !in TextAnalysis.FILLED_PAUSES }
        if (tokens.isEmpty()) return 3.0
        val recognisable = tokens.count { it in TextAnalysis.STOPWORDS || cefr.levelOf(it) != null }
        val ratio = recognisable.toDouble() / tokens.size
        return BandMath.interp(ratio, listOf(0.5 to 4.0, 0.7 to 5.0, 0.8 to 6.0, 0.9 to 6.5, 0.97 to 7.0))
    }

    // -------------------------------------------------------------------------- CEFR text
    private fun cefrRationale(
        level: CefrLevel, overall: Double, f: FluencyMetrics, l: LexicalMetrics, g: GrammarMetrics, p: PronunciationMetrics,
    ): List<String> {
        val out = ArrayList<String>()
        out += "Overall Band ${overall.r1()} maps to CEFR ${level.name} (Band ${level.minBand.r1()}-${level.maxBand.r1()})."
        out += "Spoken fluency (${CefrDescriptorMatrix.Scale.SPOKEN_FLUENCY.title}): " + CefrDescriptorMatrix.descriptor(CefrDescriptorMatrix.Scale.SPOKEN_FLUENCY, level) +
            " Evidence: ${f.wpm.r0()} WPM, PTR ${f.phonationTimeRatio.r2()}."
        out += "Vocabulary range: " + CefrDescriptorMatrix.descriptor(CefrDescriptorMatrix.Scale.VOCABULARY_RANGE, level) +
            " Evidence: ${(l.advancedRatio * 100).r0()}% B2+ words."
        out += "Grammatical accuracy: " + CefrDescriptorMatrix.descriptor(CefrDescriptorMatrix.Scale.GRAMMATICAL_ACCURACY, level) +
            " Evidence: ${(g.errorFreeClauseRatio * 100).r0()}% error-free clauses."
        out += "Phonological control: " + CefrDescriptorMatrix.descriptor(CefrDescriptorMatrix.Scale.PHONOLOGICAL_CONTROL, level) +
            if (!p.meanPhonemeScore.isNaN()) " Evidence: ${p.meanPhonemeScore.r0()}% phoneme accuracy." else ""
        out += "Coherence: " + CefrDescriptorMatrix.descriptor(CefrDescriptorMatrix.Scale.COHERENCE_COHESION, level)
        return out
    }

    // ------------------------------------------------------------------------------ utils
    private fun mergedDuration(intervals: List<Pair<Double, Double>>): Double {
        if (intervals.isEmpty()) return 0.0
        val sorted = intervals.sortedBy { it.first }
        var total = 0.0
        var curStart = sorted[0].first
        var curEnd = sorted[0].second
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= curEnd) curEnd = max(curEnd, e) else {
                total += curEnd - curStart
                curStart = s
                curEnd = e
            }
        }
        return total + (curEnd - curStart)
    }

    /** For each word token, true if a sentence/clause punctuation mark follows it. */
    private fun punctuationAfterWords(text: String): List<Boolean> {
        val tokens = TextAnalysis.tokenize(text).filter { it.lower !in TextAnalysis.FILLED_PAUSES }
        return tokens.mapIndexed { i, t ->
            val nextStart = if (i + 1 < tokens.size) tokens[i + 1].start else text.length
            text.substring(t.end, nextStart).any { it == '.' || it == ',' || it == '!' || it == '?' || it == ';' || it == ':' }
        }
    }

    private fun robustSd(values: List<Double>): Double {
        if (values.size < 3) return 0.0
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        val kept = values.filter { abs(it - mean) <= 3 * sd }
        if (kept.size < 3) return sd
        val m2 = kept.average()
        return sqrt(kept.sumOf { (it - m2) * (it - m2) } / kept.size)
    }

    companion object {
        fun Double.r0(): String = if (isNaN()) "-" else String.format(Locale.US, "%.0f", this)
        fun Double.r1(): String = if (isNaN()) "-" else String.format(Locale.US, "%.1f", this)
        fun Double.r2(): String = if (isNaN()) "-" else String.format(Locale.US, "%.2f", this)

        /** sigma_F0 in semitones for a pitch track (exposed for dashboards). */
        fun pitchSdSemitones(frames: List<PitchFrame>): Double {
            val v = frames.filter { it.voiced }.map { 12.0 * ln(it.f0Hz / 100.0) / ln(2.0) }
            if (v.size < 3) return 0.0
            val m = v.average()
            return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        }
    }
}
