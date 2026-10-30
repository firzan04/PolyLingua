package com.polylingua.edge.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polylingua.edge.R

/** PolyLingua Edge AMOLED palette (true black keeps unlit OLED pixels off on the POCO M4 Pro). */
object PolyColors {
    val AmazeVoidBlack = Color(0xFF000000)
    val ObsidianSlate = Color(0xFF0B0F19)
    val ObsidianSlateHigh = Color(0xFF151C2C)
    val FrostedSurface = Color(0xD91E293B)        // #1E293B at 85 % alpha
    val FrostedSurfaceSolid = Color(0xFF1E293B)
    val FrostedBorder = Color(0xFF334155)
    val NeonMintSuccess = Color(0xFF00E396)
    val ElectricTeal = Color(0xFF2DD4BF)
    val AmberWarning = Color(0xFFFFB020)
    val CrimsonAlert = Color(0xFFFF003F)
    val PureIceWhite = Color(0xFFF8FAFC)
    val MutedSlateText = Color(0xFF94A3B8)
    val DeepIndigo = Color(0xFF6366F1)

    val ObsidianGradient: Brush get() = Brush.verticalGradient(listOf(ObsidianSlate, ObsidianSlateHigh))
    val TealMintGradient: Brush get() = Brush.horizontalGradient(listOf(ElectricTeal, NeonMintSuccess))

    /** Score colour: green >= 80, amber 50-79, red < 50 (GOP grading thresholds). */
    fun forScore(score: Double): Color = when {
        score >= 80 -> NeonMintSuccess
        score >= 50 -> AmberWarning
        else -> CrimsonAlert
    }

    /** Band colour on the IELTS 0-9 scale. */
    fun forBand(band: Double): Color = when {
        band >= 7.0 -> NeonMintSuccess
        band >= 5.5 -> ElectricTeal
        band >= 4.0 -> AmberWarning
        else -> CrimsonAlert
    }
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int): Font =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Bundled variable fonts (SIL Open Font License). */
object PolyFonts {
    val Jakarta: FontFamily = FontFamily(
        variable(R.font.plus_jakarta_sans, 600),
        variable(R.font.plus_jakarta_sans, 700),
        variable(R.font.plus_jakarta_sans, 800),
    )
    val Noto: FontFamily = FontFamily(
        variable(R.font.noto_sans, 400),
        variable(R.font.noto_sans, 500),
        variable(R.font.noto_sans, 600),
        variable(R.font.noto_sans, 700),
    )
    val Mono: FontFamily = FontFamily(
        variable(R.font.jetbrains_mono, 400),
        variable(R.font.jetbrains_mono, 600),
        variable(R.font.jetbrains_mono, 800),
    )
}

/** Extra text styles outside the Material type scale. */
object PolyType {
    val Hero = TextStyle(fontFamily = PolyFonts.Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, color = PolyColors.PureIceWhite)
    val Section = TextStyle(fontFamily = PolyFonts.Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp, color = PolyColors.PureIceWhite)
    val CardTitle = TextStyle(fontFamily = PolyFonts.Jakarta, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, color = PolyColors.PureIceWhite)
    val Body = TextStyle(fontFamily = PolyFonts.Noto, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = PolyColors.PureIceWhite)
    val BodyMuted = Body.copy(color = PolyColors.MutedSlateText)
    val Caption = TextStyle(fontFamily = PolyFonts.Noto, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, color = PolyColors.MutedSlateText)
    val Ipa = TextStyle(fontFamily = PolyFonts.Noto, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = PolyColors.ElectricTeal)
    val Pill = TextStyle(fontFamily = PolyFonts.Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.4.sp, color = PolyColors.PureIceWhite)

    /** Tabular numerals for clocks, timers, band scores and gauges. */
    val Numeric = TextStyle(fontFamily = PolyFonts.Mono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontFeatureSettings = "tnum", color = PolyColors.PureIceWhite)
    val NumericLarge = TextStyle(fontFamily = PolyFonts.Mono, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 48.sp, fontFeatureSettings = "tnum", color = PolyColors.PureIceWhite)
    val NumericMedium = TextStyle(fontFamily = PolyFonts.Mono, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, fontFeatureSettings = "tnum", color = PolyColors.PureIceWhite)
}

/** Shared motion: spring(LowBouncy, MediumLow) everywhere. */
object PolyMotion {
    fun <T> spring(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
}

object PolyShapes {
    val Card = 20.dp
    val Pill = 50.dp
    val Chip = 12.dp
}

private val PolyTypography = Typography(
    displayLarge = PolyType.Hero.copy(fontSize = 40.sp, lineHeight = 46.sp),
    displayMedium = PolyType.Hero.copy(fontSize = 34.sp, lineHeight = 40.sp),
    displaySmall = PolyType.Hero,
    headlineLarge = PolyType.Hero.copy(fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = PolyType.Section.copy(fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = PolyType.Section.copy(fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = PolyType.Section,
    titleMedium = PolyType.CardTitle,
    titleSmall = PolyType.CardTitle.copy(fontSize = 13.sp),
    bodyLarge = PolyType.Body.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = PolyType.Body,
    bodySmall = PolyType.Caption,
    labelLarge = PolyType.CardTitle.copy(fontSize = 14.sp),
    labelMedium = PolyType.Caption.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = PolyType.Pill,
)

private val PolyDarkScheme = darkColorScheme(
    primary = PolyColors.ElectricTeal,
    onPrimary = PolyColors.AmazeVoidBlack,
    primaryContainer = PolyColors.ObsidianSlateHigh,
    onPrimaryContainer = PolyColors.ElectricTeal,
    secondary = PolyColors.NeonMintSuccess,
    onSecondary = PolyColors.AmazeVoidBlack,
    tertiary = PolyColors.AmberWarning,
    onTertiary = PolyColors.AmazeVoidBlack,
    error = PolyColors.CrimsonAlert,
    onError = PolyColors.PureIceWhite,
    background = PolyColors.AmazeVoidBlack,
    onBackground = PolyColors.PureIceWhite,
    surface = PolyColors.ObsidianSlate,
    onSurface = PolyColors.PureIceWhite,
    surfaceVariant = PolyColors.FrostedSurfaceSolid,
    onSurfaceVariant = PolyColors.MutedSlateText,
    surfaceContainer = PolyColors.ObsidianSlate,
    surfaceContainerHigh = PolyColors.ObsidianSlateHigh,
    surfaceContainerHighest = PolyColors.FrostedSurfaceSolid,
    surfaceContainerLow = PolyColors.AmazeVoidBlack,
    surfaceContainerLowest = PolyColors.AmazeVoidBlack,
    outline = PolyColors.FrostedBorder,
    outlineVariant = PolyColors.FrostedBorder,
    scrim = PolyColors.AmazeVoidBlack,
)

/** Always dark: the AMOLED design has no light variant. */
@Composable
fun PolyLinguaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PolyDarkScheme, typography = PolyTypography, content = content)
}
