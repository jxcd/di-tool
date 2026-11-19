package com.byd.proximitylab.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.live.LiveMonitorController
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.ReferenceMarkerResult
import com.byd.proximitylab.session.SessionMarkerRecorder
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
    private val markerRecorder: SessionMarkerRecorder? = null,
) : ViewModel() {

    val uiState: StateFlow<LiveMonitorState> = controller.state

    private val selectedWindow = MutableStateFlow(RssiChartWindow.DEFAULT)

    private val _markerMessage = MutableStateFlow<String?>(null)
    val markerMessage: StateFlow<String?> = _markerMessage.asStateFlow()

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
        _markerMessage.value = null
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
        _markerMessage.value = null
        viewModelScope.launch {
            controller.stopSimulation()
        }
    }

    /**
     * 在运行中的模拟测试里记录一次「到达期望触发位置」人工标记。
     *
     * 标记与模拟测试 Session 使用同一时基，只写入标记表，不改变判定输入或原始时间线。
     */
    fun markExpectedTrigger() {
        val state = controller.state.value
        // 控制器状态与共享会话状态可能相差一个调度点；测试视图持有本次 Session，可安全兜底。
        val active = state.activeSession ?: state.test?.session
        val recorder = markerRecorder
        when {
            active == null || !state.simulationRunning ->
                _markerMessage.value = "当前没有运行中的模拟测试，不能记录期望触发位置。"

            recorder == null ->
                _markerMessage.value = "人工标记入口不可用。"

            else -> viewModelScope.launch {
                when (val result = recorder.addMarker(active.id, ReferenceMarkerKind.EXPECTED_TRIGGER)) {
                    is ReferenceMarkerResult.Recorded -> {
                        val offsetMillis =
                            (result.marker.atEpochMillis - active.startedAtEpochMillis).coerceAtLeast(0L)
                        _markerMessage.value =
                            "已记录「到达期望触发位置」，会话 +${formatMarkerOffset(offsetMillis)}。" +
                                "标记只用于评价，原始观测不变。"
                    }

                    ReferenceMarkerResult.MissingSession ->
                        _markerMessage.value = "模拟测试 Session 已不存在，无法记录标记。"

                    ReferenceMarkerResult.NotRecording ->
                        _markerMessage.value = "模拟测试已结束，不能再记录实时标记。"
                }
            }
        }
    }

    fun clearMarkerMessage() {
        _markerMessage.value = null
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

private fun formatMarkerOffset(durationMillis: Long): String {
    val safe = durationMillis.coerceAtLeast(0L)
    val totalSeconds = safe / 1_000L
    val tenths = (safe % 1_000L) / 100L
    return if (totalSeconds < 60L) {
        "$totalSeconds.$tenths 秒"
    } else {
        "%d 分 %02d 秒".format(java.util.Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
    }
}
