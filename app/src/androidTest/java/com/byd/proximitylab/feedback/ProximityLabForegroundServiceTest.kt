package com.byd.proximitylab.feedback

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.MainActivity
import com.byd.proximitylab.testing.AndroidTestPermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 11 的 `connectedDevice` 前台服务：Manifest 声明、服务类型、通知渠道与启动/停止。
 *
 * 使用宿主 Activity 保持 App 在前台，避免把后台启动限制误判为服务实现失败。
 */
@RunWith(AndroidJUnit4::class)
class ProximityLabForegroundServiceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AndroidTestPermissions.grant(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun tearDown() {
        ProximityLabForegroundService.stop(context)
        waitUntil(TIMEOUT_MILLIS) {
            ProximityLabForegroundService.runningSessionId.value == null
        }
    }

    @Test
    fun manifestDeclaresConnectedDeviceServiceAndRequiredPermissions() {
        val packageInfo = requireNotNull(
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            ),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.VIBRATE in permissions)
        // 离线、只读扫描与用户主动启动：不申请网络、连接或定位权限。
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)

        val serviceInfo = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, ProximityLabForegroundService::class.java),
            0,
        )
        assertTrue(
            serviceInfo.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0,
        )
        assertFalse(serviceInfo.exported)
    }

    @Test
    fun serviceRunsForSessionAndStopsWithSameSession() {
        val sessionId = "foreground-service-session"
        val startResult = ProximityLabForegroundService.start(context, sessionId)
        assertTrue(startResult is ForegroundSessionStartResult.Started)

        waitUntil(TIMEOUT_MILLIS) {
            ProximityLabForegroundService.runningSessionId.value == sessionId
        }
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assertNotNull(manager.getNotificationChannel(FeedbackNotifications.SESSION_CHANNEL_ID))
        assertNotNull(manager.getNotificationChannel(FeedbackNotifications.EVENT_CHANNEL_ID))

        ProximityLabForegroundService.stop(context)
        waitUntil(TIMEOUT_MILLIS) {
            ProximityLabForegroundService.runningSessionId.value == null
        }
        assertEquals(null, ProximityLabForegroundService.runningSessionId.value)
    }

    private fun waitUntil(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(20L)
        }
        assertTrue("等待条件超时（${timeoutMillis}ms）", condition())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
