package com.byd.proximitylab.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.TargetAction
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        ).addMigrations(ProximityLabDatabase.MIGRATION_1_2).build()

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
                2,
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
        ).addMigrations(ProximityLabDatabase.MIGRATION_1_2).build()
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
}
