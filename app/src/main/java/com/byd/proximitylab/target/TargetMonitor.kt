package com.byd.proximitylab.target

import com.byd.proximitylab.ble.BleAdvertisement
import com.byd.proximitylab.ble.BleScanSource
import com.byd.proximitylab.ble.BydCandidateClassifier
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.ble.normalizeBleAddress
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

/**
 * 把只读扫描、候选聚合、目标身份判定和绑定持久化组合成 04/05/11 共用的入口。
 *
 * 扫描状态和目标是否出现保持独立：扫描运行但没有任何回调只表示缺少观测。
 */
class TargetMonitor(
    private val scanSource: BleScanSource,
    private val bindingStore: TargetBindingStore,
    private val scope: CoroutineScope,
    private val policy: TargetAssociationPolicy = TargetAssociationPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val candidateRetentionMillis: Long = DEFAULT_CANDIDATE_RETENTION_MILLIS,
    private val maxDiagnostics: Int = DEFAULT_MAX_DIAGNOSTICS,
    eventBufferCapacity: Int = DEFAULT_EVENT_BUFFER_CAPACITY,
) : TargetObservationSource {

    init {
        require(eventBufferCapacity >= 0) { "事件缓冲容量不能为负数：$eventBufferCapacity" }
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        TargetMonitorState(scanState = scanSource.scanState.value),
    )
    override val state: StateFlow<TargetMonitorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TargetEvent>(
        extraBufferCapacity = eventBufferCapacity,
    )
    override val events: SharedFlow<TargetEvent> = _events.asSharedFlow()

    private var binding: BoundTarget? = null
    private val candidates = LinkedHashMap<String, CandidateDevice>()
    private var identity: TargetIdentityReport = TargetIdentityReport.unbound(0L)
    private var diagnostics: List<AssociationDiagnostic> = emptyList()
    private var scanStartedAtEpochMillis: Long? = null
    private var lastAnyObservationAtEpochMillis: Long? = null
    private var targetObservationCount: Int = 0
    private var lastTargetObservation: TargetObservation? = null
    private var targetObservedInCurrentScan: Boolean = false
    private var lastEvaluatedScanState: ScanAvailability? = null
    private var droppedTargetEventCount: Int = 0

    private fun publishEvent(event: TargetEvent) {
        if (!_events.tryEmit(event)) {
            // 消费者写入较慢时 SharedFlow 可能满；计数随状态暴露，录制器据此写入缺口标记。
            droppedTargetEventCount += 1
        }
    }

    init {
        scope.launch {
            bindingStore.observeBinding().collect { stored ->
                mutex.withLock {
                    if (stored == binding) {
                        return@withLock
                    }
                    binding = stored
                    resetTargetObservationStateLocked()
                    if (stored == null) {
                        publishIdentity(TargetIdentityReport.unbound(clock()))
                    } else if (
                        identity.targetId != stored.targetId ||
                        identity.bindingAddress != stored.address
                    ) {
                        publishIdentity(TargetIdentityReport.persisted(stored, clock()))
                    }
                    publishState()
                }
            }
        }
        scope.launch {
            scanSource.scanState.collect { scanState ->
                mutex.withLock {
                    handleScanStateLocked(scanState)
                }
            }
        }
        scope.launch {
            scanSource.advertisements.collect { advertisement ->
                mutex.withLock {
                    handleAdvertisementLocked(advertisement)
                }
            }
        }
    }

    override fun startScan() {
        scanSource.start()
    }

    override fun stopScan() {
        scanSource.stop()
    }

    /**
     * 11 的会话看门狗入口：让 BLE 来源重新检查平台条件。
     *
     * 状态变化仍由 [scanSource.scanState] 的既有收集链路发布，不在这里伪造扫描结果。
     */
    fun revalidateScanAvailability() {
        scanSource.revalidate()
    }

    /**
     * 11 的会话看门狗入口：平台条件恢复后让 BLE 来源在同一 Session 内重新提交扫描。
     */
    fun resumeScanIfReady() {
        scanSource.resumeIfReady()
    }

    suspend fun bind(address: String): TargetActionResult = mutex.withLock {
        if (binding != null) {
            return@withLock TargetActionResult.AlreadyBound
        }
        val candidate = findCandidateLocked(address) ?: return@withLock TargetActionResult.CandidateNotFound
        val now = clock()
        val newBinding = TargetBindings.newBinding(
            advertisement = candidate.advertisement,
            address = candidate.address ?: return@withLock TargetActionResult.CandidateNotFound,
            nowEpochMillis = now,
            evidence = policy.verifiedIdentity(candidate.advertisement),
        )
        bindingStore.saveBinding(newBinding)
        binding = newBinding
        resetTargetObservationStateLocked()
        publishIdentity(
            TargetIdentityReport(
                status = IdentityStatus.CONFIRMED,
                targetId = newBinding.targetId,
                bindingAddress = newBinding.address,
                confirmedAddress = newBinding.address,
                basis = AssociationBasis.USER_BINDING,
                uncertainty = null,
                pendingCandidateAddresses = currentOtherAddressesLocked(newBinding.address),
                evidenceDescription = "用户人工确认并保存绑定。",
                evaluatedAtEpochMillis = now,
            ),
        )
        addDiagnostic(AssociationDiagnosticKind.TARGET_BOUND, "人工绑定目标 ${newBinding.address}。", now)
        publishEvent(TargetEvent.ActionRecorded(TargetAction.BOUND, newBinding, now))
        publishState()
        TargetActionResult.Success(newBinding)
    }

    suspend fun reconfirmAsOriginal(address: String): TargetActionResult = mutex.withLock {
        val current = binding ?: return@withLock TargetActionResult.NotBound
        val candidate = findCandidateLocked(address) ?: return@withLock TargetActionResult.CandidateNotFound
        val now = clock()
        val updated = TargetBindings.reconfirmAsOriginal(
            binding = current,
            advertisement = candidate.advertisement,
            address = candidate.address ?: return@withLock TargetActionResult.CandidateNotFound,
            nowEpochMillis = now,
            evidence = policy.verifiedIdentity(candidate.advertisement),
        )
        bindingStore.saveBinding(updated)
        binding = updated
        resetTargetObservationStateLocked()
        publishIdentity(
            TargetIdentityReport(
                status = IdentityStatus.CONFIRMED,
                targetId = updated.targetId,
                bindingAddress = updated.address,
                confirmedAddress = updated.address,
                basis = AssociationBasis.USER_RECONFIRMATION,
                uncertainty = null,
                pendingCandidateAddresses = currentOtherAddressesLocked(updated.address),
                evidenceDescription = "用户确认该候选仍是原目标，目标标识保持不变。",
                evaluatedAtEpochMillis = now,
            ),
        )
        addDiagnostic(
            AssociationDiagnosticKind.TARGET_RECONFIRMED,
            "用户重新确认 ${updated.address} 为原目标，目标标识保留。",
            now,
        )
        publishEvent(TargetEvent.ActionRecorded(TargetAction.RECONFIRMED, updated, now))
        publishState()
        TargetActionResult.Success(updated)
    }

    suspend fun rebindTo(address: String): TargetActionResult = mutex.withLock {
        if (binding == null) {
            return@withLock TargetActionResult.NotBound
        }
        val candidate = findCandidateLocked(address) ?: return@withLock TargetActionResult.CandidateNotFound
        val now = clock()
        val newBinding = TargetBindings.rebindTo(
            advertisement = candidate.advertisement,
            address = candidate.address ?: return@withLock TargetActionResult.CandidateNotFound,
            nowEpochMillis = now,
            evidence = policy.verifiedIdentity(candidate.advertisement),
        )
        bindingStore.saveBinding(newBinding)
        binding = newBinding
        resetTargetObservationStateLocked()
        publishIdentity(
            TargetIdentityReport(
                status = IdentityStatus.CONFIRMED,
                targetId = newBinding.targetId,
                bindingAddress = newBinding.address,
                confirmedAddress = newBinding.address,
                basis = AssociationBasis.USER_REBIND,
                uncertainty = null,
                pendingCandidateAddresses = currentOtherAddressesLocked(newBinding.address),
                evidenceDescription = "用户明确改绑，建立新的目标标识。",
                evaluatedAtEpochMillis = now,
            ),
        )
        addDiagnostic(
            AssociationDiagnosticKind.TARGET_REBOUND,
            "用户改绑到 ${newBinding.address}，原目标标识不再代表当前目标。",
            now,
        )
        publishEvent(TargetEvent.ActionRecorded(TargetAction.REBOUND, newBinding, now))
        publishState()
        TargetActionResult.Success(newBinding)
    }

    suspend fun clearBinding() = mutex.withLock {
        val hadBinding = binding != null
        bindingStore.clearBinding()
        binding = null
        resetTargetObservationStateLocked()
        publishIdentity(TargetIdentityReport.unbound(clock()))
        if (hadBinding) {
            addDiagnostic(AssociationDiagnosticKind.BINDING_CLEARED, "已解除绑定。", clock())
        }
        publishState()
    }

    private suspend fun handleScanStateLocked(scanState: ScanAvailability) {
        val now = clock()
        val previous = lastEvaluatedScanState
        lastEvaluatedScanState = scanState

        if (scanState is ScanAvailability.Scanning && previous !is ScanAvailability.Scanning) {
            candidates.clear()
            scanStartedAtEpochMillis = now
            lastAnyObservationAtEpochMillis = null
            resetTargetObservationStateLocked()
            val currentBinding = binding
            // 扫描停止后重新开始、蓝牙或权限恢复后的重试都不是新观测，也不是人工确认。
            // 已记录的身份不确定必须保留；已确认报告则回到“已保存绑定、等待当前观测”的初始依据，
            // 避免把上一会话的待确认候选或 BOUND_ADDRESS_RECENTLY_OBSERVED/VERIFIED_IDENTITY_TOKEN 依据带到空候选列表。
            val preservedUncertainty = identity.takeIf { report ->
                report.status == IdentityStatus.UNCERTAIN &&
                    currentBinding != null &&
                    report.targetId == currentBinding.targetId &&
                    report.bindingAddress == currentBinding.address
            }
            publishIdentity(
                when {
                    currentBinding == null -> TargetIdentityReport.unbound(now)
                    preservedUncertainty != null -> preservedUncertainty.copy(
                        evaluatedAtEpochMillis = now,
                        pendingCandidateAddresses = emptyList(),
                        evidenceDescription = "扫描重新开始；保留已记录的身份不确定，等待新的观测或人工确认。",
                        uncertainty = preservedUncertainty.uncertainty?.copy(
                            detail = "扫描重新开始；保留已记录的身份不确定，等待新的观测或人工确认。",
                        ),
                    )
                    else -> TargetIdentityReport.persisted(currentBinding, now)
                },
            )
        }

        if (previous != scanState) {
            addDiagnostic(AssociationDiagnosticKind.SCAN_STATE, scanStateMessage(scanState), now)
        }
        publishState()
        publishEvent(TargetEvent.ScanStateChanged(scanState, now))
    }

    private suspend fun handleAdvertisementLocked(advertisement: BleAdvertisement) {
        val now = clock()
        lastAnyObservationAtEpochMillis = advertisement.receivedAtEpochMillis
        recordCandidateLocked(advertisement, now)

        val currentBinding = binding
        if (currentBinding == null) {
            publishIdentity(TargetIdentityReport.unbound(now))
            publishState()
            return
        }

        val decision = policy.evaluate(
            binding = currentBinding,
            advertisement = advertisement,
            recentCandidates = candidates.values.map { it.advertisement },
            nowEpochMillis = now,
        )

        var effectiveBinding: BoundTarget = currentBinding
        decision.continuedBinding?.let { continued ->
            effectiveBinding = continued
            binding = continued
            resetTargetObservationStateLocked()
            bindingStore.saveBinding(continued)
            addDiagnostic(
                AssociationDiagnosticKind.ADDRESS_CONTINUED,
                "可复验身份证据唯一匹配 ${continued.address}，目标标识保持不变。",
                now,
            )
            publishEvent(TargetEvent.ActionRecorded(TargetAction.ADDRESS_CONTINUED, continued, now))
        }

        publishIdentity(decision.identity)

        if (decision.acceptedObservation) {
            val observation = TargetObservation(
                targetId = effectiveBinding.targetId,
                advertisement = advertisement,
                identityBasis = decision.identity.basis ?: AssociationBasis.ADDRESS_MATCH,
            )
            targetObservedInCurrentScan = true
            targetObservationCount += 1
            lastTargetObservation = observation
            publishEvent(TargetEvent.ObservationReceived(observation, now))
        }

        publishState()
    }

    private fun resetTargetObservationStateLocked() {
        targetObservationCount = 0
        lastTargetObservation = null
        targetObservedInCurrentScan = false
    }

    private fun recordCandidateLocked(advertisement: BleAdvertisement, now: Long) {
        val address = normalizeBleAddress(advertisement.address)
        val key = address?.let { "address:$it" }
            ?: "unaddressed:${advertisement.payloadFingerprint.ifEmpty { advertisement.deliverySequence.toString() }}"
        candidates[key] = CandidateDevice(
            key = key,
            advertisement = advertisement,
            bydHint = BydCandidateClassifier.classify(advertisement),
            lastSeenEpochMillis = now,
        )

        val cutoff = now - candidateRetentionMillis
        candidates.entries.removeAll { it.value.lastSeenEpochMillis < cutoff }
    }

    private fun findCandidateLocked(address: String): CandidateDevice? {
        val normalized = normalizeBleAddress(address) ?: return null
        return candidates.values.firstOrNull { normalizeBleAddress(it.address) == normalized }
    }

    private fun currentOtherAddressesLocked(boundAddress: String): List<String> =
        candidates.values
            .mapNotNull { normalizeBleAddress(it.address) }
            .filter { it != boundAddress }
            .distinct()
            .sorted()

    private fun publishIdentity(report: TargetIdentityReport) {
        val previous = identity
        identity = report
        val changed = previous.status != report.status ||
            previous.confirmedAddress != report.confirmedAddress ||
            previous.basis != report.basis ||
            previous.uncertainty?.reason != report.uncertainty?.reason ||
            previous.uncertainty?.detail != report.uncertainty?.detail ||
            previous.pendingCandidateAddresses != report.pendingCandidateAddresses ||
            previous.evidenceDescription != report.evidenceDescription ||
            previous.targetId != report.targetId
        if (changed) {
            when (report.status) {
                IdentityStatus.UNCERTAIN -> addDiagnostic(
                    AssociationDiagnosticKind.IDENTITY_UNCERTAIN,
                    report.uncertainty?.detail ?: "目标身份不确定。",
                    report.evaluatedAtEpochMillis,
                )
                IdentityStatus.CONFIRMED -> addDiagnostic(
                    AssociationDiagnosticKind.IDENTITY_CONFIRMED,
                    identityConfirmedMessage(report),
                    report.evaluatedAtEpochMillis,
                )
                IdentityStatus.UNBOUND -> Unit
            }
        }
        if (changed) {
            publishEvent(
                TargetEvent.IdentityChanged(
                    identity = report,
                    diagnostics = diagnostics,
                    atEpochMillis = report.evaluatedAtEpochMillis,
                ),
            )
        }
    }

    private fun identityConfirmedMessage(report: TargetIdentityReport): String = when (report.basis) {
        AssociationBasis.ADDRESS_MATCH -> "身份已确认：广播地址与绑定一致。"
        AssociationBasis.BOUND_ADDRESS_RECENTLY_OBSERVED -> "身份已确认：原绑定地址仍在最近候选中。"
        AssociationBasis.VERIFIED_IDENTITY_TOKEN -> "身份已确认：${report.evidenceDescription ?: "可复验身份证据唯一匹配"}。"
        AssociationBasis.USER_BINDING -> "身份已确认：人工绑定 ${report.confirmedAddress}。"
        AssociationBasis.USER_RECONFIRMATION -> "身份已确认：用户确认 ${report.confirmedAddress} 为原目标。"
        AssociationBasis.USER_REBIND -> "身份已确认：用户改绑 ${report.confirmedAddress}。"
        AssociationBasis.PERSISTED_BINDING -> report.evidenceDescription ?: "读取到已保存的绑定。"
        null -> "身份已确认。"
    }

    private fun addDiagnostic(kind: AssociationDiagnosticKind, message: String, atEpochMillis: Long) {
        diagnostics = (diagnostics + AssociationDiagnostic(atEpochMillis, kind, message))
            .takeLast(maxDiagnostics)
    }

    private fun publishState() {
        _state.value = TargetMonitorState(
            scanState = scanSource.scanState.value,
            scanStartedAtEpochMillis = scanStartedAtEpochMillis,
            lastAnyObservationAtEpochMillis = lastAnyObservationAtEpochMillis,
            candidates = candidates.values.sortedWith(
                compareByDescending<CandidateDevice> { it.lastSeenEpochMillis }
                    .thenByDescending { it.rssi },
            ),
            binding = binding,
            identity = identity,
            targetObservationCount = targetObservationCount,
            lastTargetObservation = lastTargetObservation,
            targetObservedInCurrentScan = targetObservedInCurrentScan,
            diagnostics = diagnostics,
            droppedTargetEventCount = droppedTargetEventCount,
        )
    }

    private fun scanStateMessage(scanState: ScanAvailability): String = when (scanState) {
        ScanAvailability.Idle -> "扫描未开始。"
        ScanAvailability.Scanning -> "扫描已启动，等待广播回调。"
        ScanAvailability.Stopped -> "用户停止扫描。"
        ScanAvailability.BluetoothOff -> "蓝牙已关闭，扫描不可用。"
        ScanAvailability.BluetoothUnsupported -> "此设备不支持 BLE 扫描。"
        is ScanAvailability.PermissionMissing -> if (scanState.revoked) {
            "扫描权限已被撤销。"
        } else {
            "缺少 BLUETOOTH_SCAN 扫描权限。"
        }
        is ScanAvailability.Failed -> "扫描失败：${scanState.reason}"
    }

    companion object {
        const val DEFAULT_CANDIDATE_RETENTION_MILLIS: Long = 30_000L
        const val DEFAULT_MAX_DIAGNOSTICS: Int = 20
        const val DEFAULT_EVENT_BUFFER_CAPACITY: Int = 256
    }
}
