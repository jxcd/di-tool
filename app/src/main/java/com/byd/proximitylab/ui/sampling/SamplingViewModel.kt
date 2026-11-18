package com.byd.proximitylab.ui.sampling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStartRequest
import com.byd.proximitylab.session.SessionStartResult
import com.byd.proximitylab.session.SessionStopResult
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionSummary
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.TargetObservationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 采样页表单。04 只提供定点采样；08 在此之上增加行走采样与人工标记。
 */
data class SamplingFormState(
    val name: String = "定点采样",
    val location: FixedSampleLocation = FixedSampleLocation.FRONT,
    val phonePlacement: PhonePlacement = PhonePlacement.HANDHELD,
    val estimatedDistance: String = "",
)

/**
 * 已打开 Session 的详情。
 */
data class SessionDetailState(
    val session: SamplingSession,
    val observationCount: Int,
    val timeline: List<SessionTimelineItem>,
)

data class SamplingUiState(
    val binding: BoundTarget? = null,
    val form: SamplingFormState = SamplingFormState(),
    val activeSession: SamplingSession? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val detail: SessionDetailState? = null,
    val message: String? = null,
    val droppedTargetEventCount: Int = 0,
)

/**
 * 定点采样的界面入口。所有存储和会话生命周期都走 [SessionRecorder] 与 [SessionStore] 的真实实现。
 */
class SamplingViewModel(
    private val recorder: SessionRecorder,
    private val store: SessionStore,
    private val observationSource: TargetObservationSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SamplingUiState())
    val uiState: StateFlow<SamplingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observationSource.state.collect { monitor ->
                _uiState.update { state ->
                    state.copy(
                        binding = monitor.binding,
                        droppedTargetEventCount = monitor.droppedTargetEventCount,
                    )
                }
            }
        }
        viewModelScope.launch {
            store.observeSessions().collect { sessions ->
                _uiState.update { state -> state.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            recorder.activeSession.collect { active ->
                _uiState.update { state -> state.copy(activeSession = active) }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { state -> state.copy(form = state.form.copy(name = name)) }
    }

    fun selectLocation(location: FixedSampleLocation) {
        _uiState.update { state -> state.copy(form = state.form.copy(location = location)) }
    }

    fun selectPhonePlacement(placement: PhonePlacement) {
        _uiState.update { state -> state.copy(form = state.form.copy(phonePlacement = placement)) }
    }

    fun updateEstimatedDistance(value: String) {
        _uiState.update { state -> state.copy(form = state.form.copy(estimatedDistance = value)) }
    }

    fun startSampling() {
        val state = _uiState.value
        val binding = state.binding ?: run {
            setMessage("请先在设备页绑定目标。")
            return
        }
        val distance = parseEstimatedDistance(state.form.estimatedDistance)
        if (distance.isFailure) {
            setMessage(distance.exceptionOrNull()?.message ?: "估计距离无效。")
            return
        }
        val name = state.form.name.trim().ifEmpty { SamplingType.FIXED_POINT.label }
        val request = SessionStartRequest(
            binding = binding,
            samplingType = SamplingType.FIXED_POINT,
            name = name,
            location = state.form.location,
            estimatedDistanceMeters = distance.getOrNull(),
            phonePlacement = state.form.phonePlacement,
        )
        viewModelScope.launch {
            when (val result = recorder.start(request)) {
                is SessionStartResult.Started -> setMessage("已开始采集，扫描由当前 Session 控制。")
                is SessionStartResult.AlreadyRecording -> setMessage("已有采集中 Session，请先停止。")
            }
        }
    }

    fun stopSampling() {
        viewModelScope.launch {
            when (val result = recorder.stop()) {
                is SessionStopResult.Stopped -> {
                    setMessage("已保存 Session，可从列表重新打开。")
                    val detail = _uiState.value.detail
                    if (detail?.session?.id == result.session.id) {
                        loadDetail(result.session.id)
                    }
                }

                SessionStopResult.NotRecording -> setMessage("当前没有采集中 Session。")
            }
        }
    }

    fun openSession(sessionId: String) {
        viewModelScope.launch {
            loadDetail(sessionId)
        }
    }

    fun closeDetail() {
        _uiState.update { state -> state.copy(detail = null) }
    }

    fun clearMessage() {
        _uiState.update { state -> state.copy(message = null) }
    }

    private suspend fun loadDetail(sessionId: String) {
        val session = store.loadSession(sessionId) ?: return
        val timeline = store.loadTimeline(sessionId)
        _uiState.update { state ->
            state.copy(
                detail = SessionDetailState(
                    session = session,
                    observationCount = timeline.count { item ->
                        item.event is SessionTimelineEvent.Observation
                    },
                    timeline = timeline,
                ),
            )
        }
    }

    private fun setMessage(message: String) {
        _uiState.update { state -> state.copy(message = message) }
    }

    private fun parseEstimatedDistance(raw: String): Result<Double?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.success(null)
        }
        val value = trimmed.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("估计距离必须是数字。"))
        if (!value.isFinite() || value < 0.0) {
            return Result.failure(IllegalArgumentException("估计距离不能为负数。"))
        }
        return Result.success(value)
    }

    /**
     * 当前用于展示的时间。调用方在组合或测试中显式读取，避免 UI 依赖隐藏的系统时钟。
     */
    fun nowEpochMillis(): Long = clock()
}

internal fun formatSessionDuration(durationMillis: Long): String {
    val safe = durationMillis.coerceAtLeast(0L)
    val totalSeconds = safe / 1_000L
    val tenths = (safe % 1_000L) / 100L
    return if (totalSeconds < 60L) {
        "$totalSeconds.$tenths 秒"
    } else {
        "%d 分 %02d 秒".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
    }
}

private val sessionDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

internal fun formatSessionDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(sessionDateTimeFormatter)
