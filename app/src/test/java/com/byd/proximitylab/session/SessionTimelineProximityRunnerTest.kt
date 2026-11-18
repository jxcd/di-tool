package com.byd.proximitylab.session

import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 05 实时入口与 09 回放共用的会话时间线判定适配测试。
 */
class SessionTimelineProximityRunnerTest {

    private val startEpochMillis = 1_800_000_000_000L

    private val config = ProximityConfig(
        algorithm = ProximityAlgorithm.BASELINE,
        enterThresholdDbm = -65,
        exitThresholdDbm = -80,
        confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
        sampleValidityMillis = 5_000L,
        emaAlpha = 0.5,
    )

    @Test
    fun sameTimelineAdvancesSessionTimeAndExpiresWithoutNewFrames() {
        val runner = SessionTimelineProximityRunner(config, "target-1", startEpochMillis)

        runner.apply(observation(processedAt = 1_000L, observedAt = 990L, rssi = -85))
        val far = runner.apply(observation(processedAt = 2_000L, observedAt = 1_990L, rssi = -85))
        assertEquals(ProximityState.FAR, far.state)

        val expired = runner.advanceTo(startEpochMillis + 7_000L)
        assertEquals(ProximityState.UNKNOWN, expired.state)
        assertEquals(ProximityStateReason.SAMPLES_EXPIRED, expired.reason)
        assertTrue(expired.events.isEmpty())

        runner.apply(observation(processedAt = 8_000L, observedAt = 7_990L, rssi = -60))
        val near = runner.apply(observation(processedAt = 9_000L, observedAt = 8_990L, rssi = -60))
        assertEquals(ProximityState.NEAR, near.state)
        assertTrue(near.events.isEmpty())
    }

    @Test
    fun scanIdentityChangesAndEventGapUseTheSameSessionTimeBase() {
        val runner = SessionTimelineProximityRunner(config, "target-1", startEpochMillis)
        runner.apply(observation(processedAt = 1_000L, observedAt = 990L, rssi = -85))
        val far = runner.apply(observation(processedAt = 2_000L, observedAt = 1_990L, rssi = -85))
        assertEquals(ProximityState.FAR, far.state)

        val scanOff = runner.apply(
            SessionTimelineEvent.ScanStateChanged(
                available = false,
                stateLabel = "BLUETOOTH_OFF",
                detail = "蓝牙已关闭。",
                processedAtEpochMillis = startEpochMillis + 3_000L,
            ),
        )
        assertEquals(ProximityState.UNKNOWN, scanOff.state)
        assertEquals(ProximityStateReason.SCAN_UNAVAILABLE, scanOff.reason)
        assertEquals(3_000L, scanOff.atMillis)

        val identityLost = runner.apply(
            SessionTimelineEvent.IdentityChanged(
                confirmed = false,
                statusLabel = "UNCERTAIN",
                targetId = "target-1",
                basisLabel = null,
                detail = "身份不确定。",
                processedAtEpochMillis = startEpochMillis + 4_000L,
            ),
        )
        assertEquals(ProximityState.UNKNOWN, identityLost.state)
        assertEquals(ProximityStateReason.IDENTITY_UNCONFIRMED, identityLost.reason)
        assertEquals(4_000L, identityLost.atMillis)

        val gap = runner.apply(
            SessionTimelineEvent.EventGap(
                droppedEventCount = 2,
                processedAtEpochMillis = startEpochMillis + 6_000L,
            ),
        )
        assertEquals(6_000L, gap.atMillis)
        assertEquals(ProximityState.UNKNOWN, gap.state)
        assertEquals(ProximityStateReason.IDENTITY_UNCONFIRMED, gap.reason)
    }

    @Test
    fun lateRecordedEventAfterTimeAdvanceUsesCurrentSessionTimeWithoutRollback() {
        val runner = SessionTimelineProximityRunner(config, "target-1", startEpochMillis)
        runner.apply(observation(processedAt = 1_000L, observedAt = 990L, rssi = -85))
        val far = runner.apply(observation(processedAt = 2_000L, observedAt = 1_990L, rssi = -85))
        assertEquals(ProximityState.FAR, far.state)

        // 计时器先推进了会话时间；随后才被消费的事件仍按当前会话时间处理，不要求时钟回拨。
        runner.advanceTo(startEpochMillis + 5_000L)
        val late = runner.apply(
            observation(processedAt = 4_000L, observedAt = 3_990L, rssi = -60),
        )

        assertEquals(5_000L, late.atMillis)
        assertEquals(ProximityState.FAR, late.state)
        assertTrue(late.events.isEmpty())
    }

    @Test
    fun replayedTimelineProducesTheSameStateAndEventReason() {
        fun run(): Pair<ProximityState, List<ProximityEventReason>> {
            val runner = SessionTimelineProximityRunner(config, "target-1", startEpochMillis)
            val reasons = mutableListOf<ProximityEventReason>()
            listOf(
                observation(processedAt = 1_000L, observedAt = 990L, rssi = -85),
                observation(processedAt = 2_000L, observedAt = 1_990L, rssi = -85),
                observation(processedAt = 3_000L, observedAt = 2_990L, rssi = -60),
                observation(processedAt = 4_000L, observedAt = 3_990L, rssi = -60),
            ).forEach { event ->
                runner.apply(event).events.forEach { reasons += it.reason }
            }
            return runner.advanceTo(startEpochMillis + 4_000L).state to reasons
        }

        assertEquals(run(), run())
        assertEquals(ProximityState.NEAR, run().first)
        assertEquals(
            listOf(ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR),
            run().second,
        )
    }

    private fun observation(
        processedAt: Long,
        observedAt: Long,
        rssi: Int,
        frameId: Long = processedAt,
    ): SessionTimelineEvent.Observation = SessionTimelineEvent.Observation(
        observationId = "observation-$frameId",
        targetId = "target-1",
        rawRssiDbm = rssi,
        observedAtEpochMillis = startEpochMillis + observedAt,
        receivedAtEpochMillis = startEpochMillis + observedAt + 1L,
        scanFrameId = frameId,
        deliverySequence = frameId,
        address = "AA:BB:CC:DD:EE:01",
        payloadFingerprint = "fingerprint-$frameId",
        processedAtEpochMillis = startEpochMillis + processedAt,
    )
}
