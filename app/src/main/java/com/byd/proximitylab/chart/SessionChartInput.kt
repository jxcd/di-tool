package com.byd.proximitylab.chart

import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState

/**
 * 把 04 保存的只读 Session 时间线映射成图表输入，供 08 的 Session 详情和 10 的回放对比复用。
 *
 * 这里只读取已持久化的观测、运行状态和事件，不依赖实时扫描、通知或控制器状态。09/10 的滤波曲线
 * 由回放结果额外提供 [filteredSeries]；08 的定点或行走采样默认只有原始曲线。
 */
object SessionChartInput {

    fun fromTimeline(
        timeline: List<SessionTimelineItem>,
        events: List<SessionRunEvent> = emptyList(),
        runStates: List<SessionRunState> = emptyList(),
        referenceMarkers: List<RssiChartMarkerInput> = emptyList(),
        filteredSeries: List<RssiChartSeriesInput> = emptyList(),
        thresholds: List<RssiChartThreshold> = emptyList(),
        window: RssiChartWindow = RssiChartWindow.DEFAULT,
        endEpochMillis: Long,
        gapThresholdMillis: Long = RssiChartInput.DEFAULT_GAP_THRESHOLD_MILLIS,
        maxDisplayPointsPerSeries: Int = RssiChartInput.DEFAULT_MAX_DISPLAY_POINTS,
        domain: RssiChartDomain? = null,
    ): RssiChartInput {
        val observations = timeline.mapNotNull { item ->
            item.event as? SessionTimelineEvent.Observation
        }
        // 图表时间轴统一使用 App 的处理时刻，使观测、状态转换与事件处于同一会话时基。
        val rawSeries = RssiChartSeriesInput(
            id = RAW_SERIES_ID,
            label = "原始 RSSI",
            kind = RssiSeriesKind.RAW,
            points = observations.map { observation ->
                RssiChartPoint(
                    atEpochMillis = observation.processedAtEpochMillis,
                    rssiDbm = observation.rawRssiDbm.toDouble(),
                )
            },
        )
        val eventMarkers = events.map { event ->
            RssiChartMarkerInput(
                id = event.eventId,
                label = "${event.type.name} · ${event.reason.name}",
                atEpochMillis = event.atEpochMillis,
                kind = RssiChartMarkerKind.EVENT,
                rssiDbm = event.rawRssiDbm,
            )
        }
        val stateMarkers = runStates.map { state ->
            RssiChartStateMarkerInput(
                atEpochMillis = state.atEpochMillis,
                state = state.state,
                reason = state.reason,
            )
        }
        return RssiChartInput(
            series = listOf(rawSeries) + filteredSeries,
            thresholds = thresholds,
            markers = (eventMarkers + referenceMarkers)
                .sortedWith(compareBy({ marker -> marker.atEpochMillis }, { marker -> marker.id })),
            stateMarkers = stateMarkers.sortedBy { marker -> marker.atEpochMillis },
            window = window,
            endEpochMillis = endEpochMillis,
            gapThresholdMillis = gapThresholdMillis,
            maxDisplayPointsPerSeries = maxDisplayPointsPerSeries,
            domain = domain,
        )
    }

    const val RAW_SERIES_ID: String = "raw"

    const val FILTERED_SERIES_ID: String = "filtered"
}
