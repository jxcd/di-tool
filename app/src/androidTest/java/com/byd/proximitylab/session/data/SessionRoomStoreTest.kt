package com.byd.proximitylab.session.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.coverageEndEpochMillis
import com.byd.proximitylab.session.durationMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionRoomStoreTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: ProximityLabDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "session-room-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun sessionAndTimelineSurviveCloseAndReopenInStableOrder() = runBlocking {
        var store = RoomSessionStore(openDatabase().samplingSessionDao())
        val session = session(startedAt = 1_000L)
        store.createSession(session)

        store.appendTimeline("session-1", observation("obs-1", rawRssiDbm = -85, processedAt = 1_100L))
        store.appendTimeline(
            "session-1",
            SessionTimelineEvent.ScanStateChanged(
                available = true,
                stateLabel = "SCANNING",
                detail = null,
                processedAtEpochMillis = 1_100L,
            ),
        )
        store.appendTimeline(
            "session-1",
            SessionTimelineEvent.IdentityChanged(
                confirmed = true,
                statusLabel = "CONFIRMED",
                targetId = "target-1",
                basisLabel = "ADDRESS_MATCH",
                detail = null,
                processedAtEpochMillis = 1_100L,
            ),
        )
        store.appendTimeline("session-1", observation("obs-2", rawRssiDbm = -61, processedAt = 1_100L))
        val beforeClose = store.loadTimeline("session-1")
        assertEquals(
            listOf(
                SessionTimelineEntity.KIND_OBSERVATION,
                SessionTimelineEntity.KIND_SCAN_STATE,
                SessionTimelineEntity.KIND_IDENTITY_STATE,
                SessionTimelineEntity.KIND_OBSERVATION,
            ),
            beforeClose.map { item -> item.event.toKind() },
        )
        assertEquals(
            beforeClose.map { it.sequence }.sorted(),
            beforeClose.map { it.sequence },
        )

        database?.close()
        store = RoomSessionStore(openDatabase().samplingSessionDao())

        assertEquals(session.copy(lastRecordedAtEpochMillis = 1_100L), store.loadSession("session-1"))
        assertEquals(beforeClose, store.loadTimeline("session-1"))
        val summary = store.observeSessions().first().single()
        assertEquals(2, summary.observationCount)
        assertEquals(1_100L, summary.session.lastRecordedAtEpochMillis)
        assertEquals(1_100L, summary.session.coverageEndEpochMillis)
    }

    @Test
    fun samplingGapIsPreservedWithoutSyntheticEntries() = runBlocking {
        var store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.appendTimeline("session-1", observation("gap-1", rawRssiDbm = -70, processedAt = 1_000L))
        store.appendTimeline("session-1", observation("gap-2", rawRssiDbm = -75, processedAt = 7_000L))

        database?.close()
        store = RoomSessionStore(openDatabase().samplingSessionDao())

        val observations = store.loadTimeline("session-1")
            .filter { it.event is SessionTimelineEvent.Observation }
        assertEquals(listOf(1_000L, 7_000L), observations.map { it.event.processedAtEpochMillis })
        assertEquals(
            6_000L,
            observations.last().event.processedAtEpochMillis - observations.first().event.processedAtEpochMillis,
        )
        assertEquals(7_000L, store.loadSession("session-1")?.lastRecordedAtEpochMillis)
    }

    @Test
    fun completedSessionKeepsConfirmedEndAndWrittenDataAfterReopen() = runBlocking {
        var store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.appendTimeline("session-1", observation("complete-1", rawRssiDbm = -66, processedAt = 2_000L))
        val completed = requireNotNull(store.completeSession("session-1", endedAtEpochMillis = 5_000L))
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(5_000L, completed.endedAtEpochMillis)
        assertEquals(5_000L, completed.lastRecordedAtEpochMillis)

        database?.close()
        store = RoomSessionStore(openDatabase().samplingSessionDao())

        val reopened = requireNotNull(store.loadSession("session-1"))
        assertEquals(completed, reopened)
        assertEquals(4_000L, reopened.durationMillis)
        assertNull(reopened.interruptedDetectedAtEpochMillis)
        assertEquals(1, store.loadTimeline("session-1").size)
    }

    @Test
    fun interruptedSessionOnlyKeepsRecordedCoverageAndData() = runBlocking {
        var store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.appendTimeline("session-1", observation("interrupt-1", rawRssiDbm = -73, processedAt = 1_800L))
        val marked = store.markRecordingSessionsInterrupted(detectedAtEpochMillis = 90_000L)
        assertEquals(1, marked)

        database?.close()
        store = RoomSessionStore(openDatabase().samplingSessionDao())

        val reopened = requireNotNull(store.loadSession("session-1"))
        assertEquals(SessionLifecycleStatus.INTERRUPTED, reopened.status)
        assertNull(reopened.endedAtEpochMillis)
        assertEquals(1_800L, reopened.lastRecordedAtEpochMillis)
        assertEquals(90_000L, reopened.interruptedDetectedAtEpochMillis)
        assertEquals(1_800L, reopened.coverageEndEpochMillis)
        assertEquals(800L, reopened.durationMillis)
        val observation = store.loadTimeline("session-1").single().event
        assertEquals(-73, (observation as SessionTimelineEvent.Observation).rawRssiDbm)
        assertEquals(1, store.observeSessions().first().single().observationCount)
    }

    @Test
    fun sameTimestampEntriesKeepInsertionOrderAcrossReopen() = runBlocking {
        var store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        repeat(6) { index ->
            store.appendTimeline(
                "session-1",
                observation("same-$index", rawRssiDbm = -70 - index, processedAt = 2_000L),
            )
        }
        val beforeClose = store.loadTimeline("session-1")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), beforeClose.map { it.sequence })
        assertEquals(listOf("same-0", "same-1", "same-2", "same-3", "same-4", "same-5"), beforeClose.map {
            (it.event as SessionTimelineEvent.Observation).observationId
        })

        database?.close()
        store = RoomSessionStore(openDatabase().samplingSessionDao())

        assertEquals(beforeClose, store.loadTimeline("session-1"))
    }

    @Test
    fun observeSessionTimelineAndMarkInterruptedAreUsable() = runBlocking {
        val store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.createSession(session(startedAt = 2_000L).copy(id = "session-2"))
        store.appendTimeline("session-1", observation("observe-1", rawRssiDbm = -64, processedAt = 1_500L))
        store.appendTimeline("session-1", observation("observe-2", rawRssiDbm = -70, processedAt = 1_600L))

        assertEquals(
            store.loadSession("session-1"),
            store.observeSession("session-1").first(),
        )
        assertEquals(
            store.loadTimeline("session-1"),
            store.observeTimeline("session-1").first(),
        )

        val interrupted = requireNotNull(store.markInterrupted("session-1", detectedAtEpochMillis = 80_000L))
        assertEquals(SessionLifecycleStatus.INTERRUPTED, interrupted.status)
        assertNull(interrupted.endedAtEpochMillis)
        assertEquals(1_600L, interrupted.lastRecordedAtEpochMillis)
        assertEquals(80_000L, interrupted.interruptedDetectedAtEpochMillis)
        assertEquals(2, store.loadTimeline("session-1").size)
        assertEquals(
            SessionLifecycleStatus.RECORDING,
            store.loadSession("session-2")?.status,
        )
    }

    @Test
    fun completionClampsRolledBackClockToRecordedCoverage() = runBlocking {
        val store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.appendTimeline("session-1", observation("rollback", rawRssiDbm = -72, processedAt = 2_000L))

        // 系统时钟回拨：调用方传入早于 startedAt 的结束时刻时，仍然产生可读的完成记录。
        val completed = requireNotNull(store.completeSession("session-1", endedAtEpochMillis = 500L))

        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(2_000L, completed.endedAtEpochMillis)
        assertEquals(2_000L, completed.lastRecordedAtEpochMillis)
        assertEquals(1_000L, completed.durationMillis)
        assertEquals(1, store.loadTimeline("session-1").size)
    }

    @Test
    fun eventGapIsPersistedWithCoverageAndReadAfterReopen() = runBlocking {
        var store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.appendTimeline(
            "session-1",
            SessionTimelineEvent.EventGap(
                droppedEventCount = 3,
                processedAtEpochMillis = 1_500L,
            ),
        )

        database?.close()
        store = RoomSessionStore(openDatabase().samplingSessionDao())

        val item = store.loadTimeline("session-1").single()
        assertEquals(SessionTimelineEntity.KIND_EVENT_GAP, item.event.toKind())
        val gap = item.event as SessionTimelineEvent.EventGap
        assertEquals(3, gap.droppedEventCount)
        assertEquals(1_500L, gap.processedAtEpochMillis)
        assertEquals(1_500L, store.loadSession("session-1")?.lastRecordedAtEpochMillis)
    }

    @Test
    fun referenceMarkersPersistPromoteLabelAndCascadeOnlyOnSelectedSession() = runBlocking {
        var store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.createSession(session(startedAt = 2_000L).copy(id = "session-2"))
        store.insertReferenceMarker(marker("m-approach", "session-1", ReferenceMarkerKind.APPROACH_START, 1_500L))
        store.insertReferenceMarker(marker("m-expected", "session-1", ReferenceMarkerKind.EXPECTED_TRIGGER, 2_000L))
        store.insertReferenceMarker(marker("m-other", "session-2", ReferenceMarkerKind.EXPECTED_TRIGGER, 2_500L))

        assertEquals(SessionEvaluationLabel.EXPECTED_TRIGGER, store.loadSession("session-1")?.evaluationLabel)
        assertEquals(SessionEvaluationLabel.EXPECTED_TRIGGER, store.loadSession("session-2")?.evaluationLabel)
        assertEquals(
            listOf("m-approach", "m-expected"),
            store.loadReferenceMarkers("session-1").map { it.markerId },
        )

        database?.close()
        store = RoomSessionStore(openDatabase().samplingSessionDao())

        assertEquals(
            listOf("m-approach", "m-expected"),
            store.observeReferenceMarkers("session-1").first().map { it.markerId },
        )
        assertEquals(1_500L, store.loadReferenceMarkers("session-1").first().atEpochMillis)

        assertTrue(store.deleteReferenceMarker("m-approach"))
        assertFalse(store.deleteReferenceMarker("m-approach"))
        assertEquals(listOf("m-expected"), store.loadReferenceMarkers("session-1").map { it.markerId })

        // 删除一个 Session 只级联清理它自己的标记，另一条 Session 的标记仍可读。
        store.deleteSession("session-1")
        assertTrue(store.loadReferenceMarkers("session-1").isEmpty())
        assertEquals(listOf("m-other"), store.loadReferenceMarkers("session-2").map { it.markerId })
        assertNotNull(store.loadSession("session-2"))
    }

    @Test
    fun renameAndEvaluationLabelDoNotChangeTimeline() = runBlocking {
        val store = RoomSessionStore(openDatabase().samplingSessionDao())
        store.createSession(session(startedAt = 1_000L))
        store.appendTimeline("session-1", observation("edit-1", rawRssiDbm = -70, processedAt = 1_500L))
        store.appendTimeline("session-1", observation("edit-2", rawRssiDbm = -60, processedAt = 2_500L))
        val before = store.loadTimeline("session-1")

        val renamed = requireNotNull(store.renameSession("session-1", "  改名后的车头定点  "))
        assertEquals("改名后的车头定点", renamed.name)
        val labelled = requireNotNull(
            store.updateEvaluationLabel("session-1", SessionEvaluationLabel.NO_TRIGGER),
        )
        assertEquals(SessionEvaluationLabel.NO_TRIGGER, labelled.evaluationLabel)
        assertEquals(before, store.loadTimeline("session-1"))

        assertNull(store.renameSession("session-1", "   "))
        assertEquals("改名后的车头定点", store.loadSession("session-1")?.name)
        assertEquals(before, store.loadTimeline("session-1"))
    }

    private fun openDatabase(): ProximityLabDatabase {
        val opened = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
        ).build()
        database?.close()
        database = opened
        return opened
    }

    private fun session(startedAt: Long): SamplingSession = SamplingSession(
        id = "session-1",
        targetId = "target-1",
        targetDisplayName = "BYD test target",
        targetAddress = "AA:BB:CC:DD:EE:01",
        samplingType = SamplingType.FIXED_POINT,
        name = "车头定点",
        location = FixedSampleLocation.FRONT,
        estimatedDistanceMeters = 2.0,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.RECORDING,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = null,
        lastRecordedAtEpochMillis = startedAt,
        interruptedDetectedAtEpochMillis = null,
    )

    private fun observation(
        observationId: String,
        rawRssiDbm: Int,
        processedAt: Long,
    ): SessionTimelineEvent.Observation = SessionTimelineEvent.Observation(
        observationId = observationId,
        targetId = "target-1",
        rawRssiDbm = rawRssiDbm,
        observedAtEpochMillis = processedAt - 40L,
        receivedAtEpochMillis = processedAt - 10L,
        scanFrameId = observationId.hashCode().toLong() * 31L,
        deliverySequence = observationId.hashCode().toLong(),
        address = "AA:BB:CC:DD:EE:01",
        payloadFingerprint = "fingerprint-$observationId",
        processedAtEpochMillis = processedAt,
    )

    private fun marker(
        markerId: String,
        sessionId: String,
        kind: ReferenceMarkerKind,
        atEpochMillis: Long,
    ): SessionReferenceMarker = SessionReferenceMarker(
        markerId = markerId,
        sessionId = sessionId,
        kind = kind,
        atEpochMillis = atEpochMillis,
        createdAtEpochMillis = atEpochMillis,
    )

    private fun SessionTimelineEvent.toKind(): String = when (this) {
        is SessionTimelineEvent.Observation -> SessionTimelineEntity.KIND_OBSERVATION
        is SessionTimelineEvent.ScanStateChanged -> SessionTimelineEntity.KIND_SCAN_STATE
        is SessionTimelineEvent.IdentityChanged -> SessionTimelineEntity.KIND_IDENTITY_STATE
        is SessionTimelineEvent.EventGap -> SessionTimelineEntity.KIND_EVENT_GAP
    }
}
