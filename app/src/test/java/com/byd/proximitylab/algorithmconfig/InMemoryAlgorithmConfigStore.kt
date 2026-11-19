package com.byd.proximitylab.algorithmconfig

import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.session.SessionConfigSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * JVM 单元测试用的配置存储。行为与 Room 实现对齐，真实持久化由 androidTest 的 Room 用例验证。
 */
class InMemoryAlgorithmConfigStore(
    initialConfigs: List<AlgorithmConfig> = emptyList(),
    initialSelectedConfigId: String = BuiltInAlgorithmConfigs.DEFAULT.configId,
    private val clock: () -> Long = { 1_000L },
) : AlgorithmConfigStore {

    private val userConfigs = MutableStateFlow(initialConfigs)
    private val selectedConfigId = MutableStateFlow(initialSelectedConfigId)
    private var nextId = 1

    override fun observeConfigs(): Flow<List<AlgorithmConfig>> =
        userConfigs.map { configs -> BuiltInAlgorithmConfigs.ALL + configs }

    override fun observeSelectedConfigId(): Flow<String> =
        combine(selectedConfigId, userConfigs) { selected, configs ->
            if (loadConfigBlocking(selected, configs) != null) {
                selected
            } else {
                BuiltInAlgorithmConfigs.DEFAULT.configId
            }
        }.distinctUntilChanged()

    override suspend fun loadConfig(configId: String): AlgorithmConfig? =
        BuiltInAlgorithmConfigs.find(configId) ?: userConfigs.value.firstOrNull {
            it.configId == configId
        }

    override suspend fun loadSelectedConfigId(): String =
        selectedConfigId.value.takeIf { loadConfig(it) != null }
            ?: BuiltInAlgorithmConfigs.DEFAULT.configId

    override suspend fun loadSelectedConfig(): AlgorithmConfig =
        loadConfig(loadSelectedConfigId()) ?: BuiltInAlgorithmConfigs.DEFAULT

    override suspend fun createConfig(name: String, config: ProximityConfig): AlgorithmConfig {
        val trimmed = validatedName(name)
        val now = clock()
        val created = AlgorithmConfig(
            configId = "user-config-${nextId++}",
            name = trimmed,
            config = config,
            builtIn = false,
            updatedAtEpochMillis = now,
        )
        userConfigs.update { it + created }
        return created
    }

    override suspend fun updateConfig(
        configId: String,
        name: String,
        config: ProximityConfig,
    ): AlgorithmConfig? {
        val existing = userConfigs.value.firstOrNull { it.configId == configId } ?: return null
        val updated = existing.copy(
            name = validatedName(name),
            config = config,
            updatedAtEpochMillis = maxOf(clock(), existing.updatedAtEpochMillis + 1L),
        )
        userConfigs.update { configs ->
            configs.map { if (it.configId == configId) updated else it }
        }
        return updated
    }

    override suspend fun duplicateConfig(sourceConfigId: String, name: String): AlgorithmConfig? {
        val source = loadConfig(sourceConfigId) ?: return null
        return createConfig(name.trim().ifEmpty { "${source.name} 副本" }, source.config)
    }

    override suspend fun deleteConfig(configId: String): Boolean {
        if (BuiltInAlgorithmConfigs.find(configId) != null) {
            return false
        }
        val existing = userConfigs.value.firstOrNull { it.configId == configId } ?: return false
        userConfigs.update { configs -> configs.filterNot { it.configId == configId } }
        selectedConfigId.update { selected ->
            if (selected == existing.configId) {
                BuiltInAlgorithmConfigs.DEFAULT.configId
            } else {
                selected
            }
        }
        return true
    }

    override suspend fun selectConfig(configId: String): Boolean {
        if (loadConfig(configId) == null) {
            return false
        }
        selectedConfigId.value = configId
        return true
    }

    override suspend fun snapshotForNextSimulation(): SessionConfigSnapshot {
        val selected = loadSelectedConfig()
        return selected.toSessionSnapshot(snapshotIdOf(selected))
    }

    private fun loadConfigBlocking(
        configId: String,
        userConfigs: List<AlgorithmConfig>,
    ): AlgorithmConfig? = BuiltInAlgorithmConfigs.find(configId)
        ?: userConfigs.firstOrNull { it.configId == configId }

    private fun validatedName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "配置名不能为空" }
        require(trimmed.length <= AlgorithmConfigValidator.MAX_NAME_LENGTH) {
            "配置名不能超过 ${AlgorithmConfigValidator.MAX_NAME_LENGTH} 个字符"
        }
        return trimmed
    }
}
