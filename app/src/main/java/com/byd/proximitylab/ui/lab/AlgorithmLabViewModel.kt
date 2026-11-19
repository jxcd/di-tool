package com.byd.proximitylab.ui.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.algorithmconfig.AlgorithmConfig
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigField
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigInput
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigValidator
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.comparison.ComparisonSessionContext
import com.byd.proximitylab.comparison.ComparisonTarget
import com.byd.proximitylab.comparison.ScoringWindow
import com.byd.proximitylab.comparison.SessionComparison
import com.byd.proximitylab.comparison.SessionComparisonResult
import com.byd.proximitylab.comparison.SessionComparisonService
import com.byd.proximitylab.session.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * 配置编辑表单。数字字段保留字符串，以便把解析和边界错误对准具体输入框。
 */
data class AlgorithmConfigFormState(
    val editingConfigId: String? = null,
    val input: AlgorithmConfigInput = AlgorithmConfigInput(),
    val fieldErrors: Map<AlgorithmConfigField, String> = emptyMap(),
) {
    val editing: Boolean
        get() = editingConfigId != null
}

/**
 * 10 的 Session 对比评分状态。Session 列表、选中的人工参考、多选配置和上次结果都保留在这里，
 * 运行入口走真实 [SessionComparisonService]。
 */
data class ComparisonUiState(
    val available: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val context: ComparisonSessionContext? = null,
    val contextLoading: Boolean = false,
    val selectedTargets: Set<ComparisonTarget> = emptySet(),
    val earlyToleranceSeconds: String = DEFAULT_EARLY_TOLERANCE_SECONDS,
    val lateToleranceSeconds: String = DEFAULT_LATE_TOLERANCE_SECONDS,
    val windowError: String? = null,
    val running: Boolean = false,
    val result: SessionComparison? = null,
    val message: String? = null,
) {
    val selectedSession: SessionSummary?
        get() = sessions.firstOrNull { summary -> summary.session.id == selectedSessionId }

    companion object {
        const val DEFAULT_EARLY_TOLERANCE_SECONDS: String = "1.0"
        const val DEFAULT_LATE_TOLERANCE_SECONDS: String = "2.0"
    }
}

/**
 * 06 算法实验室页面状态。内置算法始终存在；[selectedConfigId] 指向下一次模拟测试将使用的配置。
 */
data class AlgorithmLabUiState(
    val configs: List<AlgorithmConfig> = BuiltInAlgorithmConfigs.ALL,
    val selectedConfigId: String = BuiltInAlgorithmConfigs.DEFAULT.configId,
    val form: AlgorithmConfigFormState? = null,
    val detail: AlgorithmConfig? = null,
    val pendingDelete: AlgorithmConfig? = null,
    val message: String? = null,
    val comparison: ComparisonUiState = ComparisonUiState(),
) {
    val selectedConfig: AlgorithmConfig
        get() = configs.firstOrNull { it.configId == selectedConfigId }
            ?: BuiltInAlgorithmConfigs.DEFAULT

    val builtInConfigs: List<AlgorithmConfig>
        get() = configs.filter { it.builtIn }

    val userConfigs: List<AlgorithmConfig>
        get() = configs.filterNot { it.builtIn }
}

/**
 * 算法配置管理入口。所有保存、选择与删除都走 [AlgorithmConfigStore] 的真实实现，页面只负责展示和表单状态。
 */
class AlgorithmLabViewModel(
    private val store: AlgorithmConfigStore,
    private val comparisonService: SessionComparisonService? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AlgorithmLabUiState(
            comparison = ComparisonUiState(available = comparisonService != null),
        ),
    )
    val uiState: StateFlow<AlgorithmLabUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.observeConfigs().collect { configs ->
                _uiState.update { state -> state.copy(configs = configs) }
            }
        }
        viewModelScope.launch {
            store.observeSelectedConfigId().collect { selectedId ->
                _uiState.update { state -> state.copy(selectedConfigId = selectedId) }
            }
        }
        val service = comparisonService
        if (service != null) {
            viewModelScope.launch {
                service.observeSessions().collect { sessions ->
                    _uiState.update { state -> applyComparisonSessions(state, sessions) }
                }
            }
        }
    }

    fun startCreate() {
        _uiState.update { state ->
            state.copy(
                form = AlgorithmConfigFormState(input = AlgorithmConfigInput()),
                message = null,
            )
        }
    }

    fun startEdit(configId: String) {
        val config = _uiState.value.configs.firstOrNull { it.configId == configId } ?: return
        if (config.builtIn) {
            setMessage("内置配置不可编辑，请先复制为自定义配置。")
            return
        }
        _uiState.update { state ->
            state.copy(
                form = AlgorithmConfigFormState(
                    editingConfigId = config.configId,
                    input = AlgorithmConfigInput.fromConfig(config),
                ),
                message = null,
            )
        }
    }

    fun startCopy(configId: String) {
        val source = _uiState.value.configs.firstOrNull { it.configId == configId } ?: return
        _uiState.update { state ->
            state.copy(
                form = AlgorithmConfigFormState(
                    input = AlgorithmConfigInput.fromConfig(source).copy(
                        name = "${source.name} 副本",
                    ),
                ),
                message = null,
            )
        }
    }

    fun updateFormInput(input: AlgorithmConfigInput) {
        _uiState.update { state ->
            state.copy(form = state.form?.copy(input = input))
        }
    }

    fun cancelForm() {
        _uiState.update { state -> state.copy(form = null) }
    }

    /**
     * 校验并保存表单。存在字段错误时只更新错误提示，不写存储；保存成功后关闭表单并提示下次测试生效。
     */
    fun saveForm() {
        val form = _uiState.value.form ?: return
        val validation = AlgorithmConfigValidator.validate(form.input)
        if (!validation.isValid) {
            _uiState.update { state ->
                state.copy(form = state.form?.copy(fieldErrors = validation.errors))
            }
            return
        }
        val name = requireNotNull(validation.name)
        val config = requireNotNull(validation.config)
        val editingConfigId = form.editingConfigId
        viewModelScope.launch {
            val saved = if (editingConfigId == null) {
                store.createConfig(name, config)
            } else {
                store.updateConfig(editingConfigId, name, config)
            }
            _uiState.update { state ->
                if (saved == null) {
                    state.copy(message = "配置已不存在或不可编辑，未保存修改。")
                } else if (state.form == form) {
                    state.copy(
                        form = null,
                        message = "已保存配置「${saved.name}」，修改从下一次模拟测试生效。",
                    )
                } else {
                    state.copy(
                        message = "已保存配置「${saved.name}」，修改从下一次模拟测试生效。",
                    )
                }
            }
        }
    }

    fun selectConfig(configId: String) {
        val name = _uiState.value.configs.firstOrNull { it.configId == configId }?.name
            ?: return
        viewModelScope.launch {
            val selected = store.selectConfig(configId)
            setMessage(
                if (selected) {
                    "已将「$name」设为下一次模拟测试的生效配置。"
                } else {
                    "配置已不存在，未改变生效配置。"
                },
            )
        }
    }

    fun showDetail(configId: String) {
        val config = _uiState.value.configs.firstOrNull { it.configId == configId } ?: return
        _uiState.update { state -> state.copy(detail = config) }
    }

    fun dismissDetail() {
        _uiState.update { state -> state.copy(detail = null) }
    }

    fun requestDelete(configId: String) {
        val config = _uiState.value.configs.firstOrNull { it.configId == configId } ?: return
        if (config.builtIn) {
            setMessage("内置配置不可删除。")
            return
        }
        _uiState.update { state -> state.copy(pendingDelete = config) }
    }

    fun cancelDelete() {
        _uiState.update { state -> state.copy(pendingDelete = null) }
    }

    /**
     * 删除确认。删除当前生效配置时，存储层在同一事务中回退到可用默认项。
     */
    fun confirmDelete() {
        val target = _uiState.value.pendingDelete ?: return
        val wasSelected = _uiState.value.selectedConfigId == target.configId
        viewModelScope.launch {
            val deleted = store.deleteConfig(target.configId)
            _uiState.update { state ->
                state.copy(
                    pendingDelete = null,
                    message = when {
                        !deleted -> "配置已不存在，未删除任何内容。"
                        wasSelected -> "已删除配置「${target.name}」，当前生效项回退为" +
                            "「${BuiltInAlgorithmConfigs.DEFAULT.name}」。已保存 Session 的配置快照不受影响。"

                        else -> "已删除配置「${target.name}」。已保存 Session 的配置快照不受影响。"
                    },
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { state -> state.copy(message = null) }
    }

    /**
     * 选择用于对比的 Session。切换后重新读取人工参考与当次配置，并给出一组默认配置选择。
     */
    fun selectComparisonSession(sessionId: String) {
        val service = comparisonService ?: return
        _uiState.update { state ->
            state.copy(
                comparison = state.comparison.copy(
                    selectedSessionId = sessionId,
                    context = null,
                    contextLoading = true,
                    result = null,
                    message = null,
                    windowError = null,
                    running = false,
                ),
            )
        }
        viewModelScope.launch {
            val context = service.loadContext(sessionId)
            _uiState.update { state ->
                if (state.comparison.selectedSessionId != sessionId) {
                    state
                } else {
                    state.copy(
                        comparison = state.comparison.copy(
                            context = context,
                            contextLoading = false,
                            selectedTargets = defaultComparisonTargets(state, context),
                        ),
                    )
                }
            }
        }
    }

    fun toggleComparisonTarget(target: ComparisonTarget) {
        _uiState.update { state ->
            val selected = state.comparison.selectedTargets
            val next = if (target in selected) selected - target else selected + target
            state.copy(
                comparison = state.comparison.copy(
                    selectedTargets = next,
                    result = null,
                    message = null,
                    windowError = null,
                ),
            )
        }
    }

    fun updateComparisonEarlyTolerance(value: String) {
        _uiState.update { state ->
            state.copy(
                comparison = state.comparison.copy(
                    earlyToleranceSeconds = value,
                    windowError = null,
                    result = null,
                ),
            )
        }
    }

    fun updateComparisonLateTolerance(value: String) {
        _uiState.update { state ->
            state.copy(
                comparison = state.comparison.copy(
                    lateToleranceSeconds = value,
                    windowError = null,
                    result = null,
                ),
            )
        }
    }

    /**
     * 用当前 Session、选中的配置和评分窗运行对比。评分窗只用于评价，不修改任何算法参数。
     */
    fun runComparison() {
        val service = comparisonService ?: return
        val state = _uiState.value
        if (state.comparison.running) {
            return
        }
        val sessionId = state.comparison.selectedSessionId ?: run {
            setComparisonMessage("请先选择一个 Session。")
            return
        }
        val targets = orderedComparisonTargets(state)
        if (targets.isEmpty()) {
            setComparisonMessage("请至少选择一套配置。")
            return
        }
        val earlyTolerance = parseToleranceSeconds(state.comparison.earlyToleranceSeconds)
        if (earlyTolerance.isFailure) {
            _uiState.update { current ->
                current.copy(
                    comparison = current.comparison.copy(
                        windowError = "提前容差：${earlyTolerance.exceptionOrNull()?.message}",
                    ),
                )
            }
            return
        }
        val lateTolerance = parseToleranceSeconds(state.comparison.lateToleranceSeconds)
        if (lateTolerance.isFailure) {
            _uiState.update { current ->
                current.copy(
                    comparison = current.comparison.copy(
                        windowError = "迟到容差：${lateTolerance.exceptionOrNull()?.message}",
                    ),
                )
            }
            return
        }
        val window = ScoringWindow(
            earlyToleranceMillis = earlyTolerance.getOrThrow(),
            lateToleranceMillis = lateTolerance.getOrThrow(),
        )
        _uiState.update { current ->
            current.copy(
                comparison = current.comparison.copy(
                    running = true,
                    result = null,
                    message = null,
                    windowError = null,
                ),
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                service.compare(sessionId, targets, window)
            }
            _uiState.update { current ->
                if (current.comparison.selectedSessionId != sessionId) {
                    current
                } else {
                    when (result) {
                        is SessionComparisonResult.Completed -> current.copy(
                            comparison = current.comparison.copy(
                                running = false,
                                result = result.comparison,
                                message = null,
                            ),
                        )

                        is SessionComparisonResult.Unavailable -> current.copy(
                            comparison = current.comparison.copy(
                                running = false,
                                result = null,
                                message = result.reason,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun clearComparisonMessage() {
        _uiState.update { state ->
            state.copy(comparison = state.comparison.copy(message = null, windowError = null))
        }
    }

    private fun applyComparisonSessions(
        state: AlgorithmLabUiState,
        sessions: List<SessionSummary>,
    ): AlgorithmLabUiState {
        val comparison = state.comparison
        val selectedId = comparison.selectedSessionId
        val selectionStillExists = selectedId != null &&
            sessions.any { summary -> summary.session.id == selectedId }
        return state.copy(
            comparison = comparison.copy(
                sessions = sessions,
                selectedSessionId = if (selectionStillExists) selectedId else null,
                context = if (selectionStillExists) comparison.context else null,
                contextLoading = if (selectionStillExists) comparison.contextLoading else false,
                result = if (selectionStillExists) comparison.result else null,
            ),
        )
    }

    private fun defaultComparisonTargets(
        state: AlgorithmLabUiState,
        context: ComparisonSessionContext?,
    ): Set<ComparisonTarget> {
        val targets = linkedSetOf<ComparisonTarget>()
        val originalSnapshot = context?.configSnapshot
        if (originalSnapshot != null) {
            targets += ComparisonTarget.OriginalSnapshot
        }
        val selected = state.selectedConfig
        if (selected.defaultSnapshotId() != originalSnapshot?.snapshotId) {
            targets += ComparisonTarget.Config(selected.configId)
        }
        return targets
    }

    private fun orderedComparisonTargets(state: AlgorithmLabUiState): List<ComparisonTarget> = buildList {
        if (ComparisonTarget.OriginalSnapshot in state.comparison.selectedTargets) {
            add(ComparisonTarget.OriginalSnapshot)
        }
        state.configs.forEach { config ->
            val target = ComparisonTarget.Config(config.configId)
            if (target in state.comparison.selectedTargets) {
                add(target)
            }
        }
    }

    private fun parseToleranceSeconds(raw: String): Result<Long> {
        val value = raw.trim().toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("必须是 0–3600 之间的数字。"))
        if (!value.isFinite() || value < 0.0 || value > 3_600.0) {
            return Result.failure(IllegalArgumentException("必须是 0–3600 之间的数字。"))
        }
        return Result.success((value * 1_000.0).roundToLong())
    }

    private fun setComparisonMessage(message: String) {
        _uiState.update { state ->
            state.copy(comparison = state.comparison.copy(message = message))
        }
    }

    private fun setMessage(message: String) {
        _uiState.update { state -> state.copy(message = message) }
    }
}
