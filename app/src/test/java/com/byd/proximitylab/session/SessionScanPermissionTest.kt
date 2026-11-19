package com.byd.proximitylab.session

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.ble.ScanPermissionChecker
import com.byd.proximitylab.live.InMemorySimulationStore
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.live.SimulationStartResult
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.feedback.ForegroundSessionCoordinator
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.InMemoryTargetBindingStore
import com.byd.proximitylab.testing.RecordingForegroundSessionLauncher
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11 的扫描权限判断：开始前检查、运行中撤销可观察，以及恢复后零补触发。
 *
 * 只替代 BLE 扫描回调与时间输入；Session 生命周期、判定、时间线和前台服务协调走生产实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionScanPermissionTest {

    private val addressA = "AA:BB:CC:DD:EE:01"
    private var now = 1_800_000_000_000L
    private var sequence = 0L
    private var sessionSequence = 0L

    @Test
    fun missingScanPermissionBlocksSessionScanAndForegroundService() = runTest {
        val fixture = fixture(granted = false)

        val result = fixture.recorder.start(fixture.request())
        assertTrue(result is SessionStartResult.ScanPermissionMissing)
        advanceUntilIdle()

        assertNull(fixture.recorder.activeSession.value)
        assertTrue(fixture.store.observeSessions().first().isEmpty())
        assertEquals(0, fixture.source.startCount)
        assertTrue(fixture.launcher.startedSessionIds.isEmpty())
    }

    @Test
    fun grantedScanPermissionStartsSessionAndForegroundService() = runTest {
        val fixture = fixture(granted = true)

        val result = fixture.recorder.start(fixture.request())
        val session = (result as SessionStartResult.Started).session
        advanceUntilIdle()

        assertEquals(1, fixture.source.startCount)
        assertEquals(listOf(session.id), fixture.launcher.startedSessionIds)
        assertTrue(fixture.coordinator.status.value.running)
    }

    @Test
    fun missingScanPermissionBlocksSimulationStartAndForegroundService() = runTest {
        val fixture = fixture(granted = false)

        val result = fixture.controller.startSimulation()
        advanceUntilIdle()

        assertTrue(result is SimulationStartResult.ScanPermissionMissing)
        assertNull(fixture.recorder.activeSession.value)
        assertTrue(fixture.store.observeSessions().first().isEmpty())
        assertTrue(fixture.launcher.startedSessionIds.isEmpty())
        assertTrue(fixture.controller.state.value.message?.contains("BLUETOOTH_SCAN") == true)
    }

    @Test
    fun revokedScanPermissionIsRecordedAndRecoveryDoesNotRetrigger() = runTest {
        val fixture = fixture(granted = true)
        val started = fixture.controller.startSimulation()
        val session = (started as SimulationStartResult.Started).session
        advanceUntilIdle()

        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)
        assertEquals(ProximityState.FAR, fixture.controller.state.value.test?.decision?.state)

        // Android 运行中撤销权限后可能不再回调扫描结果；看门狗显式复查。
        fixture.source.revalidateResult = ScanAvailability.PermissionMissing(revoked = true)
        val watchdog = SessionScanWatchdog(
            activeSession = fixture.recorder.activeSession,
            revalidate = fixture.monitor::revalidateScanAvailability,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            intervalMillis = 0L,
            resume = fixture.monitor::resumeScanIfReady,
        )
        watchdog.checkNow()
        advanceUntilIdle()

        assertEquals(ProximityState.UNKNOWN, fixture.controller.state.value.test?.decision?.state)
        assertEquals(
            ProximityStateReason.SCAN_UNAVAILABLE,
            fixture.controller.state.value.test?.decision?.reason,
        )
        assertTrue(
            fixture.store.loadTimeline(session.id).any { item ->
                (item.event as? SessionTimelineEvent.ScanStateChanged)?.stateLabel ==
                    "PERMISSION_REVOKED"
            },
        )
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())

        // 权限恢复后看门狗沿用同一 Session 重新提交扫描；只按新证据重建状态，不补发 FAR→NEAR。
        fixture.source.revalidateResult = null
        fixture.source.resumeResult = ScanAvailability.Scanning
        watchdog.checkNow()
        advanceUntilIdle()
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)

        assertEquals(ProximityState.NEAR, fixture.controller.state.value.test?.decision?.state)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
        // 撤销检查与恢复检查各触发一次 resume 尝试；第二次把扫描状态恢复为 Scanning。
        assertEquals(2, fixture.source.resumeCount)
        assertTrue(fixture.source.scanState.value is ScanAvailability.Scanning)
        watchdog.stop()
    }

    @Test
    fun watchdogChecksOnlyWhileSessionIsActive() = runTest {
        val fixture = fixture(granted = true)
        val watchdog = SessionScanWatchdog(
            activeSession = fixture.recorder.activeSession,
            revalidate = fixture.monitor::revalidateScanAvailability,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            intervalMillis = 0L,
            resume = fixture.monitor::resumeScanIfReady,
        )

        watchdog.checkNow()
        assertEquals(0, fixture.source.revalidateCount)
        assertEquals(0, fixture.source.resumeCount)

        fixture.recorder.start(fixture.request())
        advanceUntilIdle()
        watchdog.checkNow()
        assertEquals(1, fixture.source.revalidateCount)
        assertEquals(1, fixture.source.resumeCount)
        watchdog.stop()
    }

    private fun TestScope.fixture(granted: Boolean): Fixture {
        val source = ControllableBleScanSource()
        val store = InMemorySessionStore()
        val simulationStore = InMemorySimulationStore()
        val bindingStore = InMemoryTargetBindingStore(
            testBinding(targetId = "target-1", address = addressA),
        )
        val scope = CoroutineScope(
            SupervisorJob(backgroundScope.coroutineContext[Job]) +
                UnconfinedTestDispatcher(testScheduler),
        )
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
            idGenerator = { "permission-session-${++sessionSequence}" },
            scanPermissionChecker = ScanPermissionChecker { granted },
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
        val launcher = RecordingForegroundSessionLauncher()
        val coordinator = ForegroundSessionCoordinator(
            activeSession = recorder.activeSession,
            launcher = launcher,
            scope = scope,
        )
        advanceUntilIdle()
        return Fixture(
            source = source,
            monitor = monitor,
            store = store,
            simulationStore = simulationStore,
            recorder = recorder,
            controller = controller,
            launcher = launcher,
            coordinator = coordinator,
            scope = scope,
            testScope = this,
        )
    }

    private inner class Fixture(
        val source: ControllableBleScanSource,
        val monitor: TargetMonitor,
        val store: InMemorySessionStore,
        val simulationStore: InMemorySimulationStore,
        val recorder: SessionRecorder,
        val controller: LiveMonitorController,
        val launcher: RecordingForegroundSessionLauncher,
        val coordinator: ForegroundSessionCoordinator,
        val scope: CoroutineScope,
        private val testScope: TestScope,
    ) {
        fun request(): SessionStartRequest = SessionStartRequest(
            binding = testBinding(targetId = "target-1", address = addressA),
            samplingType = SamplingType.FIXED_POINT,
            name = "权限测试",
            location = FixedSampleLocation.FRONT,
            estimatedDistanceMeters = null,
            phonePlacement = PhonePlacement.HANDHELD,
        )

        fun feed(rssi: Int) {
            now += 1_000L
            sequence += 1
            check(
                source.emit(
                    testAdvertisement(
                        address = addressA,
                        rssi = rssi,
                        observedAtEpochMillis = now - 20L,
                        receivedAtEpochMillis = now - 5L,
                        observedAtNanos = sequence * 1_000_000_000L,
                        receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                        deliverySequence = sequence,
                        payloadFingerprint = "permission-frame-$sequence",
                    ),
                ),
            ) { "扫描事件缓冲拒绝投递" }
            testScope.advanceUntilIdle()
        }
    }
}
