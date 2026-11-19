package com.byd.proximitylab.ui.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.chart.RssiChartWindow
import com.byd.proximitylab.feedback.ForegroundSessionStatus
import com.byd.proximitylab.feedback.SimulationAlertCapabilities
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.live.LiveReadingPoint
import com.byd.proximitylab.live.SimulationTestView
import com.byd.proximitylab.live.observationIntervalMillis
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.durationMillis
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SimulationEventHistory
import com.byd.proximitylab.simulation.SimulationEventHistoryEntry
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.chart.RssiChart
import com.byd.proximitylab.ui.navigation.LabDestination
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 05 的实时监控入口；07 增加状态区分、近期曲线、时间窗、完整模拟事件历史和设备页入口。
 * 08/10 可直接复用 [RssiChart] 和只读图表输入。
 */
@Composable
fun MonitorScreen(
    modifier: Modifier = Modifier,
    viewModel: MonitorViewModel = rememberMonitorViewModel(),
    onOpenDevice: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chartState by viewModel.chartState.collectAsStateWithLifecycle()
    val history by viewModel.eventHistory.collectAsStateWithLifecycle()
    val markerMessage by viewModel.markerMessage.collectAsStateWithLifecycle()
    val alertCapabilities by viewModel.alertCapabilities.collectAsStateWithLifecycle()
    val foregroundStatus by viewModel.foregroundStatus.collectAsStateWithLifecycle()
    val displayed = uiState.displayDecision
    val status = MonitorStatusFormatter.describe(uiState)

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // 拒绝也不影响已经开始/继续的测试；这里只刷新可观察的权限状态。
        viewModel.refreshAlertCapabilities()
    }
    val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAlertCapabilities()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag(LabDestination.Monitor.screenTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "实时监控",
            style = MaterialTheme.typography.headlineMedium,
        )

        uiState.message?.let { message ->
            Text(
                modifier = Modifier.testTag("monitor_message"),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        TargetAndScanCard(
            uiState = uiState,
            status = status,
            onOpenDevice = onOpenDevice,
        )

        AlertStatusCard(
            foregroundStatus = foregroundStatus,
            capabilities = alertCapabilities,
            onRequestNotificationPermission = requestNotificationPermission,
        )

        OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("monitor_decision")) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    modifier = Modifier.testTag("monitor_mode"),
                    text = if (uiState.simulationRunning) "模拟测试判定" else "普通监控判定",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    modifier = Modifier.testTag("monitor_state"),
                    text = displayed.state.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_reason"),
                    text = "原因：${displayed.reason.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    modifier = Modifier.testTag("monitor_trend"),
                    text = "趋势：${displayed.trend.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    modifier = Modifier.testTag("monitor_raw_rssi"),
                    text = "原始 RSSI：${displayed.rawRssiDbm?.let { "$it dBm" } ?: "无有效观测"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    modifier = Modifier.testTag("monitor_filtered_rssi"),
                    text = "滤波 RSSI：${displayed.filteredRssiDbm?.let { "%.1f dBm".format(Locale.ROOT, it) } ?: "无"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    modifier = Modifier.testTag("monitor_thresholds"),
                    text = "阈值：进入 ${displayed.enterThresholdDbm} dBm · 离开 ${displayed.exitThresholdDbm} dBm · " +
                        algorithmLabel(displayed.algorithm),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_config_summary"),
                    text = "配置：${displayed.configName.ifBlank { "未知" }} · " +
                        "确认 ${confirmationLabel(displayed.confirmation)} · " +
                        "过期 ${displayed.sampleValidityMillis} ms" +
                        (if (displayed.algorithm == ProximityAlgorithm.EMA) {
                            " · α ${"%.2f".format(Locale.ROOT, displayed.emaAlpha)}"
                        } else {
                            ""
                        }),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_last_observation"),
                    text = "最后观测：${uiState.displayLastObservationAtEpochMillis?.let(::formatMonitorTime) ?: "尚无"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_observation_interval"),
                    text = observationIntervalLabel(uiState.displaySamples),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_sample_count"),
                    text = "样本数：${uiState.targetObservationCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (uiState.droppedTargetEventCount > 0) {
                    Text(
                        modifier = Modifier.testTag("monitor_event_gap"),
                        text = "采集事件缓冲丢弃 ${uiState.droppedTargetEventCount} 条。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier = Modifier.weight(1f).testTag("monitor_start_test"),
                enabled = uiState.binding != null && !uiState.simulationRunning,
                onClick = {
                    // Android 13+ 单独申请通知权限；拒绝后测试仍会开始并记录事件。
                    requestNotificationPermission()
                    viewModel.startSimulation()
                },
            ) {
                Text("开始模拟测试")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f).testTag("monitor_stop_test"),
                enabled = uiState.simulationRunning,
                onClick = viewModel::stopSimulation,
            ) {
                Text("结束测试")
            }
        }
        if (uiState.binding == null) {
            Text(
                modifier = Modifier.testTag("monitor_start_hint"),
                text = "请先在设备页绑定目标后再开始模拟测试。",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val activeSession = uiState.activeSession
            if (activeSession != null &&
                activeSession.samplingType != SamplingType.SIMULATION &&
                !uiState.simulationRunning
            ) {
                Text(
                    modifier = Modifier.testTag("monitor_start_hint"),
                    text = "当前已有采集中 Session（${activeSession.samplingType.label}），请先结束后再开始模拟测试。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        uiState.test?.let { test ->
            TestCard(test = test)
            OutlinedButton(
                modifier = Modifier.testTag("monitor_mark_expected"),
                enabled = uiState.simulationRunning,
                onClick = viewModel::markExpectedTrigger,
            ) {
                Text("记录到达期望位置")
            }
            markerMessage?.let { message ->
                Text(
                    modifier = Modifier.testTag("monitor_marker_message"),
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ChartCard(
            chartState = chartState,
            onWindowSelected = viewModel::selectWindow,
        )

        EventHistoryCard(history = history)

        HorizontalDivider()

        DiagnosticsCard(uiState = uiState)
    }
}

@Composable
private fun AlertStatusCard(
    foregroundStatus: ForegroundSessionStatus,
    capabilities: SimulationAlertCapabilities?,
    onRequestNotificationPermission: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("monitor_alert_card")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "运行条件与反馈", style = MaterialTheme.typography.titleMedium)
            Text(
                modifier = Modifier.testTag("monitor_foreground_status"),
                text = foregroundStatusText(foregroundStatus),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("monitor_notification_status"),
                text = notificationStatusText(capabilities),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("monitor_lock_screen_limit"),
                text = "锁屏限制：使用标准系统 BLE 扫描（未按品牌过滤，通用目标同样适用）；" +
                    "锁屏收包与通知是否实际呈现需由 13/14 现场确认。通知不可用不会删除事件或阻止开始采样。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (capabilities?.needsNotificationPermissionRequest == true) {
                OutlinedButton(
                    modifier = Modifier.testTag("monitor_request_notifications"),
                    onClick = onRequestNotificationPermission,
                ) {
                    Text("申请通知权限")
                }
            }
        }
    }
}

@Composable
private fun TargetAndScanCard(
    uiState: LiveMonitorState,
    status: MonitorStatusView,
    onOpenDevice: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("monitor_target_card")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "绑定目标", style = MaterialTheme.typography.titleMedium)
            val binding = uiState.binding
            if (binding == null) {
                Text(
                    modifier = Modifier.testTag("monitor_no_binding"),
                    text = "尚未绑定目标。请先在设备页扫描并确认目标。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    modifier = Modifier.testTag("monitor_binding"),
                    text = "${binding.displayName ?: "名称未知"}（${binding.address}）",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedButton(
                modifier = Modifier.testTag("monitor_open_device"),
                onClick = onOpenDevice,
            ) {
                Text("打开设备页")
            }
            Text(
                modifier = Modifier.testTag("monitor_scan_state"),
                text = "扫描：${scanLabel(uiState.scanState)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("monitor_identity"),
                text = "身份：${identityLabel(uiState.identityStatus)}" +
                    (uiState.identityDetail?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("monitor_status"),
                text = status.headline,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                modifier = Modifier.testTag("monitor_status_detail"),
                text = status.detail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TestCard(test: SimulationTestView) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("monitor_test")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                modifier = Modifier.testTag("monitor_simulated_badge"),
                text = "模拟 · 本地判定，不向车辆发送指令",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                modifier = Modifier.testTag("monitor_test_name"),
                text = "模拟测试：${test.session.name}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                modifier = Modifier.testTag("monitor_test_config"),
                text = "配置：${test.config.name} · ${test.config.config.algorithm.name} · " +
                    "进入 ${test.config.config.enterThresholdDbm} / 离开 ${test.config.config.exitThresholdDbm} dBm",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("monitor_test_config_version"),
                text = "算法版本 ${test.config.algorithmVersion} · 触发规则 ${test.config.triggerRuleVersion}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("monitor_test_running"),
                text = if (test.running) "状态：运行中" else "状态：${test.session.status.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("monitor_test_time"),
                text = sessionRelativeTime(test),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                modifier = Modifier.testTag("monitor_test_state"),
                text = "判定：${test.decision.state.name} · ${test.decision.reason.name}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.testTag("monitor_event_count"),
                text = "模拟事件：${test.events.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val latest = test.latestEvent
            Text(
                modifier = Modifier.testTag("monitor_latest_event"),
                text = latest?.let(::formatEvent) ?: "最近事件：尚无",
                style = MaterialTheme.typography.bodySmall,
            )
            // 最近一条已在上面展示；这里只补更早的事件，避免同一事件显示两次。
            test.events.dropLast(1).takeLast(3).forEach { event ->
                Text(
                    modifier = Modifier.testTag("monitor_event_${event.eventId}"),
                    text = formatEvent(event),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(
    chartState: MonitorChartState,
    onWindowSelected: (RssiChartWindow) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("monitor_chart")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "曲线与时间窗", style = MaterialTheme.typography.titleMedium)
            Text(
                modifier = Modifier.testTag("monitor_chart_mode"),
                text = when (chartState.mode) {
                    MonitorChartMode.SIMULATION_TEST -> "曲线来源：模拟测试"
                    MonitorChartMode.MONITOR -> "曲线来源：普通监控（不产生模拟事件）"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RssiChartWindow.entries.forEach { window ->
                    FilterChip(
                        modifier = Modifier.testTag(windowTestTag(window)),
                        selected = chartState.window == window,
                        onClick = { onWindowSelected(window) },
                        label = { Text(window.label) },
                    )
                }
            }
            RssiChart(
                data = chartState.data,
                testTagPrefix = "monitor_chart",
            )
        }
    }
}

@Composable
private fun EventHistoryCard(history: SimulationEventHistory) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("monitor_history")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "模拟事件历史", style = MaterialTheme.typography.titleMedium)
            if (!history.hasEvents) {
                Text(
                    modifier = Modifier.testTag("monitor_history_empty"),
                    text = if (history.sessionId == null) {
                        "尚无模拟测试事件。普通监控不会产生模拟事件或通知。"
                    } else {
                        "本次模拟测试尚未产生事件。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (history.sessionId != null) {
                Text(
                    modifier = Modifier.testTag("monitor_history_target"),
                    text = "目标：${history.targetLabel ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_history_config"),
                    text = "配置：${history.configName ?: "未知"}" +
                        (history.configAlgorithm?.let { " · ${algorithmLabel(it)}" } ?: "") +
                        " · 算法版本 ${history.algorithmVersion ?: "未知"}" +
                        " · 触发规则 ${history.triggerRuleVersion ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_history_count"),
                    text = "事件：${history.entries.size}" +
                        (history.sessionStatus?.let { " · ${it.label}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            history.entries.forEach { entry ->
                HistoryEntry(entry = entry, targetLabel = history.targetLabel)
            }
        }
    }
}

@Composable
private fun HistoryEntry(
    entry: SimulationEventHistoryEntry,
    targetLabel: String?,
) {
    Text(
        modifier = Modifier.testTag("monitor_history_event_${entry.eventId}"),
        text = "时间 ${formatMonitorTime(entry.atEpochMillis)} · " +
            "目标 ${targetLabel ?: entry.targetId}（${entry.targetId}） · " +
            "原因 ${entry.reason.name} · 原始 ${entry.rawRssiDbm} dBm · " +
            "滤波 ${"%.1f".format(Locale.ROOT, entry.filteredRssiDbm)} dBm · " +
            "算法 ${entry.algorithmVersion} · 规则 ${entry.triggerRuleVersion}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        modifier = Modifier.testTag("monitor_history_notification_${entry.eventId}"),
        text = "通知：${entry.feedbackStatus.label}" +
            (entry.feedbackDetail?.let { "（$it）" } ?: "") +
            "（事件事实与通知结果分开记录）",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DiagnosticsCard(uiState: LiveMonitorState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "扫描与关联诊断", style = MaterialTheme.typography.titleSmall)
            Text(
                modifier = Modifier.testTag("monitor_scan_diagnostics"),
                text = if (uiState.diagnostics.isEmpty()) {
                    "暂无诊断记录。"
                } else {
                    uiState.diagnostics.takeLast(3).joinToString("\n") { diagnostic ->
                        "${formatMonitorTime(diagnostic.atEpochMillis)} ${diagnostic.kind.name}：" +
                            diagnostic.message
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatEvent(event: SessionRunEvent): String {
    val feedback = event.feedbackStatus.label +
        (event.feedbackDetail?.let { "（$it）" } ?: "")
    return "时间 ${formatMonitorTime(event.atEpochMillis)} · 会话 +${event.atSessionMillis} ms · " +
        "${event.type.name} · ${event.reason.name} · 目标 ${event.targetId} · " +
        "${event.rawRssiDbm} dBm · 滤波 %.1f dBm · 通知：%s".format(
            Locale.ROOT,
            event.filteredRssiDbm,
            feedback,
        )
}

private fun sessionRelativeTime(test: SimulationTestView): String = when (test.session.status) {
    SessionLifecycleStatus.RECORDING -> "开始：${formatMonitorTime(test.session.startedAtEpochMillis)}"
    SessionLifecycleStatus.COMPLETED -> "结束相对：+${test.session.durationMillis} ms"
    SessionLifecycleStatus.INTERRUPTED ->
        "已记录覆盖：+${test.session.durationMillis} ms（中断不计入结束）"
}

private fun observationIntervalLabel(samples: List<LiveReadingPoint>): String {
    val interval = observationIntervalMillis(samples)
    return if (interval == null) {
        "观测间隔：尚无（少于两条独立观测）"
    } else {
        "观测间隔：$interval ms（最近两次独立观测）"
    }
}

private fun confirmationLabel(confirmation: ConfirmationRequirement): String = when (confirmation) {
    is ConfirmationRequirement.ConsecutiveSamples -> "连续 ${confirmation.minimumSamples} 帧"
    is ConfirmationRequirement.Duration -> "持续 ${confirmation.minimumDurationMillis} ms"
}

private fun algorithmLabel(algorithm: ProximityAlgorithm): String = when (algorithm) {
    ProximityAlgorithm.BASELINE -> "Baseline"
    ProximityAlgorithm.EMA -> "EMA"
}

private fun windowTestTag(window: RssiChartWindow): String = when (window) {
    RssiChartWindow.THIRTY_SECONDS -> "monitor_window_30s"
    RssiChartWindow.ONE_MINUTE -> "monitor_window_60s"
    RssiChartWindow.FIVE_MINUTES -> "monitor_window_300s"
}

private fun scanLabel(scanState: ScanAvailability): String = when (scanState) {
    ScanAvailability.Idle -> "未开始"
    ScanAvailability.Scanning -> "扫描中"
    ScanAvailability.Stopped -> "已停止"
    ScanAvailability.BluetoothOff -> "蓝牙已关闭"
    ScanAvailability.BluetoothUnsupported -> "设备不支持 BLE 扫描"
    is ScanAvailability.PermissionMissing ->
        if (scanState.revoked) "扫描权限已被撤销" else "缺少扫描权限"

    is ScanAvailability.Failed -> "扫描失败：${scanState.reason}"
}

private fun identityLabel(status: IdentityStatus): String = when (status) {
    IdentityStatus.UNBOUND -> "未绑定"
    IdentityStatus.CONFIRMED -> "已确认"
    IdentityStatus.UNCERTAIN -> "不确定"
}

private fun foregroundStatusText(status: ForegroundSessionStatus): String = when {
    status.running ->
        "前台服务：运行中（connectedDevice，Session ${status.sessionId ?: "未知"}）"

    status.failureReason != null ->
        "前台服务：启动失败 · ${status.failureReason}（Session 仍按平台实际能力记录）"

    else -> "前台服务：未运行（开始采样或模拟测试后自动启动）"
}

private fun notificationStatusText(capabilities: SimulationAlertCapabilities?): String {
    if (capabilities == null) {
        return "通知权限：未知（未接入 Android 平台状态）。"
    }
    return when {
        capabilities.needsNotificationPermissionRequest ->
            "通知权限：未授予（Android 13+ 需单独申请）；事件仍会记录，只影响锁屏通知。"

        !capabilities.notificationsEnabled ->
            "通知：App 通知或通知渠道已关闭；事件仍会记录，只影响通知呈现。"

        else -> "通知权限：已授予，通知渠道可用。" +
            if (capabilities.vibrationAvailable) " 震动可用。" else " 设备无可用震动。"
    }
}

private val monitorTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

private fun formatMonitorTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(monitorTimeFormatter)

@Composable
private fun rememberMonitorViewModel(): MonitorViewModel {
    val context = LocalContext.current
    val application = context.applicationContext as ProximityLabApplication
    return viewModel(
        factory = AppViewModelProvider.monitorFactory(
            controller = application.container.liveMonitorController,
            configStore = application.container.algorithmConfigStore,
            sessionStore = application.container.sessionStore,
            simulationStore = application.container.simulationRunStore,
            markerRecorder = application.container.sessionMarkerRecorder,
            simulationAlarm = application.container.simulationAlarm,
            foregroundCoordinator = application.container.foregroundSessionCoordinator,
        ),
    )
}
