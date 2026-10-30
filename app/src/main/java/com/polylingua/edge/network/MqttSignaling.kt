package com.polylingua.edge.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** A signalling envelope shared by the internet (MQTT) and LAN (TCP) transports. */
data class SignalMessage(val type: String, val from: String, val to: String, val body: JSONObject = JSONObject()) {
    fun encode(): String = JSONObject().put("type", type).put("from", from).put("to", to).put("body", body).toString()

    companion object {
        fun decode(raw: String): SignalMessage? = runCatching {
            val o = JSONObject(raw)
            SignalMessage(o.getString("type"), o.optString("from"), o.optString("to"), o.optJSONObject("body") ?: JSONObject())
        }.getOrNull()
    }
}

/** Transport-agnostic signalling channel used by [CallSession]. */
interface SignalingChannel {
    val incoming: SharedFlow<SignalMessage>
    val selfId: String
    fun send(message: SignalMessage)
    fun close()
}

/**
 * Minimal MQTT 3.1.1 client over WebSocket (OkHttp), QoS 0, used as the zero-infrastructure
 * WebRTC signalling relay: any public or self-hosted broker with a wss:// endpoint works (default
 * broker.emqx.io:8084/mqtt). Packets: CONNECT, CONNACK, SUBSCRIBE, SUBACK, PUBLISH, PINGREQ,
 * PINGRESP, DISCONNECT. Topics live under `polylingua/v1/`.
 */
class MqttWebSocketClient(
    private val http: OkHttpClient,
    private val url: String,
    private val clientId: String,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var packetId = 1
    private val inbound = ByteArrayOutputStream()
    private val pendingSubscriptions = LinkedHashSet<String>()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state
    var lastError: String? = null
        private set

    private val _messages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 128)
    /** (topic, UTF-8 payload) for every PUBLISH received. */
    val messages: SharedFlow<Pair<String, String>> = _messages

    fun connect() {
        if (_state.value == State.CONNECTING || _state.value == State.CONNECTED) return
        _state.value = State.CONNECTING
        val req = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(connectPacket().toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(inbound) {
                    inbound.write(bytes.toByteArray())
                    drainPackets()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "MQTT socket failure: ${t.message}")
                lastError = t.message ?: "Connection failed"
                _state.value = State.FAILED
                pingJob?.cancel()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = State.DISCONNECTED
                pingJob?.cancel()
            }
        })
    }

    fun subscribe(topic: String) {
        pendingSubscriptions += topic
        if (_state.value == State.CONNECTED) socket?.send(subscribePacket(topic).toByteString())
    }

    fun publish(topic: String, payload: String) {
        socket?.send(publishPacket(topic, payload.toByteArray(Charsets.UTF_8)).toByteString())
    }

    fun disconnect() {
        runCatching { socket?.send(byteArrayOf(0xE0.toByte(), 0x00).toByteString()) }
        socket?.close(1000, "bye")
        socket = null
        pingJob?.cancel()
        _state.value = State.DISCONNECTED
        scope.cancel()
    }

    // --------------------------------------------------------------------- packet parsing

    private fun drainPackets() {
        val buf = inbound.toByteArray()
        var pos = 0
        while (pos < buf.size) {
            if (buf.size - pos < 2) break
            val header = buf[pos].toInt() and 0xFF
            var mult = 1
            var len = 0
            var i = pos + 1
            var complete = false
            while (i < buf.size && i < pos + 5) {
                val b = buf[i].toInt() and 0xFF
                len += (b and 0x7F) * mult
                mult *= 128
                i++
                if (b and 0x80 == 0) { complete = true; break }
            }
            if (!complete || i + len > buf.size) break
            handlePacket(header, buf.copyOfRange(i, i + len))
            pos = i + len
        }
        inbound.reset()
        if (pos < buf.size) inbound.write(buf, pos, buf.size - pos)
    }

    private fun handlePacket(header: Int, body: ByteArray) {
        when (header shr 4) {
            2 -> { // CONNACK
                val rc = if (body.size >= 2) body[1].toInt() else 0
                if (rc == 0) {
                    _state.value = State.CONNECTED
                    pendingSubscriptions.forEach { socket?.send(subscribePacket(it).toByteString()) }
                    startPing()
                } else {
                    lastError = "Broker refused connection (code $rc)"
                    _state.value = State.FAILED
                }
            }
            3 -> { // PUBLISH
                if (body.size < 2) return
                val tlen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                if (body.size < 2 + tlen) return
                val topic = String(body, 2, tlen, Charsets.UTF_8)
                val qos = (header shr 1) and 0x03
                val start = 2 + tlen + if (qos > 0) 2 else 0
                val payload = if (start <= body.size) String(body, start, body.size - start, Charsets.UTF_8) else ""
                _messages.tryEmit(topic to payload)
            }
            9, 13 -> Unit // SUBACK, PINGRESP
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_SEC * 500L)
                socket?.send(byteArrayOf(0xC0.toByte(), 0x00).toByteString())
            }
        }
    }

    // --------------------------------------------------------------------- packet building

    private fun utf8(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf((b.size shr 8).toByte(), (b.size and 0xFF).toByte()) + b
    }

    private fun remainingLength(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var x = n
        do {
            var d = x % 128
            x /= 128
            if (x > 0) d = d or 0x80
            out.write(d)
        } while (x > 0)
        return out.toByteArray()
    }

    private fun packet(header: Int, body: ByteArray): ByteArray = byteArrayOf(header.toByte()) + remainingLength(body.size) + body

    private fun connectPacket(): ByteArray {
        val variable = utf8("MQTT") + byteArrayOf(0x04, 0x02, (KEEPALIVE_SEC shr 8).toByte(), (KEEPALIVE_SEC and 0xFF).toByte())
        return packet(0x10, variable + utf8(clientId))
    }

    private fun subscribePacket(topic: String): ByteArray {
        val id = nextId()
        return packet(0x82, byteArrayOf((id shr 8).toByte(), (id and 0xFF).toByte()) + utf8(topic) + byteArrayOf(0x00))
    }

    private fun publishPacket(topic: String, payload: ByteArray): ByteArray = packet(0x30, utf8(topic) + payload)

    @Synchronized
    private fun nextId(): Int {
        packetId = if (packetId >= 65535) 1 else packetId + 1
        return packetId
    }

    companion object {
        private const val TAG = "PolyLinguaMqtt"
        private const val KEEPALIVE_SEC = 60
    }
}

/** Lobby presence for the online language exchange (who is available right now). */
data class LobbyPeer(
    val id: String,
    val name: String,
    val nativeLanguage: String,
    val learning: String,
    val level: String,
    val lastSeen: Long,
)

/**
 * Internet signalling over MQTT: a shared lobby topic for presence ("hello" every 10 s) and one
 * inbox topic per peer for invites, SDP offers/answers and ICE candidates.
 */
class MqttSignalingChannel(
    http: OkHttpClient,
    url: String,
    override val selfId: String,
    private val displayName: String,
    private val nativeLanguage: String,
    private val level: String,
) : SignalingChannel {
    private val client = MqttWebSocketClient(http, url, "pl-$selfId")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<SignalMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<SignalMessage> = _incoming
    private val _peers = MutableStateFlow<List<LobbyPeer>>(emptyList())
    val peers: StateFlow<List<LobbyPeer>> = _peers
    val state: StateFlow<MqttWebSocketClient.State> get() = client.state
    val lastError: String? get() = client.lastError

    private val lobbyTopic = "polylingua/v1/lobby/en"
    private fun inbox(id: String) = "polylingua/v1/peer/$id"

    fun start() {
        client.subscribe(lobbyTopic)
        client.subscribe(inbox(selfId))
        client.connect()
        scope.launch {
            client.messages.collect { (topic, payload) ->
                val msg = SignalMessage.decode(payload) ?: return@collect
                if (msg.from == selfId) return@collect
                if (topic == lobbyTopic) {
                    when (msg.type) {
                        "hello" -> {
                            val b = msg.body
                            val peer = LobbyPeer(msg.from, b.optString("name", "Learner"), b.optString("native", "?"),
                                b.optString("learning", "en"), b.optString("level", ""), System.currentTimeMillis())
                            _peers.value = (_peers.value.filter { it.id != peer.id } + peer).sortedBy { it.name }
                        }
                        "leave" -> _peers.value = _peers.value.filter { it.id != msg.from }
                    }
                } else {
                    _incoming.tryEmit(msg)
                }
            }
        }
        scope.launch {
            while (isActive) {
                if (client.state.value == MqttWebSocketClient.State.CONNECTED) {
                    client.publish(lobbyTopic, SignalMessage("hello", selfId, "*", JSONObject()
                        .put("name", displayName).put("native", nativeLanguage).put("learning", "en").put("level", level)).encode())
                }
                val cutoff = System.currentTimeMillis() - 35_000
                _peers.value = _peers.value.filter { it.lastSeen >= cutoff }
                delay(10_000)
            }
        }
    }

    override fun send(message: SignalMessage) {
        client.publish(inbox(message.to), message.encode())
    }

    override fun close() {
        runCatching { client.publish(lobbyTopic, SignalMessage("leave", selfId, "*").encode()) }
        client.disconnect()
        scope.cancel()
    }
}
