package com.byd.proximitylab.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityObservationValidationTest {

    @Test
    fun initial_state_is_unknown_awaiting_observation() {
        val engine = ProximityEngine(baselineConfig(), TARGET_A)

        val decision = engine.advanceTo(0)

        assertEquals(ProximityState.UNKNOWN, decision.state)
        assertEquals(ProximityStateReason.AWAITING_OBSERVATION, decision.reason)
        assertEquals(MotionTrend.UNKNOWN, decision.trend)
        assertNull(decision.lastReading)
        assertTrue(decision.events.isEmpty())
        assertEquals(ProximityEngine.ALGORITHM_VERSION, decision.algorithmVersion)
        assertEquals(ProximityEngine.TRIGGER_RULE_VERSION, decision.triggerRuleVersion)
    }

    @Test
    fun rssi_out_of_android_range_is_rejected_with_raw_diagnostics() {
        val fixture = ProximityTestHarness(baselineConfig())

        val tooHigh = fixture.observe(127, atMillis = 0)
        assertEquals(ObservationRejectionReason.RSSI_OUT_OF_RANGE, tooHigh.rejection?.reason)
        assertEquals(127, tooHigh.rejection?.observation?.rssiDbm)

        val tooLow = fixture.observe(-128, atMillis = 1_000, observedAtMillis = 900)
        assertEquals(ObservationRejectionReason.RSSI_OUT_OF_RANGE, tooLow.rejection?.reason)
        assertEquals(-128, tooLow.rejection?.observation?.rssiDbm)
        assertEquals(900L, tooLow.rejection?.observation?.observedAtMillis)
        assertEquals(1_000L, tooLow.rejection?.receivedAtMillis)

        assertEquals(ProximityState.UNKNOWN, tooLow.state)
        fixture.assertEventCount(0)
    }

    @Test
    fun future_observation_timestamp_is_rejected() {
        val fixture = ProximityTestHarness(baselineConfig())

        val decision = fixture.observe(
            rssiDbm = -85,
            atMillis = 1_000,
            observedAtMillis = 1_001,
        )

        assertEquals(ObservationRejectionReason.FUTURE_TIMESTAMP, decision.rejection?.reason)
        assertEquals(ProximityState.UNKNOWN, decision.state)
        assertNull(decision.lastReading)
    }

    @Test
    fun out_of_order_sample_does_not_regress_latest_valid_observation_time() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 2_000)
        val outOfOrder = fixture.observe(
            rssiDbm = -85,
            atMillis = 2_500,
            observedAtMillis = 1_000,
        )

        assertEquals(
            ObservationRejectionReason.OUT_OF_ORDER_TIMESTAMP,
            outOfOrder.rejection?.reason,
        )
        assertEquals(2_000L, outOfOrder.lastReading?.observedAtMillis)

        val stillValid = fixture.advanceTo(6_500)
        assertEquals(ProximityState.UNKNOWN, stillValid.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, stillValid.reason)

        val expired = fixture.advanceTo(7_000)
        assertEquals(ProximityState.UNKNOWN, expired.state)
        assertEquals(ProximityStateReason.SAMPLES_EXPIRED, expired.reason)
    }

    @Test
    fun expiry_runs_before_a_sample_at_the_exact_expiry_boundary() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 0)

        val boundarySample = fixture.observe(
            rssiDbm = -85,
            atMillis = 5_000,
            observedAtMillis = 0,
        )
        assertEquals(ProximityState.UNKNOWN, boundarySample.state)
        assertEquals(ProximityStateReason.SAMPLES_EXPIRED, boundarySample.reason)
        assertEquals(
            ObservationRejectionReason.EXPIRED_SAMPLE,
            boundarySample.rejection?.reason,
        )

        val fresh = fixture.observe(
            rssiDbm = -85,
            atMillis = 5_000,
            observedAtMillis = 5_000,
        )
        assertEquals(ProximityState.UNKNOWN, fresh.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, fresh.reason)
        assertEquals(-85.0, fresh.lastReading?.filteredRssiDbm ?: Double.NaN, DOUBLE_DELTA)
    }

    @Test
    fun session_clock_cannot_move_backwards() {
        val engine = ProximityEngine(baselineConfig(), TARGET_A)
        engine.advanceTo(1_000)

        assertThrows(IllegalArgumentException::class.java) {
            engine.advanceTo(999)
        }
    }

    @Test
    fun blank_target_id_is_rejected_at_engine_construction() {
        assertThrows(IllegalArgumentException::class.java) {
            ProximityEngine(baselineConfig(), "  ")
        }
    }
}
