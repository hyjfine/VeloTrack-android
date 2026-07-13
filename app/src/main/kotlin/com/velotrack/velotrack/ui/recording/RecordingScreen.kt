package com.velotrack.velotrack

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloDimens
import com.velotrack.velotrack.ui.rememberTapFeedback
import com.velotrack.velotrack.ui.tabularTextStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RecordingScreen(
    state: TrackUiState,
    provider: MapProvider,
    debugPermissions: LocationPermissionSnapshot = LocationPermissionSnapshot(),
    navBottom: androidx.compose.ui.unit.Dp,
    onStartRecording: () -> Unit,
    onCancelStartCountdown: () -> Unit,
    onTogglePause: () -> Unit,
    onStopRecording: () -> Unit,
    onBeginHold: () -> Unit,
    onEndHold: () -> Unit,
    onToggleDebugLog: () -> Unit = {},
    onSaveDebugLog: () -> Unit = {},
) {
    val bottomPad = (VeloDimens.gaugeBottom + navBottom.value).dp
    val deviceHeadingDeg = rememberDeviceHeadingDegrees(enabled = state.isRecording)
    Box(Modifier.fillMaxSize()) {
        MapPane(
            provider = provider,
            points = state.mapPoints,
            modifier = Modifier.fillMaxSize(),
            followLatestPosition = true,
            mapZoom = DEFAULT_RECORDING_MAP_ZOOM,
            polylineWidth = 7f,
            darkMode = true,
            centerLat = state.mapCenterLat,
            centerLng = state.mapCenterLng,
            showRouteHeadArrow = state.isRecording,
            routeHeadHeadingDeg = deviceHeadingDeg,
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
                    state.isRestoringRecording -> "RESTORING"
                    state.isSavingRide -> "SAVING"
                    state.recordingErrorMessage?.contains("保存失败") == true -> "SAVE FAILED"
                    state.recordingErrorMessage?.contains("未完成骑行") == true -> "RIDE RESTORED"
                    state.recordingErrorMessage?.contains("权限") == true -> "LOCATION NEEDED"
                    state.recordingErrorMessage != null -> "SERVICE ERROR"
                    state.locationPermissionDenied -> "LOCATION NEEDED"
                    state.startCountdownSeconds != null -> "READY"
                    state.isRecording && state.signalLost -> "SIGNAL LOST"
                    state.isRecording -> "TRACKING"
                    else -> "GPS IDLE"
                },
                value = formatDurationMs(state.elapsedMs),
                pulse = state.isRecording && !state.isPaused && !state.signalLost,
            )
            HudDistanceCard(
                distanceText = if (state.isRecording || state.livePoints.isNotEmpty()) {
                    formatDistanceMeters(state.liveDistanceM)
                } else {
                    "0.00"
                },
            )
        }

        if (BuildConfig.DEBUG) {
            DebugStatusPanel(
                state = state,
                provider = provider,
                permissions = debugPermissions,
                onToggleDebugLog = onToggleDebugLog,
                onSaveDebugLog = onSaveDebugLog,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 116.dp)
                    .padding(horizontal = VeloDimens.sidePadding.dp),
            )
        }

        MainGaugeCard(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = VeloDimens.sidePadding.dp)
                .padding(bottom = bottomPad),
            onStartRecording = onStartRecording,
            onCancelStartCountdown = onCancelStartCountdown,
            onTogglePause = onTogglePause,
            onStopRecording = onStopRecording,
            onBeginHold = onBeginHold,
            onEndHold = onEndHold,
        )
    }
}

@Composable
private fun DebugStatusPanel(
    state: TrackUiState,
    provider: MapProvider,
    permissions: LocationPermissionSnapshot,
    onToggleDebugLog: () -> Unit,
    onSaveDebugLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.68f)),
        border = BorderStroke(1.dp, VeloColors.accent.copy(alpha = 0.45f)),
        modifier = modifier.widthIn(max = 360.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "DEBUG GPS ▲" else "DEBUG GPS ▼  ${state.locationDebugMessage ?: "tap"}",
                    style = tabularTextStyle(10.sp, FontWeight.Bold, VeloColors.accent),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                DebugLine("provider", provider.displayName)
                DebugLine("recording", "${state.isRecording} paused=${state.isPaused}")
                DebugLine("countdown", state.startCountdownSeconds?.toString() ?: "-")
                DebugLine("permission", "any=${permissions.any} fine=${permissions.fine} coarse=${permissions.coarse}")
                DebugLine("center", "${formatDebugCoord(state.mapCenterLat)}, ${formatDebugCoord(state.mapCenterLng)}")
                DebugLine("points", "${state.livePoints.size} raw / ${state.displayPoints.size} display spikes=${state.liveSpikePointCount}")
                DebugLine("last loc", state.lastLocationAtMs?.let { "${formatLocationAgeMs(it)} ago" } ?: "none")
                DebugLine("accuracy", state.lastLocationAccuracyM?.let { "${it.toInt()}m" } ?: "unknown")
                DebugLine("track point", state.lastLocationCountedInTrack.toString())
                DebugLine("signalLost", state.signalLost.toString())
                DebugLine(
                    "signal pause",
                    "${state.trackPausedForSignal} bad=${state.consecutiveBadGpsCount}/2 good=${state.consecutiveGoodGpsCount}/3",
                )
                DebugLine(
                    "speed",
                    "ui=${formatSpeedKmh(state.currentSpeedMps)} " +
                        "drv=" + (state.lastDerivedSpeedMps?.let { formatSpeedKmh(it) } ?: "-") +
                        " raw=" + (state.lastRawSpeedMps?.let { formatSpeedKmh(it) } ?: "-"),
                )
                DebugLine("speed algo", "${state.lastSpeedMethod ?: "-"} · ${state.lastSpeedSource ?: "-"}")
                DebugLine(
                    "doppler",
                    "w=" + (state.lastDopplerWeight?.let { java.lang.String.format(java.util.Locale.US, "%.2f", it) } ?: "-") +
                        " sacc=" + (state.lastSpeedAccuracyMps?.let { java.lang.String.format(java.util.Locale.US, "%.2f", it) + "m/s" } ?: "-"),
                )
                DebugLine(
                    "segment",
                    "n=" + (state.lastSegmentCount?.toString() ?: "-") +
                        " dt=" + (state.lastSegmentDtMs?.let { if (it < 0) "-" else "${it}ms" } ?: "-"),
                )
                DebugLine(
                    "gnss",
                    state.gnss?.let {
                        "use=${it.inUse}/${it.visible} bds=${it.beidouInUse}/${it.beidouVisible} " +
                            "gps=${it.gpsInUse} glo=${it.glonassInUse} gal=${it.galileoInUse}"
                    } ?: "-",
                )
                DebugLine("reason", state.lastLocationDropReason ?: "-")
                DebugLine("event", state.locationDebugMessage ?: "-")
                Spacer(Modifier.height(8.dp))
                DebugLogControls(
                    recording = state.debugLogRecording,
                    lineCount = state.debugLogLineCount,
                    status = state.debugLogStatus,
                    onToggleRecording = onToggleDebugLog,
                    onSave = onSaveDebugLog,
                )
            }
        }
    }
}

@Composable
private fun DebugLogControls(
    recording: Boolean,
    lineCount: Int,
    status: String?,
    onToggleRecording: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DebugActionButton(
            label = if (recording) "Stop Log" else "Start Log",
            modifier = Modifier.weight(1f),
            onClick = onToggleRecording,
        )
        DebugActionButton(
            label = "Save Log",
            modifier = Modifier.weight(1f),
            onClick = onSave,
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = buildString {
            append(if (recording) "recording" else "idle")
            append(" · ")
            append(lineCount)
            append(" lines")
            status?.let {
                append('\n')
                append(it)
            }
        },
        style = tabularTextStyle(8.sp, FontWeight.Bold, VeloColors.gray400),
    )
}

@Composable
private fun DebugActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VeloColors.accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.accent),
        )
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.gray400),
            modifier = Modifier.width(82.dp),
        )
        Text(
            text = value,
            style = tabularTextStyle(9.sp, FontWeight.Bold, Color.White.copy(alpha = 0.88f)),
        )
    }
}

private fun formatDebugCoord(value: Double): String = String.format(java.util.Locale.US, "%.5f", value)

private fun formatLocationAgeMs(timestamp: Long): String {
    val ageSec = ((System.currentTimeMillis() - timestamp) / 1000).coerceAtLeast(0)
    return if (ageSec < 60) "${ageSec}s" else "${ageSec / 60}m${ageSec % 60}s"
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
    onCancelStartCountdown: () -> Unit,
    onTogglePause: () -> Unit,
    onStopRecording: () -> Unit,
    onBeginHold: () -> Unit,
    onEndHold: () -> Unit,
) {
    val countdownSeconds = state.startCountdownSeconds
    val isCountingDown = countdownSeconds != null
    val btnBg = when {
        isCountingDown -> VeloColors.accent
        !state.isRecording -> VeloColors.accent
        state.isPaused -> VeloColors.warn
        else -> VeloColors.foreground
    }
    val btnFg = when {
        isCountingDown -> VeloColors.foreground
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
                            style = tabularTextStyle(42.sp, FontWeight.Bold, VeloColors.foreground),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .width(78.dp)
                    .height(78.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(btnBg)
                        .semantics {
                            role = Role.Button
                            contentDescription = when {
                                state.isSavingRide -> "正在保存骑行"
                                isCountingDown -> "取消开始录制倒计时"
                                !state.isRecording -> "开始录制"
                                state.isPaused -> "继续录制，长按停止"
                                else -> "暂停录制，长按停止"
                            }
                        }
                        .pointerInput(
                            state.isRecording,
                            isCountingDown,
                            state.isSavingRide,
                            state.isRestoringRecording,
                        ) {
                            detectTapGestures(
                                onPress = {
                                    if (state.isSavingRide || state.isRestoringRecording) {
                                        return@detectTapGestures
                                    }
                                    if (isCountingDown) {
                                        tapFeedback()
                                        onCancelStartCountdown()
                                        return@detectTapGestures
                                    }
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
                    if (countdownSeconds != null) {
                        Text(
                            text = countdownSeconds.toString(),
                            style = tabularTextStyle(30.sp, FontWeight.Bold, btnFg),
                        )
                    } else {
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
                }
                if (state.isRecording) {
                    Text(
                        when {
                            state.isSavingRide -> "SAVING..."
                            state.recordingErrorMessage != null -> state.recordingErrorMessage
                            else -> "HOLD TO STOP"
                        },
                        style = tabularTextStyle(
                            if (state.recordingErrorMessage != null) 7.sp else 8.sp,
                            FontWeight.Bold,
                            if (state.recordingErrorMessage != null) VeloColors.danger else VeloColors.mutedText.copy(alpha = 0.3f),
                        ),
                        maxLines = 2,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 28.dp)
                            .widthIn(max = 150.dp),
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
