package com.byd.proximitylab.comparison

import com.byd.proximitylab.session.SamplingSession

/**
 * 一个 Session 在全部配置上的评分结果，供 [ComparisonSummaryBuilder] 跨 Session 汇总。
 */
data class ScoredSessionTrials(
    val session: SamplingSession,
    val trials: List<ComparisonTrialResult>,
)

/**
 * 按配置快照汇总评分结果。
 *
 * 只有 [TrialScore.includedInSummary] 为 `true` 的试验计入 `includedSessionCount` 和分类计数；
 * 未标记、多个参考点、观测不足都会进入 [ConfigScoreSummary.exclusions] 并保留原因，不会被算成准确率。
 */
object ComparisonSummaryBuilder {

    fun build(sessions: List<ScoredSessionTrials>): ComparisonScoreSummary {
        val accumulators = linkedMapOf<String, ConfigAccumulator>()
        sessions.forEach { scored ->
            scored.trials.forEach { trial ->
                val accumulator = accumulators.getOrPut(trial.config.snapshotId) {
                    ConfigAccumulator(
                        configSnapshotId = trial.config.snapshotId,
                        configName = trial.config.name,
                    )
                }
                accumulator.configName = trial.config.name
                val score = trial.score
                if (score.includedInSummary) {
                    accumulator.includedSessionCount += 1
                    accumulator.outcomeCounts.merge(score.outcome, 1, Int::plus)
                    accumulator.extraEventCount += score.extraEventCount
                    accumulator.falseTriggerCount += score.falseTriggerCount
                } else {
                    accumulator.excludedSessionCount += 1
                    accumulator.exclusions += ComparisonSessionExclusion(
                        sessionId = scored.session.id,
                        sessionName = scored.session.name,
                        outcome = score.outcome,
                        reason = score.exclusionReason ?: "未纳入评分",
                    )
                }
            }
        }
        return ComparisonScoreSummary(
            configs = accumulators.values.map { accumulator ->
                ConfigScoreSummary(
                    configSnapshotId = accumulator.configSnapshotId,
                    configName = accumulator.configName,
                    includedSessionCount = accumulator.includedSessionCount,
                    excludedSessionCount = accumulator.excludedSessionCount,
                    outcomeCounts = accumulator.outcomeCounts.toMap(),
                    extraEventCount = accumulator.extraEventCount,
                    falseTriggerCount = accumulator.falseTriggerCount,
                    exclusions = accumulator.exclusions.toList(),
                )
            },
        )
    }

    private data class ConfigAccumulator(
        val configSnapshotId: String,
        var configName: String,
        var includedSessionCount: Int = 0,
        var excludedSessionCount: Int = 0,
        val outcomeCounts: MutableMap<TrialOutcome, Int> = linkedMapOf(),
        var extraEventCount: Int = 0,
        var falseTriggerCount: Int = 0,
        val exclusions: MutableList<ComparisonSessionExclusion> = mutableListOf(),
    )
}
