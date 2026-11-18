package com.byd.proximitylab.ui

import com.byd.proximitylab.ui.lab.AlgorithmLabViewModel
import com.byd.proximitylab.ui.navigation.LabDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageViewModelTest {

    @Test
    fun placeholderViewModelExposesNonBlankState() {
        val state = AlgorithmLabViewModel().uiState.value

        assertTrue(state.title.isNotBlank())
        assertTrue(state.description.isNotBlank())
    }

    @Test
    fun placeholderViewModelStateMatchesItsDestinationCopy() {
        assertEquals(LabDestination.Lab.headline, AlgorithmLabViewModel().uiState.value.title)
    }
}
