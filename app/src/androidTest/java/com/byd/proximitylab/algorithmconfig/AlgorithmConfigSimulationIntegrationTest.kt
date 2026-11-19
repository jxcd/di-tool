package com.byd.proximitylab.algorithmconfig

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.AppContainer
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import com.byd.proximitylab.ui.monitor.MonitorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 06 与 05 的真实集成：配置存储生成快照后经 `LiveMonitorController.startSimulation` 进入模拟测试。
 *
 * 验证当前生效项、运行中编辑只影响下一次测试、删除当前配置回退默认，以及旧 Session 快照不随配置变化。
 */
@RunWith(AndroidJUnit4::class)
class AlgorithmConfigSimulationIntegrationTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var harness: Harness? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "algorithm-config-simulation-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harness?.close()
        harness = null
        context.deleteDatabase(databaseName)
    }

    @Test
    fun inRunEditOnlyAffectsNextTestAndDeletingCurrentConfigFallsBackToDefault() = runBlocking {
        val test = harness()
        val store = test.configStore

        val originalConfig = ProximityConfig(
            algorithm = ProximityAlgorithm.BASELINE,
            enterThresholdDbm = -70,
            exitThresholdDbm = -90,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        )
        val created = store.createConfig("口袋", originalConfig)
        assertTrue(store.selectConfig(created.configId))

        test.viewModel.startSimulationNow()
        val firstSessionId = requireNotNull(test.controller.state.value.test).session.id
        assertEquals("口袋", test.controller.state.value.test?.config?.name)
        assertEquals(-70, test.controller.state.value.test?.config?.config?.enterThresholdDbm)
        assertEquals(
            "口袋",
            test.sessionStore.loadConfigSnapshot(firstSessionId)?.name,
        )

        // 运行中编辑：测试仍使用开始时的快照，存储中的下一份快照更新。
        val edited = requireNotNull(
            store.updateConfig(
                configId = created.configId,
                name = "口袋改",
                config = originalConfig.copy(enterThresholdDbm = -50),
            ),
        )
        assertEquals(-50, edited.config.enterThresholdDbm)
        assertEquals("口袋", test.controller.state.value.test?.config?.name)
        assertEquals(-70, test.controller.state.value.test?.config?.config?.enterThresholdDbm)
        assertEquals("口袋", test.sessionStore.loadConfigSnapshot(firstSessionId)?.name)

        test.feed(rssi = -60)
        test.feed(rssi = -60)
        waitUntil { test.controller.state.value.test?.decision?.state == ProximityState.NEAR }
        assertTrue(
            "未先确认 FAR 时不应产生 ENTERED_RANGE",
            runBlocking { test.runStore.loadEvents(firstSessionId) }.none {
                it.type == ProximityEventType.ENTERED_RANGE
            },
        )
        test.controller.stopSimulation()
        waitUntil {
            test.recorder.activeSession.value == null &&
                runBlocking { test.sessionStore.loadSession(firstSessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }

        // 下一次测试读取编辑后的配置；-60 dBm 在 enter=-50 时不是 NEAR。
        test.viewModel.startSimulationNow()
        val secondSessionId = requireNotNull(test.controller.state.value.test).session.id
        assertEquals("口袋改", test.controller.state.value.test?.config?.name)
        assertEquals(-50, test.controller.state.value.test?.config?.config?.enterThresholdDbm)
        test.feed(rssi = -60)
        waitUntil {
            test.controller.state.value.test?.decision?.lastObservationAtEpochMillis != null
        }
        test.feed(rssi = -60)
        Thread.sleep(100L)
        assertNotEquals(
            ProximityState.NEAR,
            test.controller.state.value.test?.decision?.state,
        )
        test.controller.stopSimulation()
        waitUntil {
            test.recorder.activeSession.value == null &&
                runBlocking { test.sessionStore.loadSession(secondSessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }

        // 删除当前生效配置，回退到内置 Baseline。
        assertTrue(store.deleteConfig(created.configId))
        assertEquals(BuiltInAlgorithmConfigs.DEFAULT.configId, store.loadSelectedConfigId())
        assertNull(store.loadConfig(created.configId))

        test.viewModel.startSimulationNow()
        val thirdSessionId = requireNotNull(test.controller.state.value.test).session.id
        assertEquals("内置 Baseline", test.controller.state.value.test?.config?.name)
        assertEquals(
            BuiltInSessionConfigs.BASELINE.config,
            test.controller.state.value.test?.config?.config,
        )
        test.feed(rssi = -60)
        test.feed(rssi = -60)
        waitUntil { test.controller.state.value.test?.decision?.state == ProximityState.NEAR }
        test.controller.stopSimulation()
        waitUntil {
            test.recorder.activeSession.value == null &&
                runBlocking { test.sessionStore.loadSession(thirdSessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }

        // 历史 Session 的快照不受后续编辑和删除影响。
        val firstSnapshot = requireNotNull(test.sessionStore.loadConfigSnapshot(firstSessionId))
        assertEquals("口袋", firstSnapshot.name)
        assertEquals(-70, firstSnapshot.config.enterThresholdDbm)
        assertEquals(created.configId, firstSnapshot.sourceConfigId)
        val secondSnapshot = requireNotNull(test.sessionStore.loadConfigSnapshot(secondSessionId))
        assertEquals("口袋改", secondSnapshot.name)
        assertEquals(-50, secondSnapshot.config.enterThresholdDbm)
        assertEquals(created.configId, secondSnapshot.sourceConfigId)
        val thirdSnapshot = requireNotNull(test.sessionStore.loadConfigSnapshot(thirdSessionId))
        assertEquals(BuiltInSessionConfigs.BASELINE, thirdSnapshot)
    }

    @Test
    fun appContainerRestoresSelectionAndParametersAfterRestart() = runBlocking {
        val containerDatabaseName = "algorithm-config-container-${UUID.randomUUID()}.db"
        val first = AppContainer(
            context = context,
            databaseName = containerDatabaseName,
            scanSource = ControllableBleScanSource(),
        )
        val created: AlgorithmConfig
        try {
            created = first.algorithmConfigStore.createConfig(
                name = "口袋",
                config = ProximityConfig(
                    algorithm = ProximityAlgorithm.EMA,
                    enterThresholdDbm = -58,
                    exitThresholdDbm = -82,
                    confirmation = ConfirmationRequirement.Duration(1_500L),
                    sampleValidityMillis = 4_000L,
                    emaAlpha = 0.2,
                ),
            )
            assertTrue(first.algorithmConfigStore.selectConfig(created.configId))
        } finally {
            first.close()
        }

        val second = AppContainer(
            context = context,
            databaseName = containerDatabaseName,
            scanSource = ControllableBleScanSource(),
        )
        try {
            val store = second.algorithmConfigStore
            assertEquals(created.configId, store.loadSelectedConfigId())
            val restored = requireNotNull(store.loadConfig(created.configId))
            assertEquals("口袋", restored.name)
            assertEquals(ProximityAlgorithm.EMA, restored.config.algorithm)
            assertEquals(-58, restored.config.enterThresholdDbm)
            assertEquals(ConfirmationRequirement.Duration(1_500L), restored.config.confirmation)
            assertEquals(0.2, restored.config.emaAlpha, 0.0)

            // 生产容器 + 监控页 ViewModel：模拟测试开始入口读取恢复后的生效配置。
            second.bindingStore.saveBinding(
                testBinding(
                    targetId = TARGET_ID,
                    address = TARGET_ADDRESS,
                    displayName = TARGET_NAME,
                ),
            )
            waitUntil {
                second.targetMonitor.state.value.binding?.targetId == TARGET_ID &&
                    second.liveMonitorController.state.value.binding?.targetId == TARGET_ID
            }
            val monitorViewModel = MonitorViewModel(
                controller = second.liveMonitorController,
                configStore = store,
            )
            monitorViewModel.startSimulationNow()
            val sessionId = requireNotNull(
                second.liveMonitorController.state.value.test,
            ).session.id
            val runningConfig = requireNotNull(second.liveMonitorController.state.value.test?.config)
            assertEquals("口袋", runningConfig.name)
            assertEquals(-58, runningConfig.config.enterThresholdDbm)
            assertEquals(0.2, runningConfig.config.emaAlpha, 0.0)
            assertEquals(
                runningConfig,
                second.sessionStore.loadConfigSnapshot(sessionId),
            )
            second.liveMonitorController.stopSimulation()
            Unit
        } finally {
            second.close()
            context.deleteDatabase(containerDatabaseName)
        }
    }

    private fun harness(): Harness {
        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
        ).build()
        val bindingStore = RoomTargetBindingStore(database.targetBindingDao())
        runBlocking {
            bindingStore.saveBinding(
                testBinding(
                    targetId = TARGET_ID,
                    address = TARGET_ADDRESS,
                    displayName = TARGET_NAME,
                ),
            )
        }
        val source = ControllableBleScanSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        val sessionStore = RoomSessionStore(database.samplingSessionDao())
        val runStore = RoomSimulationRunStore(database.simulationRunDao())
        val recorder = SessionRecorder(
            store = sessionStore,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "algorithm-simulation-${UUID.randomUUID()}" },
        )
        val controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = sessionStore,
            simulationStore = runStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = 0L,
        )
        val configStore = com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore(
            dao = database.algorithmConfigDao(),
            clock = { now },
        )
        val viewModel = MonitorViewModel(controller, configStore)
        waitUntil {
            monitor.state.value.binding?.targetId == TARGET_ID &&
                controller.state.value.binding?.targetId == TARGET_ID
        }
        return Harness(
            database = database,
            source = source,
            scope = scope,
            sessionStore = sessionStore,
            runStore = runStore,
            recorder = recorder,
            controller = controller,
            configStore = configStore,
            viewModel = viewModel,
        ).also { harness = it }
    }

    private var now: Long = 1_800_000_000_000L
    private var sequence: Long = 0L

    private inner class Harness(
        private val database: ProximityLabDatabase,
        private val source: ControllableBleScanSource,
        private val scope: CoroutineScope,
        val sessionStore: SessionStore,
        val runStore: RoomSimulationRunStore,
        val recorder: SessionRecorder,
        val controller: LiveMonitorController,
        val configStore: AlgorithmConfigStore,
        val viewModel: MonitorViewModel,
    ) {
        fun feed(rssi: Int, advanceMillis: Long = 1_000L) {
            now += advanceMillis
            sequence += 1
            val emitted = source.emit(
                testAdvertisement(
                    address = TARGET_ADDRESS,
                    rssi = rssi,
                    observedAtEpochMillis = now - 20L,
                    receivedAtEpochMillis = now - 5L,
                    observedAtNanos = sequence * 1_000_000_000L,
                    receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                    deliverySequence = sequence,
                    payloadFingerprint = "algorithm-config-frame-$sequence",
                ),
            )
            assertTrue("扫描事件缓冲拒绝投递", emitted)
        }

        fun close() {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    private fun waitUntil(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(25L)
        }
        assertTrue("等待条件超时（${timeoutMillis}ms）", condition())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val TARGET_ID = "algorithm-config-target"
        const val TARGET_ADDRESS = "AA:BB:CC:DD:EE:66"
        const val TARGET_NAME = "算法配置集成目标"
    }
}
