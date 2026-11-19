package com.byd.proximitylab.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReferenceMarkersTest {

    @Test
    fun markersWithinCoverageAreUsedAndOutsideMarkersStayDiagnostic() {
        val session = session(startedAt = 1_000L, endedAt = 5_000L)
        val markers = listOf(
            marker("m-late", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 5_500L),
            marker("m-early", ReferenceMarkerKind.APPROACH_START, at = 500L),
            marker("m-in", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 3_000L),
        )

        val evaluation = SessionMarkerPolicy.evaluate(session, markers)

        assertEquals(listOf("m-in"), evaluation.markersInCoverage.map { it.markerId })
        assertEquals(listOf("m-early", "m-late"), evaluation.markersOutsideCoverage.map { it.markerId })
        assertEquals(3_000L, evaluation.expectedTriggerAtEpochMillis)
        assertTrue(evaluation.isPositiveSample)
        assertFalse(evaluation.isNegativeSample)
        assertFalse(evaluation.expectedTriggerConflict)
    }

    @Test
    fun interruptedSessionUsesLastWrittenCoverageNotInterruptionDetectionTime() {
        val session = session(
            startedAt = 1_000L,
            endedAt = null,
            lastRecordedAt = 4_000L,
            status = SessionLifecycleStatus.INTERRUPTED,
            interruptedDetectedAt = 9_000L,
        )
        val markers = listOf(
            marker("m-written", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 4_000L),
            marker("m-unwritten", ReferenceMarkerKind.LEFT_RANGE, at = 6_000L),
        )

        val evaluation = SessionMarkerPolicy.evaluate(session, markers)

        assertEquals(listOf("m-written"), evaluation.markersInCoverage.map { it.markerId })
        assertEquals(listOf("m-unwritten"), evaluation.markersOutsideCoverage.map { it.markerId })
        assertEquals(4_000L, evaluation.expectedTriggerAtEpochMillis)
    }

    @Test
    fun multipleExpectedTriggersInCoverageAreAConflictAndNoTriggerIsChosen() {
        val session = session(startedAt = 1_000L, endedAt = 9_000L)
        val markers = listOf(
            marker("m-1", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 2_000L),
            marker("m-2", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 6_000L),
        )

        val evaluation = SessionMarkerPolicy.evaluate(session, markers)

        assertTrue(evaluation.expectedTriggerConflict)
        assertNull(evaluation.expectedTriggerAtEpochMillis)
        assertFalse(evaluation.isPositiveSample)
        assertTrue(evaluation.markersInCoverage.isNotEmpty())
    }

    @Test
    fun negativeLabelWinsEvenWhenExpectedMarkerExists() {
        val session = session(
            startedAt = 1_000L,
            endedAt = 9_000L,
            label = SessionEvaluationLabel.NO_TRIGGER,
        )
        val markers = listOf(
            marker("m-1", ReferenceMarkerKind.EXPECTED_TRIGGER, at = 2_000L),
        )

        val evaluation = SessionMarkerPolicy.evaluate(session, markers)

        assertTrue(evaluation.isNegativeSample)
        assertFalse(evaluation.isPositiveSample)
        assertNull(evaluation.expectedTriggerAtEpochMillis)
        assertTrue(evaluation.hasScoringBasis)
    }

    @Test
    fun expectedLabelWithoutMarkerIsMissingTriggerAndUnmarkedSessionHasNoScoringBasis() {
        val expected = SessionMarkerPolicy.evaluate(
            session(
                startedAt = 1_000L,
                endedAt = 9_000L,
                label = SessionEvaluationLabel.EXPECTED_TRIGGER,
            ),
            emptyList(),
        )
        assertTrue(expected.missingExpectedTrigger)
        assertFalse(expected.hasScoringBasis)

        val unmarked = SessionMarkerPolicy.evaluate(
            session(startedAt = 1_000L, endedAt = 9_000L),
            listOf(marker("m-approach", ReferenceMarkerKind.APPROACH_START, at = 2_000L)),
        )
        assertFalse(unmarked.missingExpectedTrigger)
        assertFalse(unmarked.hasScoringBasis)
    }

    @Test
    fun markerOffsetUsesSessionTimeBase() {
        val session = session(startedAt = 1_000L, endedAt = 9_000L)
        val marker = marker("m-1", ReferenceMarkerKind.APPROACH_START, at = 3_500L)

        assertEquals(2_500L, marker.sessionOffsetMillis(session))
    }

    private fun session(
        startedAt: Long,
        endedAt: Long?,
        lastRecordedAt: Long = endedAt ?: startedAt,
        status: SessionLifecycleStatus = SessionLifecycleStatus.COMPLETED,
        interruptedDetectedAt: Long? = null,
        label: SessionEvaluationLabel = SessionEvaluationLabel.UNMARKED,
    ): SamplingSession = SamplingSession(
        id = "session-1",
        targetId = "target-1",
        targetDisplayName = "目标",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = SamplingType.WALKING,
        name = "行走采样",
        location = FixedSampleLocation.FRONT,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = status,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = endedAt,
        lastRecordedAtEpochMillis = lastRecordedAt,
        interruptedDetectedAtEpochMillis = interruptedDetectedAt,
        evaluationLabel = label,
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
        createdAtEpochMillis = at,
    )
}
