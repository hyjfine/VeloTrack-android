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
import androidx.compose.ui.unit.dp
import com.velotrack.velotrack.ui.VeloColors

@Composable
fun VeloMainScreen(
    state: TrackUiState,
    provider: MapProvider,
    debugPermissions: LocationPermissionSnapshot = LocationPermissionSnapshot(),
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
                    debugPermissions = debugPermissions,
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
