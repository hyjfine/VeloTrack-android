package com.velotrack.velotrack.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
    val gaugeBottom = 128
    val bottomNavReserve = 160
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
    fontWeight = fontWeight,
    fontSize = fontSize,
    color = color,
    letterSpacing = letterSpacing,
    fontFeatureSettings = TNUM,
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
@OptIn(ExperimentalMaterial3Api::class)
fun VeloTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        MaterialTheme(
            colorScheme = VeloLightScheme,
            content = content,
        )
    }
}
