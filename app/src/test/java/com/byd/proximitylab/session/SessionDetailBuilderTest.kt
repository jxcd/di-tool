package com.byd.proximitylab.session

import com.byd.proximitylab.chart.RssiChartMarkerKind
import com.byd.proximitylab.chart.RssiChartThresholdKind
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailBuilderTest {

    private val startedAt = 1_000L
    private val endedAt = 11_000L

    @Test
    fun fixedPointDetailExposesStatisticsGapsThresholdsAndReferenceMarkers() {
        val session = session(SamplingType.FIXED_POINT)
        val timeline = listOf(
            observation(sequence = 1, processedAt = 1_000L, rssi = -80),
            observation(sequence = 2, processedAt = 2_000L, rssi = -70),
            observation(sequence = 3, processedAt = 10_000L, rssi = -60),
        )
        val markers = listOf(
            marker("m-expected", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 2_000L),
        )
        val snapshot = SessionConfigSnapshot(
            snapshotId = "snapshot-1",
            name = "测试配置",
            sourceConfigId = null,
            config = ProximityConfig(
                algorithm = ProximityAlgorithm.BASELINE,
                enterThresholdDbm = -65,
                exitThresholdDbm = -80,
                confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
                sampleValidityMillis = 5_000L,
                emaAlpha = 0.5,
            ),
        )

        val detail = SessionDetailBuilder.build(
            session = session,
            timeline = timeline,
            markers = markers,
            configSnapshot = snapshot,
        )

        assertEquals("session-1", detail.sessionId)
        assertEquals(3, detail.observationCount)
        assertEquals(snapshot, detail.configSnapshot)
        assertEquals(listOf(1L, 2L, 3L), detail.timeline.map { it.sequence })
        assertEquals(3, detail.statistics?.sampleCount)
        assertEquals(-70.0, detail.statistics?.meanDbm ?: Double.NaN, 1e-9)
        assertEquals(-70.0, detail.statistics?.medianDbm ?: Double.NaN, 1e-9)
        assertEquals(RssiChartWindow.THIRTY_SECONDS, detail.chart.window)
        // 1s 处到 2s 处连续，2s 到 10s 是 8 秒空窗，因此原始曲线断成两段。
        assertEquals(2, detail.chart.segmentCount)
        assertEquals(3, detail.chart.rawSeries?.displayedPointCount)
        assertEquals(2, detail.chart.thresholds.size)
        assertEquals(
            listOf(RssiChartThresholdKind.ENTER, RssiChartThresholdKind.EXIT),
            detail.chart.thresholds.map { it.kind },
        )
        assertEquals(listOf("m-expected"), detail.chart.markers.map { it.id })
        assertEquals(RssiChartMarkerKind.REFERENCE, detail.chart.markers.single().kind)
        assertEquals("到达期望触发位置", detail.chart.markers.single().label)
        assertEquals(2_000L, detail.evaluation.expectedTriggerAtEpochMillis)
        assertTrue(detail.evaluation.isPositiveSample)
        assertEquals(session, detail.session)
    }

    @Test
    fun walkingDetailHasMarkersButNoFixedPointStatistics() {
        val session = session(SamplingType.WALKING)
        val detail = SessionDetailBuilder.build(
            session = session,
            timeline = listOf(
                observation(sequence = 1, processedAt = 1_000L, rssi = -80),
                observation(sequence = 2, processedAt = 2_000L, rssi = -60),
                observation(sequence = 3, processedAt = 10_000L, rssi = -62),
            ),
            markers = listOf(
                marker("m-approach", ReferenceMarkerKind.APPROACH_START, at = 1_500L),
                marker("m-expected", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 2_000L),
                marker("m-leave", ReferenceMarkerKind.LEFT_RANGE, at = 10_000L),
            ),
        )

        assertNull(detail.statistics)
        assertEquals(3, detail.markers.size)
        assertEquals(3, detail.chart.markers.size)
        assertEquals(3, detail.observationCount)
        assertTrue(detail.evaluation.isPositiveSample)
        // 1.5s 和 2s 连续，2s 到 10s 是 8 秒空窗。
        assertEquals(2, detail.chart.segmentCount)
    }

    @Test
    fun chartWindowGrowsWithSessionDuration() {
        assertEquals(RssiChartWindow.THIRTY_SECONDS, SessionDetailBuilder.chartWindowFor(30_000L))
        assertEquals(RssiChartWindow.ONE_MINUTE, SessionDetailBuilder.chartWindowFor(30_001L))
        assertEquals(RssiChartWindow.ONE_MINUTE, SessionDetailBuilder.chartWindowFor(60_000L))
        assertEquals(RssiChartWindow.FIVE_MINUTES, SessionDetailBuilder.chartWindowFor(600_000L))
    }

    private fun session(type: SamplingType): SamplingSession = SamplingSession(
        id = "session-1",
        targetId = "target-1",
        targetDisplayName = "目标",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = type,
        name = type.label,
        location = FixedSampleLocation.REAR,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.POCKET,
        status = SessionLifecycleStatus.COMPLETED,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = endedAt,
        lastRecordedAtEpochMillis = endedAt,
        interruptedDetectedAtEpochMillis = null,
    )

    private fun observation(
        sequence: Long,
        processedAt: Long,
        rssi: Int,
    ): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = "session-1",
        event = SessionTimelineEvent.Observation(
            observationId = "obs-$sequence",
            targetId = "target-1",
            rawRssiDbm = rssi,
            observedAtEpochMillis = processedAt - 10L,
            receivedAtEpochMillis = processedAt - 5L,
            scanFrameId = sequence,
            deliverySequence = sequence,
            address = "AA:BB:CC:DD:EE:01",
            payloadFingerprint = "fingerprint-$sequence",
            processedAtEpochMillis = processedAt,
        ),
    )

    private fun marker(
        id: String,
        kind: ReferenceMarkerKind,
        at: Long,
    ): SessionReferenceMarker = SessionReferenceMarker(
        markerId = id,
        sessionId = "session-1",
        kind = kind,
        atEpochMillis = at,
    )
}
