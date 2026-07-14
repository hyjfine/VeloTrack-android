package com.velotrack.velotrack

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloDimens
import com.velotrack.velotrack.ui.VeloGlassSurface
import com.velotrack.velotrack.ui.tapFeedbackClickable
import com.velotrack.velotrack.ui.tabularTextStyle
import java.util.Locale

@Composable
fun HistoryScreen(
    rides: List<Ride>,
    navBottom: androidx.compose.ui.unit.Dp,
    onOpenRide: (Ride) -> Unit,
    onRequestDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(VeloColors.mapBg)
            .statusBarsPadding()
            .padding(horizontal = VeloDimens.sidePadding.dp)
            .padding(top = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    "RIDE LOG",
                    style = tabularTextStyle(36.sp, FontWeight.Bold, Color.White, (-0.5).sp),
                )
                Text(
                    "${rides.size} TRIPS SAVED",
                    style = tabularTextStyle(10.sp, FontWeight.Bold, Color.White.copy(alpha = 0.42f), 1.4.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box(
                Modifier
                    .width(48.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(VeloColors.accent),
            )
        }
        Spacer(Modifier.height(36.dp))
        if (rides.isEmpty()) {
            VeloGlassSurface(
                shape = RoundedCornerShape(VeloDimens.radiusXl.dp),
                baseColor = VeloColors.surfaceDarkSoft,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 128.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Navigation,
                        contentDescription = null,
                        tint = VeloColors.accent.copy(alpha = 0.20f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "WAITING FOR YOUR FIRST RIDE",
                        style = tabularTextStyle(10.sp, FontWeight.Bold, Color.White.copy(alpha = 0.42f), letterSpacing = 3.sp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = VeloDimens.bottomNavReserve.dp + navBottom),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(rides, key = { it.id }) { ride ->
                    HistoryRideRow(
                        ride = ride,
                        onOpen = { onOpenRide(ride) },
                        onDelete = { onRequestDelete(ride.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRideRow(
    ride: Ride,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val cardInteraction = remember { MutableInteractionSource() }
    val cardPressed by cardInteraction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (cardPressed) 0.97f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "history_card_press_scale",
    )
    val iconBg by animateColorAsState(
        targetValue = if (cardPressed) VeloColors.accent else Color.White.copy(alpha = 0.07f),
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "history_icon_bg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (cardPressed) VeloColors.foreground else VeloColors.accent.copy(alpha = 0.78f),
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "history_icon_tint",
    )
    val titleParts = remember(ride.title) {
        val prefix = "Ride on "
        if (ride.title.startsWith(prefix)) {
            prefix.trimEnd() to ride.title.removePrefix(prefix)
        } else {
            ride.title to null
        }
    }
    val movingDurationMs = remember(ride.id, ride.movingDurationSec, ride.endTime) {
        val fromFilter = (ride.movingDurationSec * 1000).toLong()
        if (fromFilter > 0L) fromFilter else ((ride.endTime ?: 0L) - ride.startTime).coerceAtLeast(0L)
    }
    VeloGlassSurface(
        shape = RoundedCornerShape(VeloDimens.radiusLg.dp),
        baseColor = VeloColors.surfaceDarkSoft,
        shadowElevation = 8.dp,
        showAccentGlow = false,
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
            .tapFeedbackClickable(interactionSource = cardInteraction) { onOpen() },
    ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(VeloDimens.radiusMd.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.History, null, tint = iconTint, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            titleParts.first,
                            style = tabularTextStyle(18.sp, FontWeight.Bold, Color.White.copy(alpha = 0.94f), (-0.2).sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        titleParts.second?.let { date ->
                            Text(
                                date,
                                style = tabularTextStyle(18.sp, FontWeight.Bold, Color.White.copy(alpha = 0.94f), (-0.2).sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HistoryMetaText(formatDistanceMeters(ride.totalDistance).uppercase(Locale.US))
                            HistoryMetaText(formatDurationMs(movingDurationMs))
                        }
                    }
                }
                if (ride.maxSpeed > 0.0) {
                    Spacer(Modifier.width(12.dp))
                    HistoryMaxSpeed(speedMps = ride.maxSpeed)
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier
                            .size(40.dp)
                            .tapFeedbackClickable { onDelete() }
                            .padding(10.dp),
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.28f),
                        modifier = Modifier.size(20.dp),
                    )
                }
        }
    }
}

@Composable
private fun HistoryMetaText(text: String) {
    Text(
        text,
        style = tabularTextStyle(10.sp, FontWeight.Bold, Color.White.copy(alpha = 0.40f), 1.5.sp),
        maxLines = 1,
    )
}

@Composable
private fun HistoryMaxSpeed(speedMps: Double) {
    Column(
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = formatSpeedKmh(speedMps),
            style = tabularTextStyle(10.sp, FontWeight.Bold, Color.White.copy(alpha = 0.58f), 0.sp),
            maxLines = 1,
        )
        Text(
            text = "KPH",
            style = tabularTextStyle(8.sp, FontWeight.Bold, Color.White.copy(alpha = 0.30f), 1.2.sp),
            maxLines = 1,
        )
        Text(
            text = "MAX",
            style = tabularTextStyle(8.sp, FontWeight.Bold, VeloColors.accent.copy(alpha = 0.55f), 1.2.sp),
            maxLines = 1,
        )
    }
}
