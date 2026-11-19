package com.byd.proximitylab.chart

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 把只读时间线构建成画布可直接消费的图表数据。
 *
 * 构建过程依次完成窗口裁剪、空窗断线、每条曲线的点数上限和标记相对位置。所有步骤只作用于展示数据，
 * 不修改输入，也不读取 Room、实时扫描或通知状态。
 */
object RssiChartBuilder {

    fun build(input: RssiChartInput): RssiChartData {
        val endEpochMillis = input.endEpochMillis
        val startEpochMillis = windowStart(endEpochMillis, input.window.durationMillis)
        val domain = input.domain ?: defaultDomain(input)

        var sourcePointCount = 0
        var displayedPointCount = 0
        var segmentCount = 0

        val seriesData = input.series.map { series ->
            sourcePointCount += series.points.size
            val ordered = series.points.sortedBy { it.atEpochMillis }
            val windowPoints = ordered.filter { point ->
                point.atEpochMillis in startEpochMillis..endEpochMillis
            }
            val rawSegments = splitSegments(windowPoints, input.gapThresholdMillis)
            val budgets = allocateBudgets(
                sizes = rawSegments.map { segment -> segment.size },
                totalBudget = input.maxDisplayPointsPerSeries,
            )
            val displaySegments = rawSegments.mapIndexed { index, segment ->
                RssiChartSegment(decimate(segment, budgets[index]))
            }.filter { segment -> segment.points.isNotEmpty() }

            segmentCount += displaySegments.size
            val displayedForSeries = displaySegments.sumOf { segment -> segment.points.size }
            displayedPointCount += displayedForSeries
            RssiChartSeriesData(
                id = series.id,
                label = series.label,
                kind = series.kind,
                segments = displaySegments,
                sourcePointCount = series.points.size,
                windowPointCount = windowPoints.size,
                displayedPointCount = displayedForSeries,
            )
        }

        val thresholds = input.thresholds.map { threshold ->
            RssiChartThresholdLine(
                label = threshold.label,
                rssiDbm = threshold.rssiDbm,
                kind = threshold.kind,
                yFraction = yFraction(domain, threshold.rssiDbm),
            )
        }

        val markers = input.markers
            .filter { marker -> marker.atEpochMillis in startEpochMillis..endEpochMillis }
            .sortedWith(compareBy({ marker -> marker.atEpochMillis }, { marker -> marker.id }))
            .map { marker ->
                RssiChartMarkerData(
                    id = marker.id,
                    label = marker.label,
                    atEpochMillis = marker.atEpochMillis,
                    kind = marker.kind,
                    rssiDbm = marker.rssiDbm,
                    xFraction = xFraction(startEpochMillis, endEpochMillis, marker.atEpochMillis),
                )
            }

        val stateMarkers = input.stateMarkers
            .filter { marker -> marker.atEpochMillis in startEpochMillis..endEpochMillis }
            .sortedBy { marker -> marker.atEpochMillis }
            .map { marker ->
                RssiChartStateMarkerData(
                    atEpochMillis = marker.atEpochMillis,
                    state = marker.state,
                    reason = marker.reason,
                    xFraction = xFraction(startEpochMillis, endEpochMillis, marker.atEpochMillis),
                )
            }

        return RssiChartData(
            window = input.window,
            windowStartEpochMillis = startEpochMillis,
            windowEndEpochMillis = endEpochMillis,
            domain = domain,
            series = seriesData,
            thresholds = thresholds,
            markers = markers,
            stateMarkers = stateMarkers,
            sourcePointCount = sourcePointCount,
            displayedPointCount = displayedPointCount,
            segmentCount = segmentCount,
        )
    }

    /**
     * 默认的 Y 轴范围。使用全部输入点和阈值计算并向外取整到 10 dBm，因此切换时间窗不会改变阈值高度。
     */
    fun defaultDomain(input: RssiChartInput): RssiChartDomain {
        val values = buildList {
            input.thresholds.forEach { threshold -> add(threshold.rssiDbm) }
            input.series.forEach { series ->
                series.points.forEach { point -> add(point.rssiDbm) }
            }
        }
        if (values.isEmpty()) {
            return RssiChartDomain(minDbm = -100.0, maxDbm = -40.0)
        }
        var min = floor((values.min() - DOMAIN_PADDING_DBM) / 10.0) * 10.0
        var max = ceil((values.max() + DOMAIN_PADDING_DBM) / 10.0) * 10.0
        min = min.coerceAtLeast(ProximityRssiBounds.MIN)
        max = max.coerceAtMost(ProximityRssiBounds.MAX)
        if (max - min < MIN_DOMAIN_SPAN_DBM) {
            val center = (max + min) / 2.0
            min = (center - MIN_DOMAIN_SPAN_DBM / 2.0)
                .coerceAtLeast(ProximityRssiBounds.MIN)
            max = (center + MIN_DOMAIN_SPAN_DBM / 2.0)
                .coerceAtMost(ProximityRssiBounds.MAX)
        }
        if (max <= min) {
            max = (min + MIN_DOMAIN_SPAN_DBM).coerceAtMost(ProximityRssiBounds.MAX)
            if (max <= min) {
                min = (max - MIN_DOMAIN_SPAN_DBM).coerceAtLeast(ProximityRssiBounds.MIN)
            }
        }
        return RssiChartDomain(minDbm = min, maxDbm = max)
    }

    /**
     * 超过 [gapThresholdMillis] 的相邻点视为空窗，曲线在该处断开而不是用直线跨越。
     */
    internal fun splitSegments(
        points: List<RssiChartPoint>,
        gapThresholdMillis: Long,
    ): List<List<RssiChartPoint>> {
        if (points.isEmpty()) {
            return emptyList()
        }
        val segments = mutableListOf<MutableList<RssiChartPoint>>()
        var current = mutableListOf(points.first())
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val point = points[index]
            val gap = point.atEpochMillis - previous.atEpochMillis
            if (gap > gapThresholdMillis) {
                segments += current
                current = mutableListOf(point)
            } else {
                current += point
            }
        }
        segments += current
        return segments
    }

    /**
     * 把每条曲线的展示点预算按窗口内点数比例分给断线后的分段，并至少给每个分段保留端点。
     */
    internal fun allocateBudgets(
        sizes: List<Int>,
        totalBudget: Int,
    ): IntArray {
        val count = sizes.size
        if (count == 0) {
            return IntArray(0)
        }
        val minimumPerSegment = when {
            totalBudget >= count * 2 -> 2
            totalBudget >= count -> 1
            else -> 0
        }
        val budget = IntArray(count) { index -> minOf(minimumPerSegment, sizes[index]) }
        var remaining = totalBudget - budget.sum()
        if (remaining <= 0) {
            return budget
        }

        val weights = IntArray(count) { index ->
            (sizes[index] - budget[index]).coerceAtLeast(0)
        }
        val weightSum = weights.sum()
        if (weightSum > 0) {
            var assigned = 0
            for (index in 0 until count) {
                val extra = (remaining.toLong() * weights[index] / weightSum).toInt()
                val capped = minOf(extra, weights[index])
                budget[index] += capped
                assigned += capped
            }
            remaining -= assigned
            var progressed = true
            while (remaining > 0 && progressed) {
                progressed = false
                for (index in 0 until count) {
                    if (remaining == 0) {
                        break
                    }
                    if (budget[index] < sizes[index]) {
                        budget[index] += 1
                        remaining -= 1
                        progressed = true
                    }
                }
            }
        }
        return budget
    }

    /**
     * 保持首尾与每个时间桶内的最小/最大 RSSI 峰值，避免密集样本被平均成一条失真的线。
     */
    internal fun decimate(
        points: List<RssiChartPoint>,
        budget: Int,
    ): List<RssiChartPoint> {
        if (budget <= 0) {
            return emptyList()
        }
        if (points.size <= budget) {
            return points
        }
        return when (budget) {
            1 -> listOf(points.first())
            2 -> listOf(points.first(), points.last())
            3 -> listOf(0, points.size / 2, points.lastIndex)
                .distinct()
                .map { index -> points[index] }

            else -> {
                val bucketCount = (budget / 4).coerceAtLeast(1)
                val selectedIndices = sortedSetOf<Int>()
                for (bucket in 0 until bucketCount) {
                    val from = (bucket.toLong() * points.size / bucketCount).toInt()
                    val toExclusive = ((bucket + 1L) * points.size / bucketCount).toInt()
                    if (toExclusive <= from) {
                        continue
                    }
                    selectedIndices += from
                    selectedIndices += toExclusive - 1
                    var minIndex = from
                    var maxIndex = from
                    for (index in from until toExclusive) {
                        if (points[index].rssiDbm < points[minIndex].rssiDbm) {
                            minIndex = index
                        }
                        if (points[index].rssiDbm > points[maxIndex].rssiDbm) {
                            maxIndex = index
                        }
                    }
                    selectedIndices += minIndex
                    selectedIndices += maxIndex
                }
                selectedIndices.map { index -> points[index] }
            }
        }
    }

    private fun windowStart(endEpochMillis: Long, durationMillis: Long): Long =
        if (endEpochMillis < Long.MIN_VALUE + durationMillis) {
            Long.MIN_VALUE
        } else {
            endEpochMillis - durationMillis
        }

    private fun xFraction(start: Long, end: Long, at: Long): Float {
        val span = end - start
        if (span <= 0L) {
            return 0f
        }
        val fraction = (at - start).toDouble() / span
        return fraction.coerceIn(0.0, 1.0).toFloat()
    }

    private fun yFraction(domain: RssiChartDomain, rssiDbm: Double): Float {
        val span = domain.maxDbm - domain.minDbm
        if (span <= 0.0) {
            return 0f
        }
        val fraction = (domain.maxDbm - rssiDbm) / span
        return fraction.coerceIn(0.0, 1.0).toFloat()
    }

    private const val DOMAIN_PADDING_DBM: Double = 15.0
    private const val MIN_DOMAIN_SPAN_DBM: Double = 20.0
}

/**
 * 与 03 判定相同的 RSSI 可报告范围，避免图表坐标超出 Android 扫描可提供的值。
 */
private object ProximityRssiBounds {
    const val MIN: Double = -127.0
    const val MAX: Double = 126.0
}
