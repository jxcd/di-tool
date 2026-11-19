package com.byd.proximitylab.replay

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
import com.byd.proximitylab.chart.RssiSeriesKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionDetailBuilder
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.coverageEndEpochMillis
import com.byd.proximitylab.session.durationMillis

/**
 * 把回放结果与原始 Session 时间线组合成 07 契约的只读图表数据。
 *
 * 原始曲线来自 Session 已保存的观测，滤波曲线来自本次回放的 [ReplaySample]，事件与状态标记来自
 * 本次回放。构建只裁剪/抽点用于显示，不修改回放记录或原始时间线。
 */
object SessionReplayChart {

    const val REPLAY_FILTERED_SERIES_ID: String = "replay-filtered"

    fun build(
        session: SamplingSession,
        timeline: List<SessionTimelineItem>,
        record: SessionReplayRecord,
        referenceMarkers: List<RssiChartMarkerInput> = emptyList(),
    ): RssiChartData {
        val ordered = timeline.sortedBy { item -> item.sequence }
        val rawPoints = ordered.mapNotNull { item ->
            (item.event as? SessionTimelineEvent.Observation)?.let { observation ->
                RssiChartPoint(
                    atEpochMillis = observation.processedAtEpochMillis,
                    rssiDbm = observation.rawRssiDbm.toDouble(),
                )
            }
        }
        val rawSeries = RssiChartSeriesInput(
            id = "raw",
            label = "原始 RSSI",
            kind = RssiSeriesKind.RAW,
            points = rawPoints,
        )
        val filteredSeries = RssiChartSeriesInput(
            id = REPLAY_FILTERED_SERIES_ID,
            label = "回放滤波 RSSI",
            kind = RssiSeriesKind.FILTERED,
            points = record.samples.map { sample ->
                RssiChartPoint(
                    atEpochMillis = sample.atEpochMillis,
                    rssiDbm = sample.filteredRssiDbm,
                )
            },
        )
        val eventMarkers = record.events.map { event ->
            RssiChartMarkerInput(
                id = "replay-event-${event.atSessionMillis}-${event.frameId}",
                label = "回放 ${event.type.name} · ${event.reason.name}",
                atEpochMillis = event.atEpochMillis,
                kind = RssiChartMarkerKind.EVENT,
                rssiDbm = event.rawRssiDbm,
            )
        }
        val allMarkers = (eventMarkers + referenceMarkers).sortedWith(
            compareBy({ marker -> marker.atEpochMillis }, { marker -> marker.id }),
        )
        val stateMarkers = record.states.map { state ->
            RssiChartStateMarkerInput(
                atEpochMillis = state.atEpochMillis,
                state = state.state,
                reason = state.reason,
            )
        }
        val thresholds = listOf(
            RssiChartThreshold(
                label = "进入阈值",
                rssiDbm = record.config.config.enterThresholdDbm.toDouble(),
                kind = RssiChartThresholdKind.ENTER,
            ),
            RssiChartThreshold(
                label = "离开阈值",
                rssiDbm = record.config.config.exitThresholdDbm.toDouble(),
                kind = RssiChartThresholdKind.EXIT,
            ),
        )
        val endEpochMillis = maxOf(
            session.coverageEndEpochMillis,
            record.finalState.atEpochMillis,
            record.samples.lastOrNull()?.atEpochMillis ?: Long.MIN_VALUE,
        )
        return RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(rawSeries, filteredSeries),
                thresholds = thresholds,
                markers = allMarkers,
                stateMarkers = stateMarkers,
                window = SessionDetailBuilder.chartWindowFor(session.durationMillis),
                endEpochMillis = endEpochMillis,
            ),
        )
    }
}
