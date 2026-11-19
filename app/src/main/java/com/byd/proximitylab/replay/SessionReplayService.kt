package com.byd.proximitylab.replay

import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.proximity.ProximityEngine
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionStore
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 09 的 Session 回放入口。
 *
 * 每次调用从 Session 存储读取一次有序时间线和配置快照，新建独立的 `SessionTimelineProximityRunner`
 * （即同一 03 判定入口），不会复用实时监控或前一次回放的判定上下文。回放只写入 [SessionReplayStore]，
 * 不持有 [com.byd.proximitylab.simulation.SimulationRunStore]、实时反馈或通知/震动依赖。
 *
 * [ReplayConfigRequest.OriginalSnapshot] 缺少当次快照时，按规格退回 [AlgorithmConfigStore] 的当前生效
 * 配置，并把结果标为 [ReplayOutcome.NEW_CALCULATION]。当次快照存在但记录版本与当前实现不同时，仍用当前
 * 实现按快照参数重算，结果标为 [ReplayOutcome.VERSION_RECOMPUTED] 并保留差异。
 */
class SessionReplayService(
    private val sessionStore: SessionStore,
    private val replayStore: SessionReplayStore,
    private val configStore: AlgorithmConfigStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun replay(
        sessionId: String,
        request: ReplayConfigRequest = ReplayConfigRequest.OriginalSnapshot,
    ): SessionReplayResult {
        val session = sessionStore.loadSession(sessionId)
            ?: return SessionReplayResult.SessionNotFound(sessionId)
        if (session.status == SessionLifecycleStatus.RECORDING) {
            return SessionReplayResult.SessionStillRecording(sessionId)
        }

        val originalSnapshot = sessionStore.loadConfigSnapshot(sessionId)
        val selection = when (request) {
            ReplayConfigRequest.OriginalSnapshot -> {
                if (originalSnapshot != null) {
                    SelectedConfig(originalSnapshot, ReplayConfigSource.ORIGINAL_SNAPSHOT)
                } else {
                    val current = currentSnapshot()
                        ?: return SessionReplayResult.ConfigurationUnavailable(
                            sessionId = sessionId,
                            reason = "Session 没有当次算法快照，且当前生效配置不可用。",
                        )
                    SelectedConfig(current, ReplayConfigSource.CURRENT_SELECTION)
                }
            }

            is ReplayConfigRequest.NamedSnapshot -> SelectedConfig(
                snapshot = request.snapshot,
                source = ReplayConfigSource.NAMED_SNAPSHOT,
            )

            ReplayConfigRequest.CurrentSelection -> {
                val current = currentSnapshot()
                    ?: return SessionReplayResult.ConfigurationUnavailable(
                        sessionId = sessionId,
                        reason = "当前生效配置不可用。",
                    )
                SelectedConfig(current, ReplayConfigSource.CURRENT_SELECTION)
            }
        }

        val timeline = sessionStore.loadTimeline(sessionId)
        val computation = SessionReplayEngine.replay(
            session = session,
            timeline = timeline,
            config = selection.snapshot,
        )
        val versions = ReplayVersionInfo(
            originalSnapshotAvailable = originalSnapshot != null,
            originalAlgorithmVersion = originalSnapshot?.algorithmVersion,
            originalTriggerRuleVersion = originalSnapshot?.triggerRuleVersion,
            snapshotAlgorithmVersion = selection.snapshot.algorithmVersion,
            snapshotTriggerRuleVersion = selection.snapshot.triggerRuleVersion,
            executedAlgorithmVersion = ProximityEngine.ALGORITHM_VERSION,
            executedTriggerRuleVersion = ProximityEngine.TRIGGER_RULE_VERSION,
        )
        val outcome = when {
            selection.source == ReplayConfigSource.ORIGINAL_SNAPSHOT && !versions.versionMismatch ->
                ReplayOutcome.REPRODUCED

            selection.source == ReplayConfigSource.ORIGINAL_SNAPSHOT ->
                ReplayOutcome.VERSION_RECOMPUTED

            else -> ReplayOutcome.NEW_CALCULATION
        }

        val record = SessionReplayRecord(
            replayId = idGenerator(),
            sessionId = sessionId,
            config = selection.snapshot,
            configSource = selection.source,
            outcome = outcome,
            versions = versions,
            coverage = computation.coverage,
            samples = computation.samples,
            states = computation.states,
            events = computation.events,
            finalState = computation.finalState,
            createdAtEpochMillis = clock(),
        )
        return SessionReplayResult.Completed(replayStore.saveReplay(record))
    }

    fun observeReplays(sessionId: String): Flow<List<SessionReplaySummary>> =
        replayStore.observeReplaySummaries(sessionId)

    suspend fun loadReplays(sessionId: String): List<SessionReplayRecord> =
        replayStore.loadReplays(sessionId)

    suspend fun loadReplay(replayId: String): SessionReplayRecord? =
        replayStore.loadReplay(replayId)

    private suspend fun currentSnapshot(): SessionConfigSnapshot? =
        configStore?.snapshotForNextSimulation()

    private data class SelectedConfig(
        val snapshot: SessionConfigSnapshot,
        val source: ReplayConfigSource,
    )
}
