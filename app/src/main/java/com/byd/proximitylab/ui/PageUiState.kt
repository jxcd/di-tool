package com.byd.proximitylab.ui

import com.byd.proximitylab.ui.navigation.LabDestination

/**
 * 页面通过 ViewModel 暴露给 Compose 的可观察状态。
 */
data class PageUiState(
    val title: String,
    val description: String,
)

fun LabDestination.toPageUiState(): PageUiState = PageUiState(
    title = headline,
    description = description,
)
