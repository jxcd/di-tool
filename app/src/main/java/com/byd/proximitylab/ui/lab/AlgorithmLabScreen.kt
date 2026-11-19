package com.byd.proximitylab.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.algorithmconfig.AlgorithmConfig
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigField
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigInput
import com.byd.proximitylab.algorithmconfig.BuiltInAlgorithmConfigs
import com.byd.proximitylab.algorithmconfig.ConfirmationKind
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.navigation.LabDestination
import java.util.Locale

/**
 * 06 算法实验室：选择 Baseline/EMA、管理具名配置并指定下一次模拟测试的生效项。
 *
 * 编辑只写配置表；开始测试时由实时入口生成独立快照，因此运行中的测试和已保存 Session 不受后续编辑影响。
 */
@Composable
fun AlgorithmLabScreen(
    modifier: Modifier = Modifier,
    viewModel: AlgorithmLabViewModel = rememberAlgorithmLabViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag(LabDestination.Lab.screenTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "算法实验室",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "选择 Baseline 或 EMA，维护具名参数配置，并指定下一次模拟测试使用的配置。",
            style = MaterialTheme.typography.bodyMedium,
        )

        uiState.message?.let { message ->
            Text(
                modifier = Modifier.testTag("lab_message"),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        EffectiveConfigCard(uiState = uiState)

        Button(
            modifier = Modifier.fillMaxWidth().testTag("create_config"),
            onClick = viewModel::startCreate,
        ) {
            Text("新建配置")
        }

        HorizontalDivider()

        Text(
            modifier = Modifier.testTag("lab_builtin_section"),
            text = "内置算法（可复制、可选择，不可编辑或删除）",
            style = MaterialTheme.typography.titleMedium,
        )
        uiState.builtInConfigs.forEach { config ->
            ConfigCard(
                config = config,
                selected = config.configId == uiState.selectedConfigId,
                showEdit = false,
                showDelete = false,
                onDetail = { viewModel.showDetail(config.configId) },
                onCopy = { viewModel.startCopy(config.configId) },
                onEdit = { viewModel.startEdit(config.configId) },
                onDelete = { viewModel.requestDelete(config.configId) },
                onSelect = { viewModel.selectConfig(config.configId) },
            )
        }

        HorizontalDivider()

        Text(
            modifier = Modifier.testTag("lab_user_section"),
            text = "我的配置（${uiState.userConfigs.size}）",
            style = MaterialTheme.typography.titleMedium,
        )
        if (uiState.userConfigs.isEmpty()) {
            Text(
                modifier = Modifier.testTag("lab_user_configs_empty"),
                text = "尚无自定义配置。可以从内置算法复制，或新建一组参数。",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            uiState.userConfigs.forEach { config ->
                ConfigCard(
                    config = config,
                    selected = config.configId == uiState.selectedConfigId,
                    showEdit = true,
                    showDelete = true,
                    onDetail = { viewModel.showDetail(config.configId) },
                    onCopy = { viewModel.startCopy(config.configId) },
                    onEdit = { viewModel.startEdit(config.configId) },
                    onDelete = { viewModel.requestDelete(config.configId) },
                    onSelect = { viewModel.selectConfig(config.configId) },
                )
            }
        }

        AlgorithmComparisonSection(
            state = uiState,
            onSelectSession = viewModel::selectComparisonSession,
            onToggleTarget = viewModel::toggleComparisonTarget,
            onEarlyToleranceChange = viewModel::updateComparisonEarlyTolerance,
            onLateToleranceChange = viewModel::updateComparisonLateTolerance,
            onRun = viewModel::runComparison,
        )
    }

    uiState.form?.let { form ->
        ConfigEditorDialog(
            form = form,
            onInputChange = viewModel::updateFormInput,
            onSave = viewModel::saveForm,
            onCancel = viewModel::cancelForm,
        )
    }

    uiState.detail?.let { detail ->
        ConfigDetailDialog(
            config = detail,
            onDismiss = viewModel::dismissDetail,
        )
    }

    uiState.pendingDelete?.let { target ->
        DeleteConfigDialog(
            target = target,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

@Composable
private fun EffectiveConfigCard(uiState: AlgorithmLabUiState) {
    val selected = uiState.selectedConfig
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("lab_effective_config")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                modifier = Modifier.testTag("lab_selected_config"),
                text = "当前生效：${selected.name}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                modifier = Modifier.testTag("lab_selected_params"),
                text = parameterSummary(selected),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("lab_effective_note"),
                text = "修改后的参数从下一次模拟测试生效；运行中的测试和已保存 Session 继续使用开始时的快照。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConfigCard(
    config: AlgorithmConfig,
    selected: Boolean,
    showEdit: Boolean,
    showDelete: Boolean,
    onDetail: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("lab_config_${config.configId}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.testTag("lab_config_name_${config.configId}"),
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (selected) {
                    Text(
                        modifier = Modifier.testTag("lab_config_selected_${config.configId}"),
                        text = "当前生效",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                modifier = Modifier.testTag("lab_config_params_${config.configId}"),
                text = parameterSummary(config),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (config.builtIn) "内置配置" else "用户配置 · 可编辑",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f).testTag("detail_config_${config.configId}"),
                    onClick = onDetail,
                ) {
                    Text("详情")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f).testTag("copy_config_${config.configId}"),
                    onClick = onCopy,
                ) {
                    Text("复制")
                }
                if (showEdit) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f).testTag("edit_config_${config.configId}"),
                        onClick = onEdit,
                    ) {
                        Text("编辑")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f).testTag("select_config_${config.configId}"),
                    enabled = !selected,
                    onClick = onSelect,
                ) {
                    Text(if (selected) "已生效" else "设为生效")
                }
                if (showDelete) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f).testTag("delete_config_${config.configId}"),
                        onClick = onDelete,
                    ) {
                        Text("删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigEditorDialog(
    form: AlgorithmConfigFormState,
    onInputChange: (AlgorithmConfigInput) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val input = form.input
    AlertDialog(
        modifier = Modifier.testTag("config_editor"),
        onDismissRequest = onCancel,
        title = {
            Text(if (form.editing) "编辑配置" else "新建配置")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("config_name"),
                    value = input.name,
                    onValueChange = { value -> onInputChange(input.copy(name = value)) },
                    label = { Text("配置名") },
                    isError = form.fieldErrors.containsKey(AlgorithmConfigField.NAME),
                    supportingText = {
                        FieldSupportingText(
                            error = form.fieldErrors[AlgorithmConfigField.NAME],
                            tag = "config_name_error",
                            hint = "保存前必须填写配置名。",
                        )
                    },
                )

                Text(text = "算法", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AlgorithmOption(
                        label = "Baseline（原始 RSSI）",
                        selected = input.algorithm == ProximityAlgorithm.BASELINE,
                        tag = "config_algorithm_baseline",
                        onClick = {
                            onInputChange(input.copy(algorithm = ProximityAlgorithm.BASELINE))
                        },
                    )
                    AlgorithmOption(
                        label = "EMA（指数平滑）",
                        selected = input.algorithm == ProximityAlgorithm.EMA,
                        tag = "config_algorithm_ema",
                        onClick = {
                            onInputChange(input.copy(algorithm = ProximityAlgorithm.EMA))
                        },
                    )
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("config_enter_threshold"),
                    value = input.enterThresholdDbm,
                    onValueChange = { value -> onInputChange(input.copy(enterThresholdDbm = value)) },
                    label = { Text("进入阈值（dBm）") },
                    isError = form.fieldErrors.containsKey(AlgorithmConfigField.ENTER_THRESHOLD_DBM),
                    supportingText = {
                        FieldSupportingText(
                            error = form.fieldErrors[AlgorithmConfigField.ENTER_THRESHOLD_DBM],
                            tag = "config_enter_threshold_error",
                            hint = "RSSI 单位 dBm；数值越大越容易确认近端。",
                        )
                    },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("config_exit_threshold"),
                    value = input.exitThresholdDbm,
                    onValueChange = { value -> onInputChange(input.copy(exitThresholdDbm = value)) },
                    label = { Text("离开阈值（dBm）") },
                    isError = form.fieldErrors.containsKey(AlgorithmConfigField.EXIT_THRESHOLD_DBM),
                    supportingText = {
                        FieldSupportingText(
                            error = form.fieldErrors[AlgorithmConfigField.EXIT_THRESHOLD_DBM],
                            tag = "config_exit_threshold_error",
                            hint = "RSSI 单位 dBm；必须小于进入阈值。",
                        )
                    },
                )

                Text(text = "证据确认", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConfirmationOption(
                        label = "连续有效帧数",
                        selected = input.confirmationKind == ConfirmationKind.CONSECUTIVE_SAMPLES,
                        tag = "config_confirmation_samples",
                        onClick = {
                            onInputChange(
                                input.copy(confirmationKind = ConfirmationKind.CONSECUTIVE_SAMPLES),
                            )
                        },
                    )
                    ConfirmationOption(
                        label = "持续时长",
                        selected = input.confirmationKind == ConfirmationKind.DURATION,
                        tag = "config_confirmation_duration",
                        onClick = {
                            onInputChange(
                                input.copy(confirmationKind = ConfirmationKind.DURATION),
                            )
                        },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("config_confirmation_value"),
                    value = input.confirmationValue,
                    onValueChange = { value -> onInputChange(input.copy(confirmationValue = value)) },
                    label = {
                        Text(
                            if (input.confirmationKind == ConfirmationKind.CONSECUTIVE_SAMPLES) {
                                "连续有效帧数"
                            } else {
                                "持续时长（ms）"
                            },
                        )
                    },
                    isError = form.fieldErrors.containsKey(AlgorithmConfigField.CONFIRMATION_VALUE),
                    supportingText = {
                        FieldSupportingText(
                            error = form.fieldErrors[AlgorithmConfigField.CONFIRMATION_VALUE],
                            tag = "config_confirmation_value_error",
                            hint = if (input.confirmationKind == ConfirmationKind.CONSECUTIVE_SAMPLES) {
                                "需要连续多少条独立有效观测。"
                            } else {
                                "同一侧有效观测至少跨越的时长，单位毫秒。"
                            },
                        )
                    },
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("config_validity_millis"),
                    value = input.sampleValidityMillis,
                    onValueChange = { value ->
                        onInputChange(input.copy(sampleValidityMillis = value))
                    },
                    label = { Text("样本过期时间（ms）") },
                    isError = form.fieldErrors.containsKey(AlgorithmConfigField.SAMPLE_VALIDITY_MILLIS),
                    supportingText = {
                        FieldSupportingText(
                            error = form.fieldErrors[AlgorithmConfigField.SAMPLE_VALIDITY_MILLIS],
                            tag = "config_validity_error",
                            hint = "超过该时长的观测不再作为有效证据。",
                        )
                    },
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("config_ema_alpha"),
                    value = input.emaAlpha,
                    onValueChange = { value -> onInputChange(input.copy(emaAlpha = value)) },
                    label = { Text("EMA 平滑系数 α（0 < α ≤ 1）") },
                    isError = form.fieldErrors.containsKey(AlgorithmConfigField.EMA_ALPHA),
                    supportingText = {
                        FieldSupportingText(
                            error = form.fieldErrors[AlgorithmConfigField.EMA_ALPHA],
                            tag = "config_ema_alpha_error",
                            hint = "仅 EMA 使用；α 越大越接近原始 RSSI。",
                        )
                    },
                )

                Text(
                    text = "保存后从下一次模拟测试生效；当前运行中的测试继续使用原快照。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("config_save"),
                onClick = onSave,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.testTag("config_cancel"),
                onClick = onCancel,
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AlgorithmOption(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ConfirmationOption(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FieldSupportingText(
    error: String?,
    tag: String,
    hint: String,
) {
    if (error != null) {
        Text(
            modifier = Modifier.testTag(tag),
            text = error,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text(text = hint)
    }
}

@Composable
private fun ConfigDetailDialog(
    config: AlgorithmConfig,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("config_detail"),
        onDismissRequest = onDismiss,
        title = {
            Text(
                modifier = Modifier.testTag("config_detail_name"),
                text = config.name,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    modifier = Modifier.testTag("config_detail_params"),
                    text = parameterSummary(config),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = "来源：${if (config.builtIn) "内置算法" else "用户配置"}")
                Text(
                    text = "该配置只影响从下次模拟测试开始的判定。每次测试开始时固定独立快照，" +
                        "之后编辑或删除配置都不会改变已保存 Session 的判定依据。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("config_detail_close"),
                onClick = onDismiss,
            ) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun DeleteConfigDialog(
    target: AlgorithmConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("config_delete_dialog"),
        onDismissRequest = onDismiss,
        title = { Text("删除配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    modifier = Modifier.testTag("config_delete_target"),
                    text = "确定删除配置「${target.name}」？",
                )
                Text(
                    text = "删除当前生效配置后会自动回退到可用默认配置；已有 Session 的配置快照不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("config_delete_confirm"),
                onClick = onConfirm,
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.testTag("config_delete_cancel"),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}

internal fun parameterSummary(config: AlgorithmConfig): String {
    val confirmation = when (val requirement = config.config.confirmation) {
        is ConfirmationRequirement.ConsecutiveSamples -> "连续 ${requirement.minimumSamples} 帧"
        is ConfirmationRequirement.Duration -> "持续 ${requirement.minimumDurationMillis} ms"
    }
    return buildString {
        append(algorithmLabel(config.config.algorithm))
        append(" · 进入 ${config.config.enterThresholdDbm} dBm")
        append(" / 离开 ${config.config.exitThresholdDbm} dBm")
        append(" · 确认 $confirmation")
        append(" · 过期 ${config.config.sampleValidityMillis} ms")
        append(" · α ${formatAlpha(config.config.emaAlpha)}")
    }
}

internal fun algorithmLabel(algorithm: ProximityAlgorithm): String = when (algorithm) {
    ProximityAlgorithm.BASELINE -> "Baseline"
    ProximityAlgorithm.EMA -> "EMA"
}

private fun formatAlpha(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

@Composable
private fun rememberAlgorithmLabViewModel(): AlgorithmLabViewModel {
    val context = LocalContext.current
    val application = context.applicationContext as ProximityLabApplication
    return viewModel(
        factory = AppViewModelProvider.labFactory(
            store = application.container.algorithmConfigStore,
            comparisonService = application.container.sessionComparisonService,
        ),
    )
}
