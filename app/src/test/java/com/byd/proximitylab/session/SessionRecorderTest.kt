package com.byd.proximitylab.session

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.target.TargetEvent
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.TargetMonitorState
import com.byd.proximitylab.target.TargetObservationSource
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.InMemoryTargetBindingStore
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecorderTest {

    private val addressA = "AA:BB:CC:DD:EE:01"
    private val addressB = "AA:BB:CC:DD:EE:02"
    private var now = 1_800_000_000_000L

    @Test
    fun startCreatesRecordingSessionWithUserMetadata() = runTest {
        val fixture = fixture()
        advanceUntilIdle()

        now += 10L
        val result = fixture.recorder.start(request())

        assertTrue(result is SessionStartResult.Started)
        val started = (result as SessionStartResult.Started).session
        val stored = requireNotNull(fixture.store.loadSession(started.id))
        assertEquals(SamplingType.FIXED_POINT, stored.samplingType)
        assertEquals("车头定点", stored.name)
        assertEquals(FixedSampleLocation.FRONT, stored.location)
        assertEquals(1.5, stored.estimatedDistanceMeters ?: Double.NaN, 0.0)
        assertEquals(PhonePlacement.POCKET, stored.phonePlacement)
        assertEquals("target-1", stored.targetId)
        assertEquals(addressA, stored.targetAddress)
        assertEquals(SessionLifecycleStatus.RECORDING, stored.status)
        assertEquals(now, stored.startedAtEpochMillis)
        assertEquals(now, stored.lastRecordedAtEpochMillis)
        assertNull(stored.endedAtEpochMillis)
        assertNull(stored.interruptedDetectedAtEpochMillis)
        assertEquals(1, fixture.source.startCount)
        assertEquals(stored, fixture.recorder.activeSession.value)
    }

    @Test
    fun observationKeepsRawRssiObservationAndReceiptTimes() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val session = fixture.start()
        advanceUntilIdle()

        now += 1_000L
        val observedAt = now - 120L
        val receivedAt = now - 20L
        fixture.source.emit(
            testAdvertisement(
                address = addressA,
                rssi = -77,
                observedAtEpochMillis = observedAt,
                receivedAtEpochMillis = receivedAt,
                deliverySequence = 7L,
                payloadFingerprint = "payload-7",
            ),
        )
        advanceUntilIdle()

        val observations = fixture.observations(session.id)
        assertEquals(1, observations.size)
        val event = observations.single().event
        assertEquals(-77, event.rawRssiDbm)
        assertEquals(observedAt, event.observedAtEpochMillis)
        assertEquals(receivedAt, event.receivedAtEpochMillis)
        assertEquals(now, event.processedAtEpochMillis)
        assertEquals("payload-7#7", event.observationId)
        assertEquals(7L, event.deliverySequence)
        assertEquals(addressA, event.address)
        assertEquals("payload-7", event.payloadFingerprint)
        assertEquals("target-1", event.targetId)
        assertEquals(
            1,
            fixture.store.observeSessions().first().single { it.session.id == session.id }.observationCount,
        )
    }

    @Test
    fun scanAndIdentityStateChangesAreRecorded() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val session = fixture.start()
        advanceUntilIdle()

        val scanStarted = fixture.timeline(session.id).scanStates().first().event
        assertEquals(true, scanStarted.available)
        assertEquals("SCANNING", scanStarted.stateLabel)

        now += 500L
        fixture.source.emit(testAdvertisement(address = addressB, rssi = -40))
        advanceUntilIdle()

        val identity = fixture.timeline(session.id).identities().last().event
        assertEquals(false, identity.confirmed)
        assertEquals("UNCERTAIN", identity.statusLabel)
        assertEquals("target-1", identity.targetId)
        assertTrue(!identity.detail.isNullOrBlank())
    }

    @Test
    fun normalStopCompletesSessionAndRetainsWrittenTimeline() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val session = fixture.start()

        now += 1_000L
        fixture.source.emit(testAdvertisement(address = addressA, rssi = -85, deliverySequence = 1L))
        advanceUntilIdle()

        now += 2_000L
        val result = fixture.recorder.stop()
        advanceUntilIdle()

        assertTrue(result is SessionStopResult.Stopped)
        val completed = (result as SessionStopResult.Stopped).session
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(now, completed.endedAtEpochMillis)
        assertEquals(now, completed.lastRecordedAtEpochMillis)
        assertEquals(now, completed.coverageEndEpochMillis)
        assertEquals(3_000L, completed.durationMillis)
        assertNull(completed.interruptedDetectedAtEpochMillis)
        assertEquals(1, fixture.source.stopCount)
        assertNull(fixture.recorder.activeSession.value)

        val stored = requireNotNull(fixture.store.loadSession(session.id))
        assertEquals(completed, stored)
        assertEquals(1, fixture.observations(session.id).size)
        val lastScan = fixture.timeline(session.id).scanStates().last().event
        assertEquals(false, lastScan.available)
        assertEquals("STOPPED", lastScan.stateLabel)
        assertEquals(now, lastScan.processedAtEpochMillis)
        assertTrue(fixture.store.loadRecordingSessions().isEmpty())
    }

    @Test
    fun onlyOneSessionCanRecordAtATime() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val first = fixture.start()

        val second = fixture.recorder.start(request(name = "第二个会话"))

        assertTrue(second is SessionStartResult.AlreadyRecording)
        assertEquals(first.id, (second as SessionStartResult.AlreadyRecording).session.id)
        assertEquals(1, fixture.store.observeSessions().first().size)
        assertEquals(1, fixture.source.startCount)
        assertEquals(first.id, fixture.recorder.activeSession.value?.id)
    }

    @Test
    fun recoveryMarksRecordingSessionInterruptedWithoutChangingCoverage() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val session = fixture.start()

        now += 1_000L
        fixture.source.emit(testAdvertisement(address = addressA, rssi = -70, deliverySequence = 3L))
        advanceUntilIdle()
        val coverageEnd = now

        // 模拟进程异常终止后重新创建控制器：只从持久化数据恢复，不执行正常 stop。
        now += 9_000L
        val recovered = fixture.newRecorder(id = "session-2")
        val marked = recovered.recoverInterruptedSessions()
        advanceUntilIdle()

        assertEquals(1, marked)
        val stored = requireNotNull(fixture.store.loadSession(session.id))
        assertEquals(SessionLifecycleStatus.INTERRUPTED, stored.status)
        assertNull(stored.endedAtEpochMillis)
        assertEquals(coverageEnd, stored.lastRecordedAtEpochMillis)
        assertEquals(now, stored.interruptedDetectedAtEpochMillis)
        assertEquals(coverageEnd, stored.coverageEndEpochMillis)
        assertEquals(coverageEnd - session.startedAtEpochMillis, stored.durationMillis)
        assertEquals(1, fixture.observations(session.id).size)
        assertEquals(-70, fixture.observations(session.id).single().event.rawRssiDbm)

        val restarted = recovered.start(request(name = "恢复后采样"))
        assertTrue(restarted is SessionStartResult.Started)
        assertEquals("session-2", (restarted as SessionStartResult.Started).session.id)
    }

    @Test
    fun startingAfterProcessDeathMarksLeftoverRecordingSessionInterrupted() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val stale = fixture.start()
        now += 500L
        fixture.source.emit(testAdvertisement(address = addressA, rssi = -84, deliverySequence = 9L))
        advanceUntilIdle()
        val staleCoverage = now

        now += 4_000L
        val restarted = fixture.newRecorder(id = "fresh-session")
        val result = restarted.start(request(name = "新进程采样"))
        advanceUntilIdle()

        assertTrue(result is SessionStartResult.Started)
        val staleStored = requireNotNull(fixture.store.loadSession(stale.id))
        assertEquals(SessionLifecycleStatus.INTERRUPTED, staleStored.status)
        assertNull(staleStored.endedAtEpochMillis)
        assertEquals(staleCoverage, staleStored.lastRecordedAtEpochMillis)
        assertEquals(now, staleStored.interruptedDetectedAtEpochMillis)
        assertEquals(1, fixture.observations(stale.id).size)
        assertEquals("fresh-session", (result as SessionStartResult.Started).session.id)
        assertEquals(1, fixture.store.loadRecordingSessions().size)
    }

    @Test
    fun redeliveredScanFrameSharesFrameIdButKeepsDistinctDeliveryId() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val session = fixture.start()
        advanceUntilIdle()

        now += 1_000L
        val first = testAdvertisement(
            address = addressA,
            rssi = -71,
            observedAtNanos = 5_000_000_000L,
            receivedAtNanos = 5_100_000_000L,
            observedAtEpochMillis = now - 20L,
            receivedAtEpochMillis = now - 5L,
            payloadFingerprint = "same-scan-frame",
            deliverySequence = 21L,
        )
        val redelivery = first.copy(
            receivedAtNanos = 5_200_000_000L,
            receivedAtEpochMillis = first.receivedAtEpochMillis + 100L,
            deliverySequence = 22L,
        )
        fixture.source.emit(first)
        fixture.source.emit(redelivery)
        advanceUntilIdle()

        val observations = fixture.observations(session.id)
        assertEquals(2, observations.size)
        assertEquals(observations[0].event.scanFrameId, observations[1].event.scanFrameId)
        assertNotEquals(observations[0].event.observationId, observations[1].event.observationId)
        assertEquals(
            scanFrameIdOf(addressA, first.observedAtNanos, first.payloadFingerprint),
            observations[0].event.scanFrameId,
        )
    }

    @Test
    fun sameTimestampEventsKeepTheirArrivalOrder() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val session = fixture.start()
        advanceUntilIdle()

        now += 1_000L
        listOf(11L to -69, 12L to -68, 13L to -67).forEach { (sequence, rssi) ->
            fixture.source.emit(
                testAdvertisement(
                    address = addressA,
                    rssi = rssi,
                    observedAtEpochMillis = now - 10L,
                    receivedAtEpochMillis = now - 5L,
                    deliverySequence = sequence,
                    payloadFingerprint = "same-instant-$sequence",
                ),
            )
        }
        advanceUntilIdle()

        val observations = fixture.observations(session.id)
        assertEquals(
            listOf("same-instant-11#11", "same-instant-12#12", "same-instant-13#13"),
            observations.map { it.event.observationId },
        )
        assertEquals(
            observations.map { it.item.sequence }.sorted(),
            observations.map { it.item.sequence },
        )
        assertTrue(observations.all { it.event.processedAtEpochMillis == now })
        assertEquals(observations.size, observations.map { it.item.sequence }.distinct().size)
    }

    @Test
    fun droppedTargetEventsArePersistedAsTimelineGap() = runTest {
        val store = InMemorySessionStore()
        val source = DropReportingSource()
        val scope = newScope()
        val recorder = SessionRecorder(
            store = store,
            observationSource = source,
            scope = scope,
            clock = { now },
            idGenerator = { "gap-session" },
        )
        advanceUntilIdle()
        recorder.start(request())
        advanceUntilIdle()

        now += 1_000L
        source.reportDropped(3)
        advanceUntilIdle()

        val gaps = store.loadTimeline("gap-session").mapNotNull { item ->
            item.event as? SessionTimelineEvent.EventGap
        }
        assertEquals(1, gaps.size)
        assertEquals(3, gaps.single().droppedEventCount)
        assertEquals(now, gaps.single().processedAtEpochMillis)
        assertEquals(now, store.loadSession("gap-session")?.lastRecordedAtEpochMillis)
        recorder.stop()
    }

    @Test
    fun targetMonitorCountsDroppedEventsWhenEventBufferOverflows() = runTest {
        val source = ControllableBleScanSource()
        val bindingStore = InMemoryTargetBindingStore(
            testBinding(targetId = "target-1", address = addressA),
        )
        val scope = newScope()
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
            eventBufferCapacity = 1,
        )
        advanceUntilIdle()

        // 永不消费的订阅者制造缓冲压力；后续扫描状态事件只能丢弃并计数。
        val blocked = scope.launch {
            monitor.events.collect { awaitCancellation() }
        }
        advanceUntilIdle()
        repeat(10) { index ->
            source.setScanState(
                if (index % 2 == 0) ScanAvailability.Scanning else ScanAvailability.Idle,
            )
            advanceUntilIdle()
        }

        assertTrue(monitor.state.value.droppedTargetEventCount > 0)
        blocked.cancel()
    }

    @Test
    fun stopAfterClockRollbackKeepsConsistentCoverage() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        val session = fixture.start()

        now += 1_000L
        fixture.source.emit(testAdvertisement(address = addressA, rssi = -76, deliverySequence = 31L))
        advanceUntilIdle()
        val recordedAt = now

        // 系统时间回拨到开始之前，正常结束仍要留下可读的完成记录。
        now -= 5_000L
        val completed = (fixture.recorder.stop() as SessionStopResult.Stopped).session

        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(recordedAt, completed.endedAtEpochMillis)
        assertEquals(recordedAt, completed.coverageEndEpochMillis)
        assertEquals(recordedAt - session.startedAtEpochMillis, completed.durationMillis)
        assertEquals(1, fixture.observations(session.id).size)
    }

    @Test
    fun emptySessionUsesConfirmedStopTimeAsCoverage() = runTest {
        val fixture = fixture()
        advanceUntilIdle()
        fixture.start()
        advanceUntilIdle()

        now += 7_000L
        val completed = (fixture.recorder.stop() as SessionStopResult.Stopped).session

        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(now, completed.coverageEndEpochMillis)
        assertEquals(7_000L, completed.durationMillis)
        assertEquals(0, fixture.store.observeSessions().first().single().observationCount)
    }

    @Test
    fun stopWithoutRecordingReportsNotRecording() = runTest {
        val fixture = fixture()
        advanceUntilIdle()

        assertEquals(SessionStopResult.NotRecording, fixture.recorder.stop())
    }

    private fun TestScope.fixture(): Fixture {
        val source = ControllableBleScanSource()
        val store = InMemorySessionStore()
        val bindingStore = InMemoryTargetBindingStore(
            testBinding(targetId = "target-1", address = addressA),
        )
        val scope = newScope()
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
            idGenerator = { "session-1" },
        )
        return Fixture(source, store, bindingStore, monitor, recorder, scope, this, addressA, clock = { now })
    }

    private fun TestScope.newScope(): CoroutineScope = CoroutineScope(
        SupervisorJob(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler),
    )

    private data class Fixture(
        val source: ControllableBleScanSource,
        val store: InMemorySessionStore,
        val bindingStore: InMemoryTargetBindingStore,
        val monitor: TargetMonitor,
        val recorder: SessionRecorder,
        val scope: CoroutineScope,
        val testScope: TestScope,
        val addressA: String,
        val clock: () -> Long,
    ) {
        /**
         * 模拟进程重建：使用新的扫描来源和目标观测入口，只共享已持久化的 Session 存储。
         */
        fun newRecorder(id: String): SessionRecorder {
            val scope = CoroutineScope(
                SupervisorJob(testScope.backgroundScope.coroutineContext[Job]) +
                    UnconfinedTestDispatcher(testScope.testScheduler),
            )
            val monitor = TargetMonitor(
                scanSource = ControllableBleScanSource(),
                bindingStore = bindingStore,
                scope = scope,
                clock = clock,
            )
            return SessionRecorder(
                store = store,
                observationSource = monitor,
                scope = scope,
                clock = clock,
                idGenerator = { id },
            )
        }

        suspend fun start(): SamplingSession =
            (recorder.start(request()) as SessionStartResult.Started).session

        suspend fun timeline(sessionId: String): List<SessionTimelineItem> =
            store.loadTimeline(sessionId)

        suspend fun observations(sessionId: String): List<ObservationTimelineEntry> =
            timeline(sessionId).mapNotNull { item ->
                (item.event as? SessionTimelineEvent.Observation)?.let { ObservationTimelineEntry(item, it) }
            }

        fun request(name: String = "车头定点"): SessionStartRequest = SessionStartRequest(
            binding = testBinding(targetId = "target-1", address = addressA),
            samplingType = SamplingType.FIXED_POINT,
            name = name,
            location = FixedSampleLocation.FRONT,
            estimatedDistanceMeters = 1.5,
            phonePlacement = PhonePlacement.POCKET,
        )
    }

    private data class ObservationTimelineEntry(
        val item: SessionTimelineItem,
        val event: SessionTimelineEvent.Observation,
    )

    private data class ScanTimelineEntry(
        val item: SessionTimelineItem,
        val event: SessionTimelineEvent.ScanStateChanged,
    )

    private data class IdentityTimelineEntry(
        val item: SessionTimelineItem,
        val event: SessionTimelineEvent.IdentityChanged,
    )

    private fun List<SessionTimelineItem>.scanStates(): List<ScanTimelineEntry> =
        mapNotNull { item ->
            (item.event as? SessionTimelineEvent.ScanStateChanged)?.let { ScanTimelineEntry(item, it) }
        }

    private fun List<SessionTimelineItem>.identities(): List<IdentityTimelineEntry> =
        mapNotNull { item ->
            (item.event as? SessionTimelineEvent.IdentityChanged)?.let { IdentityTimelineEntry(item, it) }
        }

    private class DropReportingSource : TargetObservationSource {

        private val stateFlow = MutableStateFlow(TargetMonitorState())
        override val state: StateFlow<TargetMonitorState> = stateFlow.asStateFlow()

        private val eventFlow = MutableSharedFlow<TargetEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<TargetEvent> = eventFlow.asSharedFlow()

        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set

        override fun startScan() {
            startCount += 1
        }

        override fun stopScan() {
            stopCount += 1
        }

        fun reportDropped(count: Int) {
            stateFlow.update { state -> state.copy(droppedTargetEventCount = count) }
        }
    }

    private fun request(name: String = "车头定点"): SessionStartRequest = SessionStartRequest(
        binding = testBinding(targetId = "target-1", address = addressA),
        samplingType = SamplingType.FIXED_POINT,
        name = name,
        location = FixedSampleLocation.FRONT,
        estimatedDistanceMeters = 1.5,
        phonePlacement = PhonePlacement.POCKET,
    )
}
