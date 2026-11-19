package com.byd.proximitylab.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.byd.proximitylab.chart.RssiChartData
import com.byd.proximitylab.chart.RssiChartSummary
import com.byd.proximitylab.chart.RssiChartThresholdKind
import com.byd.proximitylab.chart.RssiSeriesKind
import com.byd.proximitylab.proximity.ProximityState
import java.util.Locale

/**
 * 07、08、10 共用的只读 RSSI 时间线图表。
 *
 * 输入是已经构建好的 [RssiChartData]，因此不依赖实时扫描、通知或 Compose 之外的资源。
 * 阈值、曲线和标记都按同一 `[windowStartEpochMillis, windowEndEpochMillis]` 映射到画布。
 */
@Composable
fun RssiChart(
    data: RssiChartData,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "rssi_chart",
) {
    val rawColor = MaterialTheme.colorScheme.onSurfaceVariant
    val filteredColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.inversePrimary,
    )
    val enterColor = MaterialTheme.colorScheme.tertiary
    val exitColor = MaterialTheme.colorScheme.error
    val markerColor = MaterialTheme.colorScheme.secondary
    val stateNearColor = MaterialTheme.colorScheme.primary
    val stateFarColor = MaterialTheme.colorScheme.error
    val stateUnknownColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val summary = RssiChartSummary.text(data)
    var filteredIndex = 0
    val seriesColors = data.series.map { series ->
        series.id to when (series.kind) {
            RssiSeriesKind.RAW -> rawColor
            RssiSeriesKind.FILTERED -> {
                val color = filteredColors[filteredIndex % filteredColors.size]
                filteredIndex += 1
                color
            }
        }
    }
    val seriesColorById = seriesColors.toMap()

    Column(
        modifier = modifier.testTag("${testTagPrefix}_section"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 07 展示原始/滤波两条；10 可添加多套配置的滤波曲线，这里按输入顺序给出可区分配色。
            data.series.forEach { series ->
                LegendText(
                    text = series.label,
                    color = seriesColorById.getValue(series.id),
                    testTag = "${testTagPrefix}_legend_${series.id}",
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            data.thresholds.forEach { threshold ->
                LegendText(
                    text = "${threshold.label} ${formatRssi(threshold.rssiDbm)} dBm",
                    color = when (threshold.kind) {
                        RssiChartThresholdKind.ENTER -> enterColor
                        RssiChartThresholdKind.EXIT -> exitColor
                        RssiChartThresholdKind.OTHER -> markerColor
                    },
                    testTag = "${testTagPrefix}_${threshold.kind.name.lowercase(Locale.ROOT)}_threshold",
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .testTag("${testTagPrefix}_canvas")
                .semantics { contentDescription = summary },
        ) {
            drawRect(color = surfaceColor.copy(alpha = 0.18f))
            for (step in 1..3) {
                val y = size.height * step / 4f
                drawLine(
                    color = surfaceColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }
            data.thresholds.forEach { threshold ->
                val y = size.height * threshold.yFraction
                drawLine(
                    color = when (threshold.kind) {
                        RssiChartThresholdKind.ENTER -> enterColor
                        RssiChartThresholdKind.EXIT -> exitColor
                        RssiChartThresholdKind.OTHER -> markerColor
                    },
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                )
            }
            data.series.forEach { series ->
                val color = seriesColorById.getValue(series.id)
                series.segments.forEach { segment ->
                    if (segment.points.size == 1) {
                        val point = segment.points.single()
                        drawCircle(
                            color = color,
                            radius = 3f,
                            center = Offset(
                                x = size.width * data.xFraction(point.atEpochMillis),
                                y = size.height * data.yFraction(point.rssiDbm),
                            ),
                        )
                    } else if (segment.points.size > 1) {
                        val path = Path()
                        segment.points.forEachIndexed { index, point ->
                            val x = size.width * data.xFraction(point.atEpochMillis)
                            val y = size.height * data.yFraction(point.rssiDbm)
                            if (index == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            data.markers.forEach { marker ->
                val x = size.width * marker.xFraction
                drawLine(
                    color = markerColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                drawCircle(
                    color = markerColor,
                    radius = 4f,
                    center = Offset(
                        x = x,
                        y = marker.rssiDbm?.let { rssi -> size.height * data.yFraction(rssi.toDouble()) }
                            ?: (size.height / 2f),
                    ),
                )
            }
            data.stateMarkers.forEach { marker ->
                val x = size.width * marker.xFraction
                drawLine(
                    color = when (marker.state) {
                        ProximityState.NEAR -> stateNearColor
                        ProximityState.FAR -> stateFarColor
                        ProximityState.UNKNOWN -> stateUnknownColor
                    },
                    start = Offset(x, size.height - 8f),
                    end = Offset(x, size.height),
                    strokeWidth = 3f,
                )
            }
        }

        Text(
            modifier = Modifier.testTag("${testTagPrefix}_summary"),
            text = summary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!data.hasData) {
            Text(
                modifier = Modifier.testTag("${testTagPrefix}_empty"),
                text = "当前时间窗内没有样本（空窗或尚未开始）。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (data.markers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                data.markers.forEach { marker ->
                    Text(
                        modifier = Modifier
                            .testTag("${testTagPrefix}_marker_${marker.id}")
                            .padding(vertical = 1.dp),
                        text = "${marker.label} · ${formatWindowOffset(data, marker.atEpochMillis)}" +
                            (marker.rssiDbm?.let { " · $it dBm" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendText(
    text: String,
    color: Color,
    testTag: String,
) {
    Text(
        modifier = Modifier.testTag(testTag),
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun formatRssi(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private fun formatWindowOffset(data: RssiChartData, atEpochMillis: Long): String {
    val offsetSeconds = (atEpochMillis - data.windowStartEpochMillis).coerceAtLeast(0L) / 1_000.0
    return String.format(Locale.ROOT, "窗口 +%.1f s", offsetSeconds)
}
