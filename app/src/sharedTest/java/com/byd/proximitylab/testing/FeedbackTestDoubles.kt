package com.byd.proximitylab.testing

import com.byd.proximitylab.feedback.ForegroundSessionLauncher
import com.byd.proximitylab.feedback.ForegroundSessionStartResult
import com.byd.proximitylab.feedback.SimulationAlarm
import com.byd.proximitylab.feedback.SimulationAlertCapabilities
import com.byd.proximitylab.feedback.SimulationAlertDelivery
import com.byd.proximitylab.simulation.SessionRunEvent

/**
 * 只替代 Android 通知/震动边界的可控报警器。事件持久化、去重与反馈写回仍走生产实现。
 */
class RecordingSimulationAlarm(
    var capabilities: SimulationAlertCapabilities = SimulationAlertCapabilities(
        notificationPermissionRequired = true,
        notificationPermissionGranted = true,
        notificationsEnabled = true,
        vibrationAvailable = true,
    ),
    var forcedFailure: String? = null,
) : SimulationAlarm {

    val attempts: MutableList<SessionRunEvent> = mutableListOf()
    val deliveries: MutableList<SessionRunEvent> = mutableListOf()

    override fun capabilities(): SimulationAlertCapabilities = capabilities

    override fun deliver(
        event: SessionRunEvent,
        capabilities: SimulationAlertCapabilities,
    ): SimulationAlertDelivery {
        attempts += event
        forcedFailure?.let { reason ->
            return SimulationAlertDelivery.Failed(reason)
        }
        if (!capabilities.canSubmitNotification) {
            return SimulationAlertDelivery.Unavailable(
                if (!capabilities.notificationPermissionGranted) {
                    "通知不可用：未授予 POST_NOTIFICATIONS 权限。"
                } else {
                    "通知不可用：App 通知或通知渠道已关闭。"
                },
            )
        }
        deliveries += event
        return SimulationAlertDelivery.Submitted("已提交系统通知；锁屏是否呈现需现场确认。")
    }
}

/**
 * 只替代 Android 前台服务启动/停止的系统调用。
 */
class RecordingForegroundSessionLauncher(
    var startResult: ForegroundSessionStartResult = ForegroundSessionStartResult.Started,
) : ForegroundSessionLauncher {

    val startedSessionIds: MutableList<String> = mutableListOf()
    var stopCount: Int = 0
        private set

    override fun start(sessionId: String): ForegroundSessionStartResult {
        startedSessionIds += sessionId
        return startResult
    }

    override fun stop() {
        stopCount += 1
    }
}
