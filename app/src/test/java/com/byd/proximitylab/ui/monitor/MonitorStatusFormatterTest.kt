package com.byd.proximitylab.ui.monitor

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.live.LiveDecisionView
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.live.LiveReadingPoint
import com.byd.proximitylab.live.observationIntervalMillis
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.testing.testBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorStatusFormatterTest {

    private val initial = LiveDecisionView.initial(BuiltInSessionConfigs.DEFAULT)

    @Test
    fun noBindingIsDistinct() {
        val status = MonitorStatusFormatter.describe(LiveMonitorState())

        assertEquals(MonitorHealth.NO_BINDING, status.health)
        assertTrue(status.headline.contains("未绑定"))
    }

    @Test
    fun scanUnavailableStatesKeepTheirReason() {
        val off = MonitorStatusFormatter.describe(
            state(scanState = ScanAvailability.BluetoothOff),
        )
        assertEquals(MonitorHealth.SCAN_UNAVAILABLE, off.health)
        assertTrue(off.headline.contains("蓝牙"))

        val permission = MonitorStatusFormatter.describe(
            state(scanState = ScanAvailability.PermissionMissing(revoked = false)),
        )
        assertEquals(MonitorHealth.SCAN_UNAVAILABLE, permission.health)
        assertTrue(permission.headline.contains("权限"))

        val stopped = MonitorStatusFormatter.describe(
            state(scanState = ScanAvailability.Stopped),
        )
        assertEquals(MonitorHealth.SCAN_STOPPED, stopped.health)
        assertTrue(stopped.headline.contains("停止"))

        val idle = MonitorStatusFormatter.describe(
            state(scanState = ScanAvailability.Idle),
        )
        assertEquals(MonitorHealth.SCAN_NOT_STARTED, idle.health)
    }

    @Test
    fun noSamplesExpiredAndConfirmedFarAreDistinct() {
        val noSamples = MonitorStatusFormatter.describe(
            state(scanState = ScanAvailability.Scanning),
        )
        assertEquals(MonitorHealth.NO_SAMPLES, noSamples.health)
        assertTrue(noSamples.headline.contains("尚无"))

        val expired = MonitorStatusFormatter.describe(
            state(
                scanState = ScanAvailability.Scanning,
                monitor = initial.copy(
                    state = ProximityState.UNKNOWN,
                    reason = ProximityStateReason.SAMPLES_EXPIRED,
                    lastObservationAtEpochMillis = 1_000L,
                ),
            ),
        )
        assertEquals(MonitorHealth.EXPIRED, expired.health)
        assertTrue(expired.headline.contains("过期"))

        val far = MonitorStatusFormatter.describe(
            state(
                scanState = ScanAvailability.Scanning,
                monitor = initial.copy(
                    state = ProximityState.FAR,
                    reason = ProximityStateReason.CONFIRMED_FAR,
                    rawRssiDbm = -85,
                    filteredRssiDbm = -85.0,
                    lastObservationAtEpochMillis = 1_000L,
                ),
            ),
        )
        assertEquals(MonitorHealth.CONFIRMED_FAR, far.health)
        assertTrue(far.headline.contains("FAR"))
        assertTrue(far.detail.contains("-85"))

        assertEquals(4, setOf(
            noSamples.health,
            expired.health,
            far.health,
            MonitorHealth.SCAN_UNAVAILABLE,
        ).size)
    }

    @Test
    fun identityUncertaintyIsShownBeforeSampleStatus() {
        val status = MonitorStatusFormatter.describe(
            state(
                scanState = ScanAvailability.Scanning,
                identityStatus = IdentityStatus.UNCERTAIN,
                identityDetail = "候选地址冲突",
            ),
        )

        assertEquals(MonitorHealth.IDENTITY_UNCERTAIN, status.health)
        assertEquals("候选地址冲突", status.detail)
    }

    @Test
    fun lastObservationFallsBackToDisplaySamplesAfterInvalidation() {
        val samples = listOf(
            LiveReadingPoint(
                atEpochMillis = 1_000L,
                atSessionMillis = null,
                observedAtEpochMillis = 990L,
                frameId = 1L,
                rawRssiDbm = -85,
                filteredRssiDbm = -85.0,
                state = ProximityState.FAR,
                reason = ProximityStateReason.CONFIRMED_FAR,
                trend = MotionTrend.STABLE,
            ),
            LiveReadingPoint(
                atEpochMillis = 2_000L,
                atSessionMillis = null,
                observedAtEpochMillis = 1_990L,
                frameId = 2L,
                rawRssiDbm = -85,
                filteredRssiDbm = -85.0,
                state = ProximityState.FAR,
                reason = ProximityStateReason.FAR_MAINTAINED,
                trend = MotionTrend.STABLE,
            ),
        )
        val state = LiveMonitorState(
            binding = testBinding(),
            scanState = ScanAvailability.Scanning,
            identityStatus = IdentityStatus.CONFIRMED,
            monitor = initial.copy(
                state = ProximityState.UNKNOWN,
                reason = ProximityStateReason.SAMPLES_EXPIRED,
                lastObservationAtEpochMillis = null,
            ),
            monitorSamples = samples,
        )

        assertEquals(1_990L, state.displayLastObservationAtEpochMillis)
        assertEquals(1_000L, observationIntervalMillis(samples))
    }

    private fun state(
        scanState: ScanAvailability,
        identityStatus: IdentityStatus = IdentityStatus.CONFIRMED,
        identityDetail: String? = null,
        monitor: LiveDecisionView = initial,
    ) = LiveMonitorState(
        binding = testBinding(),
        scanState = scanState,
        identityStatus = identityStatus,
        identityDetail = identityDetail,
        monitor = monitor,
    )
}
