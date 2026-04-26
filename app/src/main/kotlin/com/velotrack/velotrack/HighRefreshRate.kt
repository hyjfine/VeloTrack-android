package com.velotrack.velotrack

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display

fun Activity.enableAdaptiveHighRefreshRate() {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    } ?: return

    val supportedModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        display.supportedModes.toList()
    } else {
        emptyList()
    }
    val bestMode = supportedModes.maxWithOrNull(
        compareBy<Display.Mode> { it.refreshRate }
            .thenBy { it.physicalWidth * it.physicalHeight },
    )
    val targetRefreshRate = bestMode?.refreshRate ?: display.refreshRate
    val attrs = window.attributes
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bestMode != null) {
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
