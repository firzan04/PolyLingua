package com.polylingua.edge.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.speech.AudioClip
import java.nio.ByteOrder

/** One timed subtitle line. */
data class SubtitleCue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

/** SubRip (.srt) and WebVTT (.vtt) parser tolerant of BOMs, styling tags and missing indices. */
object SubtitleParser {
    private val TIME = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val ARROW = Regex("""\s*-->\s*""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*}""")

    fun parse(text: String): List<SubtitleCue> {
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val parts = line.split(ARROW)
                val start = parts.getOrNull(0)?.let { toMs(it) }
                val end = parts.getOrNull(1)?.trim()?.split(' ')?.firstOrNull()?.let { toMs(it) }
                i++
                val body = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (body.isNotEmpty()) body.append(' ')
                    body.append(lines[i].trim())
                    i++
                }
                val clean = TAGS.replace(body.toString(), "").replace(Regex("\\s+"), " ").trim()
                if (start != null && end != null && end > start && clean.isNotEmpty()) cues += SubtitleCue(cues.size, start, end, clean)
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }.mapIndexed { idx, c -> c.copy(index = idx) }
    }

    private fun toMs(s: String): Long? {
        val m = TIME.find(s.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + frac
    }

    /** Finds the cue overlapping [startMs, endMs] best (for matching translation subtitles). */
    fun bestOverlap(cues: List<SubtitleCue>, startMs: Long, endMs: Long): SubtitleCue? =
        cues.maxByOrNull { c -> (minOf(c.endMs, endMs) - maxOf(c.startMs, startMs)) }
            ?.takeIf { c -> minOf(c.endMs, endMs) - maxOf(c.startMs, startMs) > 0 }
}

object MediaUtils {
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "media"
    }

    fun durationMs(context: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
}

/** Writes timed lines as SubRip (.srt). */
object SubtitleWriter {
    private fun ts(sec: Double): String {
        val ms = Math.round(sec * 1000).coerceAtLeast(0L)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
    }

    fun toSrt(lines: List<com.polylingua.edge.speech.TimedSegment>): String = buildString {
        lines.forEachIndexed { i, l ->
            append(i + 1).append('\n')
            append(ts(l.startSec)).append(" --> ").append(ts(l.endSec)).append('\n')
            append(l.text.trim()).append("\n\n")
        }
    }
}

/**
 * Decodes the audio of a local video/audio file between two timestamps to 16 kHz mono floats
 * (MediaExtractor + MediaCodec), giving the shadowing scorer a native reference for pitch and
 * rhythm comparison of the current subtitle line.
 */
object MediaPcmExtractor {
    fun extract(context: Context, uri: Uri, startMs: Long, endMs: Long): AudioClip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val out = ArrayList<Float>(((endMs - startMs) / 1000.0 * sampleRate).toInt().coerceAtLeast(1024))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmFloat = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0 || extractor.sampleTime > endUs + 200_000) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val buf = c.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val t0 = info.presentationTimeUs
                        val frames = if (pcmFloat) info.size / 4 / channels else info.size / 2 / channels
                        for (fr in 0 until frames) {
                            var acc = 0f
                            for (ch in 0 until channels) acc += if (pcmFloat) buf.float else buf.short / 32768f
                            val tUs = t0 + fr * 1_000_000L / sampleRate
                            if (tUs in startUs..endUs) out += acc / channels
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || t0 > endUs) outputDone = true
                    }
                }
            }
            if (out.isEmpty()) return null
            val mono = out.toFloatArray()
            return AudioClip(if (sampleRate == 16000) mono else PcmUtils.resample(mono, sampleRate, 16000), 16000)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /**
     * Decodes a whole audio track (up to [maxSec]) to 16 kHz mono PCM16 with a streaming,
     * drift-free resampler, so a 20-minute video needs ~38 MB instead of hundreds.
     */
    fun extractAll(context: Context, uri: Uri, maxSec: Int, onProgress: (Float) -> Unit = {}): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val limitUs = maxSec * 1_000_000L
            val totalUs = if (durationUs > 0) minOf(durationUs, limitUs) else limitUs
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val acc = PcmUtils.PcmAccumulator(16000 * 120)
            val chunk = ShortArray(4096)
            var fill = 0
            val resampler = PcmUtils.StreamingResampler(sampleRate, 16000)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmFloat = false
            var lastProgress = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0 || extractor.sampleTime > limitUs) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        resampler.setInputRate(sampleRate)
                    }
                    outIdx >= 0 -> {
                        val buf = c.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frames = if (pcmFloat) info.size / 4 / channels else info.size / 2 / channels
                        for (fr in 0 until frames) {
                            var m = 0f
                            for (ch in 0 until channels) m += if (pcmFloat) buf.float else buf.short / 32768f
                            resampler.push(m / channels) { v ->
                                chunk[fill++] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                                if (fill == chunk.size) { acc.append(chunk, fill); fill = 0 }
                            }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.presentationTimeUs - lastProgress > 2_000_000 && totalUs > 0) {
                            lastProgress = info.presentationTimeUs
                            onProgress((info.presentationTimeUs.toDouble() / totalUs).toFloat().coerceIn(0f, 1f))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs > limitUs) outputDone = true
                    }
                }
            }
            if (fill > 0) acc.append(chunk, fill)
            onProgress(1f)
            return acc.snapshot().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            com.polylingua.edge.core.DiagLog.w("PolyLinguaMedia", "Full audio extraction failed", t)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
