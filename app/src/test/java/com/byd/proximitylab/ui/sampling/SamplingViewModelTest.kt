package com.byd.proximitylab.ui.sampling

import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.InMemorySessionStore
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionRecorder
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SamplingViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val addressA = "AA:BB:CC:DD:EE:01"
    private var now = 1_800_000_000_000L

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
        val (viewModel, source, store) = createViewModel()
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
        source.emit(
            testAdvertisement(
                address = addressA,
                rssi = -75,
                observedAtEpochMillis = now - 20L,
                receivedAtEpochMillis = now - 5L,
                deliverySequence = 1L,
                payloadFingerprint = "ui-sample-1",
            ),
        )
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.sessions.single().observationCount)

        now += 2_000L
        viewModel.stopSampling()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.activeSession)
        val completed = requireNotNull(store.loadSession(active.id))
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(now, completed.endedAtEpochMillis)

        viewModel.openSession(completed.id)
        advanceUntilIdle()
        val detail = requireNotNull(viewModel.uiState.value.detail)
        assertEquals(completed, detail.session)
        assertEquals(1, detail.observationCount)
        val timelineObservations = detail.timeline.filter {
            it.event is com.byd.proximitylab.session.SessionTimelineEvent.Observation
        }
        assertEquals(1, timelineObservations.size)
        assertEquals(
            -75,
            (timelineObservations.single().event as com.byd.proximitylab.session.SessionTimelineEvent.Observation).rawRssiDbm,
        )

        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.detail)
    }

    @Test
    fun invalidDistanceStopsBeforeCreatingSession() = runTest(dispatcher) {
        val (viewModel, _, store) = createViewModel()
        advanceUntilIdle()

        viewModel.updateEstimatedDistance("not-a-number")
        viewModel.startSampling()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeSession)
        assertTrue(viewModel.uiState.value.message?.contains("数字") == true)
        assertTrue(store.observeSessions().first().isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.createViewModel(): Triple<
        SamplingViewModel,
        ControllableBleScanSource,
        InMemorySessionStore,
        > {
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
            idGenerator = { "ui-session" },
        )
        val viewModel = SamplingViewModel(
            recorder = recorder,
            store = store,
            observationSource = monitor,
            clock = { now },
        )
        return Triple(viewModel, source, store)
    }
}
