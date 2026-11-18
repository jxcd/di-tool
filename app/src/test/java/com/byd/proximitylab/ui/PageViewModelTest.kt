package com.byd.proximitylab.ui

import androidx.lifecycle.ViewModel
import com.byd.proximitylab.ui.lab.AlgorithmLabViewModel
import com.byd.proximitylab.ui.monitor.MonitorViewModel
import com.byd.proximitylab.ui.navigation.LabDestination
import com.byd.proximitylab.ui.sampling.SamplingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageViewModelTest {

    @Test
    fun everyPlaceholderViewModelExposesItsOwnNonBlankState() {
        val viewModels: List<ViewModel> = listOf(
            MonitorViewModel(),
            SamplingViewModel(),
            AlgorithmLabViewModel(),
        )

        val states = viewModels.map { viewModel ->
            when (viewModel) {
                is MonitorViewModel -> viewModel.uiState.value
                is SamplingViewModel -> viewModel.uiState.value
                is AlgorithmLabViewModel -> viewModel.uiState.value
                else -> error("Unexpected view model: $viewModel")
            }
        }

        assertEquals(3, states.map(PageUiState::title).toSet().size)
        assertTrue(states.all { it.title.isNotBlank() })
        assertTrue(states.all { it.description.isNotBlank() })
    }

    @Test
    fun placeholderViewModelStatesMatchTheirDestinationCopy() {
        assertEquals(LabDestination.Monitor.headline, MonitorViewModel().uiState.value.title)
        assertEquals(LabDestination.Sampling.headline, SamplingViewModel().uiState.value.title)
        assertEquals(LabDestination.Lab.headline, AlgorithmLabViewModel().uiState.value.title)
    }
}
