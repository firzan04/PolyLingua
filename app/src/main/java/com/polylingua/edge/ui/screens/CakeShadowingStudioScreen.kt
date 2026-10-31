package com.polylingua.edge.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.polylingua.edge.ui.components.AbLoopTimeline
import com.polylingua.edge.ui.components.Banner
import com.polylingua.edge.ui.components.ChoiceChips
import com.polylingua.edge.ui.components.CountdownNumeral
import com.polylingua.edge.ui.components.LabeledProgress
import com.polylingua.edge.ui.components.WaveformOverlayCanvas
import com.polylingua.edge.ui.components.FrostedCard
import com.polylingua.edge.ui.components.GhostButton
import com.polylingua.edge.ui.components.IconCircleButton
import com.polylingua.edge.ui.components.PadlockBadge
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PitchContourCanvas
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.components.PrimaryButton
import com.polylingua.edge.ui.components.PulsingMicButton
import com.polylingua.edge.ui.components.ScoreRing
import com.polylingua.edge.ui.components.ScreenHeader
import com.polylingua.edge.ui.components.ScreenPadding
import com.polylingua.edge.ui.components.SectionHeader
import com.polylingua.edge.ui.components.SegmentedTabs
import com.polylingua.edge.ui.components.StatTile
import com.polylingua.edge.ui.components.pitchToArray
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyType

private data class HubLink(val title: String, val note: String, val url: String)

private val CLOUD_HUB = listOf(
    HubLink("BBC Learning English · 6 Minute English", "Free weekly discussions with transcripts", "https://www.bbc.co.uk/learningenglish/english/features/6-minute-english"),
    HubLink("VOA Learning English", "Slow, clear American news with subtitles", "https://learningenglish.voanews.com/"),
    HubLink("British Council LearnEnglish podcasts", "Graded listening with scripts", "https://learnenglish.britishcouncil.org/general-english/audio-zone"),
    HubLink("TED-Ed", "Short animated lessons; download subtitles as .srt", "https://ed.ted.com/"),
    HubLink("LibriVox", "Public-domain audiobooks for long-form shadowing", "https://librivox.org/"),
)

@Composable
fun CakeShadowingStudioScreen(nav: NavHostController, vm: CakeViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val library by vm.mediaLibrary.collectAsStateWithLifecycle()
    val attempts by vm.recentAttempts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val net by vm.network.collectAsStateWithLifecycle()
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importMedia) }
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.attachSubtitles(u, false) } }
    val trPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.attachSubtitles(u, true) } }
    val subtitleMimes = arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*")

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Cake Shadowing Studio", "Dual subtitles · A-B loop · WSOLA speeds · mastery score", onBack = { nav.popBackStack() })
        SegmentedTabs(listOf("Built-in lines", "My videos & audio"), st.source, vm::setSource, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            st.message?.let { msg -> item { Banner(msg, PolyColors.AmberWarning) { GhostButton("OK", vm::consumeMessage) } } }
            if (st.source == 1) {
                item {
                    FrostedCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Import video / audio", { mediaPicker.launch(arrayOf("video/*", "audio/*")) }, Modifier.weight(1f), icon = PolyIcons.Film)
                        }
                        if (library.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            library.forEach { m ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(if (st.media?.id == m.id) PolyColors.ElectricTeal.copy(alpha = 0.15f) else PolyColors.ObsidianSlate)
                                        .clickable { vm.openMedia(m) }.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.title, style = PolyType.Body.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${m.durationMs / 60000}:%02d · ${if (m.subtitleUri != null) "EN subs" else "no subs"}${if (m.translationUri != null) " + ID" else ""}".format((m.durationMs / 1000) % 60), style = PolyType.Caption)
                                    }
                                    IconCircleButton(PolyIcons.Trash, { vm.deleteMedia(m) }, size = 30.dp, color = PolyColors.CrimsonAlert)
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
                if (st.media != null) {
                    item {
                        FrostedCard(padding = 8.dp) {
                            AndroidView(
                                factory = { ctx -> PlayerView(ctx).apply { player = vm.player; useController = false; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton(if (st.media?.subtitleUri != null) "Replace EN subtitles" else "+ English .srt/.vtt", { subPicker.launch(subtitleMimes) }, Modifier.weight(1f))
                                GhostButton(if (st.media?.translationUri != null) "Replace ID subs" else "+ Indonesian subs", { trPicker.launch(subtitleMimes) }, Modifier.weight(1f), color = PolyColors.AmberWarning)
                            }
                            Spacer(Modifier.height(6.dp))
                            val t = st.transcribing
                            if (t != null) {
                                LabeledProgress("Transcribing offline… ${(t * 100).toInt()} %", t)
                            } else {
                                PrimaryButton(
                                    if (st.cues.isEmpty()) "Auto-transcribe offline (creates the lesson)" else "Re-transcribe offline",
                                    vm::autoTranscribe, Modifier.fillMaxWidth(), icon = PolyIcons.Sparkle,
                                    color = if (st.cues.isEmpty()) PolyColors.ElectricTeal else PolyColors.MutedSlateText,
                                )
                                Text("Speech recognition + word timing on this phone (first 20 minutes). Works for .mp4, .mkv, .mp3, .wav, .m4a.", style = PolyType.Caption)
                            }
                        }
                    }
                }
            } else {
                item {
                    FrostedCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Line ${st.sentenceIndex + 1}/${st.sentences.size}", style = PolyType.Caption, modifier = Modifier.weight(1f))
                            st.sentences.getOrNull(st.sentenceIndex)?.let { PillBadge("${it.cefr} · ${it.topic}", PolyColors.MutedSlateText) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("◀ Previous", vm::prevSentence, Modifier.weight(1f))
                            GhostButton("Next ▶", vm::nextSentence, Modifier.weight(1f))
                        }
                    }
                }
            }
            st.currentCue?.let { cue ->
                item {
                    FrostedCard(accent = PolyColors.ElectricTeal) {
                        Text(cue.text, style = PolyType.Body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold))
                        if (st.showIpa && cue.ipa.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(cue.ipa, style = PolyType.Ipa) }
                        if (st.showTranslation && cue.translation.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("🇮🇩 ${cue.translation}", style = PolyType.Body.copy(color = PolyColors.AmberWarning, fontSize = 14.sp), modifier = Modifier.clickable { vm.speakTranslationHint() })
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton(if (st.showIpa) "Hide IPA" else "Show IPA", vm::toggleIpa, color = PolyColors.ElectricTeal)
                            GhostButton(if (st.showTranslation) "Hide ID" else "Show ID", vm::toggleTranslation, color = PolyColors.AmberWarning)
                        }
                    }
                }
            }
            item { TransportCard(st, vm) }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val sequence = st.shadowStage == "Listen…" || st.countdown != null
                    if (!st.recording && !st.analysing) {
                        if (sequence) {
                            GhostButton("Cancel", vm::cancelShadowSequence, color = PolyColors.MutedSlateText)
                        } else {
                            PrimaryButton("▶ Shadow this line", vm::shadowThisLine, Modifier.fillMaxWidth(), icon = PolyIcons.Loop, enabled = st.currentCue != null)
                            Text("Plays the native line, counts 3-2-1, then records you.", style = PolyType.Caption)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    st.countdown?.let { n -> CountdownNumeral(n); Spacer(Modifier.height(8.dp)) }
                    st.shadowStage?.let { stage -> Text(stage, style = PolyType.CardTitle.copy(color = if (stage == "Speak!") PolyColors.CrimsonAlert else PolyColors.AmberWarning)) }
                    PulsingMicButton(st.recording, live.levelDb, vm::toggleShadow, enabled = !st.analysing && st.currentCue != null && !sequence)
                    Text(if (st.recording) "Shadowing… tap to finish" else "Or tap the mic to shadow without the countdown", style = PolyType.Caption)
                    if (st.recording) Text(live.transcript.text, style = PolyType.Body, maxLines = 3)
                    if (st.analysing) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = PolyColors.ElectricTeal, modifier = Modifier.padding(end = 8.dp))
                        Text("Scoring phonetics, intonation and rhythm…", style = PolyType.Caption)
                    }
                }
            }
            st.outcome?.let { o ->
                item {
                    FrostedCard(accent = PolyColors.forScore(o.score.mastery)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ScoreRing(o.score.mastery, "mastery")
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.score.grade, style = PolyType.CardTitle.copy(color = PolyColors.forScore(o.score.mastery)))
                                Text("Heard: \"${o.recognized}\"", style = PolyType.Caption, maxLines = 3)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Phonetic 40%", "${o.score.phonemeAccuracy.toInt()}", Modifier.weight(1f), color = PolyColors.forScore(o.score.phonemeAccuracy))
                            StatTile("Intonation 35%", "${o.score.intonationSimilarity.toInt()}", Modifier.weight(1f), color = PolyColors.forScore(o.score.intonationSimilarity))
                            StatTile("Rhythm 25%", "${o.score.rhythmSync.toInt()}", Modifier.weight(1f), color = PolyColors.forScore(o.score.rhythmSync))
                        }
                        val ref = st.refEnvelope
                        val mine = st.userEnvelope
                        if (ref != null && mine != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Waveform: you (teal) over the native line (amber)", style = PolyType.Caption)
                            WaveformOverlayCanvas(ref, mine)
                        }
                        if (o.userPitch.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Pitch: you (teal) vs native (amber)", style = PolyType.Caption)
                            PitchContourCanvas(pitchToArray(o.userPitch), reference = if (o.referencePitch.isNotEmpty()) pitchToArray(o.referencePitch) else null)
                        }
                    }
                }
            }
            if (st.source == 1 && st.cues.isNotEmpty()) {
                item { SectionHeader("Subtitle lines", "Tap a line to loop it (A-B set automatically)") }
                itemsIndexed(st.cues, key = { _, c -> c.index }) { i, c ->
                    val active = i == st.cueIndex
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (active) PolyColors.ElectricTeal.copy(alpha = 0.14f) else PolyColors.ObsidianSlate)
                            .border(1.dp, if (active) PolyColors.ElectricTeal else PolyColors.FrostedBorder, RoundedCornerShape(10.dp))
                            .clickable { vm.selectCue(i) }.padding(10.dp),
                    ) {
                        Text("%d:%02d".format(c.startMs / 60000, (c.startMs / 1000) % 60), style = PolyType.Numeric.copy(fontSize = 11.sp, color = PolyColors.MutedSlateText))
                        Text(c.text, style = PolyType.Body.copy(fontSize = 14.sp))
                        if (st.showTranslation && c.translation.isNotBlank()) Text(c.translation, style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
                    }
                }
            }
            if (attempts.isNotEmpty()) {
                item {
                    FrostedCard {
                        Text("Recent takes", style = PolyType.CardTitle)
                        Spacer(Modifier.height(6.dp))
                        attempts.take(6).forEach { a ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(a.sentence, style = PolyType.Caption.copy(color = PolyColors.PureIceWhite), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${a.mastery.toInt()} @${a.tempo}×", style = PolyType.Numeric.copy(fontSize = 12.sp, color = PolyColors.forScore(a.mastery)))
                            }
                        }
                    }
                }
            }
            item {
                FrostedCard(accent = if (net.online) PolyColors.NeonMintSuccess else PolyColors.CrimsonAlert) {
                    Text("Cloud content hub", style = PolyType.CardTitle)
                    Text("Free, legal listening sources - download clips + subtitles, then import them above.", style = PolyType.Caption)
                    Spacer(Modifier.height(8.dp))
                    if (!net.online) {
                        PadlockBadge()
                    } else {
                        CLOUD_HUB.forEach { link ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } catch (_: ActivityNotFoundException) {}
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(link.title, style = PolyType.Body.copy(fontSize = 13.sp))
                                    Text(link.note, style = PolyType.Caption)
                                }
                                androidx.compose.material3.Icon(PolyIcons.ChevronRight, null, tint = PolyColors.MutedSlateText)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportCard(st: CakeUiState, vm: CakeViewModel) {
    FrostedCard {
        AbLoopTimeline(st.durationMs, st.positionMs, st.loopA, st.loopB, vm::seekTo)
        Spacer(Modifier.height(4.dp))
        Row {
            Text("%d:%02d".format(st.positionMs / 60000, (st.positionMs / 1000) % 60), style = PolyType.Numeric.copy(fontSize = 11.sp))
            Spacer(Modifier.weight(1f))
            Text("%d:%02d".format(st.durationMs / 60000, (st.durationMs / 1000) % 60), style = PolyType.Numeric.copy(fontSize = 11.sp, color = PolyColors.MutedSlateText))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconCircleButton(if (st.playing) PolyIcons.Pause else PolyIcons.Play, vm::togglePlay, size = 52.dp, color = PolyColors.AmazeVoidBlack, background = PolyColors.ElectricTeal)
            GhostButton("A", vm::markA, color = if (st.loopA != null) PolyColors.AmberWarning else PolyColors.MutedSlateText)
            GhostButton("B", vm::markB, color = if (st.loopB != null) PolyColors.AmberWarning else PolyColors.MutedSlateText)
            GhostButton(if (st.loopOn) "Loop ON" else "Loop", vm::toggleLoop, icon = PolyIcons.Loop, color = if (st.loopOn) PolyColors.NeonMintSuccess else PolyColors.MutedSlateText)
            if (st.loopA != null || st.loopB != null) IconCircleButton(PolyIcons.Close, vm::clearLoop, size = 32.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(if (st.source == 0) "Speed (WSOLA time-stretch, pitch preserved)" else "Speed (pitch-preserving time-stretch)", style = PolyType.Caption)
        Spacer(Modifier.height(4.dp))
        ChoiceChips(
            CakeViewModel.SPEEDS.map { "%.2f×".format(it) },
            setOf(CakeViewModel.SPEEDS.indexOf(st.speed).coerceAtLeast(0)),
            { vm.setSpeed(CakeViewModel.SPEEDS[it]) },
        )
    }
}
