package com.byd.proximitylab.session.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.ReferenceMarkerKind
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionEvaluationLabel
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.SessionReferenceMarker
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionSummary
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 采样 Session 表。时间都是 UTC epoch 毫秒；[endedAtEpochMillis] 只表示正常结束，
 * [lastRecordedAtEpochMillis] 是已写入时间线的覆盖终点，[interruptedDetectedAtEpochMillis] 只用于诊断。
 * [evaluationLabel] 是 08 的整次试验人工标签，只影响评价，不修改时间线。
 */
@Entity(tableName = "sampling_session")
data class SamplingSessionEntity(
    @PrimaryKey val id: String,
    val targetId: String,
    val targetDisplayName: String?,
    val targetAddress: String,
    val samplingType: String,
    val name: String,
    val location: String,
    val estimatedDistanceMeters: Double?,
    val phonePlacement: String,
    val status: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val lastRecordedAtEpochMillis: Long,
    val interruptedDetectedAtEpochMillis: Long?,
    @ColumnInfo(defaultValue = "'UNMARKED'") val evaluationLabel: String,
) {
    fun toDomain(): SamplingSession = SamplingSession(
        id = id,
        targetId = targetId,
        targetDisplayName = targetDisplayName,
        targetAddress = targetAddress,
        samplingType = SamplingType.valueOf(samplingType),
        name = name,
        location = FixedSampleLocation.valueOf(location),
        estimatedDistanceMeters = estimatedDistanceMeters,
        phonePlacement = PhonePlacement.valueOf(phonePlacement),
        status = SessionLifecycleStatus.valueOf(status),
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        lastRecordedAtEpochMillis = lastRecordedAtEpochMillis,
        interruptedDetectedAtEpochMillis = interruptedDetectedAtEpochMillis,
        evaluationLabel = SessionEvaluationLabel.valueOf(evaluationLabel),
    )

    companion object {
        fun fromDomain(session: SamplingSession): SamplingSessionEntity = SamplingSessionEntity(
            id = session.id,
            targetId = session.targetId,
            targetDisplayName = session.targetDisplayName,
            targetAddress = session.targetAddress,
            samplingType = session.samplingType.name,
            name = session.name,
            location = session.location.name,
            estimatedDistanceMeters = session.estimatedDistanceMeters,
            phonePlacement = session.phonePlacement.name,
            status = session.status.name,
            startedAtEpochMillis = session.startedAtEpochMillis,
            endedAtEpochMillis = session.endedAtEpochMillis,
            lastRecordedAtEpochMillis = session.lastRecordedAtEpochMillis,
            interruptedDetectedAtEpochMillis = session.interruptedDetectedAtEpochMillis,
            evaluationLabel = session.evaluationLabel.name,
        )
    }
}

/**
 * 模拟测试开始时固定的算法与参数快照。
 *
 * 快照与 Session 同表关系保存，不引用可编辑配置行，因此原配置改名或删除后仍可解释当次结果。
 */
@Entity(
    tableName = "session_config_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = SamplingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionConfigSnapshotEntity(
    @PrimaryKey val sessionId: String,
    val snapshotId: String,
    val name: String,
    val sourceConfigId: String?,
    val algorithm: String,
    val enterThresholdDbm: Int,
    val exitThresholdDbm: Int,
    val confirmationKind: String,
    val confirmationValue: Long,
    val sampleValidityMillis: Long,
    val emaAlpha: Double,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
    val schemaVersion: Int,
) {
    fun toDomain(): SessionConfigSnapshot = SessionConfigSnapshot(
        snapshotId = snapshotId,
        name = name,
        sourceConfigId = sourceConfigId,
        config = ProximityConfig(
            algorithm = ProximityAlgorithm.valueOf(algorithm),
            enterThresholdDbm = enterThresholdDbm,
            exitThresholdDbm = exitThresholdDbm,
            confirmation = when (confirmationKind) {
                KIND_CONSECUTIVE_SAMPLES -> ConfirmationRequirement.ConsecutiveSamples(
                    confirmationValue.toInt(),
                )

                KIND_DURATION -> ConfirmationRequirement.Duration(confirmationValue)
                else -> error("未知配置确认方式：$confirmationKind")
            },
            sampleValidityMillis = sampleValidityMillis,
            emaAlpha = emaAlpha,
        ),
        algorithmVersion = algorithmVersion,
        triggerRuleVersion = triggerRuleVersion,
        schemaVersion = schemaVersion,
    )

    companion object {
        const val KIND_CONSECUTIVE_SAMPLES: String = "CONSECUTIVE_SAMPLES"
        const val KIND_DURATION: String = "DURATION"

        fun fromDomain(
            sessionId: String,
            snapshot: SessionConfigSnapshot,
        ): SessionConfigSnapshotEntity {
            val (kind, value) = when (val confirmation = snapshot.config.confirmation) {
                is ConfirmationRequirement.ConsecutiveSamples ->
                    KIND_CONSECUTIVE_SAMPLES to confirmation.minimumSamples.toLong()

                is ConfirmationRequirement.Duration ->
                    KIND_DURATION to confirmation.minimumDurationMillis
            }
            return SessionConfigSnapshotEntity(
                sessionId = sessionId,
                snapshotId = snapshot.snapshotId,
                name = snapshot.name,
                sourceConfigId = snapshot.sourceConfigId,
                algorithm = snapshot.config.algorithm.name,
                enterThresholdDbm = snapshot.config.enterThresholdDbm,
                exitThresholdDbm = snapshot.config.exitThresholdDbm,
                confirmationKind = kind,
                confirmationValue = value,
                sampleValidityMillis = snapshot.config.sampleValidityMillis,
                emaAlpha = snapshot.config.emaAlpha,
                algorithmVersion = snapshot.algorithmVersion,
                triggerRuleVersion = snapshot.triggerRuleVersion,
                schemaVersion = snapshot.schemaVersion,
            )
        }
    }
}

/**
 * 判定输入时间线。`sequence` 是自增主键，提供 Session 内稳定处理顺序；
 * 同一处理时刻的多条事件按写入顺序保留。
 */
@Entity(
    tableName = "session_timeline",
    foreignKeys = [
        ForeignKey(
            entity = SamplingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "processedAtEpochMillis"]),
    ],
)
data class SessionTimelineEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0L,
    val sessionId: String,
    val kind: String,
    val processedAtEpochMillis: Long,
    val observedAtEpochMillis: Long?,
    val receivedAtEpochMillis: Long?,
    val observationId: String?,
    val targetId: String?,
    val rawRssiDbm: Int?,
    val scanFrameId: Long?,
    val deliverySequence: Long?,
    val address: String?,
    val payloadFingerprint: String?,
    val scanAvailable: Boolean?,
    val scanStateLabel: String?,
    val detail: String?,
    val identityConfirmed: Boolean?,
    val identityStatusLabel: String?,
    val identityBasisLabel: String?,
    val droppedEvents: Int?,
) {
    fun toDomain(): SessionTimelineItem = SessionTimelineItem(
        sequence = sequence,
        sessionId = sessionId,
        event = when (kind) {
            KIND_OBSERVATION -> SessionTimelineEvent.Observation(
                observationId = requireNotNull(observationId),
                targetId = requireNotNull(targetId),
                rawRssiDbm = requireNotNull(rawRssiDbm),
                observedAtEpochMillis = requireNotNull(observedAtEpochMillis),
                receivedAtEpochMillis = requireNotNull(receivedAtEpochMillis),
                scanFrameId = requireNotNull(scanFrameId),
                deliverySequence = requireNotNull(deliverySequence),
                address = address,
                payloadFingerprint = payloadFingerprint.orEmpty(),
                processedAtEpochMillis = processedAtEpochMillis,
            )

            KIND_SCAN_STATE -> SessionTimelineEvent.ScanStateChanged(
                available = requireNotNull(scanAvailable),
                stateLabel = requireNotNull(scanStateLabel),
                detail = detail,
                processedAtEpochMillis = processedAtEpochMillis,
            )

            KIND_IDENTITY_STATE -> SessionTimelineEvent.IdentityChanged(
                confirmed = requireNotNull(identityConfirmed),
                statusLabel = requireNotNull(identityStatusLabel),
                targetId = targetId,
                basisLabel = identityBasisLabel,
                detail = detail,
                processedAtEpochMillis = processedAtEpochMillis,
            )

            KIND_EVENT_GAP -> SessionTimelineEvent.EventGap(
                droppedEventCount = requireNotNull(droppedEvents),
                processedAtEpochMillis = processedAtEpochMillis,
            )

            else -> error("未知时间线事件类型：$kind")
        },
    )

    companion object {
        const val KIND_OBSERVATION: String = "OBSERVATION"
        const val KIND_SCAN_STATE: String = "SCAN_STATE"
        const val KIND_IDENTITY_STATE: String = "IDENTITY_STATE"
        const val KIND_EVENT_GAP: String = "EVENT_GAP"

        fun fromEvent(sessionId: String, event: SessionTimelineEvent): SessionTimelineEntity =
            when (event) {
                is SessionTimelineEvent.Observation -> SessionTimelineEntity(
                    sessionId = sessionId,
                    kind = KIND_OBSERVATION,
                    processedAtEpochMillis = event.processedAtEpochMillis,
                    observedAtEpochMillis = event.observedAtEpochMillis,
                    receivedAtEpochMillis = event.receivedAtEpochMillis,
                    observationId = event.observationId,
                    targetId = event.targetId,
                    rawRssiDbm = event.rawRssiDbm,
                    scanFrameId = event.scanFrameId,
                    deliverySequence = event.deliverySequence,
                    address = event.address,
                    payloadFingerprint = event.payloadFingerprint,
                    scanAvailable = null,
                    scanStateLabel = null,
                    detail = null,
                    identityConfirmed = null,
                    identityStatusLabel = null,
                    identityBasisLabel = null,
                    droppedEvents = null,
                )

                is SessionTimelineEvent.ScanStateChanged -> SessionTimelineEntity(
                    sessionId = sessionId,
                    kind = KIND_SCAN_STATE,
                    processedAtEpochMillis = event.processedAtEpochMillis,
                    observedAtEpochMillis = null,
                    receivedAtEpochMillis = null,
                    observationId = null,
                    targetId = null,
                    rawRssiDbm = null,
                    scanFrameId = null,
                    deliverySequence = null,
                    address = null,
                    payloadFingerprint = null,
                    scanAvailable = event.available,
                    scanStateLabel = event.stateLabel,
                    detail = event.detail,
                    identityConfirmed = null,
                    identityStatusLabel = null,
                    identityBasisLabel = null,
                    droppedEvents = null,
                )

                is SessionTimelineEvent.IdentityChanged -> SessionTimelineEntity(
                    sessionId = sessionId,
                    kind = KIND_IDENTITY_STATE,
                    processedAtEpochMillis = event.processedAtEpochMillis,
                    observedAtEpochMillis = null,
                    receivedAtEpochMillis = null,
                    observationId = null,
                    targetId = event.targetId,
                    rawRssiDbm = null,
                    scanFrameId = null,
                    deliverySequence = null,
                    address = null,
                    payloadFingerprint = null,
                    scanAvailable = null,
                    scanStateLabel = null,
                    detail = event.detail,
                    identityConfirmed = event.confirmed,
                    identityStatusLabel = event.statusLabel,
                    identityBasisLabel = event.basisLabel,
                    droppedEvents = null,
                )

                is SessionTimelineEvent.EventGap -> SessionTimelineEntity(
                    sessionId = sessionId,
                    kind = KIND_EVENT_GAP,
                    processedAtEpochMillis = event.processedAtEpochMillis,
                    observedAtEpochMillis = null,
                    receivedAtEpochMillis = null,
                    observationId = null,
                    targetId = null,
                    rawRssiDbm = null,
                    scanFrameId = null,
                    deliverySequence = null,
                    address = null,
                    payloadFingerprint = null,
                    scanAvailable = null,
                    scanStateLabel = null,
                    detail = "采集事件缓冲溢出，至少 ${event.droppedEventCount} 条事件未写入。",
                    identityConfirmed = null,
                    identityStatusLabel = null,
                    identityBasisLabel = null,
                    droppedEvents = event.droppedEventCount,
                )
            }
    }
}

/**
 * 人工参考标记表。与时间线分表保存，删除/编辑标签不会改动原始观测；Session 删除时级联清理。
 */
@Entity(
    tableName = "session_reference_marker",
    foreignKeys = [
        ForeignKey(
            entity = SamplingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "atEpochMillis"]),
    ],
)
data class SessionReferenceMarkerEntity(
    @PrimaryKey val markerId: String,
    val sessionId: String,
    val kind: String,
    val atEpochMillis: Long,
    val note: String?,
    val createdAtEpochMillis: Long,
) {
    fun toDomain(): SessionReferenceMarker = SessionReferenceMarker(
        markerId = markerId,
        sessionId = sessionId,
        kind = ReferenceMarkerKind.valueOf(kind),
        atEpochMillis = atEpochMillis,
        note = note,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    companion object {
        fun fromDomain(marker: SessionReferenceMarker): SessionReferenceMarkerEntity =
            SessionReferenceMarkerEntity(
                markerId = marker.markerId,
                sessionId = marker.sessionId,
                kind = marker.kind.name,
                atEpochMillis = marker.atEpochMillis,
                note = marker.note,
                createdAtEpochMillis = marker.createdAtEpochMillis,
            )
    }
}

/**
 * 列表查询结果，附带 Session 内的原始样本数。
 */
data class SessionSummaryRow(
    @Embedded val session: SamplingSessionEntity,
    @ColumnInfo(name = "observationCount") val observationCount: Int,
)

@Dao
interface SamplingSessionDao {

    @Insert
    suspend fun insertSession(entity: SamplingSessionEntity)

    @Insert
    suspend fun insertConfigSnapshot(entity: SessionConfigSnapshotEntity)

    /**
     * 同一事务内创建 Session 与配置快照，避免出现有 Session 却没有快照的中间状态。
     */
    @Transaction
    suspend fun insertSessionWithSnapshot(
        session: SamplingSessionEntity,
        configSnapshot: SessionConfigSnapshotEntity?,
    ) {
        insertSession(session)
        if (configSnapshot != null) {
            insertConfigSnapshot(configSnapshot)
        }
    }

    @Query("SELECT * FROM session_config_snapshot WHERE sessionId = :sessionId")
    fun observeConfigSnapshot(sessionId: String): Flow<SessionConfigSnapshotEntity?>

    @Query("SELECT * FROM session_config_snapshot WHERE sessionId = :sessionId")
    suspend fun loadConfigSnapshot(sessionId: String): SessionConfigSnapshotEntity?

    @Query(
        "SELECT s.*, (SELECT COUNT(*) FROM session_timeline t " +
            "WHERE t.sessionId = s.id AND t.kind = 'OBSERVATION') AS observationCount " +
            "FROM sampling_session s ORDER BY s.startedAtEpochMillis DESC, s.id DESC",
    )
    fun observeSessionSummaries(): Flow<List<SessionSummaryRow>>

    @Query("SELECT * FROM sampling_session WHERE id = :sessionId")
    fun observeSession(sessionId: String): Flow<SamplingSessionEntity?>

    @Query("SELECT * FROM sampling_session WHERE id = :sessionId")
    suspend fun loadSession(sessionId: String): SamplingSessionEntity?

    @Query("SELECT * FROM sampling_session WHERE status = 'RECORDING'")
    suspend fun loadRecordingSessions(): List<SamplingSessionEntity>

    @Query("DELETE FROM sampling_session WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Insert
    suspend fun insertTimeline(entity: SessionTimelineEntity): Long

    @Query(
        "UPDATE sampling_session SET lastRecordedAtEpochMillis = " +
            "MAX(lastRecordedAtEpochMillis, :processedAtEpochMillis) WHERE id = :sessionId",
    )
    suspend fun updateCoverage(sessionId: String, processedAtEpochMillis: Long)

    @Query(
        "UPDATE sampling_session SET status = 'COMPLETED', " +
            "endedAtEpochMillis = MAX(:endedAtEpochMillis, startedAtEpochMillis, lastRecordedAtEpochMillis), " +
            "lastRecordedAtEpochMillis = MAX(lastRecordedAtEpochMillis, :endedAtEpochMillis), " +
            "interruptedDetectedAtEpochMillis = NULL " +
            "WHERE id = :sessionId AND status = 'RECORDING'",
    )
    suspend fun markCompleted(sessionId: String, endedAtEpochMillis: Long)

    @Query(
        "UPDATE sampling_session SET status = 'INTERRUPTED', endedAtEpochMillis = NULL, " +
            "interruptedDetectedAtEpochMillis = :detectedAtEpochMillis " +
            "WHERE id = :sessionId AND status = 'RECORDING'",
    )
    suspend fun markInterrupted(sessionId: String, detectedAtEpochMillis: Long)

    @Query(
        "UPDATE sampling_session SET status = 'INTERRUPTED', endedAtEpochMillis = NULL, " +
            "interruptedDetectedAtEpochMillis = :detectedAtEpochMillis " +
            "WHERE status = 'RECORDING'",
    )
    suspend fun markRecordingSessionsInterrupted(detectedAtEpochMillis: Long)

    @Query("SELECT * FROM session_timeline WHERE sessionId = :sessionId ORDER BY sequence")
    fun observeTimeline(sessionId: String): Flow<List<SessionTimelineEntity>>

    @Query("SELECT * FROM session_timeline WHERE sessionId = :sessionId ORDER BY sequence")
    suspend fun loadTimeline(sessionId: String): List<SessionTimelineEntity>

    /**
     * 写入一条事件并推进覆盖终点。两个操作在同一事务中，避免会话显示已记录但时间线缺失。
     */
    @Transaction
    suspend fun appendTimeline(entity: SessionTimelineEntity): Long {
        val sequence = insertTimeline(entity)
        updateCoverage(entity.sessionId, entity.processedAtEpochMillis)
        return sequence
    }

    @Query("UPDATE sampling_session SET name = :name WHERE id = :sessionId")
    suspend fun updateSessionName(sessionId: String, name: String): Int

    @Query("UPDATE sampling_session SET evaluationLabel = :label WHERE id = :sessionId")
    suspend fun updateSessionEvaluationLabel(sessionId: String, label: String): Int

    /**
     * 记录期望触发点时把未标记 Session 提升为期望触发正样本；负样本标签保持不变。
     */
    @Query(
        "UPDATE sampling_session SET evaluationLabel = 'EXPECTED_TRIGGER' " +
            "WHERE id = :sessionId AND evaluationLabel = 'UNMARKED'",
    )
    suspend fun promoteEvaluationLabelToExpected(sessionId: String): Int

    @Query(
        "SELECT * FROM session_reference_marker WHERE sessionId = :sessionId " +
            "ORDER BY atEpochMillis, markerId",
    )
    fun observeReferenceMarkers(sessionId: String): Flow<List<SessionReferenceMarkerEntity>>

    @Query(
        "SELECT * FROM session_reference_marker WHERE sessionId = :sessionId " +
            "ORDER BY atEpochMillis, markerId",
    )
    suspend fun loadReferenceMarkers(sessionId: String): List<SessionReferenceMarkerEntity>

    @Insert
    suspend fun insertReferenceMarkerEntity(entity: SessionReferenceMarkerEntity)

    @Query("DELETE FROM session_reference_marker WHERE markerId = :markerId")
    suspend fun deleteReferenceMarkerEntity(markerId: String): Int

    /**
     * 标记写入与正样本标签提升在同一事务中，避免出现“有期望触发点但仍未标记”的中间状态。
     */
    @Transaction
    suspend fun insertReferenceMarkerAndPromoteLabel(entity: SessionReferenceMarkerEntity) {
        insertReferenceMarkerEntity(entity)
        if (entity.kind == ReferenceMarkerKind.EXPECTED_TRIGGER.name) {
            promoteEvaluationLabelToExpected(entity.sessionId)
        }
    }
}

/**
 * Session 存储的 Room 实现，复用 [com.byd.proximitylab.data.ProximityLabDatabase]。
 */
class RoomSessionStore(
    private val dao: SamplingSessionDao,
) : SessionStore {

    override fun observeSessions(): Flow<List<SessionSummary>> =
        dao.observeSessionSummaries().map { rows ->
            rows.map { row ->
                SessionSummary(
                    session = row.session.toDomain(),
                    observationCount = row.observationCount,
                )
            }
        }

    override fun observeSession(sessionId: String): Flow<SamplingSession?> =
        dao.observeSession(sessionId).map { it?.toDomain() }

    override fun observeTimeline(sessionId: String): Flow<List<SessionTimelineItem>> =
        dao.observeTimeline(sessionId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun loadSession(sessionId: String): SamplingSession? =
        dao.loadSession(sessionId)?.toDomain()

    override suspend fun loadTimeline(sessionId: String): List<SessionTimelineItem> =
        dao.loadTimeline(sessionId).map { it.toDomain() }

    override suspend fun createSession(session: SamplingSession) {
        dao.insertSession(SamplingSessionEntity.fromDomain(session))
    }

    override suspend fun createSimulationSession(
        session: SamplingSession,
        configSnapshot: SessionConfigSnapshot,
    ) {
        dao.insertSessionWithSnapshot(
            session = SamplingSessionEntity.fromDomain(session),
            configSnapshot = SessionConfigSnapshotEntity.fromDomain(session.id, configSnapshot),
        )
    }

    override fun observeConfigSnapshot(sessionId: String): Flow<SessionConfigSnapshot?> =
        dao.observeConfigSnapshot(sessionId).map { it?.toDomain() }

    override suspend fun loadConfigSnapshot(sessionId: String): SessionConfigSnapshot? =
        dao.loadConfigSnapshot(sessionId)?.toDomain()

    override suspend fun saveConfigSnapshot(
        sessionId: String,
        configSnapshot: SessionConfigSnapshot,
    ) {
        dao.insertConfigSnapshot(SessionConfigSnapshotEntity.fromDomain(sessionId, configSnapshot))
    }

    override suspend fun appendTimeline(
        sessionId: String,
        event: SessionTimelineEvent,
    ): SessionTimelineItem {
        val sequence = dao.appendTimeline(
            SessionTimelineEntity.fromEvent(sessionId, event),
        )
        return SessionTimelineItem(
            sequence = sequence,
            sessionId = sessionId,
            event = event,
        )
    }

    override suspend fun completeSession(
        sessionId: String,
        endedAtEpochMillis: Long,
    ): SamplingSession? {
        val existing = dao.loadSession(sessionId) ?: return null
        // 写入前夹取，防止系统时钟回拨产生早于开始/覆盖终点的结束时刻。
        val safeEndedAt = maxOf(
            endedAtEpochMillis,
            existing.startedAtEpochMillis,
            existing.lastRecordedAtEpochMillis,
        )
        dao.markCompleted(sessionId, safeEndedAt)
        return dao.loadSession(sessionId)?.toDomain()
    }

    override suspend fun markInterrupted(
        sessionId: String,
        detectedAtEpochMillis: Long,
    ): SamplingSession? {
        dao.markInterrupted(sessionId, detectedAtEpochMillis)
        return dao.loadSession(sessionId)?.toDomain()
    }

    override suspend fun loadRecordingSessions(): List<SamplingSession> =
        dao.loadRecordingSessions().map { it.toDomain() }

    override suspend fun deleteSession(sessionId: String) {
        dao.deleteSession(sessionId)
    }

    override suspend fun renameSession(sessionId: String, name: String): SamplingSession? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        dao.updateSessionName(sessionId, trimmed)
        return dao.loadSession(sessionId)?.toDomain()
    }

    override suspend fun updateEvaluationLabel(
        sessionId: String,
        label: SessionEvaluationLabel,
    ): SamplingSession? {
        dao.updateSessionEvaluationLabel(sessionId, label.name)
        return dao.loadSession(sessionId)?.toDomain()
    }

    override fun observeReferenceMarkers(sessionId: String): Flow<List<SessionReferenceMarker>> =
        dao.observeReferenceMarkers(sessionId).map { entities ->
            entities.map { entity -> entity.toDomain() }
        }

    override suspend fun loadReferenceMarkers(sessionId: String): List<SessionReferenceMarker> =
        dao.loadReferenceMarkers(sessionId).map { entity -> entity.toDomain() }

    override suspend fun insertReferenceMarker(
        marker: SessionReferenceMarker,
    ): SessionReferenceMarker? {
        if (dao.loadSession(marker.sessionId) == null) {
            return null
        }
        dao.insertReferenceMarkerAndPromoteLabel(
            SessionReferenceMarkerEntity.fromDomain(marker),
        )
        return marker
    }

    override suspend fun deleteReferenceMarker(markerId: String): Boolean =
        dao.deleteReferenceMarkerEntity(markerId) > 0

    override suspend fun markRecordingSessionsInterrupted(
        detectedAtEpochMillis: Long,
    ): Int {
        val recording = dao.loadRecordingSessions()
        if (recording.isEmpty()) {
            return 0
        }
        dao.markRecordingSessionsInterrupted(detectedAtEpochMillis)
        return recording.size
    }
}
