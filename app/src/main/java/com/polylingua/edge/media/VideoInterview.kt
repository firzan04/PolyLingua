package com.polylingua.edge.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.polylingua.edge.core.DiagLog
import com.polylingua.edge.domain.model.BodyLanguageReport
import com.polylingua.edge.domain.model.BodyLanguageScorer
import com.polylingua.edge.domain.model.FaceFrame
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.speech.AudioClip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Front-camera recorder for the video mock interview / mirror mode: CameraX Preview +
 * VideoCapture (1080p, falling back to the best lower quality the camera supports) bound to its
 * own lifecycle, so recording continues while the arena switches between question, preparation
 * and answer panels. Records video only: the app's microphone pipeline (recognition, pitch,
 * scoring) keeps exclusive use of the mic, and [Mp4AudioMuxer] adds the audio track afterwards.
 */
class VideoInterviewRecorder(private val context: Context) : LifecycleOwner {
    enum class Phase { CLOSED, OPENING, READY, RECORDING, FINALIZING, ERROR }

    data class Status(val phase: Phase = Phase.CLOSED, val message: String = "", val elapsedSec: Double = 0.0)

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var finalized: CompletableDeferred<File?>? = null
    private var output: File? = null

    /** SystemClock.elapsedRealtimeNanos() when the first frame was being recorded. */
    @Volatile var startedAtElapsedNs: Long = 0L
        private set

    val isReady: Boolean get() = videoCapture != null

    /** Opens the front camera (main thread). Safe to call repeatedly. */
    fun open() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED) && videoCapture != null) return
        _status.value = Status(Phase.OPENING, "Opening the front camera…")
        registry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val pv = Preview.Builder().build()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, pv, vc)
                preview = pv
                videoCapture = vc
                pendingSurface?.let { pv.setSurfaceProvider(it.surfaceProvider) }
                _status.value = Status(Phase.READY, "Front camera ready")
                DiagLog.i(TAG, "Front camera bound (Preview + VideoCapture)")
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Camera bind failed", t)
                _status.value = Status(Phase.ERROR, "Camera unavailable: ${t.message ?: t.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private var pendingSurface: PreviewView? = null

    /** Shows the live preview in [view] (main thread). */
    fun attachPreview(view: PreviewView) {
        pendingSurface = view
        preview?.setSurfaceProvider(view.surfaceProvider)
    }

    fun detachPreview(view: PreviewView) {
        if (pendingSurface === view) {
            pendingSurface = null
            preview?.setSurfaceProvider(null)
        }
    }

    /** Starts a video-only recording into [file]; false when the camera is not ready. */
    @SuppressLint("MissingPermission")
    fun start(file: File): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return true
        output = file
        startedAtElapsedNs = 0L
        val done = CompletableDeferred<File?>()
        finalized = done
        return try {
            recording = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                .start(ContextCompat.getMainExecutor(context)) { e ->
                    when (e) {
                        is VideoRecordEvent.Start -> {
                            startedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
                            _status.value = Status(Phase.RECORDING, "Recording")
                        }
                        is VideoRecordEvent.Status -> {
                            if (startedAtElapsedNs == 0L) startedAtElapsedNs = SystemClock.elapsedRealtimeNanos() - e.recordingStats.recordedDurationNanos
                            _status.value = _status.value.copy(elapsedSec = e.recordingStats.recordedDurationNanos / 1e9)
                        }
                        is VideoRecordEvent.Finalize -> {
                            recording = null
                            val f = output
                            val usable = f != null && f.isFile && f.length() > 4096 && e.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
                            if (e.hasError()) DiagLog.w(TAG, "Recording finalized with error ${e.error}: ${e.cause?.message}")
                            _status.value = Status(if (videoCapture != null) Phase.READY else Phase.CLOSED, if (usable) "Video saved" else "Video could not be saved")
                            done.complete(if (usable) f else null)
                        }
                        else -> {}
                    }
                }
            true
        } catch (t: Throwable) {
            DiagLog.e(TAG, "Could not start video recording", t)
            _status.value = Status(Phase.ERROR, "Recording failed: ${t.message}")
            finalized = null
            false
        }
    }

    /** Stops recording and returns the finished MP4 (video only), or null. */
    suspend fun stop(): File? {
        val r = recording ?: return finalized?.takeIf { it.isCompleted }?.await()
        _status.value = _status.value.copy(phase = Phase.FINALIZING, message = "Saving video…")
        runCatching { r.stop() }
        return withTimeoutOrNull(15_000) { finalized?.await() }
    }

    /** Stops and deletes the current recording (session aborted). */
    fun discard() {
        runCatching { recording?.stop() }
        recording = null
        output?.delete()
        output = null
    }

    /** Releases the camera (main thread). */
    fun close() {
        discard()
        runCatching { provider?.unbindAll() }
        videoCapture = null
        preview = null
        if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        _status.value = Status()
    }

    companion object {
        private const val TAG = "PolyLinguaVideo"
    }
}

/**
 * Audio for the interview video: examiner questions (synthesised) and the learner's answers
 * (microphone), each placed at the wall-clock time it was played/recorded, mixed into one track.
 */
class SessionAudioTimeline(private val sampleRate: Int = 16000) {
    private val parts = ArrayList<Pair<Long, ShortArray>>()

    val isEmpty: Boolean get() = synchronized(parts) { parts.isEmpty() }

    fun add(startElapsedNs: Long, pcm: ShortArray) {
        if (startElapsedNs <= 0 || pcm.isEmpty()) return
        synchronized(parts) { parts += startElapsedNs to pcm }
    }

    fun add(startElapsedNs: Long, clip: AudioClip) {
        val mono = if (clip.sampleRate == sampleRate) clip.samples else PcmUtils.resample(clip.samples, clip.sampleRate, sampleRate)
        add(startElapsedNs, PcmUtils.floatsToShorts(mono))
    }

    /** Mixes every part relative to [originNs] into [durationSec] of audio (clipped to 16 bit). */
    fun render(originNs: Long, durationSec: Double): ShortArray {
        val n = max(0, (durationSec * sampleRate).roundToInt())
        val mix = IntArray(n)
        synchronized(parts) {
            for ((t, pcm) in parts) {
                val off = ((t - originNs) / 1e9 * sampleRate).roundToInt()
                for (i in pcm.indices) {
                    val j = off + i
                    if (j in 0 until n) mix[j] += pcm[i].toInt()
                }
            }
        }
        return ShortArray(n) { mix[it].coerceIn(-32768, 32767).toShort() }
    }
}

/** Adds an AAC-LC audio track (encoded from 16-bit mono PCM) to a video-only MP4. */
object Mp4AudioMuxer {
    private const val TAG = "PolyLinguaMux"

    private class Encoded(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private fun encodeAac(pcm: ShortArray, sampleRate: Int, bitrate: Int): Pair<MediaFormat, List<Encoded>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = ArrayList<Encoded>()
        var outFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytes = PcmUtils.shortsToPcm16Le(pcm)
            var pos = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        val n = min(buf.remaining() and 1.inv(), bytes.size - pos)
                        val pts = (pos / 2).toLong() * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(bytes, pos, n)
                            codec.queueInputBuffer(idx, 0, n, pts, 0)
                            pos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buf.get(data)
                            out += Encoded(data, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return (outFormat ?: error("AAC encoder produced no format")) to out
    }

    /**
     * Writes [out] = video track of [video] + AAC of [pcm] (16 kHz mono, already aligned to the
     * first video frame). Samples are interleaved by timestamp. Returns false on failure.
     */
    fun mux(video: File, pcm: ShortArray, sampleRate: Int, out: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(video.absolutePath)
            val vTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("no video track")
            val vFormat = extractor.getTrackFormat(vTrack)
            val rotation = runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(video.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                }
            }.getOrNull() ?: 0
            val (aFormat, aac) = encodeAac(pcm, sampleRate, 64_000)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(rotation)
            val vOut = m.addTrack(vFormat)
            val aOut = m.addTrack(aFormat)
            m.start()
            extractor.selectTrack(vTrack)
            val cap = if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 * 1024 * 1024
            val vBuf = ByteBuffer.allocateDirect(max(cap, 1024 * 1024)).order(ByteOrder.nativeOrder())
            val info = MediaCodec.BufferInfo()
            var ai = 0
            while (true) {
                vBuf.clear()
                val size = extractor.readSampleData(vBuf, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                // Interleave: write every audio frame that starts before this video frame.
                while (ai < aac.size && aac[ai].ptsUs <= t) {
                    val a = aac[ai++]
                    info.set(0, a.data.size, a.ptsUs, 0)
                    m.writeSampleData(aOut, ByteBuffer.wrap(a.data), info)
                }
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, t, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(vOut, vBuf, info)
                extractor.advance()
            }
            while (ai < aac.size) {
                val a = aac[ai++]
                info.set(0, a.data.size, a.ptsUs, 0)
                m.writeSampleData(aOut, ByteBuffer.wrap(a.data), info)
            }
            m.stop()
            true
        } catch (t: Throwable) {
            DiagLog.e(TAG, "Muxing ${video.name} failed", t)
            out.delete()
            false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    /** Duration of a media file in seconds (0 when unknown). */
    fun durationSec(file: File): Double = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        }
    }.getOrDefault(0.0)
}

/**
 * Body-language analysis of a recorded interview: samples one frame per second with
 * MediaMetadataRetriever and runs ML Kit face detection (bundled model, fully offline) to measure
 * eye contact (head facing the lens), smiles, eye closure, head movement and framing.
 */
object BodyLanguageAnalyzer {
    private const val TAG = "PolyLinguaBody"

    suspend fun analyze(video: File, stepMs: Long = 1000, onProgress: (Float) -> Unit = {}): BodyLanguageReport? = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(0.12f)
                .build(),
        )
        try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0) return@withContext null
            val frames = ArrayList<FaceFrame>()
            // Frames from MediaMetadataRetriever are normally upright; if no face is found in the
            // first frames, try the other orientations once and keep the one that works.
            var rotation = 0
            var rotationSettled = false
            var t = 0L
            while (t < durationMs) {
                onProgress(t.toFloat() / durationMs)
                val bmp: Bitmap? = runCatching {
                    retriever.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640)
                }.getOrNull()
                if (bmp != null) {
                    var frame = detect(detector, bmp, rotation, t / 1000.0)
                    if (!frame.faceFound && !rotationSettled) {
                        for (r in listOf(270, 90, 180)) {
                            val alt = detect(detector, bmp, r, t / 1000.0)
                            if (alt.faceFound) { rotation = r; frame = alt; break }
                        }
                    }
                    if (frame.faceFound) rotationSettled = true
                    frames += frame
                    bmp.recycle()
                }
                t += stepMs
            }
            onProgress(1f)
            if (frames.isEmpty()) null else BodyLanguageScorer.score(frames, durationMs / 1000.0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            DiagLog.e(TAG, "Body-language analysis failed", t)
            null
        } finally {
            runCatching { detector.close() }
            runCatching { retriever.release() }
        }
    }

    private fun detect(detector: com.google.mlkit.vision.face.FaceDetector, bmp: Bitmap, rotation: Int, timeSec: Double): FaceFrame {
        val faces = runCatching { Tasks.await(detector.process(InputImage.fromBitmap(bmp, rotation))) }.getOrNull().orEmpty()
        val w = if (rotation == 90 || rotation == 270) bmp.height else bmp.width
        val h = if (rotation == 90 || rotation == 270) bmp.width else bmp.height
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return FaceFrame(timeSec, false)
        val box: Rect = face.boundingBox
        val area = (box.width().toDouble() * box.height()) / (w.toDouble() * h)
        val cx = box.exactCenterX() / w - 0.5
        val cy = box.exactCenterY() / h - 0.5
        return FaceFrame(
            timeSec = timeSec,
            faceFound = true,
            yawDeg = face.headEulerAngleY.toDouble(),
            pitchDeg = face.headEulerAngleX.toDouble(),
            rollDeg = face.headEulerAngleZ.toDouble(),
            smileProb = face.smilingProbability?.toDouble() ?: -1.0,
            leftEyeOpenProb = face.leftEyeOpenProbability?.toDouble() ?: -1.0,
            rightEyeOpenProb = face.rightEyeOpenProbability?.toDouble() ?: -1.0,
            faceAreaRatio = area.coerceIn(0.0, 1.0),
            centreOffset = hypot(cx, cy),
        )
    }

    @Suppress("unused")
    private fun clampDeg(x: Float) = max(-90f, min(90f, abs(x)))
}
