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
     * 恢复时批量标记遗留的采集中 Session，返回标记数量。
     */
    suspend fun markRecordingSessionsInterrupted(
        detectedAtEpochMillis: Long,
    ): Int
}
