package com.velotrack.velotrack.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 可降级的玻璃材质。
 *
 * 实时地图使用独立渲染表面，无法稳定做跨 SDK 的背景采样。因此这里使用半透明渐变、
 * 高光描边和径向渐变柔光模拟毛玻璃，不创建 RenderEffect 图层。
 */
@Composable
fun VeloGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    baseColor: Color = VeloColors.surfaceDarkSoft,
    shadowElevation: Dp = 12.dp,
    showAccentGlow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
        shadowElevation = shadowElevation,
        tonalElevation = 0.dp,
    ) {
        Box(
            Modifier
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to baseColor.copy(alpha = 0.90f),
                        0.52f to baseColor.copy(alpha = 0.80f),
                        1f to baseColor.copy(alpha = 0.92f),
                    ),
                ),
        ) {
            if (showAccentGlow) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawWithCache {
                            val glow = Brush.radialGradient(
                                colors = listOf(
                                    VeloColors.accent.copy(alpha = 0.085f),
                                    VeloColors.accent.copy(alpha = 0.025f),
                                    Color.Transparent,
                                ),
                                center = Offset(size.width * 0.92f, 0f),
                                radius = 96.dp.toPx(),
                            )
                            onDrawBehind { drawRect(brush = glow) }
                        },
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.055f),
                            0.30f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.045f),
                        ),
                    ),
            )
            content()
        }
    }
}
