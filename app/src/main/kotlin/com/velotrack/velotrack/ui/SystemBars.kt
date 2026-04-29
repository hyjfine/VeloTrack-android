package com.velotrack.velotrack.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun VeloSystemBars() {
    val context = LocalContext.current
    val composeView = LocalView.current

    DisposableEffect(context, composeView) {
        val activity = context.findActivity()
        if (activity == null || composeView.isInEditMode) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(activity.window, composeView)

            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = true
            controller.hide(WindowInsetsCompat.Type.statusBars())

            onDispose {
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

