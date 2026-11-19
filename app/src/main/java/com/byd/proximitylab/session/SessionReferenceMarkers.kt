package com.byd.proximitylab.session

/**
 * Session 级人工标签，用于告诉后续评分一次试验的期望结果。
 *
 * [UNMARKED] 只展示观测结果；[EXPECTED_TRIGGER] 表示应按唯一期望触发点评价正样本；
 * [NO_TRIGGER] 表示整次试验明确无需触发（负样本），用于统计误触发。
 * 标签只影响评价，不修改 Session 的原始时间线。
 */
enum class SessionEvaluationLabel(val label: String) {
    UNMARKED("未标记"),
    EXPECTED_TRIGGER("期望触发"),
    NO_TRIGGER("无需触发"),
}

/**
 * 一次采样或模拟测试中可记录的人工参考时刻。
 *
 * 时刻与原始观测使用同一 UTC epoch 毫秒时基；[EXPECTED_TRIGGER] 是一次评分正试验的唯一期望触发点。
 */
enum class ReferenceMarkerKind(val label: String) {
    APPROACH_START("开始接近"),
    EXPECTED_TRIGGER("到达期望触发位置"),
    DEPARTURE_START("开始远离"),
    LEFT_RANGE("离开范围"),
}

/**
 * 已持久化的人工参考标记。只用于评价，不进入判定输入。
 */
data class SessionReferenceMarker(
    val markerId: String,
    val sessionId: String,
    val kind: ReferenceMarkerKind,
    val atEpochMillis: Long,
    val note: String? = null,
    val createdAtEpochMillis: Long = atEpochMillis,
) {
    init {
        require(markerId.isNotBlank()) { "标记标识不能为空" }
        require(sessionId.isNotBlank()) { "标记必须关联 Session" }
    }
}

/**
 * 标记相对 Session 开始时刻的偏移。显示与评分使用同一会话时基。
 */
fun SessionReferenceMarker.sessionOffsetMillis(session: SamplingSession): Long =
    (atEpochMillis - session.startedAtEpochMillis).coerceAtLeast(0L)

/**
 * 人工标记的评价结论，供 Session 详情展示和 10 的评分流程读取。
 *
 * - [isNegativeSample] 只由 [SessionEvaluationLabel.NO_TRIGGER] 决定。
 * - [expectedTriggerAtEpochMillis] 只在覆盖范围内恰好一个 [ReferenceMarkerKind.EXPECTED_TRIGGER]
 *   且 Session 未标为无需触发时给出。冲突时不擅自选择任一标记。
 * - [markersOutsideCoverage] 保留展示但不参与评分。
 */
data class SessionMarkerEvaluation(
    val label: SessionEvaluationLabel,
    val markersInCoverage: List<SessionReferenceMarker>,
    val markersOutsideCoverage: List<SessionReferenceMarker>,
    val expectedTriggerMarkersInCoverage: List<SessionReferenceMarker>,
    val expectedTriggerConflict: Boolean,
    val expectedTriggerAtEpochMillis: Long?,
    val isNegativeSample: Boolean,
    val isPositiveSample: Boolean,
    val missingExpectedTrigger: Boolean,
) {
    /** 是否具备用于评分的人工依据：负样本或唯一期望触发点。 */
    val hasScoringBasis: Boolean
        get() = isNegativeSample || isPositiveSample
}

/**
 * 只把时间落在 Session 已写入覆盖范围内的标记用于评分。
 *
 * 覆盖范围为 `[startedAtEpochMillis, coverageEndEpochMillis]`；中断 Session 的覆盖终点是最后一条
 * 已写入时间线的处理时刻，恢复发现中断的时刻不扩大覆盖范围。
 */
object SessionMarkerPolicy {

    fun evaluate(
        session: SamplingSession,
        markers: List<SessionReferenceMarker>,
    ): SessionMarkerEvaluation {
        val ordered = markers
            .filter { marker -> marker.sessionId == session.id }
            .sortedWith(compareBy({ marker -> marker.atEpochMillis }, { marker -> marker.markerId }))
        val coverageEnd = session.coverageEndEpochMillis
        val inCoverage = ordered.filter { marker ->
            marker.atEpochMillis in session.startedAtEpochMillis..coverageEnd
        }
        val outsideCoverage = ordered.filter { marker ->
            marker.atEpochMillis !in session.startedAtEpochMillis..coverageEnd
        }
        val expectedInCoverage = inCoverage.filter { marker ->
            marker.kind == ReferenceMarkerKind.EXPECTED_TRIGGER
        }
        val isNegative = session.evaluationLabel == SessionEvaluationLabel.NO_TRIGGER
        val conflict = expectedInCoverage.size > 1
        val expectedTriggerAt = if (!isNegative && !conflict && expectedInCoverage.size == 1) {
            expectedInCoverage.single().atEpochMillis
        } else {
            null
        }
        return SessionMarkerEvaluation(
            label = session.evaluationLabel,
            markersInCoverage = inCoverage,
            markersOutsideCoverage = outsideCoverage,
            expectedTriggerMarkersInCoverage = expectedInCoverage,
            expectedTriggerConflict = conflict,
            expectedTriggerAtEpochMillis = expectedTriggerAt,
            isNegativeSample = isNegative,
            isPositiveSample = !isNegative && expectedTriggerAt != null,
            missingExpectedTrigger = session.evaluationLabel == SessionEvaluationLabel.EXPECTED_TRIGGER &&
                expectedInCoverage.isEmpty(),
        )
    }
}
