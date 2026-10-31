package com.polylingua.edge.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Face measurements for one sampled video frame (null fields = no face found). */
data class FaceFrame(
    val timeSec: Double,
    val faceFound: Boolean,
    /** Head rotation left/right (degrees, 0 = facing the camera). */
    val yawDeg: Double = 0.0,
    /** Head rotation up/down (degrees). */
    val pitchDeg: Double = 0.0,
    val rollDeg: Double = 0.0,
    /** 0..1, or -1 when the classifier gave no value. */
    val smileProb: Double = -1.0,
    val leftEyeOpenProb: Double = -1.0,
    val rightEyeOpenProb: Double = -1.0,
    /** Face box area / frame area. */
    val faceAreaRatio: Double = 0.0,
    /** Face centre offset from frame centre, as a fraction of the frame size (0 = centred). */
    val centreOffset: Double = 0.0,
)

/**
 * Non-verbal delivery during a recorded mock interview. IELTS examiners do not score body
 * language, but eye contact, a steady head and an occasional smile make answers sound more
 * confident and fluent - and they matter in face-to-face interviews and presentations.
 */
data class BodyLanguageReport(
    val framesAnalysed: Int,
    val durationSec: Double,
    val facePresentPct: Double,
    val eyeContactPct: Double,
    val smilePct: Double,
    val eyesClosedPct: Double,
    val headYawStdDeg: Double,
    val headPitchStdDeg: Double,
    val lookAwayEvents: Int,
    val framing: String,
    /** 0..100 composite: eye contact 45 %, presence 20 %, steadiness 20 %, warmth 15 %. */
    val presenceScore: Double,
    /** Eye-contact share per 5-second bucket (0..1; -1 = no face in that bucket). */
    val eyeContactTimeline: List<Double>,
    val strengths: List<String>,
    val tips: List<String>,
)

/** Turns per-frame face measurements into the [BodyLanguageReport]. Pure and unit-tested. */
object BodyLanguageScorer {
    /** Head within this many degrees of the lens counts as eye contact with the examiner. */
    const val EYE_CONTACT_DEG = 12.0
    private const val LOOK_AWAY_DEG = 25.0

    /**
     * Eye contact = head facing the lens. [yawBase]/[pitchBase] absorb the learner's typical pose
     * (phone propped slightly low or to one side), limited to ±10° so looking down at notes
     * never counts as contact.
     */
    fun isEyeContact(f: FaceFrame, yawBase: Double = 0.0, pitchBase: Double = 0.0): Boolean =
        f.faceFound && abs(f.yawDeg - yawBase) <= EYE_CONTACT_DEG && abs(f.pitchDeg - pitchBase) <= EYE_CONTACT_DEG

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun std(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    private fun pct(n: Int, d: Int) = if (d == 0) 0.0 else 100.0 * n / d
    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0

    fun score(frames: List<FaceFrame>, durationSec: Double, bucketSec: Double = 5.0): BodyLanguageReport {
        val total = frames.size
        val faces = frames.filter { it.faceFound }
        val yawBase = median(faces.map { it.yawDeg }).coerceIn(-10.0, 10.0)
        val pitchBase = median(faces.map { it.pitchDeg }).coerceIn(-10.0, 10.0)
        fun contactAt(f: FaceFrame) = isEyeContact(f, yawBase, pitchBase)
        val present = pct(faces.size, total)
        val contact = pct(faces.count { contactAt(it) }, total)
        val smiles = faces.filter { it.smileProb >= 0 }
        val smilePct = pct(smiles.count { it.smileProb >= 0.6 }, smiles.size)
        val eyes = faces.filter { it.leftEyeOpenProb >= 0 && it.rightEyeOpenProb >= 0 }
        val closedPct = pct(eyes.count { it.leftEyeOpenProb < 0.3 && it.rightEyeOpenProb < 0.3 }, eyes.size)
        val yawStd = std(faces.map { it.yawDeg })
        val pitchStd = std(faces.map { it.pitchDeg })

        // Look-away events: transitions from contact to |yaw| or |pitch| beyond 25 degrees.
        var events = 0
        var wasContact = false
        for (f in frames) {
            val away = !f.faceFound || abs(f.yawDeg - yawBase) > LOOK_AWAY_DEG || abs(f.pitchDeg - pitchBase) > LOOK_AWAY_DEG
            if (wasContact && away) events++
            if (contactAt(f)) wasContact = true else if (away) wasContact = false
        }

        val meanArea = if (faces.isEmpty()) 0.0 else faces.map { it.faceAreaRatio }.average()
        val meanOffset = if (faces.isEmpty()) 0.0 else faces.map { it.centreOffset }.average()
        val framing = when {
            faces.isEmpty() -> "Face not visible"
            meanArea < 0.04 -> "Too far from the camera"
            meanArea > 0.40 -> "Too close to the camera"
            meanOffset > 0.22 -> "Off-centre"
            else -> "Well framed"
        }

        val buckets = if (durationSec <= 0) emptyList() else (0 until ((durationSec / bucketSec).toInt() + 1)).map { b ->
            val inBucket = frames.filter { it.timeSec >= b * bucketSec && it.timeSec < (b + 1) * bucketSec }
            val withFace = inBucket.filter { it.faceFound }
            if (withFace.isEmpty()) -1.0 else withFace.count { contactAt(it) }.toDouble() / inBucket.size
        }.let { list -> if (list.lastOrNull() == -1.0 && list.size > 1) list.dropLast(1) else list }

        val steadiness = (100 - (yawStd + pitchStd) * 2.5).coerceIn(0.0, 100.0)
        val warmth = (smilePct * 4).coerceIn(0.0, 100.0)
        val presence = if (total == 0) 0.0 else 0.45 * contact + 0.20 * present + 0.20 * steadiness + 0.15 * warmth

        val strengths = ArrayList<String>()
        val tips = ArrayList<String>()
        if (total == 0 || faces.isEmpty()) {
            tips += "Your face was not detected. Prop the phone at eye level, face a window or lamp, and keep your whole face in the frame."
        } else {
            if (contact >= 65) strengths += "Strong eye contact (${contact.roundToInt()} % of the time) - you look engaged with the examiner."
            else tips += "Look at the camera lens (your examiner) more: ${contact.roundToInt()} % eye contact; aim for 65 %+, especially while giving your main point."
            if (present < 85) tips += "You left the frame ${(100 - present).roundToInt()} % of the time - stay centred so the examiner can see you."
            if (yawStd + pitchStd <= 14) strengths += "Steady head position - calm and confident."
            else if (yawStd + pitchStd > 22) tips += "Your head moved a lot (±${r1(yawStd)}° side to side). Glancing away while thinking is natural, but come back to the camera to finish each idea."
            if (events >= 6 && durationSec > 0) tips += "You looked away $events times. Pause and think while still facing the examiner - it reads as composure, not hesitation."
            if (smiles.isNotEmpty()) {
                if (smilePct >= 12) strengths += "Warm, friendly expression (smiling ${smilePct.roundToInt()} % of the time)."
                else tips += "Smile occasionally - especially in Part 1 small talk - to sound relaxed and friendly."
            }
            if (closedPct > 12) tips += "Your eyes were closed or looking down ${closedPct.roundToInt()} % of the time - glance at notes briefly, then look up."
            if (framing != "Well framed") tips += "Framing: $framing. Hold the phone at eye level, about an arm's length away."
            else strengths += "Good framing and distance from the camera."
        }
        return BodyLanguageReport(
            framesAnalysed = total,
            durationSec = durationSec,
            facePresentPct = r1(present),
            eyeContactPct = r1(contact),
            smilePct = r1(smilePct),
            eyesClosedPct = r1(closedPct),
            headYawStdDeg = r1(yawStd),
            headPitchStdDeg = r1(pitchStd),
            lookAwayEvents = events,
            framing = framing,
            presenceScore = r1(presence),
            eyeContactTimeline = buckets.map { if (it < 0) -1.0 else (it * 100).roundToInt() / 100.0 },
            strengths = strengths,
            tips = tips,
        )
    }
}
