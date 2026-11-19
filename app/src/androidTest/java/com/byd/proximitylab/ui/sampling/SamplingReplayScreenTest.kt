package com.byd.proximitylab.ui.sampling

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.replay.SessionReplayService
import com.byd.proximitylab.replay.data.RoomSessionReplayStore
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionMarkerRecorder
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.testing.ControllableBleScanSource
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 09 UI 闭环：从 Session 详情使用当次快照发起回放并查看滤波曲线、状态和事件；
 * 没有当次快照的采样 Session 使用当前生效配置并标为新算结果。
 *
 * 使用真实 Room、真实 SessionReplayService 和 Compose 页面，只替代 BLE 回调和时间输入。
 */
@RunWith(AndroidJUnit4::class)
class SamplingReplayScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private val harnesses = mutableListOf<Harness>()
    private val addressA = "AA:BB:CC:DD:EE:01"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "sampling-replay-screen-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun replayFromDetailUsesOriginalSnapshotAndShowsFilteredCurveStatesAndEvents() {
        val harness = harness()
        val session = seedSession(
            harness = harness,
            id = "ui-replay-original",
            samplingType = SamplingType.SIMULATION,
            snapshot = snapshot("回放界面配置"),
        )
        setContent(harness)
        openDetail(session.id)

        composeRule.onNodeWithTag("sampling_detail_replay_run")
            .performScrollTo()
            .assertTextContains("用当次快照回放")
            .performClick()
        waitForReplay(harness, session.id)

        composeRule.onNodeWithTag("sampling_detail_replay_summary", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("重现当次结果", substring = true)
            .assertTextContains("配置 回放界面配置", substring = true)
            .assertTextContains("事件 1 条", substring = true)
            .assertTextContains("版本一致", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_coverage", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("正常结束", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_chart_summary", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("回放滤波 RSSI", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_states", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("0.0 秒 UNKNOWN/AWAITING_OBSERVATION", substring = true)
            .assertTextContains("2.0 秒 FAR/CONFIRMED_FAR", substring = true)
            .assertTextContains("4.0 秒 NEAR/CONFIRMED_NEAR", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_events", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("4.0 秒 ENTERED_RANGE/CONFIRMED_NEAR_AFTER_CONFIRMED_FAR", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_history", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(
            "sampling_replay_history_${requireNotNull(harness.viewModel.uiState.value.detail?.replay).replayId}",
            useUnmergedTree = true,
        ).performScrollTo().assertTextContains("重现当次结果", substring = true)
    }

    @Test
    fun samplingSessionWithoutSnapshotUsesCurrentSelectionAndMarksNewCalculation() {
        val harness = harness()
        val session = seedSession(
            harness = harness,
            id = "ui-replay-current",
            samplingType = SamplingType.FIXED_POINT,
            snapshot = null,
        )
        val selected = runBlocking {
            harness.configStore.createConfig(
                name = "当前选中配置",
                config = ProximityConfig(
                    algorithm = ProximityAlgorithm.BASELINE,
                    enterThresholdDbm = -50,
                    exitThresholdDbm = -80,
                    confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
                    sampleValidityMillis = 5_000L,
                    emaAlpha = 0.5,
                ),
            )
        }
        runBlocking { harness.configStore.selectConfig(selected.configId) }
        setContent(harness)
        openDetail(session.id)

        composeRule.onNodeWithTag("sampling_detail_replay_run")
            .performScrollTo()
            .assertTextContains("用当前配置分析")
            .performClick()
        waitForReplay(harness, session.id)

        composeRule.onNodeWithTag("sampling_detail_replay_summary", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("新算结果", substring = true)
            .assertTextContains("来源 当前生效配置", substring = true)
            .assertTextContains("配置 当前选中配置", substring = true)
            .assertTextContains("事件 0 条", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_events", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("回放事件：0 条", substring = true)
            .assertTextContains("不会触发通知或震动", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_message", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("不写入实时历史", substring = true)
        assertNotNull(harness.viewModel.uiState.value.detail?.replay)
    }

    private fun setContent(harness: Harness) {
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SamplingScreen(viewModel = harness.viewModel)
                }
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_sessions_empty")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openDetail(sessionId: String) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_session_$sessionId", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sampling_session_$sessionId", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_session_detail", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sampling_session_detail", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun waitForReplay(harness: Harness, sessionId: String) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            harness.viewModel.uiState.value.detail?.session?.id == sessionId &&
                harness.viewModel.uiState.value.detail?.replay != null
        }
        assertEquals(
            1,
            harness.viewModel.uiState.value.detail?.replayHistory?.size,
        )
    }

    private fun harness(): Harness {
        val database = Room.databaseBuilder(
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
        val bindingStore = RoomTargetBindingStore(database.targetBindingDao())
        runBlocking {
            bindingStore.saveBinding(
                testBinding(
                    targetId = "target-1",
                    address = addressA,
                    displayName = "目标车",
                ),
            )
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
        val simulationStore = RoomSimulationRunStore(database.simulationRunDao())
        val replayStore = RoomSessionReplayStore(database.sessionReplayDao())
        val configStore: AlgorithmConfigStore = RoomAlgorithmConfigStore(database.algorithmConfigDao())
        val recorder = SessionRecorder(
            store = sessionStore,
            observationSource = monitor,
            scope = scope,
            clock = { now },
            idGenerator = { "ui-replay-session-${UUID.randomUUID()}" },
        )
        val markerRecorder = SessionMarkerRecorder(
            store = sessionStore,
            clock = { now },
            idGenerator = { "ui-replay-marker-${UUID.randomUUID()}" },
        )
        val replayService = SessionReplayService(
            sessionStore = sessionStore,
            replayStore = replayStore,
            configStore = configStore,
            clock = { now },
            idGenerator = { "ui-replay-${UUID.randomUUID()}" },
        )
        val viewModel = SamplingViewModel(
            recorder = recorder,
            store = sessionStore,
            observationSource = monitor,
            markerRecorder = markerRecorder,
            simulationStore = simulationStore,
            replayService = replayService,
            clock = { now },
        )
        return Harness(
            database = database,
            scope = scope,
            sessionStore = sessionStore,
            configStore = configStore,
            viewModel = viewModel,
        ).also { harnesses += it }
    }

    private fun seedSession(
        harness: Harness,
        id: String,
        samplingType: SamplingType,
        snapshot: SessionConfigSnapshot?,
    ): SamplingSession {
        val startedAt = now
        val session = SamplingSession(
            id = id,
            targetId = "target-1",
            targetDisplayName = "目标车",
            targetAddress = addressA,
            samplingType = samplingType,
            name = id,
            location = FixedSampleLocation.OTHER,
            estimatedDistanceMeters = null,
            phonePlacement = PhonePlacement.HANDHELD,
            status = SessionLifecycleStatus.COMPLETED,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = startedAt + 4_000L,
            lastRecordedAtEpochMillis = startedAt + 4_000L,
            interruptedDetectedAtEpochMillis = null,
        )
        runBlocking {
            if (snapshot != null) {
                harness.sessionStore.createSimulationSession(session, snapshot)
            } else {
                harness.sessionStore.createSession(session)
            }
            listOf(
                Triple(1L, 1_000L, -85),
                Triple(2L, 2_000L, -85),
                Triple(3L, 3_000L, -60),
                Triple(4L, 4_000L, -60),
            ).forEach { (sequence, offset, rssi) ->
                harness.sessionStore.appendTimeline(
                    id,
                    SessionTimelineEvent.Observation(
                        observationId = "ui-replay-observation-$sequence",
                        targetId = "target-1",
                        rawRssiDbm = rssi,
                        observedAtEpochMillis = startedAt + offset,
                        receivedAtEpochMillis = startedAt + offset,
                        scanFrameId = 500L + sequence,
                        deliverySequence = sequence,
                        address = addressA,
                        payloadFingerprint = "ui-replay-fingerprint-$sequence",
                        processedAtEpochMillis = startedAt + offset,
                    ),
                )
            }
        }
        return session
    }

    private fun snapshot(name: String): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "ui-replay-snapshot-$name",
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
    )

    private inner class Harness(
        private val database: ProximityLabDatabase,
        private val scope: CoroutineScope,
        val sessionStore: SessionStore,
        val configStore: AlgorithmConfigStore,
        val viewModel: SamplingViewModel,
    ) {
        fun close() {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            database.close()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        var now = 1_800_000_000_000L
    }
}
