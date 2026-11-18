package com.byd.proximitylab.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.coverageEndEpochMillis
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.TargetAction
import com.byd.proximitylab.target.data.RoomTargetBindingStore
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
                3,
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
                3,
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
