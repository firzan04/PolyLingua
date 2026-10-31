package com.polylingua.edge.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Speech front-end applied to the microphone signal before recognition (the raw
 * VOICE_RECOGNITION source has no AGC, so quiet speakers reach the recogniser at -45 dBFS):
 *
 *  1. 2nd-order Butterworth high-pass at 70 Hz (removes rumble, handling noise and DC).
 *  2. Speech-gated automatic gain: frames clearly above the tracked noise floor pull the gain
 *     toward a -20 dBFS speech target (max +24 dB); silence never boosts the noise.
 *  3. Smooth per-sample gain ramps and a soft limiter above -1 dBFS (no clipping).
 *
 * Stateful; call [process] on consecutive 16 kHz chunks.
 */
class SpeechLevelNormalizer(sampleRate: Int = 16000, private val targetDb: Double = -20.0, private val maxGainDb: Double = 24.0) {
    // High-pass biquad (RBJ cookbook, Q = 1/sqrt(2))
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private var gain = 1.0
    private var noiseFloorDb = -60.0
    private val maxGain = 10.0.pow(maxGainDb / 20)

    /** Current gain in dB (for diagnostics / tests). */
    val gainDb: Double get() = 20 * log10(gain)

    init {
        val fc = 70.0
        val k = tan(PI * fc / sampleRate)
        val q = 1 / sqrt(2.0)
        val norm = 1 / (1 + k / q + k * k)
        b0 = norm
        b1 = -2 * norm
        b2 = norm
        a1 = 2 * (k * k - 1) * norm
        a2 = (1 - k / q + k * k) * norm
    }

    fun process(buf: ShortArray, n: Int = buf.size) {
        if (n <= 0) return
        // 1) high-pass into a scratch array (normalised floats)
        val x = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val xi = buf[i] / 32768.0
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi
            y2 = y1; y1 = yi
            x[i] = yi
            sum += yi * yi
        }
        // 2) speech-gated gain update
        val rms = sqrt(sum / n)
        val db = if (rms <= 1e-9) -120.0 else 20 * log10(rms)
        val speech = db > noiseFloorDb + 9.0 && db > -62.0
        if (!speech) {
            noiseFloorDb = 0.97 * noiseFloorDb + 0.03 * db.coerceIn(-90.0, -20.0)
        }
        val start = gain
        if (speech) {
            val desired = (10.0.pow((targetDb - db) / 20)).coerceIn(1.0, maxGain)
            // Fast attack when the voice gets louder (avoid clipping), slow release when it gets softer.
            gain = if (desired < gain) gain + (desired - gain) * 0.5 else gain + (desired - gain) * 0.06
        }
        // 3) ramp the gain across the chunk and soft-limit
        for (i in 0 until n) {
            val g = start + (gain - start) * (i + 1) / n
            var y = x[i] * g
            val ay = abs(y)
            if (ay > LIMIT) {
                // Soft knee: compress the excess above the threshold.
                val over = ay - LIMIT
                y = (LIMIT + over / (1 + over * 8)) * (if (y < 0) -1 else 1)
            }
            buf[i] = (min(1.0, max(-1.0, y)) * 32767).toInt().toShort()
        }
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        gain = 1.0
        noiseFloorDb = -60.0
    }

    private companion object {
        const val LIMIT = 0.89 // -1 dBFS
    }
}
