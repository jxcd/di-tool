package com.byd.proximitylab.session

import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityDecision
import com.byd.proximitylab.proximity.ProximityEngine
import com.byd.proximitylab.proximity.RssiObservation
import com.byd.proximitylab.proximity.TargetIdentityStatus

/**
 * 把 04 保存的会话时间线按处理顺序投递给 03 的共享判定入口，供 05 实时会话和 09 回放复用。
 *
 * 所有时刻都转换为相对 [sessionStartedAtEpochMillis] 的会话毫秒。[SessionTimelineEvent.Observation]
 * 以 `receivedAtEpochMillis` 的判定处理时刻（`processedAtEpochMillis`）作为引擎收到时间，样本年龄由
 * 观测时刻与处理时刻计算；扫描和身份变化使用各自的处理时刻。`EventGap` 只推进会话时间。
 *
 * 会话时间单调不减；系统时钟回拨时把本次推进夹到最后一次已应用时间，避免判定入口拒绝。调用方负责
 * 串行调用，本类不额外加锁。
 */
class SessionTimelineProximityRunner(
    config: ProximityConfig,
    targetId: String,
    private val sessionStartedAtEpochMillis: Long,
) {

    private val engine = ProximityEngine(config, targetId)
    private var lastAppliedSessionMillis: Long = 0L

    val proximityEngine: ProximityEngine
        get() = engine

    /**
     * 应用一条已持久化的会话事件。
     */
    fun apply(event: SessionTimelineEvent): ProximityDecision = when (event) {
        is SessionTimelineEvent.Observation -> engine.observe(
            observation = RssiObservation(
                targetId = event.targetId,
                frameId = event.scanFrameId,
                rssiDbm = event.rawRssiDbm,
                observedAtMillis = sessionTimeOf(event.observedAtEpochMillis),
            ),
            receivedAtMillis = nextSessionTime(event.processedAtEpochMillis),
        )

        is SessionTimelineEvent.ScanStateChanged -> engine.setScanAvailable(
            available = event.available,
            atMillis = nextSessionTime(event.processedAtEpochMillis),
        )

        is SessionTimelineEvent.IdentityChanged -> engine.setTargetIdentity(
            status = if (event.confirmed) {
                TargetIdentityStatus.CONFIRMED
            } else {
                TargetIdentityStatus.UNCONFIRMED
            },
            atMillis = nextSessionTime(event.processedAtEpochMillis),
        )

        is SessionTimelineEvent.EventGap -> advanceTo(event.processedAtEpochMillis)
    }

    /**
     * 按实际处理时间推进会话。用于没有新事件时的样本过期。
     */
    fun advanceTo(atEpochMillis: Long): ProximityDecision =
        engine.advanceTo(nextSessionTime(atEpochMillis))

    /**
     * epoch 时刻到会话毫秒的转换。样本观测时刻可能早于 Session 开始，因此允许负数。
     */
    fun sessionTimeOf(epochMillis: Long): Long = epochMillis - sessionStartedAtEpochMillis

    private fun nextSessionTime(epochMillis: Long): Long {
        val sessionMillis = maxOf(
            sessionTimeOf(epochMillis),
            lastAppliedSessionMillis,
        )
        lastAppliedSessionMillis = sessionMillis
        return sessionMillis
    }
}
