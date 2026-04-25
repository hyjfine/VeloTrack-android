package com.velotrack.velotrack

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloDimens
import com.velotrack.velotrack.ui.tapFeedbackClickable
import com.velotrack.velotrack.ui.tabularTextStyle
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun HistoryScreen(
    state: TrackUiState,
    navBottom: androidx.compose.ui.unit.Dp,
    onOpenRide: (Ride) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(VeloColors.background)
            .statusBarsPadding()
            .padding(horizontal = VeloDimens.sidePadding.dp)
            .padding(top = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    "RIDE LOG",
                    style = tabularTextStyle(36.sp, FontWeight.Bold, VeloColors.foreground, (-0.5).sp),
                )
                Text(
                    "${state.history.size} TRIPS SAVED",
                    style = tabularTextStyle(10.sp, FontWeight.Bold, VeloColors.gray400),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box(
                Modifier
                    .width(48.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(VeloColors.foreground),
            )
        }
        Spacer(Modifier.height(48.dp))
        if (state.history.isEmpty()) {
            Card(
                shape = RoundedCornerShape(VeloDimens.radiusXl.dp),
                colors = CardDefaults.cardColors(containerColor = VeloColors.white),
                border = BorderStroke(1.dp, VeloColors.divider.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, RoundedCornerShape(VeloDimens.radiusXl.dp)),
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
                        tint = VeloColors.foreground.copy(alpha = 0.05f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "WAITING FOR YOUR FIRST RIDE",
                        style = tabularTextStyle(10.sp, FontWeight.Bold, VeloColors.foreground.copy(alpha = 0.3f), letterSpacing = 3.sp),
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
                itemsIndexed(state.history, key = { _, r -> r.id }) { index, ride ->
                    HistoryRideRow(
                        ride = ride,
                        index = index,
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
    index: Int,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var visible by remember(ride.id) { mutableStateOf(false) }
    LaunchedEffect(ride.id) {
        delay(index * 50L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { 20 } + fadeIn(tween(300)),
    ) {
        val cardInteraction = remember { MutableInteractionSource() }
        val cardPressed by cardInteraction.collectIsPressedAsState()
        val cardScale by animateFloatAsState(
            targetValue = if (cardPressed) 0.97f else 1f,
            animationSpec = tween(140, easing = FastOutSlowInEasing),
            label = "history_card_press_scale",
        )
        val iconBg by animateColorAsState(
            targetValue = if (cardPressed) VeloColors.foreground else VeloColors.background,
            animationSpec = tween(140, easing = FastOutSlowInEasing),
            label = "history_icon_bg",
        )
        val iconTint by animateColorAsState(
            targetValue = if (cardPressed) VeloColors.accent else VeloColors.gray300,
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
        Card(
            shape = RoundedCornerShape(VeloDimens.radiusLg.dp),
            colors = CardDefaults.cardColors(containerColor = VeloColors.white),
            modifier = Modifier
                .fillMaxWidth()
                .scale(cardScale)
                .shadow(2.dp, RoundedCornerShape(VeloDimens.radiusLg.dp))
                .tapFeedbackClickable(interactionSource = cardInteraction) { onOpen() },
            border = BorderStroke(1.dp, VeloColors.divider.copy(alpha = 0.5f)),
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
                            style = tabularTextStyle(18.sp, FontWeight.Bold, VeloColors.gray900, (-0.2).sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        titleParts.second?.let { date ->
                            Text(
                                date,
                                style = tabularTextStyle(18.sp, FontWeight.Bold, VeloColors.gray900, (-0.2).sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                formatDistanceMeters(ride.totalDistance).uppercase(Locale.US),
                                style = tabularTextStyle(10.sp, FontWeight.Bold, VeloColors.gray900.copy(alpha = 0.3f), 1.5.sp),
                            )
                            Text(
                                formatDurationMs((ride.endTime ?: 0L) - ride.startTime),
                                style = tabularTextStyle(10.sp, FontWeight.Bold, VeloColors.gray900.copy(alpha = 0.3f), 1.5.sp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        tint = VeloColors.gray300,
                        modifier = Modifier
                            .size(40.dp)
                            .tapFeedbackClickable { onDelete() }
                            .padding(10.dp),
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = VeloColors.gray300,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
