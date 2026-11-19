package com.byd.proximitylab.comparison

import com.byd.proximitylab.chart.RssiChartBuilder
import com.byd.proximitylab.chart.RssiChartData
import com.byd.proximitylab.chart.RssiChartInput
import com.byd.proximitylab.chart.RssiChartMarkerInput
import com.byd.proximitylab.chart.RssiChartMarkerKind
import com.byd.proximitylab.chart.RssiChartPoint
import com.byd.proximitylab.chart.RssiChartSeriesInput
import com.byd.proximitylab.chart.RssiChartThreshold
import com.byd.proximitylab.chart.RssiChartThresholdKind
import com.byd.proximitylab.chart.RssiSeriesKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionDetailBuilder
import com.byd.proximitylab.session.SessionMarkerEvaluation
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.coverageEndEpochMillis
import com.byd.proximitylab.session.durationMillis

/**
 * 把同一 Session 的原始时间线和多套配置的回放结果组合成 07 契约的同一时间轴图表。
 *
 * 原始曲线只来自 Session 已保存的观测；每套配置一条滤波曲线，事件标记带配置名、评分结论和事件原因。
 * 人工参考标记和评分窗起止也画在同一时间轴上。图表只裁剪/抽点显示，不修改回放记录或原始时间线。
 */
object SessionComparisonChart {

    fun build(
        session: SamplingSession,
        timeline: List<SessionTimelineItem>,
        trials: List<ComparisonTrialResult>,
        markers: List<SessionReferenceMarker>,
        evaluation: SessionMarkerEvaluation,
        window: ScoringWindow,
    ): RssiChartData {
        val ordered = timeline.sortedBy { item -> item.sequence }
        val rawSeries = RssiChartSeriesInput(
            id = "raw",
            label = "原始 RSSI",
            kind = RssiSeriesKind.RAW,
            points = ordered.mapNotNull { item ->
                (item.event as? SessionTimelineEvent.Observation)?.let { observation ->
                    RssiChartPoint(
                        atEpochMillis = observation.processedAtEpochMillis,
                        rssiDbm = observation.rawRssiDbm.toDouble(),
                    )
                }
            },
        )
        val filteredSeries = trials.mapIndexed { index, trial ->
            RssiChartSeriesInput(
                id = "filtered-$index",
                label = "滤波 · ${trial.config.name}",
                kind = RssiSeriesKind.FILTERED,
                points = trial.replay.samples.map { sample ->
                    RssiChartPoint(
                        atEpochMillis = sample.atEpochMillis,
                        rssiDbm = sample.filteredRssiDbm,
                    )
                },
            )
        }

        val thresholds = buildList {
            val seen = mutableSetOf<Pair<Double, RssiChartThresholdKind>>()
            trials.forEach { trial ->
                val config = trial.config.config
                listOf(
                    RssiChartThreshold(
                        label = "进入阈值",
                        rssiDbm = config.enterThresholdDbm.toDouble(),
                        kind = RssiChartThresholdKind.ENTER,
                    ),
                    RssiChartThreshold(
                        label = "离开阈值",
                        rssiDbm = config.exitThresholdDbm.toDouble(),
                        kind = RssiChartThresholdKind.EXIT,
                    ),
                ).forEach { threshold ->
                    if (seen.add(threshold.rssiDbm to threshold.kind)) {
                        add(threshold)
                    }
                }
            }
        }

        val markerInputs = mutableListOf<RssiChartMarkerInput>()
        markers.forEach { marker ->
            markerInputs += RssiChartMarkerInput(
                id = "reference-${marker.markerId}",
                label = marker.kind.label,
                atEpochMillis = marker.atEpochMillis,
                kind = RssiChartMarkerKind.REFERENCE,
            )
        }
        val expectedTriggerAt = evaluation.expectedTriggerAtEpochMillis
        if (expectedTriggerAt != null) {
            val start = window.startAt(expectedTriggerAt)
            val end = window.endAt(expectedTriggerAt)
            markerInputs += RssiChartMarkerInput(
                id = "scoring-window-start",
                label = "评分窗起点",
                atEpochMillis = start,
                kind = RssiChartMarkerKind.REFERENCE,
            )
            markerInputs += RssiChartMarkerInput(
                id = "scoring-window-end",
                label = "评分窗终点",
                atEpochMillis = end,
                kind = RssiChartMarkerKind.REFERENCE,
            )
        }
        trials.forEachIndexed { trialIndex, trial ->
            trial.replay.events.forEachIndexed { eventIndex, event ->
                markerInputs += RssiChartMarkerInput(
                    id = "event-$trialIndex-$eventIndex",
                    label = "${trial.config.name} · ${trial.score.outcome.label} · ${event.reason.name}",
                    atEpochMillis = event.atEpochMillis,
                    kind = RssiChartMarkerKind.EVENT,
                    rssiDbm = event.rawRssiDbm,
                )
            }
        }

        val scoringWindowEnd = expectedTriggerAt?.let(window::endAt)
        val endEpochMillis = maxOf(
            session.coverageEndEpochMillis,
            scoringWindowEnd ?: Long.MIN_VALUE,
            fieldOf(finalStates(trials)) { state -> state.atEpochMillis },
            fieldOf(trials) { trial -> trial.replay.samples.lastOrNull()?.atEpochMillis ?: Long.MIN_VALUE },
            fieldOf(trials) { trial -> trial.replay.events.lastOrNull()?.atEpochMillis ?: Long.MIN_VALUE },
        )

        return RssiChartBuilder.build(
            RssiChartInput(
                series = listOf(rawSeries) + filteredSeries,
                thresholds = thresholds,
                markers = markerInputs.sortedWith(
                    compareBy({ marker -> marker.atEpochMillis }, { marker -> marker.id }),
                ),
                window = SessionDetailBuilder.chartWindowFor(
                    maxOf(
                        session.durationMillis,
                        scoringWindowEnd?.minus(session.startedAtEpochMillis) ?: 0L,
                    ),
                ),
                endEpochMillis = endEpochMillis,
            ),
        )
    }

    private fun finalStates(trials: List<ComparisonTrialResult>) =
        trials.map { trial -> trial.replay.finalState }

    private fun <T> fieldOf(items: List<T>, selector: (T) -> Long): Long =
        items.maxOfOrNull(selector) ?: Long.MIN_VALUE
}
