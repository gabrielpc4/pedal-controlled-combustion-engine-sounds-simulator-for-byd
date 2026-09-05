package com.gabrielpc.enginesoundsimulator

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabrielpc.enginesoundsimulator.simulation.VirtualGearProfile
import java.util.Locale

@Composable
internal fun VirtualGearDistributionChart(
    gearCount: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val boundaries = remember(gearCount) {
        VirtualGearProfile.physicalBoundarySpeedsKmh(gearCount)
    }
    val boundaryLabels = remember(gearCount) {
        boundaries.map { speed ->
            String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
    }
    val ranges = remember(gearCount) {
        boundaryLabels.zipWithNext { low, high -> "$low – $high" }
    }
    val axisHeight = with(LocalDensity.current) { 70.sp.toDp() + 16.dp }
    val description = remember(gearCount) {
        ranges.mapIndexed { index, range -> "Gear ${index + 1}: $range km/h" }
            .joinToString(". ")
    }

    Column(
        modifier = modifier
            .background(Color(0xFF09151F), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF243846), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "SPEED BANDS BY GEAR",
                color = Color(0xFF9FEAF1),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("0–190 km/h", color = Color.White, fontSize = 11.sp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height((gearCount * 34).dp + axisHeight)
                .semantics { contentDescription = description }
                .drawWithCache {
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 10.sp.toPx()
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                        color = Color(0xFFB8CBD8).toArgb()
                    }
                    val axisPaint = Paint(labelPaint).apply {
                        textSize = 12.sp.toPx()
                    }
                    val gearPaint = Paint(labelPaint).apply {
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        color = Color.White.toArgb()
                    }
                    val rowHeight = 34.dp.toPx()
                    val barHeight = 18.dp.toPx()
                    val left = 30.dp.toPx()
                    val rangeWidth = ranges.maxOf { labelPaint.measureText(it) } + 14.dp.toPx()
                    val plotWidth = (size.width - left - rangeWidth).coerceAtLeast(1f)
                    val plotBottom = gearCount * rowHeight
                    val colors = List(gearCount) { index ->
                        Color.hsv(180f + index * 10f, 0.62f, 0.95f)
                    }
                    val fills = colors.map { color ->
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.45f), color),
                            startX = left,
                            endX = left + plotWidth,
                        )
                    }

                    onDrawBehind {
                        drawLine(
                            Color(0xFF526776), Offset(left, plotBottom),
                            Offset(left + plotWidth, plotBottom), strokeWidth = 1.dp.toPx(),
                        )
                        boundaries.forEachIndexed { index, speed ->
                            val x = left + plotWidth * (speed / boundaries.last()).toFloat()
                            val color = if (index == 0) Color(0xFFB8CBD8) else colors[index - 1]
                            drawLine(
                                color.copy(alpha = 0.22f), Offset(x, 0f), Offset(x, plotBottom),
                                strokeWidth = 1.dp.toPx(),
                            )
                            drawLine(
                                color, Offset(x, plotBottom), Offset(x, plotBottom + 5.dp.toPx()),
                                strokeWidth = 1.dp.toPx(),
                            )
                            val label = boundaryLabels[index]
                            axisPaint.color = color.toArgb()
                            val canvas = drawContext.canvas.nativeCanvas
                            canvas.save()
                            canvas.translate(x, plotBottom + 10.dp.toPx())
                            canvas.rotate(-90f)
                            canvas.drawText(
                                label, -axisPaint.measureText(label),
                                -(axisPaint.ascent() + axisPaint.descent()) / 2, axisPaint,
                            )
                            canvas.restore()
                        }
                        ranges.forEachIndexed { index, range ->
                            val centerY = index * rowHeight + rowHeight / 2
                            val baseline = centerY - (labelPaint.ascent() + labelPaint.descent()) / 2
                            val startX = left + plotWidth * (boundaries[index] / boundaries.last()).toFloat()
                            val endX = left + plotWidth * (boundaries[index + 1] / boundaries.last()).toFloat()
                            drawRoundRect(
                                Color.White.copy(alpha = 0.025f),
                                Offset(left, centerY - barHeight / 2), Size(plotWidth, barHeight),
                                CornerRadius(4.dp.toPx()),
                            )
                            drawRoundRect(
                                fills[index], Offset(startX, centerY - barHeight / 2),
                                Size(endX - startX, barHeight), CornerRadius(4.dp.toPx()),
                            )
                            drawLine(
                                colors[index], Offset(endX, centerY - barHeight / 2),
                                Offset(endX, centerY + barHeight / 2), strokeWidth = 2.dp.toPx(),
                            )
                            drawContext.canvas.nativeCanvas.drawText("${index + 1}", 0f, baseline, gearPaint)
                            drawContext.canvas.nativeCanvas.drawText(
                                range, left + plotWidth + 14.dp.toPx(), baseline, labelPaint,
                            )
                        }
                    }
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("GEAR", color = Color(0xFF8DA5B6), fontSize = 9.sp)
            Text("ROAD SPEED / km/h", color = Color(0xFF8DA5B6), fontSize = 9.sp)
        }
    }
}
