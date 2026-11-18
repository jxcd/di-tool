package com.byd.proximitylab.ui.sampling

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionSummary
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.coverageEndEpochMillis
import com.byd.proximitylab.session.durationMillis
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.navigation.LabDestination

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
            text = "从定点条件开始一次采样。原始观测、扫描与身份变化写入本地 Session，停止后可重新打开查看。",
            style = MaterialTheme.typography.bodyMedium,
        )

        uiState.message?.let { message ->
            Text(
                modifier = Modifier.testTag("sampling_message"),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val detail = uiState.detail
        if (detail != null) {
            SessionDetailCard(
                detail = detail,
                onBack = viewModel::closeDetail,
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
                onStop = viewModel::stopSampling,
            )
        } else {
            SamplingFormCard(
                form = uiState.form,
                canStart = uiState.binding != null,
                onNameChange = viewModel::updateName,
                onLocationSelected = viewModel::selectLocation,
                onPlacementSelected = viewModel::selectPhonePlacement,
                onDistanceChange = viewModel::updateEstimatedDistance,
                onStart = viewModel::startSampling,
            )
        }

        HorizontalDivider()

        Text(
            text = "Session 列表（${uiState.sessions.size}）",
            style = MaterialTheme.typography.titleLarge,
        )
        if (uiState.sessions.isEmpty()) {
            Text(
                modifier = Modifier.testTag("sampling_sessions_empty"),
                text = "尚无已保存的 Session。",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            uiState.sessions.forEach { summary ->
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
    onStop: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("sampling_active")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "正在采集：${session.name}",
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
                text = "定点采样条件",
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
                text = "${session.location.label} · ${session.phonePlacement.label} · " +
                    "时长 ${formatSessionDuration(session.durationMillis)} · 原始样本 ${summary.observationCount}",
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
        }
    }
}

private fun detailStatus(session: SamplingSession): String = when (session.status) {
    SessionLifecycleStatus.RECORDING -> "状态：采集中"
    SessionLifecycleStatus.COMPLETED ->
        "状态：正常结束（${session.endedAtEpochMillis?.let(::formatSessionDateTime) ?: "未知"}）"

    SessionLifecycleStatus.INTERRUPTED ->
        "状态：中断（恢复发现于 ${session.interruptedDetectedAtEpochMillis?.let(::formatSessionDateTime) ?: "未知"}，" +
            "中断时刻不计入结束时间或评分覆盖）"
}

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
        ),
    )
}
