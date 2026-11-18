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
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.data.RoomSessionStore
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

        composeRule.onNodeWithTag("sampling_session_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("sampling_detail_target")
            .assertTextContains("Generic device", substring = true)
        composeRule.onNodeWithTag("sampling_detail_target")
            .assertTextContains(addressA, substring = true)
        composeRule.onNodeWithTag("sampling_detail_conditions")
            .assertTextContains("车尾", substring = true)
        composeRule.onNodeWithTag("sampling_detail_conditions")
            .assertTextContains("背包", substring = true)
        composeRule.onNodeWithTag("sampling_detail_conditions")
            .assertTextContains("3.5 米", substring = true)
        composeRule.onNodeWithTag("sampling_detail_duration")
            .assertTextContains("3.0 秒", substring = true)
        composeRule.onNodeWithTag("sampling_detail_sample_count")
            .assertTextContains("2", substring = true)
        composeRule.onNodeWithText("-82 dBm", substring = true).assertExists()
        composeRule.onNodeWithText("-81 dBm", substring = true).assertExists()

        composeRule.onNodeWithTag("sampling_detail_back")
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
        composeRule.onNodeWithTag("sampling_detail_status")
            .assertTextContains("中断", substring = true)
        composeRule.onNodeWithTag("sampling_detail_status")
            .assertTextContains("不计入结束时间", substring = true)
        composeRule.onNodeWithTag("sampling_detail_sample_count")
            .assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("sampling_detail_duration")
            .assertTextContains("1.0 秒", substring = true)
        composeRule.onNodeWithText("-69 dBm", substring = true).assertExists()

        val stored = runBlocking { reopened.sessionStore.loadSession(sessionId) }
        requireNotNull(stored)
        assertEquals(SessionLifecycleStatus.INTERRUPTED, stored.status)
        assertNull(stored.endedAtEpochMillis)
        assertEquals(coverageEnd, stored.lastRecordedAtEpochMillis)
        assertEquals(now, stored.interruptedDetectedAtEpochMillis)
    }

    private fun waitForBinding() {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag("sampling_binding").fetchSemanticsNodes().isNotEmpty()
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
        val viewModel = SamplingViewModel(
            recorder = recorder,
            store = sessionStore,
            observationSource = monitor,
            clock = { now },
        )
        return Harness(database, source, scope, sessionStore, recorder, viewModel)
            .also { harnesses += it }
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
