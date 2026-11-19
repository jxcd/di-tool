package com.byd.proximitylab.replay

import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityDecision
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.SessionTimelineProximityRunner
import com.byd.proximitylab.session.coverageEndEpochMillis

/** 回放使用的配置来源。 */
enum class ReplayConfigSource(val label: String) {
    /** 05 在模拟测试开始时固定在 Session 上的当次快照。 */
    ORIGINAL_SNAPSHOT("当次快照"),

    /** 由上层（10 的配置比较）显式传入的具名快照。 */
    NAMED_SNAPSHOT("指定配置快照"),

    /** Session 没有当次快照时，读取 06 当前生效配置生成的新快照。 */
    CURRENT_SELECTION("当前生效配置"),
}

/**
 * 回放结论。是否重现当次结果不仅取决于参数，也取决于当时的算法和触发规则版本。
 */
enum class ReplayOutcome(val label: String) {
    /** 使用当次快照，且当前实现的算法/触发规则版本与快照记录一致。 */
    REPRODUCED("重现当次结果"),

    /** 使用当次快照，但运行版本已变化；按当前实现重算并保留版本差异。 */
    VERSION_RECOMPUTED("按当前实现重算（版本不同）"),

    /** 使用其他配置，或 Session 没有当次快照而改用当前配置；结果标为新算。 */
    NEW_CALCULATION("新算结果"),
}

/** 版本差异的类别。 */
enum class ReplayVersionKind(val label: String) {
    ALGORITHM("算法"),
    TRIGGER_RULE("触发规则"),
}

data class ReplayVersionDifference(
    val kind: ReplayVersionKind,
    val recordedValue: String,
    val executedValue: String,
)

/**
 * 回放的版本信息。
 *
 * [originalAlgorithmVersion]/[originalTriggerRuleVersion] 来自 Session 当次快照，可为空；
 * [snapshotAlgorithmVersion]/[snapshotTriggerRuleVersion] 是本次回放所用配置快照记录的版本；
 * [executedAlgorithmVersion]/[executedTriggerRuleVersion] 是实际执行回放的当前实现版本。
 * 版本差异按“本次快照 vs 实际执行”计算，因此当次快照回放能直接说明结果是否与当时同版本。
 */
data class ReplayVersionInfo(
    val originalSnapshotAvailable: Boolean,
    val originalAlgorithmVersion: String?,
    val originalTriggerRuleVersion: String?,
    val snapshotAlgorithmVersion: String,
    val snapshotTriggerRuleVersion: String,
    val executedAlgorithmVersion: String,
    val executedTriggerRuleVersion: String,
) {
    init {
        require(originalSnapshotAvailable || originalAlgorithmVersion == null) {
            "没有当次快照时不能记录原算法版本"
        }
        require(originalSnapshotAvailable || originalTriggerRuleVersion == null) {
            "没有当次快照时不能记录原触发规则版本"
        }
    }

    val differences: List<ReplayVersionDifference>
        get() = buildList {
            if (snapshotAlgorithmVersion != executedAlgorithmVersion) {
                add(
                    ReplayVersionDifference(
                        kind = ReplayVersionKind.ALGORITHM,
                        recordedValue = snapshotAlgorithmVersion,
                        executedValue = executedAlgorithmVersion,
                    ),
                )
            }
            if (snapshotTriggerRuleVersion != executedTriggerRuleVersion) {
                add(
                    ReplayVersionDifference(
                        kind = ReplayVersionKind.TRIGGER_RULE,
                        recordedValue = snapshotTriggerRuleVersion,
                        executedValue = executedTriggerRuleVersion,
                    ),
                )
            }
        }

    val versionMismatch: Boolean
        get() = differences.isNotEmpty()
}

/**
 * 回放的一条有效滤波样本。时刻使用 Session 相对毫秒和 UTC epoch 毫秒两种表示。
 */
data class ReplaySample(
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val observedAtEpochMillis: Long,
    val frameId: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val state: ProximityState,
    val reason: ProximityStateReason,
    val trend: MotionTrend,
)

/** 回放中的一次状态/原因变化。 */
data class ReplayStatePoint(
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val state: ProximityState,
    val reason: ProximityStateReason,
    val trend: MotionTrend,
    val lastReadingFrameId: Long?,
    val lastReadingObservedAtEpochMillis: Long?,
    val rawRssiDbm: Int?,
    val filteredRssiDbm: Double?,
)

/** 回放产生的一条 `ENTERED_RANGE` 事件；不携带任何通知/震动结果。 */
data class ReplayEvent(
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val type: ProximityEventType,
    val reason: ProximityEventReason,
    val targetId: String,
    val frameId: Long,
    val observedAtEpochMillis: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
)

/**
 * 回放实际可用的输入覆盖。
 *
 * [interrupted] 表示 Session 中断，回放只到 [coverageEndEpochMillis]；[hasEventGaps] 表示采集缓冲
 * 曾溢出，缺少的输入不能解释成空窗。两者都必须在结果中可辨识。
 */
data class ReplayInputCoverage(
    val startedAtEpochMillis: Long,
    val coverageEndEpochMillis: Long,
    val sessionStatus: SessionLifecycleStatus,
    val interrupted: Boolean,
    val hasEventGaps: Boolean,
    val droppedEventCount: Int,
    val observationCount: Int,
) {
    val durationMillis: Long
        get() = (coverageEndEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)

    val hasObservations: Boolean
        get() = observationCount > 0

    val isCompleteWithinCoverage: Boolean
        get() = !interrupted && !hasEventGaps
}

/**
 * 纯 Kotlin 回放输出。`states` 与 05 实时运行状态一样只在状态或原因变化时追加；
 * [finalState] 始终是覆盖终点处的最终可观察结果。
 */
data class ReplayComputation(
    val samples: List<ReplaySample>,
    val states: List<ReplayStatePoint>,
    val events: List<ReplayEvent>,
    val finalState: ReplayStatePoint,
    val coverage: ReplayInputCoverage,
)

/** 回放入口的选择。 */
sealed interface ReplayConfigRequest {

    /** 使用 Session 当次快照；缺少快照时退回当前生效配置并标为新算结果。 */
    data object OriginalSnapshot : ReplayConfigRequest

    /** 使用上层指定的具名快照，例如 10 的配置比较。 */
    data class NamedSnapshot(val snapshot: SessionConfigSnapshot) : ReplayConfigRequest

    /** 使用 06 的当前生效配置生成快照。 */
    data object CurrentSelection : ReplayConfigRequest
}

/**
 * 一次已持久化的回放结果。
 *
 * 快照参数完整复制在 [config] 中，不依赖算法配置行；即使原配置改名或删除，回放记录仍可解释。
 * [versions] 同时保留当次快照、本次快照和实际执行版本，供 10 解释版本差异。
 */
data class SessionReplayRecord(
    val replayId: String,
    val sessionId: String,
    val config: SessionConfigSnapshot,
    val configSource: ReplayConfigSource,
    val outcome: ReplayOutcome,
    val versions: ReplayVersionInfo,
    val coverage: ReplayInputCoverage,
    val samples: List<ReplaySample>,
    val states: List<ReplayStatePoint>,
    val events: List<ReplayEvent>,
    val finalState: ReplayStatePoint,
    val createdAtEpochMillis: Long,
    val replayFormatVersion: Int = REPLAY_FORMAT_VERSION,
) {
    init {
        require(replayId.isNotBlank()) { "回放标识不能为空" }
        require(sessionId.isNotBlank()) { "回放必须关联 Session" }
        require(states.isNotEmpty()) { "回放至少包含初始状态" }
    }

    fun summary(): SessionReplaySummary = SessionReplaySummary(
        replayId = replayId,
        sessionId = sessionId,
        configSnapshotId = config.snapshotId,
        configName = config.name,
        configSource = configSource,
        outcome = outcome,
        versions = versions,
        coverage = coverage,
        finalState = finalState,
        sampleCount = samples.size,
        stateCount = states.size,
        eventCount = events.size,
        createdAtEpochMillis = createdAtEpochMillis,
        replayFormatVersion = replayFormatVersion,
    )

    companion object {
        /** 当前回放记录结构版本。字段变化时由后续票提升。 */
        const val REPLAY_FORMAT_VERSION: Int = 1
    }
}

/** 回放历史列表使用的轻量摘要。 */
data class SessionReplaySummary(
    val replayId: String,
    val sessionId: String,
    val configSnapshotId: String,
    val configName: String,
    val configSource: ReplayConfigSource,
    val outcome: ReplayOutcome,
    val versions: ReplayVersionInfo,
    val coverage: ReplayInputCoverage,
    val finalState: ReplayStatePoint,
    val sampleCount: Int,
    val stateCount: Int,
    val eventCount: Int,
    val createdAtEpochMillis: Long,
    val replayFormatVersion: Int = SessionReplayRecord.REPLAY_FORMAT_VERSION,
)

/** 回放入口结果。 */
sealed interface SessionReplayResult {

    data class Completed(val record: SessionReplayRecord) : SessionReplayResult

    data class SessionNotFound(val sessionId: String) : SessionReplayResult

    /** 采集中 Session 没有稳定覆盖终点，不能作为历史回放。 */
    data class SessionStillRecording(val sessionId: String) : SessionReplayResult

    data class ConfigurationUnavailable(
        val sessionId: String,
        val reason: String,
    ) : SessionReplayResult
}

/**
 * 将 Session、时间线和配置送入共享判定入口的纯 Kotlin 回放入口。
 *
 * 输入必须已按 [SessionTimelineItem.sequence] 排序；本对象不读取 Room、Android API 或系统时间。
 */
object SessionReplayEngine {

    fun replay(
        session: SamplingSession,
        timeline: List<SessionTimelineItem>,
        config: SessionConfigSnapshot,
    ): ReplayComputation {
        require(session.status != SessionLifecycleStatus.RECORDING) {
            "采集中 Session 不能回放：${session.id}"
        }
        val ordered = timeline.sortedBy { item -> item.sequence }
        val observations = ordered.count { item -> item.event is SessionTimelineEvent.Observation }
        val gaps = ordered.mapNotNull { item -> item.event as? SessionTimelineEvent.EventGap }
        val coverage = ReplayInputCoverage(
            startedAtEpochMillis = session.startedAtEpochMillis,
            coverageEndEpochMillis = session.coverageEndEpochMillis,
            sessionStatus = session.status,
            interrupted = session.status == SessionLifecycleStatus.INTERRUPTED,
            hasEventGaps = gaps.isNotEmpty(),
            droppedEventCount = gaps.sumOf { gap -> gap.droppedEventCount },
            observationCount = observations,
        )

        val runner = SessionTimelineProximityRunner(
            config = config.config,
            targetId = session.targetId,
            sessionStartedAtEpochMillis = session.startedAtEpochMillis,
        )
        val recorder = ReplayRecorder(session = session)

        var decision = runner.advanceTo(session.startedAtEpochMillis)
        recorder.capture(decision)

        ordered.forEach { item ->
            advanceExpiryBefore(
                runner = runner,
                recorder = recorder,
                current = decision,
                nextAtSessionMillis = maxOf(
                    decision.atMillis,
                    item.event.processedAtEpochMillis - session.startedAtEpochMillis,
                ),
                session = session,
                config = config,
            )
            decision = runner.apply(item.event)
            recorder.capture(decision)
        }

        val coverageSessionMillis =
            (session.coverageEndEpochMillis - session.startedAtEpochMillis).coerceAtLeast(0L)
        advanceExpiryBefore(
            runner = runner,
            recorder = recorder,
            current = decision,
            nextAtSessionMillis = coverageSessionMillis,
            session = session,
            config = config,
        )
        if (coverageSessionMillis > decision.atMillis) {
            decision = runner.advanceTo(session.coverageEndEpochMillis)
            recorder.capture(decision)
        }

        return ReplayComputation(
            samples = recorder.samples.toList(),
            states = recorder.states.toList(),
            events = recorder.events.toList(),
            finalState = recorder.statePoint(decision),
            coverage = coverage,
        )
    }

    /**
     * 在无新事件的空窗中按样本有效期边界显式推进，使 `SAMPLES_EXPIRED` 能在真实过期时刻被观察，
     * 而不是等到下一条事件到达才补记。覆盖终点之外不推断状态。
     */
    private fun advanceExpiryBefore(
        runner: SessionTimelineProximityRunner,
        recorder: ReplayRecorder,
        current: ProximityDecision,
        nextAtSessionMillis: Long,
        session: SamplingSession,
        config: SessionConfigSnapshot,
    ) {
        val reading = current.lastReading ?: return
        val expiryAt = reading.observedAtMillis + config.config.sampleValidityMillis
        if (expiryAt <= current.atMillis || expiryAt > nextAtSessionMillis) {
            return
        }
        val expired = runner.advanceTo(session.startedAtEpochMillis + expiryAt)
        recorder.capture(expired)
    }

    private class ReplayRecorder(
        private val session: SamplingSession,
    ) {
        val samples = mutableListOf<ReplaySample>()
        val states = mutableListOf<ReplayStatePoint>()
        val events = mutableListOf<ReplayEvent>()
        private var lastStateKey: Pair<ProximityState, ProximityStateReason>? = null
        private var lastSampleFrameId: Long? = null

        fun capture(decision: ProximityDecision) {
            val reading = decision.lastReading
            if (reading != null && reading.frameId != lastSampleFrameId) {
                lastSampleFrameId = reading.frameId
                samples += ReplaySample(
                    atEpochMillis = session.startedAtEpochMillis + decision.atMillis,
                    atSessionMillis = decision.atMillis,
                    observedAtEpochMillis = session.startedAtEpochMillis + reading.observedAtMillis,
                    frameId = reading.frameId,
                    rawRssiDbm = reading.rawRssiDbm,
                    filteredRssiDbm = reading.filteredRssiDbm,
                    state = decision.state,
                    reason = decision.reason,
                    trend = decision.trend,
                )
            }

            val key = decision.state to decision.reason
            if (key != lastStateKey) {
                lastStateKey = key
                states += statePoint(decision)
            }

            decision.events.forEach { event ->
                events += ReplayEvent(
                    atEpochMillis = session.startedAtEpochMillis + event.atMillis,
                    atSessionMillis = event.atMillis,
                    type = event.type,
                    reason = event.reason,
                    targetId = event.targetId,
                    frameId = event.frameId,
                    observedAtEpochMillis = session.startedAtEpochMillis + event.observedAtMillis,
                    rawRssiDbm = event.rawRssiDbm,
                    filteredRssiDbm = event.filteredRssiDbm,
                    algorithmVersion = event.algorithmVersion,
                    triggerRuleVersion = event.triggerRuleVersion,
                )
            }
        }

        fun statePoint(
            decision: ProximityDecision,
        ): ReplayStatePoint {
            val reading = decision.lastReading
            return ReplayStatePoint(
                atEpochMillis = session.startedAtEpochMillis + decision.atMillis,
                atSessionMillis = decision.atMillis,
                state = decision.state,
                reason = decision.reason,
                trend = decision.trend,
                lastReadingFrameId = reading?.frameId,
                lastReadingObservedAtEpochMillis = reading?.let { value ->
                    session.startedAtEpochMillis + value.observedAtMillis
                },
                rawRssiDbm = reading?.rawRssiDbm,
                filteredRssiDbm = reading?.filteredRssiDbm,
            )
        }
    }
}
