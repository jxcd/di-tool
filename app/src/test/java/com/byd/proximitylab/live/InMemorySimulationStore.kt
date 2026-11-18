package com.byd.proximitylab.live

import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.SimulationRunStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JVM 实时会话测试使用的运行结果存储。
 *
 * 只替代 Room 持久化；判定、事件生成、状态转换和反馈更新仍走生产实现，真实 Room 语义由 androidTest 覆盖。
 */
class InMemorySimulationStore : SimulationRunStore {

    private val mutex = Mutex()
    private val events = MutableStateFlow<Map<String, List<SessionRunEvent>>>(emptyMap())
    private val states = MutableStateFlow<Map<String, List<SessionRunState>>>(emptyMap())
    private var nextStateId = 1L

    override fun observeEvents(sessionId: String): Flow<List<SessionRunEvent>> =
        events.map { stored -> stored[sessionId].orEmpty() }

    override suspend fun loadEvents(sessionId: String): List<SessionRunEvent> = mutex.withLock {
        events.value[sessionId].orEmpty()
    }

    override fun observeStates(sessionId: String): Flow<List<SessionRunState>> =
        states.map { stored -> stored[sessionId].orEmpty() }

    override suspend fun loadStates(sessionId: String): List<SessionRunState> = mutex.withLock {
        states.value[sessionId].orEmpty()
    }

    override suspend fun appendEvent(event: SessionRunEvent): SessionRunEvent = mutex.withLock {
        events.update { stored ->
            stored + (event.sessionId to (stored[event.sessionId].orEmpty() + event))
        }
        event
    }

    override suspend fun appendState(state: SessionRunState): SessionRunState = mutex.withLock {
        val stored = state.copy(id = nextStateId++)
        states.update { current ->
            current + (state.sessionId to (current[state.sessionId].orEmpty() + stored))
        }
        stored
    }

    override suspend fun updateEventFeedback(
        eventId: String,
        status: SimulatedEventFeedbackStatus,
        detail: String?,
        atEpochMillis: Long,
    ): SessionRunEvent? = mutex.withLock {
        var updated: SessionRunEvent? = null
        events.update { stored ->
            stored.mapValues { (_, sessionEvents) ->
                sessionEvents.map { event ->
                    if (event.eventId == eventId) {
                        event.copy(
                            feedbackStatus = status,
                            feedbackDetail = detail,
                            feedbackUpdatedAtEpochMillis = atEpochMillis,
                        ).also { updated = it }
                    } else {
                        event
                    }
                }
            }
        }
        updated
    }
}
