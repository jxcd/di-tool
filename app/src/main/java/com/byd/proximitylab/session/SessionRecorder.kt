package com.byd.proximitylab.session

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.target.TargetEvent
import com.byd.proximitylab.target.TargetIdentityReport
import com.byd.proximitylab.target.TargetObservationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 04、05、08、11 共用的采样会话控制器。
 *
 * 同一时刻最多一个采集中 Session。开始与结束都由这里负责驱动 02 的单一扫描来源，避免各页面重复开扫描
 * 或各自维护会话状态。时间线按 02 事件到达顺序写入，数据库分配稳定处理顺序；同一时刻的多条事件因此
 * 保持先后关系。
 *
 * 正常停止时写入用户确认的结束时刻；进程异常终止不会执行 [stop]，恢复时由
 * [recoverInterruptedSessions] 把遗留的采集中 Session 标为中断，只保留已写入时间线的覆盖终点。
 */
class SessionRecorder(
    private val store: SessionStore,
    private val observationSource: TargetObservationSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    private val mutex = Mutex()

    private val _activeSession = MutableStateFlow<SamplingSession?>(null)
    val activeSession: StateFlow<SamplingSession?> = _activeSession.asStateFlow()

    /**
     * 按数据库写入顺序广播已持久化的时间线事件。
     *
     * 05 的实时判定订阅这里而不是再次直接订阅 02 的事件流，保证判定输入顺序与 09 读到的 Session
     * 时间线一致。订阅者处理较慢时 `emit` 会施加背压，不静默丢弃事件。
     */
    private val _recordedEvents = MutableSharedFlow<SessionTimelineItem>(
        extraBufferCapacity = RECORDED_EVENT_BUFFER_CAPACITY,
    )
    val recordedEvents: SharedFlow<SessionTimelineItem> = _recordedEvents.asSharedFlow()

    private var lastRecordedScanAvailable: Boolean? = null
    private var lastDroppedTargetEventCount: Int =
        observationSource.state.value.droppedTargetEventCount

    init {
        scope.launch {
            observationSource.events.collect { event -> handleEvent(event) }
        }
        scope.launch {
            observationSource.state.collect { state ->
                handleDroppedTargetEvents(state.droppedTargetEventCount)
            }
        }
    }

    /**
     * 恢复时调用。把数据库中遗留的采集中 Session 标为中断，返回标记数量。
     *
     * 恢复时刻只作为诊断信息写入，不改变原 Session 的覆盖终点，也不生成结束时刻。
     */
    suspend fun recoverInterruptedSessions(atEpochMillis: Long = clock()): Int = mutex.withLock {
        if (_activeSession.value != null) {
            return@withLock 0
        }
        store.markRecordingSessionsInterrupted(atEpochMillis)
    }

    /**
     * 开始一个定点采样 Session 并启动共享扫描。已有采集中 Session 时不再启动第二个。
     */
    suspend fun start(request: SessionStartRequest): SessionStartResult = mutex.withLock {
        val active = _activeSession.value
        if (active != null) {
            return@withLock SessionStartResult.AlreadyRecording(active)
        }

        val startedAt = clock()
        // 保证“同时只有一个采集中 Session”：若上次进程遗留了采集中记录，先按中断处理再创建新 Session。
        store.markRecordingSessionsInterrupted(startedAt)
        val session = SamplingSession(
            id = idGenerator(),
            targetId = request.binding.targetId,
            targetDisplayName = request.binding.displayName,
            targetAddress = request.binding.address,
            samplingType = request.samplingType,
            name = request.name.trim(),
            location = request.location,
            estimatedDistanceMeters = request.estimatedDistanceMeters,
            phonePlacement = request.phonePlacement,
            status = SessionLifecycleStatus.RECORDING,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = null,
            lastRecordedAtEpochMillis = startedAt,
            interruptedDetectedAtEpochMillis = null,
        )
        if (request.configSnapshot != null) {
            // 05 模拟测试：Session 与当次配置快照一起落库；Room 实现使用单事务。
            store.createSimulationSession(session, request.configSnapshot)
        } else {
            store.createSession(session)
        }
        _activeSession.value = session
        lastRecordedScanAvailable = null

        // 共享采集生命周期：Session 拥有本次扫描，05/08/11 复用同一入口时不再重复调用扫描来源。
        observationSource.startScan()

        if (request.configSnapshot != null) {
            // 模拟测试的时间线必须能重现开始时的扫描与身份条件。TargetMonitor 只在状态变化时发送
            // 事件；若进入监控页时扫描已开始，同一状态不会再发，因此在这里写入一次起点快照。
            val scanState = observationSource.state.value.scanState
            val identity = observationSource.state.value.identity
            appendLocked(
                session = session,
                event = SessionTimelineEvent.ScanStateChanged(
                    available = scanState is ScanAvailability.Scanning,
                    stateLabel = scanState.storageLabel(),
                    detail = "模拟测试开始时的扫描状态。",
                    processedAtEpochMillis = startedAt,
                ),
            )
            lastRecordedScanAvailable = scanState is ScanAvailability.Scanning
            appendLocked(
                session = session,
                event = SessionTimelineEvent.IdentityChanged(
                    confirmed = identity.status == com.byd.proximitylab.target.IdentityStatus.CONFIRMED,
                    statusLabel = identity.status.name,
                    targetId = identity.targetId,
                    basisLabel = identity.basis?.name,
                    detail = identity.uncertainty?.detail ?: identity.evidenceDescription,
                    processedAtEpochMillis = startedAt,
                ),
            )
        }
        SessionStartResult.Started(session)
    }

    /**
     * 正常结束当前 Session。结束时刻由用户停止操作确认，同时作为覆盖终点。
     */
    suspend fun stop(): SessionStopResult = mutex.withLock {
        val session = _activeSession.value ?: return@withLock SessionStopResult.NotRecording
        // 系统时间可能被回拨；正常结束时刻不能早于开始时刻或已记录的覆盖终点。
        val endedAt = maxOf(
            clock(),
            session.startedAtEpochMillis,
            session.lastRecordedAtEpochMillis,
        )

        // 监听器中的扫描停止事件是异步的；在正常结束前显式写入停止状态，避免它落在 Session 之外。
        var finalScanEvent: SessionTimelineEvent.ScanStateChanged? = null
        if (lastRecordedScanAvailable != false) {
            val event = SessionTimelineEvent.ScanStateChanged(
                available = false,
                stateLabel = ScanAvailability.Stopped.storageLabel(),
                detail = "用户停止采样。",
                processedAtEpochMillis = endedAt,
            )
            appendLocked(session = session, event = event)
            finalScanEvent = event
            lastRecordedScanAvailable = false
        }

        val completed = store.completeSession(session.id, endedAt) ?: session
        _activeSession.value = null
        observationSource.stopScan()
        SessionStopResult.Stopped(completed, finalScanEvent)
    }

    private suspend fun handleDroppedTargetEvents(count: Int) {
        mutex.withLock {
            if (count <= lastDroppedTargetEventCount) {
                return@withLock
            }
            val delta = count - lastDroppedTargetEventCount
            lastDroppedTargetEventCount = count
            val session = _activeSession.value ?: return@withLock
            appendLocked(
                session = session,
                event = SessionTimelineEvent.EventGap(
                    droppedEventCount = delta,
                    processedAtEpochMillis = clock(),
                ),
            )
        }
    }

    private suspend fun handleEvent(event: TargetEvent) {
        mutex.withLock {
            val session = _activeSession.value ?: return@withLock
            when (event) {
                is TargetEvent.ObservationReceived -> {
                    val advertisement = event.observation.advertisement
                    appendLocked(
                        session = session,
                        event = SessionTimelineEvent.Observation(
                            observationId = observationId(advertisement.payloadFingerprint, advertisement.deliverySequence),
                            targetId = event.observation.targetId,
                            rawRssiDbm = advertisement.rssi,
                            observedAtEpochMillis = advertisement.observedAtEpochMillis,
                            receivedAtEpochMillis = advertisement.receivedAtEpochMillis,
                            scanFrameId = scanFrameIdOf(
                                address = advertisement.address,
                                observedAtNanos = advertisement.observedAtNanos,
                                payloadFingerprint = advertisement.payloadFingerprint,
                            ),
                            deliverySequence = advertisement.deliverySequence,
                            address = advertisement.address,
                            payloadFingerprint = advertisement.payloadFingerprint,
                            processedAtEpochMillis = event.atEpochMillis,
                        ),
                    )
                }

                is TargetEvent.ScanStateChanged -> {
                    lastRecordedScanAvailable = event.scanState is ScanAvailability.Scanning
                    appendLocked(
                        session = session,
                        event = SessionTimelineEvent.ScanStateChanged(
                            available = event.scanState is ScanAvailability.Scanning,
                            stateLabel = event.scanState.storageLabel(),
                            detail = event.scanState.storageDetail(),
                            processedAtEpochMillis = event.atEpochMillis,
                        ),
                    )
                }

                is TargetEvent.IdentityChanged -> appendLocked(
                    session = session,
                    event = event.identity.toTimelineEvent(event.atEpochMillis),
                )

                is TargetEvent.ActionRecorded -> Unit
            }
        }
    }

    private suspend fun appendLocked(
        session: SamplingSession,
        event: SessionTimelineEvent,
    ): SamplingSession {
        val item = store.appendTimeline(session.id, event)
        val updated = session.copy(
            lastRecordedAtEpochMillis = maxOf(
                session.lastRecordedAtEpochMillis,
                event.processedAtEpochMillis,
            ),
        )
        _activeSession.value = updated
        _recordedEvents.emit(item)
        return updated
    }

    private fun TargetIdentityReport.toTimelineEvent(
        atEpochMillis: Long,
    ): SessionTimelineEvent.IdentityChanged = SessionTimelineEvent.IdentityChanged(
        confirmed = status == com.byd.proximitylab.target.IdentityStatus.CONFIRMED,
        statusLabel = status.name,
        targetId = targetId,
        basisLabel = basis?.name,
        detail = uncertainty?.detail ?: evidenceDescription,
        processedAtEpochMillis = atEpochMillis,
    )

    private fun observationId(payloadFingerprint: String, deliverySequence: Long): String =
        "${payloadFingerprint.ifEmpty { "-" }}#$deliverySequence"

    private fun ScanAvailability.storageLabel(): String = when (this) {
        ScanAvailability.Idle -> "IDLE"
        ScanAvailability.Scanning -> "SCANNING"
        ScanAvailability.Stopped -> "STOPPED"
        ScanAvailability.BluetoothOff -> "BLUETOOTH_OFF"
        ScanAvailability.BluetoothUnsupported -> "BLUETOOTH_UNSUPPORTED"
        is ScanAvailability.PermissionMissing -> if (revoked) "PERMISSION_REVOKED" else "PERMISSION_MISSING"
        is ScanAvailability.Failed -> "FAILED"
    }

    private fun ScanAvailability.storageDetail(): String? = when (this) {
        is ScanAvailability.PermissionMissing ->
            if (revoked) "扫描权限已被撤销。" else "缺少 BLUETOOTH_SCAN 扫描权限。"

        is ScanAvailability.Failed -> reason
        else -> null
    }

    private companion object {
        const val RECORDED_EVENT_BUFFER_CAPACITY = 256
    }
}
