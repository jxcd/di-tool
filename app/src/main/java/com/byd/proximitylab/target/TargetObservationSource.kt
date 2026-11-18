package com.byd.proximitylab.target

import com.byd.proximitylab.ble.BleAdvertisement
import com.byd.proximitylab.ble.BydCandidateHint
import com.byd.proximitylab.ble.ManufacturerDataRecord
import com.byd.proximitylab.ble.ScanAvailability
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 当前扫描会话内按地址聚合的候选设备。仅用于发现和人工选择。
 */
data class CandidateDevice(
    val key: String,
    val advertisement: BleAdvertisement,
    val bydHint: BydCandidateHint?,
    val lastSeenEpochMillis: Long,
) {
    val address: String?
        get() = advertisement.address

    val deviceName: String?
        get() = advertisement.deviceName

    val rssi: Int
        get() = advertisement.rssi

    val serviceUuids: List<String>
        get() = advertisement.serviceUuids

    val manufacturerData: List<ManufacturerDataRecord>
        get() = advertisement.manufacturerData
}

/**
 * 设备页和 05 共用的目标观测快照。扫描可用性与目标是否出现分别表达。
 */
data class TargetMonitorState(
    val scanState: ScanAvailability = ScanAvailability.Idle,
    val scanStartedAtEpochMillis: Long? = null,
    val lastAnyObservationAtEpochMillis: Long? = null,
    val candidates: List<CandidateDevice> = emptyList(),
    val binding: BoundTarget? = null,
    val identity: TargetIdentityReport = TargetIdentityReport.unbound(0L),
    val targetObservationCount: Int = 0,
    val lastTargetObservation: TargetObservation? = null,
    val targetObservedInCurrentScan: Boolean = false,
    val diagnostics: List<AssociationDiagnostic> = emptyList(),
    /**
     * 消费者处理较慢导致 SharedFlow 缓冲满而被丢弃的目标事件数。录制器据此写入缺口标记。
     */
    val droppedTargetEventCount: Int = 0,
)

/**
 * 02 向 04、05、11 暴露的同一目标观测来源。
 *
 * [events] 携带原始 RSSI、观测时刻、App 收到时刻、重复投递标识以及扫描/身份状态变化。
 * 目标关联判定由 03/05 接入，本层只接受身份已确认的观测。
 */
interface TargetObservationSource {

    val state: StateFlow<TargetMonitorState>

    val events: SharedFlow<TargetEvent>

    fun startScan()

    fun stopScan()
}

sealed interface TargetEvent {

    val atEpochMillis: Long

    data class ScanStateChanged(
        val scanState: ScanAvailability,
        override val atEpochMillis: Long,
    ) : TargetEvent

    data class IdentityChanged(
        val identity: TargetIdentityReport,
        val diagnostics: List<AssociationDiagnostic>,
        override val atEpochMillis: Long,
    ) : TargetEvent

    data class ObservationReceived(
        val observation: TargetObservation,
        override val atEpochMillis: Long,
    ) : TargetEvent

    data class ActionRecorded(
        val action: TargetAction,
        val binding: BoundTarget,
        override val atEpochMillis: Long,
    ) : TargetEvent
}
