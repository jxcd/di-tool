package com.byd.proximitylab.feedback

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.testing.AndroidTestPermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 11 的真实 Android 通知路径：POST_NOTIFICATIONS、通知渠道、稳定事件标签。
 *
 * 只在权限状态上使用平台真实值；通知内容与标签仍来自生产构建。
 */
@RunWith(AndroidJUnit4::class)
class AndroidSimulationAlarmTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AndroidTestPermissions.grant(context, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun grantedNotificationPermissionPostsNotificationWithStableEventTag() {
        val alarm = AndroidSimulationAlarm(context)
        val capabilities = alarm.capabilities()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue(capabilities.notificationPermissionRequired)
            assertTrue(capabilities.notificationPermissionGranted)
        }
        assertTrue(capabilities.notificationsEnabled)

        val eventId = "alarm-event-${UUID.randomUUID()}"
        val delivery = alarm.deliver(
            event(eventId),
            capabilities,
        )
        assertTrue(delivery is SimulationAlertDelivery.Submitted)
        assertTrue(
            (delivery as SimulationAlertDelivery.Submitted).detail.contains("锁屏"),
        )
        assertTrue(
            notificationManager.activeNotifications.any { notification ->
                notification.tag == eventId &&
                    notification.id == FeedbackNotifications.EVENT_NOTIFICATION_ID
            },
        )
        assertTrue(
            notificationManager.getNotificationChannel(FeedbackNotifications.EVENT_CHANNEL_ID) != null,
        )
        notificationManager.cancel(eventId, FeedbackNotifications.EVENT_NOTIFICATION_ID)
    }

    @Test
    fun deniedCapabilitiesReportUnavailableAndDoNotPost() {
        val eventId = "alarm-denied-${UUID.randomUUID()}"
        val alarm = AndroidSimulationAlarm(context) {
            SimulationAlertCapabilities(
                notificationPermissionRequired = true,
                notificationPermissionGranted = false,
                notificationsEnabled = false,
                vibrationAvailable = true,
            )
        }

        val delivery = alarm.deliver(event(eventId), alarm.capabilities())

        assertTrue(delivery is SimulationAlertDelivery.Unavailable)
        assertTrue(
            (delivery as SimulationAlertDelivery.Unavailable).detail.contains("POST_NOTIFICATIONS"),
        )
        assertFalse(
            notificationManager.activeNotifications.any { notification ->
                notification.tag == eventId
            },
        )
    }

    private fun event(eventId: String): SessionRunEvent = SessionRunEvent(
        eventId = eventId,
        sessionId = "alarm-session",
        type = ProximityEventType.ENTERED_RANGE,
        reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
        atEpochMillis = 1_800_000_000_000L,
        atSessionMillis = 1_000L,
        targetId = "alarm-target",
        frameId = 1L,
        observedAtEpochMillis = 1_800_000_000_000L,
        rawRssiDbm = -60,
        filteredRssiDbm = -60.0,
        algorithmVersion = "proximity-algorithms/1",
        triggerRuleVersion = "confirmed-far-then-near/1",
    )
}
