package com.byd.proximitylab.feedback

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import com.byd.proximitylab.simulation.SessionRunEvent

/**
 * Android 本地反馈实现：POST_NOTIFICATIONS 与通知渠道单独判断，通知不可用时仍尝试震动。
 *
 * 只用平台 NotificationManager 与 Vibrator，不引入远程推送、网络或第三方通知库。
 * [Submitted] 只表示系统接受提交通知，锁屏是否实际显示留给 13/14 现场记录。
 */
class AndroidSimulationAlarm(
    context: Context,
    private val capabilityReader: (Context) -> SimulationAlertCapabilities =
        ::readPlatformCapabilities,
) : SimulationAlarm {

    private val appContext = context.applicationContext

    init {
        FeedbackNotifications.ensureChannels(appContext)
    }

    override fun capabilities(): SimulationAlertCapabilities = capabilityReader(appContext)

    override fun deliver(
        event: SessionRunEvent,
        capabilities: SimulationAlertCapabilities,
    ): SimulationAlertDelivery {
        val vibration = vibrate()
        if (!capabilities.canSubmitNotification) {
            return SimulationAlertDelivery.Unavailable(
                buildString {
                    append(
                        when {
                            !capabilities.notificationPermissionGranted ->
                                "通知不可用：未授予 POST_NOTIFICATIONS 权限。"

                            else ->
                                "通知不可用：App 通知或通知渠道已关闭。"
                        },
                    )
                    append(vibration.describe())
                },
            )
        }

        return try {
            val manager = requireNotNull(appContext.getSystemService(NotificationManager::class.java))
            manager.notify(
                event.eventId,
                FeedbackNotifications.EVENT_NOTIFICATION_ID,
                FeedbackNotifications.buildEventNotification(appContext, event),
            )
            SimulationAlertDelivery.Submitted(
                "已提交系统通知；锁屏是否呈现需现场确认。" + vibration.describe(),
            )
        } catch (securityException: SecurityException) {
            SimulationAlertDelivery.Failed(
                "提交系统通知被拒绝：${securityException.message ?: "权限错误"}。" + vibration.describe(),
            )
        } catch (throwable: Exception) {
            SimulationAlertDelivery.Failed(
                "提交系统通知失败：${throwable.message ?: throwable.javaClass.simpleName}。" +
                    vibration.describe(),
            )
        }
    }

    private fun vibrate(): VibrationOutcome {
        val vibrator = try {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } catch (_: Exception) {
            null
        } ?: return VibrationOutcome.UNAVAILABLE
        if (!vibrator.hasVibrator()) {
            return VibrationOutcome.UNAVAILABLE
        }
        return try {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
            VibrationOutcome.SUBMITTED
        } catch (_: Exception) {
            VibrationOutcome.FAILED
        }
    }

    private fun VibrationOutcome.describe(): String = when (this) {
        VibrationOutcome.SUBMITTED -> " 已请求震动。"
        VibrationOutcome.UNAVAILABLE -> " 设备没有可用的震动能力。"
        VibrationOutcome.FAILED -> " 震动提交失败。"
    }

    private enum class VibrationOutcome {
        SUBMITTED,
        UNAVAILABLE,
        FAILED,
    }

    companion object {
        private const val VIBRATION_MILLIS = 250L

        /** 读取 Android 版本、POST_NOTIFICATIONS、通知开关和震动能力。 */
        fun readPlatformCapabilities(context: Context): SimulationAlertCapabilities {
            val appContext = context.applicationContext
            val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val permissionGranted = !permissionRequired ||
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            FeedbackNotifications.ensureChannels(appContext)
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val notificationsEnabled = manager?.areNotificationsEnabled() == true &&
                !FeedbackNotifications.isNotificationChannelBlocked(
                    appContext,
                    FeedbackNotifications.EVENT_CHANNEL_ID,
                )
            val vibrationAvailable = try {
                appContext.getSystemService(VibratorManager::class.java)
                    ?.defaultVibrator
                    ?.hasVibrator() == true
            } catch (_: Exception) {
                false
            }
            return SimulationAlertCapabilities(
                notificationPermissionRequired = permissionRequired,
                notificationPermissionGranted = permissionGranted,
                notificationsEnabled = notificationsEnabled,
                vibrationAvailable = vibrationAvailable,
            )
        }
    }
}
