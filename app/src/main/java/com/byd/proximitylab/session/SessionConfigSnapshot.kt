package com.byd.proximitylab.session

import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEngine

/**
 * 一次模拟测试开始时固定的算法与参数快照。
 *
 * 快照独立于用户配置保存，因此原配置被改名或删除后仍能解释当次结果，也能作为 09 回放的输入。
 * [snapshotId] 标识这组参数；[sourceConfigId] 指向 06 的用户配置，内置配置为 `null`。
 * [algorithmVersion] 与 [triggerRuleVersion] 记录产生当次结果时使用的实现版本。
 */
data class SessionConfigSnapshot(
    val snapshotId: String,
    val name: String,
    val sourceConfigId: String?,
    val config: ProximityConfig,
    val algorithmVersion: String = ProximityEngine.ALGORITHM_VERSION,
    val triggerRuleVersion: String = ProximityEngine.TRIGGER_RULE_VERSION,
    val schemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
) {
    init {
        require(snapshotId.isNotBlank()) { "配置快照标识不能为空" }
        require(name.isNotBlank()) { "配置快照名称不能为空" }
        require(algorithmVersion.isNotBlank()) { "算法版本不能为空" }
        require(triggerRuleVersion.isNotBlank()) { "触发规则版本不能为空" }
        require(schemaVersion > 0) { "快照结构版本必须大于 0：$schemaVersion" }
    }

    companion object {
        /** 当前快照持久化结构版本。字段变化时由后续票提升。 */
        const val SNAPSHOT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * 05 可用的内置配置。06 将在此基础上提供可编辑的具名配置和当前选择。
 *
 * 参数只是 V1 的初始默认值，不构成本票对真实车辆阈值的推断；现场标定由 13/14 完成。
 */
object BuiltInSessionConfigs {

    val BASELINE: SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "builtin-baseline-v1",
        name = "内置 Baseline",
        sourceConfigId = null,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.BASELINE,
            enterThresholdDbm = -65,
            exitThresholdDbm = -80,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        ),
    )

    val EMA: SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "builtin-ema-v1",
        name = "内置 EMA",
        sourceConfigId = null,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.EMA,
            enterThresholdDbm = -65,
            exitThresholdDbm = -80,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        ),
    )

    /** 监控和模拟测试在用户尚未选择配置时使用的默认项。 */
    val DEFAULT: SessionConfigSnapshot = BASELINE
}
