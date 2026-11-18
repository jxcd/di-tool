package com.byd.proximitylab.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 只读 BLE 扫描边界。真实实现只注册系统扫描回调；测试可在这里送入可控扫描结果。
 *
 * 实现不得配对、建立连接或执行 GATT 操作。
 */
interface BleScanSource {

    val scanState: StateFlow<ScanAvailability>

    val advertisements: Flow<BleAdvertisement>

    fun start()

    fun stop()

    fun close() {
        stop()
    }
}
