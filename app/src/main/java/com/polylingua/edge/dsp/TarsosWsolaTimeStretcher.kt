package com.polylingua.edge.dsp

import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.UniversalAudioInputStream
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Pitch-preserving tempo change with TarsosDSP's WSOLA (Waveform Similarity Overlap-Add).
 * Shadowing (Cake) and phoneme drills (ELSA) play native references from 0.50x to 1.50x in 0.05x
 * steps; WSOLA cuts/duplicates ~40 ms sequences at the best waveform-matching overlap position,
 * so the speaker's voice keeps its original pitch and timbre.
 */
object TarsosWsolaTimeStretcher {

    const val MIN_TEMPO = 0.50
    const val MAX_TEMPO = 1.50
    const val STEP = 0.05

    /** Snaps an arbitrary factor to the 0.05x grid inside [0.50, 1.50]. */
    fun quantize(tempo: Double): Double {
        val clamped = tempo.coerceIn(MIN_TEMPO, MAX_TEMPO)
        return ((clamped / STEP).roundToInt() * STEP * 100).roundToInt() / 100.0
    }

    val SUPPORTED_TEMPI: List<Double> = (0..20).map { quantize(MIN_TEMPO + it * STEP) }

    /**
     * Returns [input] (mono, [-1, 1]) time-stretched by [tempo] (< 1 slower, > 1 faster). Output
     * length ~ input.size / tempo. Tempo 1.0 returns a copy.
     */
    fun stretch(input: FloatArray, sampleRate: Int, tempo: Double): FloatArray {
        val t = quantize(tempo)
        if (input.isEmpty() || t == 1.0) return input.copyOf()
        val wsola = WaveformSimilarityBasedOverlapAdd(
            WaveformSimilarityBasedOverlapAdd.Parameters.speechDefaults(t, sampleRate.toDouble()),
        )
        val format = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val pcm = PcmUtils.floatToPcm16Le(input)
        val stream = UniversalAudioInputStream(ByteArrayInputStream(pcm), format)
        val dispatcher = AudioDispatcher(stream, wsola.inputBufferSize, wsola.overlap)
        wsola.setDispatcher(dispatcher)
        dispatcher.addAudioProcessor(wsola)
        val collector = FloatCollector((input.size / t).toInt() + sampleRate)
        dispatcher.addAudioProcessor(object : AudioProcessor {
            override fun process(audioEvent: AudioEvent): Boolean {
                val buf = audioEvent.floatBuffer
                collector.append(buf, 0, buf.size)
                return true
            }

            override fun processingFinished() = Unit
        })
        dispatcher.run()
        val expected = (input.size / t).toInt()
        return collector.toArray(expected)
    }

    private class FloatCollector(initial: Int) {
        private var data = FloatArray(maxOf(initial, 1024))
        private var size = 0
        fun append(src: FloatArray, offset: Int, length: Int) {
            if (size + length > data.size) data = data.copyOf(maxOf(data.size * 2, size + length))
            System.arraycopy(src, offset, data, size, length)
            size += length
        }

        /** Trims trailing zero padding introduced by the last WSOLA buffer. */
        fun toArray(expected: Int): FloatArray = data.copyOf(minOf(size, maxOf(expected, 0)).coerceAtLeast(minOf(size, 1)))
    }
}
