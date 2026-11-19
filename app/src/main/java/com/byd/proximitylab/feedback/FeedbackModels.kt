package com.byd.proximitylab.feedback

import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus

/**
 * 通知与震动可用的 Android 平台条件快照。
 *
 * `POST_NOTIFICATIONS` 只在 Android 13+ 存在，因此单独区分“需要申请”和“已授予”；通知渠道或
 * App 级开关被用户关闭时 [notificationsEnabled] 为 false。通知不可用不改变事件事实，也不阻止
 * 合法开始采样。
 */
data class SimulationAlertCapabilities(
    val notificationPermissionRequired: Boolean,
    val notificationPermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val vibrationAvailable: Boolean,
) {
    /** 只有权限与通知开关都可用时才尝试提交系统通知。 */
    val canSubmitNotification: Boolean
        get() = notificationPermissionGranted && notificationsEnabled

    /** Android 13+ 且尚未授予通知权限时，可见页面应单独申请。 */
    val needsNotificationPermissionRequest: Boolean
        get() = notificationPermissionRequired && !notificationPermissionGranted

    companion object {
        /** 平台条件尚不可读时的保守值；UI 只显示“未知”，不据此伪造通知结果。 */
        val UNKNOWN: SimulationAlertCapabilities = SimulationAlertCapabilities(
            notificationPermissionRequired = false,
            notificationPermissionGranted = false,
            notificationsEnabled = false,
            vibrationAvailable = false,
        )
    }
}

/**
 * 一次本地反馈尝试的可观察结果。
 *
 * [Submitted] 表示通知已交给系统，不代表锁屏一定呈现；锁屏呈现由 13/14 现场确认。
 * [Unavailable] 是可识别的权限或系统开关限制，[Failed] 是提交时抛出的系统错误。
 */
sealed interface SimulationAlertDelivery {

    data class Submitted(val detail: String) : SimulationAlertDelivery

    data class Unavailable(val detail: String) : SimulationAlertDelivery

    data class Failed(val detail: String) : SimulationAlertDelivery
}

/**
 * 模拟事件的本地反馈边界。生产实现使用 Android NotificationManager 与 Vibrator；
 * 测试只在这一点替代平台调用，事件持久化与反馈状态更新仍走真实存储。
 */
interface SimulationAlarm {

    fun capabilities(): SimulationAlertCapabilities

    fun deliver(
        event: SessionRunEvent,
        capabilities: SimulationAlertCapabilities,
    ): SimulationAlertDelivery
}

/**
 * 前台服务的可观察运行状态。启动失败时 Session 仍按平台实际能力记录，并明确显示限制。
 */
data class ForegroundSessionStatus(
    val sessionId: String? = null,
    val running: Boolean = false,
    val failureReason: String? = null,
)

sealed interface ForegroundSessionStartResult {

    data object Started : ForegroundSessionStartResult

    data class Failed(val reason: String) : ForegroundSessionStartResult
}

/**
 * 前台服务启动边界。只有用户可见操作已经创建 Session 后才由协调器调用。
 */
interface ForegroundSessionLauncher {

    fun start(sessionId: String): ForegroundSessionStartResult

    fun stop()
}

/**
 * 反馈结果写回边界。生产实现走 05 的 `LiveMonitorController.updateEventFeedback`，
 * 同时更新 Room 与实时界面；没有运行中测试时仍由存储层保留可追溯结果。
 */
fun interface SimulationFeedbackWriter {

    suspend fun update(
        eventId: String,
        status: SimulatedEventFeedbackStatus,
        detail: String?,
    ): Boolean
}
