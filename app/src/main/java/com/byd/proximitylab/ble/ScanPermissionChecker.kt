package com.byd.proximitylab.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * 开始采样或模拟测试前检查 `BLUETOOTH_SCAN` 是否已授予。
 *
 * 前台服务只在 Session 创建后启动，因此这里拒绝启动就不会启动前台服务，也不会伪造扫描状态。
 */
fun interface ScanPermissionChecker {

    fun hasScanPermission(): Boolean

    companion object {
        /** 仅用于不接触 Android 权限的测试夹具；生产接线使用 [AndroidScanPermissionChecker]。 */
        val ALWAYS_GRANTED: ScanPermissionChecker = ScanPermissionChecker { true }
    }
}

class AndroidScanPermissionChecker(private val context: Context) : ScanPermissionChecker {

    override fun hasScanPermission(): Boolean =
        context.applicationContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
}
