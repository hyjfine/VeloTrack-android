package com.velotrack.velotrack

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloDimens
import com.velotrack.velotrack.ui.rememberTapFeedback
import com.velotrack.velotrack.ui.tapFeedbackClickable
import com.velotrack.velotrack.ui.tabularTextStyle

@Composable
fun DeleteRideModal(visible: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val tapFeedback = rememberTapFeedback()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(VeloColors.overlay)
                    .tapFeedbackClickable { onCancel() },
            )
            Card(
                shape = RoundedCornerShape(VeloDimens.radiusXl.dp),
                colors = CardDefaults.cardColors(containerColor = VeloColors.white),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = 360.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            ) {
                Column(
                    Modifier.padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(VeloDimens.radiusSm.dp))
                            .background(VeloColors.red50),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = VeloColors.danger, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Delete Ride?", style = tabularTextStyle(20.sp, FontWeight.Bold, VeloColors.foreground, (-0.3).sp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This action cannot be undone. Are you sure you want to remove this session?",
                        style = tabularTextStyle(14.sp, FontWeight.Normal, VeloColors.gray500),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = {
                            tapFeedback()
                            onConfirm()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VeloColors.foreground, contentColor = VeloColors.white),
                    ) {
                        Text("CONFIRM DELETE", style = tabularTextStyle(14.sp, FontWeight.Bold, VeloColors.white, 1.5.sp))
                    }
                    TextButton(
                        onClick = {
                            tapFeedback()
                            onCancel()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("CANCEL", style = tabularTextStyle(12.sp, FontWeight.Bold, VeloColors.gray400, 1.8.sp))
                    }
                }
            }
        }
    }
}
