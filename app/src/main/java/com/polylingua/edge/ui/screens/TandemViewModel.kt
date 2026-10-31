package com.polylingua.edge.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.data.CaseRepository
import com.polylingua.edge.data.ChatMessageEntity
import com.polylingua.edge.data.PolyLinguaDatabase
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.network.CallTransport
import com.polylingua.edge.network.ConnectivityAndWebRtcManager
import com.polylingua.edge.network.LanPeer
import com.polylingua.edge.network.OfflinePersonaEngine
import com.polylingua.edge.network.Persona
import com.polylingua.edge.network.PersonaCatalog
import com.polylingua.edge.network.WordDiff
import com.polylingua.edge.speech.ChatTurn
import com.polylingua.edge.speech.LiveCaptureState
import com.polylingua.edge.speech.SpeakingCaptureController
import com.polylingua.edge.speech.SpeechSynthesisEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TandemMode { HUB, ONLINE, LAN, PERSONA }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TandemViewModel @Inject constructor(
    private val net: ConnectivityAndWebRtcManager,
    private val personas: OfflinePersonaEngine,
    private val capture: SpeakingCaptureController,
    private val tts: SpeechSynthesisEngine,
    private val settings: AppSettings,
    private val repository: CaseRepository,
    db: PolyLinguaDatabase,
) : ViewModel() {
    private val chatDao = db.chatDao()

    val network = net.network
    val onlinePeers = net.onlinePeers
    val lanPeers = net.lanPeers
    val lobbyStatus = net.lobbyStatus
    val call = net.call
    val callChat = net.chat
    val localVideo = net.localVideo
    val remoteVideo = net.remoteVideo
    val wifiDevices = net.wifiDirect.devices
    val wifiStatus = net.wifiDirect.status
    val wifiDirectSupported: Boolean get() = net.wifiDirect.supported
    fun eglContext() = net.eglContext

    private val _mode = MutableStateFlow(TandemMode.HUB)
    val mode: StateFlow<TandemMode> = _mode

    // ------------------------------------------------------------------- humans

    fun openOnline(): Boolean {
        if (!net.joinOnlineLobby()) return false
        _mode.value = TandemMode.ONLINE
        return true
    }

    fun openLan() {
        net.startLan()
        _mode.value = TandemMode.LAN
    }

    fun startWifiDirect() = net.wifiDirect.start()
    fun connectWifiDirect(device: android.net.wifi.p2p.WifiP2pDevice) = net.wifiDirect.connect(device)
    fun hasWifiDirectPermission() = net.wifiDirect.hasPermission()

    fun inviteOnline(id: String, name: String) = net.invite(id, name, CallTransport.INTERNET)
    fun inviteLan(peer: LanPeer) {
        net.connectLanPeer(peer)
        viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            net.invite(peer.id, peer.name, CallTransport.LAN)
        }
    }

    fun accept() = net.accept()
    fun decline() = net.decline()
    fun hangup() = net.hangup()
    fun toggleMic() = net.toggleMic()
    fun toggleVideo() = net.toggleVideo()
    fun switchCamera() = net.switchCamera()
    fun sendCallChat(text: String) = net.sendChat(text)
    fun correctPartner(original: String, corrected: String, note: String) = net.sendCorrection(original, corrected, note)
    fun dismissEndedCall() = net.resetCall()

    fun back() {
        when (_mode.value) {
            TandemMode.ONLINE -> net.leaveOnlineLobby()
            TandemMode.LAN -> net.stopLan()
            TandemMode.PERSONA -> { handsFreeJob?.cancel(); _handsFree.value = false; tts.stop(); recordJob?.cancel(); activeCapture?.abort(); activeCapture = null; _recording.value = false }
            TandemMode.HUB -> Unit
        }
        _mode.value = TandemMode.HUB
    }

    // ------------------------------------------------------------------- personas

    val allPersonas: List<Persona> = PersonaCatalog.ALL
    private val _persona = MutableStateFlow<Persona?>(null)
    val persona: StateFlow<Persona?> = _persona
    private val _motion = MutableStateFlow<String?>(null)
    val motion: StateFlow<String?> = _motion
    val messages: StateFlow<List<ChatMessageEntity>> = combine(_persona, _motion) { p, m -> p?.let { threadId(it, m) } }
        .distinctUntilChanged()
        .flatMapLatest { tid -> if (tid == null) flowOf(emptyList()) else chatDao.thread(tid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _speakReplies = MutableStateFlow(true)
    val speakReplies: StateFlow<Boolean> = _speakReplies
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording
    private val _live = MutableStateFlow(LiveCaptureState())
    val live: StateFlow<LiveCaptureState> = _live
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var conversation: OfflinePersonaEngine.Conversation? = null
    private var activeCapture: SpeakingCaptureController.ActiveCapture? = null
    private var recordJob: Job? = null

    private fun threadId(p: Persona, motion: String?) = if (motion == null) p.id else "${p.id}#debate"

    fun selectPersona(p: Persona, debate: Boolean = false) {
        viewModelScope.launch {
            val motion = if (debate) {
                repository.randomCases(ModuleType.TANDEM_DEBATE, settings.settings.value.targetCefr, 1).firstOrNull()?.prompt
                    ?.removePrefix("Debate motion: ")?.removePrefix("Debate: ")
            } else null
            _motion.value = motion
            _persona.value = p
            _mode.value = TandemMode.PERSONA
            val conv = personas.start(p, settings.settings.value.targetCefr, motion)
            conversation = conv
            val tid = threadId(p, motion)
            if (debate) chatDao.clear(tid)
            val previous = chatDao.lastMessages(tid, 10).reversed()
            if (previous.isEmpty()) {
                val opening = conv.opening()
                chatDao.insert(ChatMessageEntity(personaId = tid, role = "assistant", text = opening, createdAt = System.currentTimeMillis()))
                speak(p, opening)
            } else {
                previous.forEach { m -> conv.history += if (m.role == "user") ChatTurn.user(m.text) else ChatTurn.assistant(m.text) }
            }
        }
    }

    fun setSpeakReplies(on: Boolean) {
        _speakReplies.value = on
        if (!on) tts.stop()
    }

    fun clearThread() {
        val p = _persona.value ?: return
        viewModelScope.launch {
            chatDao.clear(threadId(p, _motion.value))
            selectPersona(p, _motion.value != null)
        }
    }

    fun send(text: String) {
        val p = _persona.value ?: return
        val conv = conversation ?: return
        val clean = text.trim()
        if (clean.isEmpty() || _busy.value) return
        _busy.value = true
        tts.stop()
        viewModelScope.launch {
            val tid = threadId(p, _motion.value)
            val id = chatDao.insert(ChatMessageEntity(personaId = tid, role = "user", text = clean, createdAt = System.currentTimeMillis()))
            launch {
                val fb = runCatching { personas.feedback(clean, conv.currentTopic, settings.settings.value.targetCefr, useLlm = false) }.getOrNull()
                if (fb != null && fb.hasCorrections) {
                    val note = (fb.notes + fb.vocabularyTips.map { "Upgrade: $it" }).joinToString("\n")
                    chatDao.setCorrection(id, fb.corrected, note)
                } else if (fb != null && fb.vocabularyTips.isNotEmpty()) {
                    chatDao.setCorrection(id, "", fb.vocabularyTips.joinToString("\n") { "Upgrade: $it" })
                }
            }
            var reply = ""
            runCatching { conv.reply(clean).collect { partial -> reply = partial; _streaming.value = partial } }
                .onFailure { _error.value = "Reply failed: ${it.message}" }
            _streaming.value = ""
            var speaking: Job? = null
            if (reply.isNotBlank()) {
                chatDao.insert(ChatMessageEntity(personaId = tid, role = "assistant", text = reply, createdAt = System.currentTimeMillis()))
                speaking = speak(p, reply)
            }
            _busy.value = false
            if (_handsFree.value) {
                // Live talk: listen again as soon as the partner has finished speaking.
                speaking?.join()
                startHandsFreeTurn()
            }
        }
    }

    // ------------------------------------------------------------- live talk (hands-free)

    private val _handsFree = MutableStateFlow(false)
    /** Gemini-Live-style turn taking: the mic reopens after each reply; a pause ends your turn. */
    val handsFree: StateFlow<Boolean> = _handsFree
    private var handsFreeJob: Job? = null

    val turnEndSec: Float get() = settings.settings.value.turnEndSilenceSec

    init {
        viewModelScope.launch { capture.preload() }
    }

    fun setHandsFree(on: Boolean) {
        _handsFree.value = on
        if (on) {
            startHandsFreeTurn()
        } else {
            handsFreeJob?.cancel()
            if (_recording.value) {
                activeCapture?.abort()
                activeCapture = null
                recordJob?.cancel()
                _recording.value = false
            }
        }
    }

    private fun startHandsFreeTurn() {
        if (!_handsFree.value || _recording.value || _busy.value || _persona.value == null) return
        handsFreeJob?.cancel()
        handsFreeJob = viewModelScope.launch {
            tts.player.awaitIdle()
            if (!_handsFree.value || _recording.value || _busy.value) return@launch
            val cap = capture.begin(viewModelScope, "Live talk with ${_persona.value?.name ?: "partner"}", conversation = true)
            if (cap == null) {
                _error.value = "Microphone or offline speech pack unavailable."
                _handsFree.value = false
                return@launch
            }
            activeCapture = cap
            _recording.value = true
            recordJob = viewModelScope.launch { cap.live.collect { _live.value = it } }
            // Suspends until you stop talking for the configured pause (endpoint detection).
            val heard = cap.utterances.first()
            if (activeCapture !== cap) return@launch
            activeCapture = null
            _recording.value = false
            val captured = cap.end()
            recordJob?.cancel()
            val text = captured.transcript.text.ifBlank { heard }.trim()
            if (text.isBlank()) startHandsFreeTurn() else send(text)
        }
    }

    private fun speak(p: Persona, text: String): Job? {
        if (!_speakReplies.value) return null
        return viewModelScope.launch {
            if (p.bilingualIndonesian && text.contains("Budi:")) {
                val english = text.substringBefore("Budi:").trim()
                val indo = text.substringAfter("Budi:").trim()
                if (english.isNotBlank()) tts.speak(english, tts.resolve(p.voicePrefs), label = p.name)
                if (indo.isNotBlank()) tts.speak(indo, tts.resolve(listOf("system:id-ID")), label = "Budi")
            } else {
                tts.speak(text, tts.resolve(p.voicePrefs), label = p.name)
            }
        }
    }

    fun replay(text: String) {
        val p = _persona.value ?: return
        val was = _speakReplies.value
        _speakReplies.value = true
        speak(p, text)
        _speakReplies.value = was
    }

    /** Push-to-talk: first tap records, second tap transcribes and sends. */
    fun toggleVoice() {
        handsFreeJob?.cancel()
        if (_recording.value) {
            val cap = activeCapture ?: return
            activeCapture = null
            _recording.value = false
            viewModelScope.launch {
                val captured = cap.end()
                recordJob?.cancel()
                val text = captured.transcript.text
                if (text.isBlank()) _error.value = "I didn't catch that - try again a little closer to the mic." else send(text)
            }
        } else {
            tts.stop()
            viewModelScope.launch {
                val cap = capture.begin(viewModelScope, "Chat with ${_persona.value?.name ?: "partner"}")
                if (cap == null) {
                    _error.value = "Microphone or offline speech pack unavailable."
                    return@launch
                }
                activeCapture = cap
                _recording.value = true
                recordJob = viewModelScope.launch { cap.live.collect { _live.value = it } }
            }
        }
    }

    fun consumeError() { _error.value = null }

    fun diffFor(m: ChatMessageEntity) = if (m.correctedText.isBlank()) emptyList() else WordDiff.diff(m.text, m.correctedText)

    override fun onCleared() {
        handsFreeJob?.cancel()
        tts.stop()
        recordJob?.cancel()
        activeCapture?.abort()
        if (_mode.value == TandemMode.ONLINE) net.leaveOnlineLobby()
        if (_mode.value == TandemMode.LAN) net.stopLan()
    }
}
