package com.polylingua.edge.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import com.polylingua.edge.PolyLinguaForegroundService
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.domain.GrammarPatternDetector
import com.polylingua.edge.dsp.OboeAudioRecorder
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.speech.RecognitionSession
import com.polylingua.edge.speech.SpeechRecognitionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================================ connectivity

enum class NetworkTier { ONLINE_WIFI, ONLINE_CELLULAR, ONLINE_OTHER, LAN_ONLY, OFFLINE }

data class NetworkStatus(val tier: NetworkTier = NetworkTier.OFFLINE, val metered: Boolean = false) {
    val online: Boolean get() = tier == NetworkTier.ONLINE_WIFI || tier == NetworkTier.ONLINE_CELLULAR || tier == NetworkTier.ONLINE_OTHER
    val lanAvailable: Boolean get() = tier == NetworkTier.ONLINE_WIFI || tier == NetworkTier.LAN_ONLY
    val pill: String
        get() = when (tier) {
            NetworkTier.ONLINE_WIFI -> "🟢 ONLINE WIFI"
            NetworkTier.ONLINE_CELLULAR -> "🟢 ONLINE 4G"
            NetworkTier.ONLINE_OTHER -> "🟢 ONLINE"
            NetworkTier.LAN_ONLY -> "🔒 OFFLINE EDGE MODE · LAN"
            NetworkTier.OFFLINE -> "🔒 OFFLINE EDGE MODE"
        }
}

// ================================================================================== calls

enum class CallPhase { IDLE, INVITING, RINGING, CONNECTING, CONNECTED, ENDED }

enum class CallTransport(val label: String) { INTERNET("Internet (WebRTC P2P)"), LAN("Local Wi-Fi / Wi-Fi Direct") }

data class CallChatMessage(
    val mine: Boolean,
    val text: String,
    val correctedText: String = "",
    val note: String = "",
    val at: Long = System.currentTimeMillis(),
)

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val peerId: String = "",
    val peerName: String = "",
    val transport: CallTransport = CallTransport.INTERNET,
    val micOn: Boolean = true,
    val videoOn: Boolean = false,
    val endReason: String = "",
    val iceState: String = "",
    val startedAt: Long = 0,
    val myCaption: String = "",
)

/**
 * Three-tier connectivity hub:
 *  1. Online - worldwide human partners: MQTT-over-WSS lobby + WebRTC P2P audio/video/chat
 *     (STUN for NAT traversal).
 *  2. LAN - same Wi-Fi/hotspot (NSD) or router-less Wi-Fi Direct; WebRTC with host candidates.
 *  3. Offline - the AI persona simulator (see [OfflinePersonaEngine]); human features show the
 *     padlock UI because they genuinely need a network path to another phone.
 * During calls the WebRTC audio module owns the microphone; its samples are downsampled to
 * 16 kHz and injected into [OboeAudioRecorder] so the learner gets private live captions and
 * grammar flags for their own speech.
 */
@Singleton
class ConnectivityAndWebRtcManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val http: OkHttpClient,
    private val recorder: OboeAudioRecorder,
    private val asr: SpeechRecognitionEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _network = MutableStateFlow(NetworkStatus())
    val network: StateFlow<NetworkStatus> = _network

    val selfId: String by lazy {
        val prefs = context.getSharedPreferences("polylingua_identity", Context.MODE_PRIVATE)
        prefs.getString("peer_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also {
            prefs.edit().putString("peer_id", it).apply()
        }
    }

    init {
        refreshNetwork()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNetwork()
            override fun onLost(network: Network) = refreshNetwork()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshNetwork()
        })
    }

    private fun refreshNetwork() {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val tier = when {
            caps == null -> NetworkTier.OFFLINE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTier.ONLINE_WIFI
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTier.ONLINE_CELLULAR
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkTier.ONLINE_OTHER
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTier.LAN_ONLY
            else -> NetworkTier.OFFLINE
        }
        _network.value = NetworkStatus(tier, cm.isActiveNetworkMetered)
    }

    // --------------------------------------------------------------------------- lobbies

    private var online: MqttSignalingChannel? = null
    private var lan: LanSignalingChannel? = null
    private var incomingJob: Job? = null
    private var lanIncomingJob: Job? = null
    val wifiDirect: WifiDirectHelper by lazy { WifiDirectHelper(context) }

    private val _onlinePeers = MutableStateFlow<List<LobbyPeer>>(emptyList())
    val onlinePeers: StateFlow<List<LobbyPeer>> = _onlinePeers
    private val _lanPeers = MutableStateFlow<List<LanPeer>>(emptyList())
    val lanPeers: StateFlow<List<LanPeer>> = _lanPeers
    private val _lobbyStatus = MutableStateFlow("")
    val lobbyStatus: StateFlow<String> = _lobbyStatus

    private fun displayName(): String = settings.settings.value.displayName.ifBlank { "Learner ${selfId.take(4).uppercase()}" }

    /** Joins the worldwide lobby. Returns false when offline (padlocked UI). */
    fun joinOnlineLobby(): Boolean {
        if (!_network.value.online) return false
        if (online != null) return true
        val s = settings.settings.value
        val ch = MqttSignalingChannel(http, s.signalingUrl, selfId, displayName(), "id", s.targetCefr.name)
        online = ch
        ch.start()
        _lobbyStatus.value = "Connecting to relay…"
        scope.launch { ch.peers.collect { _onlinePeers.value = it } }
        scope.launch {
            ch.state.collect { st ->
                _lobbyStatus.value = when (st) {
                    MqttWebSocketClient.State.CONNECTING -> "Connecting to relay…"
                    MqttWebSocketClient.State.CONNECTED -> "In lobby · visible to learners worldwide"
                    MqttWebSocketClient.State.FAILED -> "Relay unreachable: ${ch.lastError ?: "unknown error"}"
                    MqttWebSocketClient.State.DISCONNECTED -> "Disconnected"
                }
            }
        }
        incomingJob = scope.launch { ch.incoming.collect { onSignal(it, ch, CallTransport.INTERNET) } }
        return true
    }

    fun leaveOnlineLobby() {
        incomingJob?.cancel()
        online?.close()
        online = null
        _onlinePeers.value = emptyList()
        _lobbyStatus.value = ""
    }

    /** Starts LAN discovery + the local signalling server (works with no internet). */
    fun startLan() {
        if (lan != null) return
        val ch = LanSignalingChannel(context, selfId, displayName())
        runCatching { ch.start() }.onFailure {
            Log.w(TAG, "LAN signalling failed to start", it)
            return
        }
        lan = ch
        scope.launch { ch.peers.collect { _lanPeers.value = it } }
        lanIncomingJob = scope.launch { ch.incoming.collect { onSignal(it, ch, CallTransport.LAN) } }
        scope.launch {
            wifiDirect.connection.collect { info ->
                if (info != null && !info.isGroupOwner) {
                    val host = info.groupOwnerAddress?.hostAddress ?: return@collect
                    ch.addDirectPeer(host, ch.port, "Wi-Fi Direct partner")
                }
            }
        }
    }

    fun stopLan() {
        lanIncomingJob?.cancel()
        lan?.close()
        lan = null
        _lanPeers.value = emptyList()
        wifiDirect.stop()
    }

    fun connectLanPeer(peer: LanPeer) {
        lan?.connect(peer)
    }

    // ----------------------------------------------------------------------------- calls

    private val _call = MutableStateFlow(CallUiState())
    val call: StateFlow<CallUiState> = _call
    private val _chat = MutableStateFlow<List<CallChatMessage>>(emptyList())
    val chat: StateFlow<List<CallChatMessage>> = _chat
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    private var channel: SignalingChannel? = null
    private var rtc: RtcEngine? = null
    private var captionSession: RecognitionSession? = null
    private var captionJob: Job? = null

    val eglContext: EglBase.Context? get() = rtc?.egl?.eglBaseContext

    fun invite(peerId: String, peerName: String, transport: CallTransport) {
        val ch = (if (transport == CallTransport.INTERNET) online else lan) ?: return
        if (_call.value.phase != CallPhase.IDLE && _call.value.phase != CallPhase.ENDED) return
        channel = ch
        _chat.value = emptyList()
        _call.value = CallUiState(CallPhase.INVITING, peerId, peerName, transport)
        ch.send(SignalMessage("invite", selfId, peerId, JSONObject().put("name", displayName())))
    }

    fun accept() {
        val st = _call.value
        if (st.phase != CallPhase.RINGING) return
        channel?.send(SignalMessage("accept", selfId, st.peerId))
        _call.value = st.copy(phase = CallPhase.CONNECTING)
        startMedia(caller = false)
    }

    fun decline() {
        val st = _call.value
        channel?.send(SignalMessage("decline", selfId, st.peerId))
        _call.value = st.copy(phase = CallPhase.ENDED, endReason = "Declined")
    }

    fun hangup(reason: String = "Call ended") {
        val st = _call.value
        if (st.phase == CallPhase.IDLE) return
        channel?.send(SignalMessage("bye", selfId, st.peerId))
        teardown(reason)
    }

    fun toggleMic() {
        val on = !_call.value.micOn
        rtc?.localAudio?.setEnabled(on)
        _call.value = _call.value.copy(micOn = on)
    }

    fun toggleVideo() {
        val r = rtc ?: return
        val on = !_call.value.videoOn
        if (on) r.startCamera() else r.stopCamera()
        _localVideo.value = r.localVideo
        _call.value = _call.value.copy(videoOn = on && r.localVideo != null)
    }

    fun switchCamera() {
        rtc?.switchCamera()
    }

    /** Sends a chat line over the data channel; flags my own grammar slips privately. */
    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        rtc?.sendData(JSONObject().put("kind", "text").put("text", t).toString())
        val issue = GrammarPatternDetector.detectIssues(t).firstOrNull()
        _chat.value = _chat.value + CallChatMessage(true, t, issue?.let { applyFix(t, it.start, it.end, it.suggestion) } ?: "", issue?.explanation ?: "")
    }

    /** HelloTalk-style correction of the partner's message, sent back to them. */
    fun sendCorrection(original: String, corrected: String, note: String) {
        rtc?.sendData(JSONObject().put("kind", "correction").put("original", original).put("corrected", corrected).put("note", note).toString())
        _chat.value = _chat.value + CallChatMessage(true, "✏️ $original", corrected, note)
    }

    private fun applyFix(text: String, start: Int, end: Int, replacement: String): String =
        if (start in 0..end && end <= text.length) text.substring(0, start) + replacement + text.substring(end) else replacement

    private fun onSignal(msg: SignalMessage, ch: SignalingChannel, transport: CallTransport) {
        val st = _call.value
        when (msg.type) {
            "invite" -> {
                if (st.phase != CallPhase.IDLE && st.phase != CallPhase.ENDED) {
                    ch.send(SignalMessage("decline", selfId, msg.from, JSONObject().put("reason", "busy")))
                    return
                }
                channel = ch
                _chat.value = emptyList()
                _call.value = CallUiState(CallPhase.RINGING, msg.from, msg.body.optString("name", "Partner"), transport)
            }
            "accept" -> if (st.phase == CallPhase.INVITING && msg.from == st.peerId) {
                _call.value = st.copy(phase = CallPhase.CONNECTING)
                startMedia(caller = true)
            }
            "decline" -> if (msg.from == st.peerId) {
                _call.value = st.copy(phase = CallPhase.ENDED, endReason = if (msg.body.optString("reason") == "busy") "Partner is busy" else "Declined")
            }
            "offer" -> if (msg.from == st.peerId) rtc?.onRemoteOffer(msg.body.optString("sdp"))
            "answer" -> if (msg.from == st.peerId) rtc?.onRemoteAnswer(msg.body.optString("sdp"))
            "ice" -> if (msg.from == st.peerId) rtc?.onRemoteIce(msg.body)
            "bye" -> if (msg.from == st.peerId) teardown("Partner hung up")
        }
    }

    private fun startMedia(caller: Boolean) {
        val st = _call.value
        val ice = if (st.transport == CallTransport.INTERNET) {
            listOf(PeerConnection.IceServer.builder(settings.settings.value.stunUrl).createIceServer())
        } else emptyList()
        val camAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val engine = RtcEngine(context, ice, object : RtcEngine.Listener {
            override fun onLocalSdp(type: String, sdp: String) {
                channel?.send(SignalMessage(type, selfId, st.peerId, JSONObject().put("sdp", sdp)))
            }
            override fun onLocalIce(c: IceCandidate) {
                channel?.send(SignalMessage("ice", selfId, st.peerId,
                    JSONObject().put("mid", c.sdpMid).put("index", c.sdpMLineIndex).put("candidate", c.sdp)))
            }
            override fun onConnectionState(state: String) {
                scope.launch {
                    val cur = _call.value
                    _call.value = when (state) {
                        "CONNECTED" -> cur.copy(phase = CallPhase.CONNECTED, iceState = state, startedAt = if (cur.startedAt == 0L) System.currentTimeMillis() else cur.startedAt)
                        "FAILED" -> { teardown("Connection failed (NAT/firewall). Try Wi-Fi or LAN mode."); return@launch }
                        else -> cur.copy(iceState = state)
                    }
                }
            }
            override fun onRemoteVideo(track: VideoTrack?) { scope.launch { _remoteVideo.value = track } }
            override fun onData(text: String) {
                scope.launch {
                    val o = runCatching { JSONObject(text) }.getOrNull() ?: return@launch
                    _chat.value = _chat.value + when (o.optString("kind")) {
                        "correction" -> CallChatMessage(false, "✏️ ${o.optString("original")}", o.optString("corrected"), o.optString("note"))
                        else -> CallChatMessage(false, o.optString("text"))
                    }
                }
            }
            override fun onMicSamples(pcm16k: ShortArray) { recorder.injectExternal(pcm16k) }
        })
        rtc = engine
        engine.start(caller, withVideo = camAllowed)
        _localVideo.value = engine.localVideo
        _call.value = _call.value.copy(videoOn = engine.localVideo != null)
        PolyLinguaForegroundService.acquire(context, PolyLinguaForegroundService.Reason.CALL, "with ${st.peerName}")
        startCaptions()
    }

    private fun startCaptions() {
        if (asr.backend() != com.polylingua.edge.speech.AsrBackend.SHERPA_OFFLINE) return
        captionJob = scope.launch {
            val session = asr.startSession(this, conversation = true) ?: return@launch
            captionSession = session
            launch { session.live.collect { _call.value = _call.value.copy(myCaption = it.text.takeLast(160)) } }
            session.utterances.collect { utt ->
                val issue = GrammarPatternDetector.detectIssues(utt).firstOrNull() ?: return@collect
                _chat.value = _chat.value + CallChatMessage(true, "🎙️ $utt", applyFix(utt, issue.start, issue.end, issue.suggestion), issue.explanation)
            }
        }
    }

    private fun teardown(reason: String) {
        captionSession?.cancel()
        captionSession = null
        captionJob?.cancel()
        rtc?.dispose()
        rtc = null
        _localVideo.value = null
        _remoteVideo.value = null
        PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.CALL)
        _call.value = _call.value.copy(phase = CallPhase.ENDED, endReason = reason)
    }

    fun resetCall() {
        if (_call.value.phase == CallPhase.ENDED) _call.value = CallUiState()
    }

    companion object {
        private const val TAG = "PolyLinguaNet"
    }
}

/**
 * Thin WebRTC wrapper (io.getstream:stream-webrtc-android): one PeerConnection with Opus audio
 * (hardware AEC/NS), optional 640x480 front-camera video and a "chat" data channel.
 */
class RtcEngine(
    private val context: Context,
    private val iceServers: List<PeerConnection.IceServer>,
    private val listener: Listener,
) {
    interface Listener {
        fun onLocalSdp(type: String, sdp: String)
        fun onLocalIce(c: IceCandidate)
        fun onConnectionState(state: String)
        fun onRemoteVideo(track: VideoTrack?)
        fun onData(text: String)
        fun onMicSamples(pcm16k: ShortArray)
    }

    val egl: EglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    var localAudio: AudioTrack? = null
        private set
    var localVideo: VideoTrack? = null
        private set
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false

    fun start(caller: Boolean, withVideo: Boolean) {
        ensureInitialized(context)
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { samples -> forwardSamples(samples) }
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory.createPeerConnection(config, observer)
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudio = factory.createAudioTrack("pl-audio", audioSource).also { pc?.addTrack(it, listOf("pl-stream")) }
        if (withVideo) startCamera()
        if (caller) {
            dataChannel = pc?.createDataChannel("chat", DataChannel.Init())?.also { attachData(it) }
            pc?.createOffer(sdp("offer"), MediaConstraints())
        }
    }

    fun startCamera() {
        if (localVideo != null) return
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return
        val cap = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("pl-capture", egl.eglBaseContext)
        val source = factory.createVideoSource(cap.isScreencast)
        cap.initialize(helper, context, source.capturerObserver)
        cap.startCapture(640, 480, 24)
        val track = factory.createVideoTrack("pl-video", source)
        pc?.addTrack(track, listOf("pl-stream"))
        capturer = cap
        textureHelper = helper
        videoSource = source
        localVideo = track
    }

    fun stopCamera() {
        runCatching { capturer?.stopCapture() }
        localVideo?.setEnabled(false)
        capturer?.dispose()
        capturer = null
        textureHelper?.dispose()
        textureHelper = null
        localVideo = null
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun sendData(text: String) {
        val dc = dataChannel ?: return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    fun onRemoteOffer(sdp: String) {
        val p = pc ?: return
        p.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                remoteSet = true
                flushIce()
                p.createAnswer(sdp("answer"), MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onRemoteAnswer(sdp: String) {
        pc?.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                remoteSet = true
                flushIce()
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun onRemoteIce(o: JSONObject) {
        val c = IceCandidate(o.optString("mid"), o.optInt("index"), o.optString("candidate"))
        synchronized(pendingIce) {
            if (!remoteSet) {
                pendingIce += c
                return
            }
        }
        pc?.addIceCandidate(c)
    }

    private fun flushIce() {
        val list = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
        list.forEach { pc?.addIceCandidate(it) }
    }

    fun dispose() {
        runCatching { dataChannel?.close() }
        stopCamera()
        videoSource?.dispose()
        runCatching { pc?.close() }
        pc = null
        if (::factory.isInitialized) runCatching { factory.dispose() }
        runCatching { egl.release() }
    }

    private fun sdp(type: String) = object : SimpleSdp() {
        override fun onCreateSuccess(desc: SessionDescription) {
            pc?.setLocalDescription(SimpleSdp(), desc)
            listener.onLocalSdp(type, desc.description)
        }
    }

    private fun attachData(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                listener.onData(String(bytes, Charsets.UTF_8))
            }
        })
    }

    private fun forwardSamples(s: JavaAudioDeviceModule.AudioSamples) {
        val data = s.data ?: return
        val channels = s.channelCount.coerceAtLeast(1)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val frames = data.size / 2 / channels
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += bb.short / 32768f
            mono[i] = acc / channels
        }
        val resampled = if (s.sampleRate == 16000) mono else PcmUtils.resample(mono, s.sampleRate, 16000)
        listener.onMicSamples(PcmUtils.floatsToShorts(resampled))
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            listener.onConnectionState(
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "CONNECTED"
                    PeerConnection.IceConnectionState.FAILED -> "FAILED"
                    else -> state?.name ?: "NEW"
                },
            )
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) { candidate?.let { listener.onLocalIce(it) } }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(dc: DataChannel?) {
            dc ?: return
            dataChannel = dc
            attachData(dc)
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) listener.onRemoteVideo(track)
        }
    }

    open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) { Log.w("PolyLinguaRtc", "SDP create failed: $error") }
        override fun onSetFailure(error: String?) { Log.w("PolyLinguaRtc", "SDP set failed: $error") }
    }

    companion object {
        @Volatile private var initialized = false

        fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
                )
                initialized = true
            }
        }
    }
}
