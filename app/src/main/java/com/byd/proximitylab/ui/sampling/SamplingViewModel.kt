package com.byd.proximitylab.ui.sampling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.chart.RssiChartData
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.ReferenceMarkerResult
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionDetailBuilder
import com.byd.proximitylab.session.SessionDetailSnapshot
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionListFilter
import com.byd.proximitylab.session.SessionMarkerEvaluation
import com.byd.proximitylab.session.SessionMarkerRecorder
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionStartRequest
import com.byd.proximitylab.session.SessionStartResult
import com.byd.proximitylab.session.SessionStopResult
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionSummary
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.RssiStatistics
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.simulation.SimulationRunStore
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
 * 采样页表单。08 增加行走模式并沿用 04 的位置、可选距离与放置方式。
 */
data class SamplingFormState(
    val samplingType: SamplingType = SamplingType.FIXED_POINT,
    val name: String = SamplingType.FIXED_POINT.label,
    val location: FixedSampleLocation = FixedSampleLocation.FRONT,
    val phonePlacement: PhonePlacement = PhonePlacement.HANDHELD,
    val estimatedDistance: String = "",
)

/**
 * 已打开 Session 的详情。字段从只读 [SessionDetailSnapshot] 读取，保留 04 的字段名供既有调用方复用。
 */
data class SessionDetailState(
    val snapshot: SessionDetailSnapshot,
) {
    val session: SamplingSession get() = snapshot.session
    val observationCount: Int get() = snapshot.observationCount
    val timeline: List<SessionTimelineItem> get() = snapshot.timeline
    val markers: List<SessionReferenceMarker> get() = snapshot.markers
    val evaluation: SessionMarkerEvaluation get() = snapshot.evaluation
    val configSnapshot: SessionConfigSnapshot? get() = snapshot.configSnapshot
    val statistics: RssiStatistics? get() = snapshot.statistics
    val chart: RssiChartData get() = snapshot.chart
    val runEvents: List<SessionRunEvent> get() = snapshot.runEvents
    val runStates: List<SessionRunState> get() = snapshot.runStates
}

/**
 * Session 列表的设备筛选项。使用稳定 targetId，展示名称只作标签。
 */
data class SessionDeviceOption(
    val targetId: String,
    val label: String,
)

data class SamplingUiState(
    val binding: BoundTarget? = null,
    val form: SamplingFormState = SamplingFormState(),
    val activeSession: SamplingSession? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val filteredSessions: List<SessionSummary> = emptyList(),
    val filter: SessionListFilter = SessionListFilter(),
    val deviceOptions: List<SessionDeviceOption> = emptyList(),
    val detail: SessionDetailState? = null,
    val deleteCandidate: SessionSummary? = null,
    val message: String? = null,
    val droppedTargetEventCount: Int = 0,
) {
    /** 采集中可记录的人工标记类型。模拟测试只提供期望触发点。 */
    val activeMarkerKinds: List<ReferenceMarkerKind>
        get() = when (activeSession?.samplingType) {
            SamplingType.WALKING -> ReferenceMarkerKind.entries
            SamplingType.SIMULATION -> listOf(ReferenceMarkerKind.EXPECTED_TRIGGER)
            SamplingType.FIXED_POINT, null -> emptyList()
        }
}

/**
 * 采样页入口。定点/行走采样、人工标记、Session 筛选和详情管理都走真实 [SessionRecorder]、
 * [SessionStore] 与 [SessionMarkerRecorder] 实现，不经过 UI 状态反推存储。
 */
class SamplingViewModel(
    private val recorder: SessionRecorder,
    private val store: SessionStore,
    private val observationSource: TargetObservationSource,
    private val markerRecorder: SessionMarkerRecorder,
    private val simulationStore: SimulationRunStore? = null,
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
                _uiState.update { state -> applySessions(state, sessions) }
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

    fun selectSamplingType(type: SamplingType) {
        if (type == SamplingType.SIMULATION) {
            return
        }
        _uiState.update { state ->
            val previousDefault = state.form.samplingType.label
            val name = if (state.form.name.isBlank() || state.form.name.trim() == previousDefault) {
                type.label
            } else {
                state.form.name
            }
            state.copy(form = state.form.copy(samplingType = type, name = name))
        }
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
        val type = state.form.samplingType
        if (type == SamplingType.SIMULATION) {
            setMessage("模拟测试请从实时监控页开始。")
            return
        }
        val name = state.form.name.trim().ifEmpty { type.label }
        val request = SessionStartRequest(
            binding = binding,
            samplingType = type,
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

    /**
     * 记录一次实时人工参考标记。标记与原始观测使用同一会话时基，只写入独立标记表。
     */
    fun addReferenceMarker(kind: ReferenceMarkerKind) {
        val active = _uiState.value.activeSession ?: run {
            setMessage("当前没有采集中 Session，不能记录实时标记。")
            return
        }
        viewModelScope.launch {
            when (val result = markerRecorder.addMarker(active.id, kind)) {
                is ReferenceMarkerResult.Recorded -> {
                    val offset = formatSessionDuration(
                        (result.marker.atEpochMillis - active.startedAtEpochMillis).coerceAtLeast(0L),
                    )
                    setMessage("已记录「${kind.label}」，会话 +$offset。原始观测不变。")
                    refreshDetailIfOpen(active.id)
                }

                ReferenceMarkerResult.MissingSession -> setMessage("Session 已不存在，无法记录标记。")
                ReferenceMarkerResult.NotRecording -> setMessage("Session 已结束，不能再记录实时标记。")
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

    fun renameSession(sessionId: String, name: String) {
        viewModelScope.launch {
            val updated = store.renameSession(sessionId, name)
            if (updated == null) {
                setMessage("Session 名称不能为空，且 Session 必须存在。")
            } else {
                syncActiveSession(updated)
                setMessage("已更新 Session 名称，原始观测不变。")
                refreshDetailIfOpen(sessionId)
            }
        }
    }

    fun setEvaluationLabel(sessionId: String, label: SessionEvaluationLabel) {
        viewModelScope.launch {
            val updated = store.updateEvaluationLabel(sessionId, label)
            if (updated == null) {
                setMessage("Session 不存在，无法更新人工标签。")
            } else {
                syncActiveSession(updated)
                setMessage("已把整次试验标签改为「${label.label}」；标记只用于评价，原始观测不变。")
                refreshDetailIfOpen(sessionId)
            }
        }
    }

    fun deleteReferenceMarker(markerId: String) {
        viewModelScope.launch {
            val detailId = _uiState.value.detail?.session?.id
            if (store.deleteReferenceMarker(markerId)) {
                setMessage("已删除人工标记，原始观测不变。")
                detailId?.let { refreshDetailIfOpen(it) }
            } else {
                setMessage("人工标记不存在或已被删除。")
            }
        }
    }

    fun requestDeleteSession(sessionId: String) {
        val state = _uiState.value
        if (state.activeSession?.id == sessionId) {
            setMessage("采集中 Session 不能删除，请先停止。")
            return
        }
        val candidate = state.sessions.firstOrNull { summary -> summary.session.id == sessionId }
        if (candidate == null) {
            setMessage("Session 不存在。")
            return
        }
        _uiState.update { current -> current.copy(deleteCandidate = candidate) }
    }

    fun confirmDeleteSession() {
        val candidate = _uiState.value.deleteCandidate ?: return
        if (_uiState.value.activeSession?.id == candidate.session.id) {
            setMessage("采集中 Session 不能删除，请先停止。")
            _uiState.update { state -> state.copy(deleteCandidate = null) }
            return
        }
        viewModelScope.launch {
            store.deleteSession(candidate.session.id)
            val detailId = _uiState.value.detail?.session?.id
            _uiState.update { state ->
                state.copy(
                    deleteCandidate = null,
                    detail = if (detailId == candidate.session.id) null else state.detail,
                    message = "已删除 Session「${candidate.session.name}」。",
                )
            }
        }
    }

    fun dismissDeleteSession() {
        _uiState.update { state -> state.copy(deleteCandidate = null) }
    }

    fun selectSamplingTypeFilter(type: SamplingType?) {
        updateFilter { filter -> filter.copy(samplingType = type) }
    }

    fun selectLocationFilter(location: FixedSampleLocation?) {
        updateFilter { filter -> filter.copy(location = location) }
    }

    fun selectDeviceFilter(targetId: String?) {
        updateFilter { filter -> filter.copy(targetId = targetId) }
    }

    fun clearFilters() {
        updateFilter { SessionListFilter() }
    }

    fun clearMessage() {
        _uiState.update { state -> state.copy(message = null) }
    }

    private fun updateFilter(transform: (SessionListFilter) -> SessionListFilter) {
        _uiState.update { state -> applySessions(state.copy(filter = transform(state.filter)), state.sessions) }
    }

    private fun applySessions(
        state: SamplingUiState,
        sessions: List<SessionSummary>,
    ): SamplingUiState {
        val filtered = state.filter.apply(sessions)
        val deviceOptions = sessions
            .map { summary ->
                SessionDeviceOption(
                    targetId = summary.session.targetId,
                    label = summary.session.targetDisplayName ?: summary.session.targetAddress,
                )
            }
            .distinctBy { option -> option.targetId }
            .sortedBy { option -> option.label }
        val deleteCandidate = state.deleteCandidate?.let { candidate ->
            sessions.firstOrNull { summary -> summary.session.id == candidate.session.id }
        }
        // 从存储中消失的 Session 不应继续占据详情。
        val detail = state.detail?.let { detail ->
            if (sessions.any { summary -> summary.session.id == detail.session.id }) {
                detail
            } else {
                null
            }
        }
        return state.copy(
            sessions = sessions,
            filteredSessions = filtered,
            deviceOptions = deviceOptions,
            deleteCandidate = deleteCandidate,
            detail = detail,
        )
    }

    private suspend fun loadDetail(sessionId: String) {
        val session = store.loadSession(sessionId)
        if (session == null) {
            _uiState.update { state -> state.copy(detail = null) }
            setMessage("Session 不存在或已被删除。")
            return
        }
        val snapshot = SessionDetailBuilder.build(
            session = session,
            timeline = store.loadTimeline(sessionId),
            markers = store.loadReferenceMarkers(sessionId),
            configSnapshot = store.loadConfigSnapshot(sessionId),
            runEvents = simulationStore?.loadEvents(sessionId).orEmpty(),
            runStates = simulationStore?.loadStates(sessionId).orEmpty(),
        )
        _uiState.update { state -> state.copy(detail = SessionDetailState(snapshot)) }
    }

    private suspend fun refreshDetailIfOpen(sessionId: String) {
        if (_uiState.value.detail?.session?.id == sessionId) {
            loadDetail(sessionId)
        }
    }

    /**
     * 改名/改标签后同步正在采集的 Session 展示对象。SessionRecorder 的 activeSession 只在事件到达时更新，
     * 不应让列表和活动卡片在纯元数据编辑后短暂显示旧值。
     */
    private fun syncActiveSession(updated: SamplingSession) {
        _uiState.update { state ->
            state.copy(
                activeSession = state.activeSession?.let { active ->
                    if (active.id == updated.id) updated else active
                },
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
