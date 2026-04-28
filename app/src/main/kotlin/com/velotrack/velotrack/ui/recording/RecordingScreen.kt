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
            mapZoom = DEFAULT_RECORDING_MAP_ZOOM,
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

        if (BuildConfig.DEBUG) {
            DebugStatusPanel(
                state = state,
                provider = provider,
                permissions = debugPermissions,
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
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.68f)),
        border = BorderStroke(1.dp, VeloColors.accent.copy(alpha = 0.45f)),
        modifier = modifier
            .widthIn(max = 360.dp)
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = if (expanded) "DEBUG GPS ▲" else "DEBUG GPS ▼  ${state.locationDebugMessage ?: "tap"}",
                style = tabularTextStyle(10.sp, FontWeight.Bold, VeloColors.accent),
                maxLines = 1,
            )
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                DebugLine("provider", provider.displayName)
                DebugLine("recording", "${state.isRecording} paused=${state.isPaused}")
                DebugLine("permission", "any=${permissions.any} fine=${permissions.fine} coarse=${permissions.coarse}")
                DebugLine("center", "${formatDebugCoord(state.mapCenterLat)}, ${formatDebugCoord(state.mapCenterLng)}")
                DebugLine("points", state.livePoints.size.toString())
                DebugLine("last loc", state.lastLocationAtMs?.let { "${formatLocationAgeMs(it)} ago" } ?: "none")
                DebugLine("accuracy", state.lastLocationAccuracyM?.let { "${it.toInt()}m" } ?: "unknown")
                DebugLine("track point", state.lastLocationCountedInTrack.toString())
                DebugLine("signalLost", state.signalLost.toString())
                DebugLine("reason", state.lastLocationDropReason ?: "-")
                DebugLine("event", state.locationDebugMessage ?: "-")
            }
        }
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

private fun distanceOf(points: List<GpsPoint>): Double =
    points.zipWithNext().sumOf { (a, b) -> GeoUtils.haversineMeters(a.lat, a.lng, b.lat, b.lng) }
