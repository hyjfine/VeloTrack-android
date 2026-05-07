package com.velotrack.velotrack

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velotrack.velotrack.ui.VeloColors
import com.velotrack.velotrack.ui.VeloDimens
import com.velotrack.velotrack.ui.rememberTapFeedback
import com.velotrack.velotrack.ui.tapFeedbackClickable
import com.velotrack.velotrack.ui.tabularTextStyle
import java.util.Locale

@Composable
fun DetailScreen(
    ride: Ride,
    state: TrackUiState,
    provider: MapProvider,
    navBottom: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val scroll = rememberScrollState()
    var isMapTouching by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(VeloColors.background)
            .verticalScroll(scroll, enabled = !isMapTouching),
    ) {
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            MapPane(
                provider = provider,
                points = ride.points,
                modifier = Modifier.fillMaxSize(),
                followLatestPosition = false,
                mapZoom = 14f,
                polylineWidth = 5f,
                showEndpointMarkers = true,
                fitRouteBounds = true,
                onMapTouchingChanged = { touching ->
                    if (isMapTouching != touching) isMapTouching = touching
                },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.1f), VeloColors.background),
                        ),
                    ),
            )
            Surface(
                shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
                color = VeloColors.background,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(VeloDimens.radiusSm.dp))
                    .tapFeedbackClickable { onBack() },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "Back",
                        tint = VeloColors.gray400,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(180f),
                    )
                }
            }
        }

        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .offset(y = (-48).dp),
        ) {
            StatsGrid(ride)
            Spacer(Modifier.height(32.dp))
            PerformanceCard(ride)
            Spacer(Modifier.height(32.dp))
            AiCoachingCard(state, onAnalyze)
            Spacer(Modifier.height(VeloDimens.bottomNavReserve.dp + navBottom))
        }
    }
}

@Composable
private fun StatsGrid(ride: Ride) {
    val cells = listOf(
        "Total Distance" to formatDistanceMeters(ride.totalDistance),
        "Average Speed" to "${formatSpeedKmh(ride.avgSpeed)} kph",
        "Duration" to formatDurationMs((ride.endTime ?: 0L) - ride.startTime),
        "Max Speed" to "${formatSpeedKmh(ride.maxSpeed)} kph",
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { cell ->
                    StatCell(label = cell.first, value = cell.second)
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: String) {
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusLg.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.white),
        border = BorderStroke(1.dp, VeloColors.divider),
        modifier = Modifier
            .weight(1f)
            .shadow(2.dp, RoundedCornerShape(VeloDimens.radiusLg.dp)),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                label.uppercase(Locale.US),
                style = tabularTextStyle(9.sp, FontWeight.Bold, VeloColors.gray400, 2.sp),
            )
            Spacer(Modifier.height(6.dp))
            Text(value, style = tabularTextStyle(24.sp, FontWeight.Bold, VeloColors.foreground, (-0.2).sp))
        }
    }
}

@Composable
private fun PerformanceCard(ride: Ride) {
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusXl.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.white),
        border = BorderStroke(1.dp, VeloColors.divider),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(VeloDimens.radiusXl.dp)),
    ) {
        Column(Modifier.padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(VeloColors.foreground),
                )
                Text(
                    "PERFORMANCE",
                    style = tabularTextStyle(10.sp, FontWeight.Black, VeloColors.gray300, 2.5.sp),
                )
            }
            Spacer(Modifier.height(32.dp))
            PerformanceLineChart(
                speedsKmh = ride.points.map { (it.speedMps * 3.6).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(192.dp),
            )
        }
    }
}

@Composable
private fun PerformanceLineChart(speedsKmh: List<Float>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    if (speedsKmh.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No samples", color = VeloColors.mutedText, fontSize = 12.sp)
        }
    } else {
        val strokePx = with(density) { 3.dp.toPx() }
        val gridColor = Color(0xFFF0F0F0)
        Canvas(modifier) {
            val padL = 4f
            val padR = 8f
            val padT = 8f
            val padB = 8f
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            val maxV = (speedsKmh.maxOrNull() ?: 1f).coerceAtLeast(1f)
            val minV = 0f
            val steps = 4
            for (i in 0..steps) {
                val y = padT + h * i / steps
                drawLine(
                    color = gridColor,
                    start = Offset(padL, y),
                    end = Offset(padL + w, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                )
            }
            if (speedsKmh.size >= 2) {
                val path = Path()
                speedsKmh.forEachIndexed { i, v ->
                    val x = padL + w * i / (speedsKmh.size - 1)
                    val y = padT + h * (1f - (v - minV) / (maxV - minV).coerceAtLeast(1e-3f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = VeloColors.foreground,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun AiCoachingCard(state: TrackUiState, onAnalyze: () -> Unit) {
    val tapFeedback = rememberTapFeedback()
    Card(
        shape = RoundedCornerShape(VeloDimens.radiusXxl.dp),
        colors = CardDefaults.cardColors(containerColor = VeloColors.foreground),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(VeloDimens.radiusXxl.dp)),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.05f),
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 48.dp, y = (-24).dp)
                    .rotate(-12f),
            )
            Column(Modifier.padding(40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(VeloDimens.radiusSm.dp))
                            .background(VeloColors.accent)
                            .shadow(12.dp, RoundedCornerShape(VeloDimens.radiusSm.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Psychology, null, tint = VeloColors.foreground, modifier = Modifier.size(24.dp))
                    }
                    Column {
                        Text("AI Analysis", style = tabularTextStyle(18.sp, FontWeight.Bold, Color.White, (-0.2).sp))
                        Text(
                            "Coach Velo v3.0",
                            style = tabularTextStyle(9.sp, FontWeight.Black, VeloColors.accent.copy(alpha = 0.8f), 2.sp),
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                when {
                    !state.isAnalysing && state.aiAnalysis == null && state.errorMessage == null -> {
                        Button(
                            onClick = {
                                tapFeedback()
                                onAnalyze()
                            },
                            shape = RoundedCornerShape(VeloDimens.radiusSm.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VeloColors.white, contentColor = VeloColors.foreground),
                            modifier = Modifier.heightIn(min = 52.dp),
                        ) {
                            Text("ANALYZE MY PERFORMANCE", style = tabularTextStyle(12.sp, FontWeight.Bold, VeloColors.foreground, 1.5.sp))
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    state.isAnalysing -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                            Box(Modifier.fillMaxWidth(0.8f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                            Box(Modifier.fillMaxWidth(0.83f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)))
                        }
                    }
                    state.errorMessage != null -> {
                        Text(state.errorMessage, color = VeloColors.danger, fontSize = 16.sp)
                    }
                    else -> {
                        AiAnalysisMarkdown(state.aiAnalysis.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
private fun AiAnalysisMarkdown(markdown: String) {
    val blocks = remember(markdown) { parseAiMarkdown(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        blocks.forEach { block ->
            when (block) {
                is AiMarkdownBlock.Heading -> Text(
                    text = stripInlineMarkdown(block.text),
                    color = VeloColors.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 20.sp,
                )
                is AiMarkdownBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = block.marker,
                        color = VeloColors.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = inlineMarkdown(block.text),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                is AiMarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

private sealed interface AiMarkdownBlock {
    data class Heading(val text: String) : AiMarkdownBlock
    data class ListItem(val marker: String, val text: String) : AiMarkdownBlock
    data class Paragraph(val text: String) : AiMarkdownBlock
}

private fun parseAiMarkdown(markdown: String): List<AiMarkdownBlock> {
    val blocks = mutableListOf<AiMarkdownBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotBlank()) blocks += AiMarkdownBlock.Paragraph(text)
        paragraph.clear()
    }

    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isBlank() -> flushParagraph()
            line.startsWith("#") -> {
                flushParagraph()
                blocks += AiMarkdownBlock.Heading(line.trimStart('#').trim())
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flushParagraph()
                blocks += AiMarkdownBlock.ListItem("•", line.drop(2).trim())
            }
            line.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                flushParagraph()
                val markerEnd = line.indexOfFirst { it == '.' || it == ')' }
                blocks += AiMarkdownBlock.ListItem(
                    marker = line.substring(0, markerEnd + 1),
                    text = line.substring(markerEnd + 1).trim(),
                )
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flushParagraph()
    return blocks.ifEmpty { listOf(AiMarkdownBlock.Paragraph(markdown.trim())) }
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val start = text.indexOf("**", index)
        if (start < 0) {
            append(stripInlineMarkdown(text.substring(index)))
            break
        }
        append(stripInlineMarkdown(text.substring(index, start)))
        val end = text.indexOf("**", start + 2)
        if (end < 0) {
            append(stripInlineMarkdown(text.substring(start)))
            break
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Black, color = Color.White)) {
            append(stripInlineMarkdown(text.substring(start + 2, end)))
        }
        index = end + 2
    }
}

private fun stripInlineMarkdown(text: String): String = text
    .replace("**", "")
    .replace("__", "")
    .replace("`", "")
