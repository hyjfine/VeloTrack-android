package com.velotrack.velotrack.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 与 `docs/design-tokens.json` / VeloTrack-h5 一致。 */
@Immutable
object VeloColors {
    val background = Color(0xFFF4F4F7)
    val foreground = Color(0xFF1A1A1A)
    val accent = Color(0xFFE2FF3B)
    val accentGlow = Color(0x4DE2FF3B)
    val warn = Color(0xFFF97316)
    val danger = Color(0xFFEF4444)
    val mapBg = Color(0xFF151619)
    val surfaceDark = Color(0xF21A1C1F)
    val surfaceDarkSoft = Color(0xE6222529)
    val surfaceElevated = Color(0xFFFAFAFC)
    val polyline = Color(0xFFE2FF3B)
    val mutedText = Color(0xFFB4B4BA)
    val divider = Color(0xFFF0F0F2)
    val white = Color(0xFFFFFFFF)
    val overlay = Color(0x66000000)
    val gray300 = Color(0xFFD1D5DB)
    val gray400 = Color(0xFF9CA3AF)
    val gray500 = Color(0xFF6B7280)
    val gray900 = Color(0xFF111827)
    val red50 = Color(0xFFFEF2F2)
    val hudWhite = Color(0xF2FFFFFF)
    val detailHeaderBg = Color(0xCCFFFFFF)
}

object VeloDimens {
    val radiusSm = 16
    val radiusMd = 24
    val radiusLg = 32
    val radiusXl = 40
    val radiusXxl = 48
    val sidePadding = 24
    val gaugeBottom = 112
    val bottomNavReserve = 112
    val hudTopExtra = 48
}

private const val TNUM = "tnum"

/** 数字等宽，对齐 h5 `tabular-nums`。 */
fun tabularTextStyle(
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = VeloColors.foreground,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = fontWeight,
    fontSize = fontSize,
    color = color,
    letterSpacing = letterSpacing,
    fontFeatureSettings = TNUM,
)

private val VeloTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    ),
)

private val VeloLightScheme = lightColorScheme(
    primary = VeloColors.foreground,
    onPrimary = VeloColors.white,
    background = VeloColors.background,
    onBackground = VeloColors.foreground,
    surface = VeloColors.white,
    onSurface = VeloColors.foreground,
    outline = VeloColors.divider,
)

@Composable
fun VeloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VeloLightScheme,
        typography = VeloTypography,
        content = content,
    )
}
