package com.byd.proximitylab.replay

import kotlinx.coroutines.flow.Flow

/**
 * 回放结果的本地存储入口。生产实现复用 [com.byd.proximitylab.data.ProximityLabDatabase]，
 * 与实时 Session、配置快照和模拟事件分开保存。
 */
interface SessionReplayStore {

    /** 按创建时间倒序观察某个 Session 的回放摘要，供详情历史和 10 选择结果。 */
    fun observeReplaySummaries(sessionId: String): Flow<List<SessionReplaySummary>>

    suspend fun loadReplaySummaries(sessionId: String): List<SessionReplaySummary>

    /** 读取一条完整回放，包括滤波样本、状态变化和事件。 */
    suspend fun loadReplay(replayId: String): SessionReplayRecord?

    suspend fun loadReplays(sessionId: String): List<SessionReplayRecord>

    suspend fun saveReplay(record: SessionReplayRecord): SessionReplayRecord
}
