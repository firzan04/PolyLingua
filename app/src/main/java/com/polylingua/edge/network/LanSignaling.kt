package com.polylingua.edge.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** A partner found on the local network (NSD) or over Wi-Fi Direct. */
data class LanPeer(val id: String, val name: String, val host: String, val port: Int, val via: String)

/**
 * Offline-capable local signalling: each device runs a tiny TCP server (newline-delimited JSON
 * [SignalMessage]s) advertised over NSD/mDNS as `_polylingua._tcp`, so two phones on the same
 * Wi-Fi or hotspot can call each other with no internet. Wi-Fi Direct ([WifiDirectHelper]) forms
 * a group when there is no router at all; the client then dials the group owner directly.
 */
class LanSignalingChannel(
    private val context: Context,
    override val selfId: String,
    private val displayName: String,
) : SignalingChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var server: ServerSocket? = null
    private val connections = ConcurrentHashMap<String, Pair<Socket, BufferedWriter>>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _incoming = MutableSharedFlow<SignalMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<SignalMessage> = _incoming
    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers
    var port: Int = 0
        private set

    fun start(fixedPort: Int = 0) {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(fixedPort))
        server = ss
        port = ss.localPort
        scope.launch {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                handle(sock, null)
            }
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("polylingua-nsd").apply { setReferenceCounted(false); acquire() }
        register()
        discover()
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = "PolyLingua-${displayName.take(20).replace(Regex("[^A-Za-z0-9 ]"), "")}-${selfId.take(6)}"
            serviceType = SERVICE_TYPE
            port = this@LanSignalingChannel.port
            setAttribute("id", selfId)
            setAttribute("name", displayName.take(30))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "NSD register failed $errorCode") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    @Suppress("DEPRECATION")
    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "NSD discovery failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == serviceInfo.serviceName || serviceInfo.serviceName.endsWith(it.id.take(6)) }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.endsWith(selfId.take(6))) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: info.serviceName
                        if (id == selfId) return
                        val name = info.attributes["name"]?.toString(Charsets.UTF_8) ?: info.serviceName
                        val host = info.host?.hostAddress ?: return
                        val peer = LanPeer(id, name, host, info.port, "Wi-Fi LAN")
                        _peers.value = (_peers.value.filter { it.id != id } + peer).sortedBy { it.name }
                    }
                })
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** Adds a peer reachable at a known address (Wi-Fi Direct group owner). */
    fun addDirectPeer(host: String, port: Int, name: String) {
        val peer = LanPeer("direct-$host", name, host, port, "Wi-Fi Direct")
        _peers.value = (_peers.value.filter { it.id != peer.id } + peer)
    }

    /** Opens (or reuses) a TCP connection to [peer] so messages can be sent to it. */
    fun connect(peer: LanPeer) {
        if (connections.containsKey(peer.id)) return
        scope.launch {
            val sock = runCatching { Socket().apply { connect(InetSocketAddress(peer.host, peer.port), 5000) } }.getOrElse {
                Log.w(TAG, "Cannot reach ${peer.host}:${peer.port}: ${it.message}")
                return@launch
            }
            handle(sock, peer.id)
            send(SignalMessage("hello", selfId, peer.id, org.json.JSONObject().put("name", displayName)))
        }
    }

    private fun handle(sock: Socket, knownId: String?) {
        val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
        if (knownId != null) connections[knownId] = sock to writer
        scope.launch {
            var peerId = knownId
            runCatching {
                BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val msg = SignalMessage.decode(line) ?: continue
                        if (peerId == null || peerId != msg.from) {
                            peerId = msg.from
                            connections[msg.from] = sock to writer
                            if (msg.type == "hello") {
                                val host = sock.inetAddress?.hostAddress ?: ""
                                val name = msg.body.optString("name", "Partner")
                                _peers.value = (_peers.value.filter { it.id != msg.from } + LanPeer(msg.from, name, host, 0, "Wi-Fi LAN"))
                            }
                        }
                        if (msg.type != "hello") _incoming.tryEmit(msg)
                    }
                }
            }
            peerId?.let { connections.remove(it) }
            runCatching { sock.close() }
        }
    }

    override fun send(message: SignalMessage) {
        val conn = connections[message.to] ?: run {
            _peers.value.firstOrNull { it.id == message.to && it.port > 0 }?.let { connect(it) }
            return
        }
        scope.launch {
            runCatching {
                synchronized(conn.second) {
                    conn.second.write(message.encode())
                    conn.second.newLine()
                    conn.second.flush()
                }
            }.onFailure { connections.remove(message.to) }
        }
    }

    override fun close() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        connections.values.forEach { runCatching { it.first.close() } }
        connections.clear()
        runCatching { server?.close() }
        multicastLock?.let { runCatching { it.release() } }
        scope.cancel()
    }

    companion object {
        const val SERVICE_TYPE = "_polylingua._tcp."
        const val WIFI_DIRECT_PORT = 47321
        private const val TAG = "PolyLinguaLan"
    }
}

/**
 * Wi-Fi Direct (P2P) helper for router-less sessions: discovers nearby phones, connects, and
 * reports the group owner's address so the client can open a TCP signalling link to it.
 */
class WifiDirectHelper(private val context: Context) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _devices = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val devices: StateFlow<List<WifiP2pDevice>> = _devices
    private val _connection = MutableStateFlow<WifiP2pInfo?>(null)
    val connection: StateFlow<WifiP2pInfo?> = _connection
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    val supported: Boolean get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val m = manager ?: return
        if (channel == null) channel = m.initialize(context, Looper.getMainLooper(), null)
        val ch = channel ?: return
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> if (hasPermission()) {
                            m.requestPeers(ch) { list -> _devices.value = list?.deviceList?.toList().orEmpty() }
                        }
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> m.requestConnectionInfo(ch) { info ->
                            _connection.value = info?.takeIf { it.groupFormed }
                            _status.value = if (info?.groupFormed == true) {
                                if (info.isGroupOwner) "Connected · this phone is group owner" else "Connected to ${info.groupOwnerAddress?.hostAddress}"
                            } else "Not connected"
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        }
        if (!hasPermission()) {
            _status.value = "Nearby-devices permission required"
            return
        }
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _status.value = "Searching for nearby phones…" }
            override fun onFailure(reason: Int) { _status.value = "Discovery failed (code $reason) - is Wi-Fi on?" }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val m = manager ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _status.value = "Inviting ${device.deviceName}…" }
            override fun onFailure(reason: Int) { _status.value = "Connection failed (code $reason)" }
        })
    }

    fun stop() {
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            runCatching { m.removeGroup(ch, null) }
            runCatching { m.stopPeerDiscovery(ch, null) }
        }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        _connection.value = null
        _status.value = "Idle"
    }
}
