package com.byd.proximitylab.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.TargetMonitorState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 设备页只负责触发扫描和人工绑定操作，目标观测与身份状态由共享的 [TargetMonitor] 维护。
 */
class DeviceViewModel(
    private val monitor: TargetMonitor,
) : ViewModel() {

    val uiState: StateFlow<TargetMonitorState> = monitor.state

    fun startScan() {
        monitor.startScan()
    }

    fun stopScan() {
        monitor.stopScan()
    }

    fun bind(address: String) {
        viewModelScope.launch {
            monitor.bind(address)
        }
    }

    fun reconfirmAsOriginal(address: String) {
        viewModelScope.launch {
            monitor.reconfirmAsOriginal(address)
        }
    }

    fun rebindTo(address: String) {
        viewModelScope.launch {
            monitor.rebindTo(address)
        }
    }

    fun clearBinding() {
        viewModelScope.launch {
            monitor.clearBinding()
        }
    }
}
