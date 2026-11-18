package com.byd.proximitylab.ui.device

import androidx.lifecycle.ViewModel
import com.byd.proximitylab.ui.PageUiState
import com.byd.proximitylab.ui.navigation.LabDestination
import com.byd.proximitylab.ui.toPageUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(LabDestination.Device.toPageUiState())

    val uiState: StateFlow<PageUiState> = _uiState.asStateFlow()
}
