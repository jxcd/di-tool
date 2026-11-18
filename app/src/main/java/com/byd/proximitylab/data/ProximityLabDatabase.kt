package com.byd.proximitylab.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.byd.proximitylab.target.data.BoundTargetEntity
import com.byd.proximitylab.target.data.TargetBindingDao

/**
 * 全 App 唯一的 Room 数据库。
 *
 * 02 在这里落地绑定目标表；04 增加 Session 等实体时提升 [version] 并提供 Migration，沿用同一数据库和文件。
 * 后续切片不要新建第二个 Room 数据库，也不能用 destructive fallback 丢弃已有绑定。
 */
@Database(
    entities = [BoundTargetEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ProximityLabDatabase : RoomDatabase() {
    abstract fun targetBindingDao(): TargetBindingDao

    companion object {
        const val NAME: String = "proximity-lab.db"
    }
}
