package com.byd.proximitylab.live

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.target.AssociationDiagnostic
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.IdentityStatus

/**
 * 实时监控环形窗口中的一个点。窗口只用于展示，不限制 Session 时间线的保存长度。
 */
data class LiveReadingPoint(
    val atEpochMillis: Long,
    val atSessionMillis: Long?,
    val observedAtEpochMillis: Long,
    val frameId: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val state: ProximityState,
    val reason: ProximityStateReason,
    val trend: MotionTrend,
)

/**
 * 一次判定后的可观察摘要。监控与模拟测试共用同一结构，避免页面各读一份逻辑。
 */
data class LiveDecisionView(
    val state: ProximityState,
    val reason: ProximityStateReason,
    val trend: MotionTrend,
    val rawRssiDbm: Int?,
    val filteredRssiDbm: Double?,
    val lastObservationAtEpochMillis: Long?,
    val lastDecisionAtEpochMillis: Long,
    val lastDecisionAtSessionMillis: Long?,
    val enterThresholdDbm: Int,
    val exitThresholdDbm: Int,
    val algorithm: ProximityAlgorithm,
    /** 当前判定使用的配置名称；模拟测试运行中为当次快照名称。 */
    val configName: String = "",
    val sampleValidityMillis: Long = 5_000L,
    val confirmation: ConfirmationRequirement = ConfirmationRequirement.ConsecutiveSamples(2),
    val emaAlpha: Double = 0.5,
) {
    companion object {
        fun initial(config: SessionConfigSnapshot): LiveDecisionView = LiveDecisionView(
            state = ProximityState.UNKNOWN,
            reason = ProximityStateReason.AWAITING_OBSERVATION,
            trend = MotionTrend.UNKNOWN,
            rawRssiDbm = null,
            filteredRssiDbm = null,
            lastObservationAtEpochMillis = null,
            lastDecisionAtEpochMillis = 0L,
            lastDecisionAtSessionMillis = null,
            enterThresholdDbm = config.config.enterThresholdDbm,
            exitThresholdDbm = config.config.exitThresholdDbm,
            algorithm = config.config.algorithm,
            configName = config.name,
            sampleValidityMillis = config.config.sampleValidityMillis,
            confirmation = config.config.confirmation,
            emaAlpha = config.config.emaAlpha,
        )

        val EMPTY: LiveDecisionView = initial(BuiltInSessionConfigs.DEFAULT)
    }
}

/**
 * 正在运行或最近一次运行的模拟测试。停止后保留，供界面继续展示最近事件。
 */
data class SimulationTestView(
    val session: SamplingSession,
    val config: SessionConfigSnapshot,
    val decision: LiveDecisionView,
    val samples: List<LiveReadingPoint>,
    val events: List<SessionRunEvent>,
    val states: List<SessionRunState>,
    val running: Boolean,
) {
    val latestEvent: SessionRunEvent?
        get() = events.lastOrNull()
}

/**
 * 05 供给 07、08、11 的实时监控状态。
 *
 * [monitor] 始终反映普通监控；[test] 反映主动模拟测试。界面应在 [SimulationTestView.running] 为真时
 * 展示测试判定，否则回落到普通监控。[test] 在结束后保留，但不再追加事件。
 */
data class LiveMonitorState(
    val binding: BoundTarget? = null,
    val scanState: ScanAvailability = ScanAvailability.Idle,
    val identityStatus: IdentityStatus = IdentityStatus.UNBOUND,
    val identityDetail: String? = null,
    val diagnostics: List<AssociationDiagnostic> = emptyList(),
    val targetObservationCount: Int = 0,
    val droppedTargetEventCount: Int = 0,
    val monitor: LiveDecisionView = LiveDecisionView.EMPTY,
    val monitorSamples: List<LiveReadingPoint> = emptyList(),
    val test: SimulationTestView? = null,
    val message: String? = null,
    /** 共享 [com.byd.proximitylab.session.SessionRecorder] 中正在采集的 Session，供未开始测试时提示。 */
    val activeSession: SamplingSession? = null,
) {
    /** 当前应展示的判定：运行中的模拟测试优先，否则是普通监控。 */
    val displayDecision: LiveDecisionView
        get() = test?.takeIf { it.running }?.decision ?: monitor

    val simulationRunning: Boolean
        get() = test?.running == true

    /** 当前应展示的曲线点：运行中的模拟测试优先，否则是普通监控窗口。 */
    val displaySamples: List<LiveReadingPoint>
        get() = test?.takeIf { it.running }?.samples ?: monitorSamples

    /**
     * 当前应展示的最后观测时刻。样本过期或扫描/身份中断后判定会清空最近读数，
     * 但曲线窗口中的旧点仍可证明最后观测发生在何时。
     */
    val displayLastObservationAtEpochMillis: Long?
        get() = displayDecision.lastObservationAtEpochMillis
            ?: displaySamples.lastOrNull()?.observedAtEpochMillis
}

/**
 * 最近两次独立目标观测的间隔，用于发现丢包或空窗；不足两条时返回 `null`。
 */
fun observationIntervalMillis(samples: List<LiveReadingPoint>): Long? {
    if (samples.size < 2) {
        return null
    }
    val previous = samples[samples.size - 2]
    val latest = samples[samples.size - 1]
    return (latest.observedAtEpochMillis - previous.observedAtEpochMillis).coerceAtLeast(0L)
}
