package com.byd.proximitylab.proximity

/**
 * 实时与回放共用的纯 Kotlin 接近判定入口。
 *
 * 判定不读取 Android API、系统时间或任何全局状态；所有时间都由调用方显式传入。每次输入按调用顺序
 * 处理，会话时间必须单调不减。处理顺序如下：
 *
 * 1. 先把会话时间推进到本次输入的时刻，并按样本有效期处理过期。
 * 2. 校验观测的 RSSI、时间、目标身份和重复性。
 * 3. 只把新鲜、有效且独立的观测送入滤波和证据累计。
 *
 * 新引擎从 [ProximityState.UNKNOWN] 开始。只有先确认过 `FAR` 才允许下一次确认 `NEAR` 时产生一次
 * [ProximityEventType.ENTERED_RANGE]；停在 `NEAR` 或迟滞区间不会重复。扫描不可用、身份失去确认、
 * 手动改绑或证据过期都会回到 `UNKNOWN` 并撤销触发资格，恢复后需要重新确认 `FAR` 才能再次触发。
 *
 * [RssiObservation.frameId] 必须在同一引擎内唯一标识一帧；重复投递同一帧不会累计证据，但 RSSI 相同
 * 的独立帧可以累计。
 */
class ProximityEngine(
    val config: ProximityConfig,
    initialTargetId: String,
) {
    var boundTargetId: String = validateTargetId(initialTargetId)
        private set

    private var sessionNowMillis: Long = Long.MIN_VALUE
    private var state: ProximityState = ProximityState.UNKNOWN
    private var reason: ProximityStateReason = ProximityStateReason.AWAITING_OBSERVATION
    private var trend: MotionTrend = MotionTrend.UNKNOWN
    private var lastReading: ProximityReading? = null
    private var previousFilteredRssiDbm: Double? = null
    private var latestValidObservedAtMillis: Long? = null

    private var scanAvailable: Boolean = true
    private var identityStatus: TargetIdentityStatus = TargetIdentityStatus.CONFIRMED

    private var farSampleCount: Int = 0
    private var nearSampleCount: Int = 0
    private var farWindowStartMillis: Long? = null
    private var nearWindowStartMillis: Long? = null
    private var armedForEnteredRange: Boolean = false

    private val seenFrameIds = LinkedHashSet<Long>()
    private var rebindFloorMillis: Long? = null

    /**
     * 推进会话时间并处理没有新帧时的样本过期。返回推进后的可观察结果。
     *
     * 会话时间不能倒退；否则抛出 [IllegalArgumentException]。
     */
    fun advanceTo(atMillis: Long): ProximityDecision {
        moveSessionClock(atMillis)
        return decision()
    }

    /**
     * 投递一帧观测。[receivedAtMillis] 是判定收到该帧的会话时间，用于计算样本年龄。
     *
     * 同一帧被重复投递时复用同一个 [RssiObservation.frameId]，判定会拒绝重复项且不累计证据；若该帧
     * 已超过样本有效期，则按过期拒绝。被拒绝的观测可通过 [ProximityDecision.rejection] 诊断。
     */
    fun observe(observation: RssiObservation, receivedAtMillis: Long): ProximityDecision {
        moveSessionClock(receivedAtMillis)

        val rejectionReason = rejectionReasonFor(observation, receivedAtMillis)
        if (rejectionReason != null) {
            return decision(
                rejection = ObservationRejection(
                    observation = observation,
                    receivedAtMillis = receivedAtMillis,
                    reason = rejectionReason,
                ),
            )
        }

        val reading = acceptObservation(observation)
        val event = updateEvidence(reading.filteredRssiDbm, observation)
        return decision(events = listOfNotNull(event))
    }

    /**
     * 更新扫描可用性。停扫会清空证据和触发资格并回到 `UNKNOWN`；恢复后从无观测状态重新开始。
     */
    fun setScanAvailable(available: Boolean, atMillis: Long): ProximityDecision {
        moveSessionClock(atMillis)
        if (scanAvailable == available) {
            return decision()
        }

        scanAvailable = available
        if (!available) {
            invalidate(ProximityStateReason.SCAN_UNAVAILABLE)
        } else if (state == ProximityState.UNKNOWN) {
            reason = recoveryReason()
        }
        return decision()
    }

    /**
     * 更新目标身份确认情况。身份失去确认会清空证据和触发资格并回到 `UNKNOWN`；恢复后需要新的 `FAR`
     * 证据才能再次触发。
     */
    fun setTargetIdentity(status: TargetIdentityStatus, atMillis: Long): ProximityDecision {
        moveSessionClock(atMillis)
        if (identityStatus == status) {
            return decision()
        }

        identityStatus = status
        if (status == TargetIdentityStatus.UNCONFIRMED) {
            invalidate(ProximityStateReason.IDENTITY_UNCONFIRMED)
        } else if (state == ProximityState.UNKNOWN) {
            reason = recoveryReason()
        }
        return decision()
    }

    /**
     * 手动改绑目标。无论目标标识是否变化，都会清空当前证据和触发资格并回到 `UNKNOWN`；改绑时刻及之前
     * 观测的样本不会用于新目标。
     */
    fun rebindTarget(targetId: String, atMillis: Long): ProximityDecision {
        val validatedTargetId = validateTargetId(targetId)
        moveSessionClock(atMillis)

        boundTargetId = validatedTargetId
        rebindFloorMillis = atMillis
        seenFrameIds.clear()
        invalidate(blockingReason(fallback = ProximityStateReason.TARGET_REBOUND))
        return decision()
    }

    private fun moveSessionClock(atMillis: Long) {
        require(atMillis >= sessionNowMillis) {
            "会话时间不能倒退：$atMillis < $sessionNowMillis"
        }
        sessionNowMillis = atMillis
        expireIfNeeded()
    }

    private fun expireIfNeeded() {
        val latestValidAt = latestValidObservedAtMillis ?: return
        if (sessionNowMillis - latestValidAt >= config.sampleValidityMillis) {
            invalidate(blockingReason(fallback = ProximityStateReason.SAMPLES_EXPIRED))
        }
    }

    private fun rejectionReasonFor(
        observation: RssiObservation,
        receivedAtMillis: Long,
    ): ObservationRejectionReason? {
        if (observation.rssiDbm !in ProximityConfig.RSSI_MIN_DBM..ProximityConfig.RSSI_MAX_DBM) {
            return ObservationRejectionReason.RSSI_OUT_OF_RANGE
        }
        if (observation.observedAtMillis > receivedAtMillis) {
            return ObservationRejectionReason.FUTURE_TIMESTAMP
        }
        if (!scanAvailable) {
            return ObservationRejectionReason.SCAN_UNAVAILABLE
        }
        if (identityStatus != TargetIdentityStatus.CONFIRMED) {
            return ObservationRejectionReason.IDENTITY_UNCONFIRMED
        }
        if (observation.targetId != boundTargetId) {
            return ObservationRejectionReason.TARGET_MISMATCH
        }
        if (receivedAtMillis - observation.observedAtMillis >= config.sampleValidityMillis) {
            return ObservationRejectionReason.EXPIRED_SAMPLE
        }
        val floor = rebindFloorMillis
        if (floor != null && observation.observedAtMillis <= floor) {
            return ObservationRejectionReason.OBSERVED_BEFORE_REBIND
        }
        if (observation.frameId in seenFrameIds) {
            return ObservationRejectionReason.DUPLICATE_FRAME
        }
        val latestValidAt = latestValidObservedAtMillis
        if (latestValidAt != null && observation.observedAtMillis < latestValidAt) {
            return ObservationRejectionReason.OUT_OF_ORDER_TIMESTAMP
        }
        return null
    }

    private fun acceptObservation(observation: RssiObservation): ProximityReading {
        val previousFiltered = previousFilteredRssiDbm
        val filteredRssiDbm = when (config.algorithm) {
            ProximityAlgorithm.BASELINE -> observation.rssiDbm.toDouble()
            ProximityAlgorithm.EMA -> if (previousFiltered == null) {
                observation.rssiDbm.toDouble()
            } else {
                config.emaAlpha * observation.rssiDbm +
                    (1.0 - config.emaAlpha) * previousFiltered
            }
        }

        trend = trendBetween(previousFiltered, filteredRssiDbm)
        previousFilteredRssiDbm = filteredRssiDbm
        latestValidObservedAtMillis = observation.observedAtMillis
        rememberFrameId(observation.frameId)

        return ProximityReading(
            targetId = observation.targetId,
            frameId = observation.frameId,
            observedAtMillis = observation.observedAtMillis,
            rawRssiDbm = observation.rssiDbm,
            filteredRssiDbm = filteredRssiDbm,
        ).also { lastReading = it }
    }

    private fun updateEvidence(
        filteredRssiDbm: Double,
        observation: RssiObservation,
    ): ProximityEvent? {
        val previousState = state
        return when {
            filteredRssiDbm >= config.enterThresholdDbm -> {
                resetFarEvidence()
                if (accumulateNearEvidence(observation.observedAtMillis)) {
                    state = ProximityState.NEAR
                    val emitEvent = previousState == ProximityState.FAR && armedForEnteredRange
                    armedForEnteredRange = false
                    reason = if (previousState == ProximityState.NEAR) {
                        ProximityStateReason.NEAR_MAINTAINED
                    } else {
                        ProximityStateReason.CONFIRMED_NEAR
                    }
                    if (emitEvent) createEnteredRangeEvent(observation, filteredRssiDbm) else null
                } else {
                    reason = reasonWhileUnconfirmed(previousState)
                    null
                }
            }

            filteredRssiDbm <= config.exitThresholdDbm -> {
                resetNearEvidence()
                if (accumulateFarEvidence(observation.observedAtMillis)) {
                    state = ProximityState.FAR
                    armedForEnteredRange = true
                    reason = if (previousState == ProximityState.FAR) {
                        ProximityStateReason.FAR_MAINTAINED
                    } else {
                        ProximityStateReason.CONFIRMED_FAR
                    }
                } else {
                    reason = reasonWhileUnconfirmed(previousState)
                }
                null
            }

            else -> {
                resetFarEvidence()
                resetNearEvidence()
                reason = when (previousState) {
                    ProximityState.UNKNOWN -> ProximityStateReason.EVIDENCE_PENDING
                    ProximityState.FAR -> ProximityStateReason.FAR_MAINTAINED
                    ProximityState.NEAR -> ProximityStateReason.NEAR_MAINTAINED
                }
                null
            }
        }
    }

    private fun accumulateFarEvidence(observedAtMillis: Long): Boolean =
        when (val requirement = config.confirmation) {
            is ConfirmationRequirement.ConsecutiveSamples -> {
                farSampleCount += 1
                if (farSampleCount >= requirement.minimumSamples) {
                    farSampleCount = 0
                    true
                } else {
                    false
                }
            }

            is ConfirmationRequirement.Duration -> {
                val windowStart = farWindowStartMillis
                if (windowStart == null) {
                    farWindowStartMillis = observedAtMillis
                    false
                } else if (observedAtMillis - windowStart >= requirement.minimumDurationMillis) {
                    farWindowStartMillis = null
                    true
                } else {
                    false
                }
            }
        }

    private fun accumulateNearEvidence(observedAtMillis: Long): Boolean =
        when (val requirement = config.confirmation) {
            is ConfirmationRequirement.ConsecutiveSamples -> {
                nearSampleCount += 1
                if (nearSampleCount >= requirement.minimumSamples) {
                    nearSampleCount = 0
                    true
                } else {
                    false
                }
            }

            is ConfirmationRequirement.Duration -> {
                val windowStart = nearWindowStartMillis
                if (windowStart == null) {
                    nearWindowStartMillis = observedAtMillis
                    false
                } else if (observedAtMillis - windowStart >= requirement.minimumDurationMillis) {
                    nearWindowStartMillis = null
                    true
                } else {
                    false
                }
            }
        }

    private fun resetFarEvidence() {
        farSampleCount = 0
        farWindowStartMillis = null
    }

    private fun resetNearEvidence() {
        nearSampleCount = 0
        nearWindowStartMillis = null
    }

    private fun reasonWhileUnconfirmed(previousState: ProximityState): ProximityStateReason =
        when (previousState) {
            ProximityState.UNKNOWN -> ProximityStateReason.EVIDENCE_PENDING
            ProximityState.FAR -> ProximityStateReason.FAR_MAINTAINED
            ProximityState.NEAR -> ProximityStateReason.NEAR_MAINTAINED
        }

    private fun invalidate(reason: ProximityStateReason) {
        state = ProximityState.UNKNOWN
        this.reason = reason
        trend = MotionTrend.UNKNOWN
        lastReading = null
        previousFilteredRssiDbm = null
        latestValidObservedAtMillis = null
        resetFarEvidence()
        resetNearEvidence()
        armedForEnteredRange = false
    }

    private fun blockingReason(fallback: ProximityStateReason): ProximityStateReason =
        when {
            !scanAvailable -> ProximityStateReason.SCAN_UNAVAILABLE
            identityStatus != TargetIdentityStatus.CONFIRMED ->
                ProximityStateReason.IDENTITY_UNCONFIRMED

            else -> fallback
        }

    private fun recoveryReason(): ProximityStateReason =
        blockingReason(fallback = ProximityStateReason.AWAITING_OBSERVATION)

    private fun rememberFrameId(frameId: Long) {
        seenFrameIds.add(frameId)
        if (seenFrameIds.size > MAX_TRACKED_FRAME_IDS) {
            val oldest = seenFrameIds.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun createEnteredRangeEvent(
        observation: RssiObservation,
        filteredRssiDbm: Double,
    ): ProximityEvent = ProximityEvent(
        type = ProximityEventType.ENTERED_RANGE,
        reason = ProximityEventReason.CONFIRMED_NEAR_AFTER_CONFIRMED_FAR,
        atMillis = sessionNowMillis,
        targetId = boundTargetId,
        frameId = observation.frameId,
        observedAtMillis = observation.observedAtMillis,
        rawRssiDbm = observation.rssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        algorithmVersion = ALGORITHM_VERSION,
        triggerRuleVersion = TRIGGER_RULE_VERSION,
    )

    private fun decision(
        events: List<ProximityEvent> = emptyList(),
        rejection: ObservationRejection? = null,
    ): ProximityDecision = ProximityDecision(
        atMillis = sessionNowMillis,
        targetId = boundTargetId,
        state = state,
        reason = reason,
        trend = trend,
        lastReading = lastReading,
        events = events,
        rejection = rejection,
        algorithmVersion = ALGORITHM_VERSION,
        triggerRuleVersion = TRIGGER_RULE_VERSION,
    )

    private fun trendBetween(previous: Double?, current: Double): MotionTrend {
        if (previous == null) {
            return MotionTrend.UNKNOWN
        }
        val delta = current - previous
        return when {
            delta >= TREND_DEADBAND_DBM -> MotionTrend.APPROACHING
            delta <= -TREND_DEADBAND_DBM -> MotionTrend.LEAVING
            else -> MotionTrend.STABLE
        }
    }

    private fun validateTargetId(targetId: String): String {
        require(targetId.isNotBlank()) { "目标标识不能为空" }
        return targetId
    }

    companion object {
        /** 当前滤波与状态算法实现的版本标识。 */
        const val ALGORITHM_VERSION: String = "proximity-algorithms/1"

        /** 当前触发规则实现的版本标识。 */
        const val TRIGGER_RULE_VERSION: String = "confirmed-far-then-near/1"

        private const val TREND_DEADBAND_DBM = 2.0

        // 只保留最近的帧标识控制内存；更早的重复投递仍会因观测时刻倒退或过期被拒绝。
        private const val MAX_TRACKED_FRAME_IDS = 4_096
    }
}
