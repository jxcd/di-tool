package com.byd.proximitylab.feedback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 采样或模拟测试期间运行的 `connectedDevice` 前台服务。
 *
 * 只由用户可见操作创建的 Session 启动，Session 结束即停止。服务使用 `START_NOT_STICKY`：
 * 进程被系统终止后不自动重启，重开 App 由用户决定是否再次开始。通知权限被拒绝时服务仍可运行，
 * 只是系统不把前台通知显示在通知栏。
 */
class ProximityLabForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        FeedbackNotifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent.getStringExtra(EXTRA_SESSION_ID))
            else -> stopSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            stopSelf()
            return
        }
        try {
            startForeground(
                FeedbackNotifications.FOREGROUND_NOTIFICATION_ID,
                FeedbackNotifications.buildSessionNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            runningSessionIdState.value = sessionId
        } catch (_: Exception) {
            // 平台拒绝前台服务（权限、类型或后台启动限制）时立即结束，不伪造“运行中”。
            runningSessionIdState.value = null
            stopSelf()
        }
    }

    private fun stopSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        runningSessionIdState.value = null
        stopSelf()
    }

    override fun onDestroy() {
        runningSessionIdState.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START: String = "com.byd.proximitylab.action.START_SESSION"
        private const val EXTRA_SESSION_ID: String = "session_id"

        private val runningSessionIdState = MutableStateFlow<String?>(null)

        /**
         * 当前由前台服务承载的 Session ID。进程内测试与界面状态复用这一单一来源；
         * null 表示服务未运行或已被系统结束。
         */
        val runningSessionId: StateFlow<String?> = runningSessionIdState.asStateFlow()

        fun start(context: Context, sessionId: String): ForegroundSessionStartResult = try {
            context.startForegroundService(startIntent(context, sessionId))
            ForegroundSessionStartResult.Started
        } catch (exception: Exception) {
            ForegroundSessionStartResult.Failed(
                exception.message?.takeIf { it.isNotBlank() }
                    ?: "前台服务启动失败（${exception.javaClass.simpleName}）",
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProximityLabForegroundService::class.java))
        }

        private fun startIntent(context: Context, sessionId: String): Intent =
            Intent(context, ProximityLabForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
    }
}

/**
 * 生产环境的前台服务启动器。只在已有活动 Session 时由协调器调用。
 */
class AndroidForegroundSessionLauncher(context: Context) : ForegroundSessionLauncher {

    private val appContext = context.applicationContext

    override fun start(sessionId: String): ForegroundSessionStartResult =
        ProximityLabForegroundService.start(appContext, sessionId)

    override fun stop() {
        ProximityLabForegroundService.stop(appContext)
    }
}
