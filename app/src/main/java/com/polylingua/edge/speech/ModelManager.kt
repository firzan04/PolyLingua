package com.polylingua.edge.speech

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.polylingua.edge.PolyLinguaForegroundService
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.ModelStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PackStatus {
    data object NotInstalled : PackStatus
    data object Queued : PackStatus
    data class Downloading(val bytes: Long, val total: Long, val bytesPerSec: Long) : PackStatus {
        val fraction: Float get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }
    data class Installed(val dir: File, val onSdCard: Boolean, val diskBytes: Long) : PackStatus
    data class Failed(val message: String) : PackStatus
}

data class StorageRoot(val label: String, val dir: File, val removable: Boolean, val freeBytes: Long, val totalBytes: Long)

/**
 * Downloads, verifies, stores and locates model packs. Packs live under `<root>/models/<id>/`
 * where root is the app-private internal storage or, when selected, the app folder on the
 * microSD card (getExternalFilesDirs(null)[1]) so the 1 GB LLM does not eat internal storage.
 * Archives are stream-extracted (network -> bzip2 -> tar -> filter) so only the needed files ever
 * touch the disk; large single files resume with HTTP Range requests.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val _statuses = MutableStateFlow<Map<String, PackStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PackStatus>> = _statuses

    init {
        scope.launch { rescan() }
    }

    // ------------------------------------------------------------------------ storage

    fun storageRoots(): List<StorageRoot> {
        val out = ArrayList<StorageRoot>(2)
        out += root("Internal storage", context.filesDir, removable = false)
        val externals = context.getExternalFilesDirs(null)
        externals.drop(1).filterNotNull().forEach { dir ->
            val state = runCatching { Environment.getExternalStorageState(dir) }.getOrDefault(Environment.MEDIA_UNKNOWN)
            if (state == Environment.MEDIA_MOUNTED && runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(true)) {
                out += root("microSD card", dir, removable = true)
            }
        }
        return out
    }

    private fun root(label: String, dir: File, removable: Boolean): StorageRoot {
        val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
        return StorageRoot(label, dir, removable, stat?.availableBytes ?: 0L, stat?.totalBytes ?: 0L)
    }

    private fun modelsDir(root: File) = File(root, "models")

    /** Preferred write location according to settings (falls back to internal). */
    fun preferredRoot(): StorageRoot {
        val roots = storageRoots()
        return if (settings.settings.value.modelStorage == ModelStorage.SD_CARD) roots.firstOrNull { it.removable } ?: roots.first() else roots.first()
    }

    /** Directory of an installed pack (searching every storage root), or null. */
    fun locate(pack: ModelPack): File? {
        for (r in storageRoots()) {
            val dir = File(modelsDir(r.dir), pack.id)
            if (isComplete(pack, dir)) return dir
        }
        return null
    }

    fun isInstalled(pack: ModelPack): Boolean = locate(pack) != null

    fun file(pack: ModelPack, name: String): File? = locate(pack)?.let { File(it, name) }?.takeIf { it.isFile }

    private fun isComplete(pack: ModelPack, dir: File): Boolean {
        if (!File(dir, MARKER).isFile) return false
        if (!pack.requiredFiles.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }) return false
        return pack.keepDirs.all { File(dir, it).isDirectory }
    }

    fun rescan() {
        val map = HashMap<String, PackStatus>()
        val roots = storageRoots()
        for (pack in ModelCatalog.ALL) {
            val current = _statuses.value[pack.id]
            if (current is PackStatus.Downloading || current is PackStatus.Queued) {
                map[pack.id] = current
                continue
            }
            val dir = roots.firstNotNullOfOrNull { r -> File(modelsDir(r.dir), pack.id).takeIf { isComplete(pack, it) }?.let { it to r.removable } }
            map[pack.id] = if (dir != null) PackStatus.Installed(dir.first, dir.second, dirSize(dir.first)) else
                (current as? PackStatus.Failed) ?: PackStatus.NotInstalled
        }
        _statuses.value = map
    }

    private fun setStatus(id: String, status: PackStatus) {
        _statuses.value = _statuses.value + (id to status)
    }

    // ----------------------------------------------------------------------- download

    fun isMeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    /** Queues [packs] for download; returns immediately. Progress is published on [statuses]. */
    fun enqueue(packs: List<ModelPack>) {
        for (pack in packs) {
            if (isInstalled(pack) || jobs[pack.id]?.isActive == true) continue
            setStatus(pack.id, PackStatus.Queued)
            jobs[pack.id] = scope.launch {
                downloadMutex.withLock {
                    try {
                        PolyLinguaForegroundService.acquire(context, PolyLinguaForegroundService.Reason.DOWNLOAD, pack.title)
                        install(pack)
                        rescan()
                    } catch (e: CancellationException) {
                        setStatus(pack.id, PackStatus.NotInstalled)
                        throw e
                    } catch (t: Throwable) {
                        Log.e(TAG, "Install of ${pack.id} failed", t)
                        setStatus(pack.id, PackStatus.Failed(t.message ?: t.javaClass.simpleName))
                    } finally {
                        if (jobs.values.count { it.isActive } <= 1) {
                            PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.DOWNLOAD)
                        }
                    }
                }
            }
        }
    }

    fun cancel(packId: String) {
        jobs.remove(packId)?.cancel()
        setStatus(packId, PackStatus.NotInstalled)
    }

    suspend fun delete(pack: ModelPack) = withContext(Dispatchers.IO) {
        for (r in storageRoots()) {
            File(modelsDir(r.dir), pack.id).deleteRecursively()
            File(modelsDir(r.dir), pack.id + PARTIAL).deleteRecursively()
        }
        setStatus(pack.id, PackStatus.NotInstalled)
    }

    private suspend fun install(pack: ModelPack) {
        if (isMeteredNetwork() && !settings.settings.value.cellularDownloadsAllowed && pack.sizeBytes > 50_000_000) {
            throw IOException("Metered connection: enable \"Allow downloads on mobile data\" or connect to Wi-Fi (${pack.sizeMb} MB).")
        }
        val root = preferredRoot()
        val needed = (pack.sizeBytes * if (pack.archive) 1.6 else 1.05).toLong()
        if (root.freeBytes in 1 until needed) {
            throw IOException("Not enough space on ${root.label}: ${pack.sizeMb} MB needed, ${root.freeBytes / 1_048_576} MB free.")
        }
        val base = modelsDir(root.dir).apply { mkdirs() }
        val finalDir = File(base, pack.id)
        val partialDir = File(base, pack.id + PARTIAL).apply { mkdirs() }
        var lastError: Throwable? = null
        for (url in listOf(pack.url) + pack.fallbackUrls) {
            try {
                if (pack.archive) streamExtract(pack, url, partialDir) else downloadFile(pack, url, File(partialDir, pack.requiredFiles.first()))
                lastError = null
                break
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Download from $url failed: ${t.message}")
            }
        }
        lastError?.let { throw it }
        val missing = pack.requiredFiles.filterNot { File(partialDir, it).isFile }
        if (missing.isNotEmpty()) throw IOException("Archive is missing ${missing.joinToString()}")
        File(partialDir, MARKER).writeText(
            JSONObject().put("id", pack.id).put("url", pack.url).put("installedAt", System.currentTimeMillis()).toString(),
        )
        finalDir.deleteRecursively()
        if (!partialDir.renameTo(finalDir)) {
            partialDir.copyRecursively(finalDir, overwrite = true)
            partialDir.deleteRecursively()
        }
    }

    private suspend fun downloadFile(pack: ModelPack, url: String, target: File) {
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 416) {
                // Already complete on disk.
                part.renameTo(target)
                return
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${pack.title}")
            val body = resp.body ?: throw IOException("Empty response body")
            val append = resp.code == 206 && existing > 0
            if (!append) existing = 0
            val total = if (append) existing + body.contentLength() else body.contentLength().takeIf { it > 0 } ?: pack.sizeBytes
            val meter = ProgressMeter(pack.id, total, existing)
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        meter.add(n.toLong())
                    }
                }
            }
            if (body.contentLength() > 0 && part.length() < total) throw IOException("Download interrupted (${part.length()} / $total bytes)")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not finalise ${target.name}")
    }

    private suspend fun streamExtract(pack: ModelPack, url: String, dest: File) {
        dest.deleteRecursively()
        dest.mkdirs()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${pack.title}")
            val body = resp.body ?: throw IOException("Empty response body")
            val total = body.contentLength().takeIf { it > 0 } ?: pack.sizeBytes
            val meter = ProgressMeter(pack.id, total, 0)
            val counting = CountingStream(body.byteStream().buffered(256 * 1024)) { meter.add(it) }
            val required = pack.requiredFiles.toSet()
            val destCanonical = dest.canonicalPath + File.separator
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val rel = relativePath(entry.name) ?: continue
                    val keep = rel in required || pack.keepDirs.any { rel == it || rel.startsWith("$it/") }
                    if (!keep) continue
                    val out = File(dest, rel)
                    if (!out.canonicalPath.startsWith(destCanonical)) throw IOException("Blocked unsafe archive path: ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    if (entry.isSymbolicLink || entry.isLink) continue
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fo ->
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            fo.write(buf, 0, n)
                        }
                    }
                }
            }
        }
    }

    /** Strips "./" and the archive's top-level folder: "./kokoro-int8-en-v0_19/voices.bin" -> "voices.bin". */
    private fun relativePath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size < 2) return null
        if (parts.any { it == ".." }) return null
        return parts.drop(1).joinToString("/")
    }

    private inner class ProgressMeter(private val id: String, private val total: Long, start: Long) {
        private var bytes = start
        private var lastEmit = 0L
        private var windowStart = System.nanoTime()
        private var windowBytes = 0L
        private var speed = 0L

        fun add(n: Long) {
            bytes += n
            windowBytes += n
            val now = System.nanoTime()
            val elapsed = now - windowStart
            if (elapsed >= 1_000_000_000L) {
                speed = windowBytes * 1_000_000_000L / elapsed
                windowStart = now
                windowBytes = 0
            }
            if (now - lastEmit > 250_000_000L) {
                lastEmit = now
                setStatus(id, PackStatus.Downloading(bytes, total, speed))
                if (total > 0) {
                    val pct = (100 * bytes / total).toInt()
                    PolyLinguaForegroundService.update(context, PolyLinguaForegroundService.Reason.DOWNLOAD,
                        "${ModelCatalog.byId(id)?.title ?: id}: $pct%")
                }
            }
        }
    }

    private class CountingStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it.toLong()) }
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val TAG = "PolyLinguaModels"
        private const val MARKER = ".polylingua-pack.json"
        private const val PARTIAL = ".partial"
        private const val USER_AGENT = "PolyLinguaEdge/1.0 (Android; offline model fetch)"
    }
}
