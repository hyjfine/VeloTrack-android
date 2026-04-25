package com.velotrack.velotrack

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.velotrack.velotrack.ui.VeloColors
import kotlinx.coroutines.delay

@Composable
fun HoldProgressOverlay(isHolding: Boolean, holdVersion: Int, modifier: Modifier = Modifier) {
    var progress by remember(holdVersion) { mutableFloatStateOf(0f) }
    LaunchedEffect(isHolding, holdVersion) {
        if (!isHolding) {
            progress = 0f
            return@LaunchedEffect
        }
        val start = System.currentTimeMillis()
        while (isHolding && progress < 1f) {
            val elapsed = (System.currentTimeMillis() - start).coerceAtMost(1500L)
            progress = elapsed / 1500f
            delay(16)
        }
    }
    AnimatedVisibility(visible = isHolding, modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color.Black.copy(alpha = 0.1f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(VeloColors.warn),
            )
        }
    }
}
