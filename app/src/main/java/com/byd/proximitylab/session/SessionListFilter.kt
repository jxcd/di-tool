package com.byd.proximitylab.session

/**
 * Session 列表的筛选条件。空字段表示不限。
 *
 * 设备筛选使用稳定的 [SamplingSession.targetId]，而不是可变的显示名称或地址；
 * 位置与类型直接对应 Session 元数据。筛选只作用于展示，不修改存储。
 */
data class SessionListFilter(
    val targetId: String? = null,
    val location: FixedSampleLocation? = null,
    val samplingType: SamplingType? = null,
) {
    val isActive: Boolean
        get() = targetId != null || location != null || samplingType != null

    fun matches(session: SamplingSession): Boolean =
        (targetId == null || session.targetId == targetId) &&
            (location == null || session.location == location) &&
            (samplingType == null || session.samplingType == samplingType)

    fun apply(sessions: List<SessionSummary>): List<SessionSummary> =
        sessions.filter { summary -> matches(summary.session) }
}
