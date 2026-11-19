package com.byd.proximitylab.simulation

import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 事件历史中的一条模拟事件。事件事实与通知结果保持为独立字段，通知未提交或不可用不影响事件可查。
 */
data class SimulationEventHistoryEntry(
    val event: SessionRunEvent,
) {
    val eventId: String
        get() = event.eventId

    val atEpochMillis: Long
        get() = event.atEpochMillis

    val atSessionMillis: Long
        get() = event.atSessionMillis

    val targetId: String
        get() = event.targetId

    val rawRssiDbm: Int
        get() = event.rawRssiDbm

    val filteredRssiDbm: Double
        get() = event.filteredRssiDbm

    val reason: ProximityEventReason
        get() = event.reason

    val algorithmVersion: String
        get() = event.algorithmVersion

    val triggerRuleVersion: String
        get() = event.triggerRuleVersion

    val feedbackStatus: SimulatedEventFeedbackStatus
        get() = event.feedbackStatus

    val feedbackDetail: String?
        get() = event.feedbackDetail
}

/**
 * 最近一次模拟测试及其事件历史。历史从 Room 的 Session、配置快照和原始运行结果重建，
 * 不从实时 UI 状态取样；页面重建或进程重启后仍可查看。
 */
data class SimulationEventHistory(
    val sessionId: String? = null,
    val sessionName: String? = null,
    val targetLabel: String? = null,
    val configName: String? = null,
    val configAlgorithm: ProximityAlgorithm? = null,
    val algorithmVersion: String? = null,
    val triggerRuleVersion: String? = null,
    val sessionStatus: SessionLifecycleStatus? = null,
    val entries: List<SimulationEventHistoryEntry> = emptyList(),
) {
    val hasEvents: Boolean
        get() = entries.isNotEmpty()

    companion object {
        val EMPTY: SimulationEventHistory = SimulationEventHistory()
    }
}

/**
 * 观察最近一条模拟测试的事件历史。
 *
 * 只选择 `SamplingType.SIMULATION` 的最新 Session；新测试开始时历史随之切换，已结束测试的事件仍保留在
 * Room 中，由 08 的完整 Session 管理复用。
 */
class SimulationEventHistoryReader(
    private val sessionStore: SessionStore,
    private val simulationStore: SimulationRunStore,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLatest(): Flow<SimulationEventHistory> =
        sessionStore.observeSessions()
            .map { summaries ->
                summaries.firstOrNull { summary ->
                    summary.session.samplingType == SamplingType.SIMULATION
                }?.session?.id
            }
            .distinctUntilChangedBy { sessionId -> sessionId }
            .flatMapLatest { sessionId ->
                if (sessionId == null) {
                    flowOf(SimulationEventHistory.EMPTY)
                } else {
                    combine(
                        sessionStore.observeSession(sessionId),
                        sessionStore.observeConfigSnapshot(sessionId),
                        simulationStore.observeEvents(sessionId),
                    ) { session, config, events ->
                        historyOf(session, config, events)
                    }
                }
            }

    private fun historyOf(
        session: SamplingSession?,
        config: SessionConfigSnapshot?,
        events: List<SessionRunEvent>,
    ): SimulationEventHistory {
        if (session == null) {
            return SimulationEventHistory.EMPTY
        }
        return SimulationEventHistory(
            sessionId = session.id,
            sessionName = session.name,
            targetLabel = "${session.targetDisplayName ?: "名称未知"}（${session.targetAddress}）",
            configName = config?.name,
            configAlgorithm = config?.config?.algorithm,
            algorithmVersion = config?.algorithmVersion ?: events.firstOrNull()?.algorithmVersion,
            triggerRuleVersion = config?.triggerRuleVersion ?: events.firstOrNull()?.triggerRuleVersion,
            sessionStatus = session.status,
            entries = events.map { event -> SimulationEventHistoryEntry(event) },
        )
    }
}
