package com.byd.proximitylab.chart

import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason

/**
 * 实时监控可切换的近期时间窗。时间窗只影响图表展示范围，不改变 Session 记录或判定输入。
 */
enum class RssiChartWindow(
    val label: String,
    val durationMillis: Long,
) {
    THIRTY_SECONDS("30 秒", 30_000L),
    ONE_MINUTE("1 分钟", 60_000L),
    FIVE_MINUTES("5 分钟", 300_000L),
    ;

    companion object {
        val DEFAULT: RssiChartWindow = ONE_MINUTE
    }
}

/**
 * 图表曲线来源。原始与滤波曲线使用同一时间轴；08/10 可继续增加滤波曲线。
 */
enum class RssiSeriesKind {
    RAW,
    FILTERED,
}

enum class RssiChartThresholdKind {
    ENTER,
    EXIT,
    OTHER,
}

enum class RssiChartMarkerKind {
    EVENT,
    REFERENCE,
    STATE,
}

/**
 * 图表的 Y 轴范围（dBm）。上下界保持固定时，阈值线在切换时间窗后仍落在同一高度。
 */
data class RssiChartDomain(
    val minDbm: Double,
    val maxDbm: Double,
) {
    init {
        require(maxDbm > minDbm) { "图表 dBm 范围必须大于 0：$minDbm..$maxDbm" }
    }
}

data class RssiChartPoint(
    val atEpochMillis: Long,
    val rssiDbm: Double,
)

/**
 * 一条只读曲线。点按时间递增排列；同一时间允许存在多个点（稳定排序保留输入顺序）。
 */
data class RssiChartSeriesInput(
    val id: String,
    val label: String,
    val kind: RssiSeriesKind,
    val points: List<RssiChartPoint>,
) {
    init {
        require(id.isNotBlank()) { "曲线标识不能为空" }
        require(label.isNotBlank()) { "曲线名称不能为空" }
    }
}

data class RssiChartThreshold(
    val label: String,
    val rssiDbm: Double,
    val kind: RssiChartThresholdKind,
) {
    init {
        require(label.isNotBlank()) { "阈值名称不能为空" }
    }
}

/**
 * 图表上的一个时刻标记。07 使用模拟事件，08/10 可复用为人工参考标记。
 */
data class RssiChartMarkerInput(
    val id: String,
    val label: String,
    val atEpochMillis: Long,
    val kind: RssiChartMarkerKind = RssiChartMarkerKind.EVENT,
    val rssiDbm: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "标记标识不能为空" }
        require(label.isNotBlank()) { "标记名称不能为空" }
    }
}

data class RssiChartStateMarkerInput(
    val atEpochMillis: Long,
    val state: ProximityState,
    val reason: ProximityStateReason? = null,
)

/**
 * 图表构建输入。只依赖已经采集或回放得到的只读时间线，不读取扫描、通知或 Room。
 *
 * [endEpochMillis] 是当前展示窗口的右边界，通常取最近样本、最近判定和事件的较晚者。
 * [gapThresholdMillis] 超过该时长的相邻点会被断线，从而保留真实空窗。
 * [maxDisplayPointsPerSeries] 是每条曲线的展示点上限；抽点只影响图表，原始数据保持完整。
 */
data class RssiChartInput(
    val series: List<RssiChartSeriesInput> = emptyList(),
    val thresholds: List<RssiChartThreshold> = emptyList(),
    val markers: List<RssiChartMarkerInput> = emptyList(),
    val stateMarkers: List<RssiChartStateMarkerInput> = emptyList(),
    val window: RssiChartWindow = RssiChartWindow.DEFAULT,
    val endEpochMillis: Long,
    val gapThresholdMillis: Long = DEFAULT_GAP_THRESHOLD_MILLIS,
    val maxDisplayPointsPerSeries: Int = DEFAULT_MAX_DISPLAY_POINTS,
    /**
     * 显式 Y 轴范围。为空时按全部输入点和阈值计算，计算不随窗口裁剪变化。
     */
    val domain: RssiChartDomain? = null,
) {
    init {
        require(gapThresholdMillis > 0L) { "空窗断线阈值必须大于 0：$gapThresholdMillis" }
        require(maxDisplayPointsPerSeries >= 2) {
            "每条曲线的展示点上限至少为 2：$maxDisplayPointsPerSeries"
        }
    }

    companion object {
        const val DEFAULT_GAP_THRESHOLD_MILLIS: Long = 5_000L
        const val DEFAULT_MAX_DISPLAY_POINTS: Int = 120
    }
}

data class RssiChartSegment(
    val points: List<RssiChartPoint>,
) {
    val isDrawable: Boolean
        get() = points.isNotEmpty()
}

data class RssiChartSeriesData(
    val id: String,
    val label: String,
    val kind: RssiSeriesKind,
    val segments: List<RssiChartSegment>,
    /** 输入中的全部点数，不随窗口或抽点变化。 */
    val sourcePointCount: Int,
    /** 落在当前时间窗内的点数。 */
    val windowPointCount: Int,
    /** 抽点后实际绘制的点数。 */
    val displayedPointCount: Int,
)

data class RssiChartThresholdLine(
    val label: String,
    val rssiDbm: Double,
    val kind: RssiChartThresholdKind,
    val yFraction: Float,
)

data class RssiChartMarkerData(
    val id: String,
    val label: String,
    val atEpochMillis: Long,
    val kind: RssiChartMarkerKind,
    val rssiDbm: Int?,
    val xFraction: Float,
)

data class RssiChartStateMarkerData(
    val atEpochMillis: Long,
    val state: ProximityState,
    val reason: ProximityStateReason?,
    val xFraction: Float,
)

/**
 * 画布可直接消费的只读图表数据。`xFraction` 与 `yFraction` 都落在 `[0, 1]`，
 * `yFraction` 以顶部为 0、底部为 1。
 */
data class RssiChartData(
    val window: RssiChartWindow,
    val windowStartEpochMillis: Long,
    val windowEndEpochMillis: Long,
    val domain: RssiChartDomain,
    val series: List<RssiChartSeriesData>,
    val thresholds: List<RssiChartThresholdLine>,
    val markers: List<RssiChartMarkerData>,
    val stateMarkers: List<RssiChartStateMarkerData>,
    val sourcePointCount: Int,
    val displayedPointCount: Int,
    val segmentCount: Int,
) {
    val hasData: Boolean
        get() = displayedPointCount > 0

    val rawSeries: RssiChartSeriesData?
        get() = series.firstOrNull { it.kind == RssiSeriesKind.RAW }

    val filteredSeries: RssiChartSeriesData?
        get() = series.firstOrNull { it.kind == RssiSeriesKind.FILTERED }

    fun xFraction(atEpochMillis: Long): Float {
        val span = windowEndEpochMillis - windowStartEpochMillis
        if (span <= 0L) {
            return 0f
        }
        val fraction = (atEpochMillis - windowStartEpochMillis).toDouble() / span
        return fraction.coerceIn(0.0, 1.0).toFloat()
    }

    fun yFraction(rssiDbm: Double): Float {
        val span = domain.maxDbm - domain.minDbm
        if (span <= 0.0) {
            return 0f
        }
        val fraction = (domain.maxDbm - rssiDbm) / span
        return fraction.coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * 图表摘要文案。UI 与测试共用同一份窗口、阈值、点数和断线口径。
 */
object RssiChartSummary {

    fun text(data: RssiChartData): String = buildString {
        append("窗口 ${data.window.label}")
        data.series.forEach { series ->
            append(" · ${series.label} ${series.displayedPointCount}/${series.sourcePointCount} 点")
        }
        data.thresholds.firstOrNull { it.kind == RssiChartThresholdKind.ENTER }?.let { enter ->
            append(" · 进入 ${formatRssi(enter.rssiDbm)} dBm")
        }
        data.thresholds.firstOrNull { it.kind == RssiChartThresholdKind.EXIT }?.let { exit ->
            append(" · 离开 ${formatRssi(exit.rssiDbm)} dBm")
        }
        append(" · 标记 ${data.markers.size}")
        val gapCount = (data.segmentCount - data.series.count { it.segments.isNotEmpty() })
            .coerceAtLeast(0)
        if (gapCount > 0) {
            append(" · 断线 $gapCount 处")
        }
    }

    private fun formatRssi(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", value)
}
