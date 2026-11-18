package com.byd.proximitylab.live

import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.InMemorySessionStore
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStartRequest
import com.byd.proximitylab.session.SessionStartResult
import com.byd.proximitylab.session.SessionStopResult
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.InMemoryTargetBindingStore
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 05 实时入口行为测试。
 *
 * 只替代 BLE 系统回调与时间输入；目标关联、Room 之外的会话控制、接近判定、事件生成和运行结果
 * 持久化都走生产实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMonitorControllerTest {

    private val addressA = "AA:BB:CC:DD:EE:01"
    private val addressB = "AA:BB:CC:DD:EE:02"
    private var now = 1_800_000_000_000L
    private var sequence = 0L
    private var sessionSequence = 0L

    @Test
    fun normalMonitorFarIsNotCarriedIntoSimulationAndOnlyNearStartsUnknownWithoutEvent() = runTest {
        val fixture = fixture()
        fixture.controller.startMonitoring()
        advanceUntilIdle()

        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        assertEquals(ProximityState.FAR, fixture.controller.state.value.monitor.state)

        val started = fixture.controller.startSimulation()
        assertTrue(started is SimulationStartResult.Started)
        val session = (started as SimulationStartResult.Started).session
        advanceUntilIdle()

        val testView = requireNotNull(fixture.controller.state.value.test)
        assertEquals(ProximityState.UNKNOWN, testView.decision.state)
        assertEquals(ProximityStateReason.AWAITING_OBSERVATION, testView.decision.reason)
        assertEquals(0, testView.events.size)

        fixture.emit(rssi = -60)
        fixture.emit(rssi = -60)

        assertEquals(ProximityState.NEAR, fixture.controller.state.value.test?.decision?.state)
        assertEquals(0, fixture.controller.state.value.test?.events?.size)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
        assertEquals(
            BuiltInSessionConfigs.DEFAULT,
            fixture.sessionStore.loadConfigSnapshot(session.id),
        )
        assertTrue(
            fixture.sessionStore.loadTimeline(session.id)
                .any { it.event is SessionTimelineEvent.Observation },
        )
    }

    @Test
    fun confirmedFarThenNearPersistsOneEventAndRefoldingEmitsAgain() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()

        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -60)
        fixture.emit(rssi = -60)

        val firstEvents = fixture.simulationStore.loadEvents(session.id)
        assertEquals(1, firstEvents.size)
        val event = firstEvents.single()
        assertEquals(ProximityEventType.ENTERED_RANGE, event.type)
        assertEquals(ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR, event.reason)
        assertEquals(session.targetId, event.targetId)
        assertEquals(-60, event.rawRssiDbm)
        assertTrue(event.atSessionMillis > 0L)
        assertEquals(-60.0, event.filteredRssiDbm, 0.0)
        assertEquals("proximity-algorithms/1", event.algorithmVersion)
        assertEquals("confirmed-far-then-near/1", event.triggerRuleVersion)
        assertEquals(SimulatedEventFeedbackStatus.NOT_ATTEMPTED, event.feedbackStatus)
        assertEquals(ProximityState.NEAR, fixture.controller.state.value.test?.decision?.state)
        assertTrue(
            fixture.simulationStore.loadStates(session.id).any { state ->
                state.state == ProximityState.NEAR &&
                    state.reason == ProximityStateReason.CONFIRMED_NEAR
            },
        )

        // 近端停留与迟滞区不重复；重新确认 FAR 后折返允许再次触发。
        fixture.emit(rssi = -60)
        fixture.emit(rssi = -70)
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -60)
        fixture.emit(rssi = -60)

        assertEquals(2, fixture.simulationStore.loadEvents(session.id).size)
        assertEquals(10, fixture.sessionStore.loadTimeline(session.id).count { item ->
            item.event is SessionTimelineEvent.Observation
        })
    }

    @Test
    fun timerExpiresSamplesWithInjectedClockAndRecoveryDoesNotEmit() = runTest {
        val fixture = fixture(tickerIntervalMillis = 250L)
        val session = fixture.startSimulation(useTestScheduler = true)
        fixture.emit(rssi = -85, useTestScheduler = true)
        fixture.emit(rssi = -85, useTestScheduler = true)
        assertEquals(ProximityState.FAR, fixture.controller.state.value.test?.decision?.state)

        now += 6_000L
        advanceTimeBy(250L)
        runCurrent()

        val expired = requireNotNull(fixture.controller.state.value.test)
        assertEquals(ProximityState.UNKNOWN, expired.decision.state)
        assertEquals(ProximityStateReason.SAMPLES_EXPIRED, expired.decision.reason)
        assertTrue(
            fixture.simulationStore.loadStates(session.id).any { state ->
                state.state == ProximityState.UNKNOWN &&
                    state.reason == ProximityStateReason.SAMPLES_EXPIRED
            },
        )

        fixture.emit(rssi = -60, useTestScheduler = true)
        fixture.emit(rssi = -60, useTestScheduler = true)
        assertEquals(ProximityState.NEAR, fixture.controller.state.value.test?.decision?.state)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
    }

    @Test
    fun scanStopAndIdentityConflictInvalidateTestAndRecoveryDoesNotEmit() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        assertEquals(ProximityState.FAR, fixture.controller.state.value.test?.decision?.state)

        fixture.source.stop()
        advanceUntilIdle()
        assertEquals(ProximityState.UNKNOWN, fixture.controller.state.value.test?.decision?.state)
        assertEquals(
            ProximityStateReason.SCAN_UNAVAILABLE,
            fixture.controller.state.value.test?.decision?.reason,
        )

        // 新扫描清空候选后，未绑定地址缺少可复验证据，目标身份进入不确定。
        fixture.source.start()
        advanceUntilIdle()
        fixture.emit(rssi = -60, address = addressB)
        assertEquals(ProximityState.UNKNOWN, fixture.controller.state.value.test?.decision?.state)
        assertEquals(
            ProximityStateReason.IDENTITY_UNCONFIRMED,
            fixture.controller.state.value.test?.decision?.reason,
        )

        // 回到原绑定地址后从新证据重建 NEAR，不补发 FAR→NEAR 事件。
        fixture.emit(rssi = -60, address = addressA)
        fixture.emit(rssi = -60, address = addressA)
        assertEquals(ProximityState.NEAR, fixture.controller.state.value.test?.decision?.state)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
    }

    @Test
    fun targetRebindEndsRunningSimulationInsteadOfMixingTargets() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        assertEquals(ProximityState.FAR, fixture.controller.state.value.test?.decision?.state)

        // 让地址 B 成为候选，然后由用户明确改绑到 B。
        fixture.emit(rssi = -50, address = addressB)
        fixture.monitor.rebindTo(addressB)
        advanceUntilIdle()

        assertNull(fixture.recorder.activeSession.value)
        val stored = requireNotNull(fixture.sessionStore.loadSession(session.id))
        assertEquals(SessionLifecycleStatus.COMPLETED, stored.status)
        val testView = requireNotNull(fixture.controller.state.value.test)
        assertFalse(testView.running)
        assertTrue(fixture.controller.state.value.message?.contains("目标") == true)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
        assertEquals(session.targetId, stored.targetId)
    }

    @Test
    fun onlyRunningSimulationWritesEnteredRangeHistory() = runTest {
        val fixture = fixture()
        val started = fixture.recorder.start(
            SessionStartRequest(
                binding = testBinding(targetId = "target-1", address = addressA),
                samplingType = SamplingType.FIXED_POINT,
                name = "定点采样",
                location = FixedSampleLocation.FRONT,
                estimatedDistanceMeters = null,
                phonePlacement = PhonePlacement.HANDHELD,
            ),
        )
        val session = (started as SessionStartResult.Started).session
        advanceUntilIdle()

        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -60)
        fixture.emit(rssi = -60)

        assertNull(fixture.controller.state.value.test)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
        assertTrue(
            fixture.sessionStore.loadTimeline(session.id)
                .any { it.event is SessionTimelineEvent.Observation },
        )
    }

    @Test
    fun resubscribingStateDoesNotReemitPersistedEvents() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -60)
        fixture.emit(rssi = -60)
        assertEquals(1, fixture.simulationStore.loadEvents(session.id).size)

        val firstSubscription = fixture.scope.launch {
            fixture.controller.state.collect { }
        }
        advanceUntilIdle()
        firstSubscription.cancel()

        val secondSubscription = fixture.scope.launch {
            fixture.controller.state.collect { }
        }
        advanceUntilIdle()
        secondSubscription.cancel()

        assertEquals(1, fixture.simulationStore.loadEvents(session.id).size)
        assertEquals(1, fixture.controller.state.value.test?.events?.size)
    }

    @Test
    fun feedbackUpdatesOnlyNotificationFieldsAndKeepsEventFacts() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -60)
        fixture.emit(rssi = -60)
        val event = fixture.simulationStore.loadEvents(session.id).single()

        assertTrue(
            fixture.controller.updateEventFeedback(
                eventId = event.eventId,
                status = SimulatedEventFeedbackStatus.UNAVAILABLE,
                detail = "通知权限未授予",
            ),
        )
        val updated = fixture.simulationStore.loadEvents(session.id).single()
        assertEquals(SimulatedEventFeedbackStatus.UNAVAILABLE, updated.feedbackStatus)
        assertEquals("通知权限未授予", updated.feedbackDetail)
        assertEquals(now, updated.feedbackUpdatedAtEpochMillis)
        assertEquals(event.reason, updated.reason)
        assertEquals(event.rawRssiDbm, updated.rawRssiDbm)
        assertEquals(event.atSessionMillis, updated.atSessionMillis)
    }

    @Test
    fun externalSharedStopEndsSimulationAndStaleMonitorStopLeavesNextSamplingSessionRunning() = runTest {
        val fixture = fixture()
        val simulation = fixture.startSimulation()
        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        val statesBeforeStop = fixture.simulationStore.loadStates(simulation.id).size

        // 采样页等共享入口直接调用同一个 SessionRecorder.stop()。
        val externalStop = fixture.recorder.stop()
        assertTrue(externalStop is SessionStopResult.Stopped)
        advanceUntilIdle()

        val testView = requireNotNull(fixture.controller.state.value.test)
        assertFalse(testView.running)
        assertEquals(
            SessionLifecycleStatus.COMPLETED,
            fixture.sessionStore.loadSession(simulation.id)?.status,
        )
        val statesAfterStop = fixture.simulationStore.loadStates(simulation.id)
        assertTrue(statesAfterStop.size > statesBeforeStop)
        assertEquals(ProximityState.UNKNOWN, statesAfterStop.last().state)
        assertEquals(ProximityStateReason.SCAN_UNAVAILABLE, statesAfterStop.last().reason)

        // 已结束的模拟测试不再由刷新或计时追加运行状态。
        fixture.controller.refreshNow(now + 10_000L)
        advanceUntilIdle()
        assertEquals(statesAfterStop.size, fixture.simulationStore.loadStates(simulation.id).size)

        // 随后的定点采样拥有共享 recorder；monitor 页的旧结束操作不能停掉它。
        val fixed = fixture.recorder.start(
            SessionStartRequest(
                binding = testBinding(targetId = "target-1", address = addressA),
                samplingType = SamplingType.FIXED_POINT,
                name = "外部停止后的定点采样",
                location = FixedSampleLocation.FRONT,
                estimatedDistanceMeters = null,
                phonePlacement = PhonePlacement.HANDHELD,
            ),
        )
        assertTrue(fixed is SessionStartResult.Started)
        val fixedSessionId = (fixed as SessionStartResult.Started).session.id
        advanceUntilIdle()

        assertEquals(SimulationStopResult.NotRunning, fixture.controller.stopSimulation())
        assertEquals(
            SessionLifecycleStatus.RECORDING,
            fixture.sessionStore.loadSession(fixedSessionId)?.status,
        )
        assertEquals(fixedSessionId, fixture.recorder.activeSession.value?.id)
        fixture.recorder.stop()
    }

    @Test
    fun providedEmaConfigDrivesFilteringAndIsPersistedWithSession() = runTest {
        val fixture = fixture()
        val custom = BuiltInSessionConfigs.EMA.copy(
            snapshotId = "custom-ema",
            name = "自定义 EMA",
            sourceConfigId = "user-config-1",
        )
        val started = fixture.controller.startSimulation(custom)
        assertTrue(started is SimulationStartResult.Started)
        val session = (started as SimulationStartResult.Started).session
        advanceUntilIdle()

        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        repeat(4) { fixture.emit(rssi = -60) }

        val event = fixture.simulationStore.loadEvents(session.id).single()
        // Baseline 会直接使用 -60；EMA α=0.5 的第 6 帧为 -61.5625，证明确实使用快照算法。
        assertEquals(-61.5625, event.filteredRssiDbm, 1e-9)
        assertEquals(-60, event.rawRssiDbm)
        assertEquals(custom, fixture.sessionStore.loadConfigSnapshot(session.id))
        assertEquals(
            ProximityAlgorithm.EMA,
            fixture.controller.state.value.test?.config?.config?.algorithm,
        )
    }

    @Test
    fun providedThresholdConfigChangesConfirmationOutcome() = runTest {
        val fixture = fixture()
        val custom = BuiltInSessionConfigs.BASELINE.copy(
            snapshotId = "custom-threshold",
            name = "高进入阈值",
            sourceConfigId = null,
            config = BuiltInSessionConfigs.BASELINE.config.copy(
                enterThresholdDbm = -50,
                exitThresholdDbm = -80,
            ),
        )
        val started = fixture.controller.startSimulation(custom)
        assertTrue(started is SimulationStartResult.Started)
        val session = (started as SimulationStartResult.Started).session
        advanceUntilIdle()

        fixture.emit(rssi = -85)
        fixture.emit(rssi = -85)
        repeat(4) { fixture.emit(rssi = -60) }

        // -60 dBm 落在 -80..-50 的迟滞区，不能确认 NEAR，也不会触发事件。
        assertEquals(ProximityState.FAR, fixture.controller.state.value.test?.decision?.state)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
        assertEquals(custom, fixture.sessionStore.loadConfigSnapshot(session.id))
    }

    private fun TestScope.fixture(tickerIntervalMillis: Long = 0L): Fixture {
        val source = ControllableBleScanSource()
        val sessionStore = InMemorySessionStore()
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
            store = sessionStore,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "live-session-${++sessionSequence}" },
        )
        val controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = sessionStore,
            simulationStore = simulationStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = tickerIntervalMillis,
        )
        if (tickerIntervalMillis > 0L) {
            runCurrent()
        } else {
            advanceUntilIdle()
        }
        return Fixture(
            source = source,
            monitor = monitor,
            recorder = recorder,
            sessionStore = sessionStore,
            simulationStore = simulationStore,
            controller = controller,
            scope = scope,
            testScope = this,
        )
    }

    private inner class Fixture(
        val source: ControllableBleScanSource,
        val monitor: TargetMonitor,
        val recorder: SessionRecorder,
        val sessionStore: InMemorySessionStore,
        val simulationStore: InMemorySimulationStore,
        val controller: LiveMonitorController,
        val scope: CoroutineScope,
        private val testScope: TestScope,
    ) {
        fun emit(
            rssi: Int,
            advanceMillis: Long = 1_000L,
            address: String = addressA,
            useTestScheduler: Boolean = false,
        ) {
            now += advanceMillis
            sequence += 1
            val emitted = source.emit(
                testAdvertisement(
                    address = address,
                    rssi = rssi,
                    observedAtEpochMillis = now - 20L,
                    receivedAtEpochMillis = now - 5L,
                    observedAtNanos = sequence * 1_000_000_000L,
                    receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                    deliverySequence = sequence,
                    payloadFingerprint = "live-frame-$sequence",
                ),
            )
            assertTrue("扫描事件缓冲拒绝投递", emitted)
            if (useTestScheduler) {
                testScope.runCurrent()
            } else {
                testScope.advanceUntilIdle()
            }
        }

        suspend fun startSimulation(useTestScheduler: Boolean = false) =
            (controller.startSimulation() as SimulationStartResult.Started).session.also {
                if (useTestScheduler) {
                    testScope.runCurrent()
                } else {
                    testScope.advanceUntilIdle()
                }
            }
    }
}
