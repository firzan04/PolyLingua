package com.polylingua.edge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polylingua.edge.network.DiffKind
import com.polylingua.edge.network.DiffToken
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyMotion
import com.polylingua.edge.ui.theme.PolyShapes
import com.polylingua.edge.ui.theme.PolyType

/** Frosted glass card: #1E293B @ 85 % on true black, 1 dp #334155 border, 20 dp radius. */
@Composable
fun FrostedCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PolyShapes.Card)
    Column(
        modifier
            .clip(shape)
            .background(PolyColors.FrostedSurface)
            .border(BorderStroke(1.dp, accent?.copy(alpha = 0.55f) ?: PolyColors.FrostedBorder), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PolyType.Section)
            if (subtitle != null) Text(subtitle, style = PolyType.Caption)
        }
        trailing?.invoke(this)
    }
}

/** Compact status pill used in the telemetry bar and cards. */
@Composable
fun PillBadge(text: String, color: Color = PolyColors.ElectricTeal, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(PolyShapes.Pill))
            .background(if (filled) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(PolyShapes.Pill))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = PolyType.Pill.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = PolyColors.ElectricTeal,
) {
    val bg by animateColorAsState(if (enabled) color else PolyColors.FrostedBorder, label = "btn")
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = PolyColors.AmazeVoidBlack, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = PolyType.CardTitle.copy(color = PolyColors.AmazeVoidBlack), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = PolyColors.ElectricTeal,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, (if (enabled) color else PolyColors.FrostedBorder).copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) color else PolyColors.MutedSlateText
        if (icon != null) {
            Icon(icon, null, tint = c, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = PolyType.CardTitle.copy(color = c, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun IconCircleButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = PolyColors.PureIceWhite, background: Color = PolyColors.FrostedSurfaceSolid, size: Dp = 44.dp, contentDescription: String? = null) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).border(1.dp, PolyColors.FrostedBorder, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(size * 0.45f))
    }
}

/** Segmented tabs with a spring-animated selection. */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(PolyColors.ObsidianSlate).border(1.dp, PolyColors.FrostedBorder, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            val bg by animateColorAsState(if (active) PolyColors.ElectricTeal else Color.Transparent, label = "tab")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(bg).clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = PolyType.CardTitle.copy(fontSize = 13.sp, color = if (active) PolyColors.AmazeVoidBlack else PolyColors.MutedSlateText), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(options: List<String>, selected: Set<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier, color: Color = PolyColors.ElectricTeal) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val active = i in selected
            Box(
                Modifier.clip(RoundedCornerShape(PolyShapes.Chip))
                    .background(if (active) color.copy(alpha = 0.2f) else PolyColors.ObsidianSlate)
                    .border(1.dp, if (active) color else PolyColors.FrostedBorder, RoundedCornerShape(PolyShapes.Chip))
                    .clickable { onToggle(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(label, style = PolyType.Caption.copy(color = if (active) color else PolyColors.PureIceWhite, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/** Labelled metric tile with a tabular numeral. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = PolyColors.PureIceWhite, hint: String? = null) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(PolyColors.ObsidianSlate).border(1.dp, PolyColors.FrostedBorder, RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        Text(label.uppercase(), style = PolyType.Pill.copy(color = PolyColors.MutedSlateText))
        Spacer(Modifier.height(4.dp))
        Text(value, style = PolyType.NumericMedium.copy(color = color, fontSize = 20.sp), maxLines = 1)
        if (hint != null) Text(hint, style = PolyType.Caption, maxLines = 2)
    }
}

/** Horizontal bar 0..max with a spring-animated fill. */
@Composable
fun MeterBar(value: Float, max: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp, target: Float? = null) {
    val frac by animateFloatAsState((value / max).coerceIn(0f, 1f), PolyMotion.spring(), label = "meter")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(PolyColors.ObsidianSlateHigh)) {
        Box(Modifier.fillMaxWidth(frac).height(height).clip(RoundedCornerShape(height)).background(color))
        if (target != null) {
            val t = (target / max).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(t).height(height)) {
                Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(height).background(PolyColors.PureIceWhite.copy(alpha = 0.8f)))
            }
        }
    }
}

@Composable
fun LabeledProgress(label: String, fraction: Float, modifier: Modifier = Modifier, color: Color = PolyColors.ElectricTeal) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = PolyType.Caption, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${(fraction * 100).toInt()}%", style = PolyType.Numeric.copy(fontSize = 12.sp, color = color))
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)),
            color = color,
            trackColor = PolyColors.ObsidianSlateHigh,
        )
    }
}

/**
 * "🔒 ONLINE REQUIRED — NO INTERNET DETECTED" badge with the Indonesian explanation tooltip
 * (tap to toggle).
 */
@Composable
fun PadlockBadge(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(PolyShapes.Pill)).background(PolyColors.CrimsonAlert.copy(alpha = 0.15f))
                .border(1.dp, PolyColors.CrimsonAlert, RoundedCornerShape(PolyShapes.Pill)).clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔒 ONLINE REQUIRED — NO INTERNET DETECTED", style = PolyType.Pill.copy(color = PolyColors.CrimsonAlert))
            Spacer(Modifier.width(6.dp))
            Icon(PolyIcons.Info, "Info", tint = PolyColors.CrimsonAlert, modifier = Modifier.size(14.dp))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                PADLOCK_TOOLTIP,
                style = PolyType.Caption.copy(color = PolyColors.PureIceWhite),
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(PolyColors.ObsidianSlateHigh)
                    .border(1.dp, PolyColors.FrostedBorder, RoundedCornerShape(12.dp)).padding(10.dp),
            )
        }
    }
}

const val PADLOCK_TOOLTIP = "Fitur pertukaran bahasa dengan manusia asli di seluruh dunia membutuhkan koneksi internet (WebRTC P2P). " +
    "Saat luring, gunakan fitur Simulator Mitra Native AI Luring atau Mode Wi-Fi Direct Lokal di bawah ini secara gratis 100%."

@Composable
fun Banner(text: String, color: Color = PolyColors.AmberWarning, modifier: Modifier = Modifier, icon: ImageVector = PolyIcons.Warning, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = PolyType.Caption.copy(color = PolyColors.PureIceWhite), modifier = Modifier.weight(1f))
        action?.invoke(this)
    }
}

/** Red (removed) / green (added) word diff of a correction. */
@Composable
fun RedGreenDiffText(diff: List<DiffToken>, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        diff.forEachIndexed { i, t ->
            if (i > 0) append(' ')
            when (t.kind) {
                DiffKind.EQUAL -> withStyle(SpanStyle(color = PolyColors.PureIceWhite)) { append(t.text) }
                DiffKind.DELETE -> withStyle(SpanStyle(color = PolyColors.CrimsonAlert, textDecoration = TextDecoration.LineThrough)) { append(t.text) }
                DiffKind.INSERT -> withStyle(SpanStyle(color = PolyColors.NeonMintSuccess, fontWeight = FontWeight.SemiBold)) { append(t.text) }
            }
        }
    }
    Text(text, style = PolyType.Body, modifier = modifier)
}

@Composable
fun BulletList(items: List<String>, modifier: Modifier = Modifier, color: Color = PolyColors.ElectricTeal) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row {
                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(item, style = PolyType.Body.copy(fontSize = 13.sp))
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = PolyColors.MutedSlateText, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = PolyType.CardTitle)
        Spacer(Modifier.height(4.dp))
        Text(body, style = PolyType.BodyMuted.copy(fontSize = 13.sp))
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** Standard screen header with back arrow. */
@Composable
fun ScreenHeader(title: String, subtitle: String?, onBack: (() -> Unit)?, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconCircleButton(PolyIcons.Back, onBack, size = 38.dp, contentDescription = "Back")
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = PolyType.Section.copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = PolyType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp)

/** "HH:MM:SS" with tabular digits. */
fun formatHms(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

fun formatBand(b: Double): String = if (b % 1.0 == 0.0) "%.1f".format(b) else "%.1f".format(b)
