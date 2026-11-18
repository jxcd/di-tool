package com.byd.proximitylab.ui.monitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.PagePlaceholder
import com.byd.proximitylab.ui.navigation.LabDestination

@Composable
fun MonitorScreen(modifier: Modifier = Modifier) {
    val viewModel: MonitorViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PagePlaceholder(
        destination = LabDestination.Monitor,
        uiState = uiState,
        modifier = modifier.testTag(LabDestination.Monitor.screenTestTag),
    )
}
