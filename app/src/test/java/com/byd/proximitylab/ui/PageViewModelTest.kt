package com.byd.proximitylab.ui

import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.algorithmconfig.InMemoryAlgorithmConfigStore
import com.byd.proximitylab.ui.lab.AlgorithmLabViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageViewModelTest {

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
    fun labViewModelExposesBuiltInAlgorithmsAndDefaultSelection() = runTest(dispatcher) {
        val viewModel = AlgorithmLabViewModel(InMemoryAlgorithmConfigStore())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            listOf("builtin-baseline-v1", "builtin-ema-v1"),
            state.builtInConfigs.map { it.configId },
        )
        assertEquals(BuiltInAlgorithmConfigs.DEFAULT.configId, state.selectedConfigId)
        assertTrue(state.userConfigs.isEmpty())
    }
}
