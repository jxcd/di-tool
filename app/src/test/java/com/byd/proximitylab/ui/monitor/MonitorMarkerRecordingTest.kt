package com.byd.proximitylab.ui.monitor

import com.byd.proximitylab.live.InMemorySimulationStore
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.session.InMemorySessionStore
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionMarkerRecorder
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.InMemoryTargetBindingStore
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 08 在模拟测试中记录到达期望位置：真实控制器、会话控制、标记记录与内存存储，
 * 只替代 BLE 系统回调和时间输入。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorMarkerRecordingTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private var now = 1_800_000_000_000L
    private var sequence = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun expectedMarkerUsesSimulationSessionTimeBaseAndDoesNotTouchTimeline() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemorySessionStore()
        val simulationStore = InMemorySimulationStore()
        val bindingStore = InMemoryTargetBindingStore(
            testBinding(targetId = "target-1", address = "AA:BB:CC:DD:EE:01"),
        )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        val recorder = SessionRecorder(
            store = store,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "simulation-session" },
        )
        val controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = store,
            simulationStore = simulationStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = 0L,
        )
        val markerRecorder = SessionMarkerRecorder(
            store = store,
            clock = { now },
            idGenerator = { "marker-1" },
        )
        val viewModel = MonitorViewModel(
            controller = controller,
            sessionStore = store,
            simulationStore = simulationStore,
            markerRecorder = markerRecorder,
        )
        controller.startMonitoring()
        advanceUntilIdle()
        controller.startSimulation()
        advanceUntilIdle()

        val session = requireNotNull(controller.state.value.activeSession)
        val timelineBefore = store.loadTimeline(session.id)

        now += 4_500L
        viewModel.markExpectedTrigger()
        advanceUntilIdle()

        val marker = store.loadReferenceMarkers(session.id).single()
        assertEquals(ReferenceMarkerKind.EXPECTED_TRIGGER, marker.kind)
        assertEquals(now, marker.atEpochMillis)
        assertEquals(4_500L, marker.atEpochMillis - session.startedAtEpochMillis)
        assertEquals(
            SessionEvaluationLabel.EXPECTED_TRIGGER,
            store.loadSession(session.id)?.evaluationLabel,
        )
        // 标记只写入独立标记表，模拟测试时间线不变。
        assertEquals(timelineBefore, store.loadTimeline(session.id))
        val message = requireNotNull(viewModel.markerMessage.value)
        assertTrue(message.contains("到达期望触发位置"))
        assertTrue(message.contains("+4.5 秒"))
    }

    @Test
    fun markerIsRejectedWithoutRunningSimulation() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemorySessionStore()
        val simulationStore = InMemorySimulationStore()
        val bindingStore = InMemoryTargetBindingStore(
            testBinding(targetId = "target-1", address = "AA:BB:CC:DD:EE:01"),
        )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        val recorder = SessionRecorder(
            store = store,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "simulation-session" },
        )
        val controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = store,
            simulationStore = simulationStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = 0L,
        )
        val viewModel = MonitorViewModel(
            controller = controller,
            sessionStore = store,
            simulationStore = simulationStore,
            markerRecorder = SessionMarkerRecorder(store = store, clock = { now }),
        )
        controller.startMonitoring()
        advanceUntilIdle()

        viewModel.markExpectedTrigger()
        advanceUntilIdle()

        assertNull(controller.state.value.activeSession)
        assertNotNull(viewModel.markerMessage.value)
        assertTrue(viewModel.markerMessage.value?.contains("没有运行中的模拟测试") == true)
    }
}
