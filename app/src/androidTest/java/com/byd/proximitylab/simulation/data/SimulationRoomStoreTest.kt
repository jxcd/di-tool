package com.byd.proximitylab.simulation.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 05 的真实 Room 存储：配置快照、原始事件、状态转换和反馈结果在关闭重开后保持关联。
 */
@RunWith(AndroidJUnit4::class)
class SimulationRoomStoreTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: ProximityLabDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "simulation-room-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun snapshotEventsStatesAndFeedbackSurviveReopen() = runBlocking {
        val session = session()
        val first = open()
        val sessionStore = RoomSessionStore(first.samplingSessionDao())
        val runStore = RoomSimulationRunStore(first.simulationRunDao())

        sessionStore.createSimulationSession(session, BuiltInSessionConfigs.EMA)
        sessionStore.appendTimeline(
            session.id,
            SessionTimelineEvent.Observation(
                observationId = "observation-1",
                targetId = session.targetId,
                rawRssiDbm = -60,
                observedAtEpochMillis = START + 990L,
                receivedAtEpochMillis = START + 995L,
                scanFrameId = 77L,
                deliverySequence = 1L,
                address = session.targetAddress,
                payloadFingerprint = "fingerprint-77",
                processedAtEpochMillis = START + 1_000L,
            ),
        )
        runStore.appendEvent(event(session.id))
        runStore.appendState(
            SessionRunState(
                sessionId = session.id,
                atEpochMillis = START + 4_000L,
                atSessionMillis = 4_000L,
                targetId = session.targetId,
                state = ProximityState.NEAR,
                reason = ProximityStateReason.CONFIRMED_NEAR,
                trend = MotionTrend.APPROACHING,
                lastReadingFrameId = 77L,
                lastReadingObservedAtEpochMillis = START + 3_990L,
                rawRssiDbm = -60,
                filteredRssiDbm = -60.0,
                algorithmVersion = "proximity-algorithms/1",
                triggerRuleVersion = "confirmed-far-then-near/1",
            ),
        )

        assertEquals(BuiltInSessionConfigs.EMA, sessionStore.loadConfigSnapshot(session.id))
        assertEquals(1, runStore.loadEvents(session.id).size)
        assertEquals(1, runStore.loadStates(session.id).size)
        assertEquals(ProximityState.NEAR, runStore.loadStates(session.id).single().state)

        val feedback = runStore.updateEventFeedback(
            eventId = EVENT_ID,
            status = SimulatedEventFeedbackStatus.DELIVERED,
            detail = "已提交本地反馈",
            atEpochMillis = START + 6_000L,
        )
        requireNotNull(feedback)
        assertEquals(SimulatedEventFeedbackStatus.DELIVERED, feedback.feedbackStatus)
        assertEquals(START + 6_000L, feedback.feedbackUpdatedAtEpochMillis)

        first.close()

        val reopened = open()
        val reopenedSessions = RoomSessionStore(reopened.samplingSessionDao())
        val reopenedRuns = RoomSimulationRunStore(reopened.simulationRunDao())

        assertEquals(BuiltInSessionConfigs.EMA, reopenedSessions.loadConfigSnapshot(session.id))
        assertEquals(session, reopenedSessions.loadSession(session.id))
        assertEquals(1, reopenedSessions.loadTimeline(session.id).size)
        assertEquals(1, reopenedSessions.loadTimeline(session.id).single().sequence)

        val storedEvent = reopenedRuns.loadEvents(session.id).single()
        assertEquals(EVENT_ID, storedEvent.eventId)
        assertEquals(session.targetId, storedEvent.targetId)
        assertEquals(77L, storedEvent.frameId)
        assertEquals(-60, storedEvent.rawRssiDbm)
        assertEquals(-60.0, storedEvent.filteredRssiDbm, 0.0)
        assertEquals(4_000L, storedEvent.atSessionMillis)
        assertEquals(ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR, storedEvent.reason)
        assertEquals(SimulatedEventFeedbackStatus.DELIVERED, storedEvent.feedbackStatus)
        assertEquals("已提交本地反馈", storedEvent.feedbackDetail)

        val storedState = reopenedRuns.loadStates(session.id).single()
        assertEquals(ProximityState.NEAR, storedState.state)
        assertEquals(ProximityStateReason.CONFIRMED_NEAR, storedState.reason)
        assertEquals(77L, storedState.lastReadingFrameId)
        assertEquals("proximity-algorithms/1", storedState.algorithmVersion)
        assertEquals("confirmed-far-then-near/1", storedState.triggerRuleVersion)
    }

    @Test
    fun unknownEventFeedbackReturnsNullAndSessionsWithoutSnapshotStayNull() = runBlocking {
        val first = open()
        val sessionStore = RoomSessionStore(first.samplingSessionDao())
        val runStore = RoomSimulationRunStore(first.simulationRunDao())
        sessionStore.createSession(session())

        assertNull(sessionStore.loadConfigSnapshot(SESSION_ID))
        assertNull(runStore.updateEventFeedback("missing", SimulatedEventFeedbackStatus.FAILED, null, 1L))
    }

    private fun open(): ProximityLabDatabase {
        database?.close()
        val opened = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        ).build()
        database = opened
        return opened
    }

    private fun session(): SamplingSession = SamplingSession(
        id = SESSION_ID,
        targetId = "target-1",
        targetDisplayName = "BYD test target",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = SamplingType.SIMULATION,
        name = "模拟测试",
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.COMPLETED,
        startedAtEpochMillis = START,
        endedAtEpochMillis = START + 10_000L,
        lastRecordedAtEpochMillis = START + 10_000L,
        interruptedDetectedAtEpochMillis = null,
    )

    private fun event(sessionId: String): SessionRunEvent = SessionRunEvent(
        eventId = EVENT_ID,
        sessionId = sessionId,
        type = ProximityEventType.ENTERED_RANGE,
        reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
        atEpochMillis = START + 4_000L,
        atSessionMillis = 4_000L,
        targetId = "target-1",
        frameId = 77L,
        observedAtEpochMillis = START + 3_990L,
        rawRssiDbm = -60,
        filteredRssiDbm = -60.0,
        algorithmVersion = "proximity-algorithms/1",
        triggerRuleVersion = "confirmed-far-then-near/1",
    )

    private companion object {
        const val SESSION_ID = "simulation-session"
        const val EVENT_ID = "simulation-event-1"
        const val START = 1_800_000_000_000L
    }
}
