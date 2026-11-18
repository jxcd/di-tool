package com.byd.proximitylab.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.live.LiveMonitorState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 实时监控与模拟测试入口。控制器位于应用级作用域，ViewModel 重建只重新订阅状态，不结束运行中的 Session。
 */
class MonitorViewModel(
    private val controller: LiveMonitorController,
) : ViewModel() {

    val uiState: StateFlow<LiveMonitorState> = controller.state

    init {
        controller.startMonitoring()
    }

    fun startSimulation() {
        viewModelScope.launch {
            controller.startSimulation()
        }
    }

    fun stopSimulation() {
        viewModelScope.launch {
            controller.stopSimulation()
        }
    }

    fun clearMessage() {
        controller.clearMessage()
    }
}
