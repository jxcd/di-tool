package com.byd.proximitylab.ui.sampling

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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import com.byd.proximitylab.session.SessionMarkerRecorder
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 定点采样全链路：真实 Room + 真实目标观测入口 + 真实 SessionRecorder/ViewModel。
 * 只替代 BLE 系统回调、时间输入和 Compose 页面承载。
 */
@RunWith(AndroidJUnit4::class)
class SamplingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private val harnesses = mutableListOf<Harness>()
    private var now = 1_800_000_000_000L

    private val addressA = "AA:BB:CC:DD:EE:01"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "sampling-screen-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun fixedPointSessionIsSavedAndReopenedFromListWithRawSamples() {
        val first = harness("session-1", persistBinding = true)
        var currentViewModel by mutableStateOf(first.viewModel)

        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SamplingScreen(viewModel = currentViewModel)
                }
            }
        }
        waitForBinding()

        composeRule.onNodeWithTag("sampling_name").performTextReplacement("车尾口袋采样")
        composeRule.onNodeWithTag("sampling_location_REAR").performScrollTo().performClick()
        composeRule.onNodeWithTag("sampling_placement_BACKPACK").performScrollTo().performClick()
        composeRule.onNodeWithTag("sampling_distance").performTextReplacement("3.5")
        composeRule.onNodeWithTag("sampling_start").performScrollTo().performClick()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            first.viewModel.uiState.value.activeSession != null
        }
        val sessionId = requireNotNull(first.viewModel.uiState.value.activeSession).id
        composeRule.onNodeWithTag("sampling_active").assertIsDisplayed()

        now += 1_000L
        first.source.emit(
            testAdvertisement(
                address = addressA,
                rssi = -82,
                observedAtEpochMillis = now - 30L,
                receivedAtEpochMillis = now - 5L,
                deliverySequence = 1L,
                payloadFingerprint = "connected-sample-1",
            ),
        )
        first.source.emit(
            testAdvertisement(
                address = addressA,
                rssi = -81,
                observedAtEpochMillis = now - 30L,
                receivedAtEpochMillis = now - 5L,
                deliverySequence = 2L,
                payloadFingerprint = "connected-sample-2",
            ),
        )
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            first.viewModel.uiState.value.sessions
                .firstOrNull { it.session.id == sessionId }
                ?.observationCount == 2
        }
        composeRule.onNodeWithTag("sampling_active_sample_count")
            .assertTextContains("2", substring = true)

        now += 2_000L
        composeRule.onNodeWithTag("sampling_stop").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadSession(sessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }
        val completed = runBlocking { first.sessionStore.loadSession(sessionId) }
        requireNotNull(completed)
        assertEquals(now, completed.endedAtEpochMillis)
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertEquals(FixedSampleLocation.REAR, completed.location)
        assertEquals(PhonePlacement.BACKPACK, completed.phonePlacement)
        assertEquals(3.5, completed.estimatedDistanceMeters ?: Double.NaN, 0.0)

        // 重新打开 App：新的数据库连接、新的观测入口和 Session 控制器，只共享持久化文件。
        val reopened = harness("session-1", persistBinding = false)
        assertEquals(0, runBlocking { reopened.recorder.recoverInterruptedSessions() })
        composeRule.runOnUiThread { currentViewModel = reopened.viewModel }
        composeRule.waitForIdle()
        waitForBinding()

        composeRule.onNodeWithTag("sampling_session_$sessionId")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForDetail()

        composeRule.onNodeWithTag("sampling_session_detail", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("sampling_detail_target", useUnmergedTree = true)
            .assertTextContains("Generic device", substring = true)
        composeRule.onNodeWithTag("sampling_detail_target", useUnmergedTree = true)
            .assertTextContains(addressA, substring = true)
        composeRule.onNodeWithTag("sampling_detail_conditions", useUnmergedTree = true)
            .assertTextContains("车尾", substring = true)
        composeRule.onNodeWithTag("sampling_detail_conditions", useUnmergedTree = true)
            .assertTextContains("背包", substring = true)
        composeRule.onNodeWithTag("sampling_detail_conditions", useUnmergedTree = true)
            .assertTextContains("3.5 米", substring = true)
        composeRule.onNodeWithTag("sampling_detail_duration", useUnmergedTree = true)
            .assertTextContains("3.0 秒", substring = true)
        composeRule.onNodeWithTag("sampling_detail_sample_count", useUnmergedTree = true)
            .assertTextContains("2", substring = true)
        composeRule.onNodeWithText("-82 dBm", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("-81 dBm", substring = true, useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag("sampling_detail_back", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("sampling_session_$sessionId").assertExists()
        composeRule.onNodeWithTag("sampling_session_detail").assertDoesNotExist()
    }

    @Test
    fun interruptedSessionAfterProcessDeathKeepsWrittenSamples() {
        val first = harness("interrupted-1", persistBinding = true)
        var currentViewModel by mutableStateOf(first.viewModel)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SamplingScreen(viewModel = currentViewModel)
                }
            }
        }
        waitForBinding()

        composeRule.onNodeWithTag("sampling_location_DRIVER_SEAT").performScrollTo().performClick()
        composeRule.onNodeWithTag("sampling_start").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            first.viewModel.uiState.value.activeSession != null
        }
        val sessionId = requireNotNull(first.viewModel.uiState.value.activeSession).id

        now += 1_000L
        first.source.emit(
            testAdvertisement(
                address = addressA,
                rssi = -69,
                observedAtEpochMillis = now - 20L,
                receivedAtEpochMillis = now - 5L,
                deliverySequence = 5L,
                payloadFingerprint = "interrupted-sample-1",
            ),
        )
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            first.viewModel.uiState.value.sessions
                .firstOrNull { it.session.id == sessionId }
                ?.observationCount == 1
        }
        val coverageEnd = now

        // 进程异常终止：没有执行 stop，Session 保持采集中；重新打开后由恢复流程标记中断。
        now += 9_000L
        val reopened = harness("interrupted-1", persistBinding = false)
        val marked = runBlocking { reopened.recorder.recoverInterruptedSessions() }
        assertEquals(1, marked)

        composeRule.runOnUiThread { currentViewModel = reopened.viewModel }
        composeRule.waitForIdle()
        waitForBinding()

        composeRule.onNodeWithTag("sampling_session_$sessionId")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForDetail()
        composeRule.onNodeWithTag("sampling_detail_status", useUnmergedTree = true)
            .assertTextContains("中断", substring = true)
        composeRule.onNodeWithTag("sampling_detail_status", useUnmergedTree = true)
            .assertTextContains("不计入结束时间", substring = true)
        composeRule.onNodeWithTag("sampling_detail_sample_count", useUnmergedTree = true)
            .assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("sampling_detail_duration", useUnmergedTree = true)
            .assertTextContains("1.0 秒", substring = true)
        composeRule.onNodeWithText("-69 dBm", substring = true, useUnmergedTree = true).assertExists()

        val stored = runBlocking { reopened.sessionStore.loadSession(sessionId) }
        requireNotNull(stored)
        assertEquals(SessionLifecycleStatus.INTERRUPTED, stored.status)
        assertNull(stored.endedAtEpochMillis)
        assertEquals(coverageEnd, stored.lastRecordedAtEpochMillis)
        assertEquals(now, stored.interruptedDetectedAtEpochMillis)
    }

    @Test
    fun walkingSessionRecordsMarkersOnTheSameTimelineAndEditingLabelKeepsSamples() {
        val first = harness("walking-session", persistBinding = true)
        val viewModel = first.viewModel
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SamplingScreen(viewModel = viewModel)
                }
            }
        }
        waitForBinding()

        composeRule.onNodeWithTag("sampling_type_WALKING").performScrollTo().performClick()
        composeRule.onNodeWithTag("sampling_start").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            viewModel.uiState.value.activeSession != null
        }
        val sessionId = requireNotNull(viewModel.uiState.value.activeSession).id
        val startedAt = runBlocking {
            requireNotNull(first.sessionStore.loadSession(sessionId)).startedAtEpochMillis
        }

        now += 1_000L
        first.source.emit(
            testAdvertisement(
                address = addressA,
                rssi = -80,
                observedAtEpochMillis = now - 20L,
                receivedAtEpochMillis = now - 5L,
                deliverySequence = 11L,
                payloadFingerprint = "walking-sample-1",
            ),
        )
        composeRule.waitUntil(TIMEOUT_MILLIS) { observationCount(first.sessionStore, sessionId) == 1 }

        now += 1_000L
        composeRule.onNodeWithTag("sampling_marker_APPROACH_START")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadReferenceMarkers(sessionId).size } == 1
        }
        val approachAt = now

        now += 1_000L
        first.source.emit(
            testAdvertisement(
                address = addressA,
                rssi = -60,
                observedAtEpochMillis = now - 20L,
                receivedAtEpochMillis = now - 5L,
                deliverySequence = 12L,
                payloadFingerprint = "walking-sample-2",
            ),
        )
        composeRule.waitUntil(TIMEOUT_MILLIS) { observationCount(first.sessionStore, sessionId) == 2 }

        now += 1_000L
        composeRule.onNodeWithTag("sampling_marker_EXPECTED_TRIGGER")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadReferenceMarkers(sessionId).size } == 2
        }
        val expectedAt = now

        now += 2_000L
        composeRule.onNodeWithTag("sampling_stop").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadSession(sessionId)?.status } ==
                SessionLifecycleStatus.COMPLETED
        }

        val markers = runBlocking { first.sessionStore.loadReferenceMarkers(sessionId) }
        assertEquals(listOf(approachAt, expectedAt), markers.map { it.atEpochMillis })
        assertEquals(
            listOf(2_000L, 4_000L),
            markers.map { it.atEpochMillis - startedAt },
        )
        assertEquals(
            SessionEvaluationLabel.EXPECTED_TRIGGER,
            runBlocking { first.sessionStore.loadSession(sessionId) }?.evaluationLabel,
        )

        composeRule.onNodeWithTag("sampling_session_$sessionId")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_session_detail", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sampling_detail_conditions", useUnmergedTree = true)
            .assertTextContains("行走采样", substring = true)
        composeRule.onNodeWithTag("sampling_detail_sample_count", useUnmergedTree = true)
            .assertTextContains("2", substring = true)
        composeRule.onNodeWithTag("sampling_detail_stats", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("sampling_detail_chart_summary", useUnmergedTree = true)
            .assertTextContains("标记 2", substring = true)
        composeRule.onNodeWithTag("sampling_detail_label", useUnmergedTree = true)
            .assertTextContains("期望触发", substring = true)
        composeRule.onNodeWithTag("sampling_detail_evaluation", useUnmergedTree = true)
            .assertTextContains("期望触发", substring = true)
        composeRule.onNodeWithTag(
            "sampling_detail_chart_marker_${markers.last().markerId}",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithTag(
            "sampling_marker_row_${markers.last().markerId}",
            useUnmergedTree = true,
        ).performScrollTo().assertTextContains("到达期望触发位置", substring = true)
        composeRule.onNodeWithTag(
            "sampling_marker_row_${markers.last().markerId}",
            useUnmergedTree = true,
        ).assertTextContains("会话 +4.0 秒", substring = true)

        val timelineBeforeLabelEdit = runBlocking { first.sessionStore.loadTimeline(sessionId) }
        composeRule.onNodeWithTag("sampling_label_NO_TRIGGER", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadSession(sessionId)?.evaluationLabel } ==
                SessionEvaluationLabel.NO_TRIGGER
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            viewModel.uiState.value.detail?.session?.evaluationLabel ==
                SessionEvaluationLabel.NO_TRIGGER
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sampling_detail_label", useUnmergedTree = true)
            .assertTextContains("无需触发", substring = true)
        assertEquals(timelineBeforeLabelEdit, runBlocking { first.sessionStore.loadTimeline(sessionId) })
        assertEquals(2, observationCount(first.sessionStore, sessionId))
        assertEquals(2, runBlocking { first.sessionStore.loadReferenceMarkers(sessionId).size })
    }

    @Test
    fun filtersStatsRenameDeleteArePersistedAndLeaveOtherSessionIntact() {
        val first = harness("management-session", persistBinding = true)
        val fixedStart = now - 20_000L
        seedSession(
            store = first.sessionStore,
            session = SamplingSession(
                id = "fixed-seed",
                targetId = "target-1",
                targetDisplayName = "Generic device",
                targetAddress = addressA,
                samplingType = SamplingType.FIXED_POINT,
                name = "车尾定点",
                location = FixedSampleLocation.REAR,
                estimatedDistanceMeters = null,
                phonePlacement = PhonePlacement.HANDHELD,
                status = SessionLifecycleStatus.RECORDING,
                startedAtEpochMillis = fixedStart,
                endedAtEpochMillis = null,
                lastRecordedAtEpochMillis = fixedStart,
                interruptedDetectedAtEpochMillis = null,
            ),
            samples = listOf(-80, -60),
            markerKinds = listOf(ReferenceMarkerKind.EXPECTED_TRIGGER to fixedStart + 2_000L),
        )
        seedSession(
            store = first.sessionStore,
            session = SamplingSession(
                id = "walk-seed",
                targetId = "target-1",
                targetDisplayName = "Generic device",
                targetAddress = addressA,
                samplingType = SamplingType.WALKING,
                name = "车头行走",
                location = FixedSampleLocation.FRONT,
                estimatedDistanceMeters = 2.0,
                phonePlacement = PhonePlacement.POCKET,
                status = SessionLifecycleStatus.RECORDING,
                startedAtEpochMillis = fixedStart - 10_000L,
                endedAtEpochMillis = null,
                lastRecordedAtEpochMillis = fixedStart - 10_000L,
                interruptedDetectedAtEpochMillis = null,
            ),
            samples = listOf(-75, -70),
            markerKinds = listOf(ReferenceMarkerKind.EXPECTED_TRIGGER to fixedStart - 9_000L),
        )
        val walkTimelineBefore = runBlocking { first.sessionStore.loadTimeline("walk-seed") }

        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SamplingScreen(viewModel = first.viewModel)
                }
            }
        }
        waitForBinding()

        composeRule.onNodeWithTag("sampling_session_fixed-seed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("sampling_session_walk-seed").performScrollTo().assertIsDisplayed()

        // 按类型筛选只影响列表展示，不删除存储中的另一条 Session。
        composeRule.onNodeWithTag("sampling_filter_type_WALKING").performScrollTo().performClick()
        composeRule.onNodeWithTag("sampling_session_fixed-seed").assertDoesNotExist()
        composeRule.onNodeWithTag("sampling_session_walk-seed").performScrollTo().assertIsDisplayed()
        assertNotNull(runBlocking { first.sessionStore.loadSession("fixed-seed") })
        composeRule.onNodeWithTag("sampling_filter_type_ALL").performScrollTo().performClick()
        composeRule.onNodeWithTag("sampling_session_fixed-seed").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("sampling_session_fixed-seed").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_session_detail", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sampling_detail_stats", useUnmergedTree = true)
            .assertTextContains("样本数 2", substring = true)
        composeRule.onNodeWithTag("sampling_detail_stats", useUnmergedTree = true)
            .assertTextContains("均值 -70.0 dBm", substring = true)
        composeRule.onNodeWithTag("sampling_detail_stats", useUnmergedTree = true)
            .assertTextContains("中位数 -70.0 dBm", substring = true)
        composeRule.onNodeWithTag("sampling_detail_stats", useUnmergedTree = true)
            .assertTextContains("标准差 10.0 dBm", substring = true)
        composeRule.onNodeWithTag("sampling_detail_conditions", useUnmergedTree = true)
            .assertTextContains("未填写", substring = true)
        composeRule.onNodeWithTag("sampling_detail_chart_summary", useUnmergedTree = true)
            .assertTextContains("标记 1", substring = true)
        composeRule.onNodeWithTag(
            "sampling_detail_chart_marker_fixed-seed-marker-0",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithTag(
            "sampling_marker_row_fixed-seed-marker-0",
            useUnmergedTree = true,
        ).performScrollTo().assertTextContains("到达期望触发位置", substring = true)
        composeRule.onNodeWithTag(
            "sampling_marker_row_fixed-seed-marker-0",
            useUnmergedTree = true,
        ).assertTextContains("会话 +2.0 秒", substring = true)

        val fixedTimelineBefore = runBlocking { first.sessionStore.loadTimeline("fixed-seed") }

        composeRule.onNodeWithTag("sampling_rename_field", useUnmergedTree = true)
            .performScrollTo()
            .performTextReplacement("改名定点")
        composeRule.onNodeWithTag("sampling_rename_save", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadSession("fixed-seed")?.name } == "改名定点"
        }
        assertEquals(fixedTimelineBefore, runBlocking { first.sessionStore.loadTimeline("fixed-seed") })

        composeRule.onNodeWithTag("sampling_label_NO_TRIGGER", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadSession("fixed-seed")?.evaluationLabel } ==
                SessionEvaluationLabel.NO_TRIGGER
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            first.viewModel.uiState.value.detail?.session?.evaluationLabel ==
                SessionEvaluationLabel.NO_TRIGGER
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sampling_detail_label", useUnmergedTree = true)
            .assertTextContains("无需触发", substring = true)
        assertEquals(fixedTimelineBefore, runBlocking { first.sessionStore.loadTimeline("fixed-seed") })

        composeRule.onNodeWithTag("sampling_delete_session", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("sampling_delete_dialog", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("sampling_delete_confirm", useUnmergedTree = true).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            runBlocking { first.sessionStore.loadSession("fixed-seed") } == null
        }

        composeRule.onNodeWithTag("sampling_session_detail", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("sampling_session_fixed-seed").assertDoesNotExist()
        composeRule.onNodeWithTag("sampling_session_walk-seed").performScrollTo().assertIsDisplayed()
        assertEquals(walkTimelineBefore, runBlocking { first.sessionStore.loadTimeline("walk-seed") })
        assertNotNull(runBlocking { first.sessionStore.loadSession("walk-seed") })
    }

    private fun waitForBinding() {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_binding").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDetail() {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_session_detail", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun observationCount(store: SessionStore, sessionId: String): Int = runBlocking {
        store.loadTimeline(sessionId).count { item ->
            item.event is SessionTimelineEvent.Observation
        }
    }

    private fun harness(idPrefix: String, persistBinding: Boolean): Harness {
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
        val recorder = SessionRecorder(
            store = sessionStore,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "$idPrefix-${UUID.randomUUID()}" },
        )
        val markerRecorder = SessionMarkerRecorder(
            store = sessionStore,
            clock = { now },
            idGenerator = { "marker-${UUID.randomUUID()}" },
        )
        val viewModel = SamplingViewModel(
            recorder = recorder,
            store = sessionStore,
            observationSource = monitor,
            markerRecorder = markerRecorder,
            simulationStore = RoomSimulationRunStore(database.simulationRunDao()),
            clock = { now },
        )
        return Harness(database, source, scope, sessionStore, recorder, viewModel)
            .also { harnesses += it }
    }

    private fun seedSession(
        store: SessionStore,
        session: SamplingSession,
        samples: List<Int>,
        markerKinds: List<Pair<ReferenceMarkerKind, Long>> = emptyList(),
    ) {
        runBlocking {
            store.createSession(session)
            samples.forEachIndexed { index, rssi ->
                val processedAt = session.startedAtEpochMillis + (index + 1) * 1_000L
                store.appendTimeline(
                    session.id,
                    SessionTimelineEvent.Observation(
                        observationId = "${session.id}-obs-$index",
                        targetId = session.targetId,
                        rawRssiDbm = rssi,
                        observedAtEpochMillis = processedAt - 20L,
                        receivedAtEpochMillis = processedAt - 5L,
                        scanFrameId = index.toLong(),
                        deliverySequence = index.toLong(),
                        address = session.targetAddress,
                        payloadFingerprint = "${session.id}-fp-$index",
                        processedAtEpochMillis = processedAt,
                    ),
                )
            }
            markerKinds.forEachIndexed { index, (kind, atEpochMillis) ->
                store.insertReferenceMarker(
                    SessionReferenceMarker(
                        markerId = "${session.id}-marker-$index",
                        sessionId = session.id,
                        kind = kind,
                        atEpochMillis = atEpochMillis,
                        createdAtEpochMillis = atEpochMillis,
                    ),
                )
            }
            store.completeSession(session.id, session.endedAtEpochMillis ?: now)
        }
    }

    private class Harness(
        val database: ProximityLabDatabase,
        val source: ControllableBleScanSource,
        val scope: CoroutineScope,
        val sessionStore: SessionStore,
        val recorder: SessionRecorder,
        val viewModel: SamplingViewModel,
    ) {
        fun close() {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000L
    }
}
