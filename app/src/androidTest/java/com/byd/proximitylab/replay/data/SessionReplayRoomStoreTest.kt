package com.byd.proximitylab.replay.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.replay.ReplayConfigSource
import com.byd.proximitylab.replay.ReplayEvent
import com.byd.proximitylab.replay.ReplayInputCoverage
import com.byd.proximitylab.replay.ReplayOutcome
import com.byd.proximitylab.replay.ReplaySample
import com.byd.proximitylab.replay.ReplayStatePoint
import com.byd.proximitylab.replay.ReplayVersionInfo
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.data.RoomSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 09 回放结果的真实 Room 存储：多份结果按版本独立查询、关闭重开后完整恢复、随 Session 级联删除。
 */
@RunWith(AndroidJUnit4::class)
class SessionReplayRoomStoreTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: ProximityLabDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "session-replay-store-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun multipleReplayVersionsSurviveReopenAndCascadeWithSession() = runBlocking {
        val first = open()
        val sessionStore = RoomSessionStore(first.samplingSessionDao())
        val replayStore = RoomSessionReplayStore(first.sessionReplayDao())
        sessionStore.createSession(session())

        val oldVersion = record(
            replayId = "replay-old",
            createdAtEpochMillis = STARTED_AT + 1_000L,
            snapshot = snapshot("旧版本", "proximity-algorithms/0", "confirmed-far-then-near/0"),
            outcome = ReplayOutcome.VERSION_RECOMPUTED,
            eventCount = 1,
        )
        val currentVersion = record(
            replayId = "replay-current",
            createdAtEpochMillis = STARTED_AT + 2_000L,
            snapshot = snapshot("当前版本", "proximity-algorithms/1", "confirmed-far-then-near/1"),
            outcome = ReplayOutcome.REPRODUCED,
            eventCount = 0,
        )
        replayStore.saveReplay(oldVersion)
        replayStore.saveReplay(currentVersion)

        assertEquals(
            listOf("replay-current", "replay-old"),
            replayStore.observeReplaySummaries(SESSION_ID).first().map { summary -> summary.replayId },
        )
        assertEquals(oldVersion, replayStore.loadReplay("replay-old"))
        assertEquals(currentVersion, replayStore.loadReplay("replay-current"))

        first.close()

        val reopened = open()
        val reopenedSessions = RoomSessionStore(reopened.samplingSessionDao())
        val reopenedReplays = RoomSessionReplayStore(reopened.sessionReplayDao())

        assertEquals(2, reopenedReplays.loadReplays(SESSION_ID).size)
        assertEquals(oldVersion, reopenedReplays.loadReplay("replay-old"))
        val summaries = reopenedReplays.loadReplaySummaries(SESSION_ID)
        assertEquals(
            listOf("proximity-algorithms/1", "proximity-algorithms/0"),
            summaries.map { summary -> summary.versions.snapshotAlgorithmVersion },
        )
        assertTrue(summaries.first { summary -> summary.replayId == "replay-old" }.versions.versionMismatch)
        assertEquals(1, summaries.first { summary -> summary.replayId == "replay-old" }.eventCount)
        assertEquals(0, summaries.first { summary -> summary.replayId == "replay-current" }.eventCount)

        reopenedSessions.deleteSession(SESSION_ID)
        assertNull(reopenedReplays.loadReplay("replay-old"))
        assertTrue(reopenedReplays.loadReplays(SESSION_ID).isEmpty())
        reopened.close()
    }

    private fun open(): ProximityLabDatabase = Room.databaseBuilder(
        context,
        ProximityLabDatabase::class.java,
        databaseName,
    ).addMigrations(
        ProximityLabDatabase.MIGRATION_1_2,
        ProximityLabDatabase.MIGRATION_2_3,
        ProximityLabDatabase.MIGRATION_3_4,
        ProximityLabDatabase.MIGRATION_4_5,
        ProximityLabDatabase.MIGRATION_5_6,
    ).build().also { database = it }

    private fun session(): SamplingSession = SamplingSession(
        id = SESSION_ID,
        targetId = "target-1",
        targetDisplayName = "目标车",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = SamplingType.SIMULATION,
        name = "回放存储测试",
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.COMPLETED,
        startedAtEpochMillis = STARTED_AT,
        endedAtEpochMillis = STARTED_AT + 2_000L,
        lastRecordedAtEpochMillis = STARTED_AT + 2_000L,
        interruptedDetectedAtEpochMillis = null,
    )

    private fun snapshot(
        name: String,
        algorithmVersion: String,
        triggerRuleVersion: String,
    ): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "snapshot-$name",
        name = name,
        sourceConfigId = null,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.BASELINE,
            enterThresholdDbm = -65,
            exitThresholdDbm = -80,
            confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
            sampleValidityMillis = 5_000L,
            emaAlpha = 0.5,
        ),
        algorithmVersion = algorithmVersion,
        triggerRuleVersion = triggerRuleVersion,
    )

    private fun record(
        replayId: String,
        createdAtEpochMillis: Long,
        snapshot: SessionConfigSnapshot,
        outcome: ReplayOutcome,
        eventCount: Int,
    ): SessionReplayRecord {
        val initialState = ReplayStatePoint(
            atEpochMillis = STARTED_AT,
            atSessionMillis = 0L,
            state = ProximityState.UNKNOWN,
            reason = ProximityStateReason.AWAITING_OBSERVATION,
            trend = MotionTrend.UNKNOWN,
            lastReadingFrameId = null,
            lastReadingObservedAtEpochMillis = null,
            rawRssiDbm = null,
            filteredRssiDbm = null,
        )
        val finalState = ReplayStatePoint(
            atEpochMillis = STARTED_AT + 2_000L,
            atSessionMillis = 2_000L,
            state = ProximityState.FAR,
            reason = ProximityStateReason.CONFIRMED_FAR,
            trend = MotionTrend.APPROACHING,
            lastReadingFrameId = 11L,
            lastReadingObservedAtEpochMillis = STARTED_AT + 1_990L,
            rawRssiDbm = -85,
            filteredRssiDbm = -85.0,
        )
        return SessionReplayRecord(
            replayId = replayId,
            sessionId = SESSION_ID,
            config = snapshot,
            configSource = ReplayConfigSource.ORIGINAL_SNAPSHOT,
            outcome = outcome,
            versions = ReplayVersionInfo(
                originalSnapshotAvailable = true,
                originalAlgorithmVersion = "proximity-algorithms/0",
                originalTriggerRuleVersion = "confirmed-far-then-near/0",
                snapshotAlgorithmVersion = snapshot.algorithmVersion,
                snapshotTriggerRuleVersion = snapshot.triggerRuleVersion,
                executedAlgorithmVersion = "proximity-algorithms/1",
                executedTriggerRuleVersion = "confirmed-far-then-near/1",
            ),
            coverage = ReplayInputCoverage(
                startedAtEpochMillis = STARTED_AT,
                coverageEndEpochMillis = STARTED_AT + 2_000L,
                sessionStatus = SessionLifecycleStatus.COMPLETED,
                interrupted = false,
                hasEventGaps = false,
                droppedEventCount = 0,
                observationCount = 2,
            ),
            samples = listOf(
                ReplaySample(
                    atEpochMillis = STARTED_AT + 1_000L,
                    atSessionMillis = 1_000L,
                    observedAtEpochMillis = STARTED_AT + 1_000L,
                    frameId = 11L,
                    rawRssiDbm = -85,
                    filteredRssiDbm = -85.0,
                    state = ProximityState.UNKNOWN,
                    reason = ProximityStateReason.EVIDENCE_PENDING,
                    trend = MotionTrend.UNKNOWN,
                ),
            ),
            states = listOf(initialState, finalState),
            events = buildList {
                repeat(eventCount) { index ->
                    add(
                        ReplayEvent(
                            atEpochMillis = STARTED_AT + 2_000L,
                            atSessionMillis = 2_000L,
                            type = ProximityEventType.ENTERED_RANGE,
                            reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
                            targetId = "target-1",
                            frameId = 12L + index,
                            observedAtEpochMillis = STARTED_AT + 1_990L,
                            rawRssiDbm = -60,
                            filteredRssiDbm = -60.0,
                            algorithmVersion = "proximity-algorithms/1",
                            triggerRuleVersion = "confirmed-far-then-near/1",
                        ),
                    )
                }
            },
            finalState = finalState,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private companion object {
        const val SESSION_ID = "replay-store-session"
        const val STARTED_AT = 1_800_000_000_000L
    }
}
