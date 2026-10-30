package com.polylingua.edge.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.util.LruCache
import com.polylingua.edge.core.AccentPreference
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
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

/** A selectable voice: neural (sherpa-onnx pack) or an Android system voice by locale. */
data class SynthVoice(
    val id: String,
    val label: String,
    val accent: String,
    val female: Boolean,
    val neural: NeuralVoice? = null,
    val systemLocale: Locale? = null,
) {
    val isNeural: Boolean get() = neural != null
}

data class PlaybackState(val playing: Boolean = false, val positionSec: Double = 0.0, val durationSec: Double = 0.0, val label: String = "")

/**
 * Offline text-to-speech. Neural voices come from installed sherpa-onnx packs (Kokoro, Kitten,
 * Piper); the Android system engine covers accents those packs lack (en-AU for "Chloe from
 * Melbourne", id-ID for Budi) and acts as the fallback before any pack is downloaded.
 * Slow-down for shadowing uses WSOLA time-stretching, which preserves pitch and formants.
 */
@Singleton
class SpeechSynthesisEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    private val settings: AppSettings,
    private val residency: ModelResidency,
) : ResidentModel {

    private val lock = Any()
    private var handle = 0L
    private var loadedPack: String? = null

    override val residencyKey = "tts"
    override val residentMb: Int get() = loadedPack?.let { ModelCatalog.byId(it)?.residentMb } ?: 0

    private var systemTts: TextToSpeech? = null
    private var systemReady: CompletableDeferred<Boolean>? = null
    private val cache = LruCache<String, AudioClip>(24)

    val player = PcmPlayer()

    // ------------------------------------------------------------------------- voices

    fun installedNeuralVoices(): List<NeuralVoice> =
        ModelCatalog.NEURAL_VOICES.filter { v -> ModelCatalog.byId(v.packId)?.let { models.isInstalled(it) } == true }

    /** Neural voices first, then system locales (always listed; availability checked at use). */
    fun availableVoices(): List<SynthVoice> {
        val neural = installedNeuralVoices().map { SynthVoice(it.id, it.name, it.accent, it.female, neural = it) }
        val system = listOf(
            SynthVoice("system:en-GB", "Android voice (British)", "en-GB", true, systemLocale = Locale.UK),
            SynthVoice("system:en-US", "Android voice (American)", "en-US", true, systemLocale = Locale.US),
            SynthVoice("system:en-AU", "Android voice (Australian)", "en-AU", true, systemLocale = Locale("en", "AU")),
            SynthVoice("system:en-IE", "Android voice (Irish)", "en-IE", false, systemLocale = Locale("en", "IE")),
            SynthVoice("system:en-NZ", "Android voice (New Zealand)", "en-NZ", true, systemLocale = Locale("en", "NZ")),
            SynthVoice("system:en-IN", "Android voice (Indian)", "en-IN", true, systemLocale = Locale("en", "IN")),
            SynthVoice("system:id-ID", "Android voice (Bahasa Indonesia)", "id-ID", false, systemLocale = Locale("id", "ID")),
        )
        return neural + system
    }

    fun voiceById(id: String): SynthVoice? = availableVoices().firstOrNull { it.id == id }

    /** Resolves the first available voice from a preference list of ids. */
    fun resolve(preferences: List<String>): SynthVoice {
        val all = availableVoices()
        for (p in preferences) all.firstOrNull { it.id == p }?.let { return it }
        return defaultVoice()
    }

    fun defaultVoice(): SynthVoice {
        val s = settings.settings.value
        voiceById(s.ttsVoiceId)?.let { return it }
        val all = availableVoices()
        val wanted = s.accent.localeTag
        return all.firstOrNull { it.isNeural && it.accent == wanted }
            ?: all.firstOrNull { it.isNeural }
            ?: all.first { it.accent == wanted || (s.accent == AccentPreference.BRITISH_RP && it.accent == "en-GB") }
    }

    // ---------------------------------------------------------------------- synthesis

    /**
     * Synthesises [text]. [tempo] (0.5-1.5) is applied with WSOLA after synthesis so slowed
     * models keep natural pitch. Returns null if no engine could produce audio.
     */
    suspend fun synthesize(text: String, voice: SynthVoice = defaultVoice(), tempo: Double = 1.0): AudioClip? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val key = "${voice.id}|$clean"
        val base = cache.get(key) ?: run {
            val clip = if (voice.neural != null && NativeLoader.ensureLoaded()) synthNeural(clean, voice.neural) else null
            (clip ?: synthSystem(clean, voice.systemLocale ?: localeFor(voice.accent)))?.also { cache.put(key, it) }
        } ?: return null
        val t = TarsosWsolaTimeStretcher.quantize(tempo)
        if (t == 1.0) return base
        return withContext(CoreExecutors.dsp) { AudioClip(TarsosWsolaTimeStretcher.stretch(base.samples, base.sampleRate, t), base.sampleRate) }
    }

    /** Synthesise and play; suspends until playback finishes or is stopped. */
    suspend fun speak(text: String, voice: SynthVoice = defaultVoice(), tempo: Double = 1.0, label: String = ""): Boolean {
        val clip = synthesize(text, voice, tempo) ?: return false
        player.play(clip, label.ifBlank { text.take(60) })
        return true
    }

    fun stop() = player.stop()

    private suspend fun synthNeural(text: String, voice: NeuralVoice): AudioClip? = withContext(CoreExecutors.inference) {
        val pack = ModelCatalog.byId(voice.packId) ?: return@withContext null
        val dir = models.locate(pack) ?: return@withContext null
        synchronized(lock) {
            if (loadedPack != pack.id || handle == 0L) {
                if (handle != 0L) SherpaNative.nativeDestroyTts(handle)
                handle = 0L
                loadedPack = null
                residency.reserve(residencyKey, pack.residentMb)
                val threads = CoreExecutors.inferenceThreads
                val data = "$dir/espeak-ng-data"
                handle = when (pack.id) {
                    ModelCatalog.TTS_KOKORO.id -> SherpaNative.nativeCreateTts("kokoro", "$dir/model.int8.onnx", "$dir/voices.bin", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                    ModelCatalog.TTS_KITTEN.id -> SherpaNative.nativeCreateTts("kitten", "$dir/model.fp16.onnx", "$dir/voices.bin", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                    ModelCatalog.TTS_PIPER_ALAN.id -> SherpaNative.nativeCreateTts("vits", "$dir/en_GB-alan-medium.onnx", "", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                    ModelCatalog.TTS_PIPER_LESSAC.id -> SherpaNative.nativeCreateTts("vits", "$dir/en_US-lessac-medium.onnx", "", "$dir/tokens.txt", data, "", "", 1.0f, threads)
                    else -> 0L
                }
                if (handle == 0L) return@withContext null
                loadedPack = pack.id
                residency.register(this@SpeechSynthesisEngine)
            }
            residency.touch(residencyKey)
            val sr = SherpaNative.nativeTtsSampleRate(handle)
            val out = ArrayList<FloatArray>()
            // Synthesize sentence by sentence: bounded latency and memory on long model answers.
            for (sentence in splitForTts(text)) {
                val pcm = SherpaNative.nativeTtsGenerate(handle, sentence, voice.speakerId, 1.0f)
                if (pcm.isNotEmpty()) out += pcm
            }
            if (out.isEmpty()) null else AudioClip(concat(out, sr / 8), sr)
        }
    }

    override fun releaseForMemory() {
        synchronized(lock) {
            if (handle != 0L) SherpaNative.nativeDestroyTts(handle)
            handle = 0L
            loadedPack = null
        }
        residency.unregister(residencyKey)
    }

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
        val ok = withTimeoutOrNull(5000) { ready.await() } ?: false
        if (!ok) {
            synchronized(lock) { systemReady = null }
            systemTts?.shutdown()
            systemTts = null
            return null
        }
        systemOk = true
        return systemTts
    }

    private suspend fun synthSystem(text: String, locale: Locale): AudioClip? {
        val tts = ensureSystemTts() ?: return null
        return withContext(Dispatchers.IO) {
            val avail = tts.isLanguageAvailable(locale)
            if (avail < TextToSpeech.LANG_AVAILABLE) {
                Log.w(TAG, "System TTS lacks $locale (code $avail)")
                if (tts.isLanguageAvailable(Locale.US) < TextToSpeech.LANG_AVAILABLE) return@withContext null
            }
            val target = if (avail >= TextToSpeech.LANG_AVAILABLE) locale else Locale.US
            val voice: Voice? = runCatching {
                tts.voices?.filter { it.locale.language == target.language && it.locale.country == target.country }
                    ?.sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }))
                    ?.firstOrNull()
            }.getOrNull()
            if (voice != null) tts.setVoice(voice) else tts.setLanguage(target)
            val file = File.createTempFile("tts_", ".wav", context.cacheDir)
            try {
                val id = "pl-${System.nanoTime()}"
                val done = CompletableDeferred<Boolean>()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(false) }
                })
                if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) return@withContext null
                val ok = withTimeoutOrNull(30_000) { done.await() } ?: false
                if (!ok || file.length() < 100) return@withContext null
                val wav = file.inputStream().use { PcmUtils.readWav(it) }
                AudioClip(wav.samples, wav.sampleRate)
            } catch (t: Throwable) {
                Log.w(TAG, "System TTS failed", t)
                null
            } finally {
                file.delete()
            }
        }
    }

    private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

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

    /** Suspends until the current clip ends (used by sequential persona replies). */
    suspend fun awaitIdle() {
        _state.first { !it.playing }
    }
}
