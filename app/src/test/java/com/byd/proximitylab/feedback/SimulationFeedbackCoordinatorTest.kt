package com.byd.proximitylab.feedback

import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.session.InMemorySessionStore
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.SimulationEventHistoryReader
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.InMemoryTargetBindingStore
import com.byd.proximitylab.testing.RecordingSimulationAlarm
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11 的模拟事件反馈：只替代 Android 通知/震动边界，事件持久化、去重、反馈写回和事件历史都走生产实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulationFeedbackCoordinatorTest {

    private val addressA = "AA:BB:CC:DD:EE:01"
    private var now = 1_800_000_000_000L
    private var sequence = 0L
    private var sessionSequence = 0L

    @Test
    fun confirmedFarThenNearDeliversOneAlertAndKeepsEventFacts() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()
        fixture.coordinator.follow(session.id)

        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)

        val event = fixture.simulationStore.loadEvents(session.id).single()
        assertEquals(1, fixture.alarm.deliveries.size)
        assertEquals(event.eventId, fixture.alarm.deliveries.single().eventId)

        val stored = fixture.simulationStore.loadEvents(session.id).single()
        assertEquals(SimulatedEventFeedbackStatus.DELIVERED, stored.feedbackStatus)
        assertTrue(stored.feedbackDetail?.contains("锁屏") == true)
        // 通知结果只改反馈字段，事件事实保持不变。
        assertEquals(event.reason, stored.reason)
        assertEquals(event.type, stored.type)
        assertEquals(event.rawRssiDbm, stored.rawRssiDbm)
        assertEquals(event.atSessionMillis, stored.atSessionMillis)
        assertEquals(event.algorithmVersion, stored.algorithmVersion)
        assertEquals(event.triggerRuleVersion, stored.triggerRuleVersion)

        val history = fixture.historyReader.observeLatest().first()
        assertEquals(1, history.entries.size)
        assertEquals(SimulatedEventFeedbackStatus.DELIVERED, history.entries.single().feedbackStatus)
    }

    @Test
    fun notificationsUnavailableStillKeepsEventAndRecordsReason() = runTest {
        val fixture = fixture()
        fixture.alarm.capabilities = SimulationAlertCapabilities(
            notificationPermissionRequired = true,
            notificationPermissionGranted = false,
            notificationsEnabled = false,
            vibrationAvailable = true,
        )
        val session = fixture.startSimulation()
        fixture.coordinator.follow(session.id)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)

        val event = fixture.simulationStore.loadEvents(session.id).single()
        assertEquals(SimulatedEventFeedbackStatus.UNAVAILABLE, event.feedbackStatus)
        assertTrue(event.feedbackDetail?.contains("POST_NOTIFICATIONS") == true)
        assertEquals(-60, event.rawRssiDbm)
        assertTrue(fixture.alarm.deliveries.isEmpty())
        assertEquals(1, fixture.alarm.attempts.size)
        // 模拟事件与原始观测仍可按稳定标识查询。
        val history = fixture.historyReader.observeLatest().first()
        assertEquals(event.eventId, history.entries.single().eventId)
        assertEquals(SimulatedEventFeedbackStatus.UNAVAILABLE, history.entries.single().feedbackStatus)
        assertTrue(
            fixture.sessionStore.loadTimeline(session.id)
                .count { item -> item.event is SessionTimelineEvent.Observation } == 4,
        )
    }

    @Test
    fun failedDeliveryRecordsFailedStatusWithoutChangingEvent() = runTest {
        val fixture = fixture()
        fixture.alarm.forcedFailure = "系统通知服务错误"
        val session = fixture.startSimulation()
        fixture.coordinator.follow(session.id)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)

        val event = fixture.simulationStore.loadEvents(session.id).single()
        assertEquals(SimulatedEventFeedbackStatus.FAILED, event.feedbackStatus)
        assertEquals("系统通知服务错误", event.feedbackDetail)
        assertEquals(ProximityEventType.ENTERED_RANGE, event.type)
        assertEquals(ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR, event.reason)
    }

    @Test
    fun noEventDoesNotAttemptNotificationOrCountAsFailure() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()
        fixture.coordinator.follow(session.id)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)

        assertEquals(ProximityState.FAR, fixture.controller.state.value.test?.decision?.state)
        assertTrue(fixture.simulationStore.loadEvents(session.id).isEmpty())
        assertTrue(fixture.alarm.attempts.isEmpty())
        assertTrue(fixture.alarm.deliveries.isEmpty())
    }

    @Test
    fun eventPersistedBeforeSubscriptionIsDeliveredOnceAndDuplicateFollowIsIdempotent() = runTest {
        val fixture = fixture()
        val session = fixture.startSimulation()

        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)
        val persisted = fixture.simulationStore.loadEvents(session.id).single()
        assertEquals(SimulatedEventFeedbackStatus.NOT_ATTEMPTED, persisted.feedbackStatus)
        assertTrue(fixture.alarm.attempts.isEmpty())

        fixture.coordinator.follow(session.id)
        advanceUntilIdle()
        assertEquals(1, fixture.alarm.attempts.size)
        assertEquals(1, fixture.alarm.deliveries.size)
        assertEquals(SimulatedEventFeedbackStatus.DELIVERED, fixture.simulationStore.loadEvents(session.id).single().feedbackStatus)

        // 同一 Session 的重复 follow、状态重复发射不会重复提交通知。
        fixture.coordinator.follow(session.id)
        advanceUntilIdle()
        assertEquals(1, fixture.alarm.attempts.size)
        assertEquals(1, fixture.alarm.deliveries.size)
    }

    @Test
    fun detachedAndNewSessionDoNotBackfillOldEventsOrRetrigger() = runTest {
        val fixture = fixture()
        val firstSession = fixture.startSimulation()
        fixture.coordinator.follow(firstSession.id)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)
        val firstEvent = fixture.simulationStore.loadEvents(firstSession.id).single()
        assertEquals(1, fixture.alarm.deliveries.size)

        fixture.coordinator.follow(null)
        fixture.controller.stopSimulation()
        advanceUntilIdle()

        val secondSession = fixture.startSimulation()
        fixture.coordinator.follow(secondSession.id)
        // 新 Session 只收到近端数据：不补发 FAR→NEAR，也不重发旧事件。
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)
        assertEquals(ProximityState.NEAR, fixture.controller.state.value.test?.decision?.state)
        assertTrue(fixture.simulationStore.loadEvents(secondSession.id).isEmpty())
        assertEquals(1, fixture.alarm.deliveries.size)
        assertEquals(firstEvent.eventId, fixture.alarm.deliveries.single().eventId)
        assertEquals(
            SimulatedEventFeedbackStatus.DELIVERED,
            fixture.simulationStore.loadEvents(firstSession.id).single().feedbackStatus,
        )

        // 新 Session 重新建立 FAR 后才允许新的 ENTERED_RANGE，旧通知不重复。
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -85)
        fixture.feed(rssi = -60)
        fixture.feed(rssi = -60)
        val secondEvent = fixture.simulationStore.loadEvents(secondSession.id).single()
        assertEquals(2, fixture.alarm.deliveries.size)
        assertEquals(secondEvent.eventId, fixture.alarm.deliveries.last().eventId)
        assertTrue(firstEvent.eventId != secondEvent.eventId)
        assertEquals(4, fixture.sessionStore.loadTimeline(firstSession.id)
            .count { item -> item.event is SessionTimelineEvent.Observation })
        assertEquals(6, fixture.sessionStore.loadTimeline(secondSession.id)
            .count { item -> item.event is SessionTimelineEvent.Observation })
    }

    @Test
    fun coordinatorDoesNotRedeliverEventAlreadyRecordedBeforeProcessRestart() = runTest {
        val simulationStore = com.byd.proximitylab.live.InMemorySimulationStore()
        val sessionId = "restart-session"
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        simulationStore.appendEvent(
            testRunEvent(
                eventId = "old-event",
                sessionId = sessionId,
                feedbackStatus = SimulatedEventFeedbackStatus.DELIVERED,
                feedbackDetail = "上次进程已提交系统通知；锁屏是否呈现需现场确认。",
            ),
        )
        val alarm = RecordingSimulationAlarm()
        val coordinator = SimulationFeedbackCoordinator(
            store = simulationStore,
            alarm = alarm,
            scope = scope,
            clock = { now },
        )

        coordinator.follow(sessionId)
        advanceUntilIdle()

        assertTrue(alarm.attempts.isEmpty())
        assertEquals(
            SimulatedEventFeedbackStatus.DELIVERED,
            simulationStore.loadEvents(sessionId).single().feedbackStatus,
        )
    }

    private fun TestScope.fixture(): Fixture {
        val source = ControllableBleScanSource()
        val sessionStore = InMemorySessionStore()
        val simulationStore = com.byd.proximitylab.live.InMemorySimulationStore()
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
            idGenerator = { "feedback-session-${++sessionSequence}" },
        )
        val controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = sessionStore,
            simulationStore = simulationStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = 0L,
        )
        val alarm = RecordingSimulationAlarm()
        val coordinator = SimulationFeedbackCoordinator(
            store = simulationStore,
            alarm = alarm,
            scope = scope,
            clock = { now },
        )
        advanceUntilIdle()
        return Fixture(
            source = source,
            monitor = monitor,
            sessionStore = sessionStore,
            simulationStore = simulationStore,
            recorder = recorder,
            controller = controller,
            alarm = alarm,
            coordinator = coordinator,
            historyReader = SimulationEventHistoryReader(sessionStore, simulationStore),
            scope = scope,
            testScope = this,
        )
    }

    private fun testRunEvent(
        eventId: String,
        sessionId: String,
        feedbackStatus: SimulatedEventFeedbackStatus,
        feedbackDetail: String? = null,
    ): SessionRunEvent = SessionRunEvent(
        eventId = eventId,
        sessionId = sessionId,
        type = ProximityEventType.ENTERED_RANGE,
        reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
        atEpochMillis = now,
        atSessionMillis = 1_000L,
        targetId = "target-1",
        frameId = 1L,
        observedAtEpochMillis = now - 10L,
        rawRssiDbm = -60,
        filteredRssiDbm = -60.0,
        algorithmVersion = "proximity-algorithms/1",
        triggerRuleVersion = "confirmed-far-then-near/1",
        feedbackStatus = feedbackStatus,
        feedbackDetail = feedbackDetail,
        feedbackUpdatedAtEpochMillis = now,
    )

    private inner class Fixture(
        val source: ControllableBleScanSource,
        val monitor: TargetMonitor,
        val sessionStore: InMemorySessionStore,
        val simulationStore: com.byd.proximitylab.live.InMemorySimulationStore,
        val recorder: SessionRecorder,
        val controller: LiveMonitorController,
        val alarm: RecordingSimulationAlarm,
        val coordinator: SimulationFeedbackCoordinator,
        val historyReader: SimulationEventHistoryReader,
        val scope: CoroutineScope,
        private val testScope: TestScope,
    ) {
        fun feed(rssi: Int) {
            now += 1_000L
            sequence += 1
            val emitted = source.emit(
                testAdvertisement(
                    address = addressA,
                    rssi = rssi,
                    observedAtEpochMillis = now - 20L,
                    receivedAtEpochMillis = now - 5L,
                    observedAtNanos = sequence * 1_000_000_000L,
                    receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                    deliverySequence = sequence,
                    payloadFingerprint = "feedback-frame-$sequence",
                ),
            )
            check(emitted) { "扫描事件缓冲拒绝投递" }
            testScope.advanceUntilIdle()
        }

        suspend fun startSimulation(): SamplingSession {
            val result = controller.startSimulation()
            val session = (result as com.byd.proximitylab.live.SimulationStartResult.Started).session
            testScope.advanceUntilIdle()
            return session
        }
    }
}
