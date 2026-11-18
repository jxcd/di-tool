package com.byd.proximitylab.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 票据 03 的 P1–P9 判定用例。夹具有效期 5 秒，阈值与确认帧数仅用于测试。
 */
class ProximityEngineTest {

    @Test
    fun p1_normal_approach_confirms_far_then_near_and_emits_entered_range_once() {
        val fixture = ProximityTestHarness(baselineConfig())

        val first = fixture.observe(-85, atMillis = 0)
        assertEquals(ProximityState.UNKNOWN, first.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, first.reason)
        assertEquals(-85.0, first.lastReading.requireFiltered(), DOUBLE_DELTA)
        assertEquals(0, first.events.size)

        val second = fixture.observe(-85, atMillis = 1_000)
        assertEquals(ProximityState.FAR, second.state)
        assertEquals(ProximityStateReason.CONFIRMED_FAR, second.reason)
        assertEquals(-85.0, second.lastReading.requireFiltered(), DOUBLE_DELTA)
        fixture.assertEventCount(0)

        val third = fixture.observe(-60, atMillis = 2_000)
        assertEquals(ProximityState.FAR, third.state)
        assertEquals(ProximityStateReason.FAR_MAINTAINED, third.reason)
        assertEquals(-60.0, third.lastReading.requireFiltered(), DOUBLE_DELTA)
        fixture.assertEventCount(0)

        val fourth = fixture.observe(-60, atMillis = 3_000)
        assertEquals(ProximityState.NEAR, fourth.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, fourth.reason)
        assertEquals(1, fourth.events.size)
        fixture.assertEventCount(1)

        val event = fixture.events.single()
        assertEquals(ProximityEventType.ENTERED_RANGE, event.type)
        assertEquals(ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR, event.reason)
        assertEquals(3_000L, event.atMillis)
        assertEquals(TARGET_A, event.targetId)
        assertEquals(-60, event.rawRssiDbm)
        assertEquals(-60.0, event.filteredRssiDbm, DOUBLE_DELTA)
        assertEquals(ProximityEngine.ALGORITHM_VERSION, event.algorithmVersion)
        assertEquals(ProximityEngine.TRIGGER_RULE_VERSION, event.triggerRuleVersion)
    }

    @Test
    fun p2_cold_start_near_confirms_near_without_event() {
        val fixture = ProximityTestHarness(baselineConfig())

        val first = fixture.observe(-60, atMillis = 0)
        assertEquals(ProximityState.UNKNOWN, first.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, first.reason)

        val second = fixture.observe(-60, atMillis = 1_000)
        assertEquals(ProximityState.NEAR, second.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, second.reason)
        fixture.assertEventCount(0)
    }

    @Test
    fun p3_near_stay_and_hysteresis_do_not_repeat_but_reconfirmed_far_allows_return() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 0)
        fixture.observe(-85, atMillis = 1_000)
        fixture.observe(-60, atMillis = 2_000)
        fixture.observe(-60, atMillis = 3_000)
        fixture.assertEventCount(1)

        val stayingNear = fixture.observe(-60, atMillis = 4_000)
        assertEquals(ProximityState.NEAR, stayingNear.state)
        assertEquals(ProximityStateReason.NEAR_MAINTAINED, stayingNear.reason)

        val hysteresis = fixture.observe(-70, atMillis = 5_000)
        assertEquals(ProximityState.NEAR, hysteresis.state)
        assertEquals(ProximityStateReason.NEAR_MAINTAINED, hysteresis.reason)

        val firstFarSideSample = fixture.observe(-85, atMillis = 6_000)
        assertEquals(ProximityState.NEAR, firstFarSideSample.state)
        assertEquals(ProximityStateReason.NEAR_MAINTAINED, firstFarSideSample.reason)
        fixture.assertEventCount(1)

        val far = fixture.observe(-85, atMillis = 7_000)
        assertEquals(ProximityState.FAR, far.state)
        assertEquals(ProximityStateReason.CONFIRMED_FAR, far.reason)
        fixture.assertEventCount(1)

        val pendingNear = fixture.observe(-60, atMillis = 8_000)
        assertEquals(ProximityState.FAR, pendingNear.state)
        assertEquals(ProximityStateReason.FAR_MAINTAINED, pendingNear.reason)

        val returned = fixture.observe(-60, atMillis = 9_000)
        assertEquals(ProximityState.NEAR, returned.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, returned.reason)
        fixture.assertEventCount(2)
        assertEquals(9_000L, fixture.events.last().atMillis)
    }

    @Test
    fun p4_expiry_without_new_samples_returns_to_unknown_and_recovery_does_not_trigger() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 0)
        val far = fixture.observe(-85, atMillis = 1_000)
        assertEquals(ProximityState.FAR, far.state)

        val expired = fixture.advanceTo(6_000)
        assertEquals(ProximityState.UNKNOWN, expired.state)
        assertEquals(ProximityStateReason.SAMPLES_EXPIRED, expired.reason)
        assertNull(expired.lastReading)
        assertEquals(MotionTrend.UNKNOWN, expired.trend)
        fixture.assertEventCount(0)

        val firstNew = fixture.observe(-60, atMillis = 7_000)
        assertEquals(ProximityState.UNKNOWN, firstNew.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, firstNew.reason)

        val recovered = fixture.observe(-60, atMillis = 8_000)
        assertEquals(ProximityState.NEAR, recovered.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, recovered.reason)
        fixture.assertEventCount(0)
    }

    @Test
    fun p5a_scan_stop_returns_to_unknown_and_recovery_does_not_reuse_old_far() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 0)
        fixture.observe(-85, atMillis = 1_000)
        fixture.assertEventCount(0)

        val stopped = fixture.scan(available = false, atMillis = 2_000)
        assertEquals(ProximityState.UNKNOWN, stopped.state)
        assertEquals(ProximityStateReason.SCAN_UNAVAILABLE, stopped.reason)

        val rejectedWhileStopped = fixture.observe(-60, atMillis = 3_000)
        assertEquals(ProximityState.UNKNOWN, rejectedWhileStopped.state)
        assertEquals(ProximityStateReason.SCAN_UNAVAILABLE, rejectedWhileStopped.reason)
        assertEquals(
            ObservationRejectionReason.SCAN_UNAVAILABLE,
            rejectedWhileStopped.rejection?.reason,
        )

        val resumed = fixture.scan(available = true, atMillis = 4_000)
        assertEquals(ProximityState.UNKNOWN, resumed.state)
        assertEquals(ProximityStateReason.AWAITING_OBSERVATION, resumed.reason)

        fixture.observe(-60, atMillis = 5_000)
        val recovered = fixture.observe(-60, atMillis = 6_000)
        assertEquals(ProximityState.NEAR, recovered.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, recovered.reason)
        fixture.assertEventCount(0)
    }

    @Test
    fun p5b_identity_loss_returns_to_unknown_and_recovery_does_not_reuse_old_far() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 0)
        fixture.observe(-85, atMillis = 1_000)

        val lost = fixture.identity(TargetIdentityStatus.UNCONFIRMED, atMillis = 2_000)
        assertEquals(ProximityState.UNKNOWN, lost.state)
        assertEquals(ProximityStateReason.IDENTITY_UNCONFIRMED, lost.reason)

        val rejectedWhileUnconfirmed = fixture.observe(-60, atMillis = 3_000)
        assertEquals(
            ObservationRejectionReason.IDENTITY_UNCONFIRMED,
            rejectedWhileUnconfirmed.rejection?.reason,
        )

        val confirmed = fixture.identity(TargetIdentityStatus.CONFIRMED, atMillis = 4_000)
        assertEquals(ProximityState.UNKNOWN, confirmed.state)
        assertEquals(ProximityStateReason.AWAITING_OBSERVATION, confirmed.reason)

        fixture.observe(-60, atMillis = 5_000)
        val recovered = fixture.observe(-60, atMillis = 6_000)
        assertEquals(ProximityState.NEAR, recovered.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, recovered.reason)
        fixture.assertEventCount(0)
    }

    @Test
    fun p5c_manual_rebind_discards_old_far_for_the_new_target() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 0)
        val far = fixture.observe(-85, atMillis = 1_000)
        assertEquals(ProximityState.FAR, far.state)

        val rebound = fixture.rebind(TARGET_B, atMillis = 2_000)
        assertEquals(ProximityState.UNKNOWN, rebound.state)
        assertEquals(ProximityStateReason.TARGET_REBOUND, rebound.reason)
        assertEquals(TARGET_B, rebound.targetId)

        val oldTargetSample = fixture.observe(-60, atMillis = 3_000, targetId = TARGET_A)
        assertEquals(ProximityState.UNKNOWN, oldTargetSample.state)
        assertEquals(
            ObservationRejectionReason.TARGET_MISMATCH,
            oldTargetSample.rejection?.reason,
        )

        val firstNew = fixture.observe(-60, atMillis = 4_000, targetId = TARGET_B)
        assertEquals(ProximityState.UNKNOWN, firstNew.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, firstNew.reason)

        val secondNew = fixture.observe(-60, atMillis = 5_000, targetId = TARGET_B)
        assertEquals(ProximityState.NEAR, secondNew.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, secondNew.reason)
        fixture.assertEventCount(0)
    }

    @Test
    fun p5d_manual_rebind_to_the_same_target_also_revokes_trigger_eligibility() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 0)
        fixture.observe(-85, atMillis = 1_000)

        val rebound = fixture.rebind(TARGET_A, atMillis = 2_000)
        assertEquals(ProximityState.UNKNOWN, rebound.state)
        assertEquals(ProximityStateReason.TARGET_REBOUND, rebound.reason)

        fixture.observe(-60, atMillis = 3_000)
        val recovered = fixture.observe(-60, atMillis = 4_000)
        assertEquals(ProximityState.NEAR, recovered.state)
        fixture.assertEventCount(0)
    }

    @Test
    fun p5e_rebind_rejects_a_frame_observed_at_the_rebind_moment() {
        val fixture = ProximityTestHarness(baselineConfig())

        fixture.observe(-85, atMillis = 2_000)
        fixture.rebind(TARGET_A, atMillis = 2_000)

        val redelivered = fixture.redeliverLast(atMillis = 2_000)
        assertEquals(ProximityState.UNKNOWN, redelivered.state)
        assertEquals(
            ObservationRejectionReason.OBSERVED_BEFORE_REBIND,
            redelivered.rejection?.reason,
        )
        fixture.assertEventCount(0)
    }

    @Test
    fun p6_duplicates_do_not_confirm_and_already_expired_samples_are_rejected() {
        val fixture = ProximityTestHarness(baselineConfig())

        val firstWeak = fixture.observe(-85, atMillis = 1_000)
        assertEquals(ProximityState.UNKNOWN, firstWeak.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, firstWeak.reason)

        val duplicate = fixture.redeliverLast(atMillis = 1_000)
        assertEquals(ProximityState.UNKNOWN, duplicate.state)
        assertEquals(
            ObservationRejectionReason.DUPLICATE_FRAME,
            duplicate.rejection?.reason,
        )
        fixture.assertEventCount(0)

        val secondWeak = fixture.observe(-85, atMillis = 2_000)
        assertEquals(ProximityState.FAR, secondWeak.state)
        assertEquals(ProximityStateReason.CONFIRMED_FAR, secondWeak.reason)

        val expiredAtZero = fixture.observe(
            rssiDbm = -60,
            atMillis = 5_200,
            observedAtMillis = 0,
        )
        assertEquals(ProximityState.FAR, expiredAtZero.state)
        assertEquals(
            ObservationRejectionReason.EXPIRED_SAMPLE,
            expiredAtZero.rejection?.reason,
        )

        val expiredAtOneHundredMillis = fixture.observe(
            rssiDbm = -60,
            atMillis = 5_200,
            observedAtMillis = 100,
        )
        assertEquals(ProximityState.FAR, expiredAtOneHundredMillis.state)
        assertEquals(
            ObservationRejectionReason.EXPIRED_SAMPLE,
            expiredAtOneHundredMillis.rejection?.reason,
        )
        fixture.assertEventCount(0)
    }

    @Test
    fun p7_threshold_equality_confirms_with_the_same_evidence_rule() {
        val fixture = ProximityTestHarness(baselineConfig())

        val first = fixture.observe(-80, atMillis = 0)
        assertEquals(ProximityState.UNKNOWN, first.state)

        val far = fixture.observe(-80, atMillis = 1_000)
        assertEquals(ProximityState.FAR, far.state)
        assertEquals(ProximityStateReason.CONFIRMED_FAR, far.reason)

        val nearCandidate = fixture.observe(-65, atMillis = 2_000)
        assertEquals(ProximityState.FAR, nearCandidate.state)
        assertEquals(ProximityStateReason.FAR_MAINTAINED, nearCandidate.reason)

        val near = fixture.observe(-65, atMillis = 3_000)
        assertEquals(ProximityState.NEAR, near.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, near.reason)
        fixture.assertEventCount(1)
    }

    @Test
    fun p8_ema_outputs_known_values_and_rejects_out_of_range_without_updating_filter() {
        val fixture = ProximityTestHarness(emaConfig(emaAlpha = 0.5))

        val seeded = fixture.observe(-80, atMillis = 0)
        assertEquals(-80.0, seeded.lastReading.requireFiltered(), DOUBLE_DELTA)

        val smoothed = fixture.observe(-60, atMillis = 1_000)
        assertEquals(-70.0, smoothed.lastReading.requireFiltered(), DOUBLE_DELTA)

        val rejected = fixture.observe(127, atMillis = 2_000)
        assertEquals(
            ObservationRejectionReason.RSSI_OUT_OF_RANGE,
            rejected.rejection?.reason,
        )
        assertEquals(smoothed.lastReading, rejected.lastReading)
        assertEquals(127, rejected.rejection?.observation?.rssiDbm)

        val next = fixture.observe(-60, atMillis = 3_000)
        assertEquals(-65.0, next.lastReading.requireFiltered(), DOUBLE_DELTA)
        assertEquals(ProximityState.UNKNOWN, next.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, next.reason)
        fixture.assertEventCount(0)
    }

    @Test
    fun p8b_out_of_range_sample_does_not_extend_sample_validity() {
        val fixture = ProximityTestHarness(emaConfig())

        fixture.observe(-85, atMillis = 0)
        fixture.observe(127, atMillis = 1_000)

        val expired = fixture.advanceTo(5_000)
        assertEquals(ProximityState.UNKNOWN, expired.state)
        assertEquals(ProximityStateReason.SAMPLES_EXPIRED, expired.reason)
    }

    @Test
    fun p9_isolated_spikes_do_not_trigger_and_duration_confirmation_triggers_once() {
        val fixture = ProximityTestHarness(
            baselineConfig(confirmation = ConfirmationRequirement.Duration(2_000)),
        )

        fixture.observe(-85, atMillis = 0)
        fixture.observe(-85, atMillis = 1_000)
        val far = fixture.observe(-85, atMillis = 2_000)
        assertEquals(ProximityState.FAR, far.state)
        assertEquals(ProximityStateReason.CONFIRMED_FAR, far.reason)

        fixture.observe(-60, atMillis = 3_000)
        fixture.observe(-85, atMillis = 4_000)
        fixture.observe(-60, atMillis = 5_000)
        fixture.observe(-85, atMillis = 6_000)
        assertEquals(ProximityState.FAR, fixture.advanceTo(6_000).state)
        fixture.assertEventCount(0)

        fixture.observe(-60, atMillis = 7_000)
        val oneSecond = fixture.observe(-60, atMillis = 8_000)
        assertEquals(ProximityState.FAR, oneSecond.state)
        fixture.assertEventCount(0)

        val twoSeconds = fixture.observe(-60, atMillis = 9_000)
        assertEquals(ProximityState.NEAR, twoSeconds.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, twoSeconds.reason)
        fixture.assertEventCount(1)
        assertEquals(9_000L, fixture.events.single().atMillis)
    }

    @Test
    fun p9b_expiry_interrupts_unfinished_duration_confirmation() {
        val fixture = ProximityTestHarness(
            baselineConfig(confirmation = ConfirmationRequirement.Duration(2_000)),
        )

        fixture.observe(-85, atMillis = 0)
        fixture.observe(-85, atMillis = 1_000)
        fixture.observe(-85, atMillis = 2_000)
        fixture.observe(-60, atMillis = 3_000)
        fixture.observe(-60, atMillis = 4_000)
        fixture.assertEventCount(0)

        val expired = fixture.advanceTo(9_000)
        assertEquals(ProximityState.UNKNOWN, expired.state)
        assertEquals(ProximityStateReason.SAMPLES_EXPIRED, expired.reason)

        val restarted = fixture.observe(-60, atMillis = 9_500)
        assertEquals(ProximityState.UNKNOWN, restarted.state)
        assertEquals(ProximityStateReason.EVIDENCE_PENDING, restarted.reason)

        val underTwoSeconds = fixture.observe(-60, atMillis = 10_400)
        assertEquals(ProximityState.UNKNOWN, underTwoSeconds.state)
        fixture.assertEventCount(0)

        val confirmedAgain = fixture.observe(-60, atMillis = 11_500)
        assertEquals(ProximityState.NEAR, confirmedAgain.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, confirmedAgain.reason)
        fixture.assertEventCount(0)
    }

    @Test
    fun p9c_duration_confirmation_supports_irregular_sampling_intervals() {
        val fixture = ProximityTestHarness(
            baselineConfig(confirmation = ConfirmationRequirement.Duration(2_000)),
        )

        fixture.observe(-85, atMillis = 0)
        val far = fixture.observe(-85, atMillis = 2_100)
        assertEquals(ProximityState.FAR, far.state)
        assertEquals(ProximityStateReason.CONFIRMED_FAR, far.reason)

        fixture.observe(-60, atMillis = 5_000)
        fixture.observe(-60, atMillis = 5_900)
        val near = fixture.observe(-60, atMillis = 7_000)
        assertEquals(ProximityState.NEAR, near.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, near.reason)
        fixture.assertEventCount(1)
    }

    @Test
    fun p9d_duration_confirmation_is_not_advanced_by_a_duplicate_frame() {
        val fixture = ProximityTestHarness(
            baselineConfig(confirmation = ConfirmationRequirement.Duration(2_000)),
        )

        fixture.observe(-85, atMillis = 0)
        fixture.observe(-85, atMillis = 1_000)
        val far = fixture.observe(-85, atMillis = 2_000)
        assertEquals(ProximityState.FAR, far.state)

        fixture.observe(-60, atMillis = 3_000)
        val duplicate = fixture.redeliverLast(atMillis = 4_000)
        assertEquals(
            ObservationRejectionReason.DUPLICATE_FRAME,
            duplicate.rejection?.reason,
        )
        assertEquals(ProximityState.FAR, duplicate.state)
        fixture.assertEventCount(0)

        val underTwoSeconds = fixture.observe(-60, atMillis = 4_900)
        assertEquals(ProximityState.FAR, underTwoSeconds.state)
        fixture.assertEventCount(0)

        val confirmed = fixture.observe(-60, atMillis = 5_000)
        assertEquals(ProximityState.NEAR, confirmed.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, confirmed.reason)
        fixture.assertEventCount(1)
    }

    @Test
    fun trend_follows_filtered_rssi_direction_and_resets_on_expiry() {
        val fixture = ProximityTestHarness(baselineConfig())

        assertEquals(MotionTrend.UNKNOWN, fixture.observe(-85, atMillis = 0).trend)
        assertEquals(MotionTrend.STABLE, fixture.observe(-85, atMillis = 1_000).trend)
        assertEquals(MotionTrend.APPROACHING, fixture.observe(-60, atMillis = 2_000).trend)
        assertEquals(MotionTrend.STABLE, fixture.observe(-60, atMillis = 3_000).trend)
        assertEquals(MotionTrend.LEAVING, fixture.observe(-85, atMillis = 4_000).trend)
        assertEquals(MotionTrend.UNKNOWN, fixture.advanceTo(9_000).trend)
    }

    @Test
    fun identical_timeline_and_config_produce_identical_decisions() {
        fun runSequence(): List<ProximityDecision> {
            val fixture = ProximityTestHarness(baselineConfig())
            return listOf(
                fixture.observe(-85, atMillis = 0),
                fixture.observe(-85, atMillis = 1_000),
                fixture.observe(-60, atMillis = 2_000),
                fixture.observe(-60, atMillis = 3_000),
                fixture.observe(-70, atMillis = 4_000),
                fixture.observe(-85, atMillis = 5_000),
                fixture.advanceTo(6_000),
            )
        }

        val firstRun = runSequence()
        val secondRun = runSequence()

        assertEquals(firstRun, secondRun)
        assertEquals(1, firstRun.sumOf { it.events.size })
        assertEquals(
            ProximityEngine.ALGORITHM_VERSION,
            firstRun.first().algorithmVersion,
        )
        assertEquals(
            ProximityEngine.TRIGGER_RULE_VERSION,
            firstRun.first().triggerRuleVersion,
        )
    }
}

private fun ProximityReading?.requireFiltered(): Double =
    requireNotNull(this) { "缺少有效滤波结果" }.filteredRssiDbm
