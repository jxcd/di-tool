package com.byd.proximitylab.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 08 的真实 Room 会话管理：定点与行走会话、同一会话时基的人工标记、编辑不改原始观测、
 * 删除只作用于所选 Session，以及关闭重开后已采数据仍可读。
 *
 * 只替代 BLE 系统回调与时间输入；会话生命周期、时间线顺序、标记和持久化都走生产实现。
 */
@RunWith(AndroidJUnit4::class)
class SessionManagementRoomTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ProximityLabDatabase
    private lateinit var source: ControllableBleScanSource
    private lateinit var scope: CoroutineScope
    private lateinit var monitor: TargetMonitor
    private lateinit var store: SessionStore
    private lateinit var recorder: SessionRecorder
    private lateinit var markerRecorder: SessionMarkerRecorder
    private var now = 1_800_000_000_000L
    private var sequence = 0L
    private var sessionSequence = 0L

    private val addressA = "AA:BB:CC:DD:EE:01"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "session-management-${UUID.randomUUID()}.db"
        database = openDatabase()
        store = RoomSessionStore(database.samplingSessionDao())
        source = ControllableBleScanSource()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bindingStore = RoomTargetBindingStore(database.targetBindingDao())
        runBlocking {
            bindingStore.saveBinding(
                testBinding(targetId = "target-1", address = addressA, displayName = "目标车"),
            )
        }
        monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        awaitBinding()
        recorder = SessionRecorder(
            store = store,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "room-session-${++sessionSequence}" },
        )
        markerRecorder = SessionMarkerRecorder(
            store = store,
            clock = { now },
            idGenerator = { "room-marker-${++sessionSequence}" },
        )
    }

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun fixedAndWalkingSessionsKeepMarkersOnSameTimeBaseAndEditsDoNotTouchSamples() = runBlocking {
        val fixed = recorder.start(
            SessionStartRequest(
                binding = testBinding(targetId = "target-1", address = addressA, displayName = "目标车"),
                samplingType = SamplingType.FIXED_POINT,
                name = "主驾定点",
                location = FixedSampleLocation.DRIVER_SEAT,
                estimatedDistanceMeters = null,
                phonePlacement = PhonePlacement.POCKET,
            ),
        ) as SessionStartResult.Started
        advanceAndEmit(rssi = -80)
        advanceAndEmit(rssi = -60)
        awaitObservations(fixed.session.id, expected = 2)
        now += 1_000L
        val fixedCompleted = stopAndLoad(fixed.session.id)

        val fixedDetail = SessionDetailBuilder.build(
            session = fixedCompleted,
            timeline = store.loadTimeline(fixedCompleted.id),
            markers = store.loadReferenceMarkers(fixedCompleted.id),
        )
        assertNull(fixedCompleted.estimatedDistanceMeters)
        assertEquals(FixedSampleLocation.DRIVER_SEAT, fixedCompleted.location)
        assertEquals(2, fixedDetail.statistics?.sampleCount)
        assertEquals(-70.0, fixedDetail.statistics?.meanDbm ?: Double.NaN, 1e-9)
        assertEquals(-70.0, fixedDetail.statistics?.medianDbm ?: Double.NaN, 1e-9)
        assertEquals(10.0, fixedDetail.statistics?.standardDeviationDbm ?: Double.NaN, 1e-9)

        now += 1_000L
        val walking = recorder.start(
            SessionStartRequest(
                binding = testBinding(targetId = "target-1", address = addressA, displayName = "目标车"),
                samplingType = SamplingType.WALKING,
                name = "车头行走",
                location = FixedSampleLocation.FRONT,
                estimatedDistanceMeters = 3.5,
                phonePlacement = PhonePlacement.HANDHELD,
            ),
        ) as SessionStartResult.Started
        advanceAndEmit(rssi = -85)
        now += 1_000L
        val approachAt = now
        markerRecorder.addMarker(walking.session.id, ReferenceMarkerKind.APPROACH_START)
        advanceAndEmit(rssi = -60)
        now += 1_000L
        val expectedAt = now
        markerRecorder.addMarker(walking.session.id, ReferenceMarkerKind.EXPECTED_TRIGGER)
        now += 1_000L
        val departAt = now
        markerRecorder.addMarker(walking.session.id, ReferenceMarkerKind.DEPARTURE_START)
        awaitObservations(walking.session.id, expected = 2)
        now += 1_000L
        val walkingCompleted = stopAndLoad(walking.session.id)

        assertEquals(SamplingType.WALKING, walkingCompleted.samplingType)
        assertEquals(SessionEvaluationLabel.EXPECTED_TRIGGER, walkingCompleted.evaluationLabel)
        val markers = store.loadReferenceMarkers(walkingCompleted.id)
        assertEquals(
            listOf(
                ReferenceMarkerKind.APPROACH_START,
                ReferenceMarkerKind.EXPECTED_TRIGGER,
                ReferenceMarkerKind.DEPARTURE_START,
            ),
            markers.map { it.kind },
        )
        assertEquals(listOf(approachAt, expectedAt, departAt), markers.map { it.atEpochMillis })
        assertEquals(
            listOf(approachAt, expectedAt, departAt).map { at -> at - walking.session.startedAtEpochMillis },
            markers.map { it.sessionOffsetMillis(walkingCompleted) },
        )

        val walkingDetail = SessionDetailBuilder.build(
            session = walkingCompleted,
            timeline = store.loadTimeline(walkingCompleted.id),
            markers = markers,
        )
        assertNull(walkingDetail.statistics)
        assertEquals(expectedAt, walkingDetail.evaluation.expectedTriggerAtEpochMillis)
        assertTrue(walkingDetail.evaluation.isPositiveSample)
        assertEquals(3, walkingDetail.markers.size)

        val timelineBeforeEdit = store.loadTimeline(walkingCompleted.id)
        val renamed = requireNotNull(store.renameSession(walkingCompleted.id, "车头行走（改名）"))
        assertEquals("车头行走（改名）", renamed.name)
        val labelled = requireNotNull(
            store.updateEvaluationLabel(walkingCompleted.id, SessionEvaluationLabel.NO_TRIGGER),
        )
        assertEquals(SessionEvaluationLabel.NO_TRIGGER, labelled.evaluationLabel)
        // 改名/改标签不触碰原始观测。
        assertEquals(timelineBeforeEdit, store.loadTimeline(walkingCompleted.id))
        assertEquals(
            timelineBeforeEdit.map { item -> item.event },
            store.loadTimeline(walkingCompleted.id).map { item -> item.event },
        )

        // 删除定点 Session 只影响所选对象；行走 Session 的样本和标记仍可读。
        store.deleteSession(fixedCompleted.id)
        assertNull(store.loadSession(fixedCompleted.id))
        assertTrue(store.loadReferenceMarkers(fixedCompleted.id).isEmpty())
        assertNotNull(store.loadSession(walkingCompleted.id))
        assertEquals(timelineBeforeEdit, store.loadTimeline(walkingCompleted.id))
        assertEquals(3, store.loadReferenceMarkers(walkingCompleted.id).size)

        val walkingId = walkingCompleted.id
        database.close()
        database = openDatabase()
        val reopenedStore = RoomSessionStore(database.samplingSessionDao())
        assertEquals(walkingId, reopenedStore.loadSession(walkingId)?.id)
        assertEquals(3, reopenedStore.loadReferenceMarkers(walkingId).size)
        assertEquals(
            SessionEvaluationLabel.NO_TRIGGER,
            reopenedStore.loadSession(walkingId)?.evaluationLabel,
        )
        assertEquals(0, reopenedStore.loadTimeline(fixedCompleted.id).size)
    }

    private fun openDatabase(): ProximityLabDatabase = Room.databaseBuilder(
        context,
        ProximityLabDatabase::class.java,
        databaseName,
    ).addMigrations(
        ProximityLabDatabase.MIGRATION_1_2,
        ProximityLabDatabase.MIGRATION_2_3,
        ProximityLabDatabase.MIGRATION_3_4,
        ProximityLabDatabase.MIGRATION_4_5,
    ).build()

    private suspend fun advanceAndEmit(rssi: Int) {
        val sessionId = (recorder.activeSession.value ?: error("没有采集中 Session")).id
        val before = store.loadTimeline(sessionId).count { item ->
            item.event is SessionTimelineEvent.Observation
        }
        now += 1_000L
        sequence += 1L
        val emitted = source.emit(
            testAdvertisement(
                address = addressA,
                rssi = rssi,
                observedAtEpochMillis = now - 20L,
                receivedAtEpochMillis = now - 5L,
                observedAtNanos = sequence * 1_000_000_000L,
                receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                deliverySequence = sequence,
                payloadFingerprint = "management-frame-$sequence",
            ),
        )
        check(emitted) { "扫描事件缓冲拒绝投递" }
        awaitObservations(sessionId = sessionId, expected = before + 1)
    }

    private fun awaitBinding() {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (monitor.state.value.binding != null) {
                return
            }
            Thread.sleep(10L)
        }
        error("等待目标绑定超时")
    }

    private suspend fun awaitObservations(sessionId: String, expected: Int) {        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val count = store.loadTimeline(sessionId).count { item ->
                item.event is SessionTimelineEvent.Observation
            }
            if (count >= expected) {
                return
            }
            Thread.sleep(10L)
        }
        error("等待原始样本超时：session=$sessionId expected=$expected")
    }

    private suspend fun stopAndLoad(sessionId: String): SamplingSession {
        val result = recorder.stop() as SessionStopResult.Stopped
        assertEquals(sessionId, result.session.id)
        return requireNotNull(store.loadSession(sessionId))
    }
}
