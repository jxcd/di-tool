package com.byd.proximitylab.feedback

import com.byd.proximitylab.session.SamplingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把共享 [com.byd.proximitylab.session.SessionRecorder] 的活动 Session 接到 Android 前台服务。
 *
 * 每个 Session 只在创建后启动一次 `connectedDevice` 前台服务，Session 结束或进程退出时停止。
 * 服务启动失败不会结束 Session，只把可观察的失败原因交给界面显示。
 */
class ForegroundSessionCoordinator(
    activeSession: StateFlow<SamplingSession?>,
    private val launcher: ForegroundSessionLauncher,
    scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private val _status = MutableStateFlow(ForegroundSessionStatus())
    val status: StateFlow<ForegroundSessionStatus> = _status.asStateFlow()

    init {
        scope.launch {
            activeSession.collect { session -> sync(session) }
        }
    }

    private suspend fun sync(session: SamplingSession?) = mutex.withLock {
        val current = _status.value
        if (session == null) {
            if (current.running) {
                launcher.stop()
            }
            _status.value = ForegroundSessionStatus()
            return@withLock
        }
        if (current.sessionId == session.id) {
            // 同一 Session 已启动或已记录启动失败；活动状态继续追加事件时不重复尝试或刷屏。
            return@withLock
        }
        if (current.running) {
            launcher.stop()
        }
        _status.value = when (val result = launcher.start(session.id)) {
            ForegroundSessionStartResult.Started -> ForegroundSessionStatus(
                sessionId = session.id,
                running = true,
            )

            is ForegroundSessionStartResult.Failed -> ForegroundSessionStatus(
                sessionId = session.id,
                running = false,
                failureReason = result.reason,
            )
        }
    }
}
