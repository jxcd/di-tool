package com.byd.proximitylab.ui.monitor

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStartRequest
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.session.durationMillis
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import com.byd.proximitylab.ui.theme.ProximityLabTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 07 实时监控页全链路：真实 Room、真实目标观测入口、真实 SessionRecorder/判定、真实事件存储和 Compose 页面。
 *
 * 覆盖状态区分、时间窗与抽点、模拟事件历史跨页面重建保留，以及普通监控不产生模拟事件。
 */
@RunWith(AndroidJUnit4::class)
class MonitorChartHistoryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private val harnesses = mutableListOf<Harness>()

    @Volatile
    private var now = 1_800_000_000_000L

    private var sequence = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "monitor-chart-history-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harnesses.forEach { harness -> harness.close() }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun distinguishesNoBindingScanUnavailableNoSamplesFarAndExpired() {
        val harness = harness(persistBinding = false)
        var deviceEntries = 0
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(
                        viewModel = harness.viewModel,
                        onOpenDevice = { deviceEntries += 1 },
                    )
                }
            }
        }
        waitForTag("monitor_no_binding")
        composeRule.onNodeWithTag("monitor_status")
            .assertTextContains("未绑定目标", substring = true)
        composeRule.onNodeWithTag("monitor_start_hint").assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_open_device").performClick()
        assertEquals(1, deviceEntries)

        runBlocking {
            harness.bindingStore.saveBinding(
                testBinding(
                    targetId = TARGET_ID,
                    address = ADDRESS,
                    displayName = TARGET_NAME,
                ),
            )
        }
        waitForTag("monitor_binding")

        harness.source.setScanState(ScanAvailability.BluetoothOff)
        waitUntil {
            harness.controller.state.value.scanState is ScanAvailability.BluetoothOff
        }
        composeRule.onNodeWithTag("monitor_scan_state")
            .assertTextContains("蓝牙已关闭", substring = true)
        composeRule.onNodeWithTag("monitor_status")
            .assertTextContains("扫描不可用", substring = true)
        composeRule.onNodeWithTag("monitor_status_detail")
            .assertTextContains("空窗", substring = true)

        harness.source.setScanState(ScanAvailability.Stopped)
        waitUntil {
            harness.controller.state.value.scanState is ScanAvailability.Stopped
        }
        composeRule.onNodeWithTag("monitor_status")
            .assertTextContains("扫描已停止", substring = true)

        harness.source.setScanState(ScanAvailability.Scanning)
        waitUntil {
            harness.controller.state.value.scanState is ScanAvailability.Scanning
        }
        composeRule.onNodeWithTag("monitor_status")
            .assertTextContains("尚无目标观测", substring = true)

        harness.feed(rssi = -85)
        harness.feed(rssi = -85)
        waitUntil {
            harness.controller.state.value.monitor.state == ProximityState.FAR
        }
        composeRule.onNodeWithTag("monitor_status")
            .assertTextContains("已确认 FAR", substring = true)
        composeRule.onNodeWithTag("monitor_state")
            .assertTextContains("FAR", substring = true)
        composeRule.onNodeWithTag("monitor_raw_rssi")
            .assertTextContains("-85", substring = true)
        composeRule.onNodeWithTag("monitor_observation_interval")
            .assertTextContains("1000 ms", substring = true)
        composeRule.onNodeWithTag("monitor_sample_count")
            .assertTextContains("2", substring = true)
        composeRule.onNodeWithTag("monitor_chart_summary")
            .assertTextContains("进入 -65 dBm", substring = true)
        composeRule.onNodeWithTag("monitor_chart_summary")
            .assertTextContains("离开 -80 dBm", substring = true)
        composeRule.onNodeWithTag("monitor_chart_enter_threshold")
            .assertTextContains("-65 dBm", substring = true)
        composeRule.onNodeWithTag("monitor_chart_exit_threshold")
            .assertTextContains("-80 dBm", substring = true)

        now += 6_000L
        runBlocking { harness.controller.refreshNow(now) }
        waitUntil {
            harness.controller.state.value.monitor.reason == ProximityStateReason.SAMPLES_EXPIRED
        }
        composeRule.onNodeWithTag("monitor_state")
            .assertTextContains("UNKNOWN", substring = true)
        composeRule.onNodeWithTag("monitor_status")
            .assertTextContains("样本已过期", substring = true)
        // 过期后判定不再持有最近读数，但仍应显示最后观测时刻和间隔。
        composeRule.onNodeWithTag("monitor_last_observation")
            .assertTextContains("最后观测：20", substring = true)
        composeRule.onNodeWithTag("monitor_observation_interval")
            .assertTextContains("1000 ms", substring = true)
        // 过期只改变状态；已有曲线仍可查看，空窗与有效 FAR 明确区分。
        composeRule.onNodeWithTag("monitor_chart_summary")
            .assertTextContains("原始 RSSI 2/2 点", substring = true)
        composeRule.onAllNodesWithTag("monitor_chart_empty").fetchSemanticsNodes().let { nodes ->
            assertTrue("过期后窗口内仍有旧点，不应显示空窗提示", nodes.isEmpty())
        }

        // 扫描不可用时保留原因和已有曲线；未观测不能被解释为已远离。
        harness.source.setScanState(ScanAvailability.BluetoothOff)
        waitUntil {
            harness.controller.state.value.scanState is ScanAvailability.BluetoothOff
        }
        composeRule.onNodeWithTag("monitor_status")
            .assertTextContains("扫描不可用", substring = true)
        composeRule.onNodeWithTag("monitor_chart_summary")
            .assertTextContains("原始 RSSI 2/2 点", substring = true)
    }

    @Test
    fun activeSamplingSessionShowsHintAndBlocksSimulationStart() {
        val harness = harness(persistBinding = true)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = harness.viewModel)
                }
            }
        }
        waitForTag("monitor_binding")

        runBlocking {
            harness.recorder.start(
                SessionStartRequest(
                    binding = testBinding(
                        targetId = TARGET_ID,
                        address = ADDRESS,
                        displayName = TARGET_NAME,
                    ),
                    samplingType = SamplingType.FIXED_POINT,
                    name = "已有定点采样",
                    location = FixedSampleLocation.DRIVER_SEAT,
                    estimatedDistanceMeters = null,
                    phonePlacement = PhonePlacement.POCKET,
                ),
            )
        }
        waitUntil {
            harness.controller.state.value.activeSession?.samplingType ==
                SamplingType.FIXED_POINT
        }
        composeRule.onNodeWithTag("monitor_start_hint")
            .performScrollTo()
            .assertTextContains("已有采集中 Session", substring = true)

        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        waitUntil {
            harness.controller.state.value.message?.contains("已有采集中 Session") == true
        }
        composeRule.onNodeWithTag("monitor_message")
            .assertTextContains("已有采集中 Session", substring = true)
        assertNull(harness.controller.state.value.test)

        runBlocking { harness.recorder.stop() }
    }

    @Test
    fun denseSamplesAreDecimatedInChartWhileRoomKeepsAllAndWindowSwitchOnlyDisplay() {
        val harness = harness(persistBinding = true)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = harness.viewModel)
                }
            }
        }
        waitForTag("monitor_binding")

        // 密集样本用更长的样本有效期启动，避免测试处理速度落后于注入时钟时把观测判成过期；
        // 判定、时间线写入和图表抽点仍走生产实现。
        val denseConfig = BuiltInSessionConfigs.DEFAULT.copy(
            snapshotId = "monitor-dense-samples",
            name = "密集样本",
            config = BuiltInSessionConfigs.DEFAULT.config.copy(
                sampleValidityMillis = 120_000L,
            ),
        )
        runBlocking { harness.controller.startSimulation(denseConfig) }
        waitUntil { harness.controller.state.value.simulationRunning }
        val sessionId = requireNotNull(harness.controller.state.value.test).session.id

        repeat(160) { index ->
            harness.feed(rssi = if (index < 4) -85 else -60, advanceMillis = 200L)
            // 等本帧真正进入判定与时间线后再推进注入时钟，使曲线时间间隔与注入节奏一致。
            waitUntil(timeoutMillis = 10_000L) {
                harness.controller.state.value.test?.samples?.size == index + 1
            }
        }
        waitUntil(timeoutMillis = 60_000L) {
            runBlocking {
                harness.sessionStore.loadTimeline(sessionId)
                    .count { item -> item.event is SessionTimelineEvent.Observation } == 160
            }
        }
        waitUntil(timeoutMillis = 30_000L) {
            harness.viewModel.chartState.value.sourceSampleCount == 160
        }

        val chartBefore = harness.viewModel.chartState.value
        assertEquals(RssiChartWindow.ONE_MINUTE, chartBefore.window)
        assertEquals(160, chartBefore.sourceSampleCount)
        assertTrue(chartBefore.displayedSampleCount in 4..120)
        assertTrue(chartBefore.displayedSampleCount < chartBefore.sourceSampleCount)
        assertEquals(
            160,
            runBlocking {
                harness.sessionStore.loadTimeline(sessionId)
                    .count { item -> item.event is SessionTimelineEvent.Observation }
            },
        )

        // 30 秒窗口：只裁剪展示范围并重新抽点，Room 原始观测保持不变。
        composeRule.onNodeWithTag("monitor_window_30s").performScrollTo().performClick()
        waitUntil { harness.viewModel.chartState.value.window == RssiChartWindow.THIRTY_SECONDS }
        val thirty = harness.viewModel.chartState.value
        assertEquals(160, thirty.sourceSampleCount)
        assertTrue(thirty.windowSampleCount in 150..151)
        assertTrue(thirty.displayedSampleCount <= 120)
        assertEquals(
            160,
            runBlocking {
                harness.sessionStore.loadTimeline(sessionId)
                    .count { item -> item.event is SessionTimelineEvent.Observation }
            },
        )
        composeRule.onNodeWithTag("monitor_chart_summary")
            .assertTextContains("窗口 30 秒", substring = true)

        composeRule.onNodeWithTag("monitor_window_300s").performScrollTo().performClick()
        waitUntil { harness.viewModel.chartState.value.window == RssiChartWindow.FIVE_MINUTES }
        assertEquals(160, harness.viewModel.chartState.value.windowSampleCount)
        assertEquals(
            160,
            runBlocking {
                harness.sessionStore.loadTimeline(sessionId)
                    .count { item -> item.event is SessionTimelineEvent.Observation }
            },
        )

        composeRule.onNodeWithTag("monitor_stop_test").performScrollTo().performClick()
        waitUntil {
            runBlocking { harness.sessionStore.loadSession(sessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }
    }

    @Test
    fun simulationEventHistoryPersistsAcrossReopenAndNotificationStatusStaysSeparate() {
        val first = harness(persistBinding = true)
        var currentViewModel by mutableStateOf(first.viewModel)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = currentViewModel)
                }
            }
        }
        waitForTag("monitor_binding")

        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        waitUntil { first.controller.state.value.simulationRunning }
        val sessionId = requireNotNull(first.controller.state.value.test).session.id

        first.feed(rssi = -85)
        first.feed(rssi = -85)
        first.feed(rssi = -60)
        first.feed(rssi = -60)
        waitUntil { first.viewModel.eventHistory.value.entries.size == 1 }
        waitUntil {
            runBlocking { first.runStore.loadEvents(sessionId) }.size == 1
        }
        val event = runBlocking { first.runStore.loadEvents(sessionId) }.single()
        assertEquals(SimulatedEventFeedbackStatus.NOT_ATTEMPTED, event.feedbackStatus)

        composeRule.onNodeWithTag("monitor_simulated_badge")
            .assertTextContains("模拟", substring = true)
        composeRule.onNodeWithTag("monitor_history_count")
            .assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("monitor_history_event_${event.eventId}")
            .assertTextContains("CONFIRMED_NEAR_AFTER_CONFIRMED_FAR", substring = true)
        composeRule.onNodeWithTag("monitor_history_event_${event.eventId}")
            .assertTextContains("-60", substring = true)
        composeRule.onNodeWithTag("monitor_history_event_${event.eventId}")
            .assertTextContains(TARGET_NAME, substring = true)
        composeRule.onNodeWithTag("monitor_history_config")
            .assertTextContains("内置 Baseline", substring = true)
        composeRule.onNodeWithTag("monitor_history_config")
            .assertTextContains("proximity-algorithms/1", substring = true)
        composeRule.onNodeWithTag("monitor_history_config")
            .assertTextContains("confirmed-far-then-near/1", substring = true)
        composeRule.onNodeWithTag("monitor_history_notification_${event.eventId}")
            .assertTextContains("尚未尝试", substring = true)

        composeRule.onNodeWithTag("monitor_window_30s").performScrollTo().performClick()
        waitUntil { first.viewModel.chartState.value.window == RssiChartWindow.THIRTY_SECONDS }
        composeRule.onNodeWithTag("monitor_chart_marker_${event.eventId}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("monitor_chart_summary")
            .assertTextContains("标记 1", substring = true)
        assertEquals(1, runBlocking { first.runStore.loadEvents(sessionId) }.size)

        // 停留在 NEAR 不追加事件；旧事件只显示一次。
        first.feed(rssi = -60)
        first.feed(rssi = -60)
        waitUntil { first.controller.state.value.test?.samples?.size == 6 }
        composeRule.waitForIdle()
        assertEquals(1, runBlocking { first.runStore.loadEvents(sessionId) }.size)
        assertEquals(1, first.viewModel.eventHistory.value.entries.size)

        composeRule.onNodeWithTag("monitor_stop_test").performScrollTo().performClick()
        waitUntil {
            runBlocking { first.sessionStore.loadSession(sessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }
        val completed = requireNotNull(runBlocking { first.sessionStore.loadSession(sessionId) })
        assertEquals(SamplingType.SIMULATION, completed.samplingType)
        assertTrue(completed.durationMillis > 0L)

        // 重新进入监控页：新的 ViewModel 从同一个 Room 和事件存储重建历史，
        // 不依赖控制器中的旧展示状态，也不再次产生事件。
        val reopenedViewModel = MonitorViewModel(
            controller = first.controller,
            configStore = null,
            sessionStore = first.sessionStore,
            simulationStore = first.runStore,
        )
        first.track(reopenedViewModel)
        composeRule.runOnUiThread {
            currentViewModel = reopenedViewModel
        }
        waitUntil { reopenedViewModel.eventHistory.value.entries.size == 1 }
        composeRule.onNodeWithTag("monitor_history_count")
            .performScrollTo()
            .assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("monitor_history_event_${event.eventId}")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(1, runBlocking { first.runStore.loadEvents(sessionId) }.size)
        assertEquals(
            BuiltInSessionConfigs.DEFAULT,
            runBlocking { first.sessionStore.loadConfigSnapshot(sessionId) },
        )
        assertNull(first.recorder.activeSession.value)
        // 页面重建后重新订阅不会补发事件。
        assertEquals(1, reopenedViewModel.eventHistory.value.entries.size)
    }

    @Test
    fun normalMonitorDoesNotCreateSimulationHistoryOrEventMarkers() {
        val harness = harness(persistBinding = true)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = harness.viewModel)
                }
            }
        }
        waitForTag("monitor_binding")

        harness.feed(rssi = -85)
        harness.feed(rssi = -85)
        harness.feed(rssi = -60)
        harness.feed(rssi = -60)
        waitUntil {
            harness.controller.state.value.monitor.state == ProximityState.NEAR
        }

        assertNull(harness.controller.state.value.test)
        val hasSimulationSession = runBlocking {
            harness.sessionStore.observeSessions().first().any { summary ->
                summary.session.samplingType == SamplingType.SIMULATION
            }
        }
        assertFalse(hasSimulationSession)
        composeRule.onNodeWithTag("monitor_history_empty")
            .assertTextContains("普通监控不会产生模拟事件或通知", substring = true)
        composeRule.onNodeWithTag("monitor_chart_mode")
            .assertTextContains("普通监控", substring = true)
        composeRule.onNodeWithTag("monitor_chart_summary")
            .assertTextContains("标记 0", substring = true)
        assertTrue(
            composeRule.onAllNodesWithTag("monitor_test").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun waitForTag(tag: String) {
        waitUntil {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntil(
        timeoutMillis: Long = TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ) {
        composeRule.waitUntil(timeoutMillis, condition)
    }

    private fun harness(persistBinding: Boolean): Harness {
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
        if (persistBinding) {
            runBlocking {
                bindingStore.saveBinding(
                    testBinding(
                        targetId = TARGET_ID,
                        address = ADDRESS,
                        displayName = TARGET_NAME,
                    ),
                )
            }
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
            idGenerator = { "monitor-chart-session-${UUID.randomUUID()}" },
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
        val viewModel = MonitorViewModel(
            controller = controller,
            configStore = null,
            sessionStore = sessionStore,
            simulationStore = runStore,
        )
        return Harness(
            database = database,
            bindingStore = bindingStore,
            source = source,
            scope = scope,
            sessionStore = sessionStore,
            runStore = runStore,
            recorder = recorder,
            controller = controller,
            viewModel = viewModel,
        ).also { harness -> harnesses += harness }
    }

    private inner class Harness(
        private val database: ProximityLabDatabase,
        val bindingStore: RoomTargetBindingStore,
        val source: ControllableBleScanSource,
        private val scope: CoroutineScope,
        val sessionStore: RoomSessionStore,
        val runStore: RoomSimulationRunStore,
        val recorder: SessionRecorder,
        val controller: LiveMonitorController,
        val viewModel: MonitorViewModel,
    ) {
        private val viewModels = mutableListOf(viewModel)

        fun track(viewModel: MonitorViewModel) {
            viewModels += viewModel
        }

        fun feed(rssi: Int, advanceMillis: Long = 1_000L) {
            now += advanceMillis
            sequence += 1
            var attempts = 0
            while (true) {
                val emitted = source.emit(
                    testAdvertisement(
                        address = ADDRESS,
                        rssi = rssi,
                        observedAtEpochMillis = now - 20L,
                        receivedAtEpochMillis = now - 5L,
                        observedAtNanos = sequence * 1_000_000_000L,
                        receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                        deliverySequence = sequence,
                        payloadFingerprint = "monitor-chart-frame-$sequence",
                    ),
                )
                if (emitted) {
                    return
                }
                // 后台采集和 Room 写入较慢时，等待事件缓冲腾出空间后重投同一帧。
                attempts += 1
                assertTrue("扫描事件缓冲持续拒绝投递", attempts < 400)
                Thread.sleep(5L)
            }
        }

        fun close() {
            // ViewModel 的 Room 订阅不随控制器作用域结束；先停止它们，避免关闭数据库后仍有查询重开。
            viewModels.forEach { viewModel ->
                runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val TARGET_ID = "monitor-chart-target"
        const val ADDRESS = "AA:BB:CC:DD:EE:03"
        const val TARGET_NAME = "监控图表目标"
    }
}
