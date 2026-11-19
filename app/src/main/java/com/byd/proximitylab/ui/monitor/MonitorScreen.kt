package com.byd.proximitylab.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.live.SimulationTestView
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.durationMillis
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.navigation.LabDestination
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 05 的实时监控入口：展示普通监控与主动模拟测试的判定，提供开始/结束和最近事件。
 * 07 在此基础上扩展完整图表和事件历史。
 */
@Composable
fun MonitorScreen(
    modifier: Modifier = Modifier,
    viewModel: MonitorViewModel = rememberMonitorViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayed = uiState.displayDecision

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

        TargetAndScanCard(uiState = uiState)

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
                        displayed.algorithm.name,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.testTag("monitor_last_observation"),
                    text = "最后观测：${displayed.lastObservationAtEpochMillis?.let(::formatMonitorTime) ?: "尚无"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
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
                onClick = viewModel::startSimulation,
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

        uiState.test?.let { test ->
            TestCard(test = test)
        }

        HorizontalDivider()

        DiagnosticsCard(uiState = uiState)
    }
}

@Composable
private fun TargetAndScanCard(uiState: LiveMonitorState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
            test.events.takeLast(3).forEach { event ->
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
    return "会话 +${event.atSessionMillis} ms · ${event.type.name} · ${event.reason.name} · " +
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
        ),
    )
}
