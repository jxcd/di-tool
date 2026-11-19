package com.byd.proximitylab.ui.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byd.proximitylab.algorithmconfig.AlgorithmConfig
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigField
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigInput
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigValidator
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * 06 算法实验室页面状态。内置算法始终存在；[selectedConfigId] 指向下一次模拟测试将使用的配置。
 */
data class AlgorithmLabUiState(
    val configs: List<AlgorithmConfig> = BuiltInAlgorithmConfigs.ALL,
    val selectedConfigId: String = BuiltInAlgorithmConfigs.DEFAULT.configId,
    val form: AlgorithmConfigFormState? = null,
    val detail: AlgorithmConfig? = null,
    val pendingDelete: AlgorithmConfig? = null,
    val message: String? = null,
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlgorithmLabUiState())
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

    private fun setMessage(message: String) {
        _uiState.update { state -> state.copy(message = message) }
    }
}
