package com.byd.proximitylab.ui

import androidx.lifecycle.ViewModel
import com.byd.proximitylab.ui.device.DeviceViewModel
import com.byd.proximitylab.ui.lab.AlgorithmLabViewModel
import com.byd.proximitylab.ui.monitor.MonitorViewModel
import com.byd.proximitylab.ui.navigation.LabDestination
import com.byd.proximitylab.ui.sampling.SamplingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageViewModelTest {

    @Test
    fun everyPageViewModelExposesItsOwnNonBlankState() {
        val viewModels: List<ViewModel> = listOf(
            DeviceViewModel(),
            MonitorViewModel(),
            SamplingViewModel(),
            AlgorithmLabViewModel(),
        )

        val states = viewModels.map { viewModel ->
            when (viewModel) {
                is DeviceViewModel -> viewModel.uiState.value
                is MonitorViewModel -> viewModel.uiState.value
                is SamplingViewModel -> viewModel.uiState.value
                is AlgorithmLabViewModel -> viewModel.uiState.value
                else -> error("Unexpected view model: $viewModel")
            }
        }

        assertEquals(4, states.map(PageUiState::title).toSet().size)
        assertTrue(states.all { it.title.isNotBlank() })
        assertTrue(states.all { it.description.isNotBlank() })
    }

    @Test
    fun viewModelStatesMatchTheirDestinationCopy() {
        assertEquals(LabDestination.Device.headline, DeviceViewModel().uiState.value.title)
        assertEquals(LabDestination.Monitor.headline, MonitorViewModel().uiState.value.title)
        assertEquals(LabDestination.Sampling.headline, SamplingViewModel().uiState.value.title)
        assertEquals(LabDestination.Lab.headline, AlgorithmLabViewModel().uiState.value.title)
    }
}
