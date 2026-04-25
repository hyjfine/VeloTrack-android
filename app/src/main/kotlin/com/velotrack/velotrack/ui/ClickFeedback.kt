package com.velotrack.velotrack.ui

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberTapFeedback(): () -> Unit {
    val context = LocalContext.current
    val appContext = context.applicationContext
    return remember(appContext) {
        {
            playTapSound(appContext)
            vibrateTap(appContext)
        }
    }
}

private fun playTapSound(context: Context) {
    runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
    }
}

private fun vibrateTap(context: Context) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return@runCatching
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}

fun Modifier.tapFeedbackClickable(
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val tapFeedback = rememberTapFeedback()
    clickable(
        enabled = enabled,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        indication = null,
    ) {
        tapFeedback()
        onClick()
    }
}
