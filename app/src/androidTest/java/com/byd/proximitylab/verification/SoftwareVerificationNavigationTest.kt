package com.byd.proximitylab.verification

import android.Manifest
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.MainActivity
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.algorithmconfig.AlgorithmConfig
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.testing.AndroidTestPermissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 12 的主路径验收：真实 `MainActivity` + Compose Navigation + 页面点击。
 *
 * 只通过 [ProximityLabApplication.setContainerForTest] 注入替代 BLE/通知/前台服务边界的容器；
 * MainActivity、四个页面、Navigation、ViewModel、Room、共享判定与回放对比都是生产实现。
 */
@RunWith(AndroidJUnit4::class)
class SoftwareVerificationNavigationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var application: ProximityLabApplication
    private lateinit var harness: SoftwareVerificationHarness
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidTestPermissions.grant(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        harness = SoftwareVerificationHarness(context)
        application = context.applicationContext as ProximityLabApplication
        application.setContainerForTest(harness.container)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        application.setContainerForTest(null)
        harness.close()
    }

    @Test
    fun navigatesFromDeviceBindingThroughSamplingSimulationDetailReplayAndComparison() {
        waitForTag("screen_device")

        // 1. 设备页：扫描 → 人工绑定。广播来自可控 BLE 替身，页面、ViewModel 和 Room 是生产实现。
        composeRule.onNodeWithTag("device_scan_start").performClick()
        waitForText("device_scan_state", "扫描中")
        harness.feed(rssi = -70)
        waitForTag("device_candidate_${SoftwareVerificationHarness.TARGET_ADDRESS}")
        composeRule.onNodeWithTag(
            "device_bind_${SoftwareVerificationHarness.TARGET_ADDRESS}",
        ).performScrollTo().performClick()
        composeRule.onNodeWithTag("device_action_confirm").performClick()
        waitForText(
            "device_binding_address",
            SoftwareVerificationHarness.TARGET_ADDRESS,
        )

        // 2. 算法实验室：页面新建并选择具名配置，下一次模拟测试使用它。
        composeRule.onNodeWithTag("nav_lab").performClick()
        waitForTag("screen_lab")
        val uiConfig = createAndSelectConfigThroughUi("UI 验收配置")

        // 3. 采样页：行走采样 + 人工标记，页面入口驱动真实 SessionRecorder/Room。
        composeRule.onNodeWithTag("nav_sampling").performClick()
        waitForTag("screen_sampling")
        waitForTag("sampling_binding")
        composeRule.onNodeWithTag("sampling_name").performTextReplacement("UI 验收行走")
        composeRule.onNodeWithTag("sampling_type_WALKING").performScrollTo().performClick()
        composeRule.onNodeWithTag("sampling_start").performScrollTo().performClick()
        waitForTag("sampling_active")
        val walkingSessionId = requireNotNull(
            harness.container.sessionRecorder.activeSession.value,
        ).id
        assertEquals(SamplingType.WALKING, harness.session(walkingSessionId)?.samplingType)
        harness.feed(rssi = -82)
        harness.feed(rssi = -78)
        harness.feed(rssi = -76)
        harness.waitUntil("页面行走采样入库") {
            harness.observations(walkingSessionId).size == 3
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sampling_active_sample_count")
            .assertTextContains("3", substring = true)
        composeRule.onNodeWithTag("sampling_marker_${ReferenceMarkerKind.EXPECTED_TRIGGER.name}")
            .performScrollTo()
            .performClick()
        harness.waitUntil("页面人工标记入库") {
            harness.markers(walkingSessionId).any { marker ->
                marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER
            }
        }
        composeRule.onNodeWithTag("sampling_stop").performScrollTo().performClick()
        harness.waitUntil("页面行走 Session 完成") {
            harness.session(walkingSessionId)?.status == SessionLifecycleStatus.COMPLETED
        }
        waitForTag("sampling_session_$walkingSessionId")
        composeRule.onNodeWithTag("sampling_session_$walkingSessionId")
            .performScrollTo()
            .performClick()
        waitForTag("sampling_session_detail")
        composeRule.onNodeWithTag("sampling_detail_sample_count")
            .assertTextContains("3", substring = true)
        composeRule.onNodeWithTag("sampling_detail_back").performScrollTo().performClick()
        waitForTagGone("sampling_session_detail")

        // 4. 监控页：开始模拟测试、记录期望位置并产生一次本地事件。
        composeRule.onNodeWithTag("nav_monitor").performClick()
        waitForTag("monitor_binding")
        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        waitForTag("monitor_test_running")
        harness.waitUntil("页面模拟测试运行") {
            harness.container.liveMonitorController.state.value.simulationRunning
        }
        val simulationSessionId = requireNotNull(
            harness.container.liveMonitorController.state.value.test,
        ).session.id
        assertEquals(
            uiConfig.configId,
            harness.session(simulationSessionId)?.let { session ->
                runBlocking {
                    harness.container.sessionStore.loadConfigSnapshot(session.id)
                }
            }?.sourceConfigId,
        )
        harness.feed(rssi = -88)
        harness.feed(rssi = -88)
        waitForText("monitor_state", ProximityState.FAR.name)
        composeRule.onNodeWithTag("monitor_mark_expected").performScrollTo().performClick()
        harness.waitUntil("页面记录期望触发位置") {
            harness.markers(simulationSessionId).any { marker ->
                marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER
            }
        }
        harness.feed(rssi = -55)
        harness.feed(rssi = -55)
        harness.waitUntil("页面模拟事件产生") {
            harness.events(simulationSessionId).size == 1
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("monitor_event_count")
            .performScrollTo()
            .assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("monitor_latest_event")
            .assertTextContains("ENTERED_RANGE", substring = true)
        // 评分窗默认含标记后 2 s 迟到容差；等待记录覆盖到窗终点再停止，避免被判观测不足。
        val markerAt = harness.markers(simulationSessionId)
            .first { marker -> marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER }
            .atEpochMillis
        val remainingCoverage = markerAt + 2_200L - System.currentTimeMillis()
        if (remainingCoverage > 0L) {
            Thread.sleep(remainingCoverage)
        }
        composeRule.onNodeWithTag("monitor_stop_test").performScrollTo().performClick()
        harness.waitUntil("页面模拟测试结束") {
            harness.session(simulationSessionId)?.status == SessionLifecycleStatus.COMPLETED
        }

        // 5. Session 详情：配置快照、原始时间线和人工标记可查；页面触发原样回放。
        composeRule.onNodeWithTag("nav_sampling").performClick()
        waitForTag("sampling_session_$simulationSessionId")
        composeRule.onNodeWithTag("sampling_session_$simulationSessionId")
            .performScrollTo()
            .performClick()
        waitForTag("sampling_session_detail")
        composeRule.onNodeWithTag("sampling_detail_config")
            .performScrollTo()
            .assertTextContains("UI 验收配置", substring = true)
        composeRule.onNodeWithTag("sampling_detail_sample_count")
            .assertTextContains("4", substring = true)
        composeRule.onNodeWithTag("sampling_detail_replay_run")
            .performScrollTo()
            .performClick()
        waitForTag("sampling_detail_replay_summary")
        composeRule.onNodeWithTag("sampling_detail_replay_summary")
            .assertTextContains("重现当次结果", substring = true)
            .assertTextContains("事件 1 条", substring = true)

        // 6. 算法实验室：新建第二套严格配置并选择，用同一 Session 做不同配置对比。
        composeRule.onNodeWithTag("nav_lab").performClick()
        waitForTag("screen_lab")
        createAndSelectConfigThroughUi("UI 严格配置", enterThreshold = "-30")
        waitForTag("lab_compare_session_$simulationSessionId")
        composeRule.onNodeWithTag("lab_compare_session_$simulationSessionId")
            .performScrollTo()
            .performClick()
        waitForTag("lab_compare_context")
        composeRule.onNodeWithTag("lab_compare_run").performScrollTo().performClick()
        waitForTag("lab_compare_result_0")
        waitForTag("lab_compare_result_1")
        composeRule.onNodeWithTag("lab_compare_outcome_0")
            .performScrollTo()
            .assertTextContains("按时", substring = true)
        composeRule.onNodeWithTag("lab_compare_outcome_1")
            .assertTextContains("漏触发", substring = true)
        composeRule.onNodeWithTag("lab_compare_window")
            .assertTextContains("秒", substring = true)
        composeRule.onNodeWithTag("lab_compare_summary_0").assertIsDisplayed()
    }

    /**
     * 通过算法实验室页面新建用户配置并设为当前生效项，返回保存后的配置。
     */
    private fun createAndSelectConfigThroughUi(
        name: String,
        enterThreshold: String? = null,
    ): AlgorithmConfig {
        composeRule.onNodeWithTag("create_config").performScrollTo().performClick()
        waitForTag("config_editor")
        composeRule.onNodeWithTag("config_name").performTextReplacement(name)
        if (enterThreshold != null) {
            composeRule.onNodeWithTag("config_enter_threshold")
                .performTextReplacement(enterThreshold)
        }
        composeRule.onNodeWithTag("config_save").performClick()
        val configs = runBlocking {
            withTimeout(CONFIG_TIMEOUT_MILLIS) {
                harness.container.algorithmConfigStore.observeConfigs().first { configs ->
                    configs.any { config -> config.name == name }
                }
            }
        }
        val config = requireNotNull(configs.firstOrNull { config -> config.name == name })
        waitForTag("select_config_${config.configId}")
        composeRule.onNodeWithTag("select_config_${config.configId}")
            .performScrollTo()
            .performClick()
        waitForText("lab_selected_config", name)
        runBlocking { assertNotNull(harness.container.algorithmConfigStore.loadConfig(config.configId)) }
        return config
    }

    private fun waitForTag(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        try {
            composeRule.waitUntil(timeoutMillis) {
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("NAV_UI")
            throw error
        }
        composeRule.waitForIdle()
    }

    private fun waitForTagGone(tag: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(
        tag: String,
        expected: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text.contains(expected)
                } == true
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val CONFIG_TIMEOUT_MILLIS = 30_000L
    }
}
