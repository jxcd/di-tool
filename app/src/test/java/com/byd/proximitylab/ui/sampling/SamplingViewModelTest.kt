package com.byd.proximitylab.ui.sampling

import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.InMemorySessionStore
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionMarkerRecorder
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.InMemoryTargetBindingStore
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
class SamplingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val addressA = "AA:BB:CC:DD:EE:01"
    private var now = 1_800_000_000_000L
    private var sessionSequence = 0L
    private var markerSequence = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startAndStopUseRecorderAndExposeReopenableDetail() = runTest(dispatcher) {
        val fixture = createViewModel()
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        assertEquals(addressA, viewModel.uiState.value.binding?.address)

        viewModel.updateName("主驾定点")
        viewModel.selectLocation(FixedSampleLocation.DRIVER_SEAT)
        viewModel.selectPhonePlacement(PhonePlacement.POCKET)
        viewModel.updateEstimatedDistance("2.5")
        viewModel.startSampling()
        advanceUntilIdle()

        val active = requireNotNull(viewModel.uiState.value.activeSession)
        assertEquals(SamplingType.FIXED_POINT, active.samplingType)
        assertEquals("主驾定点", active.name)
        assertEquals(FixedSampleLocation.DRIVER_SEAT, active.location)
        assertEquals(PhonePlacement.POCKET, active.phonePlacement)
        assertEquals(2.5, active.estimatedDistanceMeters ?: Double.NaN, 0.0)
        assertEquals(SessionLifecycleStatus.RECORDING, active.status)

        now += 1_000L
        fixture.emit(rssi = -75, sequence = 1)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.sessions.single().observationCount)

        now += 2_000L
        viewModel.stopSampling()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.activeSession)
        val completed = requireNotNull(fixture.store.loadSession(active.id))
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(now, completed.endedAtEpochMillis)

        viewModel.openSession(completed.id)
        advanceUntilIdle()
        val detail = requireNotNull(viewModel.uiState.value.detail)
        assertEquals(completed, detail.session)
        assertEquals(1, detail.observationCount)
        val timelineObservations = detail.timeline.filter {
            it.event is SessionTimelineEvent.Observation
        }
        assertEquals(1, timelineObservations.size)
        assertEquals(
            -75,
            (timelineObservations.single().event as SessionTimelineEvent.Observation).rawRssiDbm,
        )
        // 定点采样给出样本数、均值、中位数和标准差；只有一条样本时波动为 0。
        val statistics = requireNotNull(detail.statistics)
        assertEquals(1, statistics.sampleCount)
        assertEquals(-75.0, statistics.meanDbm, 1e-9)
        assertEquals(0.0, statistics.standardDeviationDbm, 1e-9)

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.detail)
    }

    @Test
    fun walkingSessionRecordsMarkersOnTheSameSessionTimeBaseWithoutTouchingSamples() = runTest(dispatcher) {
        val fixture = createViewModel()
        val viewModel = fixture.viewModel
        advanceUntilIdle()

        viewModel.selectSamplingType(SamplingType.WALKING)
        assertEquals("行走采样", viewModel.uiState.value.form.name)
        viewModel.startSampling()
        advanceUntilIdle()
        val active = requireNotNull(viewModel.uiState.value.activeSession)
        assertEquals(SamplingType.WALKING, active.samplingType)

        now += 1_000L
        fixture.emit(rssi = -80, sequence = 1)
        now += 1_000L
        viewModel.addReferenceMarker(ReferenceMarkerKind.APPROACH_START)
        advanceUntilIdle()
        val approachAt = now

        now += 1_000L
        fixture.emit(rssi = -60, sequence = 2)
        now += 1_000L
        viewModel.addReferenceMarker(ReferenceMarkerKind.EXPECTED_TRIGGER)
        advanceUntilIdle()
        val expectedAt = now

        now += 1_000L
        viewModel.addReferenceMarker(ReferenceMarkerKind.DEPARTURE_START)
        advanceUntilIdle()

        now += 1_000L
        viewModel.stopSampling()
        advanceUntilIdle()
        val completed = requireNotNull(fixture.store.loadSession(active.id))
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(SessionEvaluationLabel.EXPECTED_TRIGGER, completed.evaluationLabel)

        val markers = fixture.store.loadReferenceMarkers(active.id)
        assertEquals(listOf(ReferenceMarkerKind.APPROACH_START, ReferenceMarkerKind.EXPECTED_TRIGGER), markers.take(2).map { it.kind })
        assertEquals(listOf(approachAt, expectedAt), markers.take(2).map { it.atEpochMillis })
        assertEquals(2_000L, markers.first().atEpochMillis - completed.startedAtEpochMillis)

        viewModel.openSession(completed.id)
        advanceUntilIdle()
        val detail = requireNotNull(viewModel.uiState.value.detail)
        assertEquals(SamplingType.WALKING, detail.session.samplingType)
        assertEquals(3, detail.markers.size)
        assertNull(detail.statistics)
        assertEquals(expectedAt, detail.evaluation.expectedTriggerAtEpochMillis)
        assertTrue(detail.evaluation.isPositiveSample)
        assertEquals(2, detail.observationCount)
        // 人工标记进入详情图表；原始样本数不变。
        assertEquals(3, detail.chart.markers.size)
        assertEquals(2, detail.chart.rawSeries?.sourcePointCount)
    }

    @Test
    fun renameAndLabelChangeOnlyMetadataWhileDeleteAffectsOnlySelectedSession() = runTest(dispatcher) {
        val fixture = createViewModel()
        val viewModel = fixture.viewModel
        advanceUntilIdle()

        viewModel.selectSamplingType(SamplingType.FIXED_POINT)
        viewModel.updateName("主驾定点")
        viewModel.selectLocation(FixedSampleLocation.DRIVER_SEAT)
        viewModel.startSampling()
        advanceUntilIdle()
        val firstId = requireNotNull(viewModel.uiState.value.activeSession).id
        now += 1_000L
        fixture.emit(rssi = -70, sequence = 1)
        now += 1_000L
        viewModel.stopSampling()
        advanceUntilIdle()

        viewModel.selectSamplingType(SamplingType.WALKING)
        viewModel.updateName("车头行走")
        viewModel.selectLocation(FixedSampleLocation.FRONT)
        viewModel.startSampling()
        advanceUntilIdle()
        val secondId = requireNotNull(viewModel.uiState.value.activeSession).id
        now += 1_000L
        fixture.emit(rssi = -65, sequence = 2)
        now += 1_000L
        viewModel.addReferenceMarker(ReferenceMarkerKind.EXPECTED_TRIGGER)
        advanceUntilIdle()
        now += 1_000L
        viewModel.stopSampling()
        advanceUntilIdle()

        val firstTimeline = fixture.store.loadTimeline(firstId)
        val secondTimeline = fixture.store.loadTimeline(secondId)

        viewModel.renameSession(firstId, "主驾定点（改名）")
        advanceUntilIdle()
        viewModel.setEvaluationLabel(firstId, SessionEvaluationLabel.NO_TRIGGER)
        advanceUntilIdle()

        val renamed = requireNotNull(fixture.store.loadSession(firstId))
        assertEquals("主驾定点（改名）", renamed.name)
        assertEquals(SessionEvaluationLabel.NO_TRIGGER, renamed.evaluationLabel)
        // 改名和改标签不触碰原始时间线，另一条 Session 也不受影响。
        assertEquals(firstTimeline, fixture.store.loadTimeline(firstId))
        assertEquals(secondTimeline, fixture.store.loadTimeline(secondId))

        viewModel.requestDeleteSession(firstId)
        assertNotNull(viewModel.uiState.value.deleteCandidate)
        viewModel.confirmDeleteSession()
        advanceUntilIdle()

        assertNull(fixture.store.loadSession(firstId))
        assertNotNull(fixture.store.loadSession(secondId))
        assertEquals(secondTimeline, fixture.store.loadTimeline(secondId))
        assertEquals(
            listOf(secondId),
            viewModel.uiState.value.sessions.map { it.session.id },
        )
        assertTrue(fixture.store.loadReferenceMarkers(firstId).isEmpty())
        assertEquals(1, fixture.store.loadReferenceMarkers(secondId).size)
    }

    @Test
    fun filtersNarrowTheListByTypeLocationAndDeviceWithoutDeletingData() = runTest(dispatcher) {
        val fixture = createViewModel()
        val viewModel = fixture.viewModel
        advanceUntilIdle()

        viewModel.updateName("定点车尾")
        viewModel.selectLocation(FixedSampleLocation.REAR)
        viewModel.startSampling()
        advanceUntilIdle()
        val fixedId = requireNotNull(viewModel.uiState.value.activeSession).id
        viewModel.stopSampling()
        advanceUntilIdle()

        viewModel.selectSamplingType(SamplingType.WALKING)
        viewModel.updateName("行走车头")
        viewModel.selectLocation(FixedSampleLocation.FRONT)
        viewModel.startSampling()
        advanceUntilIdle()
        val walkId = requireNotNull(viewModel.uiState.value.activeSession).id
        viewModel.stopSampling()
        advanceUntilIdle()

        // 直接经生产存储加入另一设备的记录，验证设备筛选使用稳定 targetId。
        fixture.store.createSession(
            completedSession(
                id = "other-device",
                targetId = "target-2",
                targetDisplayName = "另一台车",
                type = SamplingType.WALKING,
                location = FixedSampleLocation.REAR,
            ),
        )
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.filteredSessions.size)
        assertEquals(2, viewModel.uiState.value.deviceOptions.size)

        viewModel.selectSamplingTypeFilter(SamplingType.WALKING)
        assertEquals(setOf(walkId, "other-device"), viewModel.uiState.value.filteredSessions.map { it.session.id }.toSet())

        viewModel.selectLocationFilter(FixedSampleLocation.REAR)
        assertEquals(listOf("other-device"), viewModel.uiState.value.filteredSessions.map { it.session.id })

        viewModel.selectDeviceFilter("target-1")
        assertTrue(viewModel.uiState.value.filteredSessions.isEmpty())
        assertEquals(3, viewModel.uiState.value.sessions.size)

        viewModel.clearFilters()
        assertEquals(3, viewModel.uiState.value.filteredSessions.size)
        assertNotNull(fixture.store.loadSession(fixedId))
    }

    @Test
    fun invalidDistanceStopsBeforeCreatingSession() = runTest(dispatcher) {
        val fixture = createViewModel()
        val viewModel = fixture.viewModel
        advanceUntilIdle()

        viewModel.updateEstimatedDistance("not-a-number")
        viewModel.startSampling()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeSession)
        assertTrue(viewModel.uiState.value.message?.contains("数字") == true)
        assertTrue(fixture.store.observeSessions().first().isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.createViewModel(): Fixture {
        val source = ControllableBleScanSource()
        val bindingStore = InMemoryTargetBindingStore(
            testBinding(targetId = "target-1", address = addressA),
        )
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        val store = InMemorySessionStore()
        val recorder = SessionRecorder(
            store = store,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "ui-session-${++sessionSequence}" },
        )
        val markerRecorder = SessionMarkerRecorder(
            store = store,
            clock = { now },
            idGenerator = { "marker-${++markerSequence}" },
        )
        val viewModel = SamplingViewModel(
            recorder = recorder,
            store = store,
            observationSource = monitor,
            markerRecorder = markerRecorder,
            clock = { now },
        )
        return Fixture(viewModel, source, store, bindingStore)
    }

    private fun completedSession(
        id: String,
        targetId: String,
        targetDisplayName: String,
        type: SamplingType,
        location: FixedSampleLocation,
    ): SamplingSession = SamplingSession(
        id = id,
        targetId = targetId,
        targetDisplayName = targetDisplayName,
        targetAddress = "AA:BB:CC:DD:EE:02",
        samplingType = type,
        name = id,
        location = location,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.COMPLETED,
        startedAtEpochMillis = now - 5_000L,
        endedAtEpochMillis = now,
        lastRecordedAtEpochMillis = now,
        interruptedDetectedAtEpochMillis = null,
    )

    private inner class Fixture(
        val viewModel: SamplingViewModel,
        val source: ControllableBleScanSource,
        val store: InMemorySessionStore,
        val bindingStore: InMemoryTargetBindingStore,
    ) {
        fun emit(rssi: Int, sequence: Long) {
            source.emit(
                testAdvertisement(
                    address = "AA:BB:CC:DD:EE:01",
                    rssi = rssi,
                    observedAtEpochMillis = now - 20L,
                    receivedAtEpochMillis = now - 5L,
                    deliverySequence = sequence,
                    payloadFingerprint = "ui-sample-$sequence",
                ),
            )
        }
    }
}
