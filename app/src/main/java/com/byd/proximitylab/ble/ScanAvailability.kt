package com.byd.proximitylab.ble

/**
 * 扫描请求与运行条件。它只说明扫描是否可用，不说明目标车辆是否在广播。
 */
sealed interface ScanAvailability {

    data object Idle : ScanAvailability

    /**
     * 扫描请求已提交但结果可能为空。没有回调不等于附近没有广播。
     */
    data object Scanning : ScanAvailability

    data object Stopped : ScanAvailability

    data object BluetoothOff : ScanAvailability

    data object BluetoothUnsupported : ScanAvailability

    data class PermissionMissing(val revoked: Boolean = false) : ScanAvailability

    data class Failed(val reason: String) : ScanAvailability
}

val ScanAvailability.isScanning: Boolean
    get() = this is ScanAvailability.Scanning
