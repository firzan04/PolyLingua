package com.polylingua.edge.speech

import android.util.Log
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.nativebridge.LlamaNative
import com.polylingua.edge.nativebridge.NativeLoader
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ChatTurn(val role: String, val content: String) {
    companion object {
        fun system(c: String) = ChatTurn("system", c)
        fun user(c: String) = ChatTurn("user", c)
        fun assistant(c: String) = ChatTurn("assistant", c)
    }
}

sealed interface LlmState {
    data object NotInstalled : LlmState
    data object Unloaded : LlmState
    data class Loading(val pack: String) : LlmState
    data class Ready(val pack: String, val info: String) : LlmState
    data class Error(val message: String) : LlmState
}

/** Structured sentence correction returned by the coach (GBNF-constrained JSON). */
data class LlmCorrection(val corrected: String, val explanation: String, val nativeAlternative: String)

/**
 * Offline small language model (llama.cpp) for persona conversation and grammar coaching.
 * Model weights are memory-mapped; generation runs on the big-core inference thread with
 * n_threads = 2 (Cortex-A76 x2), n_ctx = 2048, n_batch = 256.
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    private val models: ModelManager,
    private val settings: AppSettings,
    private val residency: ModelResidency,
) : ResidentModel {

    private val lock = Any()
    @Volatile private var handle = 0L
    @Volatile private var loadedPack: ModelPack? = null
    private var backendInitialised = false

    private val _state = MutableStateFlow<LlmState>(LlmState.Unloaded)
    val state: StateFlow<LlmState> = _state

    override val residencyKey = "llm"
    override val residentMb: Int get() = loadedPack?.residentMb ?: 0

    fun installedPacks(): List<ModelPack> = listOf(ModelCatalog.LLM_QWEN, ModelCatalog.LLM_SMOL).filter { models.isInstalled(it) }

    fun preferredPack(): ModelPack? {
        val installed = installedPacks()
        val wanted = settings.settings.value.llmPackId
        return installed.firstOrNull { it.id == wanted } ?: installed.firstOrNull()
    }

    val isAvailable: Boolean get() = NativeLoader.ensureLoaded() && preferredPack() != null && settings.settings.value.useLlmCoach

    suspend fun ensureLoaded(): Boolean = withContext(CoreExecutors.inference) {
        val pack = preferredPack()
        if (pack == null || !NativeLoader.ensureLoaded()) {
            _state.value = LlmState.NotInstalled
            return@withContext false
        }
        synchronized(lock) {
            if (handle != 0L && loadedPack?.id == pack.id) {
                residency.touch(residencyKey)
                return@withContext true
            }
            if (handle != 0L) {
                LlamaNative.nativeFree(handle)
                handle = 0L
            }
            if (!backendInitialised) {
                LlamaNative.nativeBackendInit()
                backendInitialised = true
            }
            _state.value = LlmState.Loading(pack.title)
            residency.reserve(residencyKey, pack.residentMb, pinned = setOf("asr"))
            val file = models.file(pack, pack.requiredFiles.first())
            val h = if (file != null) LlamaNative.nativeLoadModel(file.absolutePath, 2048, CoreExecutors.inferenceThreads, 256) else 0L
            if (h == 0L) {
                _state.value = LlmState.Error("Could not load ${pack.title}")
                return@withContext false
            }
            handle = h
            loadedPack = pack
            residency.register(this@LocalLlmEngine)
            _state.value = LlmState.Ready(pack.title, runCatching { LlamaNative.nativeModelInfo(h) }.getOrDefault(""))
            true
        }
    }

    override fun releaseForMemory() {
        synchronized(lock) {
            if (handle != 0L) LlamaNative.nativeFree(handle)
            handle = 0L
            loadedPack = null
        }
        residency.unregister(residencyKey)
        _state.value = LlmState.Unloaded
    }

    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.nativeCancel(h)
    }

    /** Streams generated text pieces. Cancelling the collector aborts native generation. Collect off the inference thread. */
    fun generate(
        turns: List<ChatTurn>,
        maxTokens: Int = 200,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        grammar: String? = null,
    ): Flow<String> = callbackFlow {
        val job = launch(CoreExecutors.inference) {
            if (!ensureLoaded()) {
                close(IllegalStateException("No offline language model installed"))
                return@launch
            }
            val h = handle
            val roles = turns.map { it.role }.toTypedArray()
            val contents = turns.map { it.content }.toTypedArray()
            try {
                LlamaNative.nativeGenerate(h, roles, contents, grammar, maxTokens, temperature, topP, 42,
                    LlamaNative.TokenCallback { bytes -> trySend(String(bytes, Charsets.UTF_8)) })
                close()
            } catch (t: Throwable) {
                Log.e("PolyLinguaLlm", "Generation failed", t)
                close(t)
            }
        }
        // awaitClose runs on the collector's dispatcher (not the blocked inference thread), so the
        // native cancel flag is raised immediately when the collector goes away.
        awaitClose {
            if (job.isActive) this@LocalLlmEngine.cancel()
        }
    }

    /** Blocking-style completion (collects [generate]). */
    suspend fun complete(turns: List<ChatTurn>, maxTokens: Int = 200, temperature: Float = 0.7f, grammar: String? = null): String {
        val sb = StringBuilder()
        generate(turns, maxTokens, temperature, 0.9f, grammar).collect { sb.append(it) }
        return sb.toString().trim()
    }

    /** Grammar/naturalness correction of one learner sentence, or null if no model is available. */
    suspend fun correct(sentence: String, level: String): LlmCorrection? {
        if (!isAvailable || sentence.isBlank()) return null
        val prompt = listOf(
            ChatTurn.system(
                "You are a precise English teacher for $level learners. Correct grammar, word choice and naturalness. " +
                    "Keep the speaker's meaning. If the sentence is already correct, copy it unchanged. Answer in JSON only.",
            ),
            ChatTurn.user("Sentence: \"$sentence\""),
        )
        val raw = runCatching { complete(prompt, maxTokens = 160, temperature = 0.2f, grammar = CORRECTION_GRAMMAR) }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            LlmCorrection(o.getString("corrected").trim(), o.optString("explanation").trim(), o.optString("native_alternative").trim())
        }.getOrNull()
    }

    companion object {
        /** GBNF: exactly {"corrected": "...", "explanation": "...", "native_alternative": "..."} */
        val CORRECTION_GRAMMAR = """
root ::= "{" ws "\"corrected\"" ws ":" ws str ws "," ws "\"explanation\"" ws ":" ws str ws "," ws "\"native_alternative\"" ws ":" ws str ws "}"
str ::= "\"" chr* "\""
chr ::= [^"\\\x00-\x1f] | "\\" ["\\/bfnrt]
ws ::= [ \t\n]*
""".trimIndent()
    }
}
