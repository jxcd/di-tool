package com.byd.proximitylab.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.session.BuiltInSessionConfigs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 实时监控与模拟测试入口。控制器位于应用级作用域，ViewModel 重建只重新订阅状态，不结束运行中的 Session。
 *
 * 06 起，模拟测试在点时读取算法配置存储的当前生效项并生成独立快照；运行中的测试始终使用开始时
 * 固定的快照，测试期间编辑配置只影响下一次测试。未接入配置存储时沿用 05 的内置默认配置。
 */
class MonitorViewModel(
    private val controller: LiveMonitorController,
    private val configStore: AlgorithmConfigStore? = null,
) : ViewModel() {

    val uiState: StateFlow<LiveMonitorState> = controller.state

    init {
        controller.startMonitoring()
    }

    fun startSimulation() {
        viewModelScope.launch {
            startSimulationNow()
        }
    }

    /**
     * 可在测试中直接等待的启动入口。先固定当前生效配置快照，再交给 05 的模拟测试控制器。
     */
    suspend fun startSimulationNow() {
        val snapshot = configStore?.snapshotForNextSimulation() ?: BuiltInSessionConfigs.DEFAULT
        controller.startSimulation(snapshot)
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
