package com.velotrack.velotrack

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloDimens
import com.velotrack.velotrack.ui.VeloGlassSurface
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
    isVisible: Boolean = true,
) {
    val bottomPad = (VeloDimens.gaugeBottom + navBottom.value).dp
    val deviceHeadingDeg = rememberDeviceHeadingDegrees(enabled = isVisible && state.isRecording)
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
            isActive = isVisible,
        )
        RecordingHud(
            state = state,
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = VeloDimens.hudTopExtra.dp)
                .padding(horizontal = VeloDimens.sidePadding.dp)
                .fillMaxWidth(),
        )
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
                    .padding(top = 148.dp)
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
private fun RecordingHud(state: TrackUiState, modifier: Modifier = Modifier) {
    val status = when {
        state.isRestoringRecording -> "正在恢复"
        state.isSavingRide -> "正在保存"
        state.recordingIssue == com.velotrack.velotrack.recording.RecordingIssue.STORAGE -> "存储异常"
        state.recordingIssue == com.velotrack.velotrack.recording.RecordingIssue.SAVE -> "保存失败"
        state.recordingIssue == com.velotrack.velotrack.recording.RecordingIssue.RECOVERED -> "已恢复记录"
        state.recordingIssue == com.velotrack.velotrack.recording.RecordingIssue.PERMISSION -> "需要定位权限"
        state.recordingErrorMessage != null -> "服务异常"
        state.locationPermissionDenied -> "需要定位权限"
        state.startCountdownSeconds != null -> "准备出发"
        state.isRecording && state.signalLost -> "GPS 信号较弱"
        state.isRecording && state.isPaused -> "已暂停"
        state.isRecording -> "正在记录"
        else -> "等待骑行"
    }
    val statusColor = when {
        state.recordingIssue == com.velotrack.velotrack.recording.RecordingIssue.RECOVERED -> VeloColors.warn
        state.recordingErrorMessage != null || state.locationPermissionDenied -> VeloColors.danger
        state.isPaused -> VeloColors.warn
        state.isRecording && !state.signalLost -> VeloColors.accent
        else -> VeloColors.mutedText
    }
    VeloGlassSurface(
        shape = RoundedCornerShape(22.dp),
        baseColor = VeloColors.surfaceDarkSoft,
        shadowElevation = 10.dp,
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status,
                        style = tabularTextStyle(11.sp, FontWeight.Bold, Color.White.copy(alpha = 0.86f), 0.5.sp),
                    )
                }
                Text(
                    formatDurationMs(state.elapsedMs),
                    style = tabularTextStyle(22.sp, FontWeight.Bold, Color.White, (-0.2).sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Box(Modifier.width(1.dp).height(36.dp).background(Color.White.copy(alpha = 0.10f)))
            Column(
                Modifier
                    .weight(0.72f)
                    .padding(start = 18.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "骑行距离",
                    style = tabularTextStyle(9.sp, FontWeight.Bold, Color.White.copy(alpha = 0.42f), 1.2.sp),
                )
                Text(
                    if (state.isRecording || state.livePoints.isNotEmpty()) {
                        formatDistanceMeters(state.liveDistanceM)
                    } else {
                        "0m"
                    },
                    style = tabularTextStyle(20.sp, FontWeight.Bold, Color.White),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun MainGaugeCard(
    state: TrackUiState,
    onStartRecording: () -> Unit,
    onCancelStartCountdown: () -> Unit,
    onTogglePause: () -> Unit,
    onStopRecording: () -> Unit,
    onBeginHold: () -> Unit,
    onEndHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val countdownSeconds = state.startCountdownSeconds
    val isCountingDown = countdownSeconds != null
    val btnBg = when {
        isCountingDown -> VeloColors.accent
        !state.isRecording -> VeloColors.accent
        state.isPaused -> VeloColors.warn
        else -> VeloColors.white
    }
    val btnFg = when {
        isCountingDown -> VeloColors.foreground
        !state.isRecording -> VeloColors.foreground
        state.isPaused -> Color.White
        else -> VeloColors.foreground
    }
    val actionVisual = when {
        countdownSeconds != null -> countdownSeconds
        !state.isRecording || state.isPaused -> ACTION_VISUAL_PLAY
        else -> ACTION_VISUAL_PAUSE
    }
    val actionDescription = when {
        state.isSavingRide -> "正在保存骑行"
        isCountingDown -> "取消开始录制倒计时"
        !state.isRecording -> "开始录制"
        state.isPaused -> "继续录制，长按停止"
        else -> "暂停录制，长按停止"
    }
    val footerMessage = when {
        state.isSavingRide -> GaugeFooterMessage("正在保存骑行记录…")
        state.recordingErrorMessage != null -> GaugeFooterMessage(state.recordingErrorMessage, isError = true)
        state.isRecording -> GaugeFooterMessage("轻触暂停  ·  长按 1.5 秒结束骑行")
        else -> null
    }
    val tapFeedback = rememberTapFeedback()
    val performPrimaryAction: () -> Boolean = {
        when {
            state.isSavingRide || state.isRestoringRecording -> false
            isCountingDown -> {
                tapFeedback()
                onCancelStartCountdown()
                true
            }
            !state.isRecording -> {
                tapFeedback()
                onStartRecording()
                true
            }
            else -> {
                tapFeedback()
                onTogglePause()
                true
            }
        }
    }
    val performStopAction: () -> Boolean = {
        if (state.isRecording && !state.isSavingRide && !state.isRestoringRecording) {
            tapFeedback()
            onStopRecording()
            true
        } else {
            false
        }
    }
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.surfaceDark),
        modifier = modifier
            .fillMaxWidth()
            .testTag(MAIN_GAUGE_TEST_TAG)
            .shadow(20.dp, RoundedCornerShape(30.dp)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = state.isPaused,
                        transitionSpec = {
                            (
                                fadeIn(tween(170, delayMillis = 30)) +
                                    scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.92f) +
                                    slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 }
                                ).togetherWith(
                                fadeOut(tween(120)) +
                                    scaleOut(tween(150), targetScale = 1.05f) +
                                    slideOutVertically(tween(150)) { -it / 2 },
                            )
                        },
                        label = "speed_state_label",
                    ) { paused ->
                        Text(
                            if (paused) "已暂停 · PAUSED" else "当前速度 · SPEED",
                            style = tabularTextStyle(
                                10.sp,
                                FontWeight.Bold,
                                if (paused) VeloColors.warn else Color.White.copy(alpha = 0.44f),
                                1.2.sp,
                            ),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            text = formatSpeedKmh(if (state.isPaused) 0.0 else state.currentSpeedMps),
                            style = tabularTextStyle(56.sp, FontWeight.Bold, Color.White, (-1.2).sp),
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            "km/h",
                            style = tabularTextStyle(11.sp, FontWeight.Bold, Color.White.copy(alpha = 0.42f), 0.5.sp),
                            modifier = Modifier.padding(start = 7.dp, bottom = 10.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .testTag(RECORD_ACTION_TEST_TAG)
                        .shadow(14.dp, CircleShape)
                        .clip(CircleShape)
                        .background(btnBg)
                        .semantics {
                            role = Role.Button
                            contentDescription = actionDescription
                            onClick(label = actionDescription) { performPrimaryAction() }
                            if (state.isRecording && !state.isSavingRide && !state.isRestoringRecording) {
                                onLongClick(label = "结束骑行") { performStopAction() }
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
                                        performPrimaryAction()
                                        return@detectTapGestures
                                    }
                                    if (!state.isRecording) {
                                        performPrimaryAction()
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
                                            performPrimaryAction()
                                        }
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = actionVisual,
                        transitionSpec = {
                            (
                                fadeIn(tween(160, delayMillis = 40)) +
                                    scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.68f) +
                                    slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 5 }
                                ).togetherWith(
                                fadeOut(tween(120)) +
                                    scaleOut(tween(150), targetScale = 1.16f) +
                                    slideOutHorizontally(tween(140)) { -it / 6 },
                            )
                        },
                        label = "record_action_visual",
                    ) { visual ->
                        if (visual >= 0) {
                            Text(
                                text = visual.toString(),
                                style = tabularTextStyle(32.sp, FontWeight.Bold, btnFg),
                            )
                        } else {
                            Icon(
                                painter = painterResource(
                                    if (visual == ACTION_VISUAL_PLAY) {
                                        R.drawable.ic_velo_play
                                    } else {
                                        R.drawable.ic_velo_pause
                                    },
                                ),
                                contentDescription = null,
                                tint = btnFg,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                Modifier.padding(vertical = 16.dp),
                color = Color.White.copy(alpha = 0.09f),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GaugeMetric(
                    "海拔",
                    (state.currentAltitude ?: 0.0).toInt().toString(),
                    unit = "m",
                    modifier = Modifier.weight(1f),
                )
                GaugeMetric(
                    "GPS 精度",
                    state.lastLocationAccuracyM?.toInt()?.toString() ?: "--",
                    unit = "m",
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                )
                GaugeMetric(
                    "状态",
                    when {
                        state.isSavingRide -> "保存中"
                        state.isPaused -> "暂停"
                        state.isRecording -> "记录中"
                        else -> "待机"
                    },
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    animateValue = true,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .testTag(GAUGE_FOOTER_TEST_TAG),
                contentAlignment = Alignment.BottomStart,
            ) {
                AnimatedContent(
                    targetState = footerMessage,
                    transitionSpec = {
                        (fadeIn(tween(170)) + slideInVertically(tween(210)) { it / 2 })
                            .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(150)) { -it / 2 })
                    },
                    contentAlignment = Alignment.BottomStart,
                    label = "gauge_footer_message",
                ) { message ->
                    if (message != null) {
                        Text(
                            message.text,
                            style = tabularTextStyle(
                                9.sp,
                                FontWeight.Bold,
                                if (message.isError) VeloColors.danger else Color.White.copy(alpha = 0.34f),
                                0.7.sp,
                            ),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

private const val ACTION_VISUAL_PLAY = -1
private const val ACTION_VISUAL_PAUSE = -2
internal const val RECORD_ACTION_TEST_TAG = "record_action_button"
internal const val MAIN_GAUGE_TEST_TAG = "main_gauge_card"
internal const val GAUGE_FOOTER_TEST_TAG = "gauge_footer"

private data class GaugeFooterMessage(val text: String, val isError: Boolean = false)

@Composable
private fun GaugeMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    animateValue: Boolean = false,
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(
            label,
            style = tabularTextStyle(9.sp, FontWeight.Bold, Color.White.copy(alpha = 0.34f), 1.sp),
        )
        if (animateValue) {
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (
                        fadeIn(tween(160, delayMillis = 25)) +
                            slideInVertically(tween(210, easing = FastOutSlowInEasing)) { it / 2 }
                        ).togetherWith(
                        fadeOut(tween(120)) +
                            slideOutVertically(tween(150)) { -it / 2 },
                    )
                },
                label = "gauge_metric_value",
                modifier = Modifier.padding(top = 5.dp),
            ) { animatedValue ->
                GaugeMetricValue(animatedValue, unit)
            }
        } else {
            GaugeMetricValue(value, unit, Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun GaugeMetricValue(value: String, unit: String?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        Text(value, style = tabularTextStyle(18.sp, FontWeight.Bold, Color.White))
        if (unit != null) {
            Text(
                unit,
                style = tabularTextStyle(9.sp, FontWeight.Bold, Color.White.copy(alpha = 0.4f)),
                modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
            )
        }
    }
}
