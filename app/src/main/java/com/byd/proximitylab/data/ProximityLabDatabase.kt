package com.byd.proximitylab.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.byd.proximitylab.algorithmconfig.data.AlgorithmConfigDao
import com.byd.proximitylab.algorithmconfig.data.AlgorithmConfigEntity
import com.byd.proximitylab.algorithmconfig.data.AlgorithmSelectionEntity
import com.byd.proximitylab.session.data.SamplingSessionDao
import com.byd.proximitylab.session.data.SamplingSessionEntity
import com.byd.proximitylab.session.data.SessionConfigSnapshotEntity
import com.byd.proximitylab.session.data.SessionTimelineEntity
import com.byd.proximitylab.simulation.data.SessionRunEventEntity
import com.byd.proximitylab.simulation.data.SessionRunStateEntity
import com.byd.proximitylab.simulation.data.SimulationRunDao
import com.byd.proximitylab.target.data.BoundTargetEntity
import com.byd.proximitylab.target.data.TargetBindingDao

/**
 * 全 App 唯一的 Room 数据库。
 *
 * 02 在这里落地绑定目标表；04 提升到版本 2 并增量添加采样 Session 与时间线表；05 提升到版本 3，
 * 增加模拟测试配置快照、事件和状态结果；06 提升到版本 4，增加用户算法配置和当前选择。
 * 迁移保留已有绑定、已采数据和已保存的配置快照，后续切片不要新建第二个 Room 数据库，
 * 也不能用 destructive fallback 丢弃历史记录。
 */
@Database(
    entities = [
        BoundTargetEntity::class,
        SamplingSessionEntity::class,
        SessionTimelineEntity::class,
        SessionConfigSnapshotEntity::class,
        SessionRunEventEntity::class,
        SessionRunStateEntity::class,
        AlgorithmConfigEntity::class,
        AlgorithmSelectionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ProximityLabDatabase : RoomDatabase() {
    abstract fun targetBindingDao(): TargetBindingDao

    abstract fun samplingSessionDao(): SamplingSessionDao

    abstract fun simulationRunDao(): SimulationRunDao

    abstract fun algorithmConfigDao(): AlgorithmConfigDao

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

        /**
         * 04 → 05：保留已有 Session 与时间线，新增模拟测试配置快照、事件和运行状态。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_config_snapshot` (
                        `sessionId` TEXT NOT NULL,
                        `snapshotId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sourceConfigId` TEXT,
                        `algorithm` TEXT NOT NULL,
                        `enterThresholdDbm` INTEGER NOT NULL,
                        `exitThresholdDbm` INTEGER NOT NULL,
                        `confirmationKind` TEXT NOT NULL,
                        `confirmationValue` INTEGER NOT NULL,
                        `sampleValidityMillis` INTEGER NOT NULL,
                        `emaAlpha` REAL NOT NULL,
                        `algorithmVersion` TEXT NOT NULL,
                        `triggerRuleVersion` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sampling_session`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_run_event` (
                        `eventId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `atEpochMillis` INTEGER NOT NULL,
                        `atSessionMillis` INTEGER NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `frameId` INTEGER NOT NULL,
                        `observedAtEpochMillis` INTEGER NOT NULL,
                        `rawRssiDbm` INTEGER NOT NULL,
                        `filteredRssiDbm` REAL NOT NULL,
                        `algorithmVersion` TEXT NOT NULL,
                        `triggerRuleVersion` TEXT NOT NULL,
                        `feedbackStatus` TEXT NOT NULL,
                        `feedbackDetail` TEXT,
                        `feedbackUpdatedAtEpochMillis` INTEGER,
                        PRIMARY KEY(`eventId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sampling_session`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_run_event_sessionId` " +
                        "ON `session_run_event` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_run_event_sessionId_atSessionMillis` " +
                        "ON `session_run_event` (`sessionId`, `atSessionMillis`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_run_state` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `atEpochMillis` INTEGER NOT NULL,
                        `atSessionMillis` INTEGER NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `trend` TEXT NOT NULL,
                        `lastReadingFrameId` INTEGER,
                        `lastReadingObservedAtEpochMillis` INTEGER,
                        `rawRssiDbm` INTEGER,
                        `filteredRssiDbm` REAL,
                        `algorithmVersion` TEXT NOT NULL,
                        `triggerRuleVersion` TEXT NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `sampling_session`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_run_state_sessionId` " +
                        "ON `session_run_state` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_run_state_sessionId_atSessionMillis` " +
                        "ON `session_run_state` (`sessionId`, `atSessionMillis`)",
                )
            }
        }

        /**
         * 05 → 06：保留已有 Session、时间线和配置快照，新增用户算法配置与当前选择。
         *
         * 内置 Baseline/EMA 不落表；选择表只保存单行当前生效标识，内置项也可作为标识保存。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `algorithm_config` (
                        `configId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `algorithm` TEXT NOT NULL,
                        `enterThresholdDbm` INTEGER NOT NULL,
                        `exitThresholdDbm` INTEGER NOT NULL,
                        `confirmationKind` TEXT NOT NULL,
                        `confirmationValue` INTEGER NOT NULL,
                        `sampleValidityMillis` INTEGER NOT NULL,
                        `emaAlpha` REAL NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`configId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `algorithm_selection` (
                        `slot` INTEGER NOT NULL,
                        `configId` TEXT NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`slot`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
