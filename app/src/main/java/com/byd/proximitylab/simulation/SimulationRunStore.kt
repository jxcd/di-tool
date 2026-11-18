package com.byd.proximitylab.simulation

import kotlinx.coroutines.flow.Flow

/**
 * 模拟测试原始运行结果与反馈结果的存储入口。生产实现复用 04 的同一 Room 数据库。
 *
 * 本票只让主动运行的模拟测试写入 [SessionRunEvent]；普通监控和 09 回放不写实时历史。
 */
interface SimulationRunStore {

    fun observeEvents(sessionId: String): Flow<List<SessionRunEvent>>

    suspend fun loadEvents(sessionId: String): List<SessionRunEvent>

    fun observeStates(sessionId: String): Flow<List<SessionRunState>>

    suspend fun loadStates(sessionId: String): List<SessionRunState>

    suspend fun appendEvent(event: SessionRunEvent): SessionRunEvent

    suspend fun appendState(state: SessionRunState): SessionRunState

    /**
     * 只更新通知/反馈结果，返回更新后的事件；事件不存在时返回 `null`。
     */
    suspend fun updateEventFeedback(
        eventId: String,
        status: SimulatedEventFeedbackStatus,
        detail: String?,
        atEpochMillis: Long,
    ): SessionRunEvent?
}
