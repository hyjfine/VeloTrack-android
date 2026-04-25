package com.velotrack.velotrack

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloDimens
import com.velotrack.velotrack.ui.rememberTapFeedback
import com.velotrack.velotrack.ui.tapFeedbackClickable
import com.velotrack.velotrack.ui.tabularTextStyle
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VeloMainScreen(
    state: TrackUiState,
    provider: MapProvider,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopRecording: () -> Unit,
    onBeginHold: () -> Unit,
    onEndHold: () -> Unit,
    onSetView: (AppView) -> Unit,
    onOpenRide: (Ride) -> Unit,
    onRequestDelete: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onAnalyze: () -> Unit,
    onBackDetail: () -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BackHandler(enabled = state.view == AppView.DETAIL) {
        onBackDetail()
    }
    Box(Modifier.fillMaxSize().background(VeloColors.background)) {
        if (state.view == AppView.DETAIL && state.selectedRide != null) {
            DetailScreen(
                ride = state.selectedRide,
                state = state,
                provider = provider,
                navBottom = navBottom,
                onBack = onBackDetail,
                onAnalyze = onAnalyze,
            )
        } else {
            val dashParallaxX by animateDpAsState(
                targetValue = if (state.view == AppView.HISTORY) (-18).dp else 0.dp,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "dash_parallax_x",
            )
            Box(Modifier.offset(x = dashParallaxX)) {
                RecordingScreen(
                    state = state,
                    provider = provider,
                    navBottom = navBottom,
                    onStartRecording = onStartRecording,
                    onTogglePause = onTogglePause,
                    onStopRecording = onStopRecording,
                    onBeginHold = onBeginHold,
                    onEndHold = onEndHold,
                )
            }
            AnimatedVisibility(
                visible = state.view == AppView.HISTORY,
                modifier = Modifier.fillMaxSize(),
                enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 8 } + fadeIn(tween(180)),
                exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 8 } + fadeOut(tween(160)),
                label = "history_overlay",
            ) {
                HistoryScreen(
                    state = state,
                    navBottom = navBottom,
                    onOpenRide = onOpenRide,
                    onRequestDelete = onRequestDelete,
                )
            }
        }

        BottomNavBar(
            view = state.view,
            navBottom = navBottom,
            onDash = { onSetView(AppView.RECORDING) },
            onLog = { onSetView(AppView.HISTORY) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        HoldProgressOverlay(
            isHolding = state.isHolding,
            holdVersion = state.holdVersion,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

        DeleteRideModal(
            visible = state.pendingDeleteRideId != null,
            onConfirm = onConfirmDelete,
            onCancel = onCancelDelete,
        )
    }
}

@Composable
private fun HoldProgressOverlay(isHolding: Boolean, holdVersion: Int, modifier: Modifier = Modifier) {
    var progress by remember(holdVersion) { mutableFloatStateOf(0f) }
    LaunchedEffect(isHolding, holdVersion) {
        if (!isHolding) {
            progress = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (isHolding && progress < 1f) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtMost(1500L)
            progress = elapsed / 1500f
            delay(16)
        }
    }
    AnimatedVisibility(visible = isHolding, modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color.Black.copy(alpha = 0.1f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(VeloColors.warn),
            )
        }
    }
}

@Composable
private fun RecordingScreen(
    state: TrackUiState,
    provider: MapProvider,
    navBottom: androidx.compose.ui.unit.Dp,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopRecording: () -> Unit,
    onBeginHold: () -> Unit,
    onEndHold: () -> Unit,
) {
    val bottomPad = (VeloDimens.gaugeBottom + navBottom.value).dp
    Box(Modifier.fillMaxSize()) {
        MapPane(
            provider = provider,
            points = state.livePoints,
            modifier = Modifier.fillMaxSize(),
            followLatestPosition = true,
            mapZoom = 16f,
            polylineWidth = 7f,
            darkMode = true,
            centerLat = state.mapCenterLat,
            centerLng = state.mapCenterLng,
        )
        Row(
            Modifier
                .statusBarsPadding()
                .padding(top = VeloDimens.hudTopExtra.dp)
                .padding(horizontal = VeloDimens.sidePadding.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HudStatusCard(
                title = when {
                    state.isRecording && state.signalLost -> "SIGNAL LOST"
                    state.isRecording -> "TRACKING"
                    else -> "GPS IDLE"
                },
                value = formatDurationMs(state.elapsedMs),
                pulse = state.isRecording && !state.isPaused && !state.signalLost,
            )
            HudDistanceCard(
                distanceText = if (state.isRecording || state.livePoints.isNotEmpty()) {
                    formatDistanceMeters(distanceOf(state.livePoints))
                } else {
                    "0.00"
                },
            )
        }

        MainGaugeCard(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = VeloDimens.sidePadding.dp)
                .padding(bottom = bottomPad),
            onStartRecording = onStartRecording,
            onTogglePause = onTogglePause,
            onStopRecording = onStopRecording,
            onBeginHold = onBeginHold,
            onEndHold = onEndHold,
        )
    }
}

@Composable
private fun HudStatusCard(title: String, value: String, pulse: Boolean) {
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.hudWhite),
        border = BorderStroke(1.dp, VeloColors.divider),
        modifier = Modifier.shadow(8.dp, RoundedCornerShape(VeloDimens.radiusSm.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                title == "SIGNAL LOST" -> VeloColors.mutedText
                                pulse -> VeloColors.danger
                                else -> VeloColors.mutedText
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.foreground.copy(alpha = 0.4f)),
                )
            }
            Text(
                text = value,
                style = tabularTextStyle(24.sp, FontWeight.Bold, VeloColors.foreground),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HudDistanceCard(distanceText: String) {
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.hudWhite),
        modifier = Modifier.shadow(8.dp, RoundedCornerShape(VeloDimens.radiusSm.dp)),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.End) {
            Text(
                "DISTANCE",
                style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.foreground.copy(alpha = 0.4f)),
            )
            Text(
                distanceText,
                style = tabularTextStyle(20.sp, FontWeight.Bold, VeloColors.foreground),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MainGaugeCard(
    state: TrackUiState,
    modifier: Modifier = Modifier,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopRecording: () -> Unit,
    onBeginHold: () -> Unit,
    onEndHold: () -> Unit,
) {
    val btnBg = when {
        !state.isRecording -> VeloColors.accent
        state.isPaused -> VeloColors.warn
        else -> VeloColors.foreground
    }
    val btnFg = when {
        !state.isRecording -> VeloColors.foreground
        state.isPaused -> Color.White
        else -> VeloColors.accent
    }
    val tapFeedback = rememberTapFeedback()
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusXl.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.white),
        modifier = modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(VeloDimens.radiusXl.dp)),
        border = BorderStroke(1.dp, VeloColors.divider),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "SPEED",
                            style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.foreground.copy(alpha = 0.22f)),
                        )
                        Text(
                            "KPH",
                            style = tabularTextStyle(8.sp, FontWeight.Black, VeloColors.foreground.copy(alpha = 0.18f)),
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = formatSpeedKmh(if (state.isPaused) 0.0 else state.currentSpeedMps),
                            style = tabularTextStyle(36.sp, FontWeight.Bold, VeloColors.foreground),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .width(78.dp)
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(btnBg)
                        .pointerInput(state.isRecording) {
                            detectTapGestures(
                                onPress = {
                                    if (!state.isRecording) {
                                        tapFeedback()
                                        onStartRecording()
                                        return@detectTapGestures
                                    }
                                    onBeginHold()
                                    var longHoldReached = false
                                    coroutineScope {
                                        val job = launch {
                                            delay(1500)
                                            longHoldReached = true
                                            tapFeedback()
                                            onStopRecording()
                                        }
                                        val released = tryAwaitRelease()
                                        job.cancel()
                                        onEndHold()
                                        if (released && !longHoldReached) {
                                            tapFeedback()
                                            onTogglePause()
                                        }
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            !state.isRecording -> Icons.Filled.PlayArrow
                            state.isPaused -> Icons.Filled.PlayArrow
                            else -> Icons.Filled.Pause
                        },
                        contentDescription = null,
                        tint = btnFg,
                        modifier = Modifier.size(28.dp),
                    )
                }
                if (state.isRecording) {
                    Text(
                        "HOLD TO STOP",
                        style = tabularTextStyle(8.sp, FontWeight.Bold, VeloColors.mutedText.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 24.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "ALTITUDE",
                            style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.foreground.copy(alpha = 0.22f)),
                        )
                        Text(
                            "M",
                            style = tabularTextStyle(8.sp, FontWeight.Black, VeloColors.foreground.copy(alpha = 0.18f)),
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = (state.currentAltitude ?: 0.0).toInt().toString(),
                            style = tabularTextStyle(30.sp, FontWeight.Bold, VeloColors.foreground),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
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
    var visible by remember(ride.id) { androidx.compose.runtime.mutableStateOf(false) }
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

@Composable
private fun DetailScreen(
    ride: Ride,
    state: TrackUiState,
    provider: MapProvider,
    navBottom: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .background(VeloColors.background)
            .verticalScroll(scroll),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VeloColors.detailHeaderBg),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
                    color = VeloColors.background,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .clip(RoundedCornerShape(VeloDimens.radiusSm.dp))
                        .tapFeedbackClickable { onBack() },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "Back",
                            tint = VeloColors.gray400,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(180f),
                        )
                    }
                }
                Text(
                    "SESSION DETAILS",
                    style = tabularTextStyle(12.sp, FontWeight.Bold, VeloColors.foreground),
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                )
                Spacer(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp),
                )
            }
            HorizontalDivider(color = VeloColors.divider, thickness = 1.dp)
        }

        Box(Modifier.fillMaxWidth().height(320.dp)) {
            MapPane(
                provider = provider,
                points = ride.points,
                modifier = Modifier.fillMaxSize(),
                followLatestPosition = false,
                mapZoom = 14f,
                polylineWidth = 5f,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.1f), VeloColors.background),
                        ),
                    ),
            )
        }

        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .offset(y = (-48).dp),
        ) {
            StatsGrid(ride)
            Spacer(Modifier.height(32.dp))
            PerformanceCard(ride)
            Spacer(Modifier.height(32.dp))
            AiCoachingCard(state, onAnalyze)
            Spacer(Modifier.height(VeloDimens.bottomNavReserve.dp + navBottom))
        }
    }
}

@Composable
private fun StatsGrid(ride: Ride) {
    val cells = listOf(
        "Total Distance" to formatDistanceMeters(ride.totalDistance),
        "Average Speed" to "${formatSpeedKmh(ride.avgSpeed)} kph",
        "Moving Time" to formatDurationMs((ride.endTime ?: 0L) - ride.startTime),
        "Max Speed" to "${formatSpeedKmh(ride.maxSpeed)} kph",
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { cell ->
                    StatCell(label = cell.first, value = cell.second)
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusLg.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.white),
        border = BorderStroke(1.dp, VeloColors.divider),
        modifier = Modifier
            .weight(1f)
            .shadow(2.dp, RoundedCornerShape(VeloDimens.radiusLg.dp)),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                label.uppercase(Locale.US),
                style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.gray400, 2.sp),
            )
            Spacer(Modifier.height(6.dp))
            Text(value, style = tabularTextStyle(24.sp, FontWeight.Bold, VeloColors.foreground, (-0.2).sp))
        }
    }
}

@Composable
private fun PerformanceCard(ride: Ride) {
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusXl.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.white),
        border = BorderStroke(1.dp, VeloColors.divider),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(VeloDimens.radiusXl.dp)),
    ) {
        Column(Modifier.padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(VeloColors.foreground),
                )
                Text(
                    "PERFORMANCE",
                    style = tabularTextStyle(10.sp, FontWeight.Black, VeloColors.gray300, 2.5.sp),
                )
            }
            Spacer(Modifier.height(32.dp))
            PerformanceLineChart(
                speedsKmh = ride.points.map { (it.speedMps * 3.6).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(192.dp),
            )
        }
    }
}

@Composable
private fun PerformanceLineChart(speedsKmh: List<Float>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    if (speedsKmh.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No samples", color = VeloColors.mutedText, fontSize = 12.sp)
        }
    } else {
        val strokePx = with(density) { 3.dp.toPx() }
        val gridColor = Color(0xFFF0F0F0)
        Canvas(modifier) {
            val padL = 4f
            val padR = 8f
            val padT = 8f
            val padB = 8f
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            val maxV = (speedsKmh.maxOrNull() ?: 1f).coerceAtLeast(1f)
            val minV = 0f
            val steps = 4
            for (i in 0..steps) {
                val y = padT + h * i / steps
                drawLine(
                    color = gridColor,
                    start = Offset(padL, y),
                    end = Offset(padL + w, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                )
            }
            if (speedsKmh.size >= 2) {
                val path = Path()
                speedsKmh.forEachIndexed { i, v ->
                    val x = padL + w * i / (speedsKmh.size - 1)
                    val y = padT + h * (1f - (v - minV) / (maxV - minV).coerceAtLeast(1e-3f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = VeloColors.foreground,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun AiCoachingCard(state: TrackUiState, onAnalyze: () -> Unit) {
    val tapFeedback = rememberTapFeedback()
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusXxl.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.foreground),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(VeloDimens.radiusXxl.dp)),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.05f),
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 48.dp, y = (-24).dp)
                    .rotate(-12f),
            )
            Column(Modifier.padding(40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(VeloDimens.radiusSm.dp))
                            .background(VeloColors.accent)
                            .shadow(12.dp, RoundedCornerShape(VeloDimens.radiusSm.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Psychology, null, tint = VeloColors.foreground, modifier = Modifier.size(24.dp))
                    }
                    Column {
                        Text("AI Analysis", style = tabularTextStyle(18.sp, FontWeight.Bold, Color.White, (-0.2).sp))
                        Text(
                            "Coach Velo v3.0",
                            style = tabularTextStyle(9.sp, FontWeight.Black, VeloColors.accent.copy(alpha = 0.8f), 2.sp),
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                when {
                    !state.isAnalysing && state.aiAnalysis == null && state.errorMessage == null -> {
                        Button(
                            onClick = {
                                tapFeedback()
                                onAnalyze()
                            },
                            shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VeloColors.white, contentColor = VeloColors.foreground),
                            modifier = Modifier.heightIn(min = 52.dp),
                        ) {
                            Text("ANALYZE MY PERFORMANCE", style = tabularTextStyle(12.sp, FontWeight.Bold, VeloColors.foreground, 1.5.sp))
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    state.isAnalysing -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                            Box(Modifier.fillMaxWidth(0.8f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                            Box(Modifier.fillMaxWidth(0.83f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                        }
                    }
                    state.errorMessage != null -> {
                        Text(state.errorMessage, color = VeloColors.danger, fontSize = 16.sp)
                    }
                    else -> {
                        Text(
                            "\"${state.aiAnalysis}\"",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 20.sp,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 28.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavBar(
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

@Composable
private fun DeleteRideModal(visible: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val tapFeedback = rememberTapFeedback()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(VeloColors.overlay)
                    .tapFeedbackClickable { onCancel() },
            )
            Card(
                shape = RoundedCornerShape(VeloDimens.radiusXl.dp),
                colors = CardDefaults.cardColors(containerColor = VeloColors.white),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 360.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            ) {
                Column(
                    Modifier.padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(VeloDimens.radiusSm.dp))
                            .background(VeloColors.red50),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = VeloColors.danger, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Delete Ride?", style = tabularTextStyle(20.sp, FontWeight.Bold, VeloColors.foreground, (-0.3).sp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This action cannot be undone. Are you sure you want to remove this session?",
                        style = tabularTextStyle(14.sp, FontWeight.Normal, VeloColors.gray500),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = {
                            tapFeedback()
                            onConfirm()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VeloColors.foreground, contentColor = VeloColors.white),
                    ) {
                        Text("CONFIRM DELETE", style = tabularTextStyle(14.sp, FontWeight.Bold, VeloColors.white, 1.5.sp))
                    }
                    TextButton(
                        onClick = {
                            tapFeedback()
                            onCancel()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("CANCEL", style = tabularTextStyle(12.sp, FontWeight.Bold, VeloColors.gray400, 1.8.sp))
                    }
                }
            }
        }
    }
}

private fun distanceOf(points: List<GpsPoint>): Double =
    points.zipWithNext().sumOf { (a, b) -> GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng) }