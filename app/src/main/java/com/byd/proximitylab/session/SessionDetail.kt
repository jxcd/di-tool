package com.byd.proximitylab.session

import com.byd.proximitylab.chart.RssiChartBuilder
import com.byd.proximitylab.chart.RssiChartData
import com.byd.proximitylab.chart.RssiChartMarkerInput
import com.byd.proximitylab.chart.RssiChartMarkerKind
import com.byd.proximitylab.chart.RssiChartThreshold
import com.byd.proximitylab.chart.RssiChartThresholdKind
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.chart.SessionChartInput
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState

/**
 * 08 的 Session 详情只读模型。由已持久化的 Session、时间线、标记、配置快照和模拟运行结果组装。
 *
 * [chart] 复用 07 的只读图表输入与构建器；[statistics] 只在定点采样给出。
 * [sessionId] 保留给 09 的回放入口，删除前可通过详情跳转到后续回放功能。
 */
data class SessionDetailSnapshot(
    val session: SamplingSession,
    val observationCount: Int,
    val timeline: List<SessionTimelineItem>,
    val markers: List<SessionReferenceMarker>,
    val evaluation: SessionMarkerEvaluation,
    val configSnapshot: SessionConfigSnapshot?,
    val statistics: RssiStatistics?,
    val chart: RssiChartData,
    val runEvents: List<SessionRunEvent> = emptyList(),
    val runStates: List<SessionRunState> = emptyList(),
) {
    val sessionId: String
        get() = session.id
}

/**
 * 把真实存储读取结果组装成详情。纯 Kotlin，可在 JVM 测试中直接验证图表空窗、标记与统计。
 */
object SessionDetailBuilder {

    fun build(
        session: SamplingSession,
        timeline: List<SessionTimelineItem>,
        markers: List<SessionReferenceMarker>,
        configSnapshot: SessionConfigSnapshot? = null,
        runEvents: List<SessionRunEvent> = emptyList(),
        runStates: List<SessionRunState> = emptyList(),
    ): SessionDetailSnapshot {
        val orderedTimeline = timeline.sortedBy { item -> item.sequence }
        val observations = orderedTimeline.mapNotNull { item ->
            item.event as? SessionTimelineEvent.Observation
        }
        val referenceMarkers = markers.map { marker ->
            RssiChartMarkerInput(
                id = marker.markerId,
                label = marker.kind.label,
                atEpochMillis = marker.atEpochMillis,
                kind = RssiChartMarkerKind.REFERENCE,
            )
        }
        val thresholds = configSnapshot?.let { snapshot ->
            listOf(
                RssiChartThreshold(
                    label = "进入阈值",
                    rssiDbm = snapshot.config.enterThresholdDbm.toDouble(),
                    kind = RssiChartThresholdKind.ENTER,
                ),
                RssiChartThreshold(
                    label = "离开阈值",
                    rssiDbm = snapshot.config.exitThresholdDbm.toDouble(),
                    kind = RssiChartThresholdKind.EXIT,
                ),
            )
        }.orEmpty()
        val chartInput = SessionChartInput.fromTimeline(
            timeline = orderedTimeline,
            events = runEvents,
            runStates = runStates,
            referenceMarkers = referenceMarkers,
            thresholds = thresholds,
            window = chartWindowFor(session.durationMillis),
            endEpochMillis = session.coverageEndEpochMillis,
        )
        return SessionDetailSnapshot(
            session = session,
            observationCount = observations.size,
            timeline = orderedTimeline,
            markers = markers.sortedWith(
                compareBy({ marker -> marker.atEpochMillis }, { marker -> marker.markerId }),
            ),
            evaluation = SessionMarkerPolicy.evaluate(session, markers),
            configSnapshot = configSnapshot,
            statistics = if (session.samplingType == SamplingType.FIXED_POINT) {
                RssiStatistics.calculate(observations.map { observation -> observation.rawRssiDbm })
            } else {
                null
            },
            chart = RssiChartBuilder.build(chartInput),
            runEvents = runEvents,
            runStates = runStates,
        )
    }

    /**
     * 详情默认展示能覆盖整段短采样；超过 5 分钟时按现有时间窗显示最近 5 分钟，完整标记时间仍单独列出。
     */
    fun chartWindowFor(durationMillis: Long): RssiChartWindow = when {
        durationMillis <= RssiChartWindow.THIRTY_SECONDS.durationMillis ->
            RssiChartWindow.THIRTY_SECONDS

        durationMillis <= RssiChartWindow.ONE_MINUTE.durationMillis ->
            RssiChartWindow.ONE_MINUTE

        else -> RssiChartWindow.FIVE_MINUTES
    }
}
