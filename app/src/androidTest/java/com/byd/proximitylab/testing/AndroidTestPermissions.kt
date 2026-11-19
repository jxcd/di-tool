package com.byd.proximitylab.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 在 instrumentation 中授予目标 App 的运行时权限。
 *
 * 只用于建立测试前置条件（例如开始测试需要 BLUETOOTH_SCAN 已授予），不替代任何业务判断。
 */
object AndroidTestPermissions {

    fun grant(context: Context, vararg permissions: String) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        permissions.forEach { permission ->
            uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
    }
}
