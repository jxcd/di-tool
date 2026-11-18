package com.byd.proximitylab.proximity

/**
 * 接近判定使用的滤波算法。
 */
enum class ProximityAlgorithm {
    /** 直接使用原始 RSSI，不平滑。 */
    BASELINE,

    /** 使用指数滑动平均平滑原始 RSSI。 */
    EMA,
}

/**
 * 确认 `FAR` 或 `NEAR` 所需的证据形式。两种形式都只接受新鲜、独立且落在同一侧的观测。
 */
sealed interface ConfirmationRequirement {

    /**
     * 连续 [minimumSamples] 条独立有效观测。
     */
    data class ConsecutiveSamples(val minimumSamples: Int) : ConfirmationRequirement

    /**
     * 同一侧连续有效观测至少跨越 [minimumDurationMillis] 毫秒。
     */
    data class Duration(val minimumDurationMillis: Long) : ConfirmationRequirement
}

/**
 * 一次接近判定使用的参数。构造时完成校验，非法参数直接抛出 [IllegalArgumentException]。
 *
 * 进入阈值必须强于离开阈值，即 [enterThresholdDbm] 的数值大于 [exitThresholdDbm]。阈值本身属于
 * 对应一侧：滤波值大于等于进入阈值视为近端候选，小于等于离开阈值视为远端候选。
 */
data class ProximityConfig(
    val algorithm: ProximityAlgorithm,
    val enterThresholdDbm: Int,
    val exitThresholdDbm: Int,
    val confirmation: ConfirmationRequirement,
    val sampleValidityMillis: Long,
    val emaAlpha: Double,
) {
    init {
        require(enterThresholdDbm in RSSI_MIN_DBM..RSSI_MAX_DBM) {
            "进入阈值 $enterThresholdDbm dBm 超出 $RSSI_MIN_DBM..$RSSI_MAX_DBM dBm"
        }
        require(exitThresholdDbm in RSSI_MIN_DBM..RSSI_MAX_DBM) {
            "离开阈值 $exitThresholdDbm dBm 超出 $RSSI_MIN_DBM..$RSSI_MAX_DBM dBm"
        }
        require(enterThresholdDbm > exitThresholdDbm) {
            "进入阈值必须强于离开阈值：$enterThresholdDbm 必须大于 $exitThresholdDbm"
        }
        require(sampleValidityMillis > 0) {
            "样本有效期必须大于 0 ms：$sampleValidityMillis"
        }
        when (confirmation) {
            is ConfirmationRequirement.ConsecutiveSamples -> require(confirmation.minimumSamples >= 1) {
                "连续样本数必须大于等于 1：${confirmation.minimumSamples}"
            }

            is ConfirmationRequirement.Duration -> require(confirmation.minimumDurationMillis > 0) {
                "确认时长必须大于 0 ms：${confirmation.minimumDurationMillis}"
            }
        }
        require(emaAlpha.isFinite() && emaAlpha > 0.0 && emaAlpha <= 1.0) {
            "EMA 参数必须落在 (0, 1]：$emaAlpha"
        }
    }

    companion object {
        /** Android `ScanResult.getRssi()` 可报告的 RSSI 下限。 */
        const val RSSI_MIN_DBM: Int = -127

        /** Android `ScanResult.getRssi()` 可报告的 RSSI 上限。 */
        const val RSSI_MAX_DBM: Int = 126
    }
}
