package com.byd.proximitylab.ui.monitor

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.AppContainer
import com.byd.proximitylab.MainActivity
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生产接线：MainActivity → AppContainer → MonitorViewModel → 真实 SessionRecorder/模拟测试存储。
 */
@RunWith(AndroidJUnit4::class)
class MonitorProductionWiringTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = (context.applicationContext as ProximityLabApplication).container
    }

    @After
    fun tearDown() {
        runBlocking {
            container.sessionRecorder.stop()
            // 清理本测试在应用共享数据库留下的模拟 Session，避免影响后续手工状态或同进程用例。
            container.sessionStore.observeSessions().first()
                .map { summary -> summary.session }
                .filter { session ->
                    session.targetId == TARGET_ID && session.samplingType == SamplingType.SIMULATION
                }
                .forEach { session ->
                    container.sessionStore.deleteSession(session.id)
                    assertNull(container.sessionStore.loadSession(session.id))
                }
            container.bindingStore.clearBinding()
        }
    }

    @Test
    fun monitorDeviceEntryNavigatesToDevicePage() {
        composeRule.onNodeWithTag("nav_monitor").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("monitor_open_device").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("monitor_open_device").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen_device").assertIsDisplayed()
    }

    @Test
    fun productionMonitorEntryStartsSimulationWithSnapshotAndStopsIt() {
        val binding = testBinding(
            targetId = TARGET_ID,
            address = TARGET_ADDRESS,
            displayName = TARGET_NAME,
        )
        runBlocking { container.bindingStore.saveBinding(binding) }

        composeRule.onNodeWithTag("nav_monitor").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("monitor_binding").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("monitor_binding")
            .assertTextContains(TARGET_NAME, substring = true)
        composeRule.onNodeWithTag("monitor_binding")
            .assertTextContains(TARGET_ADDRESS, substring = true)

        composeRule.onNodeWithTag("monitor_start_test").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            latestSimulation()?.status == SessionLifecycleStatus.RECORDING
        }
        val session = requireNotNull(latestSimulation())
        assertEquals(SamplingType.SIMULATION, session.samplingType)
        assertEquals(TARGET_ID, session.targetId)
        assertNotNull(runBlocking { container.sessionStore.loadConfigSnapshot(session.id) })
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            container.liveMonitorController.state.value.simulationRunning
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("monitor_test_running", useUnmergedTree = true)
            .assertTextContains("运行中", substring = true)

        // 换页到采样页再返回监控页：运行中的模拟测试不结束。
        composeRule.onNodeWithTag("nav_sampling").performClick()
        composeRule.onNodeWithTag("screen_sampling").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_monitor").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            container.liveMonitorController.state.value.simulationRunning
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("monitor_test_running", useUnmergedTree = true)
            .assertTextContains("运行中", substring = true)
        assertEquals(
            SessionLifecycleStatus.RECORDING,
            latestSimulation()?.status,
        )

        composeRule.onNodeWithTag("monitor_stop_test").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            latestSimulation()?.status == SessionLifecycleStatus.COMPLETED
        }
        val completed = requireNotNull(
            runBlocking { container.sessionStore.loadSession(session.id) },
        )
        assertEquals(SessionLifecycleStatus.COMPLETED, completed.status)
        assertNotNull(completed.endedAtEpochMillis)
        assertNotNull(runBlocking { container.sessionStore.loadConfigSnapshot(session.id) })
    }

    private fun latestSimulation(): SamplingSession? = runBlocking {
        container.sessionStore.observeSessions().first()
            .map { it.session }
            .filter { it.samplingType == SamplingType.SIMULATION && it.targetId == TARGET_ID }
            .maxByOrNull { it.startedAtEpochMillis }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val TARGET_ID = "monitor-production-target"
        const val TARGET_ADDRESS = "AA:BB:CC:DD:EE:79"
        const val TARGET_NAME = "监控生产接线目标"
    }
}
