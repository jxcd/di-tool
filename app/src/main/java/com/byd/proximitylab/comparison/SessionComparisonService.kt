package com.byd.proximitylab.comparison

import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.replay.ReplayConfigRequest
import com.byd.proximitylab.replay.ReplayConfigSource
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.replay.SessionReplayResult
import com.byd.proximitylab.replay.SessionReplayService
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionMarkerPolicy
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionSummary
import kotlinx.coroutines.flow.Flow

/**
 * 10 的多配置对比入口。
 *
 * 每套配置都通过 09 的 [SessionReplayService] 独立回放同一 Session，回放记录照常持久化；
 * 评分只读取回放结果、人工参考和本次评分窗，不写实时事件历史、不触发通知或震动。
 */
class SessionComparisonService(
    private val sessionStore: SessionStore,
    private val replayService: SessionReplayService,
    private val configStore: AlgorithmConfigStore,
) {

    fun observeSessions(): Flow<List<SessionSummary>> = sessionStore.observeSessions()

    /**
     * 读取选中 Session 的人工参考、标签与当次配置，用于展示评分依据和可选的「当次配置」。
     */
    suspend fun loadContext(sessionId: String): ComparisonSessionContext? {
        val session = sessionStore.loadSession(sessionId) ?: return null
        val markers = sessionStore.loadReferenceMarkers(sessionId)
        return ComparisonSessionContext(
            session = session,
            markers = markers,
            evaluation = SessionMarkerPolicy.evaluate(session, markers),
            configSnapshot = sessionStore.loadConfigSnapshot(sessionId),
        )
    }

    /**
     * 用同一 Session 和同一 [window] 依次回放 [targets]，返回各配置的曲线、事件、版本和评分。
     *
     * 只替代外部时间与 BLE 输入；09 的回放入口、03 的共享判定与 Session/回放存储都是生产实现。
     */
    suspend fun compare(
        sessionId: String,
        targets: List<ComparisonTarget>,
        window: ScoringWindow,
    ): SessionComparisonResult {
        val session = sessionStore.loadSession(sessionId)
            ?: return SessionComparisonResult.Unavailable(sessionId, "Session 不存在或已被删除。")
        if (session.status == SessionLifecycleStatus.RECORDING) {
            return SessionComparisonResult.Unavailable(
                sessionId,
                "采集中 Session 没有稳定覆盖终点，请先结束采样再对比评分。",
            )
        }
        if (targets.isEmpty()) {
            return SessionComparisonResult.Unavailable(sessionId, "请至少选择一套配置。")
        }

        val markers = sessionStore.loadReferenceMarkers(sessionId)
        val evaluation = SessionMarkerPolicy.evaluate(session, markers)
        val timeline = sessionStore.loadTimeline(sessionId)
        val originalSnapshot = sessionStore.loadConfigSnapshot(sessionId)
        val currentSelection = configStore.loadSelectedConfig()
        val currentConfigId = currentSelection.configId
        val currentSnapshotId = currentSelection.defaultSnapshotId()

        val trials = mutableListOf<ComparisonTrialResult>()
        val failures = mutableListOf<ComparisonTrialFailure>()
        val usedSnapshotIds = mutableSetOf<String>()
        targets.forEach { target ->
            val replay = when (target) {
                ComparisonTarget.OriginalSnapshot -> {
                    if (originalSnapshot == null) {
                        failures += ComparisonTrialFailure(
                            target = target,
                            label = "当次配置（快照）",
                            reason = "Session 没有当次算法快照。",
                        )
                        return@forEach
                    }
                    replayFor(
                        sessionId = sessionId,
                        request = ReplayConfigRequest.OriginalSnapshot,
                        target = target,
                        expectedSnapshotId = originalSnapshot.snapshotId,
                        failures = failures,
                    )
                }

                is ComparisonTarget.Config -> {
                    val config = configStore.loadConfig(target.configId)
                    if (config == null) {
                        failures += ComparisonTrialFailure(
                            target = target,
                            label = "配置 ${target.configId}",
                            reason = "配置已不存在，无法回放。",
                        )
                        return@forEach
                    }
                    replayFor(
                        sessionId = sessionId,
                        request = ReplayConfigRequest.NamedSnapshot(config.toSessionSnapshot()),
                        target = target,
                        expectedSnapshotId = config.defaultSnapshotId(),
                        failures = failures,
                    )
                }
            } ?: return@forEach

            if (!usedSnapshotIds.add(replay.config.snapshotId)) {
                failures += ComparisonTrialFailure(
                    target = target,
                    label = replay.config.name,
                    reason = "与已选配置使用相同的算法快照（${replay.config.snapshotId}），已跳过重复回放。",
                )
                return@forEach
            }
            if (target == ComparisonTarget.OriginalSnapshot &&
                replay.configSource != ReplayConfigSource.ORIGINAL_SNAPSHOT
            ) {
                failures += ComparisonTrialFailure(
                    target = target,
                    label = replay.config.name,
                    reason = "当次快照不可用，回放退回 ${replay.configSource.label}。",
                )
                return@forEach
            }

            trials += ComparisonTrialResult(
                replay = replay,
                target = target,
                isOriginalRunConfig = target == ComparisonTarget.OriginalSnapshot,
                isCurrentSelection = when (target) {
                    ComparisonTarget.OriginalSnapshot ->
                        replay.config.snapshotId == currentSnapshotId

                    is ComparisonTarget.Config -> target.configId == currentConfigId
                },
                score = SessionScoring.evaluate(
                    session = session,
                    markers = markers,
                    window = window,
                    replay = replay,
                ),
            )
        }

        if (trials.isEmpty()) {
            return SessionComparisonResult.Unavailable(
                sessionId = sessionId,
                reason = failures.joinToString("；") { failure -> "${failure.label}：${failure.reason}" }
                    .ifEmpty { "所选配置均不可用。" },
            )
        }

        val chart = SessionComparisonChart.build(
            session = session,
            timeline = timeline,
            trials = trials,
            markers = markers,
            evaluation = evaluation,
            window = window,
        )
        return SessionComparisonResult.Completed(
            SessionComparison(
                session = session,
                markers = markers,
                evaluation = evaluation,
                window = window,
                trials = trials,
                failures = failures,
                chart = chart,
                summary = ComparisonSummaryBuilder.build(
                    listOf(ScoredSessionTrials(session = session, trials = trials)),
                ),
            ),
        )
    }

    private suspend fun replayFor(
        sessionId: String,
        request: ReplayConfigRequest,
        target: ComparisonTarget,
        expectedSnapshotId: String,
        failures: MutableList<ComparisonTrialFailure>,
    ): SessionReplayRecord? {
        return when (val result = replayService.replay(sessionId, request)) {
            is SessionReplayResult.Completed -> {
                if (result.record.config.snapshotId != expectedSnapshotId) {
                    failures += ComparisonTrialFailure(
                        target = target,
                        label = result.record.config.name,
                        reason = "回放实际使用的快照与所选配置不一致。",
                    )
                    null
                } else {
                    result.record
                }
            }

            is SessionReplayResult.SessionNotFound -> {
                failures += ComparisonTrialFailure(
                    target = target,
                    label = "配置",
                    reason = "Session 不存在或已被删除。",
                )
                null
            }

            is SessionReplayResult.SessionStillRecording -> {
                failures += ComparisonTrialFailure(
                    target = target,
                    label = "配置",
                    reason = "Session 仍在采集，不能回放。",
                )
                null
            }

            is SessionReplayResult.ConfigurationUnavailable -> {
                failures += ComparisonTrialFailure(
                    target = target,
                    label = "配置",
                    reason = result.reason,
                )
                null
            }
        }
    }
}
