package com.velotrack.velotrack

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloSystemBars

@Composable
fun VeloMainScreen(
    state: TrackUiState,
    provider: MapProvider,
    debugPermissions: LocationPermissionSnapshot = LocationPermissionSnapshot(),
    onStartRecording: () -> Unit,
    onCancelStartCountdown: () -> Unit,
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
    onToggleDebugLog: () -> Unit = {},
    onSaveDebugLog: () -> Unit = {},
) {
    VeloSystemBars()

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BackHandler(enabled = state.view == AppView.DETAIL) {
        onBackDetail()
    }
    Box(Modifier.fillMaxSize().background(VeloColors.background)) {
        val contentModifier = if (state.pendingDeleteRideId != null) {
            Modifier.fillMaxSize().blur(10.dp)
        } else {
            Modifier.fillMaxSize()
        }
        Box(
            contentModifier,
        ) {
            if (state.view == AppView.DETAIL && state.selectedRide != null) {
                DetailScreen(
                    ride = state.selectedRide,
                    isAnalysing = state.isAnalysing,
                    aiAnalysis = state.aiAnalysis,
                    analysisError = state.errorMessage,
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
                AnimatedVisibility(
                    visible = state.view == AppView.RECORDING,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                    label = "recording_visibility",
                ) {
                    Box(Modifier.offset { IntOffset(dashParallaxX.roundToPx(), 0) }) {
                        RecordingScreen(
                            state = state,
                            provider = provider,
                            debugPermissions = debugPermissions,
                            navBottom = navBottom,
                            onStartRecording = onStartRecording,
                            onCancelStartCountdown = onCancelStartCountdown,
                            onTogglePause = onTogglePause,
                            onStopRecording = onStopRecording,
                            onBeginHold = onBeginHold,
                            onEndHold = onEndHold,
                            onToggleDebugLog = onToggleDebugLog,
                            onSaveDebugLog = onSaveDebugLog,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = state.view == AppView.HISTORY,
                    modifier = Modifier.fillMaxSize(),
                    enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 8 } + fadeIn(tween(180)),
                    exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 8 } + fadeOut(tween(160)),
                    label = "history_overlay",
                ) {
                    HistoryScreen(
                        rides = state.history,
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
        }

        DeleteRideModal(
            visible = state.pendingDeleteRideId != null,
            isDeleting = state.isDeletingRide,
            errorMessage = state.deleteRideError,
            onConfirm = onConfirmDelete,
            onCancel = onCancelDelete,
        )
    }
}
