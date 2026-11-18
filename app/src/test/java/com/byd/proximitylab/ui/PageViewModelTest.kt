package com.byd.proximitylab.ui

import com.byd.proximitylab.ui.lab.AlgorithmLabViewModel
import com.byd.proximitylab.ui.monitor.MonitorViewModel
import com.byd.proximitylab.ui.navigation.LabDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageViewModelTest {

    @Test
    fun everyPlaceholderViewModelExposesItsOwnNonBlankState() {
        val states = listOf(
            MonitorViewModel().uiState.value,
            AlgorithmLabViewModel().uiState.value,
        )

        assertEquals(2, states.map(PageUiState::title).toSet().size)
        assertTrue(states.all { it.title.isNotBlank() })
        assertTrue(states.all { it.description.isNotBlank() })
    }

    @Test
    fun placeholderViewModelStatesMatchTheirDestinationCopy() {
        assertEquals(LabDestination.Monitor.headline, MonitorViewModel().uiState.value.title)
        assertEquals(LabDestination.Lab.headline, AlgorithmLabViewModel().uiState.value.title)
    }
}
