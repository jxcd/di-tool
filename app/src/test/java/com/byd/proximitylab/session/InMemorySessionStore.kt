package com.byd.proximitylab.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JVM 会话生命周期测试使用的内存存储。
 *
 * 只在 JVM 上替代 Android Room 持久化；SessionRecorder 的生命周期、时间顺序和覆盖终点逻辑仍走生产实现。
 * 真实 Room 语义（关闭重开、迁移、同一时刻顺序）由 androidTest 验证。
 */
class InMemorySessionStore : SessionStore {

    private val mutex = Mutex()
    private val sessions = MutableStateFlow<Map<String, SamplingSession>>(emptyMap())
    private val timelines = MutableStateFlow<Map<String, List<SessionTimelineItem>>>(emptyMap())
    private val snapshots = MutableStateFlow<Map<String, SessionConfigSnapshot>>(emptyMap())
    private var nextSequence: Long = 1L

    override fun observeSessions(): Flow<List<SessionSummary>> = sessions.map { stored ->
        stored.values
            .sortedWith(compareByDescending<SamplingSession> { it.startedAtEpochMillis }.thenByDescending { it.id })
            .map { session -> SessionSummary(session, observationCount(session.id)) }
    }

    override fun observeSession(sessionId: String): Flow<SamplingSession?> =
        sessions.map { it[sessionId] }

    override fun observeTimeline(sessionId: String): Flow<List<SessionTimelineItem>> =
        timelines.map { it[sessionId].orEmpty().sortedBy { item -> item.sequence } }

    override suspend fun loadSession(sessionId: String): SamplingSession? = mutex.withLock {
        sessions.value[sessionId]
    }

    override suspend fun loadTimeline(sessionId: String): List<SessionTimelineItem> = mutex.withLock {
        timelines.value[sessionId].orEmpty().sortedBy { item -> item.sequence }
    }

    override suspend fun createSession(session: SamplingSession) = mutex.withLock {
        check(session.id !in sessions.value) { "Session 已存在：${session.id}" }
        sessions.update { it + (session.id to session) }
    }

    override suspend fun createSimulationSession(
        session: SamplingSession,
        configSnapshot: SessionConfigSnapshot,
    ) = mutex.withLock {
        check(session.id !in sessions.value) { "Session 已存在：${session.id}" }
        sessions.update { it + (session.id to session) }
        snapshots.update { it + (session.id to configSnapshot) }
    }

    override fun observeConfigSnapshot(sessionId: String): Flow<SessionConfigSnapshot?> =
        snapshots.map { it[sessionId] }

    override suspend fun loadConfigSnapshot(sessionId: String): SessionConfigSnapshot? =
        mutex.withLock { snapshots.value[sessionId] }

    override suspend fun saveConfigSnapshot(
        sessionId: String,
        configSnapshot: SessionConfigSnapshot,
    ) = mutex.withLock {
        snapshots.update { it + (sessionId to configSnapshot) }
    }

    override suspend fun appendTimeline(
        sessionId: String,
        event: SessionTimelineEvent,
    ): SessionTimelineItem = mutex.withLock {
        val existing = sessions.value[sessionId] ?: error("Session 不存在：$sessionId")
        val item = SessionTimelineItem(
            sequence = nextSequence++,
            sessionId = sessionId,
            event = event,
        )
        timelines.update { stored ->
            stored + (sessionId to (stored[sessionId].orEmpty() + item))
        }
        sessions.update { stored ->
            stored + (
                sessionId to existing.copy(
                    lastRecordedAtEpochMillis = maxOf(
                        existing.lastRecordedAtEpochMillis,
                        event.processedAtEpochMillis,
                    ),
                )
                )
        }
        item
    }

    override suspend fun completeSession(
        sessionId: String,
        endedAtEpochMillis: Long,
    ): SamplingSession? = mutex.withLock {
        val existing = sessions.value[sessionId] ?: return@withLock null
        if (existing.status != SessionLifecycleStatus.RECORDING) {
            return@withLock existing
        }
        val completed = existing.copy(
            status = SessionLifecycleStatus.COMPLETED,
            endedAtEpochMillis = maxOf(
                endedAtEpochMillis,
                existing.startedAtEpochMillis,
                existing.lastRecordedAtEpochMillis,
            ),
            lastRecordedAtEpochMillis = maxOf(existing.lastRecordedAtEpochMillis, endedAtEpochMillis),
            interruptedDetectedAtEpochMillis = null,
        )
        sessions.update { it + (sessionId to completed) }
        completed
    }

    override suspend fun markInterrupted(
        sessionId: String,
        detectedAtEpochMillis: Long,
    ): SamplingSession? = mutex.withLock {
        val existing = sessions.value[sessionId] ?: return@withLock null
        if (existing.status != SessionLifecycleStatus.RECORDING) {
            return@withLock existing
        }
        val interrupted = existing.copy(
            status = SessionLifecycleStatus.INTERRUPTED,
            endedAtEpochMillis = null,
            interruptedDetectedAtEpochMillis = detectedAtEpochMillis,
        )
        sessions.update { it + (sessionId to interrupted) }
        interrupted
    }

    override suspend fun loadRecordingSessions(): List<SamplingSession> = mutex.withLock {
        sessions.value.values.filter { it.status == SessionLifecycleStatus.RECORDING }
    }

    override suspend fun deleteSession(sessionId: String) = mutex.withLock {
        sessions.update { it - sessionId }
        timelines.update { it - sessionId }
        snapshots.update { it - sessionId }
    }

    override suspend fun markRecordingSessionsInterrupted(
        detectedAtEpochMillis: Long,
    ): Int = mutex.withLock {
        val recording = sessions.value.values
            .filter { it.status == SessionLifecycleStatus.RECORDING }
        sessions.update { stored ->
            stored + recording.associate { session ->
                session.id to session.copy(
                    status = SessionLifecycleStatus.INTERRUPTED,
                    endedAtEpochMillis = null,
                    interruptedDetectedAtEpochMillis = detectedAtEpochMillis,
                )
            }
        }
        recording.size
    }

    private fun observationCount(sessionId: String): Int =
        timelines.value[sessionId].orEmpty().count { item ->
            item.event is SessionTimelineEvent.Observation
        }
}
