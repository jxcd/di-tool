package com.byd.proximitylab.simulation.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.byd.proximitylab.proximity.MotionTrend
import com.byd.proximitylab.proximity.ProximityEventReason
import com.byd.proximitylab.proximity.ProximityEventType
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.data.SamplingSessionEntity
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.SimulationRunStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 模拟测试真实产生的 `ENTERED_RANGE` 事件。事件事实与通知/反馈结果分开保存。
 */
@Entity(
    tableName = "session_run_event",
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
        Index(value = ["sessionId", "atSessionMillis"]),
    ],
)
data class SessionRunEventEntity(
    @PrimaryKey val eventId: String,
    val sessionId: String,
    val type: String,
    val reason: String,
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val targetId: String,
    val frameId: Long,
    val observedAtEpochMillis: Long,
    val rawRssiDbm: Int,
    val filteredRssiDbm: Double,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
    val feedbackStatus: String,
    val feedbackDetail: String?,
    val feedbackUpdatedAtEpochMillis: Long?,
) {
    fun toDomain(): SessionRunEvent = SessionRunEvent(
        eventId = eventId,
        sessionId = sessionId,
        type = ProximityEventType.valueOf(type),
        reason = ProximityEventReason.valueOf(reason),
        atEpochMillis = atEpochMillis,
        atSessionMillis = atSessionMillis,
        targetId = targetId,
        frameId = frameId,
        observedAtEpochMillis = observedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        algorithmVersion = algorithmVersion,
        triggerRuleVersion = triggerRuleVersion,
        feedbackStatus = SimulatedEventFeedbackStatus.valueOf(feedbackStatus),
        feedbackDetail = feedbackDetail,
        feedbackUpdatedAtEpochMillis = feedbackUpdatedAtEpochMillis,
    )

    companion object {
        fun fromDomain(event: SessionRunEvent): SessionRunEventEntity = SessionRunEventEntity(
            eventId = event.eventId,
            sessionId = event.sessionId,
            type = event.type.name,
            reason = event.reason.name,
            atEpochMillis = event.atEpochMillis,
            atSessionMillis = event.atSessionMillis,
            targetId = event.targetId,
            frameId = event.frameId,
            observedAtEpochMillis = event.observedAtEpochMillis,
            rawRssiDbm = event.rawRssiDbm,
            filteredRssiDbm = event.filteredRssiDbm,
            algorithmVersion = event.algorithmVersion,
            triggerRuleVersion = event.triggerRuleVersion,
            feedbackStatus = event.feedbackStatus.name,
            feedbackDetail = event.feedbackDetail,
            feedbackUpdatedAtEpochMillis = event.feedbackUpdatedAtEpochMillis,
        )
    }
}

/**
 * 模拟测试原始运行结果中的状态变化。按自增 ID 保持写入顺序。
 */
@Entity(
    tableName = "session_run_state",
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
        Index(value = ["sessionId", "atSessionMillis"]),
    ],
)
data class SessionRunStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val atEpochMillis: Long,
    val atSessionMillis: Long,
    val targetId: String,
    val state: String,
    val reason: String,
    val trend: String,
    val lastReadingFrameId: Long?,
    val lastReadingObservedAtEpochMillis: Long?,
    val rawRssiDbm: Int?,
    val filteredRssiDbm: Double?,
    val algorithmVersion: String,
    val triggerRuleVersion: String,
) {
    fun toDomain(): SessionRunState = SessionRunState(
        id = id,
        sessionId = sessionId,
        atEpochMillis = atEpochMillis,
        atSessionMillis = atSessionMillis,
        targetId = targetId,
        state = ProximityState.valueOf(state),
        reason = ProximityStateReason.valueOf(reason),
        trend = MotionTrend.valueOf(trend),
        lastReadingFrameId = lastReadingFrameId,
        lastReadingObservedAtEpochMillis = lastReadingObservedAtEpochMillis,
        rawRssiDbm = rawRssiDbm,
        filteredRssiDbm = filteredRssiDbm,
        algorithmVersion = algorithmVersion,
        triggerRuleVersion = triggerRuleVersion,
    )

    companion object {
        fun fromDomain(state: SessionRunState): SessionRunStateEntity = SessionRunStateEntity(
            id = state.id,
            sessionId = state.sessionId,
            atEpochMillis = state.atEpochMillis,
            atSessionMillis = state.atSessionMillis,
            targetId = state.targetId,
            state = state.state.name,
            reason = state.reason.name,
            trend = state.trend.name,
            lastReadingFrameId = state.lastReadingFrameId,
            lastReadingObservedAtEpochMillis = state.lastReadingObservedAtEpochMillis,
            rawRssiDbm = state.rawRssiDbm,
            filteredRssiDbm = state.filteredRssiDbm,
            algorithmVersion = state.algorithmVersion,
            triggerRuleVersion = state.triggerRuleVersion,
        )
    }
}

@Dao
interface SimulationRunDao {

    @Insert
    suspend fun insertEvent(entity: SessionRunEventEntity)

    @Insert
    suspend fun insertState(entity: SessionRunStateEntity): Long

    @Query(
        "SELECT * FROM session_run_event WHERE sessionId = :sessionId " +
            "ORDER BY atSessionMillis, eventId",
    )
    fun observeEvents(sessionId: String): Flow<List<SessionRunEventEntity>>

    @Query(
        "SELECT * FROM session_run_event WHERE sessionId = :sessionId " +
            "ORDER BY atSessionMillis, eventId",
    )
    suspend fun loadEvents(sessionId: String): List<SessionRunEventEntity>

    @Query("SELECT * FROM session_run_event WHERE eventId = :eventId")
    suspend fun loadEvent(eventId: String): SessionRunEventEntity?

    @Query("SELECT * FROM session_run_state WHERE sessionId = :sessionId ORDER BY id")
    fun observeStates(sessionId: String): Flow<List<SessionRunStateEntity>>

    @Query("SELECT * FROM session_run_state WHERE sessionId = :sessionId ORDER BY id")
    suspend fun loadStates(sessionId: String): List<SessionRunStateEntity>

    @Query(
        "UPDATE session_run_event SET feedbackStatus = :status, feedbackDetail = :detail, " +
            "feedbackUpdatedAtEpochMillis = :updatedAtEpochMillis WHERE eventId = :eventId",
    )
    suspend fun updateFeedback(
        eventId: String,
        status: String,
        detail: String?,
        updatedAtEpochMillis: Long,
    ): Int
}

/**
 * 模拟测试运行结果的 Room 实现，复用 04 的同一数据库。
 */
class RoomSimulationRunStore(
    private val dao: SimulationRunDao,
) : SimulationRunStore {

    override fun observeEvents(sessionId: String): Flow<List<SessionRunEvent>> =
        dao.observeEvents(sessionId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun loadEvents(sessionId: String): List<SessionRunEvent> =
        dao.loadEvents(sessionId).map { it.toDomain() }

    override fun observeStates(sessionId: String): Flow<List<SessionRunState>> =
        dao.observeStates(sessionId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun loadStates(sessionId: String): List<SessionRunState> =
        dao.loadStates(sessionId).map { it.toDomain() }

    override suspend fun appendEvent(event: SessionRunEvent): SessionRunEvent {
        dao.insertEvent(SessionRunEventEntity.fromDomain(event))
        return event
    }

    override suspend fun appendState(state: SessionRunState): SessionRunState {
        val id = dao.insertState(SessionRunStateEntity.fromDomain(state))
        return state.copy(id = id)
    }

    override suspend fun updateEventFeedback(
        eventId: String,
        status: SimulatedEventFeedbackStatus,
        detail: String?,
        atEpochMillis: Long,
    ): SessionRunEvent? {
        val updated = dao.updateFeedback(
            eventId = eventId,
            status = status.name,
            detail = detail,
            updatedAtEpochMillis = atEpochMillis,
        )
        return if (updated == 0) null else dao.loadEvent(eventId)?.toDomain()
    }
}
