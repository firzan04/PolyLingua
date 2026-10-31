package com.polylingua.edge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.polylingua.edge.data.ChatMessageEntity
import com.polylingua.edge.network.CallChatMessage
import com.polylingua.edge.network.CallPhase
import com.polylingua.edge.network.Persona
import com.polylingua.edge.network.WordDiff
import com.polylingua.edge.ui.components.Banner
import com.polylingua.edge.ui.components.EmptyState
import com.polylingua.edge.ui.components.FrostedCard
import com.polylingua.edge.ui.components.GhostButton
import com.polylingua.edge.ui.components.IconCircleButton
import com.polylingua.edge.ui.components.PadlockBadge
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.components.PrimaryButton
import com.polylingua.edge.ui.components.PulsingMicButton
import com.polylingua.edge.ui.components.RedGreenDiffText
import com.polylingua.edge.ui.components.ScreenHeader
import com.polylingua.edge.ui.components.SectionHeader
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyType
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun HelloTalkTandemHybridScreen(nav: NavHostController, vm: TandemViewModel = hiltViewModel()) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val call by vm.call.collectAsStateWithLifecycle()
    val persona by vm.persona.collectAsStateWithLifecycle()
    BackHandler(enabled = mode != TandemMode.HUB) { vm.back() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = when (mode) {
                TandemMode.HUB -> "HelloTalk · Tandem"
                TandemMode.ONLINE -> "Worldwide partners"
                TandemMode.LAN -> "Local network partners"
                TandemMode.PERSONA -> persona?.let { "${it.flag} ${it.name}" } ?: "AI partner"
            },
            subtitle = when (mode) {
                TandemMode.HUB -> "Humans online · LAN / Wi-Fi Direct · 12 offline AI natives"
                TandemMode.ONLINE -> "WebRTC P2P · MQTT relay signalling"
                TandemMode.LAN -> "NSD discovery · no internet needed"
                TandemMode.PERSONA -> persona?.let { "${it.accentLabel} · ${it.role}" }
            },
            onBack = { if (mode == TandemMode.HUB) nav.popBackStack() else vm.back() },
        )
        Box(Modifier.weight(1f)) {
            when (mode) {
                TandemMode.HUB -> HubView(vm)
                TandemMode.ONLINE -> OnlineView(vm)
                TandemMode.LAN -> LanView(vm)
                TandemMode.PERSONA -> PersonaChatView(vm)
            }
        }
    }
    if (call.phase != CallPhase.IDLE) CallOverlay(vm)
}

// ================================================================================== hub

@Composable
private fun HubView(vm: TandemViewModel) {
    val net by vm.network.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FrostedCard(accent = if (net.online) PolyColors.NeonMintSuccess else PolyColors.CrimsonAlert) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(PolyIcons.Globe, null, tint = PolyColors.ElectricTeal, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tier 1 · Native speakers worldwide", style = PolyType.CardTitle)
                        Text("Live audio/video calls with text chat and HelloTalk-style corrections", style = PolyType.Caption)
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (net.online) {
                    PrimaryButton("Join the worldwide lobby", { vm.openOnline() }, Modifier.fillMaxWidth(), icon = PolyIcons.Users)
                    Spacer(Modifier.height(6.dp))
                    Text("Uses a public relay for call set-up only; audio/video flow peer-to-peer. Share no personal data with strangers.", style = PolyType.Caption)
                } else {
                    PadlockBadge()
                    Spacer(Modifier.height(8.dp))
                    Banner("🔒 Fitur Panggilan P2P Manusia Membutuhkan Internet", PolyColors.CrimsonAlert, icon = PolyIcons.Lock)
                }
            }
        }
        item {
            FrostedCard(accent = PolyColors.ElectricTeal) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(PolyIcons.Wifi, null, tint = PolyColors.ElectricTeal, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tier 2 · Local Wi-Fi / Wi-Fi Direct", style = PolyType.CardTitle)
                        Text("Practise with a friend in the same room - works with no internet at all", style = PolyType.Caption)
                    }
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Find nearby partners", vm::openLan, Modifier.fillMaxWidth(), icon = PolyIcons.Wifi)
            }
        }
        item {
            FrostedCard(accent = PolyColors.AmberWarning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(PolyIcons.Bot, null, tint = PolyColors.AmberWarning, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tier 3 · Offline AI native partners", style = PolyType.CardTitle)
                        Text("Simulator Mitra Native AI Luring - 12 accents, red/green corrections, 100% free", style = PolyType.Caption)
                    }
                }
            }
        }
        item { SectionHeader("Choose a partner", "Tap to chat · long debate button for Tandem debate") }
        items(vm.allPersonas, key = { it.id }) { p -> PersonaCard(p, onChat = { vm.selectPersona(p) }, onDebate = { vm.selectPersona(p, debate = true) }) }
    }
}

@Composable
private fun PersonaCard(p: Persona, onChat: () -> Unit, onDebate: () -> Unit) {
    FrostedCard(onClick = onChat) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(PolyColors.ObsidianSlateHigh), contentAlignment = Alignment.Center) {
                Text(p.avatar, style = PolyType.Section)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${p.flag} ${p.name} · ${p.city}", style = PolyType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.accentLabel} · ${p.role}", style = PolyType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.bio, style = PolyType.Caption.copy(color = PolyColors.PureIceWhite), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            GhostButton("Debate", onDebate, color = PolyColors.AmberWarning)
        }
    }
}

// ============================================================================== online

@Composable
private fun OnlineView(vm: TandemViewModel) {
    val peers by vm.onlinePeers.collectAsStateWithLifecycle()
    val status by vm.lobbyStatus.collectAsStateWithLifecycle()
    val net by vm.network.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (!net.online) {
                PadlockBadge()
                Spacer(Modifier.height(6.dp))
                Banner("🔒 Fitur Panggilan P2P Manusia Membutuhkan Internet", PolyColors.CrimsonAlert, icon = PolyIcons.Lock)
            } else {
                Banner(status.ifBlank { "Connecting…" }, PolyColors.ElectricTeal, icon = PolyIcons.Globe)
            }
        }
        if (peers.isEmpty()) {
            item { EmptyState(PolyIcons.Users, "Waiting for learners", "Other PolyLingua users appear here while they are in the lobby. Keep this screen open or invite a friend.") }
        }
        items(peers, key = { it.id }) { p ->
            FrostedCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = PolyType.CardTitle)
                        Text("Native: ${p.nativeLanguage} · learning ${p.learning} ${p.level}", style = PolyType.Caption)
                    }
                    PrimaryButton("Call", { vm.inviteOnline(p.id, p.name) }, icon = PolyIcons.Video)
                }
            }
        }
    }
}

// ================================================================================= LAN

@Composable
private fun LanView(vm: TandemViewModel) {
    val peers by vm.lanPeers.collectAsStateWithLifecycle()
    val devices by vm.wifiDevices.collectAsStateWithLifecycle()
    val wifiStatus by vm.wifiStatus.collectAsStateWithLifecycle()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (vm.hasWifiDirectPermission()) vm.startWifiDirect() }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Banner("Both phones: open this screen on the same Wi-Fi or hotspot. Discovery uses NSD/mDNS; calls stay on your local network.", PolyColors.ElectricTeal, icon = PolyIcons.Wifi)
        }
        if (peers.isEmpty()) item { EmptyState(PolyIcons.Wifi, "Searching the local network…", "No partner found yet. If there is no router, use Wi-Fi Direct below.") }
        items(peers, key = { it.id }) { p ->
            FrostedCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = PolyType.CardTitle)
                        Text("${p.via} · ${p.host}", style = PolyType.Caption)
                    }
                    PrimaryButton("Call", { vm.inviteLan(p) }, icon = PolyIcons.Video)
                }
            }
        }
        if (vm.wifiDirectSupported) {
            item {
                FrostedCard(accent = PolyColors.AmberWarning) {
                    Text("Wi-Fi Direct (no router)", style = PolyType.CardTitle)
                    Text(wifiStatus, style = PolyType.Caption)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Discover nearby phones", {
                        if (vm.hasWifiDirectPermission()) vm.startWifiDirect() else permLauncher.launch(
                            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        )
                    }, icon = PolyIcons.Wifi, color = PolyColors.AmberWarning)
                    devices.forEach { d ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.deviceName.ifBlank { d.deviceAddress }, style = PolyType.Body, modifier = Modifier.weight(1f))
                            GhostButton("Connect", { vm.connectWifiDirect(d) })
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================== calls

@Composable
private fun CallOverlay(vm: TandemViewModel) {
    val call by vm.call.collectAsStateWithLifecycle()
    val chat by vm.callChat.collectAsStateWithLifecycle()
    val local by vm.localVideo.collectAsStateWithLifecycle()
    val remote by vm.remoteVideo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleVideo() }
    Dialog(onDismissRequest = {}) {
        FrostedCard(Modifier.fillMaxWidth().heightIn(max = 680.dp), accent = PolyColors.ElectricTeal) {
            when (call.phase) {
                CallPhase.RINGING -> {
                    Text("📞 ${call.peerName} is calling", style = PolyType.Section)
                    Text(call.transport.label, style = PolyType.Caption)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Accept", vm::accept, Modifier.weight(1f), icon = PolyIcons.Check, color = PolyColors.NeonMintSuccess)
                        PrimaryButton("Decline", vm::decline, Modifier.weight(1f), icon = PolyIcons.Close, color = PolyColors.CrimsonAlert)
                    }
                }
                CallPhase.INVITING -> {
                    Text("Calling ${call.peerName}…", style = PolyType.Section)
                    Text(call.transport.label, style = PolyType.Caption)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Cancel", vm::hangup, Modifier.fillMaxWidth(), color = PolyColors.CrimsonAlert)
                }
                CallPhase.ENDED -> {
                    Text("Call ended", style = PolyType.Section)
                    Text(call.endReason, style = PolyType.Caption)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Close", vm::dismissEndedCall, Modifier.fillMaxWidth())
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(call.peerName, style = PolyType.CardTitle)
                            Text(if (call.phase == CallPhase.CONNECTED) "Connected · ${call.transport.label}" else "Connecting… ${call.iceState}", style = PolyType.Caption)
                        }
                        PillBadge(if (call.phase == CallPhase.CONNECTED) "LIVE" else "ICE", if (call.phase == CallPhase.CONNECTED) PolyColors.NeonMintSuccess else PolyColors.AmberWarning, filled = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(PolyColors.ObsidianSlateHigh)) {
                        val egl = vm.eglContext()
                        if (remote != null && egl != null) VideoSurface(remote!!, egl, mirror = false, modifier = Modifier.fillMaxSize())
                        else Text("🎧 Audio call", style = PolyType.Section, modifier = Modifier.align(Alignment.Center))
                        if (local != null && egl != null) {
                            VideoSurface(local!!, egl, mirror = true, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(84.dp, 112.dp).clip(RoundedCornerShape(10.dp)))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (call.myCaption.isNotBlank()) Text("🎙️ ${call.myCaption}", style = PolyType.Caption, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconCircleButton(if (call.micOn) PolyIcons.Mic else PolyIcons.MicOff, vm::toggleMic)
                        IconCircleButton(if (call.videoOn) PolyIcons.Video else PolyIcons.VideoOff, {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.toggleVideo()
                            else camLauncher.launch(Manifest.permission.CAMERA)
                        })
                        IconCircleButton(PolyIcons.CameraSwitch, vm::switchCamera)
                        Spacer(Modifier.weight(1f))
                        IconCircleButton(PolyIcons.HangUp, vm::hangup, color = PolyColors.PureIceWhite, background = PolyColors.CrimsonAlert)
                    }
                    Spacer(Modifier.height(10.dp))
                    CallChatPanel(chat, vm)
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(track: VideoTrack, egl: EglBase.Context, mirror: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context).apply { init(egl, null); setMirror(mirror); setEnableHardwareScaler(true) } }
    DisposableEffect(track) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer) }
    }
    DisposableEffect(Unit) { onDispose { renderer.release() } }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun CallChatPanel(chat: List<CallChatMessage>, vm: TandemViewModel) {
    var text by remember { mutableStateOf("") }
    var correcting by remember { mutableStateOf<CallChatMessage?>(null) }
    Column {
        LazyColumn(Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chat) { m ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (m.mine) PolyColors.ElectricTeal.copy(alpha = 0.12f) else PolyColors.ObsidianSlate)
                        .clickable(enabled = !m.mine) { correcting = m }.padding(8.dp),
                ) {
                    Text(m.text, style = PolyType.Body.copy(fontSize = 13.sp))
                    if (m.correctedText.isNotBlank()) {
                        RedGreenDiffText(WordDiff.diff(m.text.removePrefix("✏️ ").removePrefix("🎙️ "), m.correctedText))
                        if (m.note.isNotBlank()) Text(m.note, style = PolyType.Caption)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Message", style = PolyType.Caption) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder))
            Spacer(Modifier.width(6.dp))
            IconCircleButton(PolyIcons.Send, { vm.sendCallChat(text); text = "" }, color = PolyColors.ElectricTeal)
        }
        Text("Tap your partner's message to send them a correction.", style = PolyType.Caption)
    }
    correcting?.let { m ->
        var fixed by remember(m) { mutableStateOf(m.text) }
        var note by remember(m) { mutableStateOf("") }
        Dialog(onDismissRequest = { correcting = null }) {
            FrostedCard {
                Text("Correct ${m.text.take(40)}", style = PolyType.CardTitle)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(fixed, { fixed = it }, Modifier.fillMaxWidth(), label = { Text("Corrected sentence") })
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Short explanation (optional)") })
                Spacer(Modifier.height(6.dp))
                RedGreenDiffText(WordDiff.diff(m.text, fixed))
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Send correction", { vm.correctPartner(m.text, fixed, note); correcting = null }, Modifier.fillMaxWidth(), icon = PolyIcons.Edit)
            }
        }
    }
}

// ============================================================================= personas

@Composable
private fun PersonaChatView(vm: TandemViewModel) {
    val persona by vm.persona.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val speak by vm.speakReplies.collectAsStateWithLifecycle()
    val handsFree by vm.handsFree.collectAsStateWithLifecycle()
    val motion by vm.motion.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var selfView by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { selfView = it }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, streaming.length) {
        val count = messages.size + if (streaming.isNotBlank()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    val p = persona ?: return
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(motion?.let { "⚖️ $it" } ?: "Topic focus: ${p.domains.joinToString(" · ")}", style = PolyType.Caption, modifier = Modifier.weight(1f), maxLines = 2)
            Text("Voice", style = PolyType.Caption)
            Switch(speak, vm::setSpeakReplies, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
            Spacer(Modifier.width(6.dp))
            IconCircleButton(PolyIcons.Video, {
                if (selfView) selfView = false
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) selfView = true
                else camLauncher.launch(Manifest.permission.CAMERA)
            }, size = 36.dp, color = if (selfView) PolyColors.NeonMintSuccess else PolyColors.PureIceWhite)
            Spacer(Modifier.width(6.dp))
            IconCircleButton(PolyIcons.Trash, vm::clearThread, size = 36.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live talk (hands-free)", style = PolyType.Body.copy(fontSize = 13.sp))
                Text(
                    if (handsFree) "Just talk - a ${"%.1f".format(vm.turnEndSec)} s pause sends your turn; the mic reopens after each reply"
                    else "Like Gemini Live: no tapping, natural turn-taking",
                    style = PolyType.Caption,
                )
            }
            Switch(handsFree, vm::setHandsFree, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.CrimsonAlert))
        }
        if (selfView) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(160.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, PolyColors.FrostedBorder, RoundedCornerShape(16.dp))) {
                CameraSelfView(Modifier.fillMaxSize())
                PillBadge("SELF-VIEW · watch your mouth shape", PolyColors.NeonMintSuccess, Modifier.align(Alignment.TopStart).padding(8.dp), filled = true)
            }
        }
        error?.let { Banner(it, PolyColors.AmberWarning, Modifier.padding(horizontal = 16.dp)) { GhostButton("OK", vm::consumeError) } }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages, key = { it.id }) { m -> ChatBubble(m, p, vm) }
            if (streaming.isNotBlank()) item { Bubble(streaming, mine = false, persona = p) }
        }
        if (recording) Text("🎙️ ${live.transcript.text.ifBlank { "Listening…" }}", style = PolyType.Body, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), maxLines = 3,
                placeholder = { Text(if (busy) "${p.name} is replying…" else "Type, or tap the mic and speak", style = PolyType.Caption) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder),
            )
            Spacer(Modifier.width(6.dp))
            if (input.isNotBlank()) {
                IconCircleButton(PolyIcons.Send, { vm.send(input); input = "" }, color = PolyColors.ElectricTeal)
            } else {
                PulsingMicButton(recording, live.levelDb, vm::toggleVoice, size = 44.dp, enabled = !busy)
            }
        }
    }
}

@Composable
private fun ChatBubble(m: ChatMessageEntity, p: Persona, vm: TandemViewModel) {
    val mine = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Bubble(m.text, mine, p, onReplay = if (!mine) ({ vm.replay(m.text) }) else null)
        if (mine && (m.correctedText.isNotBlank() || m.correctionNote.isNotBlank())) {
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(PolyColors.ObsidianSlate)
                    .border(1.dp, PolyColors.FrostedBorder, RoundedCornerShape(12.dp)).padding(10.dp),
            ) {
                val diff = vm.diffFor(m)
                if (diff.isNotEmpty()) RedGreenDiffText(diff)
                if (m.correctionNote.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(m.correctionNote, style = PolyType.Caption)
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, persona: Persona, onReplay: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (!mine) {
            Text(persona.avatar, style = PolyType.CardTitle)
            Spacer(Modifier.width(6.dp))
        }
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (mine) PolyColors.ElectricTeal.copy(alpha = 0.2f) else PolyColors.FrostedSurfaceSolid)
                .then(if (onReplay != null) Modifier.clickable(onClick = onReplay) else Modifier)
                .padding(12.dp),
        ) {
            Text(text, style = PolyType.Body.copy(fontSize = 15.sp, lineHeight = 21.sp))
            if (onReplay != null) Text("Tap to replay", style = PolyType.Caption.copy(fontSize = 10.sp))
        }
    }
}

/** CameraX front-camera preview so learners can watch their mouth and lip shapes. */
@Composable
private fun CameraSelfView(modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { provider?.unbindAll() } }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
