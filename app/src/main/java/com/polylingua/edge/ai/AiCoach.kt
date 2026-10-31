package com.polylingua.edge.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.polylingua.edge.core.AiProviderKind
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.DiagLog
import com.polylingua.edge.core.SecureKeyStore
import com.polylingua.edge.core.UserNotices
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.speech.ChatTurn
import com.polylingua.edge.speech.LlmCorrection
import com.polylingua.edge.speech.LlmState
import com.polylingua.edge.speech.LocalLlmEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Last observed result of a provider (shown next to the provider in settings). */
data class ProviderHealth(val ok: Boolean, val message: String, val latencyMs: Long, val at: Long = System.currentTimeMillis())

/**
 * Routes every AI-coach request (persona chat, sentence corrections, examiner review, cloud
 * transcription) to the provider chosen in settings: the on-device GGUF model, Google Gemini,
 * Anthropic Claude, or any OpenAI-compatible endpoint (OpenRouter, a LAN LM Studio / Ollama...).
 * Cloud failures fall back to the on-device model when allowed, so practice never stops.
 */
@Singleton
class AiCoach @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val keys: SecureKeyStore,
    private val local: LocalLlmEngine,
    http: OkHttpClient,
) {
    private val cloudHttp: OkHttpClient = http.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _health = MutableStateFlow<Map<AiProviderKind, ProviderHealth>>(emptyMap())
    val health: StateFlow<Map<AiProviderKind, ProviderHealth>> = _health

    val localState: StateFlow<LlmState> get() = local.state

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Client for [kind] (default: the selected provider) when it is configured, else null. */
    fun cloudClient(kind: AiProviderKind = settings.settings.value.aiProvider): CloudChatClient? {
        val s = settings.settings.value
        return when (kind) {
            AiProviderKind.GEMINI -> keys.get(SecureKeyStore.Slot.GEMINI)?.let { GeminiClient(cloudHttp, it, s.geminiModel) }
            AiProviderKind.CLAUDE -> keys.get(SecureKeyStore.Slot.CLAUDE)?.let { ClaudeClient(cloudHttp, it, s.claudeModel) }
            AiProviderKind.OPENAI_COMPAT -> if (s.openAiBaseUrl.isNotBlank() && s.openAiModel.isNotBlank()) {
                OpenAiCompatClient(cloudHttp, keys.get(SecureKeyStore.Slot.OPENAI).orEmpty(), s.openAiBaseUrl, s.openAiModel)
            } else null
            AiProviderKind.ON_DEVICE -> null
        }
    }

    private fun activeCloud(): CloudChatClient? =
        if (settings.settings.value.aiProvider.cloud && isOnline()) cloudClient() else null

    /** True when a cloud provider or the on-device model can answer now. */
    val isAvailable: Boolean get() = activeCloud() != null || local.isAvailable

    val isCloudActive: Boolean get() = activeCloud() != null

    /** Human-readable engine name for the UI ("Gemini · gemini-3.5-flash-lite", "Qwen2.5 1.5B ..."). */
    val activeLabel: String
        get() = activeCloud()?.label
            ?: local.preferredPack()?.takeIf { local.isAvailable }?.title
            ?: "Rule-based coach (no AI model configured)"

    private fun record(kind: AiProviderKind, ok: Boolean, message: String, startNs: Long) {
        val ms = (System.nanoTime() - startNs) / 1_000_000
        _health.value = _health.value + (kind to ProviderHealth(ok, message, ms))
    }

    /**
     * Streams a reply. Cloud first (when selected and online); on a cloud failure before any
     * text arrived, falls back to the on-device model if allowed. Throws when nothing can answer.
     */
    fun generate(turns: List<ChatTurn>, maxTokens: Int = 200, temperature: Float = 0.7f, json: Boolean = false, effort: String = "low"): Flow<String> = flow {
        val kind = settings.settings.value.aiProvider
        val client = activeCloud()
        if (client != null) {
            val t0 = System.nanoTime()
            var produced = false
            var failure: Throwable? = null
            emitAll(
                client.stream(turns, maxTokens, temperature, json, effort)
                    .onEach { produced = true }
                    .catch { failure = it },
            )
            val f = failure
            if (f == null) {
                record(kind, true, "OK", t0)
                return@flow
            }
            if (f is CancellationException) throw f
            record(kind, false, f.message ?: f.javaClass.simpleName, t0)
            DiagLog.w(TAG, "${client.label} failed", f)
            if (produced || !(settings.settings.value.aiOfflineFallback && local.isAvailable)) throw f
            UserNotices.post("${client.label}: ${f.message ?: "unavailable"} — using the on-device model.", UserNotices.Kind.WARNING)
        } else if (settings.settings.value.aiProvider.cloud && !isOnline()) {
            UserNotices.post("Offline: using the on-device AI coach.", UserNotices.Kind.INFO, dedupeMs = 120_000)
        }
        if (!local.isAvailable) throw IllegalStateException("No AI model available: add an API key or download an on-device model.")
        emitAll(local.generate(turns, maxTokens, temperature))
    }

    suspend fun complete(turns: List<ChatTurn>, maxTokens: Int = 300, temperature: Float = 0.4f, json: Boolean = false, effort: String = "low"): String {
        val sb = StringBuilder()
        generate(turns, maxTokens, temperature, json, effort).collect { sb.append(it) }
        return sb.toString().trim()
    }

    /** Grammar/naturalness correction of one learner sentence (cloud JSON or on-device GBNF). */
    suspend fun correct(sentence: String, level: String): LlmCorrection? {
        if (sentence.isBlank()) return null
        if (activeCloud() != null) {
            val prompt = listOf(
                ChatTurn.system(
                    "You are a precise English teacher for $level learners preparing for IELTS speaking. " +
                        "Correct grammar, word choice and naturalness of the learner's spoken sentence while keeping their meaning. " +
                        "If it is already correct and natural, copy it unchanged. Reply with JSON only: " +
                        "{\"corrected\": string, \"explanation\": string (one short sentence), \"native_alternative\": string}.",
                ),
                ChatTurn.user("Sentence: \"$sentence\""),
            )
            val parsed = runCatching {
                val raw = complete(prompt, maxTokens = 220, temperature = 0.2f, json = true)
                val o = CloudAiCodec.extractJsonObject(raw) ?: error("No JSON in reply")
                LlmCorrection(o.getString("corrected").trim(), o.optString("explanation").trim(), o.optString("native_alternative").trim())
            }
            parsed.onSuccess { return it }
            parsed.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
        return if (local.isAvailable) local.correct(sentence, level) else null
    }

    /**
     * Examiner-style written feedback on a whole answer (cloud only - small on-device models
     * are not reliable enough for band justification). Returns null when no cloud provider.
     */
    suspend fun examinerComments(prompt: String, transcript: String, bands: String, level: String): String? {
        if (activeCloud() == null || transcript.isBlank()) return null
        val turns = listOf(
            ChatTurn.system(
                "You are a certified IELTS Speaking examiner and coach for Indonesian learners. Be specific, kind and concise. " +
                    "Use the four public band descriptors (Fluency & Coherence, Lexical Resource, Grammatical Range & Accuracy, Pronunciation). " +
                    "The transcript comes from speech recognition, so ignore obvious recognition glitches.",
            ),
            ChatTurn.user(
                "Question: $prompt\nAutomatic scores: $bands (CEFR $level)\nTranscript: \"$transcript\"\n\n" +
                    "Write: 1) two strengths, 2) the three most important fixes, each quoting the learner's words and giving a better version, " +
                    "3) one upgraded sample sentence they could have said. Plain text, max 160 words.",
            ),
        )
        return runCatching { complete(turns, maxTokens = 400, temperature = 0.4f, effort = "medium") }
            .onFailure { if (it is CancellationException) throw it; DiagLog.w(TAG, "Examiner comments failed", it) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** True when Gemini can transcribe audio now (key present + online). */
    fun canTranscribeInCloud(): Boolean = keys.has(SecureKeyStore.Slot.GEMINI) && isOnline()

    /**
     * Verbatim transcript of 16 kHz PCM via Gemini audio understanding (fillers, repetitions and
     * false starts kept, as IELTS examiners hear them). Returns null on any failure.
     */
    suspend fun transcribe(pcm16k: ShortArray, contextHint: String = ""): String? {
        if (pcm16k.size < 8000) return null
        val key = keys.get(SecureKeyStore.Slot.GEMINI) ?: return null
        if (!isOnline()) return null
        val client = GeminiClient(cloudHttp, key, settings.settings.value.geminiModel)
        val t0 = System.nanoTime()
        return try {
            // Inline requests are limited to 20 MB; 16 kHz PCM16 base64 ≈ 43 KB/s -> cap at 6 min.
            val capped = if (pcm16k.size > 16000 * 360) pcm16k.copyOf(16000 * 360) else pcm16k
            val wav = ByteArrayOutputStream().use { out -> PcmUtils.writeWav(out, capped, 16000); out.toByteArray() }
            val instruction = "Transcribe this English speech by a language learner verbatim. Keep fillers (um, uh, er), repetitions, " +
                "false starts and grammar mistakes exactly as spoken - do not correct anything. Add normal punctuation. " +
                (if (contextHint.isNotBlank()) "The speaker is answering: \"$contextHint\". " else "") +
                "Output only the transcript text."
            val text = withTimeout(90_000) { client.transcribe(wav, instruction) }
            record(AiProviderKind.GEMINI, true, "Transcribed ${capped.size / 16000} s", t0)
            DiagLog.i(TAG, "Gemini transcript: ${text.length} chars for ${capped.size / 16000} s audio")
            text.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            record(AiProviderKind.GEMINI, false, t.message ?: "transcription failed", t0)
            DiagLog.w(TAG, "Gemini transcription failed", t)
            UserNotices.post("Gemini transcript unavailable (${t.message ?: "error"}); using the on-device transcript.", UserNotices.Kind.WARNING)
            null
        }
    }

    /** Sends a tiny prompt to [kind] and reports latency or the error (settings "Test" button). */
    suspend fun test(kind: AiProviderKind): ProviderHealth {
        val t0 = System.nanoTime()
        val result = runCatching {
            if (kind == AiProviderKind.ON_DEVICE) {
                if (!local.isAvailable) error("No on-device model installed (download Qwen2.5 or SmolLM2 below).")
                local.complete(listOf(ChatTurn.system("Reply with one word."), ChatTurn.user("Say OK.")), maxTokens = 8, temperature = 0f)
            } else {
                val client = cloudClient(kind) ?: error("Not configured: add the API key${if (kind == AiProviderKind.OPENAI_COMPAT) ", base URL and model" else ""}.")
                if (!isOnline()) error("No internet connection.")
                val sb = StringBuilder()
                withTimeout(45_000) {
                    client.stream(listOf(ChatTurn.user("Reply with the single word OK.")), maxTokens = 16, temperature = 0f).collect { sb.append(it) }
                }
                sb.toString().trim().ifBlank { error("Empty reply") }
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val h = result.fold(
            onSuccess = { ProviderHealth(true, "Replied \"${it.take(20)}\" in $ms ms", ms) },
            onFailure = { ProviderHealth(false, it.message ?: it.javaClass.simpleName, ms) },
        )
        _health.value = _health.value + (kind to h)
        DiagLog.i(TAG, "Test $kind -> ${h.ok} ${h.message}")
        return h
    }

    companion object {
        private const val TAG = "PolyLinguaAi"
    }
}
