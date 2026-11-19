package com.byd.proximitylab.feedback

import com.byd.proximitylab.session.InMemorySessionStore
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStartRequest
import com.byd.proximitylab.session.SessionStartResult
import com.byd.proximitylab.session.SessionStopResult
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingType
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11 的前台服务与会话接合：真实 SessionRecorder/TargetMonitor，只替代 Android 服务启动调用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSessionCoordinatorTest {

    private val addressA = "AA:BB:CC:DD:EE:01"
    private var now = 1_800_000_000_000L
    private var sequence = 0L
    private var sessionSequence = 0L

    @Test
    fun serviceStartsOnceForActiveSessionAndStopsWithSameSession() = runTest {
        val fixture = fixture()
        val session = fixture.startFixed()
        advanceUntilIdle()

        assertEquals(listOf(session.id), fixture.launcher.startedSessionIds)
        assertTrue(fixture.coordinator.status.value.running)
        assertEquals(session.id, fixture.coordinator.status.value.sessionId)

        // 时间线继续追加但 Session ID 不变：不重复启动服务。
        fixture.emit(rssi = -70)
        fixture.emit(rssi = -68)
        assertEquals(listOf(session.id), fixture.launcher.startedSessionIds)

        val stopped = fixture.recorder.stop() as SessionStopResult.Stopped
        assertEquals(session.id, stopped.session.id)
        advanceUntilIdle()
        assertEquals(1, fixture.launcher.stopCount)
        assertFalse(fixture.coordinator.status.value.running)
        assertNull(fixture.coordinator.status.value.sessionId)
    }

    @Test
    fun nextSessionStartsNewServiceWithoutDuplicateStop() = runTest {
        val fixture = fixture()
        val first = fixture.startFixed()
        advanceUntilIdle()
        fixture.recorder.stop()
        advanceUntilIdle()

        now += 1_000L
        val second = fixture.startFixed()
        advanceUntilIdle()

        assertEquals(listOf(first.id, second.id), fixture.launcher.startedSessionIds)
        assertEquals(1, fixture.launcher.stopCount)
        assertTrue(fixture.coordinator.status.value.running)
        assertEquals(second.id, fixture.coordinator.status.value.sessionId)
    }

    @Test
    fun serviceStartFailureIsObservableAndDoesNotEndSession() = runTest {
        val launcher = RecordingForegroundSessionLauncher(
            startResult = ForegroundSessionStartResult.Failed("后台启动被系统拒绝"),
        )
        val fixture = fixture(launcher)
        val session = fixture.startFixed()
        advanceUntilIdle()

        val status = fixture.coordinator.status.value
        assertFalse(status.running)
        assertEquals(session.id, status.sessionId)
        assertEquals("后台启动被系统拒绝", status.failureReason)
        assertEquals(session.id, fixture.recorder.activeSession.value?.id)

        // 后续时间线事件不会为同一 Session 反复尝试启动服务。
        fixture.emit(rssi = -70)
        assertEquals(1, fixture.launcher.startedSessionIds.size)

        fixture.recorder.stop()
        advanceUntilIdle()
        // 从未成功启动的服务不需要停止；Session 正常结束且失败状态被清空。
        assertEquals(0, fixture.launcher.stopCount)
        assertFalse(fixture.coordinator.status.value.running)
        assertNull(fixture.coordinator.status.value.failureReason)
    }

    @Test
    fun repeatedAlreadyRecordingStartDoesNotStartSecondService() = runTest {
        val fixture = fixture()
        val session = fixture.startFixed()
        advanceUntilIdle()

        val second = fixture.recorder.start(fixture.request("重复开始"))
        assertTrue(second is SessionStartResult.AlreadyRecording)
        advanceUntilIdle()
        assertEquals(listOf(session.id), fixture.launcher.startedSessionIds)
    }

    private fun TestScope.fixture(
        launcher: RecordingForegroundSessionLauncher = RecordingForegroundSessionLauncher(),
    ): Fixture {
        val source = ControllableBleScanSource()
        val store = InMemorySessionStore()
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
            idGenerator = { "service-session-${++sessionSequence}" },
        )
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
            recorder = recorder,
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
        val recorder: SessionRecorder,
        val launcher: RecordingForegroundSessionLauncher,
        val coordinator: ForegroundSessionCoordinator,
        val scope: CoroutineScope,
        private val testScope: TestScope,
    ) {
        suspend fun startFixed(): SamplingSession {
            val result = recorder.start(request("前台服务测试"))
            val session = (result as SessionStartResult.Started).session
            testScope.advanceUntilIdle()
            return session
        }

        fun request(name: String): SessionStartRequest = SessionStartRequest(
            binding = testBinding(targetId = "target-1", address = addressA),
            samplingType = SamplingType.FIXED_POINT,
            name = name,
            location = FixedSampleLocation.FRONT,
            estimatedDistanceMeters = null,
            phonePlacement = PhonePlacement.HANDHELD,
        )

        fun emit(rssi: Int) {
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
                        payloadFingerprint = "service-frame-$sequence",
                    ),
                ),
            ) { "扫描事件缓冲拒绝投递" }
            testScope.advanceUntilIdle()
        }
    }
}
