package com.byd.proximitylab.session

/**
 * 定点采样的 RSSI 统计。
 *
 * [meanDbm] 是算术均值，[medianDbm] 是排序后中间值（偶数个样本取中间两值平均），
 * [standardDeviationDbm] 是总体标准差，作为清楚标注的波动量。空输入没有统计。
 */
data class RssiStatistics(
    val sampleCount: Int,
    val meanDbm: Double,
    val medianDbm: Double,
    val standardDeviationDbm: Double,
) {
    companion object {

        fun calculate(values: List<Int>): RssiStatistics? {
            if (values.isEmpty()) {
                return null
            }
            val sorted = values.sorted()
            val count = sorted.size
            val mean = sorted.sumOf { value -> value.toDouble() } / count
            val median = if (count % 2 == 1) {
                sorted[count / 2].toDouble()
            } else {
                (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0
            }
            val variance = sorted.sumOf { value -> (value - mean) * (value - mean) } / count
            return RssiStatistics(
                sampleCount = count,
                meanDbm = mean,
                medianDbm = median,
                standardDeviationDbm = kotlin.math.sqrt(variance),
            )
        }
    }
}
