package com.polylingua.edge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.polylingua.edge.core.UserNotices
import com.polylingua.edge.media.VideoInterviewRecorder
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.polylingua.edge.speech.AsrBackend
import com.polylingua.edge.ui.components.Banner
import com.polylingua.edge.ui.components.CountdownNumeral
import com.polylingua.edge.ui.components.CountdownRing
import com.polylingua.edge.ui.components.FrostedCard
import com.polylingua.edge.ui.components.GhostButton
import com.polylingua.edge.ui.components.LiveWaveformCanvas
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PitchContourCanvas
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.components.PrimaryButton
import com.polylingua.edge.ui.components.PulsingMicButton
import com.polylingua.edge.ui.components.ScreenHeader
import com.polylingua.edge.ui.components.WpmGauge
import com.polylingua.edge.ui.navigation.Routes
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyType

@Composable
fun StimulerIeltsArenaScreen(nav: NavHostController, vm: ArenaViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val wave by vm.waveform.collectAsStateWithLifecycle()
    val videoStatus by vm.videoStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasCamera = { ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setVideoMode(granted)
        if (!granted) UserNotices.post("Camera permission is needed for the video interview.", UserNotices.Kind.WARNING)
    }
    val onVideoToggle: (Boolean) -> Unit = { on ->
        when {
            !on -> vm.setVideoMode(false)
            hasCamera() -> vm.setVideoMode(true)
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(Unit) {
        // Restore the saved preference only when the permission is still granted.
        if (st.videoMode) { if (hasCamera()) vm.openCamera() else vm.setVideoMode(false) }
    }
    LaunchedEffect(st.phase) {
        if (st.phase == ArenaPhase.DONE) {
            nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.HOME) }
        }
    }
    BackHandler(enabled = st.phase in setOf(ArenaPhase.SPEAKING, ArenaPhase.PREP, ArenaPhase.COUNTDOWN, ArenaPhase.EXAMINER, ArenaPhase.BETWEEN)) {
        vm.abort()
        nav.popBackStack()
    }
    val q = st.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (st.mock) "Full IELTS Mock" else "60-second Drill",
            subtitle = q?.let { "Q${st.index + 1}/${st.questions.size} · Part ${it.part} · ${it.case.cefr} · ${it.case.topic}" } ?: "Preparing questions…",
            onBack = { vm.abort(); nav.popBackStack() },
        ) {
            if (st.mock) PillBadge("${st.answered}/${st.questions.size} DONE", PolyColors.NeonMintSuccess)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (st.backend == AsrBackend.ANDROID_SYSTEM) {
                Banner("Limited mode: Android recogniser (no audio analysis, GOP or pitch). Install the offline speech pack for full scoring.", PolyColors.AmberWarning) {
                    GhostButton("Install", { nav.navigate(Routes.SETUP) }, color = PolyColors.AmberWarning)
                }
            }
            val showCamera = st.videoMode && st.phase !in setOf(ArenaPhase.LOADING, ArenaPhase.DONE, ArenaPhase.ERROR) &&
                videoStatus.phase != VideoInterviewRecorder.Phase.CLOSED
            if (showCamera) CameraCard(vm, videoStatus)
            if (q != null && st.phase != ArenaPhase.ANALYSING) QuestionCard(q, st.phase == ArenaPhase.PREP || st.phase == ArenaPhase.READY)
            AnimatedContent(st.phase, label = "phase") { phase ->
                when (phase) {
                    ArenaPhase.LOADING -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PolyColors.ElectricTeal) }
                    ArenaPhase.READY -> ReadyPanel(st, vm, onVideoToggle)
                    ArenaPhase.COUNTDOWN -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CountdownNumeral(st.countdown) }
                    ArenaPhase.EXAMINER -> FrostedCard(accent = PolyColors.AmberWarning) {
                        Text("🎙️ The examiner is reading the question…", style = PolyType.CardTitle)
                        Text("Listen carefully - you may start thinking about your answer.", style = PolyType.Caption)
                    }
                    ArenaPhase.PREP -> PrepPanel(st, vm)
                    ArenaPhase.SPEAKING -> SpeakingPanel(st, live, wave, vm)
                    ArenaPhase.BETWEEN -> FrostedCard(accent = PolyColors.NeonMintSuccess) {
                        Text("✓ Answer captured", style = PolyType.CardTitle.copy(color = PolyColors.NeonMintSuccess))
                        Text("Next question coming up…", style = PolyType.Caption)
                    }
                    ArenaPhase.ANALYSING -> FrostedCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = PolyColors.ElectricTeal, modifier = Modifier.padding(end = 14.dp))
                            Column {
                                Text("Analysing on-device", style = PolyType.CardTitle)
                                Text(st.progressText, style = PolyType.Caption)
                            }
                        }
                    }
                    ArenaPhase.DONE -> Text("Opening your diagnostic dashboard…", style = PolyType.Body)
                    ArenaPhase.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Banner(st.error ?: "Something went wrong", PolyColors.CrimsonAlert)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Models & settings", { nav.navigate(Routes.SETUP) })
                            GhostButton("Back", { nav.popBackStack() }, color = PolyColors.MutedSlateText)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QuestionCard(q: ArenaQuestion, showNotes: Boolean) {
    FrostedCard(accent = PolyColors.ElectricTeal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillBadge("PART ${q.part}", PolyColors.ElectricTeal, filled = true)
            Spacer(Modifier.width(6.dp))
            PillBadge("${q.speakSec}s TALK${if (q.prepSec > 0) " · ${q.prepSec}s PREP" else ""}", PolyColors.MutedSlateText)
            Spacer(Modifier.width(6.dp))
            PillBadge("BAND ${q.case.targetBand}", PolyColors.forBand(q.case.targetBand))
        }
        Spacer(Modifier.height(10.dp))
        Text(q.case.prompt, style = PolyType.Body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold))
        if (showNotes && q.case.colH.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("💡 ${q.case.colH}", style = PolyType.Caption)
        }
    }
}

@Composable
private fun CameraCard(vm: ArenaViewModel, status: VideoInterviewRecorder.Status) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView-backed preview behaves inside scrolling Compose layouts.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(previewView) {
        vm.video.attachPreview(previewView)
        onDispose { vm.video.detachPreview(previewView) }
    }
    val recording = status.phase == VideoInterviewRecorder.Phase.RECORDING
    FrostedCard(padding = 8.dp, accent = if (recording) PolyColors.CrimsonAlert else PolyColors.ElectricTeal) {
        Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val (label, color) = when (status.phase) {
                VideoInterviewRecorder.Phase.RECORDING -> "● REC %d:%02d".format(status.elapsedSec.toInt() / 60, status.elapsedSec.toInt() % 60) to PolyColors.CrimsonAlert
                VideoInterviewRecorder.Phase.READY -> "CAMERA READY" to PolyColors.NeonMintSuccess
                VideoInterviewRecorder.Phase.OPENING -> "OPENING CAMERA…" to PolyColors.AmberWarning
                VideoInterviewRecorder.Phase.FINALIZING -> "SAVING VIDEO…" to PolyColors.AmberWarning
                VideoInterviewRecorder.Phase.ERROR -> "CAMERA ERROR" to PolyColors.CrimsonAlert
                VideoInterviewRecorder.Phase.CLOSED -> "CAMERA OFF" to PolyColors.MutedSlateText
            }
            PillBadge(label, color, Modifier.align(Alignment.TopStart).padding(8.dp), filled = true)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (status.phase == VideoInterviewRecorder.Phase.ERROR) status.message
            else "Look into the lens as if it were the examiner. The video (with the examiner's questions and your answers) and an eye-contact report are saved with your results.",
            style = PolyType.Caption,
        )
    }
}

@Composable
private fun ReadyPanel(st: ArenaUiState, vm: ArenaViewModel, onVideoToggle: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FrostedCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🎥 Video interview", style = PolyType.Body)
                    Text("Front camera records you (1080p): watch yourself back and get an eye-contact, expression and head-movement report", style = PolyType.Caption)
                }
                Switch(st.videoMode, onVideoToggle, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.CrimsonAlert))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Examiner voice", style = PolyType.Body)
                    Text("The question is read aloud before you answer", style = PolyType.Caption)
                }
                Switch(st.examinerVoice, vm::setExaminerVoice, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (st.mock) "${st.questions.size} questions · about ${st.questions.sumOf { it.speakSec + it.prepSec } / 60 + 2} minutes. Answers are scored together like the real test."
                else "Speak for the full minute: extend with a reason, an example and a short conclusion.",
                style = PolyType.Caption,
            )
            Text("Speech engine: ${st.engineSummary.ifBlank { st.backend.label }}", style = PolyType.Caption.copy(color = PolyColors.ElectricTeal))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Start (3-s countdown)", vm::start, Modifier.weight(1f), icon = PolyIcons.Play)
            GhostButton("New question", vm::reshuffle, icon = PolyIcons.Shuffle)
        }
    }
}

@Composable
private fun PrepPanel(st: ArenaUiState, vm: ArenaViewModel) {
    FrostedCard(accent = PolyColors.AmberWarning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountdownRing(st.prepLeft / 60f, "${st.prepLeft}s", color = PolyColors.AmberWarning)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Preparation time", style = PolyType.CardTitle)
                Text("Jot down keywords for each bullet point. You will then speak for up to 2 minutes.", style = PolyType.Caption)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = st.notes, onValueChange = vm::setNotes, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            placeholder = { Text("Notes (only you can see these)", style = PolyType.Caption) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.AmberWarning, unfocusedBorderColor = PolyColors.FrostedBorder),
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton("I'm ready - start speaking", vm::skipPrep, Modifier.fillMaxWidth(), icon = PolyIcons.Mic, color = PolyColors.AmberWarning)
    }
}

@Composable
private fun SpeakingPanel(st: ArenaUiState, live: com.polylingua.edge.speech.LiveCaptureState, wave: FloatArray, vm: ArenaViewModel) {
    val total = st.current?.speakSec ?: 60
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (st.current?.part == 2 && st.notes.isNotBlank()) {
            FrostedCard(padding = 10.dp) { Text("📝 ${st.notes}", style = PolyType.Caption.copy(color = PolyColors.PureIceWhite)) }
        }
        FrostedCard(accent = PolyColors.CrimsonAlert) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountdownRing(st.speakLeft / total.toFloat(), "%d:%02d".format(st.speakLeft / 60, st.speakLeft % 60))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("● REC", style = PolyType.Pill.copy(color = PolyColors.CrimsonAlert, fontSize = 12.sp))
                    Text(if (live.transcript.speaking) "Speech detected" else "Listening…", style = PolyType.Caption)
                    Text("%.1f dBFS".format(live.levelDb), style = PolyType.Numeric.copy(fontSize = 11.sp, color = PolyColors.MutedSlateText))
                }
                WpmGauge(live.liveWpm)
            }
            Spacer(Modifier.height(10.dp))
            LiveWaveformCanvas(wave, active = true)
            Spacer(Modifier.height(8.dp))
            Text("Intonation (F0, semitones)", style = PolyType.Caption)
            PitchContourCanvas(live.f0Recent, height = 90.dp)
        }
        FrostedCard {
            Text("Live transcript", style = PolyType.Caption)
            Spacer(Modifier.height(4.dp))
            Text(
                live.transcript.text.ifBlank { "Start speaking - captions appear here in real time." },
                style = PolyType.Body.copy(fontSize = 16.sp, lineHeight = 23.sp),
                maxLines = 8,
            )
            if (st.liveIpa.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(st.liveIpa, style = PolyType.Ipa)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PulsingMicButton(recording = true, levelDb = live.levelDb, onClick = vm::stopAnswer)
        }
        Text("Tap to finish this answer early", style = PolyType.Caption, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
