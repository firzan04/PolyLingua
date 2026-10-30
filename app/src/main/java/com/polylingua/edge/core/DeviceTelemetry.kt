package com.polylingua.edge.core

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class TelemetrySnapshot(
    val appCpuPercent: Int = 0,
    val appRssMb: Int = 0,
    val deviceAvailMb: Int = 0,
    val deviceTotalMb: Int = 0,
    val lowMemory: Boolean = false,
    val nativeHeapMb: Int = 0,
)

/**
 * Lightweight 1 Hz sampler for the telemetry bar: app CPU share (process CPU time over wall time
 * across all cores - /proc/stat is not readable by apps since Android 8), resident set size from
 * /proc/self/status and device memory headroom from ActivityManager.
 */
@Singleton
class DeviceTelemetry @Inject constructor(@ApplicationContext private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot

    private val subscribers = AtomicInteger(0)
    private var job: Job? = null
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Starts sampling while at least one screen is visible; balanced by [release]. */
    @Synchronized
    fun acquire() {
        if (subscribers.incrementAndGet() == 1) {
            job = scope.launch {
                var lastCpu = Process.getElapsedCpuTime()
                var lastWall = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(1000)
                    val cpu = Process.getElapsedCpuTime()
                    val wall = SystemClock.elapsedRealtime()
                    val dCpu = (cpu - lastCpu).coerceAtLeast(0)
                    val dWall = (wall - lastWall).coerceAtLeast(1)
                    lastCpu = cpu
                    lastWall = wall
                    val pct = (100.0 * dCpu / (dWall * cores)).toInt().coerceIn(0, 100)
                    _snapshot.value = sample(pct)
                }
            }
        }
    }

    @Synchronized
    fun release() {
        if (subscribers.decrementAndGet() <= 0) {
            subscribers.set(0)
            job?.cancel()
            job = null
        }
    }

    private fun sample(cpuPercent: Int): TelemetrySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return TelemetrySnapshot(
            appCpuPercent = cpuPercent,
            appRssMb = readRssKb() / 1024,
            deviceAvailMb = (mi.availMem / (1024 * 1024)).toInt(),
            deviceTotalMb = (mi.totalMem / (1024 * 1024)).toInt(),
            lowMemory = mi.lowMemory,
            nativeHeapMb = (android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt(),
        )
    }

    private fun readRssKb(): Int = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toIntOrNull() ?: 0
        }
    }.getOrDefault(0)

    /** Resident memory in MB right now (used by the model residency governor). */
    fun currentRssMb(): Int = readRssKb() / 1024
}
