package com.polylingua.edge.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.util.LruCache
import com.polylingua.edge.core.AccentPreference
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.DiagLog
import com.polylingua.edge.core.NativeLoadGuard
import com.polylingua.edge.core.UserNotices
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.dsp.TarsosWsolaTimeStretcher
import com.polylingua.edge.nativebridge.NativeLoader
import com.polylingua.edge.nativebridge.SherpaNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/** Mono PCM clip (float -1..1). */
class AudioClip(val samples: FloatArray, val sampleRate: Int) {
    val durationSec: Double get() = samples.size.toDouble() / sampleRate
}

/** A selectable voice: neural (sherpa-onnx pack) or an Android system voice. */
data class SynthVoice(
    val id: String,
    val label: String,
    val accent: String,
    val female: Boolean,
    val neural: NeuralVoice? = null,
    val systemLocale: Locale? = null,
    /** Exact Android voice name (e.g. "en-gb-x-gbd-local"); null = best voice for [systemLocale]. */
    val systemVoiceName: String? = null,
) {
    val isNeural: Boolean get() = neural != null
}

/** Result of the last synthesis attempt with a voice. */
sealed interface VoiceHealth {
    data class Ok(val millis: Long) : VoiceHealth
    data class Failed(val reason: String, val at: Long = System.currentTimeMillis()) : VoiceHealth
}

/** What the phone's Android text-to-speech engine offers. */
data class SystemTtsInfo(
    val ready: Boolean = false,
    val enginePackage: String = "",
    val engineLabel: String = "",
    val localeAvailable: Map<String, Boolean> = emptyMap(),
    val voiceCount: Int = 0,
    val error: String? = null,
)

data class PlaybackState(val playing: Boolean = false, val positionSec: Double = 0.0, val durationSec: Double = 0.0, val label: String = "")

/**
 * Text-to-speech. Neural voices come from installed sherpa-onnx packs (Kokoro, Kitten, Piper); the
 * Android engine covers accents those packs lack (en-AU, en-IN, id-ID...) and is the fallback.
 * Every voice has a health record: a voice that fails is skipped for 10 minutes and the next best
 * voice (same accent first) is used, with a notice. When even file synthesis fails, the Android
 * engine speaks directly so the learner always hears something.
 * Slow-down for shadowing uses WSOLA time-stretching, which preserves pitch and formants.
 */
@Singleton
class SpeechSynthesisEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    private val settings: AppSettings,
    private val residency: ModelResidency,
    private val guard: NativeLoadGuard,
) : ResidentModel {

    private val lock = Any()
    private var handle = 0L
    private var loadedPack: String? = null

    override val residencyKey = "tts"
    override val residentMb: Int get() = loadedPack?.let { ModelCatalog.byId(it)?.residentMb } ?: 0

    private var systemTts: TextToSpeech? = null
    private var systemReady: CompletableDeferred<Boolean>? = null
    private val systemMutex = Mutex()
    private val pendingUtterances = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val cache = LruCache<String, AudioClip>(24)

    val player = PcmPlayer()

    private val _health = MutableStateFlow<Map<String, VoiceHealth>>(emptyMap())
    val health: StateFlow<Map<String, VoiceHealth>> = _health

    private val _systemInfo = MutableStateFlow(SystemTtsInfo())
    val systemInfo: StateFlow<SystemTtsInfo> = _systemInfo

    private val _synthesizing = MutableStateFlow<String?>(null)
    /** Label of the voice currently being generated (UI shows a "preparing voice" chip). */
    val synthesizing: StateFlow<String?> = _synthesizing

    @Volatile private var systemVoices: List<Voice> = emptyList()

    // ------------------------------------------------------------------------- voices

    fun installedNeuralVoices(): List<NeuralVoice> =
        ModelCatalog.NEURAL_VOICES.filter { v ->
            ModelCatalog.byId(v.packId)?.let { models.isInstalled(it) && !guard.isBlocked(it.id) } == true
        }

    private val localeVoices = listOf(
        SynthVoice("system:en-GB", "Android voice (British)", "en-GB", true, systemLocale = Locale.UK),
        SynthVoice("system:en-US", "Android voice (American)", "en-US", true, systemLocale = Locale.US),
        SynthVoice("system:en-AU", "Android voice (Australian)", "en-AU", true, systemLocale = Locale("en", "AU")),
        SynthVoice("system:en-IE", "Android voice (Irish)", "en-IE", false, systemLocale = Locale("en", "IE")),
        SynthVoice("system:en-NZ", "Android voice (New Zealand)", "en-NZ", true, systemLocale = Locale("en", "NZ")),
        SynthVoice("system:en-IN", "Android voice (Indian)", "en-IN", true, systemLocale = Locale("en", "IN")),
        SynthVoice("system:id-ID", "Android voice (Bahasa Indonesia)", "id-ID", false, systemLocale = Locale("id", "ID")),
    )

    /**
     * Neural voices first, then the Android locales (only those the engine reports as available,
     * once known) and each individual offline Android voice for English and Indonesian.
     */
    fun availableVoices(): List<SynthVoice> {
        val neural = installedNeuralVoices().map { SynthVoice(it.id, it.name, it.accent, it.female, neural = it) }
        val info = _systemInfo.value
        val locales = localeVoices.filter { v -> info.localeAvailable[v.accent] != false }
        val named = systemVoices
            .filter { it.locale.language == "en" || it.locale.language == "id" }
            .sortedWith(compareBy<Voice>({ it.locale.toLanguageTag() }, { it.name }))
            .map { v ->
                val tag = v.locale.toLanguageTag()
                SynthVoice(
                    id = "sysvoice:${v.name}",
                    label = "Android $tag · ${v.name.substringAfter("-x-", v.name).substringBefore("-local")}",
                    accent = tag, female = true, systemLocale = v.locale, systemVoiceName = v.name,
                )
            }
        return neural + locales + named
    }

    /** Every locale voice, including ones the engine lacks (for the "install voice data" hint). */
    fun unavailableLocaleVoices(): List<SynthVoice> {
        val info = _systemInfo.value
        return localeVoices.filter { info.localeAvailable[it.accent] == false }
    }

    fun voiceById(id: String): SynthVoice? = availableVoices().firstOrNull { it.id == id }

    private fun recentlyFailed(id: String): Boolean {
        val h = _health.value[id] as? VoiceHealth.Failed ?: return false
        return System.currentTimeMillis() - h.at < 10 * 60_000
    }

    /** Resolves the first healthy voice from a preference list of ids. */
    fun resolve(preferences: List<String>): SynthVoice {
        val all = availableVoices()
        for (p in preferences) all.firstOrNull { it.id == p && !recentlyFailed(it.id) }?.let { return it }
        return defaultVoice()
    }

    fun defaultVoice(): SynthVoice {
        val s = settings.settings.value
        voiceById(s.ttsVoiceId)?.takeIf { !recentlyFailed(it.id) }?.let { return it }
        val all = availableVoices().filter { !recentlyFailed(it.id) }.ifEmpty { availableVoices() }
        val wanted = s.accent.localeTag
        return all.firstOrNull { it.isNeural && it.accent == wanted }
            ?: all.firstOrNull { it.isNeural }
            ?: all.firstOrNull { it.accent == wanted }
            ?: all.firstOrNull { s.accent == AccentPreference.BRITISH_RP && it.accent == "en-GB" }
            ?: all.first()
    }

    /** Ordered alternatives when [voice] fails: same-accent neural, any neural, same locale, en-GB/US. */
    private fun fallbacksFor(voice: SynthVoice): List<SynthVoice> {
        val all = availableVoices().filter { it.id != voice.id && !recentlyFailed(it.id) }
        val lang = voice.accent.substringBefore('-')
        return (all.filter { it.isNeural && it.accent == voice.accent } +
            all.filter { it.isNeural && lang == "en" } +
            all.filter { !it.isNeural && it.accent == voice.accent && it.systemVoiceName == null } +
            all.filter { it.id == "system:en-GB" || it.id == "system:en-US" })
            .distinctBy { it.id }
            .take(4)
    }

    private fun markHealth(id: String, h: VoiceHealth) {
        _health.value = _health.value + (id to h)
    }

    // ---------------------------------------------------------------------- synthesis

    /**
     * Synthesises [text]. [tempo] (0.5-1.5) is applied with WSOLA after synthesis so slowed
     * models keep natural pitch. Falls back to other voices; returns null if none produced audio.
     */
    suspend fun synthesize(text: String, voice: SynthVoice = defaultVoice(), tempo: Double = 1.0): AudioClip? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val base = synthesizeExact(clean, voice) ?: run {
            var alt: AudioClip? = null
            for (v in fallbacksFor(voice)) {
                alt = synthesizeExact(clean, v)
                if (alt != null) {
                    val reason = (_health.value[voice.id] as? VoiceHealth.Failed)?.reason.orEmpty()
                    UserNotices.post("\"${voice.label}\" can't speak right now${if (reason.isNotBlank()) " ($reason)" else ""} - using ${v.label}.", UserNotices.Kind.WARNING, dedupeMs = 60_000)
                    break
                }
            }
            alt
        } ?: return null
        val t = TarsosWsolaTimeStretcher.quantize(tempo)
        if (t == 1.0) return base
        return withContext(CoreExecutors.dsp) { AudioClip(TarsosWsolaTimeStretcher.stretch(base.samples, base.sampleRate, t), base.sampleRate) }
    }

    /** One voice, no fallback; records the voice's health. */
    private suspend fun synthesizeExact(text: String, voice: SynthVoice): AudioClip? {
        val key = "${voice.id}|$text"
        cache.get(key)?.let { return it }
        if (recentlyFailed(voice.id)) return null
        val t0 = SystemClock.elapsedRealtime()
        _synthesizing.value = voice.label
        val result = try {
            if (voice.neural != null) {
                if (!NativeLoader.ensureLoaded()) Result.failure<AudioClip>(IllegalStateException(NativeLoader.loadError ?: "native runtime unavailable"))
                else synthNeural(text, voice.neural)
            } else {
                synthSystem(text, voice.systemLocale ?: Locale.forLanguageTag(voice.accent), voice.systemVoiceName)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure<AudioClip>(t)
        } finally {
            _synthesizing.value = null
        }
        val clip = result.getOrNull()
        if (clip != null) {
            markHealth(voice.id, VoiceHealth.Ok(SystemClock.elapsedRealtime() - t0))
            cache.put(key, clip)
        } else {
            val reason = result.exceptionOrNull()?.message ?: "no audio produced"
            markHealth(voice.id, VoiceHealth.Failed(reason))
            DiagLog.w(TAG, "Voice ${voice.id} failed: $reason")
        }
        return clip
    }

    /**
     * Synthesise and play; suspends until playback finishes or is stopped. When no voice can
     * render to PCM, the Android engine speaks the text directly. Returns false only when nothing
     * could be played at all.
     */
    suspend fun speak(text: String, voice: SynthVoice = defaultVoice(), tempo: Double = 1.0, label: String = ""): Boolean {
        val clip = synthesize(text, voice, tempo)
        if (clip != null) {
            player.play(clip, label.ifBlank { text.take(60) })
            return true
        }
        val spoken = speakDirect(text, voice.systemLocale ?: Locale.forLanguageTag(voice.accent), tempo.toFloat(), label.ifBlank { text.take(60) })
        if (!spoken) {
            UserNotices.post("No voice could read this aloud. Open Models & settings → Voice to test voices or install Android voice data.", UserNotices.Kind.ERROR, dedupeMs = 30_000)
        }
        return spoken
    }

    fun stop() {
        player.stop()
        runCatching { systemTts?.stop() }
    }

    /** Synthesises a short phrase with [voice] (no playback) and returns its health. */
    suspend fun testVoice(voice: SynthVoice): VoiceHealth {
        _health.value = _health.value - voice.id
        synchronized(lock) { cache.evictAll() }
        val clip = synthesizeExact("Testing, one, two, three.", voice)
        return _health.value[voice.id] ?: if (clip != null) VoiceHealth.Ok(0) else VoiceHealth.Failed("no audio produced")
    }

    /** Re-tests every listed voice; results land in [health]. */
    suspend fun testAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        refreshSystemVoices()
        val list = availableVoices()
        for ((i, v) in list.withIndex()) {
            onProgress(i, list.size)
            testVoice(v)
        }
        onProgress(list.size, list.size)
    }

    private suspend fun synthNeural(text: String, voice: NeuralVoice): Result<AudioClip> = withContext(CoreExecutors.inference) {
        val pack = ModelCatalog.byId(voice.packId) ?: return@withContext Result.failure<AudioClip>(IllegalStateException("unknown pack"))
        val dir = models.locate(pack) ?: return@withContext Result.failure<AudioClip>(IllegalStateException("${pack.title} is not installed"))
        synchronized(lock) {
            if (loadedPack != pack.id || handle == 0L) {
                if (handle != 0L) SherpaNative.nativeDestroyTts(handle)
                handle = 0L
                loadedPack = null
                residency.reserve(residencyKey, pack.residentMb)
                val threads = CoreExecutors.inferenceThreads
                val data = "$dir/espeak-ng-data"
                handle = guard.guarded(pack.id) {
                    when (pack.id) {
                        ModelCatalog.TTS_KOKORO.id -> SherpaNative.nativeCreateTts("kokoro", "$dir/model.int8.onnx", "$dir/voices.bin", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                        ModelCatalog.TTS_KITTEN.id -> SherpaNative.nativeCreateTts("kitten", "$dir/model.fp16.onnx", "$dir/voices.bin", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                        ModelCatalog.TTS_PIPER_ALAN.id -> SherpaNative.nativeCreateTts("vits", "$dir/en_GB-alan-medium.onnx", "", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                        ModelCatalog.TTS_PIPER_LESSAC.id -> SherpaNative.nativeCreateTts("vits", "$dir/en_US-lessac-medium.onnx", "", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                        else -> 0L
                    }
                }
                if (handle == 0L) return@withContext Result.failure<AudioClip>(IllegalStateException("${pack.title} failed to load (see diagnostics)"))
                loadedPack = pack.id
                residency.register(this@SpeechSynthesisEngine)
                val speakers = SherpaNative.nativeTtsNumSpeakers(handle)
                DiagLog.i(TAG, "Loaded ${pack.id}: ${SherpaNative.nativeTtsSampleRate(handle)} Hz, $speakers speaker(s)")
            }
            residency.touch(residencyKey)
            val speakers = SherpaNative.nativeTtsNumSpeakers(handle)
            if (speakers > 0 && voice.speakerId >= speakers) {
                return@withContext Result.failure<AudioClip>(IllegalStateException("voice #${voice.speakerId} not in ${pack.title} ($speakers voices)"))
            }
            val sr = SherpaNative.nativeTtsSampleRate(handle)
            val out = ArrayList<FloatArray>()
            // Synthesize sentence by sentence: bounded latency and memory on long model answers.
            for (sentence in splitForTts(text)) {
                val pcm = SherpaNative.nativeTtsGenerate(handle, sentence, voice.speakerId, 1.0f)
                if (pcm.isNotEmpty()) out += pcm
            }
            if (out.isEmpty() || out.all { c -> c.all { kotlin.math.abs(it) < 1e-4f } }) {
                Result.failure<AudioClip>(IllegalStateException("${pack.title} produced silence"))
            } else {
                Result.success(AudioClip(concat(out, sr / 8), sr))
            }
        }
    }

    /** Loads the default neural voice in the background so the first sentence is quick. */
    suspend fun prewarm() {
        val v = defaultVoice()
        if (v.isNeural) runCatching { synthesizeExact("Hi.", v) }
    }

    override fun releaseForMemory() {
        synchronized(lock) {
            if (handle != 0L) SherpaNative.nativeDestroyTts(handle)
            handle = 0L
            loadedPack = null
        }
        residency.unregister(residencyKey)
    }

    // ------------------------------------------------------------------ Android engine

    @Volatile private var systemOk = false

    private suspend fun ensureSystemTts(): TextToSpeech? {
        if (systemOk) systemTts?.let { return it }
        var create = false
        val ready = synchronized(lock) {
            systemReady ?: CompletableDeferred<Boolean>().also { systemReady = it; create = true }
        }
        if (create) {
            withContext(Dispatchers.Main) {
                systemTts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        val ok = withTimeoutOrNull(8000) { ready.await() } ?: false
        if (!ok) {
            synchronized(lock) { systemReady = null }
            systemTts?.shutdown()
            systemTts = null
            _systemInfo.value = SystemTtsInfo(ready = false, error = "Android text-to-speech engine did not start (install or enable one in Android settings)")
            DiagLog.w(TAG, "Android TTS engine failed to initialise")
            return null
        }
        val tts = systemTts ?: return null
        if (!systemOk) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { utteranceId?.let { pendingUtterances.remove(it)?.complete(true) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceId?.let { pendingUtterances.remove(it)?.complete(false) } }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    DiagLog.w(TAG, "Android TTS error $errorCode for $utteranceId")
                    utteranceId?.let { pendingUtterances.remove(it)?.complete(false) }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pendingUtterances.remove(it)?.complete(false) } }
            })
            systemOk = true
            refreshSystemInfo(tts)
        }
        return tts
    }

    /** Queries the Android engine for its offline voices and per-locale availability. */
    suspend fun refreshSystemVoices() {
        val tts = ensureSystemTts() ?: return
        withContext(Dispatchers.IO) { refreshSystemInfo(tts) }
    }

    private fun refreshSystemInfo(tts: TextToSpeech) {
        val voices = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (it.features ?: emptySet()) }
        systemVoices = voices
        val avail = localeVoices.associate { v ->
            val loc = v.systemLocale ?: Locale.forLanguageTag(v.accent)
            val code = runCatching { tts.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            val hasVoice = voices.any { it.locale.language == loc.language && it.locale.country == loc.country }
            v.accent to (code >= TextToSpeech.LANG_COUNTRY_AVAILABLE && (hasVoice || voices.isEmpty()))
        }
        val pkg = runCatching { tts.defaultEngine }.getOrNull().orEmpty()
        val label = runCatching { tts.engines.firstOrNull { it.name == pkg }?.label }.getOrNull() ?: pkg
        _systemInfo.value = SystemTtsInfo(true, pkg, label, avail, voices.size)
        DiagLog.i(TAG, "Android TTS: $label ($pkg), ${voices.size} offline voices, locales $avail")
    }

    private suspend fun synthSystem(text: String, locale: Locale, voiceName: String?): Result<AudioClip> {
        val tts = ensureSystemTts() ?: return Result.failure<AudioClip>(IllegalStateException("Android TTS engine unavailable"))
        return systemMutex.withLock {
            withContext(Dispatchers.IO) {
                val avail = tts.isLanguageAvailable(locale)
                if (avail < TextToSpeech.LANG_AVAILABLE) {
                    return@withContext Result.failure<AudioClip>(IllegalStateException("voice data for ${locale.toLanguageTag()} is not installed"))
                }
                val exact = voiceName?.let { n -> systemVoices.firstOrNull { it.name == n } }
                val voice: Voice? = exact ?: runCatching {
                    systemVoices.filter { it.locale.language == locale.language && it.locale.country == locale.country }
                        .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }))
                        .firstOrNull()
                }.getOrNull()
                val setOk = if (voice != null) tts.setVoice(voice) == TextToSpeech.SUCCESS else false
                if (!setOk && tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                    return@withContext Result.failure<AudioClip>(IllegalStateException("could not select ${locale.toLanguageTag()}"))
                }
                val file = File.createTempFile("tts_", ".wav", context.cacheDir)
                try {
                    val id = "pl-${System.nanoTime()}"
                    val done = CompletableDeferred<Boolean>()
                    pendingUtterances[id] = done
                    if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                        pendingUtterances.remove(id)
                        return@withContext Result.failure<AudioClip>(IllegalStateException("the Android engine refused to render audio"))
                    }
                    val ok = withTimeoutOrNull(10_000L + text.length * 80L) { done.await() } ?: false
                    pendingUtterances.remove(id)
                    if (!ok || file.length() < 100) {
                        return@withContext Result.failure<AudioClip>(IllegalStateException("the Android engine did not finish rendering"))
                    }
                    val wav = file.inputStream().use { PcmUtils.readWav(it) }
                    if (wav.samples.isEmpty()) Result.failure<AudioClip>(IllegalStateException("empty audio")) else Result.success(AudioClip(wav.samples, wav.sampleRate))
                } catch (t: Throwable) {
                    Result.failure<AudioClip>(t)
                } finally {
                    file.delete()
                }
            }
        }
    }

    /** Last resort: the Android engine plays the text itself (no WSOLA/visualisation). */
    private suspend fun speakDirect(text: String, locale: Locale, rate: Float, label: String): Boolean {
        val tts = ensureSystemTts() ?: return false
        return systemMutex.withLock {
            val target = if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) locale else Locale.US
            if (tts.setLanguage(target) < TextToSpeech.LANG_AVAILABLE) return@withLock false
            tts.setSpeechRate(rate.coerceIn(0.5f, 1.5f))
            val id = "pl-direct-${System.nanoTime()}"
            val done = CompletableDeferred<Boolean>()
            pendingUtterances[id] = done
            player.setExternal(true, label)
            try {
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS) return@withLock false
                withTimeoutOrNull(15_000L + text.length * 120L) { done.await() } ?: false
            } finally {
                pendingUtterances.remove(id)
                tts.setSpeechRate(1f)
                player.setExternal(false, label)
            }
        }
    }

    companion object {
        private const val TAG = "PolyLinguaTts"

        fun splitForTts(text: String): List<String> {
            val parts = text.split(Regex("(?<=[.!?;])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            return parts.ifEmpty { listOf(text) }
        }

        fun concat(parts: List<FloatArray>, gapSamples: Int): FloatArray {
            val total = parts.sumOf { it.size } + gapSamples * (parts.size - 1).coerceAtLeast(0)
            val out = FloatArray(total)
            var pos = 0
            for ((i, p) in parts.withIndex()) {
                System.arraycopy(p, 0, out, pos, p.size)
                pos += p.size
                if (i < parts.size - 1) pos += gapSamples
            }
            return out
        }
    }
}

/**
 * Streaming AudioTrack player for synthesized and recorded clips with a position flow (for karaoke
 * highlighting and A-B loops). One clip at a time; [play] suspends until done or [stop].
 */
class PcmPlayer {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    private val playLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var track: AudioTrack? = null

    /**
     * Plays [clip] (optionally the [startSec]..[endSec] window). A newer call stops the current
     * clip first; each call carries a generation number so a superseded clip never writes audio
     * or state after it has been replaced.
     */
    suspend fun play(clip: AudioClip, label: String = "", startSec: Double = 0.0, endSec: Double = Double.MAX_VALUE) {
        val gen = generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } }
        playLock.withLock {
            if (gen != generation.get()) return
            withContext(Dispatchers.IO) {
                val pcm = PcmUtils.floatsToShorts(clip.samples)
                val from = (startSec * clip.sampleRate).toInt().coerceIn(0, pcm.size)
                val to = (min(endSec, clip.durationSec) * clip.sampleRate).toInt().coerceIn(from, pcm.size)
                val minBuf = AudioTrack.getMinBufferSize(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(clip.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(clip.sampleRate / 5 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                val duration = (to - from).toDouble() / clip.sampleRate
                fun current() = gen == generation.get() && isActive
                _state.value = PlaybackState(true, 0.0, duration, label)
                try {
                    t.play()
                    var pos = from
                    val chunk = clip.sampleRate / 20
                    while (pos < to && current()) {
                        val n = min(chunk, to - pos)
                        val w = t.write(pcm, pos, n)
                        if (w <= 0) break
                        pos += w
                        if (current()) _state.value = PlaybackState(true, (t.playbackHeadPosition.toDouble() / clip.sampleRate).coerceAtMost(duration), duration, label)
                    }
                    // Drain the device buffer while reporting position.
                    while (current()) {
                        val head = t.playbackHeadPosition
                        _state.value = PlaybackState(true, (head.toDouble() / clip.sampleRate).coerceAtMost(duration), duration, label)
                        if (head >= to - from) break
                        kotlinx.coroutines.delay(20)
                    }
                } finally {
                    runCatching { t.stop() }
                    t.release()
                    if (track === t) track = null
                    if (gen == generation.get()) _state.value = PlaybackState(false, 0.0, duration, label)
                }
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } }
        if (_state.value.playing) _state.value = _state.value.copy(playing = false)
    }

    /** Mirrors playback done outside this player (Android TTS speaking directly). */
    internal fun setExternal(playing: Boolean, label: String) {
        if (playing) generation.incrementAndGet()
        _state.value = PlaybackState(playing, 0.0, 0.0, label)
    }

    /** Suspends until the current clip ends (used by sequential persona replies). */
    suspend fun awaitIdle() {
        _state.first { !it.playing }
    }
}
