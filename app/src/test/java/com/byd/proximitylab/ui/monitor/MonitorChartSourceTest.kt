package com.byd.proximitylab.ui.monitor

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.chart.RssiChartThresholdKind
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.live.LiveDecisionView
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.live.LiveReadingPoint
import com.byd.proximitylab.live.SimulationTestView
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.testing.testBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorChartSourceTest {

    private val initial = LiveDecisionView.initial(BuiltInSessionConfigs.DEFAULT)

    @Test
    fun normalMonitorBuildsRawFilteredThresholdAndStateData() {
        val samples = listOf(
            reading(1_000L, -85, -85.0, ProximityState.UNKNOWN, ProximityStateReason.EVIDENCE_PENDING),
            reading(2_000L, -85, -85.0, ProximityState.FAR, ProximityStateReason.CONFIRMED_FAR),
            reading(3_000L, -60, -60.0, ProximityState.NEAR, ProximityStateReason.CONFIRMED_NEAR),
        )
        val state = LiveMonitorState(
            binding = testBinding(),
            scanState = ScanAvailability.Scanning,
            identityStatus = IdentityStatus.CONFIRMED,
            monitor = initial.copy(
                state = ProximityState.NEAR,
                reason = ProximityStateReason.CONFIRMED_NEAR,
                rawRssiDbm = -60,
                filteredRssiDbm = -60.0,
                lastObservationAtEpochMillis = 3_000L,
                lastDecisionAtEpochMillis = 3_000L,
            ),
            monitorSamples = samples,
        )

        val chart = MonitorChartSource.build(state, RssiChartWindow.ONE_MINUTE)

        assertEquals(MonitorChartMode.MONITOR, chart.mode)
        assertEquals(3, chart.sourceSampleCount)
        assertEquals(3, chart.windowSampleCount)
        assertEquals(3, chart.displayedSampleCount)
        assertEquals(3, chart.data.rawSeries!!.displayedPointCount)
        assertEquals(3, chart.data.filteredSeries!!.displayedPointCount)
        assertTrue(chart.data.markers.isEmpty())
        assertEquals(3, chart.data.stateMarkers.size)
        assertEquals(
            listOf(ProximityState.UNKNOWN, ProximityState.FAR, ProximityState.NEAR),
            chart.data.stateMarkers.map { marker -> marker.state },
        )
        val enter = chart.data.thresholds.first { it.kind == RssiChartThresholdKind.ENTER }
        val exit = chart.data.thresholds.first { it.kind == RssiChartThresholdKind.EXIT }
        assertEquals(-65.0, enter.rssiDbm, 0.0)
        assertEquals(-80.0, exit.rssiDbm, 0.0)
        assertTrue(enter.yFraction < exit.yFraction)
    }

    @Test
    fun windowSwitchOnlyChangesChartWindowAndNotSource() {
        val samples = listOf(
            reading(0L, -85, -85.0, ProximityState.FAR, ProximityStateReason.CONFIRMED_FAR),
            reading(40_000L, -70, -70.0, ProximityState.FAR, ProximityStateReason.FAR_MAINTAINED),
            reading(60_000L, -60, -60.0, ProximityState.NEAR, ProximityStateReason.CONFIRMED_NEAR),
        )
        val state = LiveMonitorState(
            binding = testBinding(),
            scanState = ScanAvailability.Scanning,
            identityStatus = IdentityStatus.CONFIRMED,
            monitor = initial.copy(
                state = ProximityState.NEAR,
                reason = ProximityStateReason.CONFIRMED_NEAR,
                lastDecisionAtEpochMillis = 60_000L,
                lastObservationAtEpochMillis = 60_000L,
            ),
            monitorSamples = samples,
        )

        val thirty = MonitorChartSource.build(state, RssiChartWindow.THIRTY_SECONDS)
        val fiveMinutes = MonitorChartSource.build(state, RssiChartWindow.FIVE_MINUTES)

        assertEquals(3, thirty.sourceSampleCount)
        assertEquals(3, fiveMinutes.sourceSampleCount)
        assertEquals(2, thirty.windowSampleCount)
        assertEquals(3, fiveMinutes.windowSampleCount)
        assertEquals(30_000L, thirty.data.windowStartEpochMillis)
        assertEquals(-240_000L, fiveMinutes.data.windowStartEpochMillis)
        assertEquals(
            thirty.data.thresholds.first().yFraction,
            fiveMinutes.data.thresholds.first().yFraction,
            1e-6f,
        )
    }

    @Test
    fun runningSimulationUsesTestCurveAndEventMarkers() {
        val session = simulationSession()
        val testSamples = listOf(
            reading(101_000L, -85, -85.0, ProximityState.FAR, ProximityStateReason.CONFIRMED_FAR),
            reading(102_000L, -60, -60.0, ProximityState.NEAR, ProximityStateReason.CONFIRMED_NEAR),
        )
        val event = SessionRunEvent(
            eventId = "event-1",
            sessionId = session.id,
            type = ProximityEventType.ENTERED_RANGE,
            reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
            atEpochMillis = 102_000L,
            atSessionMillis = 2_000L,
            targetId = session.targetId,
            frameId = 2L,
            observedAtEpochMillis = 101_990L,
            rawRssiDbm = -60,
            filteredRssiDbm = -60.0,
            algorithmVersion = "proximity-algorithms/1",
            triggerRuleVersion = "confirmed-far-then-near/1",
        )
        val runState = SessionRunState(
            sessionId = session.id,
            atEpochMillis = 102_000L,
            atSessionMillis = 2_000L,
            targetId = session.targetId,
            state = ProximityState.NEAR,
            reason = ProximityStateReason.CONFIRMED_NEAR,
            trend = MotionTrend.APPROACHING,
            lastReadingFrameId = 2L,
            lastReadingObservedAtEpochMillis = 101_990L,
            rawRssiDbm = -60,
            filteredRssiDbm = -60.0,
            algorithmVersion = "proximity-algorithms/1",
            triggerRuleVersion = "confirmed-far-then-near/1",
        )
        val state = LiveMonitorState(
            binding = testBinding(),
            scanState = ScanAvailability.Scanning,
            identityStatus = IdentityStatus.CONFIRMED,
            monitor = initial.copy(lastDecisionAtEpochMillis = 102_000L),
            monitorSamples = listOf(
                reading(90_000L, -85, -85.0, ProximityState.FAR, ProximityStateReason.CONFIRMED_FAR),
            ),
            test = SimulationTestView(
                session = session,
                config = BuiltInSessionConfigs.BASELINE,
                decision = initial.copy(
                    state = ProximityState.NEAR,
                    reason = ProximityStateReason.CONFIRMED_NEAR,
                    rawRssiDbm = -60,
                    filteredRssiDbm = -60.0,
                    lastObservationAtEpochMillis = 101_990L,
                    lastDecisionAtEpochMillis = 102_000L,
                ),
                samples = testSamples,
                events = listOf(event),
                states = listOf(runState),
                running = true,
            ),
        )

        val chart = MonitorChartSource.build(state, RssiChartWindow.ONE_MINUTE)

        assertEquals(MonitorChartMode.SIMULATION_TEST, chart.mode)
        assertEquals(2, chart.sourceSampleCount)
        assertEquals(listOf("event-1"), chart.data.markers.map { it.id })
        assertEquals(1f, chart.data.markers.single().xFraction, 1e-6f)
        assertEquals(1, chart.data.stateMarkers.size)
        assertEquals(ProximityState.NEAR, chart.data.stateMarkers.single().state)
        assertEquals(1f, chart.data.stateMarkers.single().xFraction, 1e-6f)
    }

    @Test
    fun finishedSimulationFallsBackToMonitorWithoutEventMarkers() {
        val session = simulationSession()
        val event = SessionRunEvent(
            eventId = "event-1",
            sessionId = session.id,
            type = ProximityEventType.ENTERED_RANGE,
            reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
            atEpochMillis = 102_000L,
            atSessionMillis = 2_000L,
            targetId = session.targetId,
            frameId = 2L,
            observedAtEpochMillis = 101_990L,
            rawRssiDbm = -60,
            filteredRssiDbm = -60.0,
            algorithmVersion = "proximity-algorithms/1",
            triggerRuleVersion = "confirmed-far-then-near/1",
        )
        val state = LiveMonitorState(
            binding = testBinding(),
            scanState = ScanAvailability.Scanning,
            identityStatus = IdentityStatus.CONFIRMED,
            monitor = initial.copy(
                state = ProximityState.FAR,
                reason = ProximityStateReason.CONFIRMED_FAR,
                lastDecisionAtEpochMillis = 90_000L,
            ),
            monitorSamples = listOf(
                reading(90_000L, -85, -85.0, ProximityState.FAR, ProximityStateReason.CONFIRMED_FAR),
            ),
            test = SimulationTestView(
                session = session,
                config = BuiltInSessionConfigs.BASELINE,
                decision = initial,
                samples = emptyList(),
                events = listOf(event),
                states = emptyList(),
                running = false,
            ),
        )

        val chart = MonitorChartSource.build(state, RssiChartWindow.ONE_MINUTE)

        assertEquals(MonitorChartMode.MONITOR, chart.mode)
        assertEquals(1, chart.sourceSampleCount)
        assertTrue(chart.data.markers.isEmpty())
    }

    private fun reading(
        atEpochMillis: Long,
        rawRssiDbm: Int,
        filteredRssiDbm: Double,
        state: ProximityState,
        reason: ProximityStateReason,
    ) = LiveReadingPoint(
        atEpochMillis = atEpochMillis,
        atSessionMillis = null,
        observedAtEpochMillis = atEpochMillis - 10L,
        frameId = atEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        state = state,
        reason = reason,
        trend = MotionTrend.STABLE,
    )

    private fun simulationSession(): SamplingSession = SamplingSession(
        id = "simulation-1",
        targetId = "target-1",
        targetDisplayName = "测试目标",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = SamplingType.SIMULATION,
        name = "模拟测试",
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.RECORDING,
        startedAtEpochMillis = 100_000L,
        endedAtEpochMillis = null,
        lastRecordedAtEpochMillis = 102_000L,
        interruptedDetectedAtEpochMillis = null,
    )
}
