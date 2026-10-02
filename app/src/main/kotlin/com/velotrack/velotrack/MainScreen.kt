package com.velotrack.velotrack

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    onRetryHistory: () -> Unit = {},
    onToggleDebugLog: () -> Unit = {},
    onSaveDebugLog: () -> Unit = {},
) {
    VeloSystemBars()

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val detailVisible = state.view == AppView.DETAIL && state.selectedRide != null
    val liveDetailPresentation = if (detailVisible) {
        DetailPresentation(
            ride = state.selectedRide!!,
            isAnalysing = state.isAnalysing,
            aiAnalysis = state.aiAnalysis,
            analysisError = state.errorMessage,
        )
    } else {
        null
    }
    var cachedDetailPresentation by remember {
        mutableStateOf(liveDetailPresentation)
    }
    SideEffect {
        liveDetailPresentation?.let { cachedDetailPresentation = it }
    }
    val presentedDetail = liveDetailPresentation ?: cachedDetailPresentation
    val detailVisibility = remember { MutableTransitionState(detailVisible) }
    detailVisibility.targetState = detailVisible
    val detailLayerPresent = detailVisibility.currentState || detailVisibility.targetState
    BackHandler(enabled = detailLayerPresent) {
        if (detailVisibility.targetState) onBackDetail()
    }
    LaunchedEffect(detailVisibility.isIdle, detailVisibility.currentState, detailVisible) {
        if (detailVisibility.isIdle && !detailVisibility.currentState && !detailVisible) {
            cachedDetailPresentation = null
        }
    }
    val recordingVisible = state.view == AppView.RECORDING && !detailLayerPresent

    Box(Modifier.fillMaxSize().background(VeloColors.mapBg)) {
        Box(
            Modifier.fillMaxSize(),
        ) {
            if (!detailLayerPresent) {
                // 保持原生 MapView 稳定挂载；切换 Tab 时只动画轻量的历史页遮罩，
                // 避免移动/重建地图导致掉帧或短暂黑屏。
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
                    isVisible = recordingVisible,
                )
            }
            val historyVisible = state.view != AppView.RECORDING || detailLayerPresent
            val historyAlpha by animateFloatAsState(
                targetValue = if (historyVisible) 1f else 0f,
                animationSpec = tween(TAB_CONTENT_FADE_MS, easing = FastOutSlowInEasing),
                label = "history_alpha",
            )
            HistoryScreen(
                rides = state.history,
                errorMessage = state.historyError,
                isLoading = state.isHistoryLoading || state.isDetailLoading,
                onRetry = onRetryHistory,
                navBottom = navBottom,
                onOpenRide = onOpenRide,
                onRequestDelete = onRequestDelete,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (historyVisible) 1f else -1f)
                    .graphicsLayer { alpha = historyAlpha }
                    .then(
                        if (state.pendingDeleteRideId != null) Modifier.blur(10.dp)
                        else Modifier
                    )
                    .then(
                        if (historyVisible && !detailLayerPresent) Modifier
                        else Modifier.clearAndSetSemantics { }
                    ),
            )

            AnimatedVisibility(
                visibleState = detailVisibility,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1.5f)
                    .then(if (detailVisible) Modifier else Modifier.clearAndSetSemantics { }),
                enter = slideInHorizontally(
                    animationSpec = tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing),
                ) { it / 12 } + fadeIn(tween(180)),
                exit = slideOutHorizontally(
                    animationSpec = tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing),
                ) { -it / 12 } + fadeOut(tween(160)),
                label = "detail_transition",
            ) {
                presentedDetail?.let { detail ->
                    DetailScreen(
                        ride = detail.ride,
                        isAnalysing = detail.isAnalysing,
                        aiAnalysis = detail.aiAnalysis,
                        analysisError = detail.analysisError,
                        provider = provider,
                        navBottom = navBottom,
                        onBack = onBackDetail,
                        onAnalyze = onAnalyze,
                    )
                }
            }

            BottomNavBar(
                view = state.view,
                navBottom = navBottom,
                onDash = { onSetView(AppView.RECORDING) },
                onLog = { onSetView(AppView.HISTORY) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f),
            )

            HoldProgressOverlay(
                isHolding = state.isHolding,
                holdVersion = state.holdVersion,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(3f)
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

private const val TAB_CONTENT_FADE_MS = 150
private const val DETAIL_TRANSITION_MS = 260

private data class DetailPresentation(
    val ride: Ride,
    val isAnalysing: Boolean,
    val aiAnalysis: String?,
    val analysisError: String?,
)
