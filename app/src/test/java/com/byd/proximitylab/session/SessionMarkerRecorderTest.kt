package com.byd.proximitylab.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SessionMarkerRecorderTest {

    private var now = 2_000L

    @Test
    fun recordsMarkerAtClockAndPromotesUnmarkedSessionToExpectedTrigger() = runBlocking {
        val store = InMemorySessionStore()
        store.createSession(session(status = SessionLifecycleStatus.RECORDING))
        val recorder = SessionMarkerRecorder(store = store, clock = { now }, idGenerator = { "marker-1" })

        val result = recorder.addMarker("session-1", ReferenceMarkerKind.EXPECTED_TRIGGER)

        val recorded = result as ReferenceMarkerResult.Recorded
        assertEquals(2_000L, recorded.marker.atEpochMillis)
        assertEquals(SessionEvaluationLabel.EXPECTED_TRIGGER, recorded.session.evaluationLabel)
        assertEquals(
            listOf("marker-1"),
            store.loadReferenceMarkers("session-1").map { it.markerId },
        )
    }

    @Test
    fun clockRollbackIsClampedToSessionStartAndNonExpectedMarkerKeepsLabel() = runBlocking {
        val store = InMemorySessionStore()
        store.createSession(session(status = SessionLifecycleStatus.RECORDING))
        now = 500L
        val recorder = SessionMarkerRecorder(store = store, clock = { now }, idGenerator = { "marker-1" })

        val recorded = recorder.addMarker(
            sessionId = "session-1",
            kind = ReferenceMarkerKind.APPROACH_START,
        ) as ReferenceMarkerResult.Recorded

        assertEquals(1_000L, recorded.marker.atEpochMillis)
        assertEquals(SessionEvaluationLabel.UNMARKED, recorded.session.evaluationLabel)
    }

    @Test
    fun negativeLabelIsNotOverwrittenByExpectedMarker() = runBlocking {
        val store = InMemorySessionStore()
        store.createSession(
            session(
                status = SessionLifecycleStatus.RECORDING,
                label = SessionEvaluationLabel.NO_TRIGGER,
            ),
        )
        val recorder = SessionMarkerRecorder(store = store, clock = { now }, idGenerator = { "marker-1" })

        val recorded = recorder.addMarker(
            sessionId = "session-1",
            kind = ReferenceMarkerKind.EXPECTED_TRIGGER,
        ) as ReferenceMarkerResult.Recorded

        assertEquals(SessionEvaluationLabel.NO_TRIGGER, recorded.session.evaluationLabel)
    }

    @Test
    fun completedOrUnknownSessionRejectsLiveMarkersAndTimelineIsUntouched() = runBlocking {
        val store = InMemorySessionStore()
        store.createSession(session(status = SessionLifecycleStatus.COMPLETED))
        store.appendTimeline("session-1", observation(processedAt = 1_500L))
        val before = store.loadTimeline("session-1")
        val recorder = SessionMarkerRecorder(store = store, clock = { now }, idGenerator = { "marker-1" })

        assertEquals(
            ReferenceMarkerResult.NotRecording,
            recorder.addMarker("session-1", ReferenceMarkerKind.EXPECTED_TRIGGER),
        )
        assertEquals(
            ReferenceMarkerResult.MissingSession,
            recorder.addMarker("missing-session", ReferenceMarkerKind.EXPECTED_TRIGGER),
        )
        assertEquals(before, store.loadTimeline("session-1"))
        assertTrue(store.observeReferenceMarkers("session-1").first().isEmpty())
        assertFalse(store.deleteReferenceMarker("missing-marker"))
    }

    private fun session(
        status: SessionLifecycleStatus,
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
        startedAtEpochMillis = 1_000L,
        endedAtEpochMillis = if (status == SessionLifecycleStatus.COMPLETED) 5_000L else null,
        lastRecordedAtEpochMillis = if (status == SessionLifecycleStatus.COMPLETED) 5_000L else 1_000L,
        interruptedDetectedAtEpochMillis = null,
        evaluationLabel = label,
    )

    private fun observation(processedAt: Long): SessionTimelineEvent.Observation =
        SessionTimelineEvent.Observation(
            observationId = "obs-${UUID.randomUUID()}",
            targetId = "target-1",
            rawRssiDbm = -70,
            observedAtEpochMillis = processedAt - 10L,
            receivedAtEpochMillis = processedAt - 5L,
            scanFrameId = 1L,
            deliverySequence = 1L,
            address = "AA:BB:CC:DD:EE:01",
            payloadFingerprint = "fingerprint",
            processedAtEpochMillis = processedAt,
        )
}
