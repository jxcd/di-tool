package com.byd.proximitylab.session

import com.byd.proximitylab.target.BoundTarget

/**
 * 采样类型。04 落地定点采样，05 增加前台模拟测试；08 复用同一模型增加行走采样。
 */
enum class SamplingType(val label: String) {
    FIXED_POINT("定点采样"),
    WALKING("行走采样"),
    SIMULATION("模拟测试"),
}

/**
 * 定点采样位置。车内外在 V1 用人工位置标签记录。
 */
enum class FixedSampleLocation(val label: String) {
    FRONT("车头"),
    REAR("车尾"),
    LEFT_SIDE("左侧"),
    RIGHT_SIDE("右侧"),
    DRIVER_SEAT("主驾"),
    PASSENGER_SEAT("副驾"),
    REAR_SEAT("后排"),
    OTHER("其他"),
}

/**
 * 手机放置方式，用于比较不同携带条件下的信号。
 */
enum class PhonePlacement(val label: String) {
    HANDHELD("手持"),
    POCKET("口袋"),
    BACKPACK("背包"),
    OTHER("其他"),
}

/**
 * Session 生命周期。
 *
 * [RECORDING] 只会在 App 进程存活期间出现；恢复时发现遗留的 [RECORDING] 会被标为 [INTERRUPTED]。
 */
enum class SessionLifecycleStatus(val label: String) {
    RECORDING("采集中"),
    COMPLETED("正常结束"),
    INTERRUPTED("中断"),
}

/**
 * 一次采样 Session。
 *
 * 时间字段都是 UTC epoch 毫秒，属于同一时间基准；运行时判断使用相对 [startedAtEpochMillis] 的会话时间，
 * 格式化后的日期时间只用于显示。
 *
 * - [endedAtEpochMillis] 只在正常结束时写入，是用户确认的结束时刻。
 * - [lastRecordedAtEpochMillis] 是可证明已写入时间线的最晚处理时刻，也是中断 Session 的覆盖终点。
 * - [interruptedDetectedAtEpochMillis] 是恢复时发现中断的时刻，只用于诊断，不能充当 Session 的结束时刻。
 */
data class SamplingSession(
    val id: String,
    val targetId: String,
    val targetDisplayName: String?,
    val targetAddress: String,
    val samplingType: SamplingType,
    val name: String,
    val location: FixedSampleLocation,
    val estimatedDistanceMeters: Double?,
    val phonePlacement: PhonePlacement,
    val status: SessionLifecycleStatus,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val lastRecordedAtEpochMillis: Long,
    val interruptedDetectedAtEpochMillis: Long?,
    /**
     * 整次试验的人工标签。只影响评价，不修改原始时间线；08 起可编辑。
     */
    val evaluationLabel: SessionEvaluationLabel = SessionEvaluationLabel.UNMARKED,
) {
    init {
        require(id.isNotBlank()) { "Session ID 不能为空" }
        require(targetId.isNotBlank()) { "Session 必须关联一个绑定目标" }
        require(name.isNotBlank()) { "Session 名称不能为空" }
        require(estimatedDistanceMeters == null || estimatedDistanceMeters >= 0.0) {
            "估计距离不能为负数：$estimatedDistanceMeters"
        }
        require(lastRecordedAtEpochMillis >= startedAtEpochMillis) {
            "覆盖终点不能早于开始时刻：$lastRecordedAtEpochMillis < $startedAtEpochMillis"
        }
        require(endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis) {
            "结束时刻不能早于开始时刻：$endedAtEpochMillis < $startedAtEpochMillis"
        }
        require(status != SessionLifecycleStatus.COMPLETED || endedAtEpochMillis != null) {
            "正常结束的 Session 必须有结束时刻"
        }
        require(status != SessionLifecycleStatus.INTERRUPTED || endedAtEpochMillis == null) {
            "中断的 Session 不能伪造结束时刻"
        }
    }
}

/**
 * 正常结束或中断后可用于评分与展示的覆盖终点。
 *
 * 中断 Session 只返回最后一条已写入时间线的处理时刻；恢复发现中断的时刻不属于覆盖范围。
 */
val SamplingSession.coverageEndEpochMillis: Long
    get() = when (status) {
        SessionLifecycleStatus.COMPLETED -> maxOf(
            endedAtEpochMillis ?: lastRecordedAtEpochMillis,
            lastRecordedAtEpochMillis,
        )

        SessionLifecycleStatus.RECORDING,
        SessionLifecycleStatus.INTERRUPTED,
        -> lastRecordedAtEpochMillis
    }

/**
 * 已写入时间线覆盖的时长。中断 Session 只计算到最后一条已记录数据。
 */
val SamplingSession.durationMillis: Long
    get() = (coverageEndEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)

/**
 * 时间线中的一条事件。
 *
 * [processedAtEpochMillis] 是该事件在会话内的处理时刻，决定时间线覆盖终点；观测还保留样本自身的
 * [SessionTimelineEvent.Observation.observedAtEpochMillis] 与 App 收到时刻。
 */
sealed interface SessionTimelineEvent {

    val processedAtEpochMillis: Long

    data class Observation(
        val observationId: String,
        val targetId: String,
        val rawRssiDbm: Int,
        val observedAtEpochMillis: Long,
        val receivedAtEpochMillis: Long,
        /**
         * 交给 03 `RssiObservation.frameId` 的稳定帧标识。同一扫描帧被重复投递时必须相同，
         * 不同扫描帧必须不同；05 实时判定使用 [scanFrameIdOf] 从原始广告计算，09 直接读取本字段，
         * 保证实时与回放一致。
         */
        val scanFrameId: Long,
        val deliverySequence: Long,
        val address: String?,
        val payloadFingerprint: String,
        override val processedAtEpochMillis: Long,
    ) : SessionTimelineEvent

    data class ScanStateChanged(
        val available: Boolean,
        val stateLabel: String,
        val detail: String?,
        override val processedAtEpochMillis: Long,
    ) : SessionTimelineEvent

    data class IdentityChanged(
        val confirmed: Boolean,
        val statusLabel: String,
        val targetId: String?,
        val basisLabel: String?,
        val detail: String?,
        override val processedAtEpochMillis: Long,
    ) : SessionTimelineEvent

    /**
     * 02 的事件缓冲发生溢出，导致至少 [droppedEventCount] 条采集事件未送达录制器。
     *
     * 缺口本身没有原始时刻，只能记录发现时刻。回放据此知道该段输入不完整，不能把缺失当作空窗证据。
     */
    data class EventGap(
        val droppedEventCount: Int,
        override val processedAtEpochMillis: Long,
    ) : SessionTimelineEvent
}

/**
 * 已持久化的事件。[sequence] 是数据库分配的稳定处理顺序，同一时刻的多条事件按它排序。
 */
data class SessionTimelineItem(
    val sequence: Long,
    val sessionId: String,
    val event: SessionTimelineEvent,
)

/**
 * 列表和详情复用的 Session 摘要。
 */
data class SessionSummary(
    val session: SamplingSession,
    val observationCount: Int,
)

/**
 * 开始采样的输入。目标来自 02 的当前绑定，类型、位置、距离与放置方式由用户选择。
 *
 * 05 的模拟测试传入 [configSnapshot]；04/08 的普通采样保持 `null`。
 */
data class SessionStartRequest(
    val binding: BoundTarget,
    val samplingType: SamplingType,
    val name: String,
    val location: FixedSampleLocation,
    val estimatedDistanceMeters: Double?,
    val phonePlacement: PhonePlacement,
    val configSnapshot: SessionConfigSnapshot? = null,
) {
    init {
        require(name.isNotBlank()) { "Session 名称不能为空" }
        require(estimatedDistanceMeters == null || estimatedDistanceMeters >= 0.0) {
            "估计距离不能为负数：$estimatedDistanceMeters"
        }
    }
}

sealed interface SessionStartResult {

    data class Started(val session: SamplingSession) : SessionStartResult

    data class AlreadyRecording(val session: SamplingSession) : SessionStartResult
}

sealed interface SessionStopResult {

    /**
     * 正常结束。[finalScanEvent] 是停止时写入时间线的最后一条停扫事件，供实时模拟测试把同一事件
     * 应用到原始运行状态，保证 09 回放末尾状态与实时记录一致。
     */
    data class Stopped(
        val session: SamplingSession,
        val finalScanEvent: SessionTimelineEvent.ScanStateChanged? = null,
    ) : SessionStopResult

    data object NotRecording : SessionStopResult
}

/**
 * 从 02 的原始广告字段计算稳定的扫描帧标识，供 03 识别重复投递。
 *
 * 地址、单调观测时刻和载荷指纹相同视为同一扫描帧；收到时间更晚或投递序号不同不会改变结果。
 * 05 实时判定与 09 回放都必须使用本函数，避免同一输入得到不同的 `frameId`。
 */
fun scanFrameIdOf(
    address: String?,
    observedAtNanos: Long,
    payloadFingerprint: String,
): Long {
    var result = 1_125_899_906_842_597L
    result = 31L * result + (address?.hashCode() ?: 0)
    result = 31L * result + observedAtNanos
    result = 31L * result + payloadFingerprint.hashCode()
    return result
}
