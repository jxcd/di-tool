package com.byd.proximitylab.ui.device

import android.Manifest
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byd.proximitylab.ProximityLabApplication
import com.byd.proximitylab.ble.BydCandidateHint
import com.byd.proximitylab.ble.BydSignal
import com.byd.proximitylab.ble.ManufacturerDataRecord
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.ble.isScanning
import com.byd.proximitylab.target.AssociationBasis
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.CandidateDevice
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.target.TargetAction
import com.byd.proximitylab.target.TargetIdentityReport
import com.byd.proximitylab.target.TargetMonitorState
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.navigation.LabDestination
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class CandidateActionType {
    BIND,
    RECONFIRM,
    REBIND,
}

private data class PendingCandidateAction(
    val type: CandidateActionType,
    val candidate: CandidateDevice,
)

@Composable
fun DeviceScreen(
    modifier: Modifier = Modifier,
    viewModel: DeviceViewModel = rememberDeviceViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startScan()
        }
    }
    var pendingAction by remember { mutableStateOf<PendingCandidateAction?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag(LabDestination.Device.screenTestTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "设备",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "只读扫描附近的 BLE 广播，人工确认一个需要监测的目标。App 不配对、不连接、不执行 GATT。",
            style = MaterialTheme.typography.bodyMedium,
        )

        ScanCard(
            state = uiState,
            onStartScan = viewModel::startScan,
            onStopScan = viewModel::stopScan,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN) },
        )

        BindingCard(
            state = uiState,
            onClearBinding = viewModel::clearBinding,
        )

        DiagnosticsCard(state = uiState)

        HorizontalDivider()

        Text(
            text = "候选设备（${uiState.candidates.size}）",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "比亚迪提示只用于辅助查找候选；未命中提示的通用 BLE 设备也可以人工绑定。",
            style = MaterialTheme.typography.bodySmall,
        )

        if (uiState.candidates.isEmpty()) {
            EmptyCandidates(state = uiState)
        }

        uiState.candidates.forEach { candidate ->
            CandidateCard(
                candidate = candidate,
                binding = uiState.binding,
                onAction = { type ->
                    pendingAction = PendingCandidateAction(type, candidate)
                },
            )
        }
    }

    pendingAction?.let { action ->
        ConfirmCandidateDialog(
            action = action,
            onDismiss = { pendingAction = null },
            onConfirm = {
                when (action.type) {
                    CandidateActionType.BIND -> action.candidate.address?.let(viewModel::bind)
                    CandidateActionType.RECONFIRM -> action.candidate.address?.let(viewModel::reconfirmAsOriginal)
                    CandidateActionType.REBIND -> action.candidate.address?.let(viewModel::rebindTo)
                }
                pendingAction = null
            },
        )
    }
}

@Composable
private fun rememberDeviceViewModel(): DeviceViewModel {
    val context = LocalContext.current
    val application = context.applicationContext as ProximityLabApplication
    return viewModel(
        factory = AppViewModelProvider.deviceFactory(application.container.targetMonitor),
    )
}

@Composable
private fun ScanCard(
    state: TargetMonitorState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "扫描",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                modifier = Modifier.testTag("device_scan_state"),
                text = scanStateLabel(state.scanState),
                style = MaterialTheme.typography.bodyLarge,
            )
            scanStateDetail(state.scanState)?.let { detail ->
                Text(
                    modifier = Modifier.testTag("device_scan_detail"),
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                modifier = Modifier.testTag("device_last_observation"),
                text = state.lastAnyObservationAtEpochMillis?.let {
                    "最近候选观测：${formatClockTime(it)}"
                } ?: "尚未收到任何 BLE 广播。",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.scanState.isScanning) {
                    Button(
                        modifier = Modifier.testTag("device_scan_stop"),
                        onClick = onStopScan,
                    ) {
                        Text("停止扫描")
                    }
                } else {
                    Button(
                        modifier = Modifier.testTag("device_scan_start"),
                        onClick = onStartScan,
                    ) {
                        Text(if (state.scanState is ScanAvailability.Idle) "开始扫描" else "重新开始扫描")
                    }
                }
                if (state.scanState is ScanAvailability.PermissionMissing) {
                    OutlinedButton(
                        modifier = Modifier.testTag("device_scan_grant"),
                        onClick = onRequestPermission,
                    ) {
                        Text("授予扫描权限")
                    }
                }
            }

            Text(
                text = "没有扫描回调不能证明车辆没有广播；扫描可用性与目标是否出现分开显示。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BindingCard(
    state: TargetMonitorState,
    onClearBinding: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "绑定目标",
                style = MaterialTheme.typography.titleMedium,
            )
            val binding = state.binding
            if (binding == null) {
                Text(
                    modifier = Modifier.testTag("device_binding_empty"),
                    text = "尚未绑定目标。扫描后选择下面的候选设备并人工确认。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            Text(
                text = binding.displayName?.takeIf { it.isNotBlank() } ?: "名称未知",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                modifier = Modifier.testTag("device_binding_target_id"),
                text = "App 目标标识：${binding.targetId}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                modifier = Modifier.testTag("device_binding_address"),
                text = "当前观测地址：${binding.address}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "绑定时间：${formatClockTime(binding.boundAtEpochMillis)} · 最近动作：${targetActionLabel(binding.lastAction)} ${formatClockTime(binding.lastActionAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                modifier = Modifier.testTag("device_identity_status"),
                text = identityStatusLabel(state.identity),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                modifier = Modifier.testTag("device_identity_detail"),
                text = identityDetail(state.identity),
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                modifier = Modifier.testTag("device_target_presence"),
                text = targetPresenceText(state),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "本次扫描接受的绑定目标观测：${state.targetObservationCount} 次。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(
                modifier = Modifier.testTag("device_clear_binding"),
                onClick = onClearBinding,
            ) {
                Text("解除绑定")
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(state: TargetMonitorState) {
    val recent = state.diagnostics.takeLast(5)
    if (recent.isEmpty()) {
        return
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("device_diagnostics")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "关联诊断",
                style = MaterialTheme.typography.titleMedium,
            )
            recent.forEach { diagnostic ->
                Text(
                    text = "${formatClockTime(diagnostic.atEpochMillis)} · ${diagnostic.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyCandidates(state: TargetMonitorState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("device_candidates_empty")) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (state.scanState.isScanning) {
                    "扫描中，暂无候选设备。没有回调不能证明附近没有广播。"
                } else {
                    "开始扫描后在这里显示候选设备。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: CandidateDevice,
    binding: BoundTarget?,
    onAction: (CandidateActionType) -> Unit,
) {
    val key = candidateTestKey(candidate)
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_candidate_$key"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.deviceName?.takeIf { it.isNotBlank() } ?: "名称未知",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    modifier = Modifier.testTag("device_candidate_byd_$key"),
                    text = bydHintLabel(candidate.bydHint),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (candidate.bydHint != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(
                text = "地址：${candidate.address ?: "地址不可取得"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "RSSI：${candidate.rssi} dBm",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = serviceUuidText(candidate.serviceUuids),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = manufacturerDataText(candidate.manufacturerData),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "最近发现：${formatClockTime(candidate.lastSeenEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
            )

            when {
                candidate.address == null -> Text(
                    text = "地址不可取得，无法绑定或关联。",
                    style = MaterialTheme.typography.bodySmall,
                )
                binding == null -> Button(
                    modifier = Modifier.testTag("device_bind_$key"),
                    onClick = { onAction(CandidateActionType.BIND) },
                ) {
                    Text("绑定该目标")
                }
                candidate.address == binding.address -> Text(
                    text = "当前绑定",
                    style = MaterialTheme.typography.labelLarge,
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.testTag("device_reconfirm_$key"),
                        onClick = { onAction(CandidateActionType.RECONFIRM) },
                    ) {
                        Text("确认为原设备")
                    }
                    Button(
                        modifier = Modifier.testTag("device_rebind_$key"),
                        onClick = { onAction(CandidateActionType.REBIND) },
                    ) {
                        Text("改绑为新目标")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmCandidateDialog(
    action: PendingCandidateAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val candidate = action.candidate
    val candidateLabel = candidate.deviceName?.takeIf { it.isNotBlank() } ?: "名称未知"
    val address = candidate.address ?: "地址不可取得"
    val (title, message, confirmLabel) = when (action.type) {
        CandidateActionType.BIND -> Triple(
            "绑定目标",
            "确认将 $candidateLabel（$address）保存为当前监测目标？",
            "确认绑定",
        )
        CandidateActionType.RECONFIRM -> Triple(
            "确认为原设备",
            "确认该候选就是原目标？目标标识保持不变，当前地址将更新为 $address，旧触发资格由 03/05 清除。",
            "确认为原设备",
        )
        CandidateActionType.REBIND -> Triple(
            "改绑为新目标",
            "确认改绑到 $candidateLabel（$address）？将建立新的目标标识，原目标标识不再代表当前目标。",
            "确认改绑",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("device_action_confirm"),
                onClick = onConfirm,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("device_action_cancel"),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}

private fun scanStateLabel(state: ScanAvailability): String = when (state) {
    ScanAvailability.Idle -> "扫描未开始"
    ScanAvailability.Scanning -> "扫描中"
    ScanAvailability.Stopped -> "扫描已停止"
    ScanAvailability.BluetoothOff -> "蓝牙已关闭"
    ScanAvailability.BluetoothUnsupported -> "此设备不支持 BLE 扫描"
    is ScanAvailability.PermissionMissing -> if (state.revoked) "扫描权限已被撤销" else "缺少扫描权限"
    is ScanAvailability.Failed -> "扫描失败"
}

private fun scanStateDetail(state: ScanAvailability): String? = when (state) {
    ScanAvailability.Idle -> "点击开始扫描读取标准 BLE 广播。"
    ScanAvailability.Scanning -> "扫描请求已提交；持续没有回调只表示缺少观测。"
    ScanAvailability.Stopped -> "扫描已由用户停止，可重新开始。"
    ScanAvailability.BluetoothOff -> "系统蓝牙已关闭。打开蓝牙后可重新开始扫描。"
    ScanAvailability.BluetoothUnsupported -> "当前设备没有可用的 BLE 扫描能力。"
    is ScanAvailability.PermissionMissing -> if (state.revoked) {
        "BLUETOOTH_SCAN 权限已被撤销，请重新授予。"
    } else {
        "需要 BLUETOOTH_SCAN 权限；本 App 不申请定位或 BLUETOOTH_CONNECT。"
    }
    is ScanAvailability.Failed -> "原因：${state.reason}"
}

private fun identityStatusLabel(identity: TargetIdentityReport): String = when (identity.status) {
    IdentityStatus.UNBOUND -> "尚未绑定目标"
    IdentityStatus.CONFIRMED -> "身份已确认"
    IdentityStatus.UNCERTAIN -> "身份不确定"
}

private fun identityDetail(identity: TargetIdentityReport): String {
    if (identity.status == IdentityStatus.UNBOUND) {
        return "绑定后显示目标身份判定依据。"
    }
    val basis = identity.basis?.let(::basisLabel) ?: "身份依据未知"
    val evidence = identity.evidenceDescription?.takeIf { it.isNotBlank() } ?: ""
    val pending = identity.pendingCandidateAddresses.takeIf { it.isNotEmpty() }?.let {
        "待确认候选：${it.joinToString()}。"
    } ?: ""
    val uncertainty = identity.uncertainty?.let {
        "${it.detail}${if (identity.pendingCandidateAddresses.isEmpty()) "" else " 请从下方候选重新人工确认。"}"
    } ?: ""
    return listOf(basis, evidence, pending, uncertainty)
        .filter { it.isNotBlank() }
        .joinToString(separator = " ")
}

private fun basisLabel(basis: AssociationBasis): String = when (basis) {
    AssociationBasis.PERSISTED_BINDING -> "匹配依据：本地已保存绑定。"
    AssociationBasis.ADDRESS_MATCH -> "匹配依据：广播地址一致。"
    AssociationBasis.BOUND_ADDRESS_RECENTLY_OBSERVED -> "匹配依据：原绑定地址仍在最近候选中。"
    AssociationBasis.VERIFIED_IDENTITY_TOKEN -> "匹配依据：可复验身份证据唯一匹配。"
    AssociationBasis.USER_BINDING -> "匹配依据：人工绑定。"
    AssociationBasis.USER_RECONFIRMATION -> "匹配依据：人工重新确认。"
    AssociationBasis.USER_REBIND -> "匹配依据：人工改绑。"
}

private fun targetPresenceText(state: TargetMonitorState): String {
    val binding = state.binding ?: return "尚未绑定目标。"
    val last = state.lastTargetObservation
    return when {
        state.scanState.isScanning && state.targetObservedInCurrentScan && last != null ->
            "最近目标观测：${formatClockTime(last.observedAtEpochMillis)} · RSSI ${last.rssi} dBm。"
        state.scanState.isScanning ->
            "扫描中，尚未收到绑定目标的观测。没有回调不能证明车辆没有广播。"
        last != null ->
            "扫描不可用；最近目标观测：${formatClockTime(last.observedAtEpochMillis)} · RSSI ${last.rssi} dBm。"
        else ->
            "扫描不可用，未收到绑定目标 ${binding.address} 的观测。"
    }
}

private fun targetActionLabel(action: TargetAction): String = when (action) {
    TargetAction.BOUND -> "人工绑定"
    TargetAction.ADDRESS_CONTINUED -> "按可复验证据延续地址"
    TargetAction.RECONFIRMED -> "人工重新确认"
    TargetAction.REBOUND -> "人工改绑"
}

private fun bydHintLabel(hint: BydCandidateHint?): String {
    if (hint == null) {
        return "通用 BLE 设备"
    }
    return "比亚迪候选：" + hint.matchedSignals.joinToString("、") { signal ->
        when (signal) {
            BydSignal.CLASSIC_SERVICE_UUID -> "经典 UUID"
            BydSignal.BLE3_SERVICE_UUID -> "BLE3 UUID"
            BydSignal.BLE3_LEGACY_SERVICE_UUID -> "BLE3 兼容 UUID"
            BydSignal.MANUFACTURER_ID -> "厂商 ID 0x3494"
            BydSignal.NAME -> "名称"
        }
    }
}

private fun serviceUuidText(uuids: List<String>): String =
    if (uuids.isEmpty()) {
        "Service UUID：未知"
    } else {
        "Service UUID：${uuids.joinToString(separator = "，")}"
    }

private fun manufacturerDataText(data: List<ManufacturerDataRecord>): String =
    if (data.isEmpty()) {
        "Manufacturer Data：未知"
    } else {
        "Manufacturer Data：" + data.joinToString(separator = "；") {
            "0x%04X=%s".format(Locale.ROOT, it.companyId, it.payloadHex)
        }
    }

private fun candidateTestKey(candidate: CandidateDevice): String =
    candidate.address ?: candidate.key

private fun formatClockTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
