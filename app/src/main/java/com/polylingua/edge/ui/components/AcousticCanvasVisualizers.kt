package com.polylingua.edge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.PhonemeGrade
import com.polylingua.edge.domain.model.PhonemeScore
import com.polylingua.edge.domain.model.PitchFrame
import com.polylingua.edge.domain.model.WordPronunciation
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyMotion
import com.polylingua.edge.ui.theme.PolyType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Scrolling mirrored peak envelope (newest bar on the right), 3.2 s window. */
@Composable
fun LiveWaveformCanvas(envelope: FloatArray, active: Boolean, modifier: Modifier = Modifier) {
    val brush = Brush.verticalGradient(listOf(PolyColors.NeonMintSuccess, PolyColors.ElectricTeal, PolyColors.NeonMintSuccess))
    Canvas(modifier.fillMaxWidth().height(84.dp)) {
        val n = envelope.size.coerceAtLeast(1)
        val barW = size.width / n
        val mid = size.height / 2
        drawLine(PolyColors.FrostedBorder, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        for (i in 0 until envelope.size) {
            // Perceptual (log-ish) scaling so quiet speech is still visible.
            val v = envelope[i].coerceIn(0f, 1f)
            val h = (ln(1f + 30f * v) / ln(31f)) * mid * 0.95f
            if (h < 0.5f) continue
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * barW + barW * 0.15f, mid - h),
                size = Size(barW * 0.7f, h * 2),
                cornerRadius = CornerRadius(barW * 0.35f),
                alpha = if (active) 1f else 0.35f,
            )
        }
    }
}

private fun semitonesRelative(hz: Float, refHz: Float): Float = (12.0 * ln(hz / refHz.toDouble()) / ln(2.0)).toFloat()

/**
 * F0 contour in semitones relative to the speaker's median (-12..+12 st), learner in teal and the
 * native reference (when given) in dashed amber - the ELSA-style intonation overlay.
 */
@Composable
fun PitchContourCanvas(
    user: FloatArray,
    modifier: Modifier = Modifier,
    reference: FloatArray? = null,
    height: Dp = 120.dp,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val range = 12f
        fun y(st: Float) = size.height / 2 - (st.coerceIn(-range, range) / range) * (size.height / 2 * 0.9f)
        for (st in listOf(-12f, -6f, 0f, 6f, 12f)) {
            drawLine(
                PolyColors.FrostedBorder.copy(alpha = if (st == 0f) 0.9f else 0.4f), Offset(0f, y(st)), Offset(size.width, y(st)),
                strokeWidth = 1f, pathEffect = if (st == 0f) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        fun contour(values: FloatArray, color: Color, dashed: Boolean) {
            val voiced = values.filter { it > 0f }.sorted()
            if (voiced.size < 3) return
            val median = voiced[voiced.size / 2]
            val step = size.width / max(1, values.size - 1)
            val path = Path()
            var pen = false
            for (i in values.indices) {
                val hz = values[i]
                if (hz <= 0f) { pen = false; continue }
                val p = Offset(i * step, y(semitonesRelative(hz, median)))
                if (!pen) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                pen = true
            }
            drawPath(
                path, color,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null),
            )
        }
        reference?.let { contour(it, PolyColors.AmberWarning, true) }
        contour(user, PolyColors.ElectricTeal, false)
    }
}

/** Downsamples pitch frames to an F0 array for [PitchContourCanvas]. */
fun pitchToArray(frames: List<PitchFrame>, maxPoints: Int = 400): FloatArray {
    if (frames.isEmpty()) return FloatArray(0)
    val step = max(1, frames.size / maxPoints)
    return FloatArray((frames.size + step - 1) / step) { i -> frames[min(frames.size - 1, i * step)].f0Hz }
}

/** Semicircular speech-rate gauge 0-220 WPM with the Band 8-9 window (130-165) in mint. */
@Composable
fun WpmGauge(wpm: Double, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    val animated by animateFloatAsState(wpm.toFloat().coerceIn(0f, 220f), PolyMotion.spring(), label = "wpm")
    Box(modifier.size(size, size * 0.66f), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val diameter = min(this.size.width, this.size.height * 2) - stroke
            val topLeft = Offset((this.size.width - diameter) / 2, stroke / 2)
            val arcSize = Size(diameter, diameter)
            fun sweepFor(v: Float) = 180f * (v / 220f)
            drawArc(PolyColors.ObsidianSlateHigh, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(PolyColors.NeonMintSuccess.copy(alpha = 0.35f), 180f + sweepFor(130f), sweepFor(35f), false, topLeft, arcSize, style = Stroke(stroke))
            val color = when {
                animated in 130f..165f -> PolyColors.NeonMintSuccess
                animated in 100f..190f -> PolyColors.ElectricTeal
                animated < 60f -> PolyColors.CrimsonAlert
                else -> PolyColors.AmberWarning
            }
            drawArc(color, 180f, sweepFor(animated), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = PI + PI * (animated / 220f)
            val c = Offset(topLeft.x + diameter / 2, topLeft.y + diameter / 2)
            val r = diameter / 2 - stroke * 1.4f
            drawLine(PolyColors.PureIceWhite, c, Offset(c.x + (r * cos(angle)).toFloat(), c.y + (r * sin(angle)).toFloat()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(PolyColors.PureIceWhite, 5.dp.toPx(), c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 2.dp)) {
            Text("${animated.toInt()}", style = PolyType.NumericMedium.copy(fontSize = 22.sp))
            Text("WPM · target 130-165", style = PolyType.Caption.copy(fontSize = 9.sp))
        }
    }
}

/** Big record button: pulsing rings scale with the input level while recording. */
@Composable
fun PulsingMicButton(recording: Boolean, levelDb: Float, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 88.dp, enabled: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val level = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    val levelScale by animateFloatAsState(1f + 0.25f * level, PolyMotion.spring(), label = "lvl")
    val base = if (recording) PolyColors.CrimsonAlert else PolyColors.ElectricTeal
    Box(modifier.size(size * 1.6f), contentAlignment = Alignment.Center) {
        if (recording) {
            Canvas(Modifier.fillMaxSize()) {
                val r0 = size.toPx() / 2
                for (k in 0 until 2) {
                    val t = (pulse + k * 0.5f) % 1f
                    drawCircle(base.copy(alpha = (1f - t) * 0.45f), radius = r0 * (1f + 0.6f * t) * levelScale, style = Stroke(3.dp.toPx()))
                }
            }
        }
        Box(
            Modifier.size(size).scale(if (recording) levelScale.coerceAtMost(1.12f) else 1f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(base, base.copy(alpha = 0.75f))))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (recording) PolyIcons.Stop else PolyIcons.Mic, if (recording) "Stop" else "Record", tint = PolyColors.AmazeVoidBlack, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/** Ring showing remaining time fraction with an HH:MM:SS / seconds label in the centre. */
@Composable
fun CountdownRing(fraction: Float, label: String, modifier: Modifier = Modifier, size: Dp = 64.dp, color: Color = PolyColors.ElectricTeal) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), PolyMotion.spring(), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 5.dp.toPx()
            drawArc(PolyColors.ObsidianSlateHigh, -90f, 360f, false, Offset(s / 2, s / 2), Size(this.size.width - s, this.size.height - s), style = Stroke(s))
            drawArc(if (f < 0.15f) PolyColors.CrimsonAlert else color, -90f, 360f * f, false, Offset(s / 2, s / 2), Size(this.size.width - s, this.size.height - s), style = Stroke(s, cap = StrokeCap.Round))
        }
        Text(label, style = PolyType.Numeric.copy(fontSize = 13.sp))
    }
}

/** 0-100 mastery / score ring. */
@Composable
fun ScoreRing(score: Double, label: String, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val color = PolyColors.forScore(score)
    val f by animateFloatAsState((score / 100.0).toFloat().coerceIn(0f, 1f), PolyMotion.spring(), label = "score")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 8.dp.toPx()
            val o = Offset(s / 2, s / 2)
            val sz = Size(this.size.width - s, this.size.height - s)
            drawArc(PolyColors.ObsidianSlateHigh, -90f, 360f, false, o, sz, style = Stroke(s))
            drawArc(color, -90f, 360f * f, false, o, sz, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${score.toInt()}", style = PolyType.NumericMedium.copy(color = color))
            Text(label, style = PolyType.Caption.copy(fontSize = 9.sp))
        }
    }
}

/** Four-axis IELTS radar (FC top, LR right, GRA bottom, P left) on a 0-9 scale. */
@Composable
fun BandRadarChart(fc: Double, lr: Double, gra: Double, p: Double, modifier: Modifier = Modifier, target: Double? = null) {
    val a1 by animateFloatAsState(fc.toFloat(), PolyMotion.spring(), label = "fc")
    val a2 by animateFloatAsState(lr.toFloat(), PolyMotion.spring(), label = "lr")
    val a3 by animateFloatAsState(gra.toFloat(), PolyMotion.spring(), label = "gra")
    val a4 by animateFloatAsState(p.toFloat(), PolyMotion.spring(), label = "p")
    Box(modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(22.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2
            fun pt(axis: Int, v: Float): Offset {
                val ang = -PI / 2 + axis * PI / 2
                val rr = r * (v / 9f)
                return Offset(c.x + (rr * cos(ang)).toFloat(), c.y + (rr * sin(ang)).toFloat())
            }
            for (ring in listOf(3f, 6f, 9f)) {
                val path = Path().apply {
                    moveTo(pt(0, ring).x, pt(0, ring).y)
                    for (k in 1..3) lineTo(pt(k, ring).x, pt(k, ring).y)
                    close()
                }
                drawPath(path, PolyColors.FrostedBorder, style = Stroke(1f))
            }
            for (k in 0..3) drawLine(PolyColors.FrostedBorder, c, pt(k, 9f), strokeWidth = 1f)
            if (target != null) {
                val t = target.toFloat()
                val tp = Path().apply { moveTo(pt(0, t).x, pt(0, t).y); for (k in 1..3) lineTo(pt(k, t).x, pt(k, t).y); close() }
                drawPath(tp, PolyColors.AmberWarning, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            }
            val values = listOf(a1, a2, a3, a4)
            val poly = Path().apply {
                moveTo(pt(0, values[0]).x, pt(0, values[0]).y)
                for (k in 1..3) lineTo(pt(k, values[k]).x, pt(k, values[k]).y)
                close()
            }
            drawPath(poly, PolyColors.ElectricTeal.copy(alpha = 0.25f))
            drawPath(poly, PolyColors.ElectricTeal, style = Stroke(2.5f))
            values.forEachIndexed { k, v -> drawCircle(PolyColors.NeonMintSuccess, 4.dp.toPx(), pt(k, v)) }
        }
        Text("FC ${"%.1f".format(fc)}", style = PolyType.Pill, modifier = Modifier.align(Alignment.TopCenter))
        Text("LR ${"%.1f".format(lr)}", style = PolyType.Pill, modifier = Modifier.align(Alignment.CenterEnd))
        Text("GRA ${"%.1f".format(gra)}", style = PolyType.Pill, modifier = Modifier.align(Alignment.BottomCenter))
        Text("P ${"%.1f".format(p)}", style = PolyType.Pill, modifier = Modifier.align(Alignment.CenterStart))
    }
}

private val CEFR_COLORS = mapOf(
    CefrLevel.A1 to Color(0xFF64748B), CefrLevel.A2 to Color(0xFF94A3B8), CefrLevel.B1 to Color(0xFF38BDF8),
    CefrLevel.B2 to PolyColors.ElectricTeal, CefrLevel.C1 to PolyColors.NeonMintSuccess, CefrLevel.C2 to PolyColors.AmberWarning,
)

/** Stacked bar of the lexical CEFR profile with a legend. */
@Composable
fun CefrDistributionBar(distribution: Map<CefrLevel, Double>, modifier: Modifier = Modifier) {
    val total = distribution.values.sum().takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(PolyColors.ObsidianSlateHigh)) {
            CefrLevel.entries.forEach { lv ->
                val share = ((distribution[lv] ?: 0.0) / total).toFloat()
                if (share > 0.001f) Box(Modifier.weight(share).height(16.dp).background(CEFR_COLORS.getValue(lv)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CefrLevel.entries.forEach { lv ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(CEFR_COLORS.getValue(lv)))
                    Spacer(Modifier.width(3.dp))
                    Text("${lv.name} ${((distribution[lv] ?: 0.0) / total * 100).toInt()}%", style = PolyType.Caption.copy(fontSize = 10.sp))
                }
            }
        }
    }
}

fun gradeColor(g: PhonemeGrade): Color = when (g) {
    PhonemeGrade.GREEN -> PolyColors.NeonMintSuccess
    PhonemeGrade.AMBER -> PolyColors.AmberWarning
    PhonemeGrade.RED -> PolyColors.CrimsonAlert
}

/** Word blocks with per-phoneme GOP chips (tap a chip for the remediation sheet). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhonemeHeatmap(words: List<WordPronunciation>, onPhoneme: (WordPronunciation, PhonemeScore) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        words.forEach { w ->
            val wc = PolyColors.forScore(w.score.toDouble())
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).background(PolyColors.ObsidianSlate).border(1.dp, wc.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.word, style = PolyType.CardTitle.copy(color = wc))
                    Spacer(Modifier.width(6.dp))
                    Text("${w.score}", style = PolyType.Numeric.copy(fontSize = 11.sp, color = wc))
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    w.phonemes.forEach { ph ->
                        val c = gradeColor(ph.grade)
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.18f)).border(1.dp, c, RoundedCornerShape(6.dp))
                                .clickable { onPhoneme(w, ph) }.padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text(ph.ipa.ifBlank { PhoneticArticulationDatabase.ipaFor(ph.arpabet) }, style = PolyType.Ipa.copy(fontSize = 12.sp, color = c))
                        }
                    }
                }
                if (w.expectedStressSyllable >= 0) {
                    Spacer(Modifier.height(4.dp))
                    StressPatternDots(
                        syllables = w.phonemes.count { PhoneticArticulationDatabase.isVowel(it.arpabet) },
                        expected = w.expectedStressSyllable,
                        detected = w.detectedStressSyllable,
                    )
                }
            }
        }
    }
}

/** Syllable dots: large = stressed. Expected in mint outline, detected filled (red if wrong). */
@Composable
fun StressPatternDots(syllables: Int, expected: Int, detected: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until syllables.coerceAtLeast(1)) {
            val isExpected = i == expected
            val isDetected = i == detected
            val d = if (isExpected) 12.dp else 7.dp
            val fill = when {
                isDetected && isExpected -> PolyColors.NeonMintSuccess
                isDetected -> PolyColors.CrimsonAlert
                else -> Color.Transparent
            }
            Box(Modifier.size(d).clip(CircleShape).background(fill).border(1.5.dp, if (isExpected) PolyColors.NeonMintSuccess else PolyColors.MutedSlateText, CircleShape))
        }
    }
}

/** Media timeline with A-B loop markers; tap to seek. */
@Composable
fun AbLoopTimeline(durationMs: Long, positionMs: Long, aMs: Long?, bMs: Long?, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier.fillMaxWidth().height(28.dp).pointerInput(durationMs) {
            detectTapGestures { off -> if (durationMs > 0) onSeek((off.x / size.width * durationMs).toLong()) }
        },
    ) {
        val mid = size.height / 2
        val d = durationMs.coerceAtLeast(1).toFloat()
        drawLine(PolyColors.ObsidianSlateHigh, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        if (aMs != null && bMs != null && bMs > aMs) {
            drawLine(PolyColors.AmberWarning.copy(alpha = 0.55f), Offset(aMs / d * size.width, mid), Offset(bMs / d * size.width, mid), strokeWidth = 10.dp.toPx())
        }
        drawLine(PolyColors.ElectricTeal, Offset(0f, mid), Offset(positionMs / d * size.width, mid), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        aMs?.let { drawLine(PolyColors.AmberWarning, Offset(it / d * size.width, 2f), Offset(it / d * size.width, size.height - 2f), strokeWidth = 3.dp.toPx()) }
        bMs?.let { drawLine(PolyColors.AmberWarning, Offset(it / d * size.width, 2f), Offset(it / d * size.width, size.height - 2f), strokeWidth = 3.dp.toPx()) }
        drawCircle(PolyColors.PureIceWhite, 7.dp.toPx(), Offset(positionMs / d * size.width, mid))
    }
}

/** Large animated countdown numeral (3-2-1) for the arena. */
@Composable
fun CountdownNumeral(value: Int, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (value > 0) 1f else 0.6f, PolyMotion.spring(), label = "cd")
    Box(modifier.size(160.dp).clip(CircleShape).background(PolyColors.ObsidianSlateHigh).border(2.dp, PolyColors.ElectricTeal, CircleShape), contentAlignment = Alignment.Center) {
        Text(if (value > 0) "$value" else "GO", style = PolyType.NumericLarge.copy(fontSize = 72.sp, color = PolyColors.ElectricTeal, fontWeight = FontWeight.ExtraBold), modifier = Modifier.scale(scale))
    }
}
