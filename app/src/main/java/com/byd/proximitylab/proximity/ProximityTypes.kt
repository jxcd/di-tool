package com.byd.proximitylab.proximity

/**
 * 已确认的接近状态。`UNKNOWN` 表示当前没有足够的新鲜证据。
 */
enum class ProximityState {
    UNKNOWN,
    FAR,
    NEAR,
}

/**
 * 运动趋势，独立于接近状态。只作为辅助信息，不参与是否触发的判断。
 */
enum class MotionTrend {
    UNKNOWN,
    APPROACHING,
    LEAVING,
    STABLE,
}

/**
 * 当前状态的原因。调用方可直接记录或展示，用于区分没有观测、证据不足、扫描或身份中断与过期。
 */
enum class ProximityStateReason {
    AWAITING_OBSERVATION,
    EVIDENCE_PENDING,
    SCAN_UNAVAILABLE,
    IDENTITY_UNCONFIRMED,
    TARGET_REBOUND,
    SAMPLES_EXPIRED,
    CONFIRMED_FAR,
    FAR_MAINTAINED,
    CONFIRMED_NEAR,
    NEAR_MAINTAINED,
}

/**
 * 绑定目标的身份确认情况。
 */
enum class TargetIdentityStatus {
    CONFIRMED,
    UNCONFIRMED,
}

/**
 * 交给判定入口的一帧 RSSI 观测。
 *
 * [frameId] 用于区分独立样本：同一次扫描结果被重复投递时必须复用同一个值；不同扫描结果即使是同一
 * RSSI 也必须使用不同值。[observedAtMillis] 是样本自身的观测时刻，判定用它计算样本年龄。
 */
data class RssiObservation(
    val targetId: String,
    val frameId: Long,
    val rssiDbm: Int,
    val observedAtMillis: Long,
)

/**
 * 最近一条被接受的有效观测及其滤波结果。
 */
data class ProximityReading(
    val targetId: String,
    val frameId: Long,
    val observedAtMillis: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
)

/**
 * 观测被拒绝的原因。被拒绝的观测不会进入滤波、证据或有效观测时刻。
 */
enum class ObservationRejectionReason {
    RSSI_OUT_OF_RANGE,
    FUTURE_TIMESTAMP,
    SCAN_UNAVAILABLE,
    IDENTITY_UNCONFIRMED,
    TARGET_MISMATCH,
    EXPIRED_SAMPLE,
    OBSERVED_BEFORE_REBIND,
    DUPLICATE_FRAME,
    OUT_OF_ORDER_TIMESTAMP,
}

/**
 * 一帧被拒绝观测的诊断信息；原始值保留在这里供调用方写入原始时间线。
 */
data class ObservationRejection(
    val observation: RssiObservation,
    val receivedAtMillis: Long,
    val reason: ObservationRejectionReason,
)

/**
 * 判定产生的事件类型。
 */
enum class ProximityEventType {
    ENTERED_RANGE,
}

/**
 * 事件产生的原因。
 */
enum class ProximityEventReason {
    CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
}

/**
 * 一次 `ENTERED_RANGE` 事件。事件自带算法与触发规则版本，调用方可原样持久化。
 */
data class ProximityEvent(
    val type: ProximityEventType,
    val reason: ProximityEventReason,
    val atMillis: Long,
    val targetId: String,
    val frameId: Long,
    val observedAtMillis: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
)

/**
 * 一次输入处理后的可观察结果。
 *
 * [lastReading] 是最近一条被接受的有效观测；被拒绝的观测不会更新它，也不会更新有效观测时刻。
 * [events] 只包含本次输入产生的事件。
 */
data class ProximityDecision(
    val atMillis: Long,
    val targetId: String,
    val state: ProximityState,
    val reason: ProximityStateReason,
    val trend: MotionTrend,
    val lastReading: ProximityReading?,
    val events: List<ProximityEvent>,
    val rejection: ObservationRejection?,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
)
