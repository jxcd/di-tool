package com.byd.proximitylab.ui.monitor

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.session.durationMillis
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.testing.testBinding
import com.byd.proximitylab.ui.theme.ProximityLabTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 05 实时监控页全链路：真实 Room、真实目标观测入口、真实 SessionRecorder/判定和 Compose 页面。
 *
 * 只替代 BLE 系统回调、时间输入和页面承载。
 */
@RunWith(AndroidJUnit4::class)
class MonitorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private val harnesses = mutableListOf<Harness>()

    @Volatile
    private var now = 1_800_000_000_000L

    private var sequence = 0L
    private val addressA = "AA:BB:CC:DD:EE:01"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "monitor-screen-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun normalMonitorFarIsNotCarriedAndOnlyNearSimulationKeepsZeroEvents() {
        val first = harness(persistBinding = true)
        var currentViewModel by mutableStateOf(first.viewModel)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = currentViewModel)
                }
            }
        }
        waitForBinding()

        first.feed(rssi = -85)
        first.feed(rssi = -85)
        waitUntil { first.controller.state.value.monitor.state == ProximityState.FAR }
        composeRule.onNodeWithTag("monitor_state").assertTextContains("FAR", substring = true)

        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        waitUntil { first.controller.state.value.simulationRunning }
        val sessionId = requireNotNull(first.controller.state.value.test).session.id
        assertEquals(
            ProximityState.UNKNOWN,
            requireNotNull(first.controller.state.value.test).decision.state,
        )

        first.feed(rssi = -60)
        first.feed(rssi = -60)
        waitUntil {
            first.controller.state.value.test?.decision?.state == ProximityState.NEAR
        }
        composeRule.onNodeWithTag("monitor_state").assertTextContains("NEAR", substring = true)
        composeRule.onNodeWithTag("monitor_event_count").assertTextContains("0", substring = true)
        composeRule.onNodeWithTag("monitor_latest_event")
            .assertTextContains("尚无", substring = true)
        assertTrue(runBlocking { first.runStore.loadEvents(sessionId) }.isEmpty())

        composeRule.onNodeWithTag("monitor_stop_test").performScrollTo().performClick()
        waitUntil {
            first.recorder.activeSession.value == null &&
                runBlocking { first.sessionStore.loadSession(sessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }
        assertEquals(
            BuiltInSessionConfigs.DEFAULT,
            runBlocking { first.sessionStore.loadConfigSnapshot(sessionId) },
        )

        first.close()
        harnesses.remove(first)
        val reopened = harness(persistBinding = false)
        assertEquals(0, runBlocking { reopened.recorder.recoverInterruptedSessions() })
        assertEquals(
            SessionLifecycleStatus.COMPLETED,
            runBlocking { reopened.sessionStore.loadSession(sessionId)?.status },
        )
        assertTrue(runBlocking { reopened.runStore.loadEvents(sessionId) }.isEmpty())
        assertNull(reopened.recorder.activeSession.value)
    }

    @Test
    fun confirmedFarThenNearPersistsOneEventAndReopeningUiDoesNotReemit() {
        val first = harness(persistBinding = true)
        var currentViewModel by mutableStateOf(first.viewModel)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = currentViewModel)
                }
            }
        }
        waitForBinding()

        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        waitUntil { first.controller.state.value.simulationRunning }
        val sessionId = requireNotNull(first.controller.state.value.test).session.id

        first.feed(rssi = -85)
        first.feed(rssi = -85)
        first.feed(rssi = -60)
        first.feed(rssi = -60)
        waitUntil {
            first.controller.state.value.test?.events?.size == 1
        }
        composeRule.onNodeWithTag("monitor_event_count").assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("monitor_latest_event")
            .assertTextContains("ENTERED_RANGE", substring = true)
        composeRule.onNodeWithTag("monitor_latest_event")
            .assertTextContains("CONFIRMED_NEAR_AFTER_CONFIRMED_FAR", substring = true)
        assertEquals(1, runBlocking { first.runStore.loadEvents(sessionId) }.size)
        assertTrue(
            runBlocking { first.runStore.loadStates(sessionId) }.any { state ->
                state.state == ProximityState.NEAR &&
                    state.reason == ProximityStateReason.CONFIRMED_NEAR
            },
        )

        // 页面重新订阅/ViewModel 重建不结束会话，也不补发事件。
        composeRule.runOnUiThread {
            currentViewModel = MonitorViewModel(first.controller)
        }
        composeRule.waitForIdle()
        waitUntil { first.controller.state.value.test?.events?.size == 1 }
        assertEquals(1, runBlocking { first.runStore.loadEvents(sessionId) }.size)
        assertTrue(first.controller.state.value.simulationRunning)

        composeRule.onNodeWithTag("monitor_stop_test").performScrollTo().performClick()
        waitUntil {
            first.recorder.activeSession.value == null &&
                runBlocking { first.sessionStore.loadSession(sessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }

        first.close()
        harnesses.remove(first)
        val reopened = harness(persistBinding = false)
        val events = runBlocking { reopened.runStore.loadEvents(sessionId) }
        assertEquals(1, events.size)
        assertEquals(-60, events.single().rawRssiDbm)
        assertEquals(
            BuiltInSessionConfigs.DEFAULT,
            runBlocking { reopened.sessionStore.loadConfigSnapshot(sessionId) },
        )
        val timeline = runBlocking { reopened.sessionStore.loadTimeline(sessionId) }
        val observations = timeline.mapNotNull { item ->
            item.event as? SessionTimelineEvent.Observation
        }
        assertEquals(4, observations.size)
        assertTrue(observations.any { it.scanFrameId == events.single().frameId })
        assertTrue(timeline.any { item ->
            (item.event as? SessionTimelineEvent.ScanStateChanged)?.available == true
        })
        assertTrue(timeline.any { item ->
            (item.event as? SessionTimelineEvent.ScanStateChanged)?.available == false
        })
        val completed = requireNotNull(runBlocking { reopened.sessionStore.loadSession(sessionId) })
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(4_000L, completed.durationMillis)
        val states = runBlocking { reopened.runStore.loadStates(sessionId) }
        assertEquals(
            ProximityStateReason.CONFIRMED_NEAR,
            states.last { it.state == ProximityState.NEAR }.reason,
        )
        assertEquals(ProximityState.UNKNOWN, states.last().state)
        assertEquals(ProximityStateReason.SCAN_UNAVAILABLE, states.last().reason)
        assertNull(reopened.recorder.activeSession.value)
    }

    @Test
    fun interruptedSimulationKeepsSnapshotTimelineAndEvents() {
        val first = harness(persistBinding = true)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = first.viewModel)
                }
            }
        }
        waitForBinding()
        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        waitUntil { first.controller.state.value.simulationRunning }
        val sessionId = requireNotNull(first.controller.state.value.test).session.id

        first.feed(rssi = -85)
        first.feed(rssi = -85)
        waitUntil {
            runBlocking { first.sessionStore.loadTimeline(sessionId) }
                .count { it.event is SessionTimelineEvent.Observation } == 2
        }
        assertTrue(first.controller.state.value.simulationRunning)

        // 进程终止：不执行 stop，直接关闭资源后重开。
        first.close()
        harnesses.remove(first)

        val reopened = harness(persistBinding = false)
        assertEquals(1, runBlocking { reopened.recorder.recoverInterruptedSessions() })
        val stored = requireNotNull(runBlocking { reopened.sessionStore.loadSession(sessionId) })
        assertEquals(SessionLifecycleStatus.INTERRUPTED, stored.status)
        assertNull(stored.endedAtEpochMillis)
        assertEquals(2, runBlocking {
            reopened.sessionStore.loadTimeline(sessionId)
                .count { it.event is SessionTimelineEvent.Observation }
        })
        assertEquals(
            BuiltInSessionConfigs.DEFAULT,
            runBlocking { reopened.sessionStore.loadConfigSnapshot(sessionId) },
        )
        assertTrue(runBlocking { reopened.runStore.loadEvents(sessionId) }.isEmpty())
    }

    @Test
    fun injectedClockExpiresTestWithoutNewSamplesAndRecoveryEmitsNothing() {
        val first = harness(persistBinding = true)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen(viewModel = first.viewModel)
                }
            }
        }
        waitForBinding()
        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        waitUntil { first.controller.state.value.simulationRunning }
        val sessionId = requireNotNull(first.controller.state.value.test).session.id

        first.feed(rssi = -85)
        first.feed(rssi = -85)
        waitUntil {
            first.controller.state.value.test?.decision?.state == ProximityState.FAR
        }

        now += 6_000L
        runBlocking { first.controller.refreshNow(now) }
        waitUntil {
            first.controller.state.value.test?.decision?.reason ==
                ProximityStateReason.SAMPLES_EXPIRED
        }
        composeRule.onNodeWithTag("monitor_test_state")
            .assertTextContains("UNKNOWN", substring = true)
        assertTrue(
            runBlocking { first.runStore.loadStates(sessionId) }.any { state ->
                state.state == ProximityState.UNKNOWN &&
                    state.reason == ProximityStateReason.SAMPLES_EXPIRED
            },
        )

        first.feed(rssi = -60)
        first.feed(rssi = -60)
        waitUntil {
            first.controller.state.value.test?.decision?.state == ProximityState.NEAR
        }
        assertTrue(runBlocking { first.runStore.loadEvents(sessionId) }.isEmpty())
    }

    private fun waitForBinding() {
        waitUntil {
            composeRule.onAllNodesWithTag("monitor_binding").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun harness(persistBinding: Boolean): Harness {
        val database = Room.databaseBuilder(
            context,
            ProximityLabDatabase::class.java,
            databaseName,
        ).addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
        ).build()
        val bindingStore = RoomTargetBindingStore(database.targetBindingDao())
        if (persistBinding) {
            runBlocking {
                bindingStore.saveBinding(
                    testBinding(
                        targetId = "target-1",
                        address = addressA,
                        displayName = "Generic device",
                    ),
                )
            }
        }
        val source = ControllableBleScanSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val monitor = TargetMonitor(
            scanSource = source,
            bindingStore = bindingStore,
            scope = scope,
            clock = { now },
        )
        val sessionStore = RoomSessionStore(database.samplingSessionDao())
        val runStore = RoomSimulationRunStore(database.simulationRunDao())
        val recorder = SessionRecorder(
            store = sessionStore,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "monitor-session-${UUID.randomUUID()}" },
        )
        val controller = LiveMonitorController(
            observationSource = monitor,
            recorder = recorder,
            sessionStore = sessionStore,
            simulationStore = runStore,
            scope = scope,
            clock = { now },
            tickerIntervalMillis = 0L,
        )
        val viewModel = MonitorViewModel(controller)
        return Harness(
            database = database,
            source = source,
            scope = scope,
            sessionStore = sessionStore,
            runStore = runStore,
            recorder = recorder,
            controller = controller,
            viewModel = viewModel,
        ).also { harnesses += it }
    }

    private fun waitUntil(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis, condition)
    }

    private inner class Harness(
        private val database: ProximityLabDatabase,
        private val source: ControllableBleScanSource,
        private val scope: CoroutineScope,
        val sessionStore: SessionStore,
        val runStore: RoomSimulationRunStore,
        val recorder: SessionRecorder,
        val controller: LiveMonitorController,
        val viewModel: MonitorViewModel,
    ) {
        fun feed(rssi: Int, advanceMillis: Long = 1_000L) {
            now += advanceMillis
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
                    payloadFingerprint = "monitor-frame-$sequence",
                ),
            )
            assertTrue("扫描事件缓冲拒绝投递", emitted)
        }

        fun close() {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
