package com.byd.proximitylab.simulation

import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason

/**
 * 模拟事件之后可观察的本地反馈结果。11 在提交通知或震动后更新，只修改反馈字段，不改变事件事实。
 */
enum class SimulatedEventFeedbackStatus(val label: String) {
    NOT_ATTEMPTED("尚未尝试"),
    DELIVERED("已提交"),
    UNAVAILABLE("通知不可用"),
    FAILED("提交失败"),
}

/**
 * 一次主动模拟测试真实产生的 `ENTERED_RANGE` 原始运行结果。
 *
 * [eventId] 是稳定标识，反馈和通知按它关联；[atSessionMillis] 与 [atEpochMillis] 分别用于会话内
 * 对齐和展示。事件事实（原因、RSSI、版本）与 [feedbackStatus] 等通知结果分开保存。
 */
data class SessionRunEvent(
    val eventId: String,
    val sessionId: String,
    val type: ProximityEventType,
    val reason: ProximityEventReason,
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val targetId: String,
    val frameId: Long,
    val observedAtEpochMillis: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
    val feedbackStatus: SimulatedEventFeedbackStatus = SimulatedEventFeedbackStatus.NOT_ATTEMPTED,
    val feedbackDetail: String? = null,
    val feedbackUpdatedAtEpochMillis: Long? = null,
) {
    init {
        require(eventId.isNotBlank()) { "事件标识不能为空" }
        require(sessionId.isNotBlank()) { "事件必须关联 Session" }
        require(targetId.isNotBlank()) { "事件必须关联目标" }
    }
}

/**
 * 模拟测试原始运行结果中的状态变化。
 *
 * 只在状态或原因变化时写入，并保存当时的原始/滤波值与最近有效观测；事件和状态都使用同一会话时基。
 */
data class SessionRunState(
    val id: Long = 0L,
    val sessionId: String,
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val targetId: String,
    val state: ProximityState,
    val reason: ProximityStateReason,
    val trend: MotionTrend,
    val lastReadingFrameId: Long?,
    val lastReadingObservedAtEpochMillis: Long?,
    val rawRssiDbm: Int?,
    val filteredRssiDbm: Double?,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
) {
    init {
        require(sessionId.isNotBlank()) { "运行状态必须关联 Session" }
        require(targetId.isNotBlank()) { "运行状态必须关联目标" }
    }
}
