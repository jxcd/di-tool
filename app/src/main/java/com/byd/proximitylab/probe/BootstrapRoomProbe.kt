package com.byd.proximitylab.probe

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 仅用于票据 01 验证 Room + KSP2 实际生成代码的临时探针。
 * 正式数据库与迁移由票据 04 落地，落地时应替换或移除本探针。
 */
@Entity(tableName = "bootstrap_probe")
data class BootstrapProbeEntity(
    @PrimaryKey val id: Long,
    val note: String,
)

@Dao
interface BootstrapProbeDao {

    @Insert
    suspend fun insert(entity: BootstrapProbeEntity)

    @Query("SELECT * FROM bootstrap_probe ORDER BY id")
    suspend fun findAll(): List<BootstrapProbeEntity>
}

@Database(
    entities = [BootstrapProbeEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BootstrapProbeDatabase : RoomDatabase() {
    abstract fun bootstrapProbeDao(): BootstrapProbeDao
}
