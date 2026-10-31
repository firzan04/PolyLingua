package com.polylingua.edge.dsp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.polylingua.edge.nativebridge.CpuAffinityNative
import com.polylingua.edge.nativebridge.NativeLoader
import com.polylingua.edge.nativebridge.OboeNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Where audio is currently routed; drives sidetone vs. echo-cancellation decisions. */
enum class AudioRoute(val badge: String, val isHeadset: Boolean) {
    WIRED_HEADSET("🎧 3.5MM JACK ACTIVE", true),
    USB_HEADSET("🎧 USB-C HEADSET ACTIVE", true),
    BLUETOOTH("🎧 BLUETOOTH AUDIO", true),
    SPEAKER("🔊 STEREO SPEAKERS AEC", false),
}

data class RouteInfo(
    val route: AudioRoute = AudioRoute.SPEAKER,
    val outputDeviceId: Int = 0,
    val inputDeviceId: Int = 0,
    val deviceName: String = "",
)

/**
 * AudioDeviceCallback-based route monitor. Plugging a 3.5 mm headset switches capture to the Oboe
 * full-duplex sidetone engine; unplugging falls back to AudioRecord with AEC/NS/AGC.
 */
@Singleton
class AudioRouteMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _route = MutableStateFlow(RouteInfo())
    val route: StateFlow<RouteInfo> = _route

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        refresh()
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun refresh() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        fun out(vararg types: Int) = outputs.firstOrNull { it.type in types }
        fun inp(vararg types: Int) = inputs.firstOrNull { it.type in types }

        val wired = out(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        val usb = out(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val bt = out(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 /* TYPE_BLE_HEADSET */)
        val info = when {
            wired != null -> RouteInfo(
                AudioRoute.WIRED_HEADSET, wired.id,
                inp(AudioDeviceInfo.TYPE_WIRED_HEADSET)?.id ?: 0,
                wired.productName?.toString().orEmpty(),
            )
            usb != null -> RouteInfo(
                AudioRoute.USB_HEADSET, usb.id,
                inp(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)?.id ?: 0,
                usb.productName?.toString().orEmpty(),
            )
            bt != null -> RouteInfo(AudioRoute.BLUETOOTH, bt.id, 0, bt.productName?.toString().orEmpty())
            else -> RouteInfo(
                AudioRoute.SPEAKER,
                out(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)?.id ?: 0,
                inp(AudioDeviceInfo.TYPE_BUILTIN_MIC)?.id ?: 0,
                "Built-in speaker",
            )
        }
        _route.value = info
    }
}

enum class CaptureBackend(val label: String) {
    NONE("Idle"),
    AUDIO_RECORD("AudioRecord 16 kHz · AEC/NS/AGC"),
    AUDIO_RECORD_RAW("AudioRecord 16 kHz · voice recognition"),
    OBOE_SIDETONE("Oboe full-duplex · zero-latency sidetone"),
}

data class CaptureState(
    val running: Boolean = false,
    val backend: CaptureBackend = CaptureBackend.NONE,
    val route: AudioRoute = AudioRoute.SPEAKER,
    val echoCanceller: Boolean = false,
    val noiseSuppressor: Boolean = false,
    val gainControl: Boolean = false,
    val sidetoneLatencyMs: Double = 0.0,
    val error: String? = null,
)

/**
 * Single microphone owner for the whole app (Android allows one active capture per app).
 * Produces 16 kHz mono PCM16 chunks on [frames] for ASR, VAD, pitch tracking and recording.
 *
 *  - Headset routes: Oboe low-latency full-duplex engine (oboe_sidetone_engine.cpp) - the
 *    learner hears their own voice in real time (sidetone) while the same samples are
 *    decimated to 16 kHz for analysis.
 *  - Speaker route: AudioRecord(VOICE_RECOGNITION) - the flat, unprocessed path Android reserves
 *    for speech recognisers - followed by [SpeechLevelNormalizer] (70 Hz high-pass + speech-gated
 *    gain). VOICE_COMMUNICATION with AEC/NS/AGC is used only when [start] is asked for echo
 *    cancellation (hands-free conversation while a voice may be playing), because the telephony
 *    noise suppressor smears consonants and measurably lowers recognition accuracy.
 *
 * The capture loop runs on a dedicated thread pinned to the Cortex-A55 cluster.
 */
@Singleton
class OboeAudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeMonitor: AudioRouteMonitor,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK = 320          // 20 ms
        private const val TAG = "PolyLinguaCapture"
        const val WAVEFORM_BARS = 160
    }

    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 20 ms chunks of 16 kHz mono PCM16 while capture is running. */
    val frames: SharedFlow<ShortArray> = _frames

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state

    private val _levelDb = MutableStateFlow(-90f)
    val levelDb: StateFlow<Float> = _levelDb

    private val _waveform = MutableStateFlow(FloatArray(WAVEFORM_BARS))
    /** Rolling peak envelope (0..1), newest last, for the live waveform canvas. */
    val waveform: StateFlow<FloatArray> = _waveform

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var oboeHandle = 0L
    private val recordingLock = Any()
    private val effects = ArrayList<AudioEffect>(3)
    private var recording: PcmUtils.PcmAccumulator? = null

    /** Applies [SpeechLevelNormalizer] to captured audio (set before [start]). */
    @Volatile var autoGain: Boolean = true
    private val normalizer = SpeechLevelNormalizer(SAMPLE_RATE)
    @Volatile private var echoCancel = false

    val isRunning: Boolean get() = running.get()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts capture (idempotent). [sidetone] enables real-time monitoring on headset routes.
     * Returns false if the microphone could not be opened.
     */
    @Synchronized
    fun start(sidetone: Boolean = true, sidetoneGain: Float = 0.85f, echoCancellation: Boolean = false): Boolean {
        if (running.get()) {
            setSidetone(sidetone, sidetoneGain)
            return true
        }
        echoCancel = echoCancellation
        synchronized(deliverLock) { normalizer.reset() }
        if (!hasPermission()) {
            _state.value = CaptureState(error = "Microphone permission not granted")
            return false
        }
        routeMonitor.refresh()
        val route = routeMonitor.route.value
        running.set(true)
        val ready = java.util.concurrent.CountDownLatch(1)
        var ok = false
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            if (NativeLoader.ensureLoaded()) runCatching { CpuAffinityNative.nativePinToLittleCores() }
            val useOboe = route.route.isHeadset && route.route != AudioRoute.BLUETOOTH && NativeLoader.loaded
            if (useOboe && startOboe(route, sidetone, sidetoneGain)) {
                ok = true
                ready.countDown()
                oboeLoop()
            } else {
                val record = openAudioRecord(route)
                if (record == null) {
                    running.set(false)
                    ready.countDown()
                    return@Thread
                }
                ok = true
                ready.countDown()
                audioRecordLoop(record)
            }
        }, "pl-a55-capture").apply { isDaemon = true; start() }
        ready.await(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!ok) running.set(false)
        return ok
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(1500)
        thread = null
        _levelDb.value = -90f
        _waveform.value = FloatArray(WAVEFORM_BARS)
        _state.value = CaptureState(route = routeMonitor.route.value.route)
    }

    fun setSidetone(enabled: Boolean, gain: Float) {
        val h = oboeHandle
        if (h != 0L && NativeLoader.loaded) {
            OboeNative.nativeSetMonitoring(h, enabled)
            OboeNative.nativeSetGain(h, gain.coerceIn(0f, 2f))
        }
    }

    /** Begins accumulating everything captured from now on (for WAV export and offline analysis). */
    fun beginRecording() = synchronized(recordingLock) { recording = PcmUtils.PcmAccumulator(SAMPLE_RATE * 60) }

    /** Ends accumulation and returns the captured samples (empty if none). */
    fun endRecording(): ShortArray = synchronized(recordingLock) {
        val r = recording
        recording = null
        r?.snapshot() ?: ShortArray(0)
    }

    /** Seconds accumulated by the current recording. */
    fun recordedSeconds(): Double = synchronized(recordingLock) { recording?.durationSec(SAMPLE_RATE) ?: 0.0 }

    // ------------------------------------------------------------------------- Oboe path

    private fun startOboe(route: RouteInfo, sidetone: Boolean, gain: Float): Boolean {
        return try {
            if (oboeHandle == 0L) oboeHandle = OboeNative.nativeCreate()
            val rc = OboeNative.nativeStart(oboeHandle, gain, route.inputDeviceId, route.outputDeviceId)
            if (rc != 0) {
                Log.w(TAG, "Oboe start failed ($rc); falling back to AudioRecord")
                false
            } else {
                OboeNative.nativeSetMonitoring(oboeHandle, sidetone)
                _state.value = CaptureState(
                    running = true, backend = CaptureBackend.OBOE_SIDETONE, route = route.route,
                    sidetoneLatencyMs = OboeNative.nativeLatencyMs(oboeHandle),
                )
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Oboe unavailable", t)
            false
        }
    }

    private fun oboeLoop() {
        val buf = ShortArray(CHUNK * 4)
        var latencyTick = 0
        try {
            while (running.get()) {
                val n = OboeNative.nativeRead(oboeHandle, buf, buf.size)
                if (n > 0) {
                    deliver(buf, n)
                } else {
                    if (!OboeNative.nativeIsRunning(oboeHandle)) {
                        // Headset unplugged mid-session: the stream disconnected. Reopen on the new route.
                        Log.i(TAG, "Oboe stream disconnected; switching backend")
                        OboeNative.nativeStop(oboeHandle)
                        routeMonitor.refresh()
                        val record = openAudioRecord(routeMonitor.route.value) ?: break
                        audioRecordLoop(record)
                        return
                    }
                    Thread.sleep(8)
                }
                if (++latencyTick % 250 == 0) {
                    _state.value = _state.value.copy(sidetoneLatencyMs = OboeNative.nativeLatencyMs(oboeHandle))
                }
            }
        } finally {
            if (oboeHandle != 0L) OboeNative.nativeStop(oboeHandle)
        }
    }

    // -------------------------------------------------------------------- AudioRecord path

    @SuppressLint("MissingPermission")
    private fun openAudioRecord(route: RouteInfo): AudioRecord? {
        val speaker = !route.route.isHeadset
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf * 2, SAMPLE_RATE / 5 * 2)
        val processed = speaker && echoCancel
        val source = if (processed) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", t)
            _state.value = CaptureState(error = "Microphone unavailable: ${t.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _state.value = CaptureState(error = "Microphone is busy (another app may be recording)")
            return null
        }
        var aec = false
        var ns = false
        var agc = false
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        if (processed) {
            val session = record.audioSessionId
            fun enable(effect: AudioEffect?): Boolean {
                if (effect == null) return false
                effects += effect
                effect.setEnabled(true)
                return effect.getEnabled()
            }
            if (AcousticEchoCanceler.isAvailable()) aec = runCatching { enable(AcousticEchoCanceler.create(session)) }.getOrDefault(false)
            if (NoiseSuppressor.isAvailable()) ns = runCatching { enable(NoiseSuppressor.create(session)) }.getOrDefault(false)
            if (AutomaticGainControl.isAvailable()) agc = runCatching { enable(AutomaticGainControl.create(session)) }.getOrDefault(false)
        }
        if (route.inputDeviceId > 0) {
            val dev = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == route.inputDeviceId }
            if (dev != null) record.setPreferredDevice(dev)
        }
        return try {
            record.startRecording()
            _state.value = CaptureState(
                running = true,
                backend = if (processed) CaptureBackend.AUDIO_RECORD else CaptureBackend.AUDIO_RECORD_RAW,
                route = route.route, echoCanceller = aec, noiseSuppressor = ns, gainControl = agc,
            )
            record
        } catch (t: Throwable) {
            record.release()
            _state.value = CaptureState(error = "Could not start recording: ${t.message}")
            null
        }
    }

    private fun audioRecordLoop(record: AudioRecord) {
        val buf = ShortArray(CHUNK)
        try {
            while (running.get()) {
                val n = record.read(buf, 0, CHUNK)
                if (n > 0) deliver(buf, n) else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                    Thread.sleep(10)
                }
            }
        } finally {
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            effects.clear()
            record.release()
        }
    }

    /**
     * Feeds 16 kHz mono PCM captured elsewhere (e.g. the WebRTC audio device module during a live
     * call, which owns the microphone) into [frames], so ASR and pitch tracking work unchanged.
     */
    fun injectExternal(samples16k: ShortArray, n: Int = samples16k.size) {
        if (n <= 0) return
        // WebRTC's audio processing already applied AGC/NS: deliver as-is.
        synchronized(deliverLock) { deliverLocked(samples16k, n, normalize = false) }
    }

    // ------------------------------------------------------------------------- fan-out

    private var envelope = FloatArray(WAVEFORM_BARS)
    private var envelopeTick = 0

    private val deliverLock = Any()

    private fun deliver(buf: ShortArray, n: Int) {
        synchronized(deliverLock) { deliverLocked(buf, n) }
    }

    private fun deliverLocked(buf: ShortArray, n: Int, normalize: Boolean = true) {
        val chunk = buf.copyOf(n)
        if (normalize && autoGain) normalizer.process(chunk, n)
        synchronized(recordingLock) { recording?.append(chunk, n) }
        _frames.tryEmit(chunk)

        var sum = 0.0
        var peak = 0
        for (i in 0 until n) {
            val v = chunk[i].toInt()
            sum += v.toDouble() * v
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        val rms = sqrt(sum / max(1, n)) / 32768.0
        _levelDb.value = if (rms <= 1e-6) -90f else (20 * log10(rms)).toFloat()

        // Shift the envelope one bar per chunk (20 ms) -> 3.2 s window at 160 bars.
        System.arraycopy(envelope, 1, envelope, 0, WAVEFORM_BARS - 1)
        envelope[WAVEFORM_BARS - 1] = (peak / 32768f).coerceIn(0f, 1f)
        if (++envelopeTick % 2 == 0) _waveform.value = envelope.copyOf()
    }
}
