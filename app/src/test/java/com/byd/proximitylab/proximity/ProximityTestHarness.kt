package com.byd.proximitylab.proximity

import org.junit.Assert.assertEquals

internal const val TARGET_A = "target-a"
internal const val TARGET_B = "target-b"
internal const val DOUBLE_DELTA = 1e-9

internal fun baselineConfig(
    enterThresholdDbm: Int = -65,
    exitThresholdDbm: Int = -80,
    confirmation: ConfirmationRequirement = ConfirmationRequirement.ConsecutiveSamples(2),
    sampleValidityMillis: Long = 5_000,
    emaAlpha: Double = 0.5,
): ProximityConfig = ProximityConfig(
    algorithm = ProximityAlgorithm.BASELINE,
    enterThresholdDbm = enterThresholdDbm,
    exitThresholdDbm = exitThresholdDbm,
    confirmation = confirmation,
    sampleValidityMillis = sampleValidityMillis,
    emaAlpha = emaAlpha,
)

internal fun emaConfig(
    enterThresholdDbm: Int = -65,
    exitThresholdDbm: Int = -80,
    confirmation: ConfirmationRequirement = ConfirmationRequirement.ConsecutiveSamples(2),
    sampleValidityMillis: Long = 5_000,
    emaAlpha: Double = 0.5,
): ProximityConfig = ProximityConfig(
    algorithm = ProximityAlgorithm.EMA,
    enterThresholdDbm = enterThresholdDbm,
    exitThresholdDbm = exitThresholdDbm,
    confirmation = confirmation,
    sampleValidityMillis = sampleValidityMillis,
    emaAlpha = emaAlpha,
)

/**
 * 票据 03 的固定时间线夹具：只替代时间输入，所有判定都走真实的 [ProximityEngine]。
 */
internal class ProximityTestHarness(
    config: ProximityConfig,
    private val defaultTargetId: String = TARGET_A,
) {
    val engine = ProximityEngine(config, defaultTargetId)
    val events = mutableListOf<ProximityEvent>()

    var lastObservation: RssiObservation? = null
        private set

    private var nextFrameId = 1L

    fun observe(
        rssiDbm: Int,
        atMillis: Long,
        observedAtMillis: Long = atMillis,
        targetId: String = defaultTargetId,
        frameId: Long = nextFrameId++,
    ): ProximityDecision {
        val observation = RssiObservation(
            targetId = targetId,
            frameId = frameId,
            rssiDbm = rssiDbm,
            observedAtMillis = observedAtMillis,
        )
        lastObservation = observation
        return record(engine.observe(observation, receivedAtMillis = atMillis))
    }

    fun redeliverLast(atMillis: Long): ProximityDecision {
        val observation = requireNotNull(lastObservation) { "没有可重复投递的观测" }
        return record(engine.observe(observation, receivedAtMillis = atMillis))
    }

    fun advanceTo(atMillis: Long): ProximityDecision = record(engine.advanceTo(atMillis))

    fun scan(available: Boolean, atMillis: Long): ProximityDecision =
        record(engine.setScanAvailable(available, atMillis))

    fun identity(status: TargetIdentityStatus, atMillis: Long): ProximityDecision =
        record(engine.setTargetIdentity(status, atMillis))

    fun rebind(targetId: String, atMillis: Long): ProximityDecision =
        record(engine.rebindTarget(targetId, atMillis))

    fun assertEventCount(expected: Int) {
        assertEquals(expected, events.size)
    }

    private fun record(decision: ProximityDecision): ProximityDecision {
        events += decision.events
        return decision
    }
}
