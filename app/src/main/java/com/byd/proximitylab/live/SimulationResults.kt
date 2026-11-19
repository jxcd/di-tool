package com.byd.proximitylab.live

import com.byd.proximitylab.session.SamplingSession

/**
 * 开始前台模拟测试的结果。
 */
sealed interface SimulationStartResult {

    data class Started(val session: SamplingSession) : SimulationStartResult

    /**
     * 未满足开始条件。已有普通采样、已有模拟测试或目标未绑定时会返回可展示的原因。
     */
    data class Blocked(
        val session: SamplingSession?,
        val reason: String,
    ) : SimulationStartResult

    data object NotBound : SimulationStartResult

    /**
     * 11：缺少 `BLUETOOTH_SCAN`，未创建 Session，也未启动前台服务。
     */
    data object ScanPermissionMissing : SimulationStartResult
}

/**
 * 结束前台模拟测试的结果。
 */
sealed interface SimulationStopResult {

    data class Stopped(val session: SamplingSession) : SimulationStopResult

    data object NotRunning : SimulationStopResult
}
