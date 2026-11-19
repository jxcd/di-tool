package com.byd.proximitylab.comparison

import com.byd.proximitylab.chart.RssiChartData
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.replay.ReplayConfigSource
import com.byd.proximitylab.replay.ReplayOutcome
import com.byd.proximitylab.replay.ReplayVersionInfo
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionMarkerEvaluation
import com.byd.proximitylab.session.SessionReferenceMarker

/**
 * 一次正样本评分使用的允许窗口，时刻相对唯一的期望触发标记。
 *
 * 窗口起点为 `标记 - [earlyToleranceMillis]`，终点为 `标记 + [lateToleranceMillis]`，两端都算「按时」。
 * 参数只用于评价，不进入 03 的判定输入；默认值只是实验室初始测试值，需要按实车样本校准。
 */
data class ScoringWindow(
    val earlyToleranceMillis: Long = DEFAULT_EARLY_TOLERANCE_MILLIS,
    val lateToleranceMillis: Long = DEFAULT_LATE_TOLERANCE_MILLIS,
) {
    init {
        require(earlyToleranceMillis >= 0L) {
            "提前容差不能为负数：$earlyToleranceMillis"
        }
        require(lateToleranceMillis >= 0L) {
            "迟到容差不能为负数：$lateToleranceMillis"
        }
    }

    fun startAt(markerEpochMillis: Long): Long = markerEpochMillis - earlyToleranceMillis

    fun endAt(markerEpochMillis: Long): Long = markerEpochMillis + lateToleranceMillis

    companion object {
        const val DEFAULT_EARLY_TOLERANCE_MILLIS: Long = 1_000L
        const val DEFAULT_LATE_TOLERANCE_MILLIS: Long = 2_000L
    }
}

/**
 * 一次评分试验的结论。一次试验只会有一个结论；重复事件用 [TrialScore.extraEventCount] 单独计数。
 */
enum class TrialOutcome(val label: String) {
    /** 首条事件早于窗起点。 */
    EARLY("提前"),

    /** 首条事件落在窗起点到窗终点之间（含端点）。 */
    ON_TIME("按时"),

    /** 首条事件晚于窗终点，但仍在已记录 Session 内。 */
    LATE("迟到"),

    /** 覆盖完整评分窗口且零事件。 */
    MISSED("漏触发"),

    /** 明确无需触发的试验出现事件。 */
    FALSE_TRIGGER("误触发"),

    /** 明确无需触发的试验在完整覆盖下没有事件。 */
    NO_FALSE_TRIGGER("未误触发"),

    /** 记录未覆盖所需评分窗口，不能判定为漏触发、按时或成功。 */
    INSUFFICIENT_OBSERVATION("观测不足"),

    /** 缺少唯一参考点或整次试验未标记，只做诊断。 */
    SKIPPED("跳过评分"),
}

/**
 * 漏触发时，区分「未取得可信 `FAR`」与「已确认 `FAR` 后仍未触发」。
 */
enum class MissedTriggerCause(val label: String) {
    NO_VERIFIED_FAR("未取得可信 FAR"),
    FAR_CONFIRMED_WITHOUT_TRIGGER("已确认 FAR 后仍未触发"),
}

/**
 * 评分时事件的角色。首条事件决定提前/按时/迟到；其余事件计为额外事件（重复）。
 */
enum class ScoredEventRole(val label: String) {
    FIRST("首条事件"),
    EXTRA("额外事件"),
    FALSE_TRIGGER("误触发事件"),
    UNSCORED("未评分事件"),
}

/**
 * 不进入评分汇总的原因。
 */
enum class ScoreSkipReason(val label: String) {
    UNMARKED("未标记整次试验"),
    MULTIPLE_EXPECTED_MARKERS("存在多个期望触发参考点"),
    MISSING_EXPECTED_MARKER("覆盖范围内缺少期望触发参考点"),
    NO_SCORING_BASIS("缺少评分依据"),
}

/**
 * 一条已按评分规则归类的事件。时刻同时保留 UTC epoch 和 Session 相对毫秒；
 * [offsetFromMarkerMillis] 只在正样本有唯一期望触发标记时给出。
 */
data class ScoredTrialEvent(
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val offsetFromMarkerMillis: Long?,
    val role: ScoredEventRole,
    val type: ProximityEventType,
    val reason: ProximityEventReason,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
)

/**
 * 一套配置在一个 Session 上的评分结果。窗口、人工参考和版本都随结果保留，供界面和现场解释。
 *
 * [windowStartEpochMillis]/[windowEndEpochMillis] 只在正样本有唯一参考点时给出；负样本和跳过评分不设窗口。
 * [coverageCoversWindow] 为 `false` 表示记录在评分窗结束前中断，不能把未记录区间解释成漏触发或成功。
 */
data class TrialScore(
    val sessionId: String,
    val outcome: TrialOutcome,
    val window: ScoringWindow,
    val expectedTriggerAtEpochMillis: Long?,
    val expectedTriggerSessionMillis: Long?,
    val windowStartEpochMillis: Long?,
    val windowEndEpochMillis: Long?,
    val windowStartSessionMillis: Long?,
    val windowEndSessionMillis: Long?,
    val events: List<ScoredTrialEvent>,
    val extraEventCount: Int,
    val falseTriggerCount: Int,
    val missedCause: MissedTriggerCause?,
    val farConfirmedAtEpochMillis: Long?,
    val finalState: ProximityState,
    val finalReason: ProximityStateReason,
    val coverageEndEpochMillis: Long,
    val coverageCoversWindow: Boolean?,
    val coverageIncomplete: Boolean,
    val skipReason: ScoreSkipReason?,
    val skipDetail: String?,
    val diagnostics: List<String>,
) {
    val firstEvent: ScoredTrialEvent?
        get() = events.firstOrNull { event -> event.role == ScoredEventRole.FIRST }

    /** 是否进入评分汇总。观测不足和跳过评分都不推算出准确率。 */
    val includedInSummary: Boolean
        get() = outcome != TrialOutcome.SKIPPED && outcome != TrialOutcome.INSUFFICIENT_OBSERVATION

    /** 剔除原因；进入汇总时为 `null`。 */
    val exclusionReason: String?
        get() = when (outcome) {
            TrialOutcome.SKIPPED -> skipDetail ?: skipReason?.label ?: "缺少评分依据"
            TrialOutcome.INSUFFICIENT_OBSERVATION -> if (coverageCoversWindow == false) {
                "观测不足：记录覆盖未到评分窗终点"
            } else {
                "观测不足：试验提前中断"
            }

            else -> null
        }
}

/**
 * 对比入口可选择的一套结果来源：Session 当次快照，或 06 的某个具名配置。
 */
sealed interface ComparisonTarget {

    val key: String

    /** 使用 Session 保存的当次算法快照。只有存在快照的 Session 才能选择。 */
    data object OriginalSnapshot : ComparisonTarget {
        override val key: String = ORIGINAL_SNAPSHOT_KEY
    }

    /** 使用 06 的具名配置生成新的回放快照。 */
    data class Config(val configId: String) : ComparisonTarget {
        init {
            require(configId.isNotBlank()) { "配置标识不能为空" }
        }

        override val key: String = "config:$configId"
    }

    companion object {
        const val ORIGINAL_SNAPSHOT_KEY: String = "original-snapshot"
    }
}

/**
 * 一套配置在同一个 Session 上的完整结果。回放记录保留配置快照、版本、事件和滤波曲线，
 * [score] 只根据该记录与人工参考算出，不回写实时历史。
 */
data class ComparisonTrialResult(
    val replay: SessionReplayRecord,
    val target: ComparisonTarget,
    val isOriginalRunConfig: Boolean,
    val isCurrentSelection: Boolean,
    val score: TrialScore,
) {
    val config: SessionConfigSnapshot
        get() = replay.config

    val configSource: ReplayConfigSource
        get() = replay.configSource

    val outcome: ReplayOutcome
        get() = replay.outcome

    val versions: ReplayVersionInfo
        get() = replay.versions
}

/** 单套配置无法参与对比的原因，例如配置已删除。 */
data class ComparisonTrialFailure(
    val target: ComparisonTarget,
    val label: String,
    val reason: String,
)

/**
 * 同一 Session 的多配置对比结果。`chart` 使用同一时间轴叠加原始曲线、各配置滤波曲线、
 * 人工参考、评分窗和事件标记；`summary` 给出每套配置纳入评分的 Session 数及剔除原因。
 */
data class SessionComparison(
    val session: SamplingSession,
    val markers: List<SessionReferenceMarker>,
    val evaluation: SessionMarkerEvaluation,
    val window: ScoringWindow,
    val trials: List<ComparisonTrialResult>,
    val failures: List<ComparisonTrialFailure>,
    val chart: RssiChartData,
    val summary: ComparisonScoreSummary,
)

/** 选中 Session 后展示的人工参考与当次配置，不运行回放。 */
data class ComparisonSessionContext(
    val session: SamplingSession,
    val markers: List<SessionReferenceMarker>,
    val evaluation: SessionMarkerEvaluation,
    val configSnapshot: SessionConfigSnapshot?,
)

sealed interface SessionComparisonResult {

    data class Completed(val comparison: SessionComparison) : SessionComparisonResult

    data class Unavailable(
        val sessionId: String,
        val reason: String,
    ) : SessionComparisonResult
}

/**
 * 一套配置跨 Session 的评分汇总。
 *
 * [includedSessionCount] 只统计进入评分的试验；[exclusions] 保留每一条被剔除的 Session 与原因，
 * 未标记、多个参考点、观测不足都不会被算成准确率。
 */
data class ConfigScoreSummary(
    val configSnapshotId: String,
    val configName: String,
    val includedSessionCount: Int,
    val excludedSessionCount: Int,
    val outcomeCounts: Map<TrialOutcome, Int>,
    val extraEventCount: Int,
    val falseTriggerCount: Int,
    val exclusions: List<ComparisonSessionExclusion>,
)

data class ComparisonSessionExclusion(
    val sessionId: String,
    val sessionName: String,
    val outcome: TrialOutcome,
    val reason: String,
)

data class ComparisonScoreSummary(
    val configs: List<ConfigScoreSummary>,
) {
    fun forConfig(configSnapshotId: String): ConfigScoreSummary? =
        configs.firstOrNull { config -> config.configSnapshotId == configSnapshotId }
}
