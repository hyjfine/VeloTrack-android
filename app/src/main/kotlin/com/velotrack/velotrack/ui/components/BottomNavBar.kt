package com.velotrack.velotrack

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 0.dp),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = Color.White.copy(alpha = 0.95f),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, VeloColors.divider),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp + navBottom),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                label = "Dash",
                selected = view == AppView.RECORDING,
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
                label = "Log",
                selected = logActive,
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
) {
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "nav_item_scale",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) VeloColors.foreground else VeloColors.gray300,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "nav_item_color",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .tapFeedbackClickable { onClick() }
            .padding(horizontal = 24.dp),
    ) {
        Box(Modifier.scale(selectionScale)) {
            icon(contentColor)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label.uppercase(Locale.US),
            style = tabularTextStyle(10.sp, FontWeight.Bold, contentColor, 2.sp),
        )
    }
}
