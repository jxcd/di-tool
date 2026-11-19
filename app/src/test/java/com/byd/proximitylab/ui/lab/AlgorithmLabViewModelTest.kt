package com.byd.proximitylab.ui.lab

import com.byd.proximitylab.algorithmconfig.AlgorithmConfigField
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigInput
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.algorithmconfig.ConfirmationKind
import com.byd.proximitylab.algorithmconfig.InMemoryAlgorithmConfigStore
import com.byd.proximitylab.proximity.ProximityAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlgorithmLabViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateListsBuiltInsAndSelectsDefault() = runTest(dispatcher) {
        val store = InMemoryAlgorithmConfigStore()
        val viewModel = AlgorithmLabViewModel(store)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("builtin-baseline-v1", "builtin-ema-v1"), state.builtInConfigs.map {
            it.configId
        })
        assertEquals(BuiltInAlgorithmConfigs.DEFAULT.configId, state.selectedConfigId)
        assertTrue(state.userConfigs.isEmpty())
    }

    @Test
    fun validFormSavesUserConfigAndClosesEditor() = runTest(dispatcher) {
        val store = InMemoryAlgorithmConfigStore()
        val viewModel = AlgorithmLabViewModel(store)
        advanceUntilIdle()

        viewModel.startCreate()
        viewModel.updateFormInput(validInput(name = "配置甲"))
        viewModel.saveForm()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.form)
        val userConfig = viewModel.uiState.value.userConfigs.single()
        assertEquals("配置甲", userConfig.name)
        assertEquals(ProximityAlgorithm.BASELINE, userConfig.config.algorithm)
        assertEquals(1, store.observeConfigs().first().count { !it.builtIn })
        assertTrue(viewModel.uiState.value.message!!.contains("下一次模拟测试生效"))
    }

    @Test
    fun invalidThresholdsShowFieldErrorsAndDoNotSave() = runTest(dispatcher) {
        val store = InMemoryAlgorithmConfigStore()
        val viewModel = AlgorithmLabViewModel(store)
        advanceUntilIdle()

        viewModel.startCreate()
        viewModel.updateFormInput(
            validInput(name = "无效阈值").copy(
                enterThresholdDbm = "-80",
                exitThresholdDbm = "-60",
            ),
        )
        viewModel.saveForm()
        advanceUntilIdle()

        val form = requireNotNull(viewModel.uiState.value.form)
        assertTrue(form.fieldErrors.containsKey(AlgorithmConfigField.ENTER_THRESHOLD_DBM))
        assertTrue(form.fieldErrors.containsKey(AlgorithmConfigField.EXIT_THRESHOLD_DBM))
        assertTrue(store.observeConfigs().first().none { !it.builtIn })
    }

    @Test
    fun copyBuiltInEmaThenEditKeepsBuiltInUnchanged() = runTest(dispatcher) {
        val store = InMemoryAlgorithmConfigStore()
        val viewModel = AlgorithmLabViewModel(store)
        advanceUntilIdle()

        viewModel.startCopy(BuiltInAlgorithmConfigs.EMA.configId)
        assertEquals("内置 EMA 副本", viewModel.uiState.value.form?.input?.name)
        viewModel.updateFormInput(
            requireNotNull(viewModel.uiState.value.form).input.copy(
                name = "口袋",
                emaAlpha = "0.2",
                enterThresholdDbm = "-58",
            ),
        )
        viewModel.saveForm()
        advanceUntilIdle()

        val copied = viewModel.uiState.value.userConfigs.single()
        assertEquals("口袋", copied.name)
        assertEquals(0.2, copied.config.emaAlpha, 0.0)
        assertEquals(-58, copied.config.enterThresholdDbm)
        assertEquals(BuiltInAlgorithmConfigs.EMA, store.loadConfig("builtin-ema-v1"))

        viewModel.startEdit(copied.configId)
        viewModel.updateFormInput(
            requireNotNull(viewModel.uiState.value.form).input.copy(emaAlpha = "0.25"),
        )
        viewModel.saveForm()
        advanceUntilIdle()

        val edited = viewModel.uiState.value.userConfigs.single()
        assertEquals(0.25, edited.config.emaAlpha, 0.0)
        assertEquals(0.5, store.loadConfig("builtin-ema-v1")!!.config.emaAlpha, 0.0)
    }

    @Test
    fun selectThenDeleteCurrentConfigFallsBackToDefault() = runTest(dispatcher) {
        val store = InMemoryAlgorithmConfigStore()
        val viewModel = AlgorithmLabViewModel(store)
        advanceUntilIdle()

        viewModel.startCreate()
        viewModel.updateFormInput(validInput(name = "口袋"))
        viewModel.saveForm()
        advanceUntilIdle()
        val created = viewModel.uiState.value.userConfigs.single()

        viewModel.selectConfig(created.configId)
        advanceUntilIdle()
        assertEquals(created.configId, viewModel.uiState.value.selectedConfigId)

        viewModel.requestDelete(created.configId)
        assertEquals("口袋", viewModel.uiState.value.pendingDelete?.name)
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals(BuiltInAlgorithmConfigs.DEFAULT.configId, viewModel.uiState.value.selectedConfigId)
        assertTrue(viewModel.uiState.value.userConfigs.isEmpty())
        assertTrue(BuiltInAlgorithmConfigs.find(viewModel.uiState.value.selectedConfigId) != null)
    }

    @Test
    fun builtInConfigsCannotBeEditedOrDeleted() = runTest(dispatcher) {
        val store = InMemoryAlgorithmConfigStore()
        val viewModel = AlgorithmLabViewModel(store)
        advanceUntilIdle()

        viewModel.startEdit(BuiltInAlgorithmConfigs.BASELINE.configId)
        assertNull(viewModel.uiState.value.form)
        assertTrue(viewModel.uiState.value.message!!.contains("不可编辑"))

        viewModel.requestDelete(BuiltInAlgorithmConfigs.EMA.configId)
        assertNull(viewModel.uiState.value.pendingDelete)
        assertTrue(viewModel.uiState.value.message!!.contains("不可删除"))
        assertEquals(2, store.observeConfigs().first().size)
    }

    private fun validInput(name: String): AlgorithmConfigInput = AlgorithmConfigInput(
        name = name,
        algorithm = ProximityAlgorithm.BASELINE,
        enterThresholdDbm = "-60",
        exitThresholdDbm = "-80",
        confirmationKind = ConfirmationKind.CONSECUTIVE_SAMPLES,
        confirmationValue = "2",
        sampleValidityMillis = "5000",
        emaAlpha = "0.5",
    )
}
