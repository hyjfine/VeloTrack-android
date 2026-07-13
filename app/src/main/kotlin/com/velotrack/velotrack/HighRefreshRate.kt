package com.velotrack.velotrack

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs

fun Activity.enableAdaptiveHighRefreshRate() {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    } ?: return

    val supportedModes = display.supportedModes.toList()
    val currentMode = display.mode
    val sameResolutionModes = supportedModes.filter {
        it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
    }
    // 骑行记录优先续航：选择最接近 60Hz 的原生分辨率模式，不再强制 90/120Hz。
    val bestMode = sameResolutionModes.minByOrNull { abs(it.refreshRate - TARGET_REFRESH_RATE_HZ) }
    val targetRefreshRate = bestMode?.refreshRate ?: minOf(display.refreshRate, TARGET_REFRESH_RATE_HZ)
    val attrs = window.attributes
    if (bestMode != null) {
        attrs.preferredDisplayModeId = bestMode.modeId
    }
    attrs.preferredRefreshRate = targetRefreshRate
    window.attributes = attrs

    if (Build.VERSION.SDK_INT >= 35) {
        window.decorView.setRequestedFrameRate(targetRefreshRate)
    }

    Log.d(
        "VeloTrack",
        "High refresh: requested=${targetRefreshRate}Hz, modeId=${bestMode?.modeId ?: "default"}, " +
            "current=${display.refreshRate}Hz, modes=${supportedModes.joinToString { mode ->
                "${mode.modeId}:${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate}"
            }}",
    )
}

private const val TARGET_REFRESH_RATE_HZ = 60f
