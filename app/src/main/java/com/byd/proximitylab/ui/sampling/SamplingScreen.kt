package com.byd.proximitylab.ui.sampling

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.RssiStatistics
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionListFilter
import com.byd.proximitylab.session.SessionMarkerEvaluation
import com.byd.proximitylab.session.SessionSummary
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.coverageEndEpochMillis
import com.byd.proximitylab.session.durationMillis
import com.byd.proximitylab.session.sessionOffsetMillis
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.chart.RssiChart
import com.byd.proximitylab.ui.navigation.LabDestination
import java.util.Locale

@Composable
fun SamplingScreen(
    modifier: Modifier = Modifier,
    viewModel: SamplingViewModel = rememberSamplingViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag(LabDestination.Sampling.screenTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "采样",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "记录定点或行走采样。原始观测、扫描与身份变化写入本地 Session；" +
                "人工标记只用于评价，不修改原始观测。",
            style = MaterialTheme.typography.bodyMedium,
        )

        uiState.message?.let { message ->
            Text(
                modifier = Modifier.testTag("sampling_message"),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        uiState.deleteCandidate?.let { candidate ->
            DeleteSessionDialog(
                candidate = candidate,
                onConfirm = viewModel::confirmDeleteSession,
                onDismiss = viewModel::dismissDeleteSession,
            )
        }

        val detail = uiState.detail
        if (detail != null) {
            SessionDetailCard(
                detail = detail,
                onBack = viewModel::closeDetail,
                onRename = viewModel::renameSession,
                onSelectLabel = viewModel::setEvaluationLabel,
                onDeleteMarker = viewModel::deleteReferenceMarker,
                onDeleteSession = viewModel::requestDeleteSession,
            )
            return@Column
        }

        BindingCard(binding = uiState.binding)

        val activeSession = uiState.activeSession
        if (activeSession != null) {
            val summary = uiState.sessions.firstOrNull { it.session.id == activeSession.id }
            ActiveSessionCard(
                session = activeSession,
                observationCount = summary?.observationCount ?: 0,
                droppedTargetEventCount = uiState.droppedTargetEventCount,
                markerKinds = uiState.activeMarkerKinds,
                onMarker = viewModel::addReferenceMarker,
                onStop = viewModel::stopSampling,
            )
        } else {
            SamplingFormCard(
                form = uiState.form,
                canStart = uiState.binding != null,
                onNameChange = viewModel::updateName,
                onTypeSelected = viewModel::selectSamplingType,
                onLocationSelected = viewModel::selectLocation,
                onPlacementSelected = viewModel::selectPhonePlacement,
                onDistanceChange = viewModel::updateEstimatedDistance,
                onStart = viewModel::startSampling,
            )
        }

        HorizontalDivider()

        Text(
            text = "Session 列表（${uiState.filteredSessions.size}/${uiState.sessions.size}）",
            style = MaterialTheme.typography.titleLarge,
        )
        SessionFilterCard(
            filter = uiState.filter,
            deviceOptions = uiState.deviceOptions,
            onTypeSelected = viewModel::selectSamplingTypeFilter,
            onLocationSelected = viewModel::selectLocationFilter,
            onDeviceSelected = viewModel::selectDeviceFilter,
            onClear = viewModel::clearFilters,
        )
        if (uiState.filteredSessions.isEmpty()) {
            Text(
                modifier = Modifier.testTag("sampling_sessions_empty"),
                text = if (uiState.sessions.isEmpty()) {
                    "尚无已保存的 Session。"
                } else {
                    "没有符合筛选条件的 Session。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            uiState.filteredSessions.forEach { summary ->
                SessionRow(
                    summary = summary,
                    onClick = { viewModel.openSession(summary.session.id) },
                )
            }
        }
    }
}

@Composable
private fun BindingCard(binding: BoundTarget?) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "绑定目标",
                style = MaterialTheme.typography.titleMedium,
            )
            if (binding == null) {
                Text(
                    modifier = Modifier.testTag("sampling_no_binding"),
                    text = "尚未绑定目标。请先在设备页扫描并人工确认一个目标。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    modifier = Modifier.testTag("sampling_binding"),
                    text = "${binding.displayName ?: "名称未知"}（${binding.address}）",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "目标标识：${binding.targetId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    session: SamplingSession,
    observationCount: Int,
    droppedTargetEventCount: Int,
    markerKinds: List<ReferenceMarkerKind>,
    onMarker: (ReferenceMarkerKind) -> Unit,
    onStop: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("sampling_active")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "正在采集：${session.name}（${session.samplingType.label}）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${session.location.label} · ${session.phonePlacement.label} · 原始样本 $observationCount",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("sampling_active_sample_count"),
                text = "原始样本：$observationCount",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "已记录覆盖：${formatSessionDuration(session.durationMillis)}（${formatSessionDateTime(session.lastRecordedAtEpochMillis)}）",
                style = MaterialTheme.typography.bodySmall,
            )
            if (droppedTargetEventCount > 0) {
                Text(
                    modifier = Modifier.testTag("sampling_event_gap"),
                    text = "采集事件缓冲已丢弃 $droppedTargetEventCount 条事件，缺口已写入时间线。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (markerKinds.isNotEmpty()) {
                Text(
                    text = if (session.samplingType == SamplingType.SIMULATION) {
                        "人工标记（模拟测试）"
                    } else {
                        "行走参考标记"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    markerKinds.forEach { kind ->
                        OutlinedButton(
                            modifier = Modifier.testTag("sampling_marker_${kind.name}"),
                            onClick = { onMarker(kind) },
                        ) {
                            Text(kind.label)
                        }
                    }
                }
            }
            Button(
                modifier = Modifier.testTag("sampling_stop"),
                onClick = onStop,
            ) {
                Text("停止并保存")
            }
        }
    }
}

@Composable
private fun SamplingFormCard(
    form: SamplingFormState,
    canStart: Boolean,
    onNameChange: (String) -> Unit,
    onTypeSelected: (SamplingType) -> Unit,
    onLocationSelected: (FixedSampleLocation) -> Unit,
    onPlacementSelected: (PhonePlacement) -> Unit,
    onDistanceChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "采样条件",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("sampling_name"),
                value = form.name,
                onValueChange = onNameChange,
                label = { Text("Session 名称") },
                singleLine = true,
            )

            Text(
                text = "采样类型",
                style = MaterialTheme.typography.titleSmall,
            )
            listOf(SamplingType.FIXED_POINT, SamplingType.WALKING).forEach { type ->
                ChoiceRow(
                    label = type.label,
                    selected = form.samplingType == type,
                    testTag = "sampling_type_${type.name}",
                    onClick = { onTypeSelected(type) },
                )
            }

            Text(
                text = "位置",
                style = MaterialTheme.typography.titleSmall,
            )
            FixedSampleLocation.entries.forEach { location ->
                ChoiceRow(
                    label = location.label,
                    selected = form.location == location,
                    testTag = "sampling_location_${location.name}",
                    onClick = { onLocationSelected(location) },
                )
            }

            Text(
                text = "手机放置方式",
                style = MaterialTheme.typography.titleSmall,
            )
            PhonePlacement.entries.forEach { placement ->
                ChoiceRow(
                    label = placement.label,
                    selected = form.phonePlacement == placement,
                    testTag = "sampling_placement_${placement.name}",
                    onClick = { onPlacementSelected(placement) },
                )
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("sampling_distance"),
                value = form.estimatedDistance,
                onValueChange = onDistanceChange,
                label = { Text("估计距离（米，可选）") },
                singleLine = true,
            )

            Button(
                modifier = Modifier.testTag("sampling_start"),
                enabled = canStart,
                onClick = onStart,
            ) {
                Text("开始采样")
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(testTag)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SessionFilterCard(
    filter: SessionListFilter,
    deviceOptions: List<SessionDeviceOption>,
    onTypeSelected: (SamplingType?) -> Unit,
    onLocationSelected: (FixedSampleLocation?) -> Unit,
    onDeviceSelected: (String?) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("sampling_filters")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "筛选", style = MaterialTheme.typography.titleMedium)
            FilterChipRow(title = "类型") {
                FilterChip(
                    modifier = Modifier.testTag("sampling_filter_type_ALL"),
                    selected = filter.samplingType == null,
                    onClick = { onTypeSelected(null) },
                    label = { Text("全部") },
                )
                SamplingType.entries.forEach { type ->
                    FilterChip(
                        modifier = Modifier.testTag("sampling_filter_type_${type.name}"),
                        selected = filter.samplingType == type,
                        onClick = { onTypeSelected(type) },
                        label = { Text(type.label) },
                    )
                }
            }
            FilterChipRow(title = "位置") {
                FilterChip(
                    modifier = Modifier.testTag("sampling_filter_location_ALL"),
                    selected = filter.location == null,
                    onClick = { onLocationSelected(null) },
                    label = { Text("全部") },
                )
                FixedSampleLocation.entries.forEach { location ->
                    FilterChip(
                        modifier = Modifier.testTag("sampling_filter_location_${location.name}"),
                        selected = filter.location == location,
                        onClick = { onLocationSelected(location) },
                        label = { Text(location.label) },
                    )
                }
            }
            FilterChipRow(title = "设备") {
                FilterChip(
                    modifier = Modifier.testTag("sampling_filter_device_ALL"),
                    selected = filter.targetId == null,
                    onClick = { onDeviceSelected(null) },
                    label = { Text("全部") },
                )
                deviceOptions.forEach { option ->
                    FilterChip(
                        modifier = Modifier.testTag("sampling_filter_device_${option.targetId}"),
                        selected = filter.targetId == option.targetId,
                        onClick = { onDeviceSelected(option.targetId) },
                        label = { Text(option.label) },
                    )
                }
            }
            if (filter.isActive) {
                TextButton(
                    modifier = Modifier.testTag("sampling_filter_clear"),
                    onClick = onClear,
                ) {
                    Text("清除筛选")
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun SessionRow(
    summary: SessionSummary,
    onClick: () -> Unit,
) {
    val session = summary.session
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sampling_session_${session.id}")
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "${session.name} · ${session.status.label}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${session.targetDisplayName ?: "名称未知"}（${session.targetAddress}）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${session.samplingType.label} · ${session.location.label} · " +
                    "${session.phonePlacement.label} · 时长 ${formatSessionDuration(session.durationMillis)} · " +
                    "原始样本 ${summary.observationCount}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("sampling_session_label_${session.id}"),
                text = "人工标签：${session.evaluationLabel.label}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "开始 ${formatSessionDateTime(session.startedAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SessionDetailCard(
    detail: SessionDetailState,
    onBack: () -> Unit,
    onRename: (String, String) -> Unit,
    onSelectLabel: (String, SessionEvaluationLabel) -> Unit,
    onDeleteMarker: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val session = detail.session
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sampling_session_detail"),
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
                    modifier = Modifier.weight(1f),
                    text = session.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedButton(
                    modifier = Modifier.testTag("sampling_detail_back"),
                    onClick = onBack,
                ) {
                    Text("返回列表")
                }
            }
            Text(
                modifier = Modifier.testTag("sampling_detail_status"),
                text = detailStatus(session),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("sampling_detail_target"),
                text = "目标：${session.targetDisplayName ?: "名称未知"}（${session.targetAddress}）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("sampling_detail_conditions"),
                text = "${session.samplingType.label} · 位置 ${session.location.label} · " +
                    "放置 ${session.phonePlacement.label} · " +
                    "估计距离 ${session.estimatedDistanceMeters?.let { "$it 米" } ?: "未填写"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "开始：${formatSessionDateTime(session.startedAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("sampling_detail_coverage"),
                text = "覆盖终点：${formatSessionDateTime(session.coverageEndEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("sampling_detail_duration"),
                text = "时长：${formatSessionDuration(session.durationMillis)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("sampling_detail_sample_count"),
                text = "原始样本：${detail.observationCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            detail.configSnapshot?.let { snapshot ->
                Text(
                    modifier = Modifier.testTag("sampling_detail_config"),
                    text = "配置快照：${snapshot.name} · ${snapshot.config.algorithm.name} · " +
                        "进入 ${snapshot.config.enterThresholdDbm} / 离开 ${snapshot.config.exitThresholdDbm} dBm · " +
                        "算法版本 ${snapshot.algorithmVersion} · 触发规则 ${snapshot.triggerRuleVersion}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            Text(text = "采样曲线", style = MaterialTheme.typography.titleSmall)
            RssiChart(
                data = detail.chart,
                testTagPrefix = "sampling_detail_chart",
            )
            detail.statistics?.let { statistics ->
                Text(
                    modifier = Modifier.testTag("sampling_detail_stats"),
                    text = statisticsText(statistics),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            Text(text = "人工参考标记", style = MaterialTheme.typography.titleSmall)
            Text(
                modifier = Modifier.testTag("sampling_detail_label"),
                text = "整次试验标签：${session.evaluationLabel.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SessionEvaluationLabel.entries.forEach { label ->
                    FilterChip(
                        modifier = Modifier.testTag("sampling_label_${label.name}"),
                        selected = session.evaluationLabel == label,
                        onClick = { onSelectLabel(session.id, label) },
                        label = { Text(label.label) },
                    )
                }
            }
            Text(
                modifier = Modifier.testTag("sampling_detail_evaluation"),
                text = evaluationText(detail.evaluation, session),
                style = MaterialTheme.typography.bodySmall,
            )
            if (detail.markers.isEmpty()) {
                Text(
                    modifier = Modifier.testTag("sampling_detail_markers_empty"),
                    text = "尚无人工作业标记。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                detail.markers.forEach { marker ->
                    val inCoverage = detail.evaluation.markersInCoverage.any { candidate ->
                        candidate.markerId == marker.markerId
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sampling_marker_row_${marker.markerId}"),
                            text = "${marker.kind.label} · ${formatSessionDateTime(marker.atEpochMillis)} · " +
                                "会话 +${formatSessionDuration(marker.sessionOffsetMillis(session))}" +
                                if (inCoverage) "" else "（覆盖范围外，不参与评分）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            modifier = Modifier.testTag("sampling_delete_marker_${marker.markerId}"),
                            onClick = { onDeleteMarker(marker.markerId) },
                        ) {
                            Text("删除")
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "原始时间线",
                style = MaterialTheme.typography.titleSmall,
            )
            val eventGaps = detail.timeline.mapNotNull { item ->
                item.event as? SessionTimelineEvent.EventGap
            }
            if (eventGaps.isNotEmpty()) {
                Text(
                    modifier = Modifier.testTag("sampling_detail_gaps"),
                    text = "事件缺口：${eventGaps.sumOf { it.droppedEventCount }} 条事件未写入，" +
                        "回放时不能把该缺口当作空窗证据。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val observations = detail.timeline.filter { item ->
                item.event is SessionTimelineEvent.Observation
            }
            if (observations.isEmpty()) {
                Text(
                    text = "该 Session 没有原始样本。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                observations.takeLast(MAX_DETAIL_OBSERVATIONS).forEach { item ->
                    val observation = item.event as SessionTimelineEvent.Observation
                    Text(
                        modifier = Modifier.testTag("sampling_detail_sample_${item.sequence}"),
                        text = "#${item.sequence} 观测 ${formatSessionDateTime(observation.observedAtEpochMillis)} " +
                            "收到 ${formatSessionDateTime(observation.receivedAtEpochMillis)} " +
                            "${observation.rawRssiDbm} dBm",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (observations.size > MAX_DETAIL_OBSERVATIONS) {
                    Text(
                        text = "共 ${observations.size} 条，显示最近 $MAX_DETAIL_OBSERVATIONS 条。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()

            Text(text = "Session 管理", style = MaterialTheme.typography.titleSmall)
            var renameDraft by remember(session.id, session.name) { mutableStateOf(session.name) }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("sampling_rename_field"),
                value = renameDraft,
                onValueChange = { value -> renameDraft = value },
                label = { Text("Session 名称") },
                singleLine = true,
            )
            Button(
                modifier = Modifier.testTag("sampling_rename_save"),
                enabled = renameDraft.isNotBlank() && renameDraft.trim() != session.name,
                onClick = { onRename(session.id, renameDraft) },
            ) {
                Text("保存名称")
            }
            OutlinedButton(
                modifier = Modifier.testTag("sampling_delete_session"),
                enabled = session.status != SessionLifecycleStatus.RECORDING,
                onClick = { onDeleteSession(session.id) },
            ) {
                Text("删除此 Session")
            }
            Text(
                modifier = Modifier.testTag("sampling_detail_replay_hint"),
                text = "Session ID：${session.id}（后续回放入口使用同一 ID）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeleteSessionDialog(
    candidate: SessionSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("sampling_delete_dialog"),
        onDismissRequest = onDismiss,
        title = { Text("删除 Session") },
        text = {
            Text(
                "确定删除「${candidate.session.name}」？该 Session 的原始观测、人工标记和配置快照" +
                    "将一并删除，且不可恢复。",
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("sampling_delete_confirm"),
                onClick = onConfirm,
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("sampling_delete_cancel"),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}

private fun detailStatus(session: SamplingSession): String = when (session.status) {
    SessionLifecycleStatus.RECORDING -> "状态：采集中"
    SessionLifecycleStatus.COMPLETED ->
        "状态：正常结束（${session.endedAtEpochMillis?.let(::formatSessionDateTime) ?: "未知"}）"

    SessionLifecycleStatus.INTERRUPTED ->
        "状态：中断（恢复发现于 ${session.interruptedDetectedAtEpochMillis?.let(::formatSessionDateTime) ?: "未知"}，" +
            "中断时刻不计入结束时间或评分覆盖）"
}

private fun statisticsText(statistics: RssiStatistics): String =
    "定点统计：样本数 ${statistics.sampleCount} · " +
        "均值 ${formatDbm(statistics.meanDbm)} · " +
        "中位数 ${formatDbm(statistics.medianDbm)} · " +
        "标准差 ${formatDbm(statistics.standardDeviationDbm)}"

private fun evaluationText(
    evaluation: SessionMarkerEvaluation,
    session: SamplingSession,
): String = when {
    evaluation.isNegativeSample ->
        "评价：无需触发（负样本），用于统计误触发。原始观测仍可查看。"

    evaluation.expectedTriggerConflict ->
        "评价：参考标记冲突（覆盖范围内 ${evaluation.expectedTriggerMarkersInCoverage.size} 个期望触发点）。" +
            "请删除多余标记，或保留为诊断；冲突记录不参与评分。"

    evaluation.isPositiveSample -> {
        val triggerAt = requireNotNull(evaluation.expectedTriggerAtEpochMillis)
        val offset = (triggerAt - session.startedAtEpochMillis).coerceAtLeast(0L)
        "评价：期望触发，会话 +${formatSessionDuration(offset)}（唯一期望触发点）。"
    }

    evaluation.missingExpectedTrigger ->
        "评价：已标记期望触发，但覆盖范围内没有期望触发点；不会作为正样本参与评分。"

    else -> "评价：未标记，仅显示观测结果；也可把整次试验标为无需触发或记录期望触发点。"
}

private fun formatDbm(value: Double): String = String.format(Locale.ROOT, "%.1f dBm", value)

private const val MAX_DETAIL_OBSERVATIONS = 20

@Composable
private fun rememberSamplingViewModel(): SamplingViewModel {
    val context = LocalContext.current
    val application = context.applicationContext as ProximityLabApplication
    return viewModel(
        factory = AppViewModelProvider.samplingFactory(
            recorder = application.container.sessionRecorder,
            store = application.container.sessionStore,
            observationSource = application.container.targetMonitor,
            markerRecorder = application.container.sessionMarkerRecorder,
            simulationStore = application.container.simulationRunStore,
        ),
    )
}
