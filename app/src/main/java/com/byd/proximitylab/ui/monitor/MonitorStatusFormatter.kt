package com.byd.proximitylab.ui.monitor

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.live.LiveMonitorState
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.target.IdentityStatus
import java.util.Locale

/**
 * 实时监控顶部状态分类。用于把「没有绑定、扫描不可用、尚无样本、样本过期、已确认 FAR」等
 * 看起来都是 `UNKNOWN` 的情况明确区分开。
 */
enum class MonitorHealth {
    NO_BINDING,
    SCAN_NOT_STARTED,
    SCAN_STOPPED,
    SCAN_UNAVAILABLE,
    IDENTITY_UNCERTAIN,
    NO_SAMPLES,
    EXPIRED,
    CONFIRMED_FAR,
    CONFIRMED_NEAR,
    EVIDENCE_PENDING,
    UNKNOWN,
}

data class MonitorStatusView(
    val health: MonitorHealth,
    val headline: String,
    val detail: String,
)

object MonitorStatusFormatter {

    fun describe(state: LiveMonitorState): MonitorStatusView {
        if (state.binding == null) {
            return MonitorStatusView(
                health = MonitorHealth.NO_BINDING,
                headline = "未绑定目标",
                detail = "请先在设备页扫描并人工确认目标，然后回到实时监控。",
            )
        }

        scanStatus(state.scanState)?.let { return it }

        if (state.identityStatus == IdentityStatus.UNCERTAIN) {
            return MonitorStatusView(
                health = MonitorHealth.IDENTITY_UNCERTAIN,
                headline = "身份不确定",
                detail = state.identityDetail
                    ?: "目标身份无法确认，接近判定暂停；请到设备页重新确认或改绑。",
            )
        }

        val decision = state.displayDecision
        if (decision.reason == ProximityStateReason.SAMPLES_EXPIRED) {
            return MonitorStatusView(
                health = MonitorHealth.EXPIRED,
                headline = "样本已过期",
                detail = "最后观测已超过样本有效期，状态回到 UNKNOWN；旧点仍保留在图表中。",
            )
        }

        if (decision.lastObservationAtEpochMillis == null) {
            return MonitorStatusView(
                health = MonitorHealth.NO_SAMPLES,
                headline = "尚无目标观测",
                detail = "扫描已开始，等待目标广播。没有目标样本时不按 FAR 处理。",
            )
        }

        return when (decision.state) {
            ProximityState.FAR -> MonitorStatusView(
                health = MonitorHealth.CONFIRMED_FAR,
                headline = "已确认 FAR",
                detail = "原始 ${rssi(decision.rawRssiDbm)}，滤波 ${rssi(decision.filteredRssiDbm)}；" +
                    "进入 ${decision.enterThresholdDbm} / 离开 ${decision.exitThresholdDbm} dBm。",
            )

            ProximityState.NEAR -> MonitorStatusView(
                health = MonitorHealth.CONFIRMED_NEAR,
                headline = "已确认 NEAR",
                detail = "原始 ${rssi(decision.rawRssiDbm)}，滤波 ${rssi(decision.filteredRssiDbm)}；" +
                    "进入 ${decision.enterThresholdDbm} / 离开 ${decision.exitThresholdDbm} dBm。",
            )

            ProximityState.UNKNOWN ->
                if (decision.reason == ProximityStateReason.EVIDENCE_PENDING) {
                    MonitorStatusView(
                        health = MonitorHealth.EVIDENCE_PENDING,
                        headline = "证据待确认",
                        detail = "收到有效观测，但尚未满足进入或离开的确认条件。",
                    )
                } else {
                    MonitorStatusView(
                        health = MonitorHealth.UNKNOWN,
                        headline = "状态未知",
                        detail = "当前没有足够的新鲜证据。",
                    )
                }
        }
    }

    private fun scanStatus(scanState: ScanAvailability): MonitorStatusView? = when (scanState) {
        ScanAvailability.Scanning -> null
        ScanAvailability.Idle -> MonitorStatusView(
            health = MonitorHealth.SCAN_NOT_STARTED,
            headline = "扫描未开始",
            detail = "进入页面后应自动开始只读扫描；如未开始，请检查扫描权限。",
        )

        ScanAvailability.Stopped -> MonitorStatusView(
            health = MonitorHealth.SCAN_STOPPED,
            headline = "扫描已停止",
            detail = "图表只显示停止前的记录和空窗；未观测不代表车辆已远离。",
        )

        ScanAvailability.BluetoothOff -> MonitorStatusView(
            health = MonitorHealth.SCAN_UNAVAILABLE,
            headline = "扫描不可用：蓝牙已关闭",
            detail = "已有记录和空窗仍可查看；恢复扫描后需要新的有效观测重新建立状态。",
        )

        ScanAvailability.BluetoothUnsupported -> MonitorStatusView(
            health = MonitorHealth.SCAN_UNAVAILABLE,
            headline = "扫描不可用：设备不支持 BLE",
            detail = "当前设备无法执行只读 BLE 扫描。",
        )

        is ScanAvailability.PermissionMissing -> MonitorStatusView(
            health = MonitorHealth.SCAN_UNAVAILABLE,
            headline = if (scanState.revoked) "扫描不可用：权限已被撤销" else "扫描不可用：缺少扫描权限",
            detail = "授予 BLUETOOTH_SCAN 后重新开始扫描；已有记录仍保留。",
        )

        is ScanAvailability.Failed -> MonitorStatusView(
            health = MonitorHealth.SCAN_UNAVAILABLE,
            headline = "扫描不可用：扫描失败",
            detail = scanState.reason,
        )
    }

    private fun rssi(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.1f dBm", it) } ?: "无"

    private fun rssi(value: Int?): String =
        value?.let { "$it dBm" } ?: "无"
}
