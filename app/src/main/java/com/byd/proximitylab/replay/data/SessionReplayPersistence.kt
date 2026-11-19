package com.byd.proximitylab.replay.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.byd.proximitylab.proximity.ConfirmationRequirement
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityAlgorithm
import com.byd.proximitylab.proximity.ProximityConfig
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.replay.ReplayConfigSource
import com.byd.proximitylab.replay.ReplayEvent
import com.byd.proximitylab.replay.ReplayInputCoverage
import com.byd.proximitylab.replay.ReplayOutcome
import com.byd.proximitylab.replay.ReplaySample
import com.byd.proximitylab.replay.ReplayStatePoint
import com.byd.proximitylab.replay.ReplayVersionInfo
import com.byd.proximitylab.replay.SessionReplayRecord
import com.byd.proximitylab.replay.SessionReplayStore
import com.byd.proximitylab.replay.SessionReplaySummary
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionLifecycleStatus
import com.byd.proximitylab.session.data.SamplingSessionEntity
import com.byd.proximitylab.session.data.SessionConfigSnapshotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 一次回放的元数据与配置副本。参数完整复制，不引用可编辑或可删除的算法配置行。
 *
 * 时间字段与 Session 使用同一 UTC epoch 毫秒基准；样本、状态和事件另存相对 Session 开始的毫秒。
 */
@Entity(
    tableName = "session_replay",
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
        Index(value = ["sessionId", "createdAtEpochMillis"]),
    ],
)
data class SessionReplayEntity(
    @PrimaryKey val replayId: String,
    val sessionId: String,
    val configSnapshotId: String,
    val configName: String,
    val configSourceConfigId: String?,
    val configSource: String,
    val algorithm: String,
    val enterThresholdDbm: Int,
    val exitThresholdDbm: Int,
    val confirmationKind: String,
    val confirmationValue: Long,
    val sampleValidityMillis: Long,
    val emaAlpha: Double,
    val configSchemaVersion: Int,
    val originalSnapshotAvailable: Boolean,
    val originalAlgorithmVersion: String?,
    val originalTriggerRuleVersion: String?,
    val snapshotAlgorithmVersion: String,
    val snapshotTriggerRuleVersion: String,
    val executedAlgorithmVersion: String,
    val executedTriggerRuleVersion: String,
    val outcome: String,
    val startedAtEpochMillis: Long,
    val coverageEndEpochMillis: Long,
    val sessionStatus: String,
    val interrupted: Boolean,
    val hasEventGaps: Boolean,
    val droppedEventCount: Int,
    val observationCount: Int,
    val finalAtEpochMillis: Long,
    val finalAtSessionMillis: Long,
    val finalState: String,
    val finalReason: String,
    val finalTrend: String,
    val finalLastReadingFrameId: Long?,
    val finalLastReadingObservedAtEpochMillis: Long?,
    val finalRawRssiDbm: Int?,
    val finalFilteredRssiDbm: Double?,
    val sampleCount: Int,
    val stateCount: Int,
    val eventCount: Int,
    val createdAtEpochMillis: Long,
    val replayFormatVersion: Int,
) {
    fun toDomain(
        samples: List<SessionReplaySampleEntity>,
        states: List<SessionReplayStateEntity>,
        events: List<SessionReplayEventEntity>,
    ): SessionReplayRecord = SessionReplayRecord(
        replayId = replayId,
        sessionId = sessionId,
        config = SessionConfigSnapshot(
            snapshotId = configSnapshotId,
            name = configName,
            sourceConfigId = configSourceConfigId,
            config = ProximityConfig(
                algorithm = ProximityAlgorithm.valueOf(algorithm),
                enterThresholdDbm = enterThresholdDbm,
                exitThresholdDbm = exitThresholdDbm,
                confirmation = when (confirmationKind) {
                    SessionConfigSnapshotEntity.KIND_CONSECUTIVE_SAMPLES ->
                        ConfirmationRequirement.ConsecutiveSamples(confirmationValue.toInt())

                    SessionConfigSnapshotEntity.KIND_DURATION ->
                        ConfirmationRequirement.Duration(confirmationValue)

                    else -> error("未知回放配置确认方式：$confirmationKind")
                },
                sampleValidityMillis = sampleValidityMillis,
                emaAlpha = emaAlpha,
            ),
            algorithmVersion = snapshotAlgorithmVersion,
            triggerRuleVersion = snapshotTriggerRuleVersion,
            schemaVersion = configSchemaVersion,
        ),
        configSource = ReplayConfigSource.valueOf(configSource),
        outcome = ReplayOutcome.valueOf(outcome),
        versions = ReplayVersionInfo(
            originalSnapshotAvailable = originalSnapshotAvailable,
            originalAlgorithmVersion = originalAlgorithmVersion,
            originalTriggerRuleVersion = originalTriggerRuleVersion,
            snapshotAlgorithmVersion = snapshotAlgorithmVersion,
            snapshotTriggerRuleVersion = snapshotTriggerRuleVersion,
            executedAlgorithmVersion = executedAlgorithmVersion,
            executedTriggerRuleVersion = executedTriggerRuleVersion,
        ),
        coverage = ReplayInputCoverage(
            startedAtEpochMillis = startedAtEpochMillis,
            coverageEndEpochMillis = coverageEndEpochMillis,
            sessionStatus = SessionLifecycleStatus.valueOf(sessionStatus),
            interrupted = interrupted,
            hasEventGaps = hasEventGaps,
            droppedEventCount = droppedEventCount,
            observationCount = observationCount,
        ),
        samples = samples.map { entity -> entity.toDomain() },
        states = states.map { entity -> entity.toDomain() },
        events = events.map { entity -> entity.toDomain() },
        finalState = ReplayStatePoint(
            atEpochMillis = finalAtEpochMillis,
            atSessionMillis = finalAtSessionMillis,
            state = ProximityState.valueOf(finalState),
            reason = ProximityStateReason.valueOf(finalReason),
            trend = MotionTrend.valueOf(finalTrend),
            lastReadingFrameId = finalLastReadingFrameId,
            lastReadingObservedAtEpochMillis = finalLastReadingObservedAtEpochMillis,
            rawRssiDbm = finalRawRssiDbm,
            filteredRssiDbm = finalFilteredRssiDbm,
        ),
        createdAtEpochMillis = createdAtEpochMillis,
        replayFormatVersion = replayFormatVersion,
    )

    fun toSummary(): SessionReplaySummary = SessionReplaySummary(
        replayId = replayId,
        sessionId = sessionId,
        configSnapshotId = configSnapshotId,
        configName = configName,
        configSource = ReplayConfigSource.valueOf(configSource),
        outcome = ReplayOutcome.valueOf(outcome),
        versions = ReplayVersionInfo(
            originalSnapshotAvailable = originalSnapshotAvailable,
            originalAlgorithmVersion = originalAlgorithmVersion,
            originalTriggerRuleVersion = originalTriggerRuleVersion,
            snapshotAlgorithmVersion = snapshotAlgorithmVersion,
            snapshotTriggerRuleVersion = snapshotTriggerRuleVersion,
            executedAlgorithmVersion = executedAlgorithmVersion,
            executedTriggerRuleVersion = executedTriggerRuleVersion,
        ),
        coverage = ReplayInputCoverage(
            startedAtEpochMillis = startedAtEpochMillis,
            coverageEndEpochMillis = coverageEndEpochMillis,
            sessionStatus = SessionLifecycleStatus.valueOf(sessionStatus),
            interrupted = interrupted,
            hasEventGaps = hasEventGaps,
            droppedEventCount = droppedEventCount,
            observationCount = observationCount,
        ),
        finalState = ReplayStatePoint(
            atEpochMillis = finalAtEpochMillis,
            atSessionMillis = finalAtSessionMillis,
            state = ProximityState.valueOf(finalState),
            reason = ProximityStateReason.valueOf(finalReason),
            trend = MotionTrend.valueOf(finalTrend),
            lastReadingFrameId = finalLastReadingFrameId,
            lastReadingObservedAtEpochMillis = finalLastReadingObservedAtEpochMillis,
            rawRssiDbm = finalRawRssiDbm,
            filteredRssiDbm = finalFilteredRssiDbm,
        ),
        sampleCount = sampleCount,
        stateCount = stateCount,
        eventCount = eventCount,
        createdAtEpochMillis = createdAtEpochMillis,
        replayFormatVersion = replayFormatVersion,
    )

    companion object {
        fun fromRecord(
            record: SessionReplayRecord,
            confirmationKind: String,
            confirmationValue: Long,
        ): SessionReplayEntity = SessionReplayEntity(
            replayId = record.replayId,
            sessionId = record.sessionId,
            configSnapshotId = record.config.snapshotId,
            configName = record.config.name,
            configSourceConfigId = record.config.sourceConfigId,
            configSource = record.configSource.name,
            algorithm = record.config.config.algorithm.name,
            enterThresholdDbm = record.config.config.enterThresholdDbm,
            exitThresholdDbm = record.config.config.exitThresholdDbm,
            confirmationKind = confirmationKind,
            confirmationValue = confirmationValue,
            sampleValidityMillis = record.config.config.sampleValidityMillis,
            emaAlpha = record.config.config.emaAlpha,
            configSchemaVersion = record.config.schemaVersion,
            originalSnapshotAvailable = record.versions.originalSnapshotAvailable,
            originalAlgorithmVersion = record.versions.originalAlgorithmVersion,
            originalTriggerRuleVersion = record.versions.originalTriggerRuleVersion,
            snapshotAlgorithmVersion = record.versions.snapshotAlgorithmVersion,
            snapshotTriggerRuleVersion = record.versions.snapshotTriggerRuleVersion,
            executedAlgorithmVersion = record.versions.executedAlgorithmVersion,
            executedTriggerRuleVersion = record.versions.executedTriggerRuleVersion,
            outcome = record.outcome.name,
            startedAtEpochMillis = record.coverage.startedAtEpochMillis,
            coverageEndEpochMillis = record.coverage.coverageEndEpochMillis,
            sessionStatus = record.coverage.sessionStatus.name,
            interrupted = record.coverage.interrupted,
            hasEventGaps = record.coverage.hasEventGaps,
            droppedEventCount = record.coverage.droppedEventCount,
            observationCount = record.coverage.observationCount,
            finalAtEpochMillis = record.finalState.atEpochMillis,
            finalAtSessionMillis = record.finalState.atSessionMillis,
            finalState = record.finalState.state.name,
            finalReason = record.finalState.reason.name,
            finalTrend = record.finalState.trend.name,
            finalLastReadingFrameId = record.finalState.lastReadingFrameId,
            finalLastReadingObservedAtEpochMillis =
                record.finalState.lastReadingObservedAtEpochMillis,
            finalRawRssiDbm = record.finalState.rawRssiDbm,
            finalFilteredRssiDbm = record.finalState.filteredRssiDbm,
            sampleCount = record.samples.size,
            stateCount = record.states.size,
            eventCount = record.events.size,
            createdAtEpochMillis = record.createdAtEpochMillis,
            replayFormatVersion = record.replayFormatVersion,
        )
    }
}

/**
 * 回放的滤波样本。`sequence` 保留判定处理顺序，同一毫秒的多条样本不会重排。
 */
@Entity(
    tableName = "session_replay_sample",
    primaryKeys = ["replayId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = SessionReplayEntity::class,
            parentColumns = ["replayId"],
            childColumns = ["replayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["replayId"])],
)
data class SessionReplaySampleEntity(
    val replayId: String,
    val sequence: Int,
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val observedAtEpochMillis: Long,
    val frameId: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val state: String,
    val reason: String,
    val trend: String,
) {
    fun toDomain(): ReplaySample = ReplaySample(
        atEpochMillis = atEpochMillis,
        atSessionMillis = atSessionMillis,
        observedAtEpochMillis = observedAtEpochMillis,
        frameId = frameId,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        state = ProximityState.valueOf(state),
        reason = ProximityStateReason.valueOf(reason),
        trend = MotionTrend.valueOf(trend),
    )

    companion object {
        fun fromDomain(replayId: String, sequence: Int, sample: ReplaySample): SessionReplaySampleEntity =
            SessionReplaySampleEntity(
                replayId = replayId,
                sequence = sequence,
                atEpochMillis = sample.atEpochMillis,
                atSessionMillis = sample.atSessionMillis,
                observedAtEpochMillis = sample.observedAtEpochMillis,
                frameId = sample.frameId,
                rawRssiDbm = sample.rawRssiDbm,
                filteredRssiDbm = sample.filteredRssiDbm,
                state = sample.state.name,
                reason = sample.reason.name,
                trend = sample.trend.name,
            )
    }
}

/** 回放的状态/原因变化。自增 ID 保证同一毫秒的多次变化保持写入顺序。 */
@Entity(
    tableName = "session_replay_state",
    foreignKeys = [
        ForeignKey(
            entity = SessionReplayEntity::class,
            parentColumns = ["replayId"],
            childColumns = ["replayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["replayId"])],
)
data class SessionReplayStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val replayId: String,
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val state: String,
    val reason: String,
    val trend: String,
    val lastReadingFrameId: Long?,
    val lastReadingObservedAtEpochMillis: Long?,
    val rawRssiDbm: Int?,
    val filteredRssiDbm: Double?,
) {
    fun toDomain(): ReplayStatePoint = ReplayStatePoint(
        atEpochMillis = atEpochMillis,
        atSessionMillis = atSessionMillis,
        state = ProximityState.valueOf(state),
        reason = ProximityStateReason.valueOf(reason),
        trend = MotionTrend.valueOf(trend),
        lastReadingFrameId = lastReadingFrameId,
        lastReadingObservedAtEpochMillis = lastReadingObservedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
    )

    companion object {
        fun fromDomain(replayId: String, state: ReplayStatePoint): SessionReplayStateEntity =
            SessionReplayStateEntity(
                replayId = replayId,
                atEpochMillis = state.atEpochMillis,
                atSessionMillis = state.atSessionMillis,
                state = state.state.name,
                reason = state.reason.name,
                trend = state.trend.name,
                lastReadingFrameId = state.lastReadingFrameId,
                lastReadingObservedAtEpochMillis = state.lastReadingObservedAtEpochMillis,
                rawRssiDbm = state.rawRssiDbm,
                filteredRssiDbm = state.filteredRssiDbm,
            )
    }
}

/** 回放事件。没有反馈/通知字段，避免与实时事件历史混淆。 */
@Entity(
    tableName = "session_replay_event",
    foreignKeys = [
        ForeignKey(
            entity = SessionReplayEntity::class,
            parentColumns = ["replayId"],
            childColumns = ["replayId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["replayId"])],
)
data class SessionReplayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val replayId: String,
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val type: String,
    val reason: String,
    val targetId: String,
    val frameId: Long,
    val observedAtEpochMillis: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
) {
    fun toDomain(): ReplayEvent = ReplayEvent(
        atEpochMillis = atEpochMillis,
        atSessionMillis = atSessionMillis,
        type = ProximityEventType.valueOf(type),
        reason = ProximityEventReason.valueOf(reason),
        targetId = targetId,
        frameId = frameId,
        observedAtEpochMillis = observedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        algorithmVersion = algorithmVersion,
        triggerRuleVersion = triggerRuleVersion,
    )

    companion object {
        fun fromDomain(replayId: String, event: ReplayEvent): SessionReplayEventEntity =
            SessionReplayEventEntity(
                replayId = replayId,
                atEpochMillis = event.atEpochMillis,
                atSessionMillis = event.atSessionMillis,
                type = event.type.name,
                reason = event.reason.name,
                targetId = event.targetId,
                frameId = event.frameId,
                observedAtEpochMillis = event.observedAtEpochMillis,
                rawRssiDbm = event.rawRssiDbm,
                filteredRssiDbm = event.filteredRssiDbm,
                algorithmVersion = event.algorithmVersion,
                triggerRuleVersion = event.triggerRuleVersion,
            )
    }
}

@Dao
interface SessionReplayDao {

    @Insert
    suspend fun insertReplay(entity: SessionReplayEntity)

    @Insert
    suspend fun insertSamples(entities: List<SessionReplaySampleEntity>)

    @Insert
    suspend fun insertStates(entities: List<SessionReplayStateEntity>)

    @Insert
    suspend fun insertEvents(entities: List<SessionReplayEventEntity>)

    /**
     * 一次回放的元数据与明细在同一事务中写入，避免出现只写入元数据的中间状态。
     */
    @Transaction
    suspend fun insertReplayWithDetails(
        replay: SessionReplayEntity,
        samples: List<SessionReplaySampleEntity>,
        states: List<SessionReplayStateEntity>,
        events: List<SessionReplayEventEntity>,
    ) {
        insertReplay(replay)
        if (samples.isNotEmpty()) {
            insertSamples(samples)
        }
        if (states.isNotEmpty()) {
            insertStates(states)
        }
        if (events.isNotEmpty()) {
            insertEvents(events)
        }
    }

    @Query(
        "SELECT * FROM session_replay WHERE sessionId = :sessionId " +
            "ORDER BY createdAtEpochMillis DESC, replayId DESC",
    )
    fun observeReplays(sessionId: String): Flow<List<SessionReplayEntity>>

    @Query(
        "SELECT * FROM session_replay WHERE sessionId = :sessionId " +
            "ORDER BY createdAtEpochMillis DESC, replayId DESC",
    )
    suspend fun loadReplays(sessionId: String): List<SessionReplayEntity>

    @Query("SELECT * FROM session_replay WHERE replayId = :replayId")
    suspend fun loadReplay(replayId: String): SessionReplayEntity?

    @Query("SELECT * FROM session_replay_sample WHERE replayId = :replayId ORDER BY sequence")
    suspend fun loadSamples(replayId: String): List<SessionReplaySampleEntity>

    @Query("SELECT * FROM session_replay_state WHERE replayId = :replayId ORDER BY id")
    suspend fun loadStates(replayId: String): List<SessionReplayStateEntity>

    @Query("SELECT * FROM session_replay_event WHERE replayId = :replayId ORDER BY id")
    suspend fun loadEvents(replayId: String): List<SessionReplayEventEntity>
}

/**
 * 回放结果的 Room 实现，复用 [ProximityLabDatabase] 并随 Session 级联删除。
 */
class RoomSessionReplayStore(
    private val dao: SessionReplayDao,
) : SessionReplayStore {

    override fun observeReplaySummaries(sessionId: String): Flow<List<SessionReplaySummary>> =
        dao.observeReplays(sessionId).map { entities ->
            entities.map { entity -> entity.toSummary() }
        }

    override suspend fun loadReplaySummaries(sessionId: String): List<SessionReplaySummary> =
        dao.loadReplays(sessionId).map { entity -> entity.toSummary() }

    override suspend fun loadReplay(replayId: String): SessionReplayRecord? {
        val entity = dao.loadReplay(replayId) ?: return null
        return entity.toDomain(
            samples = dao.loadSamples(replayId),
            states = dao.loadStates(replayId),
            events = dao.loadEvents(replayId),
        )
    }

    override suspend fun loadReplays(sessionId: String): List<SessionReplayRecord> =
        dao.loadReplays(sessionId).map { entity ->
            entity.toDomain(
                samples = dao.loadSamples(entity.replayId),
                states = dao.loadStates(entity.replayId),
                events = dao.loadEvents(entity.replayId),
            )
        }

    override suspend fun saveReplay(record: SessionReplayRecord): SessionReplayRecord {
        val (confirmationKind, confirmationValue) = when (val confirmation = record.config.config.confirmation) {
            is ConfirmationRequirement.ConsecutiveSamples ->
                SessionConfigSnapshotEntity.KIND_CONSECUTIVE_SAMPLES to
                    confirmation.minimumSamples.toLong()

            is ConfirmationRequirement.Duration ->
                SessionConfigSnapshotEntity.KIND_DURATION to confirmation.minimumDurationMillis
        }
        dao.insertReplayWithDetails(
            replay = SessionReplayEntity.fromRecord(record, confirmationKind, confirmationValue),
            samples = record.samples.mapIndexed { index, sample ->
                SessionReplaySampleEntity.fromDomain(record.replayId, index, sample)
            },
            states = record.states.map { state ->
                SessionReplayStateEntity.fromDomain(record.replayId, state)
            },
            events = record.events.map { event ->
                SessionReplayEventEntity.fromDomain(record.replayId, event)
            },
        )
        return record
    }
}
