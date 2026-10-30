package com.polylingua.edge.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Cake-style Shadowing Mastery Score:
 *   mastery = 0.40 * phoneme accuracy (GOP) + 0.35 * intonation similarity (F0 DTW)
 *           + 0.25 * rhythm & timing synchronisation (syllable-onset alignment)
 * Grade S >= 90, A >= 80, B >= 65, otherwise C.
 */
object ShadowingScorer {

    data class Result(
        val mastery: Double,
        val grade: String,
        val phonemeAccuracy: Double,
        val intonationSimilarity: Double,
        val rhythmSync: Double,
    )

    fun score(phonemeAccuracy: Double, intonationSimilarity: Double, rhythmSync: Double): Result {
        val p = phonemeAccuracy.takeUnless { it.isNaN() } ?: 60.0
        val i = intonationSimilarity.takeUnless { it.isNaN() } ?: 50.0
        val r = rhythmSync.takeUnless { it.isNaN() } ?: 50.0
        val m = (0.40 * p + 0.35 * i + 0.25 * r).coerceIn(0.0, 100.0)
        return Result(m, grade(m), p, i, r)
    }

    fun grade(score: Double): String = when {
        score >= 90 -> "S"
        score >= 80 -> "A"
        score >= 65 -> "B"
        else -> "C"
    }

    /**
     * Rhythm similarity 0-100 from syllable-nucleus onset times. Both sequences are normalised to
     * their own span (so a slower but proportionally identical rhythm still scores well), aligned
     * with DTW, and the mean absolute deviation is mapped through exp(-d / 0.06). A mismatch in
     * syllable count is penalised proportionally.
     */
    fun rhythmSimilarity(userOnsets: List<Double>, refOnsets: List<Double>): Double {
        if (userOnsets.size < 2 || refOnsets.size < 2) return Double.NaN
        fun norm(v: List<Double>): List<Double> {
            val a = v.first()
            val span = max(1e-6, v.last() - a)
            return v.map { (it - a) / span }
        }
        val u = norm(userOnsets)
        val r = norm(refOnsets)
        val n = u.size
        val m = r.size
        val inf = Double.MAX_VALUE / 4
        val d = Array(n + 1) { DoubleArray(m + 1) { inf } }
        val len = Array(n + 1) { IntArray(m + 1) }
        d[0][0] = 0.0
        for (i in 1..n) for (j in 1..m) {
            val c = abs(u[i - 1] - r[j - 1])
            var best = d[i - 1][j - 1]
            var l = len[i - 1][j - 1]
            if (d[i - 1][j] < best) { best = d[i - 1][j]; l = len[i - 1][j] }
            if (d[i][j - 1] < best) { best = d[i][j - 1]; l = len[i][j - 1] }
            d[i][j] = best + c
            len[i][j] = l + 1
        }
        val meanDev = d[n][m] / max(1, len[n][m])
        val countPenalty = min(n, m).toDouble() / max(n, m)
        return (100.0 * exp(-meanDev / 0.06) * (0.5 + 0.5 * countPenalty)).coerceIn(0.0, 100.0)
    }
}
