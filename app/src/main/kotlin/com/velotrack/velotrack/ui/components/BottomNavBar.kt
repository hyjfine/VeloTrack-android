package com.velotrack.velotrack

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloGlassSurface
import com.velotrack.velotrack.ui.tapFeedbackClickable
import com.velotrack.velotrack.ui.tabularTextStyle
import java.util.Locale

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
        shape = RoundedCornerShape(28.dp),
        baseColor = VeloColors.surfaceDark,
        shadowElevation = 16.dp,
        showAccentGlow = false,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    val itemBackground by animateColorAsState(
        targetValue = if (selected) VeloColors.accent else Color.Transparent,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "nav_item_background",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(itemBackground)
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
