package com.byd.proximitylab.algorithmconfig

import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.SessionConfigSnapshot

/**
 * 一组具名算法参数。内置配置来自 [BuiltInAlgorithmConfigs]，用户配置由 [AlgorithmConfigStore] 管理。
 *
 * [builtIn] 为 `true` 时不可编辑或删除，但可以被复制和选为生效项。[updatedAtEpochMillis] 是用户配置的
 * 最后一次保存时刻；内置配置固定为 0。
 */
data class AlgorithmConfig(
    val configId: String,
    val name: String,
    val config: ProximityConfig,
    val builtIn: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(configId.isNotBlank()) { "配置标识不能为空" }
        require(name.isNotBlank()) { "配置名不能为空" }
        require(updatedAtEpochMillis >= 0L) {
            "配置更新时间不能为负数：$updatedAtEpochMillis"
        }
    }

    /**
     * 这一版参数的稳定标识。内置配置沿用内置标识；用户配置带上更新时间，编辑后得到新的版本标识。
     */
    fun defaultSnapshotId(): String =
        if (builtIn) {
            configId
        } else {
            "$configId@$updatedAtEpochMillis"
        }

    /**
     * 生成一次模拟测试开始时的独立快照。
     *
     * 快照只保存名称和参数本身，不引用用户配置行；之后改名、编辑或删除原配置都不会改变快照。
     * 内置配置的 [SessionConfigSnapshot.sourceConfigId] 为 `null`，用户配置保留其 [configId]。
     */
    fun toSessionSnapshot(snapshotId: String = defaultSnapshotId()): SessionConfigSnapshot =
        SessionConfigSnapshot(
            snapshotId = snapshotId,
            name = name,
            sourceConfigId = if (builtIn) null else configId,
            config = config,
        )
}

/**
 * 内置算法配置。参数沿用 05 已固定的默认值，内置项不可编辑，但可以复制为用户配置。
 */
object BuiltInAlgorithmConfigs {

    val BASELINE: AlgorithmConfig = AlgorithmConfig(
        configId = BuiltInSessionConfigs.BASELINE.snapshotId,
        name = BuiltInSessionConfigs.BASELINE.name,
        config = BuiltInSessionConfigs.BASELINE.config,
        builtIn = true,
        updatedAtEpochMillis = 0L,
    )

    val EMA: AlgorithmConfig = AlgorithmConfig(
        configId = BuiltInSessionConfigs.EMA.snapshotId,
        name = BuiltInSessionConfigs.EMA.name,
        config = BuiltInSessionConfigs.EMA.config,
        builtIn = true,
        updatedAtEpochMillis = 0L,
    )

    val ALL: List<AlgorithmConfig> = listOf(BASELINE, EMA)

    /** 删除当前用户配置或选择缺失时使用的默认项。 */
    val DEFAULT: AlgorithmConfig = BASELINE

    fun find(configId: String): AlgorithmConfig? = ALL.firstOrNull { it.configId == configId }
}
