package com.velotrack.velotrack

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloGlassSurface
import com.velotrack.velotrack.ui.tapFeedbackClickable
import com.velotrack.velotrack.ui.tabularTextStyle
import java.util.Locale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role

@Composable
fun BottomNavBar(
    view: AppView,
    navBottom: androidx.compose.ui.unit.Dp,
    onDash: () -> Unit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logActive = view == AppView.HISTORY || view == AppView.DETAIL
    VeloGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = navBottom + 10.dp),
        shape = RoundedCornerShape(30.dp),
        baseColor = VeloColors.surfaceDark,
        shadowElevation = 16.dp,
        showAccentGlow = false,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            val gap = 8.dp
            val itemWidth = (maxWidth - gap) / 2
            val indicatorOffset by animateDpAsState(
                targetValue = if (logActive) itemWidth + gap else 0.dp,
                animationSpec = tween(TAB_INDICATOR_MS, easing = FastOutSlowInEasing),
                label = "bottom_nav_indicator",
            )
            Box(
                Modifier
                    .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                    .width(itemWidth)
                    .height(48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(VeloColors.accent),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavItem(
                    label = "骑行",
                    selected = view == AppView.RECORDING,
                    modifier = Modifier.weight(1f),
                    icon = { color ->
                        Icon(
                            Icons.AutoMirrored.Outlined.ShowChart,
                            null,
                            tint = color,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = onDash,
                )
                NavItem(
                    label = "记录",
                    selected = logActive,
                    modifier = Modifier.weight(1f),
                    icon = { color ->
                        Icon(
                            Icons.Outlined.History,
                            null,
                            tint = color,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = onLog,
                )
            }
        }
    }
}

private const val TAB_INDICATOR_MS = 240

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) VeloColors.foreground else Color.White.copy(alpha = 0.48f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "nav_item_color",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .semantics { this.selected = selected; role = Role.Tab }
            .tapFeedbackClickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box {
            icon(contentColor)
        }
        Text(
            label.uppercase(Locale.US),
            style = tabularTextStyle(11.sp, FontWeight.Bold, contentColor, 1.2.sp),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
