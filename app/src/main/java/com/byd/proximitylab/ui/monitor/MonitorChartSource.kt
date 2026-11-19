package com.byd.proximitylab.ui.monitor

import com.byd.proximitylab.chart.RssiChartBuilder
import com.byd.proximitylab.chart.RssiChartData
import com.byd.proximitylab.chart.RssiChartInput
import com.byd.proximitylab.chart.RssiChartMarkerInput
import com.byd.proximitylab.chart.RssiChartMarkerKind
import com.byd.proximitylab.chart.RssiChartPoint
import com.byd.proximitylab.chart.RssiChartSeriesInput
import com.byd.proximitylab.chart.RssiChartStateMarkerInput
import com.byd.proximitylab.chart.RssiChartThreshold
import com.byd.proximitylab.chart.RssiChartThresholdKind
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.chart.RssiSeriesKind
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.live.LiveReadingPoint
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason

enum class MonitorChartMode {
    MONITOR,
    SIMULATION_TEST,
}

/**
 * 实时监控图表的只读展示状态。切换窗口只重建该状态，不写回控制器或 Session。
 */
data class MonitorChartState(
    val window: RssiChartWindow,
    val mode: MonitorChartMode,
    val data: RssiChartData,
) {
    val sourceSampleCount: Int
        get() = data.rawSeries?.sourcePointCount ?: 0

    val windowSampleCount: Int
        get() = data.rawSeries?.windowPointCount ?: 0

    val displayedSampleCount: Int
        get() = data.rawSeries?.displayedPointCount ?: 0
}

/**
 * 把 05 的实时展示状态映射为图表输入。模拟测试运行中展示测试曲线和事件标记；
 * 否则展示普通监控曲线。普通监控不产生模拟事件。
 */
object MonitorChartSource {

    /** 每条曲线的展示点上限；原始 Room 时间线不受影响。 */
    const val MAX_DISPLAY_POINTS: Int = 120

    fun build(
        state: LiveMonitorState,
        window: RssiChartWindow,
    ): MonitorChartState {
        val test = state.test?.takeIf { it.running }
        val mode = if (test != null) MonitorChartMode.SIMULATION_TEST else MonitorChartMode.MONITOR
        val samples = test?.samples ?: state.monitorSamples
        val decision = test?.decision ?: state.monitor
        val events = test?.events.orEmpty()
        val endEpochMillis = maxOf(
            samples.lastOrNull()?.atEpochMillis ?: 0L,
            decision.lastDecisionAtEpochMillis,
            events.maxOfOrNull { event -> event.atEpochMillis } ?: 0L,
        )
        val gapThresholdMillis = decision.sampleValidityMillis.coerceAtLeast(1L)
        val rawSeries = RssiChartSeriesInput(
            id = "raw",
            label = "原始 RSSI",
            kind = RssiSeriesKind.RAW,
            points = samples.map { sample ->
                RssiChartPoint(
                    atEpochMillis = sample.atEpochMillis,
                    rssiDbm = sample.rawRssiDbm.toDouble(),
                )
            },
        )
        val filteredSeries = RssiChartSeriesInput(
            id = "filtered",
            label = "滤波 RSSI",
            kind = RssiSeriesKind.FILTERED,
            points = samples.map { sample ->
                RssiChartPoint(
                    atEpochMillis = sample.atEpochMillis,
                    rssiDbm = sample.filteredRssiDbm,
                )
            },
        )
        val thresholds = listOf(
            RssiChartThreshold(
                label = "进入阈值",
                rssiDbm = decision.enterThresholdDbm.toDouble(),
                kind = RssiChartThresholdKind.ENTER,
            ),
            RssiChartThreshold(
                label = "离开阈值",
                rssiDbm = decision.exitThresholdDbm.toDouble(),
                kind = RssiChartThresholdKind.EXIT,
            ),
        )
        val markers = events.map { event ->
            RssiChartMarkerInput(
                id = event.eventId,
                label = "${event.type.name} · ${event.reason.name}",
                atEpochMillis = event.atEpochMillis,
                kind = RssiChartMarkerKind.EVENT,
                rssiDbm = event.rawRssiDbm,
            )
        }
        val stateMarkers = if (test != null) {
            test.states.map { runState ->
                RssiChartStateMarkerInput(
                    atEpochMillis = runState.atEpochMillis,
                    state = runState.state,
                    reason = runState.reason,
                )
            }
        } else {
            stateTransitions(samples)
        }
        val input = RssiChartInput(
            series = listOf(rawSeries, filteredSeries),
            thresholds = thresholds,
            markers = markers,
            stateMarkers = stateMarkers,
            window = window,
            endEpochMillis = endEpochMillis,
            gapThresholdMillis = gapThresholdMillis,
            maxDisplayPointsPerSeries = MAX_DISPLAY_POINTS,
        )
        return MonitorChartState(
            window = window,
            mode = mode,
            data = RssiChartBuilder.build(input),
        )
    }

    private fun stateTransitions(samples: List<LiveReadingPoint>): List<RssiChartStateMarkerInput> {
        val result = mutableListOf<RssiChartStateMarkerInput>()
        var previous: Pair<ProximityState, ProximityStateReason>? = null
        samples.forEach { sample ->
            val current = sample.state to sample.reason
            if (current != previous) {
                result += RssiChartStateMarkerInput(
                    atEpochMillis = sample.atEpochMillis,
                    state = sample.state,
                    reason = sample.reason,
                )
                previous = current
            }
        }
        return result
    }
}
