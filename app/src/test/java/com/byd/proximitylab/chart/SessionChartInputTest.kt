package com.byd.proximitylab.chart

import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChartInputTest {

    @Test
    fun persistedTimelineBuildsReadOnlyChartWithoutLiveScan() {
        val timeline = listOf(
            observationItem(sequence = 1L, processedAt = 1_000L, rawRssi = -85),
            observationItem(sequence = 2L, processedAt = 2_000L, rawRssi = -60),
            scanStateItem(sequence = 3L, processedAt = 3_000L, available = false),
        )
        val event = SessionRunEvent(
            eventId = "event-1",
            sessionId = SESSION_ID,
            type = ProximityEventType.ENTERED_RANGE,
            reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
            atEpochMillis = 2_000L,
            atSessionMillis = 2_000L,
            targetId = TARGET_ID,
            frameId = 42L,
            observedAtEpochMillis = 1_990L,
            rawRssiDbm = -60,
            filteredRssiDbm = -60.0,
            algorithmVersion = "proximity-algorithms/1",
            triggerRuleVersion = "confirmed-far-then-near/1",
        )
        val runState = SessionRunState(
            sessionId = SESSION_ID,
            atEpochMillis = 2_000L,
            atSessionMillis = 2_000L,
            targetId = TARGET_ID,
            state = ProximityState.NEAR,
            reason = ProximityStateReason.CONFIRMED_NEAR,
            trend = MotionTrend.APPROACHING,
            lastReadingFrameId = 42L,
            lastReadingObservedAtEpochMillis = 1_990L,
            rawRssiDbm = -60,
            filteredRssiDbm = -60.0,
            algorithmVersion = "proximity-algorithms/1",
            triggerRuleVersion = "confirmed-far-then-near/1",
        )

        val input = SessionChartInput.fromTimeline(
            timeline = timeline,
            events = listOf(event),
            runStates = listOf(runState),
            referenceMarkers = listOf(
                RssiChartMarkerInput(
                    id = "reference-1",
                    label = "到达期望位置",
                    atEpochMillis = 2_500L,
                    kind = RssiChartMarkerKind.REFERENCE,
                ),
            ),
            window = RssiChartWindow.THIRTY_SECONDS,
            endEpochMillis = 3_000L,
        )
        val data = RssiChartBuilder.build(input)

        assertEquals(2, data.rawSeries!!.sourcePointCount)
        assertEquals(2, data.rawSeries!!.windowPointCount)
        assertEquals(2, data.displayedPointCount)
        assertEquals(2, data.markers.size)
        assertEquals(
            listOf("event-1", "reference-1"),
            data.markers.map { marker -> marker.id },
        )
        assertEquals(RssiChartMarkerKind.REFERENCE, data.markers.last().kind)
        assertEquals(1, data.stateMarkers.size)
        assertEquals(ProximityState.NEAR, data.stateMarkers.single().state)
        // 扫描状态事件不产生曲线点，但原始观测仍按处理时刻保留。
        assertEquals(
            listOf(1_000L, 2_000L),
            data.rawSeries!!.segments.single().points.map { point -> point.atEpochMillis },
        )
        assertTrue(data.hasData)
    }

    private fun observationItem(
        sequence: Long,
        processedAt: Long,
        rawRssi: Int,
    ): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = SESSION_ID,
        event = SessionTimelineEvent.Observation(
            observationId = "obs-$sequence",
            targetId = TARGET_ID,
            rawRssiDbm = rawRssi,
            observedAtEpochMillis = processedAt - 10L,
            receivedAtEpochMillis = processedAt - 5L,
            scanFrameId = sequence,
            deliverySequence = sequence,
            address = "AA:BB:CC:DD:EE:01",
            payloadFingerprint = "fingerprint-$sequence",
            processedAtEpochMillis = processedAt,
        ),
    )

    private fun scanStateItem(
        sequence: Long,
        processedAt: Long,
        available: Boolean,
    ): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = SESSION_ID,
        event = SessionTimelineEvent.ScanStateChanged(
            available = available,
            stateLabel = if (available) "SCANNING" else "STOPPED",
            detail = null,
            processedAtEpochMillis = processedAt,
        ),
    )

    private companion object {
        const val SESSION_ID = "session-1"
        const val TARGET_ID = "target-1"
    }
}
