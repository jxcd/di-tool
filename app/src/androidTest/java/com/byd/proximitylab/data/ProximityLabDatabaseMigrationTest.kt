package com.byd.proximitylab.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.replay.ReplayConfigSource
import com.byd.proximitylab.replay.ReplayInputCoverage
import com.byd.proximitylab.replay.ReplayOutcome
import com.byd.proximitylab.replay.ReplayStatePoint
import com.byd.proximitylab.replay.ReplayVersionInfo
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.replay.data.RoomSessionReplayStore
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.coverageEndEpochMillis
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.TargetAction
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 验证 02 → 04 的真实数据库迁移：v1 的 bound_target 数据保留，新表可写并可再次打开。
 */
@RunWith(AndroidJUnit4::class)
class ProximityLabDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "migration-1-2-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun version1BoundTargetSurvivesMigrationAndSessionTablesAreUsable() = runBlocking {
        val expectedBinding = BoundTarget(
            targetId = "persisted-target",
            displayName = "BYD candidate",
            address = "AA:BB:CC:DD:EE:02",
            boundAtEpochMillis = 1_800_000_000_000L,
            verifiedIdentityToken = "verified-token",
            verifiedIdentityDescription = "迁移测试证据",
            lastAction = TargetAction.ADDRESS_CONTINUED,
            lastActionAtEpochMillis = 1_800_000_100_000L,
        )
        createVersion1Database(expectedBinding)

        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()

        try {
            val bindingStore = RoomTargetBindingStore(database.targetBindingDao())
            assertEquals(expectedBinding, bindingStore.loadBinding())

            val sessionStore = RoomSessionStore(database.samplingSessionDao())
            val session = SamplingSession(
                id = "post-migration-session",
                targetId = expectedBinding.targetId,
                targetDisplayName = expectedBinding.displayName,
                targetAddress = expectedBinding.address,
                samplingType = SamplingType.FIXED_POINT,
                name = "迁移后采样",
                location = FixedSampleLocation.DRIVER_SEAT,
                estimatedDistanceMeters = null,
                phonePlacement = PhonePlacement.HANDHELD,
                status = SessionLifecycleStatus.RECORDING,
                startedAtEpochMillis = 1_800_000_200_000L,
                endedAtEpochMillis = null,
                lastRecordedAtEpochMillis = 1_800_000_200_000L,
                interruptedDetectedAtEpochMillis = null,
            )
            sessionStore.createSession(session)
            sessionStore.appendTimeline(
                session.id,
                SessionTimelineEvent.Observation(
                    observationId = "migration-observation",
                    targetId = expectedBinding.targetId,
                    rawRssiDbm = -68,
                    observedAtEpochMillis = 1_800_000_201_000L,
                    receivedAtEpochMillis = 1_800_000_201_100L,
                    scanFrameId = 123_456L,
                    deliverySequence = 42L,
                    address = expectedBinding.address,
                    payloadFingerprint = "migration-fingerprint",
                    processedAtEpochMillis = 1_800_000_201_100L,
                ),
            )

            assertEquals(session.id, sessionStore.loadSession(session.id)?.id)
            assertEquals(1, sessionStore.loadTimeline(session.id).size)
            assertEquals(
                6,
                database.openHelper.readableDatabase
                    .query("PRAGMA user_version")
                    .use { cursor ->
                        cursor.moveToFirst()
                        cursor.getInt(0)
                    },
            )
            assertEquals(expectedBinding, bindingStore.loadBinding())
        } finally {
            database.close()
        }

        // 关闭重开后，迁移后的绑定和采样数据仍可读取。
        val reopened = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()
        try {
            assertEquals(expectedBinding, RoomTargetBindingStore(reopened.targetBindingDao()).loadBinding())
            assertEquals(
                "post-migration-session",
                RoomSessionStore(reopened.samplingSessionDao()).loadSession("post-migration-session")?.id,
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun version2SessionTimelineSurvivesMigrationAndStaysReadable() = runBlocking {
        createVersion2DatabaseWithSession()

        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()

        try {
            val sessionStore = RoomSessionStore(database.samplingSessionDao())
            val session = requireNotNull(sessionStore.loadSession(V2_SESSION_ID))
            assertEquals(SamplingType.FIXED_POINT, session.samplingType)
            assertEquals(STARTED_AT, session.startedAtEpochMillis)
            assertEquals(STARTED_AT + 3_000L, session.coverageEndEpochMillis)

            val timeline = sessionStore.loadTimeline(V2_SESSION_ID)
            assertEquals(listOf(1L, 2L, 3L), timeline.map { it.sequence })
            assertEquals(
                listOf(-85, -84, -60),
                timeline.map { (it.event as SessionTimelineEvent.Observation).rawRssiDbm },
            )
            assertEquals(
                listOf(STARTED_AT + 1_000L, STARTED_AT + 2_000L, STARTED_AT + 3_000L),
                timeline.map { it.event.processedAtEpochMillis },
            )
            assertNull(sessionStore.loadConfigSnapshot(V2_SESSION_ID))

            val runStore = RoomSimulationRunStore(database.simulationRunDao())
            runStore.appendEvent(
                SessionRunEvent(
                    eventId = "migrated-event",
                    sessionId = V2_SESSION_ID,
                    type = ProximityEventType.ENTERED_RANGE,
                    reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
                    atEpochMillis = STARTED_AT + 3_000L,
                    atSessionMillis = 3_000L,
                    targetId = "v2-target",
                    frameId = 3L,
                    observedAtEpochMillis = STARTED_AT + 2_990L,
                    rawRssiDbm = -60,
                    filteredRssiDbm = -60.0,
                    algorithmVersion = "proximity-algorithms/1",
                    triggerRuleVersion = "confirmed-far-then-near/1",
                ),
            )
            assertEquals(1, runStore.loadEvents(V2_SESSION_ID).size)
            assertEquals(
                6,
                database.openHelper.readableDatabase
                    .query("PRAGMA user_version")
                    .use { cursor ->
                        cursor.moveToFirst()
                        cursor.getInt(0)
                    },
            )
        } finally {
            database.close()
        }

        val reopened = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()
        try {
            val sessionStore = RoomSessionStore(reopened.samplingSessionDao())
            assertEquals(V2_SESSION_ID, sessionStore.loadSession(V2_SESSION_ID)?.id)
            assertEquals(3, sessionStore.loadTimeline(V2_SESSION_ID).size)
            assertEquals(
                1,
                RoomSimulationRunStore(reopened.simulationRunDao()).loadEvents(V2_SESSION_ID).size,
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun version3SessionSnapshotSurvivesMigrationAndAlgorithmConfigTablesAreUsable() = runBlocking {
        createVersion3DatabaseWithSnapshot()

        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()

        try {
            val sessionStore = RoomSessionStore(database.samplingSessionDao())
            val snapshot = requireNotNull(sessionStore.loadConfigSnapshot(V2_SESSION_ID))
            assertEquals("迁移前用户配置", snapshot.name)
            assertEquals("migrated-user-config", snapshot.sourceConfigId)
            assertEquals(ProximityAlgorithm.EMA, snapshot.config.algorithm)
            assertEquals(-62, snapshot.config.enterThresholdDbm)
            assertEquals(0.35, snapshot.config.emaAlpha, 0.0)
            assertEquals(3, sessionStore.loadTimeline(V2_SESSION_ID).size)

            val configStore: AlgorithmConfigStore =
                RoomAlgorithmConfigStore(database.algorithmConfigDao())
            assertEquals(
                listOf("builtin-baseline-v1", "builtin-ema-v1"),
                configStore.observeConfigs().first().map { it.configId },
            )
            val created = configStore.createConfig(
                name = "迁移后配置",
                config = ProximityConfig(
                    algorithm = ProximityAlgorithm.EMA,
                    enterThresholdDbm = -60,
                    exitThresholdDbm = -80,
                    confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
                    sampleValidityMillis = 5_000L,
                    emaAlpha = 0.4,
                ),
            )
            assertEquals(true, configStore.selectConfig(created.configId))
            assertEquals(created.configId, configStore.loadSelectedConfigId())
            assertEquals(
                6,
                database.openHelper.readableDatabase
                    .query("PRAGMA user_version")
                    .use { cursor ->
                        cursor.moveToFirst()
                        cursor.getInt(0)
                    },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun version4SessionAndSnapshotSurviveMigrationAndMarkerTablesAreUsable() = runBlocking {
        createVersion4DatabaseWithSnapshot()

        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()

        try {
            val sessionStore = RoomSessionStore(database.samplingSessionDao())
            val session = requireNotNull(sessionStore.loadSession(V2_SESSION_ID))
            // 旧 Session 保留，并得到默认未标记标签。
            assertEquals(SamplingType.FIXED_POINT, session.samplingType)
            assertEquals(STARTED_AT, session.startedAtEpochMillis)
            assertEquals(SessionEvaluationLabel.UNMARKED, session.evaluationLabel)
            assertEquals(3, sessionStore.loadTimeline(V2_SESSION_ID).size)
            assertEquals(
                listOf(-85, -84, -60),
                sessionStore.loadTimeline(V2_SESSION_ID).map {
                    (it.event as SessionTimelineEvent.Observation).rawRssiDbm
                },
            )
            assertEquals("迁移前用户配置", sessionStore.loadConfigSnapshot(V2_SESSION_ID)?.name)

            val marker = SessionReferenceMarker(
                markerId = "migrated-marker",
                sessionId = V2_SESSION_ID,
                kind = ReferenceMarkerKind.EXPECTED_TRIGGER,
                atEpochMillis = STARTED_AT + 1_500L,
                createdAtEpochMillis = STARTED_AT + 1_500L,
            )
            assertEquals(marker, sessionStore.insertReferenceMarker(marker))
            assertEquals(
                SessionEvaluationLabel.EXPECTED_TRIGGER,
                sessionStore.loadSession(V2_SESSION_ID)?.evaluationLabel,
            )
            assertEquals(
                listOf("migrated-marker"),
                sessionStore.loadReferenceMarkers(V2_SESSION_ID).map { it.markerId },
            )
            assertEquals(
                6,
                database.openHelper.readableDatabase
                    .query("PRAGMA user_version")
                    .use { cursor ->
                        cursor.moveToFirst()
                        cursor.getInt(0)
                    },
            )
        } finally {
            database.close()
        }

        // 关闭重开后，升级前已采记录、快照和升级后标记均仍可读。
        val reopened = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()
        try {
            val sessionStore = RoomSessionStore(reopened.samplingSessionDao())
            assertEquals(V2_SESSION_ID, sessionStore.loadSession(V2_SESSION_ID)?.id)
            assertEquals(3, sessionStore.loadTimeline(V2_SESSION_ID).size)
            assertEquals(1, sessionStore.loadReferenceMarkers(V2_SESSION_ID).size)
            assertEquals(
                SessionEvaluationLabel.EXPECTED_TRIGGER,
                sessionStore.loadSession(V2_SESSION_ID)?.evaluationLabel,
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun version5SessionSnapshotAndMarkersSurviveMigrationAndReplayTablesAreUsable() = runBlocking {
        createVersion5DatabaseWithSession()

        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()

        val savedRecord: SessionReplayRecord
        try {
            val sessionStore = RoomSessionStore(database.samplingSessionDao())
            val session = requireNotNull(sessionStore.loadSession(V2_SESSION_ID))
            assertEquals(SessionEvaluationLabel.EXPECTED_TRIGGER, session.evaluationLabel)
            assertEquals(3, sessionStore.loadTimeline(V2_SESSION_ID).size)
            val snapshot = requireNotNull(sessionStore.loadConfigSnapshot(V2_SESSION_ID))
            assertEquals("迁移前用户配置", snapshot.name)
            assertEquals(
                listOf("v5-marker"),
                sessionStore.loadReferenceMarkers(V2_SESSION_ID).map { it.markerId },
            )
            assertEquals(
                6,
                database.openHelper.readableDatabase
                    .query("PRAGMA user_version")
                    .use { cursor ->
                        cursor.moveToFirst()
                        cursor.getInt(0)
                    },
            )

            val replayStore = RoomSessionReplayStore(database.sessionReplayDao())
            assertEquals(emptyList<SessionReplayRecord>(), replayStore.loadReplays(V2_SESSION_ID))
            savedRecord = replayRecord(session = session, snapshot = snapshot)
            replayStore.saveReplay(savedRecord)
            assertEquals(savedRecord, replayStore.loadReplay(savedRecord.replayId))
            assertEquals(
                listOf(savedRecord.replayId),
                replayStore.loadReplaySummaries(V2_SESSION_ID).map { it.replayId },
            )
        } finally {
            database.close()
        }

        // 关闭重开后，v5 已采 Session、快照、标记和 v6 回放记录仍可按版本查询。
        val reopened = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()
        try {
            val sessionStore = RoomSessionStore(reopened.samplingSessionDao())
            assertEquals(SessionEvaluationLabel.EXPECTED_TRIGGER, sessionStore.loadSession(V2_SESSION_ID)?.evaluationLabel)
            assertEquals(1, sessionStore.loadReferenceMarkers(V2_SESSION_ID).size)
            val replayStore = RoomSessionReplayStore(reopened.sessionReplayDao())
            val loaded = requireNotNull(replayStore.loadReplay(savedRecord.replayId))
            assertEquals(savedRecord, loaded)
            assertEquals(
                savedRecord.versions,
                replayStore.loadReplaySummaries(V2_SESSION_ID).single().versions,
            )
        } finally {
            reopened.close()
        }
    }

    private fun createVersion2DatabaseWithSession() {
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bound_target` (
                    `slot` INTEGER NOT NULL,
                    `targetId` TEXT NOT NULL,
                    `displayName` TEXT,
                    `address` TEXT NOT NULL,
                    `boundAtEpochMillis` INTEGER NOT NULL,
                    `verifiedIdentityToken` TEXT,
                    `verifiedIdentityDescription` TEXT,
                    `lastAction` TEXT NOT NULL,
                    `lastActionAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`slot`)
                )
                """.trimIndent(),
            )
            database.execSQL(
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
            database.execSQL(
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
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_timeline_sessionId` " +
                    "ON `session_timeline` (`sessionId`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_timeline_sessionId_processedAtEpochMillis` " +
                    "ON `session_timeline` (`sessionId`, `processedAtEpochMillis`)",
            )
            database.execSQL(
                "INSERT INTO `sampling_session` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    V2_SESSION_ID,
                    "v2-target",
                    "V2 target",
                    "AA:BB:CC:DD:EE:02",
                    SamplingType.FIXED_POINT.name,
                    "升级前定点采样",
                    FixedSampleLocation.FRONT.name,
                    null,
                    PhonePlacement.HANDHELD.name,
                    SessionLifecycleStatus.COMPLETED.name,
                    STARTED_AT,
                    STARTED_AT + 3_000L,
                    STARTED_AT + 3_000L,
                    null,
                ),
            )
            listOf(
                Triple(1L, STARTED_AT + 1_000L, -85),
                Triple(2L, STARTED_AT + 2_000L, -84),
                Triple(3L, STARTED_AT + 3_000L, -60),
            ).forEach { (sequence, processedAt, rssi) ->
                database.execSQL(
                    "INSERT INTO `session_timeline` (" +
                        "sequence, sessionId, kind, processedAtEpochMillis, observedAtEpochMillis, " +
                        "receivedAtEpochMillis, observationId, targetId, rawRssiDbm, scanFrameId, " +
                        "deliverySequence, address, payloadFingerprint" +
                        ") VALUES (?, ?, 'OBSERVATION', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        sequence,
                        V2_SESSION_ID,
                        processedAt,
                        processedAt - 10L,
                        processedAt - 5L,
                        "v2-observation-$sequence",
                        "v2-target",
                        rssi,
                        sequence,
                        sequence,
                        "AA:BB:CC:DD:EE:02",
                        "v2-fingerprint-$sequence",
                    ),
                )
            }
            database.version = 2
        } finally {
            database.close()
        }
    }

    private fun createVersion3DatabaseWithSnapshot() {
        createVersion2DatabaseWithSession()
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
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
            database.execSQL(
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
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_run_event_sessionId` " +
                    "ON `session_run_event` (`sessionId`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_run_event_sessionId_atSessionMillis` " +
                    "ON `session_run_event` (`sessionId`, `atSessionMillis`)",
            )
            database.execSQL(
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
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_run_state_sessionId` " +
                    "ON `session_run_state` (`sessionId`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_run_state_sessionId_atSessionMillis` " +
                    "ON `session_run_state` (`sessionId`, `atSessionMillis`)",
            )
            database.execSQL(
                "INSERT INTO `session_config_snapshot` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    V2_SESSION_ID,
                    "migrated-user-config@1800000000000",
                    "迁移前用户配置",
                    "migrated-user-config",
                    ProximityAlgorithm.EMA.name,
                    -62,
                    -82,
                    "CONSECUTIVE_SAMPLES",
                    3L,
                    5_000L,
                    0.35,
                    "proximity-algorithms/1",
                    "confirmed-far-then-near/1",
                    1,
                ),
            )
            database.version = 3
        } finally {
            database.close()
        }
    }

    private fun createVersion4DatabaseWithSnapshot() {
        createVersion3DatabaseWithSnapshot()
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
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
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `algorithm_selection` (
                    `slot` INTEGER NOT NULL,
                    `configId` TEXT NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`slot`)
                )
                """.trimIndent(),
            )
            database.version = 4
        } finally {
            database.close()
        }
    }

    private fun createVersion5DatabaseWithSession() {
        createVersion4DatabaseWithSnapshot()
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                "ALTER TABLE `sampling_session` ADD COLUMN `evaluationLabel` TEXT NOT NULL " +
                    "DEFAULT 'UNMARKED'",
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `session_reference_marker` (
                    `markerId` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `atEpochMillis` INTEGER NOT NULL,
                    `note` TEXT,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`markerId`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sampling_session`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_session_reference_marker_sessionId` " +
                    "ON `session_reference_marker` (`sessionId`)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_session_reference_marker_sessionId_atEpochMillis` " +
                    "ON `session_reference_marker` (`sessionId`, `atEpochMillis`)",
            )
            database.execSQL(
                "UPDATE `sampling_session` SET `evaluationLabel` = 'EXPECTED_TRIGGER' WHERE `id` = ?",
                arrayOf<Any?>(V2_SESSION_ID),
            )
            database.execSQL(
                "INSERT INTO `session_reference_marker` VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "v5-marker",
                    V2_SESSION_ID,
                    ReferenceMarkerKind.EXPECTED_TRIGGER.name,
                    STARTED_AT + 1_500L,
                    null,
                    STARTED_AT + 1_500L,
                ),
            )
            database.version = 5
        } finally {
            database.close()
        }
    }

    private fun replayRecord(
        session: SamplingSession,
        snapshot: com.byd.proximitylab.session.SessionConfigSnapshot,
    ): SessionReplayRecord {
        val state = ReplayStatePoint(
            atEpochMillis = session.startedAtEpochMillis,
            atSessionMillis = 0L,
            state = ProximityState.UNKNOWN,
            reason = ProximityStateReason.AWAITING_OBSERVATION,
            trend = MotionTrend.UNKNOWN,
            lastReadingFrameId = null,
            lastReadingObservedAtEpochMillis = null,
            rawRssiDbm = null,
            filteredRssiDbm = null,
        )
        return SessionReplayRecord(
            replayId = "v5-replay",
            sessionId = session.id,
            config = snapshot,
            configSource = ReplayConfigSource.ORIGINAL_SNAPSHOT,
            outcome = ReplayOutcome.REPRODUCED,
            versions = ReplayVersionInfo(
                originalSnapshotAvailable = true,
                originalAlgorithmVersion = snapshot.algorithmVersion,
                originalTriggerRuleVersion = snapshot.triggerRuleVersion,
                snapshotAlgorithmVersion = snapshot.algorithmVersion,
                snapshotTriggerRuleVersion = snapshot.triggerRuleVersion,
                executedAlgorithmVersion = "proximity-algorithms/1",
                executedTriggerRuleVersion = "confirmed-far-then-near/1",
            ),
            coverage = ReplayInputCoverage(
                startedAtEpochMillis = session.startedAtEpochMillis,
                coverageEndEpochMillis = session.coverageEndEpochMillis,
                sessionStatus = session.status,
                interrupted = false,
                hasEventGaps = false,
                droppedEventCount = 0,
                observationCount = 3,
            ),
            samples = emptyList(),
            states = listOf(state),
            events = emptyList(),
            finalState = state,
            createdAtEpochMillis = session.startedAtEpochMillis + 4_000L,
        )
    }

    private fun createVersion1Database(binding: BoundTarget) {
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `bound_target` (
                    `slot` INTEGER NOT NULL,
                    `targetId` TEXT NOT NULL,
                    `displayName` TEXT,
                    `address` TEXT NOT NULL,
                    `boundAtEpochMillis` INTEGER NOT NULL,
                    `verifiedIdentityToken` TEXT,
                    `verifiedIdentityDescription` TEXT,
                    `lastAction` TEXT NOT NULL,
                    `lastActionAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`slot`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO `bound_target` VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    binding.targetId,
                    binding.displayName,
                    binding.address,
                    binding.boundAtEpochMillis,
                    binding.verifiedIdentityToken,
                    binding.verifiedIdentityDescription,
                    binding.lastAction.name,
                    binding.lastActionAtEpochMillis,
                ),
            )
            database.version = 1
        } finally {
            database.close()
        }
    }

    private companion object {
        const val V2_SESSION_ID = "v2-fixed-point"
        const val STARTED_AT = 1_800_000_000_000L
    }
}
