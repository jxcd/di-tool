package com.byd.proximitylab.ui.sampling

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.AppContainer
import com.byd.proximitylab.MainActivity
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.feedback.ProximityLabForegroundService
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.testing.AndroidTestPermissions
import com.byd.proximitylab.testing.testBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生产接线：直接启动 MainActivity，走 rememberSamplingViewModel → AppContainer → samplingFactory
 * → 真实 TargetMonitor/RoomSessionStore/SessionRecorder，验证 Session 确实写入生产数据库。
 */
@RunWith(AndroidJUnit4::class)
class SamplingProductionWiringTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidTestPermissions.grant(context, Manifest.permission.BLUETOOTH_SCAN)
        container = (context.applicationContext as ProximityLabApplication).container
    }

    @After
    fun tearDown() {
        // 生产容器由 Application 持有，测试结束时只清理本次插入的绑定，避免影响其他用例。
        ProximityLabForegroundService.stop(context)
        runBlocking { container.bindingStore.clearBinding() }
    }

    @Test
    fun productionSamplingEntryCreatesAndCompletesSessionInAppDatabase() {
        val sessionName = "生产接线-${System.currentTimeMillis()}"
        val binding = testBinding(
            targetId = TARGET_ID,
            address = TARGET_ADDRESS,
            displayName = TARGET_NAME,
        )
        runBlocking { container.bindingStore.saveBinding(binding) }

        composeRule.onNodeWithTag("nav_sampling").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_binding").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sampling_binding")
            .assertTextContains(TARGET_NAME, substring = true)
        composeRule.onNodeWithTag("sampling_binding")
            .assertTextContains(TARGET_ADDRESS, substring = true)

        composeRule.onNodeWithTag("sampling_name").performTextReplacement(sessionName)
        composeRule.onNodeWithTag("sampling_start").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            findSessionByName(sessionName)?.status == SessionLifecycleStatus.RECORDING
        }
        composeRule.onNodeWithTag("sampling_active").assertIsDisplayed()
        val active = requireNotNull(findSessionByName(sessionName))
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            ProximityLabForegroundService.runningSessionId.value == active.id
        }

        composeRule.onNodeWithTag("sampling_stop").performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            findSessionByName(sessionName)?.status == SessionLifecycleStatus.COMPLETED
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            ProximityLabForegroundService.runningSessionId.value == null
        }

        val completed = requireNotNull(findSessionByName(sessionName))
        assertEquals(TARGET_ID, completed.targetId)
        assertEquals(TARGET_ADDRESS, completed.targetAddress)
        assertEquals(TARGET_NAME, completed.targetDisplayName)
        assertTrue(requireNotNull(completed.endedAtEpochMillis) >= completed.startedAtEpochMillis)
    }

    private fun findSessionByName(name: String): SamplingSession? = runBlocking {
        container.sessionStore.observeSessions().first()
            .filter { summary -> summary.session.name == name }
            .maxByOrNull { summary -> summary.session.startedAtEpochMillis }
            ?.session
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val TARGET_ID = "production-wiring-target"
        const val TARGET_ADDRESS = "AA:BB:CC:DD:EE:77"
        const val TARGET_NAME = "生产接线目标"
    }
}
