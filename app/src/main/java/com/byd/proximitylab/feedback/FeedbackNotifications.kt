package com.byd.proximitylab.feedback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.byd.proximitylab.R
import com.byd.proximitylab.simulation.SessionRunEvent

/**
 * 11 的通知渠道与通知构建。事件反馈与前台服务状态使用独立渠道，二者可分别关闭。
 */
object FeedbackNotifications {

    const val EVENT_CHANNEL_ID: String = "proximity_lab_simulation_alerts"
    const val SESSION_CHANNEL_ID: String = "proximity_lab_session_status"

    const val EVENT_NOTIFICATION_ID: Int = 1101
    const val FOREGROUND_NOTIFICATION_ID: Int = 1102

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                EVENT_CHANNEL_ID,
                "模拟事件通知",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "主动模拟测试产生进入范围事件时的本地通知。"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL_ID,
                "测试运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "采样或模拟测试期间的前台服务状态。"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun buildEventNotification(context: Context, event: SessionRunEvent): Notification {
        val text = "目标 ${event.targetId} · ${event.rawRssiDbm} dBm · ${event.reason.name}" +
            "（本地模拟，不向车辆发送指令）"
        return Notification.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proximity)
            .setContentTitle("接近事件：进入范围")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setWhen(event.atEpochMillis)
            .setAutoCancel(true)
            .build()
    }

    fun buildSessionNotification(context: Context): Notification =
        Notification.Builder(context, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proximity)
            .setContentTitle("接近实验运行中")
            .setContentText("锁屏后继续记录；请在 App 内结束测试。")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** Android 13+ 的通知权限只影响呈现；检测使用平台 API，便于 UI 明确显示限制。 */
    fun isNotificationChannelBlocked(context: Context, channelId: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }
}
