package com.byd.proximitylab.ui.lab

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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.AlgorithmConfig
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore
import com.byd.proximitylab.comparison.ComparisonTarget
import com.byd.proximitylab.comparison.SessionComparisonService
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEngine
import com.byd.proximitylab.replay.SessionReplayService
import com.byd.proximitylab.replay.data.RoomSessionReplayStore
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.ui.theme.ProximityLabTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 10 的真实 App 入口对比评分：实验室页面选择同一 Session 与多套配置，走真实 Room、真实 09
 * 回放服务和 Compose 页面，验证同轴曲线、事件原因、评分差异及各类界面状态。
 */
@RunWith(AndroidJUnit4::class)
class AlgorithmComparisonScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var harness: Harness? = null
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "algorithm-comparison-screen-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harness?.close()
        harness = null
        context.deleteDatabase(databaseName)
    }

    @Test
    fun twoConfigsShareOneTimelineAndShowDifferentScoresAtTheWindowEndBoundary() {
        val h = harness()
        val session = seedSession(
            h = h,
            id = "ui-comparison-marked",
            eventAtMillis = 12_000L,
            coverageEndMillis = 20_000L,
            snapshot = snapshot("当次配置"),
        )
        addMarker(h, session, 10_000L)
        val triggering = runBlocking { h.createConfig("触发配置", enterThresholdDbm = -65) }
        val notTriggering = runBlocking { h.createConfig("不触发配置", enterThresholdDbm = -50) }
        runBlocking { h.configStore.selectConfig(triggering.configId) }
        setContent(h)

        selectSession(h, session.id)
        setTargets(
            h,
            ComparisonTarget.Config(triggering.configId),
            ComparisonTarget.Config(notTriggering.configId),
        )
        runAndWait(h)

        composeRule.onNodeWithTag("lab_compare_context", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("当次配置", substring = true)
            .assertTextContains("期望触发 1 个", substring = true)
        composeRule.onNodeWithTag("lab_compare_original", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("lab_compare_window", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("单位：秒", substring = true)
            .assertTextContains("窗起点 +9.0 秒", substring = true)
            .assertTextContains("窗终点 +12.0 秒", substring = true)
            .assertTextContains("相对标记 -1.0 秒 / +2.0 秒", substring = true)

        composeRule.onNodeWithTag("lab_compare_chart_legend_raw", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("原始 RSSI", substring = true)
        composeRule.onNodeWithTag("lab_compare_chart_legend_filtered-0", useUnmergedTree = true)
            .assertTextContains("触发配置", substring = true)
        composeRule.onNodeWithTag("lab_compare_chart_legend_filtered-1", useUnmergedTree = true)
            .assertTextContains("不触发配置", substring = true)
        composeRule.onNodeWithTag("lab_compare_chart_marker_event-0-0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("按时", substring = true)
            .assertTextContains("CONFIRMED_NEAR_AFTER_CONFIRMED_FAR", substring = true)

        composeRule.onNodeWithTag("lab_compare_outcome_0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("按时", substring = true)
        composeRule.onNodeWithTag("lab_compare_outcome_1", useUnmergedTree = true)
            .assertTextContains("漏触发", substring = true)
        composeRule.onNodeWithTag("lab_compare_score_0", useUnmergedTree = true)
            .assertTextContains("首条事件 +12.0 秒", substring = true)
            .assertTextContains("相对标记 +2.0 秒", substring = true)
        composeRule.onNodeWithTag("lab_compare_events_0", useUnmergedTree = true)
            .assertTextContains("CONFIRMED_NEAR_AFTER_CONFIRMED_FAR", substring = true)
            .assertTextContains("+2.0 秒", substring = true)
        composeRule.onNodeWithTag("lab_compare_result_config_0", useUnmergedTree = true)
            .assertTextContains("触发配置", substring = true)
            .assertTextContains("算法 ${ProximityEngine.ALGORITHM_VERSION}", substring = true)
            .assertTextContains("触发规则 ${ProximityEngine.TRIGGER_RULE_VERSION}", substring = true)
            .assertTextContains("当前配置", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("触发配置", substring = true)
            .assertTextContains("纳入评分 1", substring = true)
            .assertTextContains("按时 1", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_1", useUnmergedTree = true)
            .assertTextContains("不触发配置", substring = true)
            .assertTextContains("漏触发 1", substring = true)

        // UI 输入的时间窗直接参与评分：把窗收窄到标记本身，12 秒事件从「按时」变成「迟到」。
        composeRule.onNodeWithTag("lab_compare_early_window", useUnmergedTree = true)
            .performScrollTo()
            .performTextClearance()
        composeRule.onNodeWithTag("lab_compare_early_window", useUnmergedTree = true)
            .performTextInput("0")
        composeRule.onNodeWithTag("lab_compare_late_window", useUnmergedTree = true)
            .performScrollTo()
            .performTextClearance()
        composeRule.onNodeWithTag("lab_compare_late_window", useUnmergedTree = true)
            .performTextInput("0")
        runAndWait(h)
        composeRule.onNodeWithTag("lab_compare_window", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("窗起点 +10.0 秒", substring = true)
            .assertTextContains("窗终点 +10.0 秒", substring = true)
        composeRule.onNodeWithTag("lab_compare_outcome_0", useUnmergedTree = true)
            .assertTextContains("迟到", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_0", useUnmergedTree = true)
            .assertTextContains("迟到 1", substring = true)
    }

    @Test
    fun unmarkedSessionShowsSkippedStateAndIsExcludedFromSummary() {
        val h = harness()
        val session = seedSession(
            h = h,
            id = "ui-comparison-unmarked",
            eventAtMillis = 10_000L,
            coverageEndMillis = 20_000L,
        )
        val triggering = runBlocking { h.createConfig("触发配置", enterThresholdDbm = -65) }
        setContent(h)

        selectSession(h, session.id)
        setTargets(h, ComparisonTarget.Config(triggering.configId))
        runAndWait(h)

        composeRule.onNodeWithTag("lab_compare_outcome_0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("跳过评分", substring = true)
        composeRule.onNodeWithTag("lab_compare_score_0", useUnmergedTree = true)
            .assertTextContains("未标记整次试验", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_0", useUnmergedTree = true)
            .assertTextContains("纳入评分 0", substring = true)
            .assertTextContains("剔除 1", substring = true)
            .assertTextContains("未标记", substring = true)
    }

    @Test
    fun negativeAndInterruptedSessionsShowFalseTriggerAndInsufficientObservation() {
        val h = harness()
        val triggering = runBlocking { h.createConfig("触发配置", enterThresholdDbm = -65) }
        val negative = seedSession(
            h = h,
            id = "ui-comparison-negative",
            eventAtMillis = 5_000L,
            coverageEndMillis = 20_000L,
            label = SessionEvaluationLabel.NO_TRIGGER,
        )
        val interrupted = seedSession(
            h = h,
            id = "ui-comparison-interrupted",
            eventAtMillis = 10_000L,
            coverageEndMillis = 11_000L,
            status = SessionLifecycleStatus.INTERRUPTED,
        )
        addMarker(h, interrupted, 10_000L)
        setContent(h)

        selectSession(h, negative.id)
        setTargets(h, ComparisonTarget.Config(triggering.configId))
        runAndWait(h)
        composeRule.onNodeWithTag("lab_compare_outcome_0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("误触发", substring = true)
        composeRule.onNodeWithTag("lab_compare_score_0", useUnmergedTree = true)
            .assertTextContains("误触发事件 1 条", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_0", useUnmergedTree = true)
            .assertTextContains("误触发 1", substring = true)

        selectSession(h, interrupted.id)
        setTargets(h, ComparisonTarget.Config(triggering.configId))
        runAndWait(h)
        composeRule.onNodeWithTag("lab_compare_outcome_0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("观测不足", substring = true)
        composeRule.onNodeWithTag("lab_compare_score_0", useUnmergedTree = true)
            .assertTextContains("早于评分窗终点", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_0", useUnmergedTree = true)
            .assertTextContains("纳入评分 0", substring = true)
            .assertTextContains("观测不足", substring = true)
    }

    @Test
    fun conflictingMarkersAndOldVersionReplayHaveDistinctUiStates() {
        val h = harness()
        val triggering = runBlocking { h.createConfig("触发配置", enterThresholdDbm = -65) }
        val conflicting = seedSession(
            h = h,
            id = "ui-comparison-conflict",
            eventAtMillis = 10_000L,
            coverageEndMillis = 20_000L,
            label = SessionEvaluationLabel.EXPECTED_TRIGGER,
        )
        addMarker(h, conflicting, 10_000L)
        addMarker(h, conflicting, 11_000L)

        val oldSnapshot = snapshot("旧版本配置").copy(
            algorithmVersion = "proximity-algorithms/0",
            triggerRuleVersion = "confirmed-far-then-near/0",
        )
        val oldVersion = seedSession(
            h = h,
            id = "ui-comparison-old-version",
            eventAtMillis = 10_000L,
            coverageEndMillis = 20_000L,
            snapshot = oldSnapshot,
        )
        addMarker(h, oldVersion, 10_000L)
        setContent(h)

        selectSession(h, conflicting.id)
        setTargets(h, ComparisonTarget.Config(triggering.configId))
        runAndWait(h)
        composeRule.onNodeWithTag("lab_compare_outcome_0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("跳过评分", substring = true)
        composeRule.onNodeWithTag("lab_compare_score_0", useUnmergedTree = true)
            .assertTextContains("多个期望触发参考点", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_0", useUnmergedTree = true)
            .assertTextContains("纳入评分 0", substring = true)

        selectSession(h, oldVersion.id)
        setTargets(h, ComparisonTarget.OriginalSnapshot)
        runAndWait(h)
        composeRule.onNodeWithTag("lab_compare_outcome_0", useUnmergedTree = true)
            .performScrollTo()
            .assertTextContains("按时", substring = true)
        composeRule.onNodeWithTag("lab_compare_result_config_0", useUnmergedTree = true)
            .assertTextContains("当次运行配置", substring = true)
            .assertTextContains("proximity-algorithms/0 → 执行 ${ProximityEngine.ALGORITHM_VERSION}", substring = true)
            .assertTextContains("confirmed-far-then-near/0 → 执行 ${ProximityEngine.TRIGGER_RULE_VERSION}", substring = true)
            .assertTextContains("版本差异", substring = true)
    }

    private fun setContent(h: Harness) {
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlgorithmLabScreen(viewModel = h.viewModel)
                }
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("lab_compare_section", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun selectSession(h: Harness, sessionId: String) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            h.viewModel.uiState.value.comparison.sessions.any { summary ->
                summary.session.id == sessionId
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("lab_compare_session_$sessionId", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("lab_compare_session_$sessionId", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            h.viewModel.uiState.value.comparison.context?.session?.id == sessionId
        }
    }

    private fun setTargets(
        h: Harness,
        vararg targets: ComparisonTarget,
    ) {
        val desired = targets.toSet()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            !h.viewModel.uiState.value.comparison.contextLoading &&
                h.viewModel.uiState.value.comparison.context != null
        }
        val current = h.viewModel.uiState.value.comparison.selectedTargets
        (current - desired).forEach { target -> clickTarget(target) }
        (desired - current).forEach { target -> clickTarget(target) }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            h.viewModel.uiState.value.comparison.selectedTargets == desired
        }
    }

    private fun clickTarget(target: ComparisonTarget) {
        val tag = when (target) {
            ComparisonTarget.OriginalSnapshot -> "lab_compare_original"
            is ComparisonTarget.Config -> "lab_compare_config_${target.configId}"
        }
        composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
    }

    private fun runAndWait(h: Harness) {
        composeRule.onNodeWithTag("lab_compare_run", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            val comparison = h.viewModel.uiState.value.comparison
            !comparison.running && comparison.result != null
        }
        composeRule.waitForIdle()
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
        val sessionStore: SessionStore = RoomSessionStore(database.samplingSessionDao())
        val configStore: AlgorithmConfigStore = RoomAlgorithmConfigStore(
            dao = database.algorithmConfigDao(),
            clock = { now },
        )
        val replayService = SessionReplayService(
            sessionStore = sessionStore,
            replayStore = RoomSessionReplayStore(database.sessionReplayDao()),
            configStore = configStore,
            clock = { now },
            idGenerator = { "ui-comparison-replay-${UUID.randomUUID()}" },
        )
        val comparisonService = SessionComparisonService(
            sessionStore = sessionStore,
            replayService = replayService,
            configStore = configStore,
        )
        val viewModel = AlgorithmLabViewModel(configStore, comparisonService)
        return Harness(
            database = database,
            sessionStore = sessionStore,
            configStore = configStore,
            viewModel = viewModel,
        ).also { harness = it }
    }

    private inner class Harness(
        private val database: ProximityLabDatabase,
        val sessionStore: SessionStore,
        val configStore: AlgorithmConfigStore,
        val viewModel: AlgorithmLabViewModel,
    ) {
        suspend fun createConfig(name: String, enterThresholdDbm: Int): AlgorithmConfig {
            // 配置列表按创建时间排序；递增时钟保证多配置界面的顺序稳定。
            now += 1L
            return configStore.createConfig(
                name = name,
                config = ProximityConfig(
                    algorithm = ProximityAlgorithm.BASELINE,
                    enterThresholdDbm = enterThresholdDbm,
                    exitThresholdDbm = -80,
                    confirmation = ConfirmationRequirement.ConsecutiveSamples(2),
                    sampleValidityMillis = 5_000L,
                    emaAlpha = 0.5,
                ),
            )
        }

        fun close() {
            viewModel.viewModelScope.cancel()
            database.close()
        }
    }

    private fun seedSession(
        h: Harness,
        id: String,
        eventAtMillis: Long,
        coverageEndMillis: Long,
        snapshot: SessionConfigSnapshot? = null,
        status: SessionLifecycleStatus = SessionLifecycleStatus.COMPLETED,
        label: SessionEvaluationLabel = SessionEvaluationLabel.UNMARKED,
    ): SamplingSession {
        val startedAt = now
        val session = SamplingSession(
            id = id,
            targetId = "target-1",
            targetDisplayName = "目标车",
            targetAddress = "AA:BB:CC:DD:EE:01",
            samplingType = if (snapshot != null) SamplingType.SIMULATION else SamplingType.WALKING,
            name = id,
            location = FixedSampleLocation.OTHER,
            estimatedDistanceMeters = null,
            phonePlacement = PhonePlacement.HANDHELD,
            status = status,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = if (status == SessionLifecycleStatus.COMPLETED) {
                startedAt + coverageEndMillis
            } else {
                null
            },
            lastRecordedAtEpochMillis = startedAt + coverageEndMillis,
            interruptedDetectedAtEpochMillis = if (status == SessionLifecycleStatus.INTERRUPTED) {
                startedAt + coverageEndMillis + 1L
            } else {
                null
            },
            evaluationLabel = label,
        )
        val timeline = listOf(
            0L to -85,
            100L to -85,
            eventAtMillis - 800L to -85,
            eventAtMillis - 600L to -85,
            eventAtMillis - 100L to -60,
            eventAtMillis to -60,
        ).mapIndexed { index, (offset, rssi) ->
            SessionTimelineItem(
                sequence = index + 1L,
                sessionId = id,
                event = SessionTimelineEvent.Observation(
                    observationId = "$id-observation-$index",
                    targetId = "target-1",
                    rawRssiDbm = rssi,
                    observedAtEpochMillis = startedAt + offset,
                    receivedAtEpochMillis = startedAt + offset,
                    scanFrameId = 20_000L + index,
                    deliverySequence = index + 1L,
                    address = "AA:BB:CC:DD:EE:01",
                    payloadFingerprint = "$id-fingerprint-$index",
                    processedAtEpochMillis = startedAt + offset,
                ),
            )
        }
        runBlocking {
            if (snapshot != null) {
                h.sessionStore.createSimulationSession(session, snapshot)
            } else {
                h.sessionStore.createSession(session)
            }
            timeline.forEach { item -> h.sessionStore.appendTimeline(id, item.event) }
        }
        return session
    }

    private fun addMarker(h: Harness, session: SamplingSession, offsetMillis: Long) {
        runBlocking {
            h.sessionStore.insertReferenceMarker(
                SessionReferenceMarker(
                    markerId = "marker-${session.id}-$offsetMillis",
                    sessionId = session.id,
                    kind = ReferenceMarkerKind.EXPECTED_TRIGGER,
                    atEpochMillis = session.startedAtEpochMillis + offsetMillis,
                ),
            )
        }
    }

    private fun snapshot(name: String): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = "ui-comparison-snapshot-$name",
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

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
