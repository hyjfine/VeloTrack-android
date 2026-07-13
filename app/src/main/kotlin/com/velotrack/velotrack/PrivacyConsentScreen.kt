package com.velotrack.velotrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.tabularTextStyle

@Composable
fun PrivacyConsentScreen(
    onAgree: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VeloColors.background)
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "隐私与定位说明",
            style = tabularTextStyle(28.sp, FontWeight.Bold, VeloColors.foreground),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "VeloTrack 需要精确定位来记录骑行轨迹，并会使用高德或 Google 地图 SDK 展示地图。轨迹保存在本机；只有你主动点击 AI 分析时，骑行汇总数据才会发送到 AI 服务。",
            color = VeloColors.foreground.copy(alpha = 0.68f),
            fontSize = 15.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onAgree,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VeloColors.foreground,
                contentColor = Color.White,
            ),
        ) {
            Text("同意并继续", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onExit, modifier = Modifier.padding(top = 8.dp)) {
            Text("不同意并退出", color = VeloColors.foreground.copy(alpha = 0.5f))
        }
    }
}
