package com.byd.proximitylab.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.simulation.SimulationEventHistory
import com.byd.proximitylab.simulation.SimulationEventHistoryReader
import com.byd.proximitylab.simulation.SimulationRunStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 实时监控与模拟测试入口。控制器位于应用级作用域，ViewModel 重建只重新订阅状态，不结束运行中的 Session。
 *
 * 06 起，模拟测试在点时读取算法配置存储的当前生效项并生成独立快照；运行中的测试始终使用开始时
 * 固定的快照，测试期间编辑配置只影响下一次测试。未接入配置存储时沿用 05 的内置默认配置。
 *
 * 图表状态和事件历史是独立的展示流：时间窗裁剪与抽点只作用于 [chartState]；事件历史从
 * [SessionStore]/[SimulationRunStore] 读取已持久化记录，不从 UI 展示状态反推 Session。
 */
class MonitorViewModel(
    private val controller: LiveMonitorController,
    private val configStore: AlgorithmConfigStore? = null,
    sessionStore: SessionStore? = null,
    simulationStore: SimulationRunStore? = null,
) : ViewModel() {

    val uiState: StateFlow<LiveMonitorState> = controller.state

    private val selectedWindow = MutableStateFlow(RssiChartWindow.DEFAULT)

    val chartState: StateFlow<MonitorChartState> =
        combine(controller.state, selectedWindow, ::buildChartState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = buildChartState(controller.state.value, selectedWindow.value),
            )

    val window: StateFlow<RssiChartWindow> = selectedWindow.asStateFlow()

    val eventHistory: StateFlow<SimulationEventHistory> =
        if (sessionStore != null && simulationStore != null) {
            SimulationEventHistoryReader(
                sessionStore = sessionStore,
                simulationStore = simulationStore,
            ).observeLatest().stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = SimulationEventHistory.EMPTY,
            )
        } else {
            MutableStateFlow(SimulationEventHistory.EMPTY)
        }

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

    /** 切换近期时间窗。只影响图表展示，不改变判定、Session 记录或事件历史。 */
    fun selectWindow(window: RssiChartWindow) {
        selectedWindow.value = window
    }

    fun clearMessage() {
        controller.clearMessage()
    }

    private fun buildChartState(
        state: LiveMonitorState,
        window: RssiChartWindow,
    ): MonitorChartState = MonitorChartSource.build(state, window)
}
