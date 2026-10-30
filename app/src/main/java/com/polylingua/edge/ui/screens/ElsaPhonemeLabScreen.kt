package com.polylingua.edge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.model.PhonemeScore
import com.polylingua.edge.domain.model.WordPronunciation
import com.polylingua.edge.ui.components.Banner
import com.polylingua.edge.ui.components.ChoiceChips
import com.polylingua.edge.ui.components.FrostedCard
import com.polylingua.edge.ui.components.GhostButton
import com.polylingua.edge.ui.components.IconCircleButton
import com.polylingua.edge.ui.components.PhonemeHeatmap
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PitchContourCanvas
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.components.PrimaryButton
import com.polylingua.edge.ui.components.PulsingMicButton
import com.polylingua.edge.ui.components.RemediationSheet
import com.polylingua.edge.ui.components.ScoreRing
import com.polylingua.edge.ui.components.ScreenHeader
import com.polylingua.edge.ui.components.ScreenPadding
import com.polylingua.edge.ui.components.SectionHeader
import com.polylingua.edge.ui.components.SegmentedTabs
import com.polylingua.edge.ui.components.pitchToArray
import com.polylingua.edge.ui.navigation.Routes
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ElsaPhonemeLabScreen(nav: NavHostController, vm: ElsaViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<Pair<String, PhonemeScore>?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("ELSA Phoneme Lab", "GOP phoneme scoring · stress · intonation", onBack = { nav.popBackStack() })
        SegmentedTabs(listOf("Drill", "Sounds", "Ear", "Roleplay"), tab, { tab = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> item { DrillTab(vm) { w, ph -> sheet = w.word to ph } }
                1 -> item { SoundsTab(vm) { ph -> sheet = "" to ph } }
                2 -> item { EarTab(vm) }
                else -> item { RoleplayTab(vm, nav) { w, ph -> sheet = w.word to ph } }
            }
        }
    }
    sheet?.let { (word, ph) ->
        RemediationSheet(word, ph, onDismiss = { sheet = null }, onSpeak = vm::speakWord, onDrill = { a -> sheet = null; vm.drillPhoneme(a); tab = 0 })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrillTab(vm: ElsaViewModel, onPhoneme: (WordPronunciation, PhonemeScore) -> Unit) {
    val d by vm.drill.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val weakest by vm.weakest.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FrostedCard(accent = PolyColors.ElectricTeal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Read aloud", style = PolyType.Caption, modifier = Modifier.weight(1f))
                d.sourceCase?.let { PillBadge("${it.cefr} · ${it.topic}", PolyColors.MutedSlateText) }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.words.forEachIndexed { i, w ->
                    val scored = d.outcome?.pronunciation?.words?.getOrNull(i)
                    val color = scored?.let { PolyColors.forScore(it.score.toDouble()) } ?: PolyColors.PureIceWhite
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.speakWord(w.word) }.padding(2.dp)) {
                        Text(w.word, style = PolyType.Body.copy(fontSize = 20.sp, color = color))
                        Text(w.ipa, style = PolyType.Ipa.copy(fontSize = 12.sp))
                    }
                }
            }
            if (d.translation.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("🇮🇩 ${d.translation}", style = PolyType.Caption)
            }
            if (d.stressHints.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Stress: " + d.stressHints.joinToString(" · "), style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Listen", vm::listen, icon = PolyIcons.Speaker)
                ChoiceChips(listOf("0.75×", "1.0×"), setOf(if (d.tempo < 1.0) 0 else 1), { vm.setTempo(if (it == 0) 0.75 else 1.0) })
                Spacer(Modifier.weight(1f))
                IconCircleButton(PolyIcons.Shuffle, vm::nextDrill, size = 40.dp, contentDescription = "Next sentence")
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PulsingMicButton(d.recording, live.levelDb, vm::toggleRecord, enabled = !d.analysing)
        }
        if (d.recording) {
            PitchContourCanvas(live.f0Recent, height = 80.dp)
            Text(live.transcript.text.ifBlank { "Listening…" }, style = PolyType.Body, modifier = Modifier.fillMaxWidth())
        }
        if (d.analysing) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = PolyColors.ElectricTeal, modifier = Modifier.padding(end = 10.dp))
            Text("Forced alignment + GOP…", style = PolyType.Caption)
        }
        d.error?.let { Banner(it, PolyColors.CrimsonAlert) }
        d.outcome?.let { o ->
            FrostedCard(accent = PolyColors.forScore(o.pronunciation?.meanScore ?: 0.0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(o.pronunciation?.meanScore ?: 0.0, if (o.pronunciation?.method == "gop") "phoneme" else "word")
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Attempt ${d.attempts}", style = PolyType.CardTitle)
                        Text("Heard: \"${o.recognized}\"", style = PolyType.Caption)
                        o.intonation?.let { Text("Intonation match ${it.similarityPercent.toInt()}% (Δ ${"%.1f".format(it.meanAbsSemitoneDistance)} st)", style = PolyType.Caption.copy(color = PolyColors.AmberWarning)) }
                    }
                }
                o.pronunciation?.let { pr ->
                    Spacer(Modifier.height(10.dp))
                    PhonemeHeatmap(pr.words, onPhoneme)
                }
                if (o.userPitch.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Intonation: you (teal) vs model (amber)", style = PolyType.Caption)
                    PitchContourCanvas(pitchToArray(o.userPitch), reference = if (o.referencePitch.isNotEmpty()) pitchToArray(o.referencePitch) else null)
                }
            }
        }
        if (weakest.isNotEmpty()) {
            FrostedCard {
                Text("Your weakest sounds", style = PolyType.CardTitle)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    weakest.forEach { s ->
                        val ipa = PhoneticArticulationDatabase.ipaFor(s.arpabet)
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(PolyColors.forScore(s.meanScore).copy(alpha = 0.15f))
                                .border(1.dp, PolyColors.forScore(s.meanScore), RoundedCornerShape(10.dp)).clickable { vm.drillPhoneme(s.arpabet) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) { Text("/$ipa/ ${s.meanScore.toInt()}", style = PolyType.Ipa.copy(color = PolyColors.forScore(s.meanScore))) }
                    }
                }
            }
        }
        FrostedCard {
            Text("Practise your own sentence", style = PolyType.Caption)
            OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder))
            Spacer(Modifier.height(6.dp))
            GhostButton("Use this sentence", { vm.setCustomTarget(custom) }, icon = PolyIcons.Edit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundsTab(vm: ElsaViewModel, onOpen: (PhonemeScore) -> Unit) {
    val groups = PhoneticArticulationDatabase.PHONEMES.groupBy { it.kind }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { (kind, list) ->
            FrostedCard {
                SectionHeader(kind.name.lowercase().replaceFirstChar { it.uppercase() } + "s", "${list.size} sounds · tap for articulation guide")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { p ->
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(PolyColors.ObsidianSlate).border(1.dp, PolyColors.FrostedBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    onOpen(PhonemeScore(p.arpabet ?: p.ipa, p.ipa, -1, 0.0, 0.0, 0.0, null, 0))
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("/${p.ipa}/", style = PolyType.Ipa.copy(fontSize = 16.sp))
                        }
                    }
                }
            }
        }
        Text("RP-only centring diphthongs and /ɒ/ have no ARPAbet symbol; they are explained but not GOP-scored.", style = PolyType.Caption)
    }
}

@Composable
private fun EarTab(vm: ElsaViewModel) {
    val s by vm.ear.collectAsStateWithLifecycle()
    FrostedCard(accent = PolyColors.AmberWarning) {
        Text("Minimal-pair ear trainer", style = PolyType.CardTitle)
        Text("Listen, then tap the word you heard. Perception comes before production.", style = PolyType.Caption)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Play word", vm::playEar, icon = PolyIcons.Play)
            Spacer(Modifier.weight(1f))
            Text("${s.correct}/${s.total}", style = PolyType.NumericMedium)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(s.pair.first to true, s.pair.second to false).forEach { (word, first) ->
                val chosen = s.chosen >= 0 && (s.chosen == 0) == first
                val isAnswer = first == s.answerIsFirst
                val color = when {
                    s.chosen < 0 -> PolyColors.ElectricTeal
                    isAnswer -> PolyColors.NeonMintSuccess
                    chosen -> PolyColors.CrimsonAlert
                    else -> PolyColors.MutedSlateText
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).border(1.5.dp, color, RoundedCornerShape(14.dp))
                        .clickable { vm.answerEar(first) }.padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(word, style = PolyType.Section.copy(color = color)) }
            }
        }
        if (s.chosen >= 0) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Hear ${s.pair.first}", { vm.speakWord(s.pair.first) }, icon = PolyIcons.Speaker)
                GhostButton("Hear ${s.pair.second}", { vm.speakWord(s.pair.second) }, icon = PolyIcons.Speaker)
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Next pair", vm::nextEarItem, Modifier.fillMaxWidth(), icon = PolyIcons.Forward)
        }
    }
}

@Composable
private fun RoleplayTab(vm: ElsaViewModel, nav: NavHostController, onPhoneme: (WordPronunciation, PhonemeScore) -> Unit) {
    val s by vm.roleplay.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FrostedCard(accent = PolyColors.DeepIndigo) {
            Text("Roleplay coach", style = PolyType.CardTitle)
            Spacer(Modifier.height(6.dp))
            Text(s.prompt?.prompt ?: "Loading a scenario…", style = PolyType.Body.copy(fontSize = 16.sp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Hear the coach", vm::playRoleplayPrompt, icon = PolyIcons.Speaker)
                GhostButton("New scenario", vm::nextRoleplay, icon = PolyIcons.Shuffle)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PulsingMicButton(s.recording, live.levelDb, vm::toggleRoleplayRecord, enabled = !s.analysing)
        }
        if (s.recording) Text(live.transcript.text.ifBlank { "Respond naturally for 20-40 seconds…" }, style = PolyType.Body)
        if (s.analysing) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = PolyColors.ElectricTeal, modifier = Modifier.padding(end = 10.dp))
            Text("Scoring pronunciation and fluency…", style = PolyType.Caption)
        }
        s.error?.let { Banner(it, PolyColors.CrimsonAlert) }
        s.result?.let { r ->
            val a = r.assessment
            FrostedCard(accent = PolyColors.forBand(a.p.band)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pronunciation ${"%.1f".format(a.p.band)} · Fluency ${"%.1f".format(a.fc.band)}", style = PolyType.CardTitle)
                        Text("${a.fluency.wpm.toInt()} WPM · ${a.fluency.wordCount} words · overall ${"%.1f".format(a.overallBand)} (${a.cefr})", style = PolyType.Caption)
                    }
                    GhostButton("Full report", { nav.navigate(Routes.DASHBOARD) }, icon = PolyIcons.Chart)
                }
                r.sample.pronunciation?.let { pr ->
                    Spacer(Modifier.height(10.dp))
                    Text("Words to polish", style = PolyType.Caption)
                    Spacer(Modifier.height(6.dp))
                    PhonemeHeatmap(pr.words.sortedBy { it.score }.take(8), onPhoneme)
                }
            }
        }
    }
}
