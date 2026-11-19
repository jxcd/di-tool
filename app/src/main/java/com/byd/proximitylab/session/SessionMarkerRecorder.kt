package com.byd.proximitylab.session

import java.util.UUID

sealed interface ReferenceMarkerResult {

    data class Recorded(
        val marker: SessionReferenceMarker,
        val session: SamplingSession,
    ) : ReferenceMarkerResult

    data object MissingSession : ReferenceMarkerResult

    /**
     * 只有采集中 Session 才能记录实时标记；完成后只允许删除/修正已有标记。
     */
    data object NotRecording : ReferenceMarkerResult
}

/**
 * 08 的实时人工标记入口，采样页和模拟测试页共用。
 *
 * 标记时刻使用与原始观测相同的 UTC epoch 毫秒时基；时钟回拨时夹取到 Session 开始时刻。
 * 标记只写入独立标记表，不追加、不修改 Session 时间线。
 */
class SessionMarkerRecorder(
    private val store: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun addMarker(
        sessionId: String,
        kind: ReferenceMarkerKind,
        note: String? = null,
        atEpochMillis: Long = clock(),
    ): ReferenceMarkerResult {
        val session = store.loadSession(sessionId) ?: return ReferenceMarkerResult.MissingSession
        if (session.status != SessionLifecycleStatus.RECORDING) {
            return ReferenceMarkerResult.NotRecording
        }
        val marker = SessionReferenceMarker(
            markerId = idGenerator(),
            sessionId = sessionId,
            kind = kind,
            atEpochMillis = maxOf(atEpochMillis, session.startedAtEpochMillis),
            note = note?.trim()?.ifEmpty { null },
            createdAtEpochMillis = clock(),
        )
        store.insertReferenceMarker(marker) ?: return ReferenceMarkerResult.MissingSession
        val updated = store.loadSession(sessionId) ?: session
        return ReferenceMarkerResult.Recorded(marker = marker, session = updated)
    }
}
