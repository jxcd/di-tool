package com.byd.proximitylab.target

import com.byd.proximitylab.ble.BydCandidateClassifier
import com.byd.proximitylab.ble.ManufacturerDataRecord
import com.byd.proximitylab.ble.ScanAvailability
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetMonitorTest {

    private val addressA = "AA:BB:CC:DD:EE:01"
    private val addressB = "AA:BB:CC:DD:EE:02"
    private val addressC = "AA:BB:CC:DD:EE:03"
    private var now = 1_800_000_000_000L

    @Test
    fun scanAvailabilityAndTargetPresenceAreSeparateOutputs() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(address = addressA))
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(source, store, monitorScope, clock = { now })
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()

        assertEquals(ScanAvailability.Scanning, monitor.state.value.scanState)
        assertTrue(monitor.state.value.candidates.isEmpty())
        assertNull(monitor.state.value.lastTargetObservation)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)

        source.setScanState(ScanAvailability.PermissionMissing(revoked = true))
        advanceUntilIdle()
        val permissionMissing = monitor.state.value.scanState
        assertTrue(permissionMissing is ScanAvailability.PermissionMissing)
        assertTrue((permissionMissing as ScanAvailability.PermissionMissing).revoked)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)

        source.setScanState(ScanAvailability.BluetoothOff)
        advanceUntilIdle()
        assertEquals(ScanAvailability.BluetoothOff, monitor.state.value.scanState)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)
    }

    @Test
    fun onlyBoundAddressObservationsAreAcceptedWhenAnotherCandidateIsStronger() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(address = addressA))
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(source, store, monitorScope, clock = { now })
        val events = mutableListOf<TargetEvent>()
        monitorScope.launch { monitor.events.collect { events += it } }
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()

        assertTrue(source.emit(testAdvertisement(address = addressA, rssi = -80)))
        advanceUntilIdle()
        assertTrue(source.emit(testAdvertisement(address = addressB, rssi = -30)))
        advanceUntilIdle()

        val state = monitor.state.value
        assertEquals(2, state.candidates.size)
        assertEquals(addressA, state.lastTargetObservation?.advertisement?.address)
        assertEquals(1, state.targetObservationCount)
        assertTrue(state.targetObservedInCurrentScan)
        assertEquals(IdentityStatus.CONFIRMED, state.identity.status)
        assertEquals(addressA, state.identity.confirmedAddress)

        val accepted = events.filterIsInstance<TargetEvent.ObservationReceived>()
        assertEquals(1, accepted.size)
        assertEquals(addressA, accepted.single().observation.advertisement.address)
        assertTrue(events.any { it is TargetEvent.ScanStateChanged })
        assertTrue(events.any { it is TargetEvent.IdentityChanged })
    }

    @Test
    fun addressChangeWithoutEvidence_keepsBindingAndRaisesIdentityUncertainty() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore()
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(source, store, monitorScope, clock = { now })
        val events = mutableListOf<TargetEvent>()
        monitorScope.launch { monitor.events.collect { events += it } }
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        val bindResult = monitor.bind(addressA)
        assertTrue(bindResult is TargetActionResult.Success)
        val originalTargetId = (bindResult as TargetActionResult.Success).binding.targetId

        monitor.stopScan()
        advanceUntilIdle()
        monitor.startScan()
        advanceUntilIdle()

        source.emit(testAdvertisement(address = addressB, name = "BYD candidate"))
        advanceUntilIdle()

        val state = monitor.state.value
        assertEquals(IdentityStatus.UNCERTAIN, state.identity.status)
        assertEquals(originalTargetId, state.binding?.targetId)
        assertEquals(addressA, state.binding?.address)
        assertEquals(
            IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE,
            state.identity.uncertainty?.reason,
        )
        assertFalse(state.targetObservedInCurrentScan)
        assertTrue(events.any { event ->
            event is TargetEvent.IdentityChanged &&
                event.identity.status == IdentityStatus.UNCERTAIN
        })
    }

    @Test
    fun verifiedEvidenceContinuationPersistsSameTargetIdAndAddress() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(
            testBinding(
                targetId = "stable-target",
                address = addressA,
                verifiedIdentityToken = "verified-token",
            ),
        )
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = store,
            scope = monitorScope,
            policy = TargetAssociationPolicy(tokenProvider()),
            clock = { now },
        )
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(bydAdvertisement(addressB, payload = "verified-token"))
        advanceUntilIdle()

        val state = monitor.state.value
        assertEquals(IdentityStatus.CONFIRMED, state.identity.status)
        assertEquals("stable-target", state.binding?.targetId)
        assertEquals(addressB, state.binding?.address)
        assertEquals(TargetAction.ADDRESS_CONTINUED, state.binding?.lastAction)
        assertEquals(addressB, state.lastTargetObservation?.advertisement?.address)

        val persisted = store.loadBinding()
        assertEquals("stable-target", persisted?.targetId)
        assertEquals(addressB, persisted?.address)
        assertEquals(TargetAction.ADDRESS_CONTINUED, persisted?.lastAction)
    }

    @Test
    fun multipleVerifiedEvidenceCandidatesAfterOriginalAddressAgesOut_reportConflictAndKeepBinding() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(
            testBinding(
                targetId = "stable-target",
                address = addressA,
                verifiedIdentityToken = "verified-token",
            ),
        )
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = store,
            scope = monitorScope,
            policy = TargetAssociationPolicy(tokenProvider()),
            clock = { now },
        )
        val events = mutableListOf<TargetEvent>()
        monitorScope.launch { monitor.events.collect { events += it } }
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(bydAdvertisement(addressA, payload = "verified-token"))
        advanceUntilIdle()
        assertEquals(1, monitor.state.value.targetObservationCount)

        now += 1_000L
        source.emit(bydAdvertisement(addressB, payload = "verified-token"))
        advanceUntilIdle()
        // 原绑定地址仍在候选保留期内：B 只进入待确认列表，不触发地址延续。
        assertEquals(addressA, monitor.state.value.binding?.address)
        assertEquals(IdentityStatus.CONFIRMED, monitor.state.value.identity.status)

        now += TargetMonitor.DEFAULT_CANDIDATE_RETENTION_MILLIS
        source.emit(bydAdvertisement(addressC, payload = "verified-token"))
        advanceUntilIdle()

        val state = monitor.state.value
        assertEquals("stable-target", state.binding?.targetId)
        assertEquals(addressA, state.binding?.address)
        assertEquals(IdentityStatus.UNCERTAIN, state.identity.status)
        assertEquals(
            IdentityUncertaintyReason.MULTIPLE_VERIFIED_IDENTITY_CANDIDATES,
            state.identity.uncertainty?.reason,
        )
        assertEquals(listOf(addressB, addressC), state.identity.pendingCandidateAddresses)
        assertEquals(1, state.targetObservationCount)
        assertEquals(addressA, state.lastTargetObservation?.advertisement?.address)
        assertEquals(
            listOf(addressA),
            events.filterIsInstance<TargetEvent.ObservationReceived>()
                .map { it.observation.advertisement.address },
        )
        assertEquals(addressA, store.loadBinding()?.address)
        assertEquals("stable-target", store.loadBinding()?.targetId)
    }

    @Test
    fun verifiedEvidenceDisappearingKeepsBindingAndDoesNotAcceptNewAddress() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(
            testBinding(
                targetId = "stable-target",
                address = addressA,
                verifiedIdentityToken = "verified-token",
            ),
        )
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = store,
            scope = monitorScope,
            policy = TargetAssociationPolicy(tokenProvider()),
            clock = { now },
        )
        val events = mutableListOf<TargetEvent>()
        monitorScope.launch { monitor.events.collect { events += it } }
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()

        // 原地址未出现，候选缺少可复验证据：保持原绑定，不接受该观测。
        source.emit(testAdvertisement(address = addressB, name = "Generic candidate"))
        advanceUntilIdle()

        val uncertain = monitor.state.value
        assertEquals(IdentityStatus.UNCERTAIN, uncertain.identity.status)
        assertEquals(
            IdentityUncertaintyReason.NO_VERIFIED_IDENTITY_EVIDENCE,
            uncertain.identity.uncertainty?.reason,
        )
        assertEquals(addressA, uncertain.binding?.address)
        assertEquals("stable-target", uncertain.binding?.targetId)
        assertEquals(0, uncertain.targetObservationCount)
        assertNull(uncertain.lastTargetObservation)
        assertTrue(events.none { it is TargetEvent.ObservationReceived })
        assertEquals(addressA, store.loadBinding()?.address)
        assertEquals("stable-target", store.loadBinding()?.targetId)

        // 原地址重新出现后恢复使用同一目标标识。
        source.emit(bydAdvertisement(addressA, payload = "verified-token"))
        advanceUntilIdle()
        assertEquals(IdentityStatus.CONFIRMED, monitor.state.value.identity.status)
        assertEquals(AssociationBasis.ADDRESS_MATCH, monitor.state.value.identity.basis)
        assertEquals(1, monitor.state.value.targetObservationCount)
        assertEquals("stable-target", monitor.state.value.lastTargetObservation?.targetId)
    }

    @Test
    fun manualReconfirmationKeepsTargetIdAndRebindCreatesNewTargetId() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore()
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(source, store, monitorScope, clock = { now })
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        val bound = monitor.bind(addressA)
        assertTrue(bound is TargetActionResult.Success)
        val originalTargetId = (bound as TargetActionResult.Success).binding.targetId

        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        assertEquals(1, monitor.state.value.targetObservationCount)

        source.emit(testAdvertisement(address = addressB))
        advanceUntilIdle()
        val reconfirmed = monitor.reconfirmAsOriginal(addressB)
        assertTrue(reconfirmed is TargetActionResult.Success)
        assertEquals(originalTargetId, (reconfirmed as TargetActionResult.Success).binding.targetId)
        assertEquals(addressB, store.loadBinding()?.address)
        assertEquals(0, monitor.state.value.targetObservationCount)
        assertNull(monitor.state.value.lastTargetObservation)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)

        source.emit(testAdvertisement(address = addressC))
        advanceUntilIdle()
        val rebound = monitor.rebindTo(addressC)
        assertTrue(rebound is TargetActionResult.Success)
        val reboundBinding = (rebound as TargetActionResult.Success).binding
        assertNotEquals(originalTargetId, reboundBinding.targetId)
        assertEquals(addressC, store.loadBinding()?.address)
        assertEquals(reboundBinding.targetId, store.loadBinding()?.targetId)
    }

    @Test
    fun rebindAndClearResetPerTargetObservationState() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore()
        val monitor = TargetMonitor(source, store, testMonitorScope(), clock = { now })
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        val bound = monitor.bind(addressA)
        assertTrue(bound is TargetActionResult.Success)
        val originalTargetId = (bound as TargetActionResult.Success).binding.targetId
        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        assertEquals(1, monitor.state.value.targetObservationCount)

        source.emit(testAdvertisement(address = addressC))
        advanceUntilIdle()
        val rebound = monitor.rebindTo(addressC)
        assertTrue(rebound is TargetActionResult.Success)
        val reboundBinding = (rebound as TargetActionResult.Success).binding
        assertNotEquals(originalTargetId, reboundBinding.targetId)
        assertEquals(reboundBinding.targetId, monitor.state.value.identity.targetId)
        assertEquals(0, monitor.state.value.targetObservationCount)
        assertNull(monitor.state.value.lastTargetObservation)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)

        source.emit(testAdvertisement(address = addressC))
        advanceUntilIdle()
        assertEquals(1, monitor.state.value.targetObservationCount)
        assertEquals(addressC, monitor.state.value.lastTargetObservation?.advertisement?.address)
        assertEquals(reboundBinding.targetId, monitor.state.value.lastTargetObservation?.targetId)

        monitor.clearBinding()
        advanceUntilIdle()
        assertNull(monitor.state.value.binding)
        assertEquals(IdentityStatus.UNBOUND, monitor.state.value.identity.status)
        assertNull(monitor.state.value.identity.targetId)
        assertEquals(0, monitor.state.value.targetObservationCount)
        assertNull(monitor.state.value.lastTargetObservation)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)

        val boundAgain = monitor.bind(addressA)
        assertTrue(boundAgain is TargetActionResult.Success)
        val boundAgainBinding = (boundAgain as TargetActionResult.Success).binding
        assertNotEquals(originalTargetId, boundAgainBinding.targetId)
        assertEquals(boundAgainBinding.targetId, monitor.state.value.identity.targetId)
        assertEquals(0, monitor.state.value.targetObservationCount)
        assertNull(monitor.state.value.lastTargetObservation)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)
    }

    @Test
    fun identityUncertaintySurvivesScanRestartWithoutNewEvidence() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(address = addressA))
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(source, store, monitorScope, clock = { now })
        val events = mutableListOf<TargetEvent>()
        monitorScope.launch { monitor.events.collect { events += it } }
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressB))
        advanceUntilIdle()
        assertEquals(IdentityStatus.UNCERTAIN, monitor.state.value.identity.status)

        monitor.stopScan()
        advanceUntilIdle()
        val restartEventIndex = events.size
        monitor.startScan()
        advanceUntilIdle()

        val restarted = monitor.state.value
        assertEquals(IdentityStatus.UNCERTAIN, restarted.identity.status)
        assertEquals(addressA, restarted.binding?.address)
        assertTrue(restarted.candidates.isEmpty())
        assertTrue(restarted.identity.pendingCandidateAddresses.isEmpty())
        assertEquals(
            "扫描重新开始；保留已记录的身份不确定，等待新的观测或人工确认。",
            restarted.identity.evidenceDescription,
        )
        assertEquals(0, restarted.targetObservationCount)
        assertNull(restarted.lastTargetObservation)
        // 重新开始扫描本身不是新观测，不能产生“身份已确认”的转换。
        assertTrue(
            events.drop(restartEventIndex).none { event ->
                event is TargetEvent.IdentityChanged &&
                    event.identity.status == IdentityStatus.CONFIRMED
            },
        )

        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        assertEquals(IdentityStatus.CONFIRMED, monitor.state.value.identity.status)
        assertEquals(AssociationBasis.ADDRESS_MATCH, monitor.state.value.identity.basis)
    }

    @Test
    fun scanRestartDropsConfirmedSessionEvidenceAndPendingCandidates() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(address = addressA))
        val monitor = TargetMonitor(source, store, testMonitorScope(), clock = { now })
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressB))
        advanceUntilIdle()

        val beforeRestart = monitor.state.value.identity
        assertEquals(IdentityStatus.CONFIRMED, beforeRestart.status)
        assertEquals(AssociationBasis.BOUND_ADDRESS_RECENTLY_OBSERVED, beforeRestart.basis)
        assertEquals(listOf(addressB), beforeRestart.pendingCandidateAddresses)

        monitor.stopScan()
        advanceUntilIdle()
        monitor.startScan()
        advanceUntilIdle()

        val restarted = monitor.state.value
        assertEquals(IdentityStatus.CONFIRMED, restarted.identity.status)
        // 新扫描已清空候选；不能继续展示上一会话的匹配依据和待确认候选。
        assertEquals(AssociationBasis.PERSISTED_BINDING, restarted.identity.basis)
        assertTrue(restarted.identity.pendingCandidateAddresses.isEmpty())
        assertEquals(addressA, restarted.identity.confirmedAddress)
        assertEquals(0, restarted.targetObservationCount)
        assertNull(restarted.lastTargetObservation)

        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        assertEquals(AssociationBasis.ADDRESS_MATCH, monitor.state.value.identity.basis)
        assertEquals(1, monitor.state.value.targetObservationCount)
    }

    @Test
    fun identityUncertaintySurvivesBluetoothAndPermissionRecovery() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(address = addressA))
        val monitor = TargetMonitor(source, store, testMonitorScope(), clock = { now })
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressB))
        advanceUntilIdle()
        assertEquals(IdentityStatus.UNCERTAIN, monitor.state.value.identity.status)

        source.setScanState(ScanAvailability.BluetoothOff)
        advanceUntilIdle()
        assertEquals(ScanAvailability.BluetoothOff, monitor.state.value.scanState)

        monitor.startScan()
        advanceUntilIdle()
        assertEquals(ScanAvailability.Scanning, monitor.state.value.scanState)
        assertEquals(IdentityStatus.UNCERTAIN, monitor.state.value.identity.status)
        assertTrue(monitor.state.value.identity.pendingCandidateAddresses.isEmpty())
        assertEquals(addressA, monitor.state.value.binding?.address)
        assertEquals(0, monitor.state.value.targetObservationCount)
        assertNull(monitor.state.value.lastTargetObservation)

        source.setScanState(ScanAvailability.PermissionMissing(revoked = true))
        advanceUntilIdle()
        monitor.startScan()
        advanceUntilIdle()
        assertEquals(ScanAvailability.Scanning, monitor.state.value.scanState)
        assertEquals(IdentityStatus.UNCERTAIN, monitor.state.value.identity.status)

        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        assertEquals(IdentityStatus.CONFIRMED, monitor.state.value.identity.status)
        assertEquals(AssociationBasis.ADDRESS_MATCH, monitor.state.value.identity.basis)
    }

    @Test
    fun storeReloadOfNewBindingResetsTargetObservationState() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(targetId = "target-A", address = addressA))
        val monitor = TargetMonitor(source, store, testMonitorScope(), clock = { now })
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        assertEquals(1, monitor.state.value.targetObservationCount)

        store.saveBinding(testBinding(targetId = "target-B", address = addressB))
        advanceUntilIdle()

        assertEquals("target-B", monitor.state.value.binding?.targetId)
        assertEquals(addressB, monitor.state.value.binding?.address)
        assertEquals("target-B", monitor.state.value.identity.targetId)
        assertEquals(IdentityStatus.CONFIRMED, monitor.state.value.identity.status)
        assertEquals(0, monitor.state.value.targetObservationCount)
        assertNull(monitor.state.value.lastTargetObservation)
        assertFalse(monitor.state.value.targetObservedInCurrentScan)
    }

    @Test
    fun storeReloadWithSameTargetIdButNewAddressRefreshesIdentityAndObservationState() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(targetId = "target-A", address = addressA))
        val monitor = TargetMonitor(source, store, testMonitorScope(), clock = { now })
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()
        source.emit(testAdvertisement(address = addressA))
        advanceUntilIdle()
        assertEquals(IdentityStatus.CONFIRMED, monitor.state.value.identity.status)
        assertEquals(addressA, monitor.state.value.identity.bindingAddress)
        assertEquals(1, monitor.state.value.targetObservationCount)

        store.saveBinding(
            testBinding(
                targetId = "target-A",
                address = addressB,
                lastAction = TargetAction.ADDRESS_CONTINUED,
            ),
        )
        advanceUntilIdle()

        val reloaded = monitor.state.value
        assertEquals("target-A", reloaded.binding?.targetId)
        assertEquals(addressB, reloaded.binding?.address)
        assertEquals(addressB, reloaded.identity.bindingAddress)
        assertEquals(addressB, reloaded.identity.confirmedAddress)
        assertEquals(AssociationBasis.PERSISTED_BINDING, reloaded.identity.basis)
        assertEquals(IdentityStatus.CONFIRMED, reloaded.identity.status)
        assertEquals(0, reloaded.targetObservationCount)
        assertNull(reloaded.lastTargetObservation)
        assertFalse(reloaded.targetObservedInCurrentScan)
    }

    @Test
    fun repeatedDeliveryRetainsDistinctDeliveryMetadataForDownstreamDeduplication() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore(testBinding(address = addressA))
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(source, store, monitorScope, clock = { now })
        val events = mutableListOf<TargetEvent>()
        monitorScope.launch { monitor.events.collect { events += it } }
        advanceUntilIdle()

        monitor.startScan()
        advanceUntilIdle()

        val first = testAdvertisement(
            address = addressA,
            observedAtNanos = 5_000_000_000L,
            receivedAtNanos = 5_100_000_000L,
            deliverySequence = 10L,
            payloadFingerprint = "same-frame",
        )
        val redelivery = first.copy(
            receivedAtNanos = 5_200_000_000L,
            receivedAtEpochMillis = first.receivedAtEpochMillis + 100L,
            deliverySequence = 11L,
        )

        source.emit(first)
        advanceUntilIdle()
        source.emit(redelivery)
        advanceUntilIdle()

        assertTrue(first.identity.hasSameScanFrameAs(redelivery.identity))
        assertNotEquals(first.identity.receivedAtNanos, redelivery.identity.receivedAtNanos)
        assertNotEquals(first.identity.deliverySequence, redelivery.identity.deliverySequence)

        val observations = events.filterIsInstance<TargetEvent.ObservationReceived>()
        assertEquals(2, observations.size)
        assertEquals(first.identity.deliverySequence, observations[0].observation.advertisement.identity.deliverySequence)
        assertEquals(redelivery.identity.deliverySequence, observations[1].observation.advertisement.identity.deliverySequence)
        assertEquals(2, monitor.state.value.targetObservationCount)
    }

    @Test
    fun scanFailureAndRestartHaveDistinctStates() = runTest {
        val source = ControllableBleScanSource()
        val store = InMemoryTargetBindingStore()
        val monitorScope = testMonitorScope()
        val monitor = TargetMonitor(source, store, monitorScope, clock = { now })
        advanceUntilIdle()

        source.setScanState(ScanAvailability.Failed("测试扫描错误"))
        advanceUntilIdle()
        assertEquals(ScanAvailability.Failed("测试扫描错误"), monitor.state.value.scanState)

        monitor.startScan()
        advanceUntilIdle()
        assertEquals(ScanAvailability.Scanning, monitor.state.value.scanState)
        assertEquals(1, source.startCount)

        monitor.stopScan()
        advanceUntilIdle()
        assertEquals(ScanAvailability.Stopped, monitor.state.value.scanState)
        assertEquals(1, source.stopCount)
    }

    private fun TestScope.testMonitorScope(): CoroutineScope = CoroutineScope(
        SupervisorJob(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler),
    )

    private fun bydAdvertisement(address: String, payload: String) = testAdvertisement(
        address = address,
        name = "BYD candidate",
        serviceUuids = listOf(BydCandidateClassifier.CLASSIC_SERVICE_UUID),
        manufacturerData = listOf(
            ManufacturerDataRecord(
                companyId = BydCandidateClassifier.BYD_MANUFACTURER_ID,
                payloadHex = payload,
            ),
        ),
    )

    private fun tokenProvider(): VerifiedIdentityEvidenceProvider =
        VerifiedIdentityEvidenceProvider { advertisement ->
            advertisement.manufacturerData
                .firstOrNull { it.companyId == BydCandidateClassifier.BYD_MANUFACTURER_ID }
                ?.let { VerifiedIdentityEvidence(it.payloadHex, "测试可复验身份证据") }
        }
}
