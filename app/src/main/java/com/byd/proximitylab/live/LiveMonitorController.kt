package com.byd.proximitylab.live

import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.proximity.ProximityDecision
import com.byd.proximitylab.proximity.ProximityState
import com.byd.proximitylab.proximity.ProximityStateReason
import com.byd.proximitylab.session.BuiltInSessionConfigs
import com.byd.proximitylab.session.FixedSampleLocation
import com.byd.proximitylab.session.PhonePlacement
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SamplingType
import com.byd.proximitylab.session.SessionConfigSnapshot
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionStartRequest
import com.byd.proximitylab.session.SessionStartResult
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.SessionStopResult
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.session.SessionTimelineProximityRunner
import com.byd.proximitylab.session.scanFrameIdOf
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.simulation.SessionRunState
import com.byd.proximitylab.simulation.SimulatedEventFeedbackStatus
import com.byd.proximitylab.simulation.SimulationRunStore
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.IdentityStatus
import com.byd.proximitylab.target.TargetEvent
import com.byd.proximitylab.target.TargetMonitorState
import com.byd.proximitylab.target.TargetObservationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 05 的实时监控与前台模拟测试入口，由 07 的监控界面、08 的采样详情和 11 的锁屏反馈复用。
 *
 * 普通监控直接订阅 02 的目标事件并在环形窗口内展示，不写入任何持久化结果。模拟测试判定订阅
 * [SessionRecorder.recordedEvents]，因此输入顺序与 09 读取的 Session 时间线完全一致；只有运行中的
 * 模拟测试会把事件和状态转换写入 [SimulationRunStore]。
 *
 * 会话时间以 Session 开始时刻为原点。模拟测试的过期由可取消的计时器按实际处理时间推进，即使没有新的
 * BLE 回调也会输出 `UNKNOWN`。控制器应放在应用级作用域，界面旋转、换页或重新订阅不会结束运行中的
 * Session。
 */
class LiveMonitorController(
    private val observationSource: TargetObservationSource,
    private val recorder: SessionRecorder,
    private val sessionStore: SessionStore,
    private val simulationStore: SimulationRunStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickerIntervalMillis: Long = DEFAULT_TICKER_INTERVAL_MILLIS,
    private val monitorConfig: SessionConfigSnapshot = BuiltInSessionConfigs.DEFAULT,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        LiveMonitorState(monitor = LiveDecisionView.initial(monitorConfig)),
    )
    val state: StateFlow<LiveMonitorState> = _state.asStateFlow()

    private var monitorRunner: SessionTimelineProximityRunner? = null
    private var monitorTargetId: String? = null
    private val monitorSamples = ArrayDeque<LiveReadingPoint>()

    private var testRun: TestRun? = null
    private var startInProgress: Boolean = false
    private var stopInProgressSessionId: String? = null
    private var monitorTickerJob: Job? = null
    private val pendingRecordedEvents = mutableListOf<SessionTimelineItem>()

    init {
        scope.launch {
            observationSource.events.collect { event -> handleTargetEvent(event) }
        }
        scope.launch {
            observationSource.state.collect { monitorState -> handleTargetState(monitorState) }
        }
        scope.launch {
            recorder.recordedEvents.collect { item -> handleRecordedEvent(item) }
        }
        scope.launch {
            recorder.activeSession.collect { session -> handleRecorderSessionChanged(session) }
        }
    }

    /**
     * 进入实时监控页面时调用，由用户操作启动只读扫描。已有采集中 Session 时不重复启动扫描。
     *
     * 计时器只在这里启动；模拟测试另有自己的可取消计时器，不依赖监控页是否可见。
     */
    fun startMonitoring() {
        if (monitorTickerJob == null && tickerIntervalMillis > 0L) {
            monitorTickerJob = scope.launch {
                while (isActive) {
                    delay(tickerIntervalMillis)
                    tick(clock())
                }
            }
        }
        val scanState = observationSource.state.value.scanState
        if (recorder.activeSession.value == null && scanState !is ScanAvailability.Scanning) {
            observationSource.startScan()
        }
    }

    /** 使用监控当前配置开始一次前台模拟测试。 */
    suspend fun startSimulation(): SimulationStartResult = startSimulation(monitorConfig)

    /**
     * 使用指定配置快照开始一次前台模拟测试。判定从 `UNKNOWN` 重新建立，普通监控已确认的 `FAR` 不会带入。
     * 06 的配置选择从这里接入。
     */
    suspend fun startSimulation(
        configSnapshot: SessionConfigSnapshot,
        name: String = "${configSnapshot.name} 模拟测试",
    ): SimulationStartResult = withContext(NonCancellable) {
        startSimulationInternal(configSnapshot, name)
    }

    private suspend fun startSimulationInternal(
        configSnapshot: SessionConfigSnapshot,
        name: String,
    ): SimulationStartResult {
        val gate = mutex.withLock {
            when {
                startInProgress -> StartGate.Blocked(
                    result = SimulationStartResult.Blocked(
                        session = null,
                        reason = "模拟测试正在开始，请稍候。",
                    ),
                    message = "模拟测试正在开始，请稍候。",
                )

                testRun != null -> StartGate.Blocked(
                    result = SimulationStartResult.Blocked(
                        session = testRun?.session,
                        reason = "已有运行中的模拟测试。",
                    ),
                    message = "已有运行中的模拟测试，请先结束。",
                )

                recorder.activeSession.value != null -> StartGate.Blocked(
                    result = SimulationStartResult.Blocked(
                        session = recorder.activeSession.value,
                        reason = "已有采集中 Session，不能同时开始模拟测试。",
                    ),
                    message = "已有采集中 Session，请先停止普通采样。",
                )

                observationSource.state.value.binding == null -> StartGate.MissingBinding

                else -> {
                    startInProgress = true
                    pendingRecordedEvents.clear()
                    StartGate.Ready(requireNotNull(observationSource.state.value.binding))
                }
            }
        }

        val binding = when (gate) {
            is StartGate.Blocked -> {
                setMessage(gate.message)
                return gate.result
            }

            StartGate.MissingBinding -> {
                setMessage("请先在设备页绑定目标。")
                return SimulationStartResult.NotBound
            }

            is StartGate.Ready -> gate.binding
        }
        val startResult = try {
            recorder.start(
                SessionStartRequest(
                    binding = binding,
                    samplingType = SamplingType.SIMULATION,
                    name = name.trim().ifEmpty { "${configSnapshot.name} 模拟测试" },
                    location = FixedSampleLocation.OTHER,
                    estimatedDistanceMeters = null,
                    phonePlacement = PhonePlacement.HANDHELD,
                    configSnapshot = configSnapshot,
                ),
            )
        } catch (throwable: Throwable) {
            mutex.withLock {
                startInProgress = false
                pendingRecordedEvents.clear()
            }
            throw throwable
        }

        return when (startResult) {
            is SessionStartResult.Started -> finishStart(configSnapshot, startResult.session)
            is SessionStartResult.AlreadyRecording -> {
                mutex.withLock {
                    startInProgress = false
                    pendingRecordedEvents.clear()
                }
                setMessage("已有采集中 Session，请先停止普通采样。")
                SimulationStartResult.Blocked(
                    session = startResult.session,
                    reason = "已有采集中 Session，不能同时开始模拟测试。",
                )
            }

            SessionStartResult.ScanPermissionMissing -> {
                mutex.withLock {
                    startInProgress = false
                    pendingRecordedEvents.clear()
                }
                setMessage("缺少 BLUETOOTH_SCAN 扫描权限，未开始模拟测试，也未启动前台服务。")
                SimulationStartResult.ScanPermissionMissing
            }
        }
    }

    /**
     * 正常结束当前模拟测试。事件已持久化，结束后不再追加；普通采样 Session 不会被这里停止。
     */
    suspend fun stopSimulation(): SimulationStopResult = withContext(NonCancellable) {
        stopSimulationInternal(null)
    }

    /**
     * 更新事件的本地反馈结果。11 在提交通知或震动后调用；本票不主动发送 Android 通知。
     */
    suspend fun updateEventFeedback(
        eventId: String,
        status: SimulatedEventFeedbackStatus,
        detail: String? = null,
    ): Boolean {
        val updated = simulationStore.updateEventFeedback(
            eventId = eventId,
            status = status,
            detail = detail,
            atEpochMillis = clock(),
        ) ?: return false

        mutex.withLock {
            val current = testRun?.view ?: _state.value.test ?: return@withLock
            val events = current.events.map { event ->
                if (event.eventId == eventId) updated else event
            }
            val updatedView = current.copy(events = events)
            testRun?.view = updatedView
            _state.update { state -> state.copy(test = updatedView) }
        }
        return true
    }

    /**
     * 注入时钟或测试中手动推进时间。生产环境由内部计时器调用。
     */
    suspend fun refreshNow(atEpochMillis: Long = clock()) {
        mutex.withLock {
            advanceMonitorLocked(atEpochMillis)
            val active = testRun
            if (active != null && recorder.activeSession.value?.id == active.session.id) {
                applyTestDecisionLocked(active, active.runner.advanceTo(atEpochMillis))
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 当前 Session 的配置快照，供 07/09 在详情中读取。 */
    suspend fun loadConfigSnapshot(sessionId: String): SessionConfigSnapshot? =
        sessionStore.loadConfigSnapshot(sessionId)

    private suspend fun finishStart(
        configSnapshot: SessionConfigSnapshot,
        session: SamplingSession,
    ): SimulationStartResult {
        // 开始过程中用户改绑：立即结束刚创建的 Session，不建立旧目标的判定上下文。
        if (observationSource.state.value.binding?.targetId != session.targetId) {
            mutex.withLock {
                startInProgress = false
                pendingRecordedEvents.clear()
            }
            recorder.stop()
            setMessage("目标在开始过程中已切换，本次模拟测试已结束。")
            return SimulationStartResult.Blocked(
                session = session,
                reason = "目标在开始过程中已切换，不能混合目标数据。",
            )
        }
        if (recorder.activeSession.value?.id != session.id) {
            mutex.withLock {
                startInProgress = false
                pendingRecordedEvents.clear()
            }
            setMessage("模拟测试在开始过程中已被结束。")
            return SimulationStartResult.Blocked(
                session = session,
                reason = "模拟测试在开始过程中已被共享会话入口结束。",
            )
        }

        val runner = SessionTimelineProximityRunner(
            config = configSnapshot.config,
            targetId = session.targetId,
            sessionStartedAtEpochMillis = session.startedAtEpochMillis,
        )
        val initialDecision = runner.advanceTo(session.startedAtEpochMillis)
        val initialState = SessionRunState(
            sessionId = session.id,
            atEpochMillis = session.startedAtEpochMillis,
            atSessionMillis = 0L,
            targetId = session.targetId,
            state = initialDecision.state,
            reason = initialDecision.reason,
            trend = initialDecision.trend,
            lastReadingFrameId = null,
            lastReadingObservedAtEpochMillis = null,
            rawRssiDbm = null,
            filteredRssiDbm = null,
            algorithmVersion = initialDecision.algorithmVersion,
            triggerRuleVersion = initialDecision.triggerRuleVersion,
        )
        val persistedInitialState = simulationStore.appendState(initialState)
        val run = TestRun(
            session = session,
            config = configSnapshot,
            runner = runner,
            view = SimulationTestView(
                session = session,
                config = configSnapshot,
                decision = initialDecision.toView(
                    config = configSnapshot,
                    sessionStartedAtEpochMillis = session.startedAtEpochMillis,
                    atEpochMillis = session.startedAtEpochMillis,
                ),
                samples = emptyList(),
                events = emptyList(),
                states = listOf(persistedInitialState),
                running = true,
            ),
            tickerJob = null,
            lastStateKey = initialDecision.state to initialDecision.reason,
        )

        mutex.withLock {
            testRun = run
            val pending = pendingRecordedEvents.toList()
            pendingRecordedEvents.clear()
            pending
                .filter { item -> item.sessionId == session.id }
                .forEach { item -> applyTestEventLocked(run, item.event) }
            startInProgress = false
            _state.update { current ->
                current.copy(
                    test = run.view,
                    message = "模拟测试已开始，判定从 UNKNOWN 重新建立。",
                )
            }
        }
        startTestTicker(run)
        return SimulationStartResult.Started(session)
    }

    private fun startTestTicker(run: TestRun) {
        if (tickerIntervalMillis <= 0L) {
            return
        }
        run.tickerJob = scope.launch {
            while (isActive) {
                delay(tickerIntervalMillis)
                advanceTestToClock(run.session.id)
            }
        }
    }

    private suspend fun advanceTestToClock(sessionId: String) {
        mutex.withLock {
            val active = testRun
            if (active?.session?.id != sessionId) {
                return@withLock
            }
            // 采样页等外部入口可能已停止会话；在清理观察者运行前不再追加运行状态。
            if (recorder.activeSession.value?.id != sessionId) {
                return@withLock
            }
            applyTestDecisionLocked(active, active.runner.advanceTo(clock()))
        }
    }

    private suspend fun tick(atEpochMillis: Long) {
        mutex.withLock {
            advanceMonitorLocked(atEpochMillis)
            val active = testRun
            if (active != null && recorder.activeSession.value?.id == active.session.id) {
                applyTestDecisionLocked(active, active.runner.advanceTo(atEpochMillis))
            }
        }
    }

    private suspend fun stopSimulationInternal(targetChangeMessage: String?): SimulationStopResult {
        val active = mutex.withLock {
            if (startInProgress) {
                return@withLock null
            }
            testRun
        } ?: return SimulationStopResult.NotRunning

        active.tickerJob?.cancel()

        mutex.withLock {
            if (testRun !== active) {
                return@withLock
            }
            // 先登记停止中的 Session，外部 activeSession 观察者不会与本次停止重复清理。
            stopInProgressSessionId = active.session.id
            applyTestDecisionLocked(active, active.runner.advanceTo(clock()))
        }

        val result = try {
            recorder.stop()
        } catch (throwable: Throwable) {
            mutex.withLock { stopInProgressSessionId = null }
            throw throwable
        }
        val stoppedSession = (result as? SessionStopResult.Stopped)?.session
        val finalScanEvent = (result as? SessionStopResult.Stopped)?.finalScanEvent
        val message = targetChangeMessage ?: "模拟测试已结束，事件已保存。"

        mutex.withLock {
            if (testRun === active) {
                // 把共享停止入口写入的最终停扫事件应用到原始运行结果，使 09 按完整时间线回放的
                // 末尾状态与实时记录一致。该事件是幂等的，重复应用不会产生第二条状态。
                if (finalScanEvent != null) {
                    applyTestEventLocked(active, finalScanEvent)
                }
                testRun = null
                if (stoppedSession != null) {
                    active.view = active.view.copy(session = stoppedSession)
                }
                active.view = active.view.copy(running = false)
            }
            stopInProgressSessionId = null
            _state.update { current ->
                current.copy(test = active.view, message = message)
            }
        }

        return if (result is SessionStopResult.Stopped) {
            SimulationStopResult.Stopped(result.session)
        } else {
            SimulationStopResult.NotRunning
        }
    }

    private fun stopForTargetChange() {
        scope.launch {
            stopSimulationInternal("目标已切换，当前模拟测试已结束，避免混合目标数据。")
        }
    }

    private suspend fun handleTargetState(monitorState: TargetMonitorState) {
        var stopForSwitch = false
        mutex.withLock {
            val binding = monitorState.binding
            if (binding?.targetId != monitorTargetId) {
                resetMonitorLocked(binding)
            }
            val active = testRun
            if (active != null &&
                (binding == null || binding.targetId != active.session.targetId)
            ) {
                stopForSwitch = true
            }
            _state.update { current ->
                current.copy(
                    binding = binding,
                    scanState = monitorState.scanState,
                    identityStatus = monitorState.identity.status,
                    identityDetail = monitorState.identity.uncertainty?.detail
                        ?: monitorState.identity.evidenceDescription,
                    diagnostics = monitorState.diagnostics,
                    targetObservationCount = monitorState.targetObservationCount,
                    droppedTargetEventCount = monitorState.droppedTargetEventCount,
                )
            }
        }
        if (stopForSwitch) {
            stopForTargetChange()
        }
    }

    private suspend fun handleTargetEvent(event: TargetEvent) {
        mutex.withLock {
            when (event) {
                is TargetEvent.ObservationReceived -> {
                    ensureMonitorRunnerLocked(event.observation.targetId)
                    applyMonitorEventLocked(observationTimelineEvent(event))
                }

                is TargetEvent.ScanStateChanged -> applyMonitorEventLocked(
                    SessionTimelineEvent.ScanStateChanged(
                        available = event.scanState is ScanAvailability.Scanning,
                        stateLabel = event.scanState.storageLabel(),
                        detail = null,
                        processedAtEpochMillis = event.atEpochMillis,
                    ),
                )

                is TargetEvent.IdentityChanged -> {
                    val targetId = event.identity.targetId ?: monitorTargetId ?: return@withLock
                    ensureMonitorRunnerLocked(targetId)
                    applyMonitorEventLocked(
                        SessionTimelineEvent.IdentityChanged(
                            confirmed = event.identity.status == IdentityStatus.CONFIRMED,
                            statusLabel = event.identity.status.name,
                            targetId = event.identity.targetId,
                            basisLabel = event.identity.basis?.name,
                            detail = event.identity.uncertainty?.detail
                                ?: event.identity.evidenceDescription,
                            processedAtEpochMillis = event.atEpochMillis,
                        ),
                    )
                }

                is TargetEvent.ActionRecorded -> {
                    if (event.binding.targetId != monitorTargetId) {
                        resetMonitorLocked(event.binding)
                    }
                }
            }
        }
    }

    private suspend fun handleRecordedEvent(item: SessionTimelineItem) {
        mutex.withLock {
            if (startInProgress) {
                pendingRecordedEvents += item
                return@withLock
            }
            val active = testRun ?: return@withLock
            if (item.sessionId != active.session.id) {
                return@withLock
            }
            applyTestEventLocked(active, item.event)
        }
    }

    /**
     * 采样页等共享入口直接调用 [SessionRecorder.stop] 时，结束控制器中的模拟测试上下文。
     *
     * 控制器自己的停止流程会先登记 [stopInProgressSessionId]，避免和这里重复处理。
     */
    private suspend fun handleRecorderSessionChanged(session: SamplingSession?) {
        _state.update { current -> current.copy(activeSession = session) }
        val active = mutex.withLock {
            val run = testRun ?: return@withLock null
            if (stopInProgressSessionId == run.session.id) {
                return@withLock null
            }
            if (session?.id == run.session.id) {
                return@withLock null
            }
            run.tickerJob?.cancel()
            run.view = run.view.copy(running = false)
            _state.update { current -> current.copy(test = run.view) }
            run
        } ?: return

        val stored = sessionStore.loadSession(active.session.id)
        val lastScanOff = sessionStore.loadTimeline(active.session.id)
            .mapNotNull { item -> item.event as? SessionTimelineEvent.ScanStateChanged }
            .lastOrNull { event -> !event.available }

        mutex.withLock {
            if (testRun !== active) {
                // 控制器自己的停止流程已接管收尾。
                return@withLock
            }
            if (lastScanOff != null) {
                // 外部停止也把最终停扫写入原始运行状态，保持与 09 完整时间线回放一致。
                applyTestEventLocked(active, lastScanOff)
            }
            testRun = null
            if (stored != null) {
                active.view = active.view.copy(session = stored)
            }
            _state.update { current ->
                current.copy(
                    test = active.view,
                    message = "模拟测试已由共享会话入口结束，事件已保存。",
                )
            }
        }
    }

    private suspend fun applyTestEventLocked(run: TestRun, event: SessionTimelineEvent) {
        applyTestDecisionLocked(run, run.runner.apply(event))
    }

    private suspend fun applyTestDecisionLocked(run: TestRun, decision: ProximityDecision) {
        if (testRun !== run && !startInProgress) {
            return
        }
        val atEpochMillis = run.session.startedAtEpochMillis + decision.atMillis
        val view = decision.toView(
            config = run.config,
            sessionStartedAtEpochMillis = run.session.startedAtEpochMillis,
            atEpochMillis = atEpochMillis,
        )
        var samples = run.view.samples
        val reading = decision.lastReading
        if (reading != null && samples.lastOrNull()?.frameId != reading.frameId) {
            samples = (samples + LiveReadingPoint(
                atEpochMillis = atEpochMillis,
                atSessionMillis = decision.atMillis,
                observedAtEpochMillis = run.session.startedAtEpochMillis + reading.observedAtMillis,
                frameId = reading.frameId,
                rawRssiDbm = reading.rawRssiDbm,
                filteredRssiDbm = reading.filteredRssiDbm,
                state = decision.state,
                reason = decision.reason,
                trend = decision.trend,
            )).takeLast(TEST_SAMPLE_CAPACITY)
        }

        var states = run.view.states
        val stateKey = decision.state to decision.reason
        if (run.lastStateKey != stateKey) {
            val persisted = simulationStore.appendState(
                SessionRunState(
                    sessionId = run.session.id,
                    atEpochMillis = atEpochMillis,
                    atSessionMillis = decision.atMillis,
                    targetId = decision.targetId,
                    state = decision.state,
                    reason = decision.reason,
                    trend = decision.trend,
                    lastReadingFrameId = reading?.frameId,
                    lastReadingObservedAtEpochMillis = reading?.let {
                        run.session.startedAtEpochMillis + it.observedAtMillis
                    },
                    rawRssiDbm = reading?.rawRssiDbm,
                    filteredRssiDbm = reading?.filteredRssiDbm,
                    algorithmVersion = decision.algorithmVersion,
                    triggerRuleVersion = decision.triggerRuleVersion,
                ),
            )
            states = states + persisted
            run.lastStateKey = stateKey
        }

        var events = run.view.events
        decision.events.forEach { event ->
            val persisted = simulationStore.appendEvent(
                SessionRunEvent(
                    eventId = idGenerator(),
                    sessionId = run.session.id,
                    type = event.type,
                    reason = event.reason,
                    atEpochMillis = run.session.startedAtEpochMillis + event.atMillis,
                    atSessionMillis = event.atMillis,
                    targetId = event.targetId,
                    frameId = event.frameId,
                    observedAtEpochMillis = run.session.startedAtEpochMillis + event.observedAtMillis,
                    rawRssiDbm = event.rawRssiDbm,
                    filteredRssiDbm = event.filteredRssiDbm,
                    algorithmVersion = event.algorithmVersion,
                    triggerRuleVersion = event.triggerRuleVersion,
                ),
            )
            events = events + persisted
        }

        run.view = run.view.copy(
            decision = view,
            samples = samples,
            events = events,
            states = states,
        )
        _state.update { current -> current.copy(test = run.view) }
    }

    private suspend fun applyMonitorEventLocked(event: SessionTimelineEvent) {
        val runner = monitorRunner ?: return
        val decision = runner.apply(event)
        val view = decision.toView(
            config = monitorConfig,
            sessionStartedAtEpochMillis = null,
            atEpochMillis = decision.atMillis,
        )
        val reading = decision.lastReading
        if (reading != null && monitorSamples.lastOrNull()?.frameId != reading.frameId) {
            while (monitorSamples.size >= MONITOR_SAMPLE_CAPACITY) {
                monitorSamples.removeFirst()
            }
            monitorSamples.addLast(
                LiveReadingPoint(
                    atEpochMillis = decision.atMillis,
                    atSessionMillis = null,
                    observedAtEpochMillis = reading.observedAtMillis,
                    frameId = reading.frameId,
                    rawRssiDbm = reading.rawRssiDbm,
                    filteredRssiDbm = reading.filteredRssiDbm,
                    state = decision.state,
                    reason = decision.reason,
                    trend = decision.trend,
                ),
            )
        }
        _state.update { current ->
            current.copy(
                monitor = view,
                monitorSamples = monitorSamples.toList(),
            )
        }
    }

    private suspend fun advanceMonitorLocked(atEpochMillis: Long) {
        val runner = monitorRunner ?: return
        val decision = runner.advanceTo(atEpochMillis)
        val current = _state.value.monitor
        if (current.state == decision.state &&
            current.reason == decision.reason &&
            current.trend == decision.trend
        ) {
            return
        }
        _state.update { state ->
            state.copy(
                monitor = decision.toView(
                    config = monitorConfig,
                    sessionStartedAtEpochMillis = null,
                    atEpochMillis = decision.atMillis,
                ),
            )
        }
    }

    private fun ensureMonitorRunnerLocked(targetId: String) {
        val binding = _state.value.binding ?: return
        if (monitorRunner != null && monitorTargetId == targetId) {
            return
        }
        resetMonitorLocked(
            if (binding.targetId == targetId) binding else null,
        )
    }

    private fun resetMonitorLocked(binding: BoundTarget?) {
        monitorTargetId = binding?.targetId
        monitorSamples.clear()
        monitorRunner = binding?.let { target ->
            SessionTimelineProximityRunner(
                config = monitorConfig.config,
                targetId = target.targetId,
                sessionStartedAtEpochMillis = 0L,
            )
        }
        _state.update { current ->
            current.copy(
                monitor = LiveDecisionView.initial(monitorConfig),
                monitorSamples = emptyList(),
            )
        }
    }

    private fun observationTimelineEvent(
        event: TargetEvent.ObservationReceived,
    ): SessionTimelineEvent.Observation {
        val advertisement = event.observation.advertisement
        return SessionTimelineEvent.Observation(
            observationId = "${advertisement.payloadFingerprint}#${advertisement.deliverySequence}",
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
        )
    }

    private fun ProximityDecision.toView(
        config: SessionConfigSnapshot,
        sessionStartedAtEpochMillis: Long?,
        atEpochMillis: Long,
    ): LiveDecisionView = LiveDecisionView(
        state = state,
        reason = reason,
        trend = trend,
        rawRssiDbm = lastReading?.rawRssiDbm,
        filteredRssiDbm = lastReading?.filteredRssiDbm,
        lastObservationAtEpochMillis = lastReading?.let { reading ->
            (sessionStartedAtEpochMillis ?: 0L) + reading.observedAtMillis
        },
        lastDecisionAtEpochMillis = atEpochMillis,
        lastDecisionAtSessionMillis = if (sessionStartedAtEpochMillis == null) null else atMillis,
        enterThresholdDbm = config.config.enterThresholdDbm,
        exitThresholdDbm = config.config.exitThresholdDbm,
        algorithm = config.config.algorithm,
        configName = config.name,
        sampleValidityMillis = config.config.sampleValidityMillis,
        confirmation = config.config.confirmation,
        emaAlpha = config.config.emaAlpha,
    )

    private fun setMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    private fun ScanAvailability.storageLabel(): String = when (this) {
        ScanAvailability.Idle -> "IDLE"
        ScanAvailability.Scanning -> "SCANNING"
        ScanAvailability.Stopped -> "STOPPED"
        ScanAvailability.BluetoothOff -> "BLUETOOTH_OFF"
        ScanAvailability.BluetoothUnsupported -> "BLUETOOTH_UNSUPPORTED"
        is ScanAvailability.PermissionMissing -> if (revoked) "PERMISSION_REVOKED" else "PERMISSION_MISSING"
        is ScanAvailability.Failed -> "FAILED"
    }

    private class TestRun(
        val session: SamplingSession,
        val config: SessionConfigSnapshot,
        val runner: SessionTimelineProximityRunner,
        var view: SimulationTestView,
        var tickerJob: Job?,
        var lastStateKey: Pair<ProximityState, ProximityStateReason>,
    )

    private sealed interface StartGate {
        data class Ready(val binding: BoundTarget) : StartGate

        data class Blocked(
            val result: SimulationStartResult,
            val message: String,
        ) : StartGate

        data object MissingBinding : StartGate
    }

    companion object {
        const val DEFAULT_TICKER_INTERVAL_MILLIS: Long = 250L

        /** 监控环形窗口只影响展示，不影响 Session 记录长度。 */
        const val MONITOR_SAMPLE_CAPACITY: Int = 600

        /** 模拟测试展示窗口同样只影响展示。 */
        const val TEST_SAMPLE_CAPACITY: Int = 600
    }
}
