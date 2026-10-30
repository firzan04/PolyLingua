package com.polylingua.edge.core

import android.os.Process
import android.util.Log
import com.polylingua.edge.nativebridge.CpuAffinityNative
import com.polylingua.edge.nativebridge.NativeLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Heterogeneous-core scheduling for the MediaTek Helio G96 (2x Cortex-A76 @ 2.05 GHz + 6x
 * Cortex-A55 @ 2.0 GHz).
 *
 *  - [inference]: heavy neural work (llama.cpp, ONNX Runtime GOP, TTS, second-pass ASR). One
 *    thread pinned to the A76 pair with sched_setaffinity; native libraries spawn their own worker
 *    threads from it, and Linux copies the affinity mask to child threads, so ggml / ORT
 *    intra-op pools stay on the big cores with n_threads = 2.
 *  - [realtime]: streaming ASR + Silero VAD (light, latency-critical). A second big-core thread so
 *    live captions keep flowing while the LLM generates.
 *  - [dsp]: pitch tracking, WSOLA, file and XLSX I/O. Two threads pinned to the A55 cluster.
 *
 * When the native library is unavailable (e.g. stripped ABI) the threads simply run unpinned.
 */
object CoreExecutors {
    private const val TAG = "PolyLinguaCores"

    private class PinnedFactory(
        private val name: String,
        private val big: Boolean,
        private val priority: Int,
    ) : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread = Thread({
            Process.setThreadPriority(priority)
            if (NativeLoader.ensureLoaded()) {
                val rc = runCatching {
                    if (big) CpuAffinityNative.nativePinToBigCores(2) else CpuAffinityNative.nativePinToLittleCores()
                }.getOrDefault(-1)
                if (rc != 0) Log.w(TAG, "Affinity pin for $name returned $rc")
            }
            r.run()
        }, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    val inferenceExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(PinnedFactory("pl-a76-infer", true, Process.THREAD_PRIORITY_DEFAULT))
    val realtimeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(PinnedFactory("pl-a76-rt", true, Process.THREAD_PRIORITY_URGENT_AUDIO))
    val dspExecutor: ExecutorService =
        Executors.newFixedThreadPool(2, PinnedFactory("pl-a55-dsp", false, Process.THREAD_PRIORITY_DEFAULT))

    val inference: CoroutineDispatcher = inferenceExecutor.asCoroutineDispatcher()
    val realtime: CoroutineDispatcher = realtimeExecutor.asCoroutineDispatcher()
    val dsp: CoroutineDispatcher = dspExecutor.asCoroutineDispatcher()

    /** Threads for native inference engines: the big-core count (2 on the Helio G96). */
    val inferenceThreads: Int by lazy {
        if (NativeLoader.ensureLoaded()) {
            runCatching { CpuAffinityNative.nativeRecommendedThreads() }.getOrDefault(2).coerceIn(1, 4)
        } else {
            (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 2)
        }
    }

    data class Topology(
        val method: String,
        val hardware: String,
        val bigCores: List<Int>,
        val littleCores: List<Int>,
        val summary: String,
    )

    /** Parsed CPU topology for the diagnostics panel. */
    val topology: Topology by lazy {
        val fallback = Topology("unavailable", android.os.Build.HARDWARE ?: "", emptyList(), emptyList(),
            "${Runtime.getRuntime().availableProcessors()} cores (native library not loaded)")
        if (!NativeLoader.ensureLoaded()) return@lazy fallback
        runCatching {
            val o = JSONObject(CpuAffinityNative.nativeDescribeTopology())
            fun ints(key: String): List<Int> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getInt(it) }
            }
            val big = ints("big")
            val little = ints("little")
            Topology(
                method = o.optString("method"),
                hardware = o.optString("hardware"),
                bigCores = big,
                littleCores = little,
                summary = "Big ${big.joinToString(prefix = "cpu", separator = ",cpu")} · Little ${little.size} cores",
            )
        }.getOrDefault(fallback)
    }
}
