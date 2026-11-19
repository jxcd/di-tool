package com.byd.proximitylab.ui.lab

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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.algorithmconfig.ConfirmationKind
import com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.ui.theme.ProximityLabTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 06 算法实验室页面全链路：真实 Room 配置存储、真实 ViewModel 与 Compose 页面。
 *
 * 覆盖新建/复制/编辑/详情/选择/删除、非法参数的字段错误，以及 ViewModel 重建后的选择恢复。
 */
@RunWith(AndroidJUnit4::class)
class AlgorithmLabScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var harness: Harness? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "algorithm-lab-screen-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        harness?.close()
        harness = null
        context.deleteDatabase(databaseName)
    }

    @Test
    fun createCopyEditSelectAndRecreateRestoresSelection() {
        val h = harness()
        var currentViewModel by mutableStateOf(h.viewModel)
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlgorithmLabScreen(viewModel = currentViewModel)
                }
            }
        }
        waitForBuiltIns()

        createConfig(
            viewModel = h.viewModel,
            name = "配置甲",
            algorithm = ProximityAlgorithm.BASELINE,
            enter = "-60",
            exit = "-80",
            confirmationKind = ConfirmationKind.CONSECUTIVE_SAMPLES,
            confirmationValue = "2",
            alpha = "0.5",
        )
        createConfig(
            viewModel = h.viewModel,
            name = "配置乙",
            algorithm = ProximityAlgorithm.EMA,
            enter = "-55",
            exit = "-75",
            confirmationKind = ConfirmationKind.DURATION,
            confirmationValue = "1500",
            alpha = "0.3",
        )
        waitUntil { h.viewModel.uiState.value.userConfigs.size == 2 }
        val secondId = h.viewModel.uiState.value.userConfigs.first { it.name == "配置乙" }.configId
        assertEquals(ProximityAlgorithm.EMA, h.viewModel.uiState.value.userConfigs.first {
            it.configId == secondId
        }.config.algorithm)

        composeRule.onNodeWithTag("copy_config_$secondId").performScrollTo().performClick()
        waitUntil { h.viewModel.uiState.value.form != null }
        assertEquals("配置乙 副本", h.viewModel.uiState.value.form?.input?.name)
        composeRule.onNodeWithTag("config_name").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_name").performTextInput("口袋")
        composeRule.onNodeWithTag("config_ema_alpha").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_ema_alpha").performTextInput("0.2")
        composeRule.onNodeWithTag("config_save").performClick()
        waitUntil { h.viewModel.uiState.value.userConfigs.size == 3 }
        val copyId = h.viewModel.uiState.value.userConfigs.first { it.name == "口袋" }.configId

        composeRule.onNodeWithTag("edit_config_$copyId").performScrollTo().performClick()
        waitUntil { h.viewModel.uiState.value.form?.editingConfigId == copyId }
        composeRule.onNodeWithTag("config_ema_alpha").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_ema_alpha").performTextInput("0.25")
        composeRule.onNodeWithTag("config_save").performClick()
        waitUntil {
            h.viewModel.uiState.value.userConfigs.firstOrNull { it.configId == copyId }
                ?.config?.emaAlpha == 0.25
        }
        assertEquals(
            0.5,
            runBlocking { h.store.loadConfig(BuiltInAlgorithmConfigs.EMA.configId) }!!
                .config.emaAlpha,
            0.0,
        )

        composeRule.onNodeWithTag("detail_config_$copyId").performScrollTo().performClick()
        composeRule.onNodeWithTag("config_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("config_detail_params")
            .assertTextContains("α 0.25", substring = true)
        composeRule.onNodeWithTag("config_detail_close").performClick()
        waitUntil { h.viewModel.uiState.value.detail == null }

        composeRule.onNodeWithTag("select_config_$copyId").performScrollTo().performClick()
        waitUntil { h.viewModel.uiState.value.selectedConfigId == copyId }
        composeRule.onNodeWithTag("lab_selected_config")
            .performScrollTo()
            .assertTextContains("口袋", substring = true)

        // 模拟页面重建：新的 ViewModel 从真实存储恢复当前生效项和参数。
        h.viewModel.viewModelScope.cancel()
        composeRule.runOnUiThread {
            currentViewModel = AlgorithmLabViewModel(h.store)
        }
        waitUntil { currentViewModel.uiState.value.selectedConfigId == copyId }
        composeRule.onNodeWithTag("lab_selected_config")
            .performScrollTo()
            .assertTextContains("口袋", substring = true)
        val restored = currentViewModel.uiState.value.userConfigs.first { it.configId == copyId }
        assertEquals(0.25, restored.config.emaAlpha, 0.0)
        assertEquals(-55, restored.config.enterThresholdDbm)
    }

    @Test
    fun invalidParametersShowFieldErrorsAndDoNotSave() {
        val h = harness()
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlgorithmLabScreen(viewModel = h.viewModel)
                }
            }
        }
        waitForBuiltIns()

        composeRule.onNodeWithTag("create_config").performScrollTo().performClick()
        waitUntil { h.viewModel.uiState.value.form != null }
        composeRule.onNodeWithTag("config_name").performScrollTo().performTextInput("无效阈值")
        composeRule.onNodeWithTag("config_enter_threshold").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_enter_threshold").performTextInput("-80")
        composeRule.onNodeWithTag("config_exit_threshold").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_exit_threshold").performTextInput("-60")
        composeRule.onNodeWithTag("config_save").performClick()
        waitUntil {
            h.viewModel.uiState.value.form?.fieldErrors?.isNotEmpty() == true
        }
        composeRule.onNodeWithTag("config_enter_threshold_error", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag("config_exit_threshold_error", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag("config_enter_threshold_error", useUnmergedTree = true)
            .assertTextContains("进入阈值", substring = true)
        assertTrue(
            h.viewModel.uiState.value.form!!.fieldErrors.values.any { it.contains("进入阈值") },
        )
        assertTrue(h.viewModel.uiState.value.userConfigs.isEmpty())

        // 修正阈值但保留非法 EMA 参数：仍不保存，并把错误对准 EMA 字段。
        composeRule.onNodeWithTag("config_enter_threshold").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_enter_threshold").performTextInput("-60")
        composeRule.onNodeWithTag("config_ema_alpha").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_ema_alpha").performTextInput("1.5")
        composeRule.onNodeWithTag("config_save").performClick()
        waitUntil {
            h.viewModel.uiState.value.form?.fieldErrors?.containsKey(
                com.byd.proximitylab.algorithmconfig.AlgorithmConfigField.EMA_ALPHA,
            ) == true
        }
        composeRule.onNodeWithTag("config_ema_alpha_error", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("config_ema_alpha_error", useUnmergedTree = true)
            .assertTextContains("EMA", substring = true)
        assertTrue(h.viewModel.uiState.value.userConfigs.isEmpty())
    }

    @Test
    fun deleteSelectedConfigUsesExplicitNameFallsBackToDefaultAndKeepsSnapshot() {
        val h = harness()
        composeRule.setContent {
            ProximityLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AlgorithmLabScreen(viewModel = h.viewModel)
                }
            }
        }
        waitForBuiltIns()

        createConfig(
            viewModel = h.viewModel,
            name = "口袋",
            algorithm = ProximityAlgorithm.EMA,
            enter = "-58",
            exit = "-82",
            confirmationKind = ConfirmationKind.DURATION,
            confirmationValue = "1500",
            alpha = "0.2",
        )
        waitUntil { h.viewModel.uiState.value.userConfigs.size == 1 }
        val configId = h.viewModel.uiState.value.userConfigs.single().configId
        composeRule.onNodeWithTag("select_config_$configId").performScrollTo().performClick()
        waitUntil { h.viewModel.uiState.value.selectedConfigId == configId }

        val snapshot = runBlocking { h.store.snapshotForNextSimulation() }
        runBlocking {
            h.sessionStore.createSimulationSession(simulationSession(), snapshot)
        }

        composeRule.onNodeWithTag("delete_config_$configId").performScrollTo().performClick()
        composeRule.onNodeWithTag("config_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("config_delete_target")
            .assertTextContains("口袋", substring = true)
        composeRule.onNodeWithTag("config_delete_confirm").performClick()
        waitUntil {
            h.viewModel.uiState.value.pendingDelete == null &&
                h.viewModel.uiState.value.userConfigs.isEmpty()
        }
        waitUntil {
            h.viewModel.uiState.value.selectedConfigId == BuiltInAlgorithmConfigs.DEFAULT.configId
        }

        assertEquals(BuiltInAlgorithmConfigs.DEFAULT.configId, h.viewModel.uiState.value.selectedConfigId)
        composeRule.onNodeWithTag("lab_selected_config")
            .performScrollTo()
            .assertTextContains("内置 Baseline", substring = true)
        // 内置算法一直可选择：删除后选择 EMA。
        composeRule.onNodeWithTag("select_config_builtin-ema-v1").performScrollTo().performClick()
        waitUntil {
            h.viewModel.uiState.value.selectedConfigId == BuiltInAlgorithmConfigs.EMA.configId
        }
        composeRule.onNodeWithTag("lab_selected_config")
            .performScrollTo()
            .assertTextContains("内置 EMA", substring = true)

        val storedSnapshot = runBlocking { h.sessionStore.loadConfigSnapshot(SESSION_ID) }
        assertEquals(snapshot, storedSnapshot)
        assertEquals("口袋", storedSnapshot?.name)
        assertEquals(0.2, storedSnapshot!!.config.emaAlpha, 0.0)
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
        ).build()
        var now = 1_800_000_000_000L
        val store: AlgorithmConfigStore = RoomAlgorithmConfigStore(
            dao = database.algorithmConfigDao(),
            clock = { now++ },
        )
        val viewModel = AlgorithmLabViewModel(store)
        return Harness(
            database = database,
            store = store,
            sessionStore = RoomSessionStore(database.samplingSessionDao()),
            viewModel = viewModel,
        ).also { harness = it }
    }

    private fun createConfig(
        viewModel: AlgorithmLabViewModel,
        name: String,
        algorithm: ProximityAlgorithm,
        enter: String,
        exit: String,
        confirmationKind: ConfirmationKind,
        confirmationValue: String,
        alpha: String,
    ) {
        composeRule.onNodeWithTag("create_config").performScrollTo().performClick()
        waitUntil { viewModel.uiState.value.form != null }
        composeRule.onNodeWithTag("config_name").performScrollTo().performTextInput(name)
        if (algorithm == ProximityAlgorithm.EMA) {
            composeRule.onNodeWithTag("config_algorithm_ema").performScrollTo().performClick()
        }
        composeRule.onNodeWithTag("config_enter_threshold").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_enter_threshold").performTextInput(enter)
        composeRule.onNodeWithTag("config_exit_threshold").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_exit_threshold").performTextInput(exit)
        if (confirmationKind == ConfirmationKind.DURATION) {
            composeRule.onNodeWithTag("config_confirmation_duration").performScrollTo().performClick()
        }
        composeRule.onNodeWithTag("config_confirmation_value").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_confirmation_value").performTextInput(confirmationValue)
        composeRule.onNodeWithTag("config_ema_alpha").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("config_ema_alpha").performTextInput(alpha)
        composeRule.onNodeWithTag("config_save").performClick()
        waitUntil { viewModel.uiState.value.form == null }
    }

    private fun waitForBuiltIns() {
        waitUntil {
            composeRule.onAllNodesWithTag("lab_config_builtin-baseline-v1")
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("lab_config_builtin-ema-v1")
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntil(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis, condition)
    }

    private inner class Harness(
        private val database: ProximityLabDatabase,
        val store: AlgorithmConfigStore,
        val sessionStore: RoomSessionStore,
        val viewModel: AlgorithmLabViewModel,
    ) {
        fun close() {
            viewModel.viewModelScope.cancel()
            database.close()
        }
    }

    private fun simulationSession(): SamplingSession = SamplingSession(
        id = SESSION_ID,
        targetId = "lab-screen-target",
        targetDisplayName = "算法实验室目标",
        targetAddress = "AA:BB:CC:DD:EE:6A",
        samplingType = SamplingType.SIMULATION,
        name = "配置删除后的快照",
        location = FixedSampleLocation.OTHER,
        estimatedDistanceMeters = null,
        phonePlacement = PhonePlacement.HANDHELD,
        status = SessionLifecycleStatus.RECORDING,
        startedAtEpochMillis = 1_800_000_100_000L,
        endedAtEpochMillis = null,
        lastRecordedAtEpochMillis = 1_800_000_100_000L,
        interruptedDetectedAtEpochMillis = null,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val SESSION_ID = "lab-screen-session"
    }
}
