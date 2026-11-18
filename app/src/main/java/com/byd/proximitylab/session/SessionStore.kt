package com.byd.proximitylab.session

import kotlinx.coroutines.flow.Flow

/**
 * Session 与时间线的本地存储入口。生产实现是 [com.byd.proximitylab.data.ProximityLabDatabase]；
 * 05、08、09、11 复用同一存储和同一数据库。
 */
interface SessionStore {

    fun observeSessions(): Flow<List<SessionSummary>>

    fun observeSession(sessionId: String): Flow<SamplingSession?>

    fun observeTimeline(sessionId: String): Flow<List<SessionTimelineItem>>

    suspend fun loadSession(sessionId: String): SamplingSession?

    suspend fun loadTimeline(sessionId: String): List<SessionTimelineItem>

    suspend fun createSession(session: SamplingSession)

    /**
     * 在同一事务中创建 Session 并保存当次配置快照。
     *
     * 默认实现用于简单存储；Room 实现应覆盖为单事务，避免出现有 Session 但没有快照的记录。
     */
    suspend fun createSimulationSession(
        session: SamplingSession,
        configSnapshot: SessionConfigSnapshot,
    ) {
        createSession(session)
        saveConfigSnapshot(session.id, configSnapshot)
    }

    fun observeConfigSnapshot(sessionId: String): Flow<SessionConfigSnapshot?>

    suspend fun loadConfigSnapshot(sessionId: String): SessionConfigSnapshot?

    suspend fun saveConfigSnapshot(
        sessionId: String,
        configSnapshot: SessionConfigSnapshot,
    )

    /**
     * 按调用顺序追加一条时间线事件，返回数据库分配的稳定顺序。
     */
    suspend fun appendTimeline(
        sessionId: String,
        event: SessionTimelineEvent,
    ): SessionTimelineItem

    /**
     * 正常结束。结束时刻是用户确认的结束时间，同时成为覆盖终点。
     */
    suspend fun completeSession(
        sessionId: String,
        endedAtEpochMillis: Long,
    ): SamplingSession?

    /**
     * 把遗留的采集中 Session 标为中断。只更新状态和诊断时刻，不修改覆盖终点或时间线。
     */
    suspend fun markInterrupted(
        sessionId: String,
        detectedAtEpochMillis: Long,
    ): SamplingSession?

    suspend fun loadRecordingSessions(): List<SamplingSession>

    /**
     * 删除指定 Session 及其时间线、快照和运行结果。供 08 的 Session 管理和测试清理复用。
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * 恢复时批量标记遗留的采集中 Session，返回标记数量。
     */
    suspend fun markRecordingSessionsInterrupted(
        detectedAtEpochMillis: Long,
    ): Int
}
