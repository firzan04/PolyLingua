package com.polylingua.edge.dsp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** PCM conversions, resampling and WAV I/O (16-bit PCM and 32-bit float, mono/stereo). */
object PcmUtils {

    fun shortsToFloats(src: ShortArray, count: Int = src.size): FloatArray =
        FloatArray(count) { src[it] / 32768f }

    fun floatsToShorts(src: FloatArray): ShortArray =
        ShortArray(src.size) { (src[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

    fun floatToPcm16Le(src: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(src.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in src) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return bb.array()
    }

    fun shortsToPcm16Le(src: ShortArray, count: Int = src.size): ByteArray {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(src[i])
        return bb.array()
    }

    /** Linear-interpolation resampler with a simple moving-average anti-alias pre-filter. */
    fun resample(src: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || src.isEmpty()) return src.copyOf()
        val input = if (toRate < fromRate) {
            val taps = max(1, floor(fromRate.toDouble() / toRate).toInt())
            if (taps <= 1) src else FloatArray(src.size) { i ->
                var s = 0f
                var c = 0
                for (k in -(taps / 2)..(taps / 2)) {
                    val idx = i + k
                    if (idx in src.indices) { s += src[idx]; c++ }
                }
                s / c
            }
        } else src
        val outLen = (input.size.toLong() * toRate / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = input[min(i0, input.size - 1)]
            val b = input[min(i0 + 1, input.size - 1)]
            a + frac * (b - a)
        }
    }

    fun rms(src: FloatArray): Float {
        if (src.isEmpty()) return 0f
        var s = 0.0
        for (v in src) s += v * v
        return sqrt(s / src.size).toFloat()
    }

    fun peakNormalize(src: FloatArray, target: Float = 0.89f): FloatArray {
        var peak = 0f
        for (v in src) peak = max(peak, abs(v))
        if (peak < 1e-6f) return src.copyOf()
        val g = target / peak
        return FloatArray(src.size) { src[it] * g }
    }

    // ------------------------------------------------------------------------------- WAV

    fun writeWav(out: OutputStream, samples: ShortArray, sampleRate: Int, count: Int = samples.size) {
        val dataLen = count * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataLen)
        out.write(header.array())
        out.write(shortsToPcm16Le(samples, count))
        out.flush()
    }

    fun writeWav(file: File, samples: ShortArray, sampleRate: Int, count: Int = samples.size) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { writeWav(it, samples, sampleRate, count) }
    }

    data class WavData(val samples: FloatArray, val sampleRate: Int)

    /** Reads RIFF/WAVE (PCM 8/16/24/32-bit or IEEE float 32) and downmixes to mono floats. */
    fun readWav(input: InputStream): WavData {
        val din = DataInputStream(input.buffered())
        fun le32(): Int { val b = ByteArray(4); din.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int }
        fun le16(): Int { val b = ByteArray(2); din.readFully(b); return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF }
        val riff = ByteArray(4).also { din.readFully(it) }
        require(String(riff) == "RIFF") { "Not a RIFF file" }
        le32()
        val wave = ByteArray(4).also { din.readFully(it) }
        require(String(wave) == "WAVE") { "Not a WAVE file" }
        var format = 1
        var channels = 1
        var rate = 16000
        var bits = 16
        while (true) {
            val id = ByteArray(4)
            try { din.readFully(id) } catch (e: EOFException) { throw IllegalArgumentException("No data chunk") }
            val size = le32()
            when (String(id)) {
                "fmt " -> {
                    format = le16(); channels = le16(); rate = le32(); le32(); le16(); bits = le16()
                    var remaining = size - 16
                    while (remaining > 0) { din.readByte(); remaining-- }
                    if (format == 0xFFFE) format = if (bits == 32) 3 else 1  // WAVE_FORMAT_EXTENSIBLE (common cases)
                }
                "data" -> {
                    val bytes = ByteArray(size).also { din.readFully(it) }
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val bytesPerSample = bits / 8
                    val frames = size / (bytesPerSample * channels)
                    val out = FloatArray(frames)
                    for (f in 0 until frames) {
                        var acc = 0f
                        for (c in 0 until channels) {
                            acc += when {
                                format == 3 && bits == 32 -> bb.float
                                bits == 16 -> bb.short / 32768f
                                bits == 8 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                                bits == 24 -> {
                                    val b0 = bb.get().toInt() and 0xFF
                                    val b1 = bb.get().toInt() and 0xFF
                                    val b2 = bb.get().toInt()
                                    ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                                }
                                bits == 32 -> bb.int / 2147483648f
                                else -> 0f
                            }
                        }
                        out[f] = acc / channels
                    }
                    return WavData(out, rate)
                }
                else -> {
                    var remaining = size + (size and 1)
                    while (remaining > 0) { din.readByte(); remaining-- }
                }
            }
        }
    }

    /** Growable PCM16 buffer used for session recordings. */
    class PcmAccumulator(initialCapacity: Int = 16000 * 30) {
        private var data = ShortArray(initialCapacity)
        var size = 0
            private set

        fun append(src: ShortArray, count: Int) {
            if (size + count > data.size) data = data.copyOf(max(data.size * 2, size + count))
            System.arraycopy(src, 0, data, size, count)
            size += count
        }

        fun snapshot(): ShortArray = data.copyOf(size)
        fun clear() { size = 0 }
        fun durationSec(sampleRate: Int): Double = size.toDouble() / sampleRate
    }

    fun toByteArrayWav(samples: ShortArray, sampleRate: Int): ByteArray {
        val bos = ByteArrayOutputStream(samples.size * 2 + 44)
        writeWav(bos, samples, sampleRate)
        return bos.toByteArray()
    }
}
