package com.byd.proximitylab.comparison

import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.replay.ReplayEvent
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionMarkerEvaluation
import com.byd.proximitylab.session.SessionMarkerPolicy
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.coverageEndEpochMillis
import java.util.Locale

/**
 * 把一次 09 回放结果按人工参考和允许窗口评成一条试验结论。
 *
 * 评分只读取 [SessionReplayRecord] 的事件、状态和覆盖范围，不修改回放记录、原始时间线或实时历史。
 * 判定规则：
 * - 正样本首条事件早于窗起点为「提前」，落在窗内（含端点）为「按时」，晚于窗终点为「迟到」；
 * - 覆盖完整评分窗口且零事件为「漏触发」，并按是否出现可信 `FAR` 区分原因；
 * - 其余事件逐条计为额外事件（重复），不改变首条结论；
 * - 负样本出现事件为「误触发」，提前中断且无事件为「观测不足」，完整无事件为「未误触发」；
 * - 未标记、多个期望点、缺少覆盖内期望点只做诊断，不进入评分汇总。
 */
object SessionScoring {

    fun evaluate(
        session: SamplingSession,
        markers: List<SessionReferenceMarker>,
        window: ScoringWindow,
        replay: SessionReplayRecord,
    ): TrialScore {
        val evaluation = SessionMarkerPolicy.evaluate(session, markers)
        val orderedEvents = replay.events.sortedWith(
            compareBy({ event -> event.atEpochMillis }, { event -> event.frameId }),
        )
        val finalState = replay.finalState
        val diagnostics = diagnosticsFor(evaluation)

        if (evaluation.isNegativeSample) {
            return negativeSampleScore(
                session = session,
                window = window,
                replay = replay,
                events = orderedEvents,
                finalStateReason = finalState.reason,
                finalState = finalState.state,
                diagnostics = diagnostics,
            )
        }

        if (evaluation.expectedTriggerConflict) {
            return skippedScore(
                session = session,
                window = window,
                replay = replay,
                events = orderedEvents,
                evaluation = evaluation,
                reason = ScoreSkipReason.MULTIPLE_EXPECTED_MARKERS,
                detail = "覆盖范围内有 ${evaluation.expectedTriggerMarkersInCoverage.size} 个期望触发参考点，" +
                    "评分参考不唯一，只展示曲线与事件，不计算提前/按时/迟到。",
                diagnostics = diagnostics,
            )
        }

        val expectedTriggerAt = evaluation.expectedTriggerAtEpochMillis
        if (expectedTriggerAt == null) {
            val (reason, detail) = when {
                session.evaluationLabel == SessionEvaluationLabel.EXPECTED_TRIGGER ->
                    ScoreSkipReason.MISSING_EXPECTED_MARKER to if (
                        evaluation.markersOutsideCoverage.any { marker ->
                            marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER
                        }
                    ) {
                        "期望触发参考点不在已记录覆盖范围内，不能作为评分依据。"
                    } else {
                        "整次试验标为期望触发，但覆盖范围内没有期望触发参考点。"
                    }

                session.evaluationLabel == SessionEvaluationLabel.UNMARKED ->
                    ScoreSkipReason.UNMARKED to "整次试验未标记；只展示状态和事件，不推算出准确率。"

                else -> ScoreSkipReason.NO_SCORING_BASIS to "没有可用于评分的正样本或负样本依据。"
            }
            return skippedScore(
                session = session,
                window = window,
                replay = replay,
                events = orderedEvents,
                evaluation = evaluation,
                reason = reason,
                detail = detail,
                diagnostics = diagnostics,
            )
        }

        val windowStartEpochMillis = window.startAt(expectedTriggerAt)
        val windowEndEpochMillis = window.endAt(expectedTriggerAt)
        val coverageCoversWindow = session.coverageEndEpochMillis >= windowEndEpochMillis
        val scoredEvents = orderedEvents.mapIndexed { index, event ->
            scoredEvent(
                event = event,
                markerAtEpochMillis = expectedTriggerAt,
                role = if (index == 0) ScoredEventRole.FIRST else ScoredEventRole.EXTRA,
            )
        }
        val firstEvent = scoredEvents.firstOrNull()
        val baseWindows = WindowFields(
            expectedTriggerAtEpochMillis = expectedTriggerAt,
            expectedTriggerSessionMillis = expectedTriggerAt - session.startedAtEpochMillis,
            windowStartEpochMillis = windowStartEpochMillis,
            windowEndEpochMillis = windowEndEpochMillis,
            windowStartSessionMillis = windowStartEpochMillis - session.startedAtEpochMillis,
            windowEndSessionMillis = windowEndEpochMillis - session.startedAtEpochMillis,
        )

        if (!coverageCoversWindow) {
            return TrialScore(
                sessionId = session.id,
                outcome = TrialOutcome.INSUFFICIENT_OBSERVATION,
                window = window,
                expectedTriggerAtEpochMillis = baseWindows.expectedTriggerAtEpochMillis,
                expectedTriggerSessionMillis = baseWindows.expectedTriggerSessionMillis,
                windowStartEpochMillis = baseWindows.windowStartEpochMillis,
                windowEndEpochMillis = baseWindows.windowEndEpochMillis,
                windowStartSessionMillis = baseWindows.windowStartSessionMillis,
                windowEndSessionMillis = baseWindows.windowEndSessionMillis,
                events = scoredEvents,
                extraEventCount = 0,
                falseTriggerCount = 0,
                missedCause = null,
                farConfirmedAtEpochMillis = null,
                finalState = finalState.state,
                finalReason = finalState.reason,
                coverageEndEpochMillis = session.coverageEndEpochMillis,
                coverageCoversWindow = false,
                coverageIncomplete = true,
                skipReason = null,
                skipDetail = "记录覆盖终点 ${formatSessionTime(session.coverageEndEpochMillis - session.startedAtEpochMillis)} " +
                    "早于评分窗终点 ${formatSessionTime(baseWindows.windowEndSessionMillis)}；未记录区间不判为漏触发或成功。",
                diagnostics = diagnostics,
            )
        }

        if (firstEvent == null) {
            val farStates = replay.states.filter { state ->
                state.state == ProximityState.FAR && state.atEpochMillis <= windowEndEpochMillis
            }
            val firstFar = farStates.firstOrNull()
            val missedCause = if (firstFar == null) {
                MissedTriggerCause.NO_VERIFIED_FAR
            } else {
                MissedTriggerCause.FAR_CONFIRMED_WITHOUT_TRIGGER
            }
            val detail = if (firstFar == null) {
                "覆盖内未出现可验证的 FAR；回放最终 ${finalState.state.name}/${finalState.reason.name}。" +
                    "缺少 FAR 时不能把零事件归因于阈值过滤。"
            } else {
                "已在 ${formatSessionTime(firstFar.atSessionMillis)} 确认 FAR，但评分窗内没有产生事件；" +
                    "回放最终 ${finalState.state.name}/${finalState.reason.name}。"
            }
            return TrialScore(
                sessionId = session.id,
                outcome = TrialOutcome.MISSED,
                window = window,
                expectedTriggerAtEpochMillis = baseWindows.expectedTriggerAtEpochMillis,
                expectedTriggerSessionMillis = baseWindows.expectedTriggerSessionMillis,
                windowStartEpochMillis = baseWindows.windowStartEpochMillis,
                windowEndEpochMillis = baseWindows.windowEndEpochMillis,
                windowStartSessionMillis = baseWindows.windowStartSessionMillis,
                windowEndSessionMillis = baseWindows.windowEndSessionMillis,
                events = scoredEvents,
                extraEventCount = 0,
                falseTriggerCount = 0,
                missedCause = missedCause,
                farConfirmedAtEpochMillis = firstFar?.atEpochMillis,
                finalState = finalState.state,
                finalReason = finalState.reason,
                coverageEndEpochMillis = session.coverageEndEpochMillis,
                coverageCoversWindow = true,
                coverageIncomplete = false,
                skipReason = null,
                skipDetail = detail,
                diagnostics = diagnostics,
            )
        }

        val firstAt = firstEvent.atEpochMillis
        val outcome = when {
            firstAt < windowStartEpochMillis -> TrialOutcome.EARLY
            firstAt <= windowEndEpochMillis -> TrialOutcome.ON_TIME
            else -> TrialOutcome.LATE
        }
        return TrialScore(
            sessionId = session.id,
            outcome = outcome,
            window = window,
            expectedTriggerAtEpochMillis = baseWindows.expectedTriggerAtEpochMillis,
            expectedTriggerSessionMillis = baseWindows.expectedTriggerSessionMillis,
            windowStartEpochMillis = baseWindows.windowStartEpochMillis,
            windowEndEpochMillis = baseWindows.windowEndEpochMillis,
            windowStartSessionMillis = baseWindows.windowStartSessionMillis,
            windowEndSessionMillis = baseWindows.windowEndSessionMillis,
            events = scoredEvents,
            extraEventCount = (scoredEvents.size - 1).coerceAtLeast(0),
            falseTriggerCount = 0,
            missedCause = null,
            farConfirmedAtEpochMillis = null,
            finalState = finalState.state,
            finalReason = finalState.reason,
            coverageEndEpochMillis = session.coverageEndEpochMillis,
            coverageCoversWindow = true,
            coverageIncomplete = false,
            skipReason = null,
            skipDetail = null,
            diagnostics = diagnostics,
        )
    }

    private fun negativeSampleScore(
        session: SamplingSession,
        window: ScoringWindow,
        replay: SessionReplayRecord,
        events: List<ReplayEvent>,
        finalState: ProximityState,
        finalStateReason: ProximityStateReason,
        diagnostics: List<String>,
    ): TrialScore {
        val scoredEvents = events.map { event ->
            scoredEvent(
                event = event,
                markerAtEpochMillis = null,
                role = ScoredEventRole.FALSE_TRIGGER,
            )
        }
        val interrupted = session.status == SessionLifecycleStatus.INTERRUPTED
        val outcome = when {
            scoredEvents.isNotEmpty() -> TrialOutcome.FALSE_TRIGGER
            interrupted -> TrialOutcome.INSUFFICIENT_OBSERVATION
            else -> TrialOutcome.NO_FALSE_TRIGGER
        }
        val detail = when (outcome) {
            TrialOutcome.FALSE_TRIGGER -> if (interrupted) {
                "可观察事件 ${scoredEvents.size} 条；试验提前中断，覆盖不全。"
            } else {
                "可观察事件 ${scoredEvents.size} 条。"
            }

            TrialOutcome.INSUFFICIENT_OBSERVATION ->
                "试验提前中断且没有事件，不能判定为未误触发。"

            else -> "整次试验未产生事件。"
        }
        return TrialScore(
            sessionId = session.id,
            outcome = outcome,
            window = window,
            expectedTriggerAtEpochMillis = null,
            expectedTriggerSessionMillis = null,
            windowStartEpochMillis = null,
            windowEndEpochMillis = null,
            windowStartSessionMillis = null,
            windowEndSessionMillis = null,
            events = scoredEvents,
            extraEventCount = 0,
            falseTriggerCount = scoredEvents.size,
            missedCause = null,
            farConfirmedAtEpochMillis = null,
            finalState = finalState,
            finalReason = finalStateReason,
            coverageEndEpochMillis = session.coverageEndEpochMillis,
            coverageCoversWindow = null,
            coverageIncomplete = interrupted,
            skipReason = null,
            skipDetail = detail,
            diagnostics = diagnostics,
        )
    }

    private fun skippedScore(
        session: SamplingSession,
        window: ScoringWindow,
        replay: SessionReplayRecord,
        events: List<ReplayEvent>,
        evaluation: SessionMarkerEvaluation,
        reason: ScoreSkipReason,
        detail: String,
        diagnostics: List<String>,
    ): TrialScore {
        val markerAt = evaluation.expectedTriggerAtEpochMillis
        return TrialScore(
            sessionId = session.id,
            outcome = TrialOutcome.SKIPPED,
            window = window,
            expectedTriggerAtEpochMillis = markerAt,
            expectedTriggerSessionMillis = markerAt?.let { at -> at - session.startedAtEpochMillis },
            windowStartEpochMillis = markerAt?.let(window::startAt),
            windowEndEpochMillis = markerAt?.let(window::endAt),
            windowStartSessionMillis = markerAt?.let { at ->
                window.startAt(at) - session.startedAtEpochMillis
            },
            windowEndSessionMillis = markerAt?.let { at ->
                window.endAt(at) - session.startedAtEpochMillis
            },
            events = events.map { event ->
                scoredEvent(
                    event = event,
                    markerAtEpochMillis = markerAt,
                    role = ScoredEventRole.UNSCORED,
                )
            },
            extraEventCount = 0,
            falseTriggerCount = 0,
            missedCause = null,
            farConfirmedAtEpochMillis = null,
            finalState = replay.finalState.state,
            finalReason = replay.finalState.reason,
            coverageEndEpochMillis = session.coverageEndEpochMillis,
            coverageCoversWindow = null,
            coverageIncomplete = session.status == SessionLifecycleStatus.INTERRUPTED,
            skipReason = reason,
            skipDetail = detail,
            diagnostics = diagnostics,
        )
    }

    private fun diagnosticsFor(evaluation: SessionMarkerEvaluation): List<String> = buildList {
        if (evaluation.isNegativeSample && evaluation.expectedTriggerMarkersInCoverage.isNotEmpty()) {
            add(
                "整次试验标为无需触发，但覆盖范围内还有 " +
                    "${evaluation.expectedTriggerMarkersInCoverage.size} 个期望触发参考点；按负样本统计误触发。",
            )
        }
        if (evaluation.expectedTriggerConflict) {
            add("覆盖范围内存在多个期望触发参考点，不能唯一确定评分参考。")
        }
    }

    private fun scoredEvent(
        event: ReplayEvent,
        markerAtEpochMillis: Long?,
        role: ScoredEventRole,
    ): ScoredTrialEvent = ScoredTrialEvent(
        atEpochMillis = event.atEpochMillis,
        atSessionMillis = event.atSessionMillis,
        offsetFromMarkerMillis = markerAtEpochMillis?.let { at -> event.atEpochMillis - at },
        role = role,
        type = event.type,
        reason = event.reason,
        rawRssiDbm = event.rawRssiDbm,
        filteredRssiDbm = event.filteredRssiDbm,
    )

    private fun formatSessionTime(sessionMillis: Long): String =
        String.format(Locale.ROOT, "%+.1f 秒", sessionMillis / 1_000.0)

    private data class WindowFields(
        val expectedTriggerAtEpochMillis: Long,
        val expectedTriggerSessionMillis: Long,
        val windowStartEpochMillis: Long,
        val windowEndEpochMillis: Long,
        val windowStartSessionMillis: Long,
        val windowEndSessionMillis: Long,
    )
}
