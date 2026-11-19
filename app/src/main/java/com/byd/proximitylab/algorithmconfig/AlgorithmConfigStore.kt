package com.byd.proximitylab.algorithmconfig

import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.session.SessionConfigSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 算法配置与当前生效项的本地存储入口。
 *
 * 内置 Baseline/EMA 不写入数据库，由 [BuiltInAlgorithmConfigs] 固定提供；用户配置保存在 04 的
 * [com.byd.proximitylab.data.ProximityLabDatabase] 中。删除当前生效的用户配置时必须回退到
 * [BuiltInAlgorithmConfigs.DEFAULT]，因此界面和测试都总能获得一个可用配置。
 *
 * 配置快照由 [snapshotForNextSimulation] 在测试开始时生成；快照不引用配置行，后续编辑或删除配置
 * 不会改变已保存的 Session 快照。10 的比较入口复用同一方法与同一生效项逻辑。
 */
interface AlgorithmConfigStore {

    /** 内置 Baseline、EMA 在前，用户配置按创建顺序在后。 */
    fun observeConfigs(): Flow<List<AlgorithmConfig>>

    /** 当前生效配置标识。选中项失效时回退到 [BuiltInAlgorithmConfigs.DEFAULT]。 */
    fun observeSelectedConfigId(): Flow<String>

    suspend fun loadConfig(configId: String): AlgorithmConfig?

    suspend fun loadSelectedConfigId(): String

    suspend fun loadSelectedConfig(): AlgorithmConfig

    /**
     * 新建用户配置。[name] 会去掉首尾空白并拒绝空名称；[config] 必须已通过 03 的参数校验。
     */
    suspend fun createConfig(name: String, config: ProximityConfig): AlgorithmConfig

    /**
     * 更新用户配置的参数和名称，返回更新后的配置；内置配置或已删除配置返回 `null`。
     */
    suspend fun updateConfig(
        configId: String,
        name: String,
        config: ProximityConfig,
    ): AlgorithmConfig?

    /**
     * 以现有配置（包括内置项）为模板复制出新的用户配置。源配置不存在时返回 `null`。
     */
    suspend fun duplicateConfig(sourceConfigId: String, name: String): AlgorithmConfig?

    /**
     * 删除用户配置。删除当前生效项时在同一事务中回退到 [BuiltInAlgorithmConfigs.DEFAULT]。
     * 内置配置返回 `false`。
     */
    suspend fun deleteConfig(configId: String): Boolean

    /** 选为下一次模拟测试的生效配置。配置不存在时返回 `false`。 */
    suspend fun selectConfig(configId: String): Boolean

    /**
     * 为下一次模拟测试生成固定快照。内置配置使用自身标识；用户配置使用
     * `configId@updatedAtEpochMillis` 标识这一版参数。
     */
    suspend fun snapshotForNextSimulation(): SessionConfigSnapshot

    /**
     * 生成用户配置的稳定快照标识。内置配置和用户配置都可用，测试与 10 可直接复用。
     */
    fun snapshotIdOf(config: AlgorithmConfig): String = config.defaultSnapshotId()
}
