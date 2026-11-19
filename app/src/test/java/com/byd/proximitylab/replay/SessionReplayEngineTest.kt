package com.byd.proximitylab.replay

import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 09 纯 Kotlin 回放行为：同一输入按记录的处理顺序投递到 03 判定入口，保留空窗过期、
 * 重复/迟到帧、中断覆盖终点和独立配置。
 */
class SessionReplayEngineTest {

    private val startEpochMillis = 1_800_000_000_000L

    @Test
    fun p4GapExpiresWithoutNewFrameBeforeRecoveryConfirmsNearWithoutEvent() {
        val config = snapshot(
            enterThresholdDbm = -65,
            exitThresholdDbm = -80,
        )
        val session = session(
            endedAtEpochMillis = startEpochMillis + 10_000L,
            lastRecordedAtEpochMillis = startEpochMillis + 10_000L,
        )
        val timeline = listOf(
            observation(sequence = 1, processedAt = 0L, observedAt = 0L, rssi = -85, frameId = 11L),
            observation(sequence = 2, processedAt = 1_000L, observedAt = 1_000L, rssi = -85, frameId = 12L),
            observation(sequence = 3, processedAt = 8_000L, observedAt = 8_000L, rssi = -60, frameId = 13L),
            observation(sequence = 4, processedAt = 9_000L, observedAt = 9_000L, rssi = -60, frameId = 14L),
        )

        val result = SessionReplayEngine.replay(session, timeline, config)

        assertEquals(
            listOf(
                StateExpectation(0L, ProximityState.UNKNOWN, ProximityStateReason.AWAITING_OBSERVATION),
                StateExpectation(0L, ProximityState.UNKNOWN, ProximityStateReason.EVIDENCE_PENDING),
                StateExpectation(1_000L, ProximityState.FAR, ProximityStateReason.CONFIRMED_FAR),
                // 最后一条弱帧观测于 1 s，有效期 5 s，因此无新帧时在 6 s 过期。
                StateExpectation(6_000L, ProximityState.UNKNOWN, ProximityStateReason.SAMPLES_EXPIRED),
                StateExpectation(8_000L, ProximityState.UNKNOWN, ProximityStateReason.EVIDENCE_PENDING),
                StateExpectation(9_000L, ProximityState.NEAR, ProximityStateReason.CONFIRMED_NEAR),
            ),
            result.states.map { state ->
                StateExpectation(state.atSessionMillis, state.state, state.reason)
            },
        )
        assertTrue(result.events.isEmpty())
        assertEquals(ProximityState.NEAR, result.finalState.state)
        assertEquals(10_000L, result.finalState.atSessionMillis)
        assertEquals(listOf(11L, 12L, 13L, 14L), result.samples.map { it.frameId })
        assertEquals(4, result.coverage.observationCount)
        assertFalse(result.coverage.hasEventGaps)
        assertFalse(result.coverage.interrupted)
    }

    @Test
    fun p6DuplicateAndLateStrongFramesKeepFarAndEmitNoEvent() {
        val config = snapshot(
            enterThresholdDbm = -65,
            exitThresholdDbm = -80,
        )
        val session = session(
            endedAtEpochMillis = startEpochMillis + 5_200L,
            lastRecordedAtEpochMillis = startEpochMillis + 5_200L,
        )
        val timeline = listOf(
            observation(sequence = 1, processedAt = 1_000L, observedAt = 1_000L, rssi = -85, frameId = 21L),
            // 同一帧重复投递：不累计证据。
            observation(sequence = 2, processedAt = 1_000L, observedAt = 1_000L, rssi = -85, frameId = 21L),
            observation(sequence = 3, processedAt = 2_000L, observedAt = 2_000L, rssi = -85, frameId = 22L),
            // 两条强帧在 5.2 s 批量到达，但真正的观测时刻是 0 s / 0.1 s，已过期。
            observation(sequence = 4, processedAt = 5_200L, observedAt = 0L, rssi = -60, frameId = 23L),
            observation(sequence = 5, processedAt = 5_200L, observedAt = 100L, rssi = -60, frameId = 24L),
        )

        val result = SessionReplayEngine.replay(session, timeline, config)

        assertEquals(
            listOf(
                StateExpectation(0L, ProximityState.UNKNOWN, ProximityStateReason.AWAITING_OBSERVATION),
                StateExpectation(1_000L, ProximityState.UNKNOWN, ProximityStateReason.EVIDENCE_PENDING),
                StateExpectation(2_000L, ProximityState.FAR, ProximityStateReason.CONFIRMED_FAR),
            ),
            result.states.map { state ->
                StateExpectation(state.atSessionMillis, state.state, state.reason)
            },
        )
        assertTrue(result.events.isEmpty())
        assertEquals(ProximityState.FAR, result.finalState.state)
        assertEquals(5_200L, result.finalState.atSessionMillis)
        // 重复帧和过期帧都不能进入滤波/有效样本。
        assertEquals(listOf(21L, 22L), result.samples.map { it.frameId })
    }

    @Test
    fun interruptedSessionStopsAtRecordedCoverageAndNeverInfersLaterExpiry() {
        val config = snapshot(
            enterThresholdDbm = -65,
            exitThresholdDbm = -80,
        )
        val session = session(
            status = SessionLifecycleStatus.INTERRUPTED,
            endedAtEpochMillis = null,
            lastRecordedAtEpochMillis = startEpochMillis + 4_000L,
        )
        val timeline = listOf(
            observation(sequence = 1, processedAt = 1_000L, observedAt = 1_000L, rssi = -85, frameId = 31L),
            observation(sequence = 2, processedAt = 2_000L, observedAt = 2_000L, rssi = -85, frameId = 32L),
        )

        val result = SessionReplayEngine.replay(session, timeline, config)

        assertEquals(startEpochMillis + 4_000L, result.coverage.coverageEndEpochMillis)
        assertTrue(result.coverage.interrupted)
        assertEquals(ProximityState.FAR, result.finalState.state)
        assertEquals(4_000L, result.finalState.atSessionMillis)
        // 1 s + 5 s 有效期的 6 s 状态不在已记录覆盖内，回放不能推断。
        assertTrue(result.states.none { state -> state.atSessionMillis >= 6_000L })
        assertTrue(result.states.none { state -> state.reason == ProximityStateReason.SAMPLES_EXPIRED })
    }

    @Test
    fun eventGapsAreVisibleAsIdentifiableInputDeficiency() {
        val session = session(
            status = SessionLifecycleStatus.INTERRUPTED,
            endedAtEpochMillis = null,
            lastRecordedAtEpochMillis = startEpochMillis + 4_000L,
        )
        val timeline = listOf(
            observation(sequence = 1, processedAt = 1_000L, observedAt = 1_000L, rssi = -85, frameId = 41L),
            observation(sequence = 2, processedAt = 2_000L, observedAt = 2_000L, rssi = -85, frameId = 42L),
            SessionTimelineItem(
                sequence = 3,
                sessionId = session.id,
                event = SessionTimelineEvent.EventGap(
                    droppedEventCount = 7,
                    processedAtEpochMillis = startEpochMillis + 3_000L,
                ),
            ),
        )

        val result = SessionReplayEngine.replay(session, timeline, snapshot())

        assertTrue(result.coverage.hasEventGaps)
        assertEquals(7, result.coverage.droppedEventCount)
        assertEquals(4_000L, result.finalState.atSessionMillis)
        assertTrue(result.states.none { state -> state.atSessionMillis == 3_000L })
    }

    @Test
    fun differentConfigurationsReplayIndependentContextsWithDeterministicResults() {
        val session = session(
            endedAtEpochMillis = startEpochMillis + 4_000L,
            lastRecordedAtEpochMillis = startEpochMillis + 4_000L,
        )
        val timeline = listOf(
            observation(sequence = 1, processedAt = 1_000L, observedAt = 1_000L, rssi = -85, frameId = 51L),
            observation(sequence = 2, processedAt = 2_000L, observedAt = 2_000L, rssi = -85, frameId = 52L),
            observation(sequence = 3, processedAt = 3_000L, observedAt = 3_000L, rssi = -60, frameId = 53L),
            observation(sequence = 4, processedAt = 4_000L, observedAt = 4_000L, rssi = -60, frameId = 54L),
        )
        val triggering = snapshot(enterThresholdDbm = -65, exitThresholdDbm = -80)
        val notTriggering = snapshot(enterThresholdDbm = -50, exitThresholdDbm = -80)

        val first = SessionReplayEngine.replay(session, timeline, triggering)
        val second = SessionReplayEngine.replay(session, timeline, triggering)
        val other = SessionReplayEngine.replay(session, timeline, notTriggering)

        assertEquals(first, second)
        assertEquals(1, first.events.size)
        assertEquals(ProximityEventType.ENTERED_RANGE, first.events.single().type)
        assertEquals(
            ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
            first.events.single().reason,
        )
        assertEquals(4_000L, first.events.single().atSessionMillis)
        assertEquals(ProximityState.NEAR, first.finalState.state)

        assertTrue(other.events.isEmpty())
        assertEquals(ProximityState.FAR, other.finalState.state)
        assertEquals(
            ProximityState.UNKNOWN,
            other.states.first().state,
        )
    }

    @Test
    fun replayStartsFromUnknownEvenWhenOriginalRunEndedNear() {
        val session = session(
            endedAtEpochMillis = startEpochMillis + 4_000L,
            lastRecordedAtEpochMillis = startEpochMillis + 4_000L,
        )
        val timeline = listOf(
            observation(sequence = 1, processedAt = 1_000L, observedAt = 1_000L, rssi = -85, frameId = 61L),
            observation(sequence = 2, processedAt = 2_000L, observedAt = 2_000L, rssi = -85, frameId = 62L),
            observation(sequence = 3, processedAt = 3_000L, observedAt = 3_000L, rssi = -60, frameId = 63L),
            observation(sequence = 4, processedAt = 4_000L, observedAt = 4_000L, rssi = -60, frameId = 64L),
        )

        val result = SessionReplayEngine.replay(session, timeline, snapshot())

        assertEquals(0L, result.states.first().atSessionMillis)
        assertEquals(ProximityState.UNKNOWN, result.states.first().state)
        assertEquals(ProximityStateReason.AWAITING_OBSERVATION, result.states.first().reason)
        assertNull(result.states.first().lastReadingFrameId)
    }

    private fun snapshot(
        enterThresholdDbm: Int = -65,
        exitThresholdDbm: Int = -80,
    ): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "replay-test-$enterThresholdDbm",
        name = "回放测试配置 $enterThresholdDbm",
        sourceConfigId = null,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.BASELINE,
            enterThresholdDbm = enterThresholdDbm,
            exitThresholdDbm = exitThresholdDbm,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(minimumSamples = 2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        ),
    )

    private fun session(
        status: SessionLifecycleStatus = SessionLifecycleStatus.COMPLETED,
        endedAtEpochMillis: Long? = startEpochMillis + 4_000L,
        lastRecordedAtEpochMillis: Long = startEpochMillis + 4_000L,
    ): SamplingSession = SamplingSession(
        id = "replay-session",
        targetId = "target-1",
        targetDisplayName = "目标车",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = SamplingType.SIMULATION,
        name = "回放测试",
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = status,
        startedAtEpochMillis = startEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        lastRecordedAtEpochMillis = lastRecordedAtEpochMillis,
        interruptedDetectedAtEpochMillis = null,
    )

    private fun observation(
        sequence: Long,
        processedAt: Long,
        observedAt: Long,
        rssi: Int,
        frameId: Long,
    ): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = "replay-session",
        event = SessionTimelineEvent.Observation(
            observationId = "observation-$frameId",
            targetId = "target-1",
            rawRssiDbm = rssi,
            observedAtEpochMillis = startEpochMillis + observedAt,
            receivedAtEpochMillis = startEpochMillis + observedAt,
            scanFrameId = frameId,
            deliverySequence = sequence,
            address = "AA:BB:CC:DD:EE:01",
            payloadFingerprint = "fingerprint-$frameId",
            processedAtEpochMillis = startEpochMillis + processedAt,
        ),
    )

    private data class StateExpectation(
        val atSessionMillis: Long,
        val state: ProximityState,
        val reason: ProximityStateReason,
    )
}
