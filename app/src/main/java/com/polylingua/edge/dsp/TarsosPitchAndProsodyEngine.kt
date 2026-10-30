package com.polylingua.edge.dsp

import be.tarsos.dsp.pitch.FastYin
import be.tarsos.dsp.pitch.McLeodPitchMethod
import be.tarsos.dsp.pitch.PitchDetector
import com.polylingua.edge.domain.model.PitchFrame
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real-time prosody tracker on top of TarsosDSP: fundamental frequency (F0) via FFT-YIN (YIN with
 * the difference function computed through an FFT) or the McLeod Pitch Method, pitch confidence,
 * RMS energy in dBFS for syllable-stress detection, and DTW intonation comparison against a
 * native reference contour.
 *
 * At 16 kHz a 1024-sample window (64 ms) resolves F0 down to ~60 Hz; a 256-sample hop gives a
 * 16 ms frame rate (62.5 frames/s), comfortably faster than the 90 Hz UI needs to look smooth
 * after interpolation. Runs on a Cortex-A55 worker: ~0.2 ms per frame.
 */
class TarsosPitchAndProsodyEngine(
    val sampleRate: Int = 16000,
    val bufferSize: Int = 1024,
    val hopSize: Int = 256,
    val algorithm: Algorithm = Algorithm.FFT_YIN,
) {
    enum class Algorithm { FFT_YIN, MPM }

    private val detector: PitchDetector = when (algorithm) {
        Algorithm.FFT_YIN -> FastYin(sampleRate.toFloat(), bufferSize)
        Algorithm.MPM -> McLeodPitchMethod(sampleRate.toFloat(), bufferSize)
    }

    private val ring = FloatArray(bufferSize)
    private var ringPos = 0
    private var totalSamples = 0L
    private var sinceLastFrame = 0
    private val window = FloatArray(bufferSize)

    fun reset() {
        ring.fill(0f)
        ringPos = 0
        totalSamples = 0L
        sinceLastFrame = 0
    }

    /** Streams PCM16 samples; returns frames completed by this chunk (raw, unsmoothed). */
    fun push(samples: ShortArray, count: Int = samples.size): List<PitchFrame> {
        val out = ArrayList<PitchFrame>(count / hopSize + 1)
        for (i in 0 until count) {
            ring[ringPos] = samples[i] / 32768f
            ringPos = (ringPos + 1) % bufferSize
            totalSamples++
            sinceLastFrame++
            if (totalSamples >= bufferSize && sinceLastFrame >= hopSize) {
                sinceLastFrame = 0
                out += computeFrame()
            }
        }
        return out
    }

    /** Streams float samples in [-1, 1]. */
    fun push(samples: FloatArray, count: Int = samples.size): List<PitchFrame> {
        val out = ArrayList<PitchFrame>(count / hopSize + 1)
        for (i in 0 until count) {
            ring[ringPos] = samples[i]
            ringPos = (ringPos + 1) % bufferSize
            totalSamples++
            sinceLastFrame++
            if (totalSamples >= bufferSize && sinceLastFrame >= hopSize) {
                sinceLastFrame = 0
                out += computeFrame()
            }
        }
        return out
    }

    private fun computeFrame(): PitchFrame {
        // Unroll the ring into chronological order.
        val firstPart = bufferSize - ringPos
        System.arraycopy(ring, ringPos, window, 0, firstPart)
        System.arraycopy(ring, 0, window, firstPart, ringPos)
        val db = rmsDb(window, 0, bufferSize)
        val centerSec = (totalSamples - bufferSize / 2).toDouble() / sampleRate
        if (db < SILENCE_DB) return PitchFrame(centerSec, 0f, 0f, db)
        val result = detector.getPitch(window)
        val f0 = result.pitch
        val voiced = result.isPitched && f0 in MIN_F0..MAX_F0
        return PitchFrame(centerSec, if (voiced) f0 else 0f, if (voiced) result.probability else 0f, db)
    }

    /** Offline analysis of a whole utterance with smoothing/octave correction applied. */
    fun analyze(samples: FloatArray): List<PitchFrame> {
        reset()
        val raw = push(samples, samples.size)
        reset()
        return postProcess(raw)
    }

    fun analyze(samples: ShortArray): List<PitchFrame> {
        reset()
        val raw = push(samples, samples.size)
        reset()
        return postProcess(raw)
    }

    data class IntonationComparison(
        val similarityPercent: Double,
        val meanAbsSemitoneDistance: Double,
        val correlation: Double,
        val userContour: DoubleArray,
        val refContour: DoubleArray,
        val path: List<Pair<Int, Int>>,
    )

    companion object {
        const val SILENCE_DB = -50f
        const val MIN_F0 = 60f
        const val MAX_F0 = 500f

        fun rmsDb(buf: FloatArray, offset: Int, length: Int): Float {
            var sum = 0.0
            for (i in offset until offset + length) sum += buf[i] * buf[i]
            val rms = sqrt(sum / max(1, length))
            return if (rms <= 1e-9) -120f else (20.0 * log10(rms)).toFloat()
        }

        fun hzToSemitones(hz: Double, ref: Double = 100.0): Double = 12.0 * ln(hz / ref) / ln(2.0)

        /** Octave-error correction, isolated-frame removal and 5-point median smoothing. */
        fun postProcess(frames: List<PitchFrame>): List<PitchFrame> {
            if (frames.isEmpty()) return frames
            val f0 = FloatArray(frames.size) { frames[it].f0Hz }
            val voicedValues = f0.filter { it > 0f }.sorted()
            if (voicedValues.isEmpty()) return frames
            val median = voicedValues[voicedValues.size / 2]
            for (i in f0.indices) {
                if (f0[i] <= 0f) continue
                if (f0[i] > 1.8f * median) f0[i] /= 2f
                else if (f0[i] < 0.55f * median) f0[i] *= 2f
                if (f0[i] !in MIN_F0..MAX_F0) f0[i] = 0f
            }
            // remove voiced runs shorter than 3 frames (spurious)
            var i = 0
            while (i < f0.size) {
                if (f0[i] > 0f) {
                    var j = i
                    while (j < f0.size && f0[j] > 0f) j++
                    if (j - i < 3) for (k in i until j) f0[k] = 0f
                    i = j
                } else i++
            }
            val smoothed = f0.copyOf()
            for (k in f0.indices) {
                if (f0[k] <= 0f) continue
                val win = ArrayList<Float>(5)
                for (d in -2..2) {
                    val idx = k + d
                    if (idx in f0.indices && f0[idx] > 0f) win += f0[idx]
                }
                win.sort()
                smoothed[k] = win[win.size / 2]
            }
            return frames.mapIndexed { idx, fr ->
                fr.copy(f0Hz = smoothed[idx], probability = if (smoothed[idx] > 0f) fr.probability else 0f)
            }
        }

        /**
         * Speaker-normalised contour: voiced F0 in semitones relative to the speaker's median, short
         * unvoiced gaps (< 150 ms) linearly interpolated, long gaps removed. Decimated by [step].
         */
        fun relativeContour(frames: List<PitchFrame>, step: Int = 2): DoubleArray {
            val voiced = frames.filter { it.voiced }
            if (voiced.size < 3) return DoubleArray(0)
            val med = voiced.map { it.f0Hz }.sorted()[voiced.size / 2].toDouble()
            val values = ArrayList<Double>()
            var lastVoicedIdx = -1
            for ((idx, fr) in frames.withIndex()) {
                if (!fr.voiced) continue
                val st = hzToSemitones(fr.f0Hz.toDouble(), med)
                if (lastVoicedIdx >= 0) {
                    val gapFrames = idx - lastVoicedIdx - 1
                    val gapSec = if (gapFrames > 0) fr.timeSec - frames[lastVoicedIdx].timeSec else 0.0
                    if (gapFrames > 0 && gapSec < 0.15) {
                        val prev = values.last()
                        for (g in 1..gapFrames) values += prev + (st - prev) * g / (gapFrames + 1)
                    }
                }
                values += st
                lastVoicedIdx = idx
            }
            val out = ArrayList<Double>(values.size / step + 1)
            for (k in values.indices step max(1, step)) out += values[k]
            return out.toDoubleArray()
        }

        /** DTW (Sakoe-Chiba band) between speaker-normalised user and reference contours. */
        fun compareIntonation(user: List<PitchFrame>, reference: List<PitchFrame>): IntonationComparison {
            val u = relativeContour(user)
            val r = relativeContour(reference)
            if (u.size < 8 || r.size < 8) {
                return IntonationComparison(Double.NaN, Double.NaN, Double.NaN, u, r, emptyList())
            }
            val n = u.size
            val m = r.size
            val band = max(abs(n - m), (0.25 * max(n, m)).toInt()) + 2
            val inf = Double.MAX_VALUE / 4
            val cost = Array(n + 1) { DoubleArray(m + 1) { inf } }
            cost[0][0] = 0.0
            for (i in 1..n) {
                val center = i.toDouble() * m / n
                val jStart = max(1, (center - band).toInt())
                val jEnd = min(m, (center + band).toInt())
                for (j in jStart..jEnd) {
                    val d = abs(u[i - 1] - r[j - 1])
                    cost[i][j] = d + min(cost[i - 1][j - 1], min(cost[i - 1][j], cost[i][j - 1]))
                }
            }
            if (cost[n][m] >= inf) return IntonationComparison(Double.NaN, Double.NaN, Double.NaN, u, r, emptyList())
            // backtrack
            val path = ArrayList<Pair<Int, Int>>()
            var i = n
            var j = m
            while (i > 0 && j > 0) {
                path += (i - 1) to (j - 1)
                val diag = cost[i - 1][j - 1]
                val up = cost[i - 1][j]
                val left = cost[i][j - 1]
                when {
                    diag <= up && diag <= left -> { i--; j-- }
                    up <= left -> i--
                    else -> j--
                }
            }
            path.reverse()
            val meanDist = cost[n][m] / path.size
            // Pearson correlation along the path (shape agreement independent of offset)
            val xs = path.map { u[it.first] }
            val ys = path.map { r[it.second] }
            val mx = xs.average()
            val my = ys.average()
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (k in xs.indices) {
                sxy += (xs[k] - mx) * (ys[k] - my)
                sxx += (xs[k] - mx) * (xs[k] - mx)
                syy += (ys[k] - my) * (ys[k] - my)
            }
            val corr = if (sxx <= 1e-9 || syy <= 1e-9) 0.0 else sxy / sqrt(sxx * syy)
            val similarity = (100.0 * (0.6 * exp(-meanDist / 4.0) + 0.4 * max(0.0, corr))).coerceIn(0.0, 100.0)
            return IntonationComparison(similarity, meanDist, corr, u, r, path)
        }

        /** Times (s) of syllable nuclei: prominent RMS peaks separated by >= [minGapSec]. */
        fun syllableNuclei(frames: List<PitchFrame>, minGapSec: Double = 0.12): List<Double> {
            if (frames.size < 3) return emptyList()
            val db = frames.map { it.rmsDb.toDouble() }
            val smooth = DoubleArray(db.size) { k ->
                var s = 0.0
                var c = 0
                for (d in -1..1) {
                    val idx = k + d
                    if (idx in db.indices) { s += db[idx]; c++ }
                }
                s / c
            }
            val maxDb = smooth.maxOrNull() ?: return emptyList()
            val floor = maxDb - 25.0
            val peaks = ArrayList<Double>()
            for (k in 1 until smooth.size - 1) {
                if (smooth[k] < floor || smooth[k] < smooth[k - 1] || smooth[k] < smooth[k + 1]) continue
                // prominence vs. the minima within +-80 ms
                var leftMin = smooth[k]
                var rightMin = smooth[k]
                var a = k
                while (a > 0 && frames[k].timeSec - frames[a].timeSec < 0.08) { a--; leftMin = min(leftMin, smooth[a]) }
                var b = k
                while (b < smooth.size - 1 && frames[b].timeSec - frames[k].timeSec < 0.08) { b++; rightMin = min(rightMin, smooth[b]) }
                if (smooth[k] - max(leftMin, rightMin) < 3.0) continue
                val t = frames[k].timeSec
                if (peaks.isEmpty() || t - peaks.last() >= minGapSec) peaks += t
            }
            return peaks
        }
    }
}
