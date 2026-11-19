package com.byd.proximitylab.algorithmconfig.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.data.RoomSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 06 的真实 Room 配置存储：内置项、创建/复制/编辑、选择、删除回退和快照独立于配置行。
 */
@RunWith(AndroidJUnit4::class)
class AlgorithmConfigRoomStoreTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: ProximityLabDatabase? = null
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "algorithm-config-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun builtInsAreAlwaysAvailableAndDefaultIsSelectedInitially() = runBlocking {
        val store = newStore()

        val configs = store.observeConfigs().first()
        assertEquals(
            listOf("builtin-baseline-v1", "builtin-ema-v1"),
            configs.map { it.configId },
        )
        assertTrue(configs.all { it.builtIn })
        assertEquals(BuiltInAlgorithmConfigs.DEFAULT.configId, store.observeSelectedConfigId().first())
        assertEquals(BuiltInAlgorithmConfigs.DEFAULT, store.loadSelectedConfig())
        assertEquals(BuiltInSessionConfigs.BASELINE, store.snapshotForNextSimulation())
    }

    @Test
    fun createTwoConfigsCopyOneEditAndReopenRestoresSelectionAndParameters() = runBlocking {
        val store = newStore()
        val first = store.createConfig(
            name = "配置甲",
            config = baselineConfig(enter = -60, exit = -80, samples = 3),
        )
        val second = store.createConfig(
            name = "配置乙",
            config = emaConfig(enter = -55, exit = -75, durationMillis = 1_500L, alpha = 0.3),
        )
        val copy = requireNotNull(store.duplicateConfig(BuiltInAlgorithmConfigs.EMA.configId, "口袋"))
        val edited = requireNotNull(
            store.updateConfig(
                configId = copy.configId,
                name = "口袋",
                config = copy.config.copy(emaAlpha = 0.2, enterThresholdDbm = -58),
            ),
        )

        assertEquals("配置甲", first.name)
        assertEquals("配置乙", second.name)
        assertEquals("口袋", edited.name)
        assertEquals(0.2, edited.config.emaAlpha, 0.0)
        assertEquals(3, store.observeConfigs().first().count { !it.builtIn })
        assertEquals(0.5, store.loadConfig(BuiltInAlgorithmConfigs.EMA.configId)!!.config.emaAlpha, 0.0)

        assertTrue(store.selectConfig(edited.configId))
        assertEquals(edited.configId, store.observeSelectedConfigId().first())

        // 模拟 App 重启：关闭数据库后以同一文件重开。
        closeDatabase()
        val reopened = newStore()
        assertEquals(edited.configId, reopened.observeSelectedConfigId().first())
        val restored = requireNotNull(reopened.loadConfig(edited.configId))
        assertEquals("口袋", restored.name)
        assertEquals(0.2, restored.config.emaAlpha, 0.0)
        assertEquals(-58, restored.config.enterThresholdDbm)
        assertEquals(ProximityAlgorithm.EMA, restored.config.algorithm)
        assertEquals(3, reopened.observeConfigs().first().count { !it.builtIn })
    }

    @Test
    fun deletingCurrentConfigFallsBackToBuiltInDefault() = runBlocking {
        val store = newStore()
        val config = store.createConfig("口袋", baselineConfig(enter = -60, exit = -80))
        store.selectConfig(config.configId)
        assertEquals(config.configId, store.observeSelectedConfigId().first())

        assertTrue(store.deleteConfig(config.configId))

        assertEquals(BuiltInAlgorithmConfigs.DEFAULT.configId, store.observeSelectedConfigId().first())
        assertEquals(BuiltInAlgorithmConfigs.DEFAULT, store.loadSelectedConfig())
        assertNull(store.loadConfig(config.configId))
        val remaining = store.observeConfigs().first()
        assertEquals(
            listOf("builtin-baseline-v1", "builtin-ema-v1"),
            remaining.map { it.configId },
        )
        // 内置项在删除后仍可选择。
        assertTrue(store.selectConfig(BuiltInAlgorithmConfigs.EMA.configId))
        assertEquals(BuiltInAlgorithmConfigs.EMA.configId, store.observeSelectedConfigId().first())
    }

    @Test
    fun deletingNonSelectedConfigKeepsCurrentSelection() = runBlocking {
        val store = newStore()
        val selected = store.createConfig("生效", baselineConfig(enter = -60, exit = -80))
        val other = store.createConfig("其他", baselineConfig(enter = -58, exit = -78))
        store.selectConfig(selected.configId)

        assertTrue(store.deleteConfig(other.configId))

        assertEquals(selected.configId, store.observeSelectedConfigId().first())
        assertNotNull(store.loadConfig(selected.configId))
        assertNull(store.loadConfig(other.configId))
    }

    @Test
    fun sessionSnapshotSurvivesConfigEditAndDelete() = runBlocking {
        val store = newStore()
        val config = store.createConfig("口袋", baselineConfig(enter = -70, exit = -90, samples = 2))
        store.selectConfig(config.configId)
        val snapshot = store.snapshotForNextSimulation()
        assertEquals("口袋", snapshot.name)
        assertEquals(config.configId, snapshot.sourceConfigId)
        assertEquals(-70, snapshot.config.enterThresholdDbm)

        val sessionStore = RoomSessionStore(requireNotNull(database).samplingSessionDao())
        sessionStore.createSimulationSession(
            simulationSession(),
            snapshot,
        )

        val edited = requireNotNull(
            store.updateConfig(
                configId = config.configId,
                name = "口袋改",
                config = config.config.copy(enterThresholdDbm = -50),
            ),
        )
        assertEquals(-50, edited.config.enterThresholdDbm)
        assertTrue(store.deleteConfig(config.configId))

        val stored = requireNotNull(sessionStore.loadConfigSnapshot(SESSION_ID))
        assertEquals(snapshot, stored)
        assertEquals("口袋", stored.name)
        assertEquals(-70, stored.config.enterThresholdDbm)
        assertEquals(config.configId, stored.sourceConfigId)
        assertNull(store.loadConfig(config.configId))
    }

    @Test
    fun builtInConfigsCannotBeMutatedAndBlankNamesAreRejected() = runBlocking {
        val store = newStore()
        val baseline = BuiltInAlgorithmConfigs.BASELINE

        assertNull(
            store.updateConfig(
                baseline.configId,
                "改名",
                baseline.config,
            ),
        )
        assertFalse(store.deleteConfig(baseline.configId))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.createConfig("   ", baseline.config) }
        }
        assertEquals(2, store.observeConfigs().first().size)
    }

    private fun newStore(): AlgorithmConfigStore {
        val db = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
        ).build()
        database = db
        return RoomAlgorithmConfigStore(
            dao = db.algorithmConfigDao(),
            clock = { now++ },
        )
    }

    private fun closeDatabase() {
        database?.close()
        database = null
    }

    private fun baselineConfig(
        enter: Int,
        exit: Int,
        samples: Int = 2,
    ): ProximityConfig = ProximityConfig(
        algorithm = ProximityAlgorithm.BASELINE,
        enterThresholdDbm = enter,
        exitThresholdDbm = exit,
        confirmation = ConfirmationRequirement.ConsecutiveSamples(samples),
        sampleValidityMillis = 5_000L,
        emaAlpha = 0.5,
    )

    private fun emaConfig(
        enter: Int,
        exit: Int,
        durationMillis: Long,
        alpha: Double,
    ): ProximityConfig = ProximityConfig(
        algorithm = ProximityAlgorithm.EMA,
        enterThresholdDbm = enter,
        exitThresholdDbm = exit,
        confirmation = ConfirmationRequirement.Duration(durationMillis),
        sampleValidityMillis = 6_000L,
        emaAlpha = alpha,
    )

    private fun simulationSession(): SamplingSession = SamplingSession(
        id = SESSION_ID,
        targetId = "config-test-target",
        targetDisplayName = "配置测试目标",
        targetAddress = "AA:BB:CC:DD:EE:06",
        samplingType = SamplingType.SIMULATION,
        name = "配置快照测试",
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.RECORDING,
        startedAtEpochMillis = 1_800_000_100_000L,
        endedAtEpochMillis = null,
        lastRecordedAtEpochMillis = 1_800_000_100_000L,
        interruptedDetectedAtEpochMillis = null,
    )

    private companion object {
        const val SESSION_ID = "algorithm-config-session"
    }
}
