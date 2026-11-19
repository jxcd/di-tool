package com.byd.proximitylab.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 采样期间的扫描条件看门狗。
 *
 * Android 在运行中撤销 `BLUETOOTH_SCAN` 时不会保证再回调扫描结果，仅靠下一次广播无法发现权限失效。
 * 看门狗在 Session 存续期间周期调用扫描来源的 `revalidate()`，让 BLE 来源把可观察的权限撤销写入
 * 扫描状态，再由共享录制器写入时间线。App 空闲时等待下一个 Session，不做无意义轮询。
 *
 * 看门狗只负责触发检查；权限判断和状态变化仍由 BLE 来源与目标观测入口处理。
 */
class SessionScanWatchdog(
    private val activeSession: StateFlow<SamplingSession?>,
    private val revalidate: () -> Unit,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val resume: () -> Unit = {},
) {

    private var job: Job? = null

    fun start() {
        if (job != null || intervalMillis <= 0L) {
            return
        }
        job = scope.launch {
            while (isActive) {
                if (activeSession.value != null) {
                    checkOnce()
                    delay(intervalMillis)
                } else {
                    // 没有活动 Session 时挂起等待，避免常驻轮询。
                    activeSession.first { session -> session != null }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 立即执行一次与周期检查相同的判断；测试与显式刷新复用。 */
    suspend fun checkNow() {
        if (activeSession.value != null) {
            checkOnce()
        }
    }

    private fun checkOnce() {
        revalidate()
        resume()
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000L
    }
}
