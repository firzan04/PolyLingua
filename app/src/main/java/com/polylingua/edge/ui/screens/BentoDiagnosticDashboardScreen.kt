package com.polylingua.edge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.polylingua.edge.data.SessionArtifactStore
import com.polylingua.edge.domain.model.CriterionScore
import com.polylingua.edge.domain.model.GrammarIssue
import com.polylingua.edge.domain.model.PhonemeScore
import com.polylingua.edge.domain.model.WordPronunciation
import com.polylingua.edge.network.WordDiff
import com.polylingua.edge.speech.AssessmentPipeline
import com.polylingua.edge.speech.AudioClip
import com.polylingua.edge.speech.LocalLlmEngine
import com.polylingua.edge.speech.PlaybackState
import com.polylingua.edge.speech.SpeechSynthesisEngine
import com.polylingua.edge.ui.DashboardPayload
import com.polylingua.edge.ui.DiagnosticSessionStore
import com.polylingua.edge.ui.components.BandRadarChart
import com.polylingua.edge.ui.components.Banner
import com.polylingua.edge.ui.components.BulletList
import com.polylingua.edge.ui.components.CefrDistributionBar
import com.polylingua.edge.ui.components.EmptyState
import com.polylingua.edge.ui.components.FrostedCard
import com.polylingua.edge.ui.components.GhostButton
import com.polylingua.edge.ui.components.IconCircleButton
import com.polylingua.edge.ui.components.MeterBar
import com.polylingua.edge.ui.components.PhonemeHeatmap
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PitchContourCanvas
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.components.PrimaryButton
import com.polylingua.edge.ui.components.RedGreenDiffText
import com.polylingua.edge.ui.components.RemediationSheet
import com.polylingua.edge.ui.components.ScoreRing
import com.polylingua.edge.ui.components.ScreenHeader
import com.polylingua.edge.ui.components.ScreenPadding
import com.polylingua.edge.ui.components.StatTile
import com.polylingua.edge.ui.components.WpmGauge
import com.polylingua.edge.ui.components.pitchToArray
import com.polylingua.edge.ui.navigation.Routes
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val store: DiagnosticSessionStore,
    private val tts: SpeechSynthesisEngine,
    private val pipeline: AssessmentPipeline,
    private val vault: SessionArtifactStore,
    private val llm: LocalLlmEngine,
) : ViewModel() {
    val payload: StateFlow<DashboardPayload?> = store.current
    val playback: StateFlow<PlaybackState> = tts.player.state

    private val _deep = MutableStateFlow<String?>(null)
    val deepStatus: StateFlow<String?> = _deep

    val llmAvailable: Boolean get() = llm.isAvailable

    fun speak(text: String, label: String) {
        viewModelScope.launch { tts.speak(text, label = label) }
    }

    fun stopAudio() = tts.stop()

    /** Plays the learner's own recording from [startSec] (tap a transcript word). */
    fun playRecording(startSec: Double = 0.0) {
        val audio = payload.value?.audio ?: return
        viewModelScope.launch { tts.player.play(AudioClip(audio, 16000), "Your recording", startSec = startSec) }
    }

    fun deepCheck() {
        val p = payload.value ?: return
        if (!llm.isAvailable || _deep.value != null) return
        viewModelScope.launch {
            _deep.value = "Starting offline AI review…"
            val issues = pipeline.deepGrammarCheck(p.sample.transcript, p.assessment.cefr.name) { i, n -> _deep.value = "Reviewing sentence ${minOf(i + 1, n)}/$n…" }
            if (issues.isEmpty()) {
                _deep.value = "AI review found no further corrections."
                return@launch
            }
            val outcome = pipeline.reassess(p.sample, p.context, p.engine, issues)
            store.current.value = p.copy(assessment = outcome.assessment)
            if (p.sessionId != null && p.stem != null) vault.overwrite(p.sessionId, p.stem, p.context, outcome.assessment, p.sample, p.engine)
            _deep.value = "AI review added ${issues.size} correction(s); scores updated."
        }
    }

    override fun onCleared() {
        tts.stop()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BentoDiagnosticDashboardScreen(nav: NavHostController, vm: DashboardViewModel = hiltViewModel()) {
    val payload by vm.payload.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val deep by vm.deepStatus.collectAsStateWithLifecycle()
    var remediation by remember { mutableStateOf<Pair<WordPronunciation, PhonemeScore>?>(null) }
    val context = LocalContext.current
    val p = payload
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Diagnostic Dashboard", p?.stem ?: "IELTS + CEFR analysis", onBack = { nav.popBackStack() })
        if (p == null) {
            EmptyState(PolyIcons.Chart, "No assessment loaded", "Complete a drill or hydrate a saved session from the History Vault.") {
                PrimaryButton("Go to practice", { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } })
            }
            return@Column
        }
        val a = p.assessment
        LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---------------------------------------------------------- Card A: overall
            item {
                FrostedCard(accent = PolyColors.forBand(a.overallBand)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("OVERALL BAND", style = PolyType.Pill.copy(color = PolyColors.MutedSlateText))
                            Text("%.1f".format(a.overallBand), style = PolyType.NumericLarge.copy(color = PolyColors.forBand(a.overallBand)))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PillBadge("CEFR ${a.cefr.name}", PolyColors.forBand(a.overallBand), filled = true)
                                PillBadge("TARGET ${p.context.targetBand}", PolyColors.AmberWarning)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(p.context.module.label, style = PolyType.Caption)
                            Text("Transcript engine: ${p.engine}", style = PolyType.Caption)
                        }
                        BandRadarChart(a.fc.band, a.lr.band, a.gra.band, a.p.band, target = p.context.targetBand)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Why ${a.cefr.name}", style = PolyType.CardTitle)
                    Spacer(Modifier.height(4.dp))
                    BulletList(a.cefrRationale)
                    if (a.nextLevelChecklist.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Checklist to reach the next level", style = PolyType.CardTitle.copy(color = PolyColors.NeonMintSuccess))
                        Spacer(Modifier.height(4.dp))
                        BulletList(a.nextLevelChecklist, color = PolyColors.NeonMintSuccess)
                    }
                }
            }
            if (a.warnings.isNotEmpty()) item { Banner(a.warnings.joinToString("\n"), PolyColors.AmberWarning) }
            // ---------------------------------------------------------- Card B: fluency
            item {
                val f = a.fluency
                CriterionCard(a.fc) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WpmGauge(f.wpm)
                        Spacer(Modifier.width(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            StatTile("PTR", "%.2f".format(f.phonationTimeRatio), color = if (f.phonationTimeRatio >= 0.78) PolyColors.NeonMintSuccess else PolyColors.AmberWarning, hint = "target ≥ 0.78")
                            StatTile("Pauses/min", "%.1f".format(f.silentPausesPerMin), color = if (f.silentPausesPerMin <= 1.5) PolyColors.NeonMintSuccess else PolyColors.AmberWarning, hint = ">600 ms · ≤ 1.5")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Words", "${f.wordCount}", Modifier.weight(1f))
                        StatTile("Fillers", "${f.filledPauseCount}", Modifier.weight(1f), color = if (f.filledPauseRatio <= 0.02) PolyColors.NeonMintSuccess else PolyColors.AmberWarning)
                        StatTile("Run", "%.1f".format(f.meanLengthOfRun), Modifier.weight(1f), hint = "words")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Discourse markers (variety ${"%.1f".format(f.weightedMarkerVariety)} · target ≥ 5)", style = PolyType.Caption)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (f.discourseMarkers.isEmpty()) Text("None detected", style = PolyType.Caption.copy(color = PolyColors.CrimsonAlert))
                        f.discourseMarkers.forEach { PillBadge(it, PolyColors.ElectricTeal) }
                    }
                }
            }
            // ---------------------------------------------------------- Card C: lexis
            item {
                val l = a.lexical
                CriterionCard(a.lr) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("MATTR(50)", "%.2f".format(l.mattr), Modifier.weight(1f))
                        StatTile("B2+", "${(l.advancedRatio * 100).toInt()}%", Modifier.weight(1f))
                        StatTile("C1+", "${(l.c1PlusRatio * 100).toInt()}%", Modifier.weight(1f), color = PolyColors.NeonMintSuccess)
                        StatTile("Colloc.", "%.1f".format(l.collocationsPer100), Modifier.weight(1f), hint = "/100w")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("CEFR vocabulary profile", style = PolyType.Caption)
                    Spacer(Modifier.height(4.dp))
                    CefrDistributionBar(l.cefrDistribution)
                    if (l.upgrades.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("C1/C2 upgrades (3 alternatives each)", style = PolyType.CardTitle)
                        Spacer(Modifier.height(6.dp))
                        l.upgrades.take(10).forEach { u ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(u.original, style = PolyType.Body.copy(fontSize = 13.sp, color = PolyColors.CrimsonAlert), modifier = Modifier.weight(0.35f))
                                Text("→", style = PolyType.Caption)
                                Spacer(Modifier.width(6.dp))
                                Text(u.replacements.take(3).joinToString(" · "), style = PolyType.Body.copy(fontSize = 13.sp, color = PolyColors.NeonMintSuccess), modifier = Modifier.weight(0.65f))
                            }
                        }
                    }
                    if (l.collocationHits.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Collocations used: ${l.collocationHits.take(8).joinToString()}", style = PolyType.Caption)
                    }
                }
            }
            // ---------------------------------------------------------- Card D: grammar
            item {
                val g = a.grammar
                CriterionCard(a.gra) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Complex", "${(g.complexSentenceRatio * 100).toInt()}%", Modifier.weight(1f), hint = "sentences")
                        StatTile("Error-free", "${(g.errorFreeClauseRatio * 100).toInt()}%", Modifier.weight(1f), hint = "clauses",
                            color = if (g.errorFreeClauseRatio >= 0.75) PolyColors.NeonMintSuccess else PolyColors.AmberWarning)
                        StatTile("Structures", "${g.distinctStructureCount}", Modifier.weight(1f), hint = "distinct")
                    }
                    if (g.structures.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            g.structures.map { it.type }.distinct().forEach { PillBadge(it, PolyColors.NeonMintSuccess) }
                        }
                    }
                    if (g.issues.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Corrections", style = PolyType.CardTitle)
                        Spacer(Modifier.height(6.dp))
                        g.issues.take(12).forEach { IssueRow(it) }
                    }
                }
            }
            // ---------------------------------------------------- Card D2: pronunciation
            item {
                val pr = a.pronunciation
                CriterionCard(a.p) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(pr.meanPhonemeScore, if (pr.method == "gop") "GOP mean" else "word match")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Word stress accuracy ${(pr.wordStressAccuracy * 100).toInt()}%", style = PolyType.Caption)
                            MeterBar(pr.wordStressAccuracy.toFloat(), 1f, PolyColors.ElectricTeal)
                            Text("Pitch range σF0 ${"%.1f".format(pr.pitchSdSemitones)} st ${if (pr.monotone) "· monotone (< 1.8 st)" else "· lively"}", style = PolyType.Caption.copy(color = if (pr.monotone) PolyColors.AmberWarning else PolyColors.NeonMintSuccess))
                            MeterBar(pr.pitchSdSemitones.toFloat(), 6f, if (pr.monotone) PolyColors.AmberWarning else PolyColors.NeonMintSuccess, target = 1.8f)
                        }
                    }
                    if (p.sample.pitch.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Intonation contour", style = PolyType.Caption)
                        PitchContourCanvas(pitchToArray(p.sample.pitch), height = 100.dp)
                    }
                    val words = p.sample.pronunciation?.words.orEmpty()
                    if (words.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Phoneme heatmap (tap a red/amber sound)", style = PolyType.Caption)
                        Spacer(Modifier.height(6.dp))
                        PhonemeHeatmap(words.sortedBy { it.score }.take(24), onPhoneme = { w, ph -> remediation = w to ph })
                    }
                }
            }
            // ---------------------------------------------------------- transcript
            item {
                FrostedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your transcript", style = PolyType.CardTitle, modifier = Modifier.weight(1f))
                        if (p.audio != null) {
                            IconCircleButton(if (playback.playing) PolyIcons.Stop else PolyIcons.Play,
                                { if (playback.playing) vm.stopAudio() else vm.playRecording() }, size = 36.dp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TranscriptText(p, playback) { t -> vm.playRecording(t) }
                    if (p.audio != null) Text("Tap any word to replay from that moment.", style = PolyType.Caption)
                }
            }
            // ---------------------------------------------------------- model answers
            item {
                ModelAnswerCard("Band 7.5 natural polish", "Your answer with grammar fixes and C1 vocabulary applied", a.naturalPolish, PolyColors.ElectricTeal,
                    onPlay = { vm.speak(a.naturalPolish, "Band 7.5 polish") }, onCopy = { copy(context, a.naturalPolish) })
            }
            item {
                ModelAnswerCard("Band 9 · C2 rewrite", "Inversion, cleft sentences, hedging and idiomatic range", a.band9Version, PolyColors.NeonMintSuccess,
                    onPlay = { vm.speak(a.band9Version, "Band 9 model") }, onCopy = { copy(context, a.band9Version) })
            }
            item {
                FrostedCard {
                    Text("Next steps", style = PolyType.CardTitle)
                    Spacer(Modifier.height(6.dp))
                    BulletList(a.criteria.flatMap { it.nextSteps }.distinct().take(8), color = PolyColors.AmberWarning)
                    Spacer(Modifier.height(12.dp))
                    if (vm.llmAvailable) {
                        GhostButton(deep ?: "Deep grammar check (offline AI)", vm::deepCheck, Modifier.fillMaxWidth(), icon = PolyIcons.Sparkle, enabled = deep == null)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Practise again", { nav.navigate(Routes.arena("drill", p.caseId ?: -1L)) }, Modifier.weight(1f), icon = PolyIcons.Replay)
                        GhostButton("ELSA lab", { nav.navigate(Routes.elsa()) }, icon = PolyIcons.Waveform)
                    }
                    p.stem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Saved as saved_results/$it.json · .csv${if (p.audio != null) " · .wav" else ""}", style = PolyType.Caption)
                    }
                }
            }
        }
    }
    remediation?.let { (w, ph) ->
        RemediationSheet(w.word, ph, onDismiss = { remediation = null }, onSpeak = { vm.speak(it, it) },
            onDrill = { remediation = null; nav.navigate(Routes.elsa()) })
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PolyLingua model answer", text))
}

@Composable
private fun CriterionCard(c: CriterionScore, content: @Composable () -> Unit) {
    FrostedCard(accent = PolyColors.forBand(c.band)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.criterion.title, style = PolyType.CardTitle)
                Text("raw %.2f".format(c.raw), style = PolyType.Caption)
            }
            Text("%.1f".format(c.band), style = PolyType.NumericMedium.copy(color = PolyColors.forBand(c.band)))
        }
        Spacer(Modifier.height(4.dp))
        Text(c.descriptor, style = PolyType.Caption.copy(color = PolyColors.PureIceWhite))
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(10.dp))
        Text("Evidence", style = PolyType.Caption)
        Spacer(Modifier.height(4.dp))
        BulletList(c.evidence, color = PolyColors.MutedSlateText)
    }
}

@Composable
private fun IssueRow(issue: GrammarIssue) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(PolyColors.ObsidianSlate).padding(10.dp)) {
        RedGreenDiffText(WordDiff.diff(issue.excerpt, issue.suggestion))
        Spacer(Modifier.height(4.dp))
        Text((if (issue.source == "llm") "🤖 " else "") + issue.explanation, style = PolyType.Caption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptText(p: DashboardPayload, playback: PlaybackState, onWord: (Double) -> Unit) {
    val scores = p.sample.pronunciation?.words.orEmpty()
    val words = p.sample.words
    if (words.isEmpty()) {
        Text(p.sample.transcript.ifBlank { "(empty)" }, style = PolyType.Body)
        return
    }
    val playingFrom = if (playback.playing && playback.label == "Your recording") playback.positionSec else -1.0
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        words.forEach { w ->
            val score = scores.firstOrNull { kotlin.math.abs(it.startSec - w.startSec) < 0.35 && it.word.equals(w.word, true) }?.score
            val color = score?.let { PolyColors.forScore(it.toDouble()) } ?: PolyColors.PureIceWhite
            val active = playingFrom >= 0 && playingFrom >= w.startSec && playingFrom < w.endSec + 0.05
            Text(
                buildAnnotatedString { withStyle(SpanStyle(color = color, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)) { append(w.word) } },
                style = PolyType.Body.copy(fontSize = 15.sp),
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (active) PolyColors.ElectricTeal.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onWord(w.startSec) }.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun ModelAnswerCard(title: String, subtitle: String, text: String, color: androidx.compose.ui.graphics.Color, onPlay: () -> Unit, onCopy: () -> Unit) {
    FrostedCard(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = PolyType.CardTitle.copy(color = color))
                Text(subtitle, style = PolyType.Caption)
            }
            IconCircleButton(PolyIcons.Play, onPlay, size = 38.dp, color = color)
            Spacer(Modifier.width(6.dp))
            IconCircleButton(PolyIcons.Cards, onCopy, size = 38.dp, contentDescription = "Copy")
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = PolyType.Body.copy(fontSize = 14.sp, lineHeight = 21.sp))
    }
}
