package com.polylingua.edge.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostics log: a bounded ring buffer of the events that matter when something does
 * not work on a particular phone (model installs, voice failures, recogniser choice, cloud AI
 * errors). Mirrored to logcat and exportable from Models & settings -> Diagnostics, so a learner
 * can send the exact failure instead of describing it.
 */
object DiagLog {
    private const val MAX_LINES = 800
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val _count = MutableStateFlow(0)
    /** Number of buffered lines (observable so the settings card can refresh). */
    val count: StateFlow<Int> = _count

    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            else -> Log.i(tag, msg)
        }
        val detail = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        val line = "${synchronized(fmt) { fmt.format(Date()) }} $level/$tag: $msg$detail"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            _count.value = lines.size
        }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) {
        lines.clear()
        _count.value = 0
    }

    /** Writes a shareable report (device header + buffered lines) to cache/diagnostics/. */
    fun export(context: Context, extraHeader: String = ""): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val f = File(dir, "PolyLingua_diagnostics.txt")
        val header = buildString {
            appendLine("PolyLingua Edge diagnostics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            if (extraHeader.isNotBlank()) appendLine(extraHeader)
            appendLine("----")
        }
        f.writeText(header + snapshot())
        return f
    }
}

/** Short user-facing notices (rendered as a snackbar by the root layout). */
object UserNotices {
    enum class Kind { INFO, SUCCESS, WARNING, ERROR }
    data class Notice(val text: String, val kind: Kind = Kind.INFO)

    private val _flow = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    val flow: SharedFlow<Notice> = _flow

    private val recent = HashMap<String, Long>()

    /** Posts [text]; identical notices within [dedupeMs] are dropped to avoid snackbar storms. */
    fun post(text: String, kind: Kind = Kind.INFO, dedupeMs: Long = 8000) {
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val last = recent[text]
            if (last != null && now - last < dedupeMs) return
            recent[text] = now
            if (recent.size > 64) recent.entries.removeAll { now - it.value > 60_000 }
        }
        _flow.tryEmit(Notice(text, kind))
    }
}
