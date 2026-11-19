package com.byd.proximitylab.feedback

import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.SimulationRunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 消费已持久化的模拟事件并提交本地通知或震动。
 *
 * 只有 [follow] 到运行中的模拟测试 Session 时才消费；每个稳定 `eventId` 最多尝试一次，
 * 已在存储中记录过反馈结果的旧事件不会再次提交。进程重启后没有运行中的 Session，协调器不会
 * 自动恢复或补发旧事件。
 *
 * 反馈写回通过 [feedbackWriter] 完成，生产实现同时更新 Room 与实时界面。通知不可用或提交失败
 * 只更新反馈字段，不改写事件原因、RSSI 或时间。
 */
class SimulationFeedbackCoordinator(
    private val store: SimulationRunStore,
    private val alarm: SimulationAlarm,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val feedbackWriter: SimulationFeedbackWriter = SimulationFeedbackWriter { eventId, status, detail ->
        store.updateEventFeedback(
            eventId = eventId,
            status = status,
            detail = detail,
            atEpochMillis = clock(),
        ) != null
    },
) {

    private val mutex = Mutex()
    private var trackedSessionId: String? = null
    private var subscription: Job? = null
    private var attemptedEventIds: Set<String> = emptySet()

    /**
     * 跟随一个运行中的模拟测试 Session；传入 `null` 时停止消费。
     *
     * 同一 Session 重复调用是幂等的，不会再为已尝试的事件提交反馈。
     */
    suspend fun follow(sessionId: String?) = mutex.withLock {
        if (trackedSessionId == sessionId) {
            return@withLock
        }
        subscription?.cancel()
        subscription = null
        trackedSessionId = sessionId
        attemptedEventIds = emptySet()
        if (sessionId == null) {
            return@withLock
        }
        subscription = scope.launch {
            store.observeEvents(sessionId).collect { events ->
                process(sessionId, events)
            }
        }
    }

    private suspend fun process(expectedSessionId: String, events: List<SessionRunEvent>) {
        mutex.withLock {
            // follow(null) 或切换到新 Session 后，旧订阅不得再提交反馈。
            if (trackedSessionId != expectedSessionId) {
                return@withLock
            }
            val attempted = attemptedEventIds.toMutableSet()
            events.forEach { event ->
                if (event.sessionId != expectedSessionId) {
                    return@forEach
                }
                if (event.eventId in attempted) {
                    return@forEach
                }
                if (event.feedbackStatus != SimulatedEventFeedbackStatus.NOT_ATTEMPTED) {
                    // 已有反馈结果说明之前已尝试或进程重启前已记录；保留原结果，不补发。
                    attempted += event.eventId
                    return@forEach
                }
                // 先登记再提交，避免同一事件在反馈写入触发的再次发射中重复提交。
                attempted += event.eventId
                val capabilities = alarm.capabilities()
                val delivery = try {
                    alarm.deliver(event, capabilities)
                } catch (exception: Exception) {
                    SimulationAlertDelivery.Failed(
                        "提交本地反馈失败：${exception.message ?: exception.javaClass.simpleName}",
                    )
                }
                val (status, detail) = delivery.toFeedback()
                try {
                    feedbackWriter.update(event.eventId, status, detail)
                } catch (_: Exception) {
                    // 进程终止或存储关闭时保持已登记状态，不在本次生命周期内重复提交。
                }
            }
            attemptedEventIds = attempted
        }
    }

    private fun SimulationAlertDelivery.toFeedback(): Pair<SimulatedEventFeedbackStatus, String?> =
        when (this) {
            is SimulationAlertDelivery.Submitted ->
                SimulatedEventFeedbackStatus.DELIVERED to detail

            is SimulationAlertDelivery.Unavailable ->
                SimulatedEventFeedbackStatus.UNAVAILABLE to detail

            is SimulationAlertDelivery.Failed ->
                SimulatedEventFeedbackStatus.FAILED to detail
        }
}
