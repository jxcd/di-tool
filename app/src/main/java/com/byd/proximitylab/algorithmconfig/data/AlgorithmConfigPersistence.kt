package com.byd.proximitylab.algorithmconfig.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.byd.proximitylab.algorithmconfig.AlgorithmConfig
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigValidator
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.session.SessionConfigSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 用户算法配置。内置 Baseline/EMA 不落表，避免内置项被误编辑或删除。
 */
@Entity(tableName = "algorithm_config")
data class AlgorithmConfigEntity(
    @PrimaryKey val configId: String,
    val name: String,
    val algorithm: String,
    val enterThresholdDbm: Int,
    val exitThresholdDbm: Int,
    val confirmationKind: String,
    val confirmationValue: Long,
    val sampleValidityMillis: Long,
    val emaAlpha: Double,
    @ColumnInfo(name = "createdAtEpochMillis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updatedAtEpochMillis") val updatedAtEpochMillis: Long,
) {
    fun toDomain(): AlgorithmConfig = AlgorithmConfig(
        configId = configId,
        name = name,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.valueOf(algorithm),
            enterThresholdDbm = enterThresholdDbm,
            exitThresholdDbm = exitThresholdDbm,
            confirmation = when (confirmationKind) {
                KIND_CONSECUTIVE_SAMPLES ->
                    ConfirmationRequirement.ConsecutiveSamples(confirmationValue.toInt())

                KIND_DURATION -> ConfirmationRequirement.Duration(confirmationValue)
                else -> error("未知算法配置确认方式：$confirmationKind")
            },
            sampleValidityMillis = sampleValidityMillis,
            emaAlpha = emaAlpha,
        ),
        builtIn = false,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    companion object {
        const val KIND_CONSECUTIVE_SAMPLES: String = "CONSECUTIVE_SAMPLES"
        const val KIND_DURATION: String = "DURATION"

        fun fromDomain(
            config: AlgorithmConfig,
            createdAtEpochMillis: Long,
        ): AlgorithmConfigEntity {
            val (kind, value) = when (val confirmation = config.config.confirmation) {
                is ConfirmationRequirement.ConsecutiveSamples ->
                    KIND_CONSECUTIVE_SAMPLES to confirmation.minimumSamples.toLong()

                is ConfirmationRequirement.Duration ->
                    KIND_DURATION to confirmation.minimumDurationMillis
            }
            return AlgorithmConfigEntity(
                configId = config.configId,
                name = config.name,
                algorithm = config.config.algorithm.name,
                enterThresholdDbm = config.config.enterThresholdDbm,
                exitThresholdDbm = config.config.exitThresholdDbm,
                confirmationKind = kind,
                confirmationValue = value,
                sampleValidityMillis = config.config.sampleValidityMillis,
                emaAlpha = config.config.emaAlpha,
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = config.updatedAtEpochMillis,
            )
        }
    }
}

/**
 * 当前生效配置选择。单行表，[slot] 固定为 [SINGLETON_SLOT]；内置配置也以标识形式保存在这里。
 */
@Entity(tableName = "algorithm_selection")
data class AlgorithmSelectionEntity(
    @PrimaryKey val slot: Int = SINGLETON_SLOT,
    val configId: String,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        const val SINGLETON_SLOT: Int = 1
    }
}

@Dao
interface AlgorithmConfigDao {

    @Query("SELECT * FROM algorithm_config ORDER BY createdAtEpochMillis, configId")
    fun observeConfigs(): Flow<List<AlgorithmConfigEntity>>

    @Query("SELECT * FROM algorithm_config WHERE configId = :configId")
    suspend fun loadConfig(configId: String): AlgorithmConfigEntity?

    @Insert
    suspend fun insertConfig(entity: AlgorithmConfigEntity)

    @Update
    suspend fun updateConfig(entity: AlgorithmConfigEntity): Int

    @Query("DELETE FROM algorithm_config WHERE configId = :configId")
    suspend fun deleteConfig(configId: String): Int

    @Query("SELECT * FROM algorithm_selection WHERE slot = ${AlgorithmSelectionEntity.SINGLETON_SLOT}")
    fun observeSelection(): Flow<AlgorithmSelectionEntity?>

    @Query("SELECT * FROM algorithm_selection WHERE slot = ${AlgorithmSelectionEntity.SINGLETON_SLOT}")
    suspend fun loadSelection(): AlgorithmSelectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSelection(entity: AlgorithmSelectionEntity)

    @Query(
        "UPDATE algorithm_selection SET configId = :fallbackConfigId, " +
            "updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE slot = ${AlgorithmSelectionEntity.SINGLETON_SLOT} AND configId = :deletedConfigId",
    )
    suspend fun fallbackSelection(
        deletedConfigId: String,
        fallbackConfigId: String,
        updatedAtEpochMillis: Long,
    )

    /**
     * 同一事务内删除用户配置并在它正好是当前生效项时回退，避免出现选中已被删除配置的中间状态。
     */
    @Transaction
    suspend fun deleteConfigAndFallback(
        configId: String,
        fallbackConfigId: String,
        updatedAtEpochMillis: Long,
    ) {
        deleteConfig(configId)
        fallbackSelection(
            deletedConfigId = configId,
            fallbackConfigId = fallbackConfigId,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }
}

/**
 * 算法配置的 Room 实现，复用 04/05 的同一数据库。
 */
class RoomAlgorithmConfigStore(
    private val dao: AlgorithmConfigDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : AlgorithmConfigStore {

    override fun observeConfigs(): Flow<List<AlgorithmConfig>> =
        dao.observeConfigs().map { entities ->
            BuiltInAlgorithmConfigs.ALL + entities.map { it.toDomain() }
        }

    override fun observeSelectedConfigId(): Flow<String> =
        combine(dao.observeSelection(), dao.observeConfigs()) { selection, userConfigs ->
            val selectedId = selection?.configId
            when {
                selectedId == null -> BuiltInAlgorithmConfigs.DEFAULT.configId
                BuiltInAlgorithmConfigs.find(selectedId) != null -> selectedId
                userConfigs.any { it.configId == selectedId } -> selectedId
                else -> BuiltInAlgorithmConfigs.DEFAULT.configId
            }
        }.distinctUntilChanged()

    override suspend fun loadConfig(configId: String): AlgorithmConfig? =
        BuiltInAlgorithmConfigs.find(configId) ?: dao.loadConfig(configId)?.toDomain()

    override suspend fun loadSelectedConfigId(): String {
        val selectedId = dao.loadSelection()?.configId ?: return BuiltInAlgorithmConfigs.DEFAULT.configId
        return if (loadConfig(selectedId) != null) {
            selectedId
        } else {
            BuiltInAlgorithmConfigs.DEFAULT.configId
        }
    }

    override suspend fun loadSelectedConfig(): AlgorithmConfig =
        loadConfig(loadSelectedConfigId()) ?: BuiltInAlgorithmConfigs.DEFAULT

    override suspend fun createConfig(
        name: String,
        config: ProximityConfig,
    ): AlgorithmConfig {
        val validName = validatedName(name)
        val now = clock()
        val entity = AlgorithmConfigEntity.fromDomain(
            config = AlgorithmConfig(
                configId = idGenerator(),
                name = validName,
                config = config,
                builtIn = false,
                updatedAtEpochMillis = now,
            ),
            createdAtEpochMillis = now,
        )
        dao.insertConfig(entity)
        return entity.toDomain()
    }

    override suspend fun updateConfig(
        configId: String,
        name: String,
        config: ProximityConfig,
    ): AlgorithmConfig? {
        val existing = dao.loadConfig(configId) ?: return null
        val validName = validatedName(name)
        // 时钟回拨或同一毫秒内连续保存时仍保证配置版本递增，快照标识不会重复。
        val updatedAt = maxOf(clock(), existing.updatedAtEpochMillis + 1L)
        val updated = AlgorithmConfigEntity.fromDomain(
            config = AlgorithmConfig(
                configId = configId,
                name = validName,
                config = config,
                builtIn = false,
                updatedAtEpochMillis = updatedAt,
            ),
            createdAtEpochMillis = existing.createdAtEpochMillis,
        )
        dao.updateConfig(updated)
        return updated.toDomain()
    }

    override suspend fun duplicateConfig(sourceConfigId: String, name: String): AlgorithmConfig? {
        val source = loadConfig(sourceConfigId) ?: return null
        val copyName = name.trim().ifEmpty { "${source.name} 副本" }
        return createConfig(copyName, source.config)
    }

    override suspend fun deleteConfig(configId: String): Boolean {
        // 内置配置不在表中；loadConfig 会把它们解析出来，但这里必须先确认是用户配置。
        if (BuiltInAlgorithmConfigs.find(configId) != null) {
            return false
        }
        val existing = dao.loadConfig(configId) ?: return false
        dao.deleteConfigAndFallback(
            configId = existing.configId,
            fallbackConfigId = BuiltInAlgorithmConfigs.DEFAULT.configId,
            updatedAtEpochMillis = clock(),
        )
        return true
    }

    override suspend fun selectConfig(configId: String): Boolean {
        if (loadConfig(configId) == null) {
            return false
        }
        dao.upsertSelection(
            AlgorithmSelectionEntity(
                configId = configId,
                updatedAtEpochMillis = clock(),
            ),
        )
        return true
    }

    override suspend fun snapshotForNextSimulation(): SessionConfigSnapshot {
        val selected = loadSelectedConfig()
        return selected.toSessionSnapshot(snapshotIdOf(selected))
    }

    private fun validatedName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "配置名不能为空" }
        require(trimmed.length <= AlgorithmConfigValidator.MAX_NAME_LENGTH) {
            "配置名不能超过 ${AlgorithmConfigValidator.MAX_NAME_LENGTH} 个字符"
        }
        return trimmed
    }
}
