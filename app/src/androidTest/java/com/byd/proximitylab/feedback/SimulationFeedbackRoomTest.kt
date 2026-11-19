package com.byd.proximitylab.feedback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.live.SimulationStartResult
import com.byd.proximitylab.live.SimulationStopResult
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.SimulationRunStore
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.RecordingSimulationAlarm
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
 * 11 的真实 Room 反馈与中断：事件、反馈、通知结果分别持久化，重开数据库仍可查。
 *
 * 只替代 BLE 系统回调和 Android 通知边界；判定、事件生成、反馈写回和 Room 存储走生产实现。
 */
@RunWith(AndroidJUnit4::class)
class SimulationFeedbackRoomTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ProximityLabDatabase
    private lateinit var source: ControllableBleScanSource
    private lateinit var scope: CoroutineScope
    private lateinit var monitor: TargetMonitor
    private lateinit var sessionStore: SessionStore
    private lateinit var runStore: SimulationRunStore
    private lateinit var recorder: SessionRecorder
    private lateinit var controller: LiveMonitorController
    private lateinit var alarm: RecordingSimulationAlarm
    private lateinit var coordinator: SimulationFeedbackCoordinator

    @Volatile
    private var now = 1_800_000_000_000L
    private var sequence = 0L
    private var sessionSequence = 0L

    private val addressA = "AA:BB:CC:DD:EE:0A"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "simulation-feedback-${UUID.randomUUID()}.db"
        database = openDatabase()
        sessionStore = RoomSessionStore(database.samplingSessionDao())
        runStore = RoomSimulationRunStore(database.simulationRunDao())
        source = ControllableBleScanSource()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bindingStore = RoomTargetBindingStore(database.targetBindingDao())
        runBlocking {
            bindingStore.saveBinding(
                testBinding(targetId = TARGET_ID, address = addressA, displayName = "反馈测试目标"),
            )
        }
        monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        recorder = SessionRecorder(
            store = sessionStore,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "feedback-room-session-${++sessionSequence}" },
        )
        controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = sessionStore,
            simulationStore = runStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = 0L,
        )
        alarm = RecordingSimulationAlarm()
        coordinator = SimulationFeedbackCoordinator(
            store = runStore,
            alarm = alarm,
            scope = scope,
            clock = { now },
            feedbackWriter = { eventId, status, detail ->
                controller.updateEventFeedback(eventId, status, detail)
            },
        )
        waitUntil {
            monitor.state.value.binding?.targetId == TARGET_ID &&
                controller.state.value.binding?.targetId == TARGET_ID
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            scope.coroutineContext[Job]?.cancelAndJoin()
            if (database.isOpen) {
                database.close()
            }
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun unavailableNotificationsKeepEventInRoomAndReopenStillShowsFeedback() = runBlocking {
        alarm.capabilities = SimulationAlertCapabilities(
            notificationPermissionRequired = true,
            notificationPermissionGranted = false,
            notificationsEnabled = false,
            vibrationAvailable = true,
        )
        val session = startSimulation()
        coordinator.follow(session.id)

        feed(rssi = -85)
        feed(rssi = -85)
        feed(rssi = -60)
        feed(rssi = -60)

        val event = waitForEvent(session.id)
        assertEquals(SimulatedEventFeedbackStatus.UNAVAILABLE, event.feedbackStatus)
        assertTrue(event.feedbackDetail?.contains("POST_NOTIFICATIONS") == true)
        assertEquals(-60, event.rawRssiDbm)
        assertTrue(alarm.deliveries.isEmpty())
        assertEquals(1, alarm.attempts.size)
        assertEquals(4, runBlocking { sessionStore.loadTimeline(session.id) }
            .count { item -> item.event is SessionTimelineEvent.Observation })

        controller.stopSimulation().let { assertTrue(it is SimulationStopResult.Stopped) }
        coordinator.follow(null)
        closeResources()

        val reopenedDatabase = openDatabase()
        try {
            val reopenedRunStore = RoomSimulationRunStore(reopenedDatabase.simulationRunDao())
            val reopened = reopenedRunStore.loadEvents(session.id).single()
            assertEquals(event.eventId, reopened.eventId)
            assertEquals(SimulatedEventFeedbackStatus.UNAVAILABLE, reopened.feedbackStatus)
            assertEquals(event.reason, reopened.reason)
            assertEquals(event.rawRssiDbm, reopened.rawRssiDbm)
            assertEquals(event.atSessionMillis, reopened.atSessionMillis)

            // 重开 App 后没有运行中的 Session；即使显式跟随旧 Session，已有反馈也不补发。
            val reopenAlarm = RecordingSimulationAlarm()
            val reopenCoordinator = SimulationFeedbackCoordinator(
                store = reopenedRunStore,
                alarm = reopenAlarm,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                clock = { now },
            )
            reopenCoordinator.follow(session.id)
            delayForBackgroundWork()
            assertTrue(reopenAlarm.attempts.isEmpty())
            assertEquals(
                SimulatedEventFeedbackStatus.UNAVAILABLE,
                reopenedRunStore.loadEvents(session.id).single().feedbackStatus,
            )
            reopenCoordinator.follow(null)
        } finally {
            reopenedDatabase.close()
        }
        database = reopenedDatabase
    }

    @Test
    fun submittedNotificationIsRecordedSeparatelyAndNotResentAfterReopen() = runBlocking {
        val session = startSimulation()
        coordinator.follow(session.id)

        feed(rssi = -85)
        feed(rssi = -85)
        feed(rssi = -60)
        feed(rssi = -60)

        val event = waitForEvent(session.id)
        assertEquals(1, alarm.deliveries.size)
        assertEquals(event.eventId, alarm.deliveries.single().eventId)
        assertEquals(SimulatedEventFeedbackStatus.DELIVERED, event.feedbackStatus)
        assertTrue(event.feedbackDetail?.contains("锁屏") == true)
        // 事件事实与通知结果分开：原因、RSSI、版本不受反馈影响。
        assertEquals(com.byd.proximitylab.proximity.ProximityEventType.ENTERED_RANGE, event.type)
        assertEquals(
            com.byd.proximitylab.proximity.ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
            event.reason,
        )
        assertEquals("proximity-algorithms/1", event.algorithmVersion)
        assertEquals("confirmed-far-then-near/1", event.triggerRuleVersion)

        controller.stopSimulation()
        coordinator.follow(null)
        closeResources()

        val reopenedDatabase = openDatabase()
        try {
            val reopenedRunStore = RoomSimulationRunStore(reopenedDatabase.simulationRunDao())
            val persisted = reopenedRunStore.loadEvents(session.id).single()
            assertEquals(SimulatedEventFeedbackStatus.DELIVERED, persisted.feedbackStatus)
            assertEquals(event.eventId, persisted.eventId)
            assertNotNull(persisted.feedbackUpdatedAtEpochMillis)

            val reopenAlarm = RecordingSimulationAlarm()
            val reopenCoordinator = SimulationFeedbackCoordinator(
                store = reopenedRunStore,
                alarm = reopenAlarm,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                clock = { now },
            )
            reopenCoordinator.follow(session.id)
            delayForBackgroundWork()
            assertTrue(reopenAlarm.attempts.isEmpty())
            reopenCoordinator.follow(null)
        } finally {
            reopenedDatabase.close()
        }
        database = reopenedDatabase
    }

    @Test
    fun interruptedSimulationKeepsEventsTimelineAndSnapshotAfterReopen() = runBlocking {
        alarm.capabilities = SimulationAlertCapabilities(
            notificationPermissionRequired = true,
            notificationPermissionGranted = false,
            notificationsEnabled = false,
            vibrationAvailable = true,
        )
        val session = startSimulation()
        coordinator.follow(session.id)
        feed(rssi = -85)
        feed(rssi = -85)
        feed(rssi = -60)
        feed(rssi = -60)
        val event = waitForEvent(session.id)
        assertEquals(4, sessionStore.loadTimeline(session.id)
            .count { item -> item.event is SessionTimelineEvent.Observation })

        // 模拟进程终止：不调用 stop，直接取消协程并关闭数据库。
        closeResources()

        val reopenedDatabase = openDatabase()
        try {
            val reopenedStore = RoomSessionStore(reopenedDatabase.samplingSessionDao())
            val bindingStore = RoomTargetBindingStore(reopenedDatabase.targetBindingDao())
            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val reopenedMonitor = TargetMonitor(
                scanSource = ControllableBleScanSource(),
                bindingStore = bindingStore,
                scope = reopenedScope,
                clock = { now },
            )
            val reopenedRecorder = SessionRecorder(
                store = reopenedStore,
                observationSource = reopenedMonitor,
                scope = reopenedScope,
                clock = { now },
            )
            val recovered = reopenedRecorder.recoverInterruptedSessions(now + 30_000L)
            assertEquals(1, recovered)

            val stored = requireNotNull(reopenedStore.loadSession(session.id))
            assertEquals(
                com.byd.proximitylab.session.SessionLifecycleStatus.INTERRUPTED,
                stored.status,
            )
            assertNull(stored.endedAtEpochMillis)
            assertNotNull(stored.interruptedDetectedAtEpochMillis)
            assertEquals(4, reopenedStore.loadTimeline(session.id)
                .count { item -> item.event is SessionTimelineEvent.Observation })
            assertNotNull(reopenedStore.loadConfigSnapshot(session.id))

            val reopenedRunStore = RoomSimulationRunStore(reopenedDatabase.simulationRunDao())
            val persistedEvent = reopenedRunStore.loadEvents(session.id).single()
            assertEquals(event.eventId, persistedEvent.eventId)
            assertEquals(SimulatedEventFeedbackStatus.UNAVAILABLE, persistedEvent.feedbackStatus)
            assertEquals(-60, persistedEvent.rawRssiDbm)
            reopenedScope.coroutineContext[Job]?.cancelAndJoin()
        } finally {
            reopenedDatabase.close()
        }
        database = reopenedDatabase
    }

    private suspend fun startSimulation(): SamplingSession {
        val result = controller.startSimulation()
        return (result as SimulationStartResult.Started).session
    }

    private suspend fun feed(rssi: Int) {
        val sessionId = (recorder.activeSession.value ?: error("没有采集中 Session")).id
        val before = sessionStore.loadTimeline(sessionId)
            .count { item -> item.event is SessionTimelineEvent.Observation }
        now += 1_000L
        sequence += 1
        val emitted = source.emit(
            testAdvertisement(
                address = addressA,
                rssi = rssi,
                observedAtEpochMillis = now - 20L,
                receivedAtEpochMillis = now - 5L,
                observedAtNanos = sequence * 1_000_000_000L,
                receivedAtNanos = sequence * 1_000_000_000L + 1_000_000L,
                deliverySequence = sequence,
                payloadFingerprint = "feedback-room-frame-$sequence",
            ),
        )
        check(emitted) { "扫描事件缓冲拒绝投递" }
        waitUntil {
            runBlocking { sessionStore.loadTimeline(sessionId) }
                .count { item -> item.event is SessionTimelineEvent.Observation } > before
        }
    }

    private suspend fun waitForEvent(
        sessionId: String,
    ): com.byd.proximitylab.simulation.SessionRunEvent {
        var event: com.byd.proximitylab.simulation.SessionRunEvent? = null
        waitUntil {
            event = runBlocking { runStore.loadEvents(sessionId) }.singleOrNull()
            event != null &&
                event?.feedbackStatus != SimulatedEventFeedbackStatus.NOT_ATTEMPTED
        }
        return requireNotNull(event)
    }

    private fun closeResources() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
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
        ProximityLabDatabase.MIGRATION_5_6,
    ).build()

    private fun waitUntil(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(20L)
        }
        assertTrue("等待条件超时（${timeoutMillis}ms）", condition())
    }

    private fun delayForBackgroundWork() {
        Thread.sleep(300L)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val TARGET_ID = "feedback-room-target"
    }
}
