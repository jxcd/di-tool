package com.byd.proximitylab.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.byd.proximitylab.session.data.SamplingSessionDao
import com.byd.proximitylab.session.data.SamplingSessionEntity
import com.byd.proximitylab.session.data.SessionTimelineEntity
import com.byd.proximitylab.target.data.BoundTargetEntity
import com.byd.proximitylab.target.data.TargetBindingDao

/**
 * 全 App 唯一的 Room 数据库。
 *
 * 02 在这里落地绑定目标表；04 提升到版本 2 并增量添加采样 Session 与时间线表。迁移保留已有绑定和已采数据，
 * 后续切片不要新建第二个 Room 数据库，也不能用 destructive fallback 丢弃历史记录。
 */
@Database(
    entities = [
        BoundTargetEntity::class,
        SamplingSessionEntity::class,
        SessionTimelineEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ProximityLabDatabase : RoomDatabase() {
    abstract fun targetBindingDao(): TargetBindingDao

    abstract fun samplingSessionDao(): SamplingSessionDao

    companion object {
        const val NAME: String = "proximity-lab.db"

        /**
         * 02 → 04：保留 `bound_target`，新增 Session 与时间线表。
         *
         * SQL 必须与 Room 导出的 schema 一致；`app/schemas` 中的 2.json 由后续迁移测试复用。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sampling_session` (
                        `id` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `targetDisplayName` TEXT,
                        `targetAddress` TEXT NOT NULL,
                        `samplingType` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `location` TEXT NOT NULL,
                        `estimatedDistanceMeters` REAL,
                        `phonePlacement` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `startedAtEpochMillis` INTEGER NOT NULL,
                        `endedAtEpochMillis` INTEGER,
                        `lastRecordedAtEpochMillis` INTEGER NOT NULL,
                        `interruptedDetectedAtEpochMillis` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_timeline` (
                        `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `processedAtEpochMillis` INTEGER NOT NULL,
                        `observedAtEpochMillis` INTEGER,
                        `receivedAtEpochMillis` INTEGER,
                        `observationId` TEXT,
                        `targetId` TEXT,
                        `rawRssiDbm` INTEGER,
                        `scanFrameId` INTEGER,
                        `deliverySequence` INTEGER,
                        `address` TEXT,
                        `payloadFingerprint` TEXT,
                        `scanAvailable` INTEGER,
                        `scanStateLabel` TEXT,
                        `detail` TEXT,
                        `identityConfirmed` INTEGER,
                        `identityStatusLabel` TEXT,
                        `identityBasisLabel` TEXT,
                        `droppedEvents` INTEGER,
                        FOREIGN KEY(`sessionId`) REFERENCES `sampling_session`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_timeline_sessionId` " +
                        "ON `session_timeline` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_timeline_sessionId_processedAtEpochMillis` " +
                        "ON `session_timeline` (`sessionId`, `processedAtEpochMillis`)",
                )
            }
        }
    }
}
