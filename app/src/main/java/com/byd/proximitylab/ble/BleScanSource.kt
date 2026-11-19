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

    /**
     * 重新检查平台条件（例如权限是否仍授予）并更新 [scanState]。
     *
     * 运行中的 Android 扫描在权限撤销后可能不再回调；11 的会话看门狗周期调用这里，使撤销成为
     * 可观察的扫描状态变化。默认实现不改变状态，供不模拟平台条件的测试夹具使用。
     */
    fun revalidate() {
        // 默认无操作。
    }

    /**
     * 当平台条件已恢复且扫描因不可用而停止时，重新提交扫描请求，沿用当前 Session。
     *
     * 用户主动停止扫描（[ScanAvailability.Stopped]）不在这里自动恢复。默认实现不改变状态。
     */
    fun resumeIfReady() {
        // 默认无操作。
    }

    fun close() {
        stop()
    }
}
