package com.byd.proximitylab.simulation

import com.byd.proximitylab.live.InMemorySimulationStore
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.InMemorySessionStore
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationEventHistoryReaderTest {

    private val sessionStore = InMemorySessionStore()
    private val simulationStore = InMemorySimulationStore()
    private val reader = SimulationEventHistoryReader(sessionStore, simulationStore)

    @Test
    fun latestSimulationSessionHistoryComesFromPersistedRecords() = runTest {
        val older = session(
            id = "simulation-old",
            startedAt = 1_000L,
            name = "旧测试",
            displayName = "旧目标",
        )
        sessionStore.createSimulationSession(
            older,
            BuiltInSessionConfigs.BASELINE.copy(snapshotId = "snapshot-old", name = "旧配置"),
        )
        simulationStore.appendEvent(event("event-old", older.id, atEpochMillis = 1_500L))

        val fixed = session(
            id = "fixed-newer",
            startedAt = 2_000L,
            name = "较新的定点采样",
            displayName = "定点目标",
        ).copy(samplingType = SamplingType.FIXED_POINT)
        sessionStore.createSession(fixed)

        val latest = session(
            id = "simulation-latest",
            startedAt = 3_000L,
            name = "最新模拟测试",
            displayName = "车边目标",
        )
        val latestSnapshot = BuiltInSessionConfigs.EMA.copy(
            snapshotId = "snapshot-latest",
            name = "口袋 EMA",
        )
        sessionStore.createSimulationSession(latest, latestSnapshot)
        val event = event("event-latest", latest.id, atEpochMillis = 3_500L)
        simulationStore.appendEvent(event)

        val history = reader.observeLatest().first()

        assertEquals("simulation-latest", history.sessionId)
        assertEquals("最新模拟测试", history.sessionName)
        assertEquals(SamplingType.SIMULATION, latest.samplingType)
        val targetLabel = requireNotNull(history.targetLabel)
        assertTrue(targetLabel.contains("车边目标"))
        assertTrue(targetLabel.contains("AA:BB:CC:DD:EE:02"))
        assertEquals("口袋 EMA", history.configName)
        assertEquals(BuiltInSessionConfigs.EMA.config.algorithm, history.configAlgorithm)
        assertEquals(latestSnapshot.algorithmVersion, history.algorithmVersion)
        assertEquals(latestSnapshot.triggerRuleVersion, history.triggerRuleVersion)
        assertEquals(SessionLifecycleStatus.RECORDING, history.sessionStatus)
        assertEquals(listOf("event-latest"), history.entries.map { it.eventId })
        assertEquals(SimulatedEventFeedbackStatus.NOT_ATTEMPTED, history.entries.single().feedbackStatus)
        assertTrue(history.hasEvents)
    }

    @Test
    fun notificationResultIsSeparateAndUpdatesWithoutChangingEventFacts() = runTest {
        val session = session(
            id = "simulation-1",
            startedAt = 1_000L,
            name = "通知未尝试",
            displayName = "目标",
        )
        sessionStore.createSimulationSession(session, BuiltInSessionConfigs.BASELINE)
        val event = event("event-1", session.id, atEpochMillis = 1_500L)
        simulationStore.appendEvent(event)
        val before = reader.observeLatest().first().entries.single()
        assertEquals(SimulatedEventFeedbackStatus.NOT_ATTEMPTED, before.feedbackStatus)
        assertEquals(-60, before.rawRssiDbm)
        assertEquals(ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR, before.reason)

        simulationStore.updateEventFeedback(
            eventId = "event-1",
            status = SimulatedEventFeedbackStatus.UNAVAILABLE,
            detail = "通知权限未授予",
            atEpochMillis = 2_000L,
        )
        val after = reader.observeLatest().first().entries.single()
        assertEquals(SimulatedEventFeedbackStatus.UNAVAILABLE, after.feedbackStatus)
        assertEquals("通知权限未授予", after.feedbackDetail)
        assertEquals(before.rawRssiDbm, after.rawRssiDbm)
        assertEquals(before.atEpochMillis, after.atEpochMillis)
        assertEquals(before.reason, after.reason)
        assertEquals(before.algorithmVersion, after.algorithmVersion)
    }

    @Test
    fun noSimulationSessionProducesEmptyHistory() = runTest {
        val fixed = session(
            id = "fixed-only",
            startedAt = 1_000L,
            name = "仅定点",
            displayName = "目标",
        ).copy(samplingType = SamplingType.FIXED_POINT)
        sessionStore.createSession(fixed)

        val history = reader.observeLatest().first()

        assertEquals(SimulationEventHistory.EMPTY, history)
        assertFalse(history.hasEvents)
    }

    private fun session(
        id: String,
        startedAt: Long,
        name: String,
        displayName: String,
    ): SamplingSession = SamplingSession(
        id = id,
        targetId = "target-1",
        targetDisplayName = displayName,
        targetAddress = "AA:BB:CC:DD:EE:02",
        samplingType = SamplingType.SIMULATION,
        name = name,
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.RECORDING,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = null,
        lastRecordedAtEpochMillis = startedAt,
        interruptedDetectedAtEpochMillis = null,
    )

    private fun event(eventId: String, sessionId: String, atEpochMillis: Long): SessionRunEvent =
        SessionRunEvent(
            eventId = eventId,
            sessionId = sessionId,
            type = ProximityEventType.ENTERED_RANGE,
            reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
            atEpochMillis = atEpochMillis,
            atSessionMillis = atEpochMillis - 1_000L,
            targetId = "target-1",
            frameId = 42L,
            observedAtEpochMillis = atEpochMillis - 10L,
            rawRssiDbm = -60,
            filteredRssiDbm = -59.5,
            algorithmVersion = "proximity-algorithms/1",
            triggerRuleVersion = "confirmed-far-then-near/1",
        )
}
