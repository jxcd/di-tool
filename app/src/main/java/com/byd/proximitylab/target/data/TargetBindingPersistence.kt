package com.byd.proximitylab.target.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.TargetAction
import com.byd.proximitylab.target.TargetBindingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 单绑定目标表。V1 固定一个槽位，改绑覆盖同一行。
 */
@Entity(tableName = "bound_target")
data class BoundTargetEntity(
    @PrimaryKey val slot: Int = SINGLETON_SLOT,
    val targetId: String,
    val displayName: String?,
    val address: String,
    val boundAtEpochMillis: Long,
    val verifiedIdentityToken: String?,
    val verifiedIdentityDescription: String?,
    val lastAction: String,
    val lastActionAtEpochMillis: Long,
) {
    fun toDomain(): BoundTarget = BoundTarget(
        targetId = targetId,
        displayName = displayName,
        address = address,
        boundAtEpochMillis = boundAtEpochMillis,
        verifiedIdentityToken = verifiedIdentityToken,
        verifiedIdentityDescription = verifiedIdentityDescription,
        lastAction = runCatching { TargetAction.valueOf(lastAction) }.getOrDefault(TargetAction.BOUND),
        lastActionAtEpochMillis = lastActionAtEpochMillis,
    )

    companion object {
        const val SINGLETON_SLOT: Int = 1

        fun fromDomain(binding: BoundTarget): BoundTargetEntity = BoundTargetEntity(
            targetId = binding.targetId,
            displayName = binding.displayName,
            address = binding.address,
            boundAtEpochMillis = binding.boundAtEpochMillis,
            verifiedIdentityToken = binding.verifiedIdentityToken,
            verifiedIdentityDescription = binding.verifiedIdentityDescription,
            lastAction = binding.lastAction.name,
            lastActionAtEpochMillis = binding.lastActionAtEpochMillis,
        )
    }
}

@Dao
interface TargetBindingDao {

    @Query("SELECT * FROM bound_target WHERE slot = 1")
    fun observeBinding(): Flow<BoundTargetEntity?>

    @Query("SELECT * FROM bound_target WHERE slot = 1")
    suspend fun loadBinding(): BoundTargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBinding(entity: BoundTargetEntity)

    @Query("DELETE FROM bound_target")
    suspend fun clearBinding()
}

/**
 * 绑定目标的 Room 表、DAO 和存储适配。数据库本身是 [com.byd.proximitylab.data.ProximityLabDatabase]，
 * 04 及后续切片在同一实例上增加实体并迁移，05/11 通过同一个 AppContainer 复用。
 */
class RoomTargetBindingStore(
    private val dao: TargetBindingDao,
) : TargetBindingStore {

    override fun observeBinding(): Flow<BoundTarget?> =
        dao.observeBinding().map { entity -> entity?.toDomain() }

    override suspend fun loadBinding(): BoundTarget? = dao.loadBinding()?.toDomain()

    override suspend fun saveBinding(binding: BoundTarget) {
        dao.saveBinding(BoundTargetEntity.fromDomain(binding))
    }

    override suspend fun clearBinding() {
        dao.clearBinding()
    }
}
