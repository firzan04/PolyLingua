package com.polylingua.edge.ai

import com.polylingua.edge.speech.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A cloud provider failure with a learner-friendly message. */
class CloudAiException(message: String, val httpStatus: Int = 0) : IOException(message)

/** Streaming chat client for one cloud provider. */
interface CloudChatClient {
    val label: String

    /** Streams text deltas. [json] asks for a JSON-only answer where the API supports it. */
    fun stream(turns: List<ChatTurn>, maxTokens: Int, temperature: Float, json: Boolean = false, effort: String = "low"): Flow<String>
}

/**
 * Pure request builders and response parsers for the three wire formats, kept free of Android
 * and network types so they are covered by JVM unit tests.
 */
object CloudAiCodec {
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
    const val CLAUDE_VERSION = "2023-06-01"

    /** Splits system text and returns user/assistant turns that alternate and start with a user turn. */
    fun normalize(turns: List<ChatTurn>): Pair<String, List<ChatTurn>> {
        val system = turns.filter { it.role == "system" }.joinToString("\n\n") { it.content.trim() }
        val merged = ArrayList<ChatTurn>()
        for (t in turns) {
            if (t.role == "system" || t.content.isBlank()) continue
            val role = if (t.role == "assistant") "assistant" else "user"
            val last = merged.lastOrNull()
            if (last != null && last.role == role) merged[merged.lastIndex] = ChatTurn(role, last.content + "\n" + t.content)
            else merged += ChatTurn(role, t.content)
        }
        if (merged.firstOrNull()?.role == "assistant") merged.add(0, ChatTurn.user("(The conversation starts.)"))
        if (merged.isEmpty()) merged += ChatTurn.user("Hello")
        return system to merged
    }

    // ------------------------------------------------------------------------- Gemini

    fun geminiRequest(turns: List<ChatTurn>, maxTokens: Int, temperature: Float, json: Boolean): JSONObject {
        val (system, convo) = normalize(turns)
        val o = JSONObject()
        if (system.isNotBlank()) o.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val contents = JSONArray()
        for (t in convo) {
            contents.put(
                JSONObject()
                    .put("role", if (t.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", t.content))),
            )
        }
        o.put("contents", contents)
        val gen = JSONObject()
            .put("temperature", temperature.toDouble())
            // Thinking tokens count against the output budget on recent Gemini models, so keep
            // headroom and constrain length through the prompt instead.
            .put("maxOutputTokens", maxOf(1024, maxTokens * 4))
        if (json) gen.put("responseMimeType", "application/json")
        o.put("generationConfig", gen)
        return o
    }

    fun geminiAudioRequest(wavBase64: String, instruction: String): JSONObject = JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", wavBase64)))
                        .put(JSONObject().put("text", instruction)),
                ),
            ),
        )
        .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 8192))

    /** Text pieces in one generateContent response / SSE chunk (thought parts are skipped). */
    fun geminiText(json: String): String {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return ""
        o.optJSONObject("error")?.let { throw geminiError(json, it.optInt("code")) }
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    /** Reason a Gemini answer was blocked / empty, if any. */
    fun geminiBlockReason(json: String): String? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let { return "blocked: $it" }
        val reason = o.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
        return reason.takeIf { it == "SAFETY" || it == "RECITATION" || it == "PROHIBITED_CONTENT" }?.let { "stopped: $it" }
    }

    fun geminiError(body: String, status: Int): CloudAiException {
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val msg = err?.optString("message").orEmpty().ifBlank { body.take(200) }
        val code = if (status > 0) status else err?.optInt("code") ?: 0
        return CloudAiException(friendly("Gemini", code, msg), code)
    }

    // ------------------------------------------------------------------------- Claude

    /** Claude 5-family models take `output_config.effort`; older models reject it. */
    fun claudeSupportsEffort(model: String): Boolean =
        Regex("""claude-(haiku|sonnet|opus)-5|claude-fable|claude-mythos|claude-opus-4-[5-9]|claude-sonnet-4-6""").containsMatchIn(model)

    fun claudeRequest(model: String, turns: List<ChatTurn>, maxTokens: Int, stream: Boolean, effort: String?): JSONObject {
        val (system, convo) = normalize(turns)
        val o = JSONObject()
            .put("model", model)
            .put("max_tokens", maxOf(1024, maxTokens * 4))
            .put("stream", stream)
        if (system.isNotBlank()) o.put("system", system)
        val msgs = JSONArray()
        for (t in convo) msgs.put(JSONObject().put("role", t.role).put("content", t.content))
        o.put("messages", msgs)
        // Sampling parameters are omitted on purpose: current Claude models expect the defaults.
        if (effort != null && claudeSupportsEffort(model)) o.put("output_config", JSONObject().put("effort", effort))
        return o
    }

    sealed interface ClaudeEvent {
        data class Text(val text: String) : ClaudeEvent
        data class Error(val message: String) : ClaudeEvent
        data object Stop : ClaudeEvent
        data object Ignore : ClaudeEvent
    }

    fun claudeEvent(data: String): ClaudeEvent {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return ClaudeEvent.Ignore
        return when (o.optString("type")) {
            "content_block_delta" -> {
                val d = o.optJSONObject("delta")
                if (d?.optString("type") == "text_delta") ClaudeEvent.Text(d.optString("text")) else ClaudeEvent.Ignore
            }
            "message_stop" -> ClaudeEvent.Stop
            "error" -> ClaudeEvent.Error(o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Claude stream error" })
            else -> ClaudeEvent.Ignore
        }
    }

    /** Text of a non-streaming Messages response (thinking blocks skipped). */
    fun claudeText(json: String): String {
        val content = runCatching { JSONObject(json).optJSONArray("content") }.getOrNull() ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString()
    }

    fun claudeError(body: String, status: Int): CloudAiException {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().ifBlank { body.take(200) }
        return CloudAiException(friendly("Claude", status, msg), status)
    }

    // ---------------------------------------------------------------- OpenAI-compatible

    fun openAiRequest(model: String, turns: List<ChatTurn>, maxTokens: Int, temperature: Float, stream: Boolean, json: Boolean): JSONObject {
        val (system, convo) = normalize(turns)
        val msgs = JSONArray()
        if (system.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", system))
        for (t in convo) msgs.put(JSONObject().put("role", t.role).put("content", t.content))
        val o = JSONObject().put("model", model).put("messages", msgs).put("stream", stream)
            .put("max_tokens", maxOf(512, maxTokens * 3)).put("temperature", temperature.toDouble())
        if (json) o.put("response_format", JSONObject().put("type", "json_object"))
        return o
    }

    /** Delta text of one SSE chunk; null for the terminal "[DONE]". */
    fun openAiDelta(data: String): String? {
        if (data.trim() == "[DONE]") return null
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return ""
        o.optJSONObject("error")?.let { throw CloudAiException(it.optString("message").ifBlank { "Endpoint error" }) }
        val c = o.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        return c.optJSONObject("delta")?.optString("content").orEmpty().ifEmpty { c.optJSONObject("message")?.optString("content").orEmpty() }
    }

    fun openAiError(body: String, status: Int): CloudAiException {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().ifBlank { body.take(200) }
        return CloudAiException(friendly("Endpoint", status, msg), status)
    }

    fun friendly(provider: String, status: Int, raw: String): String = when (status) {
        400 -> "$provider rejected the request: $raw"
        401, 403 -> "$provider API key is invalid or lacks access ($status). Check the key in Models & settings → AI coach."
        404 -> "$provider model not found ($raw). Pick another model name."
        429 -> "$provider rate limit / quota reached. Wait a minute, or check your plan's limits."
        in 500..599 -> "$provider is temporarily unavailable ($status). Try again shortly."
        else -> "$provider error${if (status > 0) " $status" else ""}: $raw"
    }

    /** Extracts the JSON object embedded in a model answer (tolerates ```json fences). */
    fun extractJsonObject(text: String): JSONObject? {
        val a = text.indexOf('{')
        val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(text.substring(a, b + 1)) }.getOrNull()
    }
}

// ----------------------------------------------------------------------------- transport

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** Awaits an OkHttp call without blocking a thread; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Reads Server-Sent Events: calls [onEvent] (event name, data) per event; return false to stop. */
internal fun readSse(source: BufferedSource, onEvent: (String?, String) -> Boolean) {
    var event: String? = null
    val data = StringBuilder()
    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    if (!onEvent(event, data.toString())) return
                }
                event = null
                data.setLength(0)
            }
            line.startsWith(":") -> {}
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
            }
        }
    }
    if (data.isNotEmpty()) onEvent(event, data.toString())
}

/** Runs a streaming POST and feeds SSE events to [handle]; cancellation aborts the HTTP call. */
private fun sseFlow(
    http: OkHttpClient,
    request: Request,
    onHttpError: (String, Int) -> CloudAiException,
    handle: (event: String?, data: String, emit: (String) -> Unit) -> Boolean,
): Flow<String> = callbackFlow {
    val call = http.newCall(request)
    val job = launch(Dispatchers.IO) {
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw onHttpError(resp.body?.string().orEmpty(), resp.code)
                val source = resp.body?.source() ?: throw CloudAiException("Empty response")
                readSse(source) { ev, data -> handle(ev, data) { piece -> if (piece.isNotEmpty()) trySend(piece) } }
            }
            close()
        } catch (t: Throwable) {
            close(if (t is IOException && call.isCanceled()) null else t)
        }
    }
    awaitClose {
        call.cancel()
        job.cancel()
    }
}

class GeminiClient(private val http: OkHttpClient, private val apiKey: String, private val model: String) : CloudChatClient {
    override val label: String = "Gemini · $model"

    override fun stream(turns: List<ChatTurn>, maxTokens: Int, temperature: Float, json: Boolean, effort: String): Flow<String> {
        val body = CloudAiCodec.geminiRequest(turns, maxTokens, temperature, json).toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url("${CloudAiCodec.GEMINI_BASE}/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()
        return sseFlow(http, req, CloudAiCodec::geminiError) { _, data, emit ->
            emit(CloudAiCodec.geminiText(data))
            CloudAiCodec.geminiBlockReason(data)?.let { throw CloudAiException("Gemini $it") }
            true
        }
    }

    /** Verbatim transcription of a WAV file (inline audio, ≤ ~7 min per request). */
    suspend fun transcribe(wav: ByteArray, instruction: String): String {
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val body = CloudAiCodec.geminiAudioRequest(b64, instruction).toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url("${CloudAiCodec.GEMINI_BASE}/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()
        http.newCall(req).await().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw CloudAiCodec.geminiError(text, resp.code)
            return CloudAiCodec.geminiText(text).trim()
        }
    }
}

class ClaudeClient(private val http: OkHttpClient, private val apiKey: String, private val model: String) : CloudChatClient {
    override val label: String = "Claude · $model"

    override fun stream(turns: List<ChatTurn>, maxTokens: Int, temperature: Float, json: Boolean, effort: String): Flow<String> {
        val t = if (json) turns + ChatTurn.user("Answer with one JSON object only, no prose.") else turns
        val body = CloudAiCodec.claudeRequest(model, t, maxTokens, stream = true, effort = effort).toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url(CloudAiCodec.CLAUDE_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", CloudAiCodec.CLAUDE_VERSION)
            .post(body)
            .build()
        return sseFlow(http, req, CloudAiCodec::claudeError) { _, data, emit ->
            when (val e = CloudAiCodec.claudeEvent(data)) {
                is CloudAiCodec.ClaudeEvent.Text -> { emit(e.text); true }
                is CloudAiCodec.ClaudeEvent.Error -> throw CloudAiException("Claude: ${e.message}")
                CloudAiCodec.ClaudeEvent.Stop -> false
                CloudAiCodec.ClaudeEvent.Ignore -> true
            }
        }
    }
}

class OpenAiCompatClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) : CloudChatClient {
    override val label: String = "$model @ ${baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')}"

    override fun stream(turns: List<ChatTurn>, maxTokens: Int, temperature: Float, json: Boolean, effort: String): Flow<String> {
        val body = CloudAiCodec.openAiRequest(model, turns, maxTokens, temperature, stream = true, json = json).toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body)
            .build()
        return sseFlow(http, req, CloudAiCodec::openAiError) { _, data, emit ->
            val d = CloudAiCodec.openAiDelta(data)
            if (d == null) false else { emit(d); true }
        }
    }
}
