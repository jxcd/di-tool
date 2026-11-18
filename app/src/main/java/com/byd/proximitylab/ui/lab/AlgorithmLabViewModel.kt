package com.byd.proximitylab.ui.lab

import androidx.lifecycle.ViewModel
import com.byd.proximitylab.ui.PageUiState
import com.byd.proximitylab.ui.navigation.LabDestination
import com.byd.proximitylab.ui.toPageUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlgorithmLabViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(LabDestination.Lab.toPageUiState())

    val uiState: StateFlow<PageUiState> = _uiState.asStateFlow()
}
