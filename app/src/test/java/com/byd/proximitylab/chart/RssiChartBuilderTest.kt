package com.byd.proximitylab.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiChartBuilderTest {

    @Test
    fun windowSwitchOnlyChangesVisibleRangeAndNotSourceOrThresholds() {
        val input = RssiChartInput(
            series = listOf(
                series(
                    points = listOf(
                        point(50_000L, -85.0),
                        point(75_000L, -70.0),
                        point(80_000L, -60.0),
                        point(90_000L, -75.0),
                    ),
                ),
            ),
            thresholds = dualThresholds(),
            window = RssiChartWindow.THIRTY_SECONDS,
            endEpochMillis = 100_000L,
        )

        val thirty = RssiChartBuilder.build(input)
        val fiveMinutes = RssiChartBuilder.build(input.copy(window = RssiChartWindow.FIVE_MINUTES))

        assertEquals(4, thirty.rawSeries!!.sourcePointCount)
        assertEquals(4, fiveMinutes.rawSeries!!.sourcePointCount)
        assertEquals(3, thirty.rawSeries!!.windowPointCount)
        assertEquals(4, fiveMinutes.rawSeries!!.windowPointCount)
        assertEquals(3, thirty.rawSeries!!.displayedPointCount)
        assertEquals(4, fiveMinutes.rawSeries!!.displayedPointCount)

        assertEquals(70_000L, thirty.windowStartEpochMillis)
        assertEquals(100_000L, thirty.windowEndEpochMillis)
        assertEquals(-200_000L, fiveMinutes.windowStartEpochMillis)
        assertEquals(
            thirty.thresholds.first { it.kind == RssiChartThresholdKind.ENTER }.yFraction,
            fiveMinutes.thresholds.first { it.kind == RssiChartThresholdKind.ENTER }.yFraction,
            1e-6f,
        )
        assertEquals(
            thirty.thresholds.first { it.kind == RssiChartThresholdKind.EXIT }.yFraction,
            fiveMinutes.thresholds.first { it.kind == RssiChartThresholdKind.EXIT }.yFraction,
            1e-6f,
        )
        // 域由全部输入点和阈值决定：[max - threshold] / [max - min] = ( -40 + 65 ) / 60。
        assertEquals(25f / 60f, thirty.thresholds.first().yFraction, 1e-6f)
        assertEquals(40f / 60f, thirty.thresholds.last().yFraction, 1e-6f)
    }

    @Test
    fun markersAlignToTheirWindowTimesAfterSwitchingWindows() {
        val input = RssiChartInput(
            series = listOf(series(points = listOf(point(75_000L, -70.0)))),
            thresholds = dualThresholds(),
            markers = listOf(
                marker("early", 60_000L),
                marker("start", 70_000L),
                marker("middle", 80_000L),
                marker("end", 100_000L),
            ),
            stateMarkers = listOf(
                RssiChartStateMarkerInput(
                    atEpochMillis = 80_000L,
                    state = com.byd.proximitylab.proximity.ProximityState.NEAR,
                ),
            ),
            window = RssiChartWindow.THIRTY_SECONDS,
            endEpochMillis = 100_000L,
        )

        val thirty = RssiChartBuilder.build(input)
        assertEquals(listOf("start", "middle", "end"), thirty.markers.map { it.id })
        assertEquals(0f, thirty.markers.first().xFraction, 1e-6f)
        assertEquals(1f / 3f, thirty.markers[1].xFraction, 1e-6f)
        assertEquals(1f, thirty.markers.last().xFraction, 1e-6f)
        assertEquals(1f / 3f, thirty.stateMarkers.single().xFraction, 1e-6f)

        val oneMinute = RssiChartBuilder.build(input.copy(window = RssiChartWindow.ONE_MINUTE))
        assertEquals(40_000L, oneMinute.windowStartEpochMillis)
        assertEquals(listOf("early", "start", "middle", "end"), oneMinute.markers.map { it.id })
        assertEquals(1f / 3f, oneMinute.markers.first().xFraction, 1e-6f)
        assertEquals(2f / 3f, oneMinute.markers[2].xFraction, 1e-6f)
        assertEquals(1f, oneMinute.markers.last().xFraction, 1e-6f)
        assertEquals(2f / 3f, oneMinute.stateMarkers.single().xFraction, 1e-6f)
    }

    @Test
    fun markersAndPointsOutsideTheWindowAreOmitted() {
        val data = RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(
                    series(points = listOf(point(10_000L, -80.0), point(80_000L, -60.0))),
                ),
                thresholds = dualThresholds(),
                markers = listOf(marker("old", 10_000L), marker("recent", 80_000L)),
                window = RssiChartWindow.THIRTY_SECONDS,
                endEpochMillis = 100_000L,
            ),
        )

        assertEquals(1, data.rawSeries!!.windowPointCount)
        assertEquals(listOf("recent"), data.markers.map { it.id })
    }

    @Test
    fun gapBreaksSeriesIntoIndependentSegments() {
        val data = RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(
                    series(
                        points = listOf(
                            point(0L, -80.0),
                            point(1_000L, -79.0),
                            point(2_000L, -81.0),
                            point(10_000L, -70.0),
                            point(11_000L, -69.0),
                        ),
                    ),
                ),
                window = RssiChartWindow.THIRTY_SECONDS,
                endEpochMillis = 12_000L,
                gapThresholdMillis = 3_000L,
            ),
        )

        assertEquals(
            listOf(3, 2),
            data.rawSeries!!.segments.map { segment -> segment.points.size },
        )
        assertEquals(2, data.segmentCount)
        assertEquals(5, data.rawSeries!!.displayedPointCount)
        assertEquals(5, data.rawSeries!!.windowPointCount)
    }

    @Test
    fun denseSamplesAreDecimatedButSourceCountIsKept() {
        val points = (0 until 600).map { index ->
            point(atEpochMillis = index * 400L, rssiDbm = (-85 + index % 7).toDouble())
        }
        val data = RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(series(points = points)),
                thresholds = dualThresholds(),
                window = RssiChartWindow.FIVE_MINUTES,
                endEpochMillis = 240_000L,
                gapThresholdMillis = 5_000L,
                maxDisplayPointsPerSeries = 120,
            ),
        )

        val raw = data.rawSeries!!
        assertEquals(600, raw.sourcePointCount)
        assertEquals(600, raw.windowPointCount)
        assertTrue(raw.displayedPointCount in 4..120)
        assertTrue(raw.displayedPointCount < raw.sourcePointCount)
        val displayed = raw.segments.single().points
        assertEquals(0L, displayed.first().atEpochMillis)
        assertEquals(239_600L, displayed.last().atEpochMillis)
        assertTrue(displayed.map { it.atEpochMillis }.zipWithNext().all { (a, b) -> a < b })
        assertTrue(displayed.any { it.rssiDbm == -85.0 })
        assertTrue(displayed.any { it.rssiDbm == -79.0 })
        assertEquals(displayed.size, displayed.map { it.atEpochMillis }.distinct().size)
    }

    @Test
    fun decimationBudgetIsSharedAcrossSegmentsAndKeepsBothEnds() {
        val points = (0 until 150).map { index ->
            point(atEpochMillis = index * 1_000L, rssiDbm = -90.0 + index % 10)
        } + (0 until 150).map { index ->
            point(atEpochMillis = 160_000L + index * 1_000L, rssiDbm = -80.0 + index % 10)
        }
        val data = RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(series(points = points)),
                thresholds = dualThresholds(),
                window = RssiChartWindow.FIVE_MINUTES,
                endEpochMillis = 320_000L,
                gapThresholdMillis = 5_000L,
                maxDisplayPointsPerSeries = 120,
            ),
        )

        val raw = data.rawSeries!!
        assertEquals(2, raw.segments.size)
        assertTrue(raw.segments.all { segment -> segment.points.isNotEmpty() })
        assertTrue(raw.displayedPointCount <= 120)
        raw.segments.forEach { segment ->
            val sourceTimes = if (segment === raw.segments.first()) {
                points.take(150)
                    .filter { it.atEpochMillis in data.windowStartEpochMillis..data.windowEndEpochMillis }
                    .map { it.atEpochMillis }
            } else {
                points.drop(150)
                    .filter { it.atEpochMillis in data.windowStartEpochMillis..data.windowEndEpochMillis }
                    .map { it.atEpochMillis }
            }
            assertEquals(sourceTimes.first(), segment.points.first().atEpochMillis)
            assertEquals(sourceTimes.last(), segment.points.last().atEpochMillis)
        }
    }

    @Test
    fun emptyInputProducesEmptyChartAndKeepsThresholdLines() {
        val data = RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(RssiChartSeriesInput("raw", "原始 RSSI", RssiSeriesKind.RAW, emptyList())),
                thresholds = dualThresholds(),
                window = RssiChartWindow.THIRTY_SECONDS,
                endEpochMillis = 1_000L,
            ),
        )

        assertFalse(data.hasData)
        assertEquals(0, data.rawSeries!!.sourcePointCount)
        assertEquals(0, data.rawSeries!!.windowPointCount)
        assertTrue(data.rawSeries!!.segments.isEmpty())
        assertEquals(2, data.thresholds.size)
        assertTrue(data.thresholds.all { it.yFraction in 0f..1f })
        assertEquals(-29_000L, data.windowStartEpochMillis)
        assertTrue(RssiChartSummary.text(data).contains("窗口 30 秒"))
        assertTrue(RssiChartSummary.text(data).contains("进入 -65 dBm"))
    }

    @Test
    fun summaryReportsMarkersAndGapCounts() {
        val data = RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(
                    series(
                        points = listOf(point(0L, -80.0), point(1_000L, -79.0), point(10_000L, -70.0)),
                    ),
                ),
                thresholds = dualThresholds(),
                markers = listOf(marker("event", 1_000L)),
                window = RssiChartWindow.THIRTY_SECONDS,
                endEpochMillis = 10_000L,
                gapThresholdMillis = 2_000L,
            ),
        )

        val summary = RssiChartSummary.text(data)
        assertTrue(summary.contains("标记 1"))
        assertTrue(summary.contains("断线 1 处"))
        assertTrue(summary.contains("离开 -80 dBm"))
    }

    @Test
    fun summaryListsEverySeriesForMultiConfigReuse() {
        val data = RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(
                    RssiChartSeriesInput(
                        id = "raw",
                        label = "原始 RSSI",
                        kind = RssiSeriesKind.RAW,
                        points = listOf(point(1_000L, -80.0)),
                    ),
                    RssiChartSeriesInput(
                        id = "config-a",
                        label = "配置甲",
                        kind = RssiSeriesKind.FILTERED,
                        points = listOf(point(1_000L, -78.0)),
                    ),
                    RssiChartSeriesInput(
                        id = "config-b",
                        label = "配置乙",
                        kind = RssiSeriesKind.FILTERED,
                        points = listOf(point(1_000L, -76.0)),
                    ),
                ),
                window = RssiChartWindow.ONE_MINUTE,
                endEpochMillis = 1_000L,
            ),
        )

        val summary = RssiChartSummary.text(data)
        assertTrue(summary.contains("原始 RSSI 1/1 点"))
        assertTrue(summary.contains("配置甲 1/1 点"))
        assertTrue(summary.contains("配置乙 1/1 点"))
    }

    private fun series(points: List<RssiChartPoint>) = RssiChartSeriesInput(
        id = "raw",
        label = "原始 RSSI",
        kind = RssiSeriesKind.RAW,
        points = points,
    )

    private fun point(atEpochMillis: Long, rssiDbm: Double) = RssiChartPoint(
        atEpochMillis = atEpochMillis,
        rssiDbm = rssiDbm,
    )

    private fun marker(id: String, atEpochMillis: Long) = RssiChartMarkerInput(
        id = id,
        label = id,
        atEpochMillis = atEpochMillis,
    )

    private fun dualThresholds() = listOf(
        RssiChartThreshold(
            label = "进入阈值",
            rssiDbm = -65.0,
            kind = RssiChartThresholdKind.ENTER,
        ),
        RssiChartThreshold(
            label = "离开阈值",
            rssiDbm = -80.0,
            kind = RssiChartThresholdKind.EXIT,
        ),
    )
}
