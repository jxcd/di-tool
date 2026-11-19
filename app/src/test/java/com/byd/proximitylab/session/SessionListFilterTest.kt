package com.byd.proximitylab.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListFilterTest {

    @Test
    fun filtersByDeviceLocationAndTypeTogether() {
        val sessions = listOf(
            summary(id = "fixed", targetId = "target-1", type = SamplingType.FIXED_POINT, location = FixedSampleLocation.REAR),
            summary(id = "walk", targetId = "target-1", type = SamplingType.WALKING, location = FixedSampleLocation.REAR),
            summary(id = "sim", targetId = "target-1", type = SamplingType.SIMULATION, location = FixedSampleLocation.OTHER),
            summary(id = "other-target", targetId = "target-2", type = SamplingType.WALKING, location = FixedSampleLocation.REAR),
        )

        val filter = SessionListFilter(
            targetId = "target-1",
            location = FixedSampleLocation.REAR,
            samplingType = SamplingType.WALKING,
        )

        assertEquals(listOf("walk"), filter.apply(sessions).map { it.session.id })
        assertTrue(filter.isActive)
    }

    @Test
    fun emptyFilterKeepsEverySessionAndMatchesIndividualFields() {
        val sessions = listOf(
            summary(id = "fixed", targetId = "target-1", type = SamplingType.FIXED_POINT, location = FixedSampleLocation.FRONT),
            summary(id = "walk", targetId = "target-2", type = SamplingType.WALKING, location = FixedSampleLocation.REAR),
        )

        val empty = SessionListFilter()
        assertFalse(empty.isActive)
        assertEquals(sessions, empty.apply(sessions))
        assertTrue(empty.matches(sessions.first().session))

        assertTrue(SessionListFilter(samplingType = SamplingType.WALKING).matches(sessions.last().session))
        assertFalse(SessionListFilter(location = FixedSampleLocation.REAR).matches(sessions.first().session))
        assertTrue(SessionListFilter(targetId = "target-1").matches(sessions.first().session))
        assertFalse(SessionListFilter(targetId = "target-2").matches(sessions.first().session))
    }

    private fun summary(
        id: String,
        targetId: String,
        type: SamplingType,
        location: FixedSampleLocation,
    ): SessionSummary = SessionSummary(
        session = SamplingSession(
            id = id,
            targetId = targetId,
            targetDisplayName = targetId,
            targetAddress = "AA:BB:CC:DD:EE:01",
            samplingType = type,
            name = id,
            location = location,
            estimatedDistanceMeters = null,
            phonePlacement = PhonePlacement.HANDHELD,
            status = SessionLifecycleStatus.COMPLETED,
            startedAtEpochMillis = 1_000L,
            endedAtEpochMillis = 2_000L,
            lastRecordedAtEpochMillis = 2_000L,
            interruptedDetectedAtEpochMillis = null,
        ),
        observationCount = 0,
    )
}
