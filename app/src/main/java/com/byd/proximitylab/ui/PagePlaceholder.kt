package com.byd.proximitylab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.byd.proximitylab.ui.navigation.LabDestination

/**
 * 四页共用空状态。后续票用真实内容替换各页面时保留页面根节点与测试标识。
 */
@Composable
fun PagePlaceholder(
    destination: LabDestination,
    uiState: PageUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = uiState.title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = uiState.description,
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "待接入",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = destination.pendingWork,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
