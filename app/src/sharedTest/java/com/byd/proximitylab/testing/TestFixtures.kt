package com.byd.proximitylab.testing

import com.byd.proximitylab.ble.BleAdvertisement
import com.byd.proximitylab.ble.BleScanSource
import com.byd.proximitylab.ble.ManufacturerDataRecord
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.ble.normalizeBleAddress
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.target.TargetAction
import com.byd.proximitylab.target.TargetBindingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM 与 Android 测试共用的可控扫描来源。只在 Android BLE 系统边界替代回调，
 * 目标判定、身份策略和 Room 持久化仍走真实实现。
 */
class ControllableBleScanSource : BleScanSource {

    private val _scanState = MutableStateFlow<ScanAvailability>(ScanAvailability.Idle)
    override val scanState: StateFlow<ScanAvailability> = _scanState.asStateFlow()

    private val _advertisements = MutableSharedFlow<BleAdvertisement>(
        extraBufferCapacity = 128,
    )
    override val advertisements: Flow<BleAdvertisement> = _advertisements.asSharedFlow()

    var startCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    var startResult: ScanAvailability = ScanAvailability.Scanning

    /** 11 的会话看门狗调用；为 null 时保持当前扫描状态，模拟无权撤销之外的普通检查。 */
    var revalidateResult: ScanAvailability? = null

    var revalidateCount: Int = 0
        private set

    /** 11 的恢复入口：非 null 时把扫描状态改为该值，模拟平台条件恢复。 */
    var resumeResult: ScanAvailability? = null

    var resumeCount: Int = 0
        private set

    override fun start() {
        startCount += 1
        _scanState.value = startResult
    }

    override fun stop() {
        stopCount += 1
        _scanState.value = ScanAvailability.Stopped
    }

    override fun revalidate() {
        revalidateCount += 1
        revalidateResult?.let { state -> _scanState.value = state }
    }

    override fun resumeIfReady() {
        resumeCount += 1
        resumeResult?.let { state -> _scanState.value = state }
    }

    fun setScanState(state: ScanAvailability) {
        _scanState.value = state
    }

    fun emit(advertisement: BleAdvertisement): Boolean =
        _advertisements.tryEmit(advertisement)
}

class InMemoryTargetBindingStore(
    initialBinding: BoundTarget? = null,
) : TargetBindingStore {

    private val binding = MutableStateFlow(initialBinding)

    override fun observeBinding(): Flow<BoundTarget?> = binding.asStateFlow()

    override suspend fun loadBinding(): BoundTarget? = binding.value

    override suspend fun saveBinding(binding: BoundTarget) {
        this.binding.value = binding
    }

    override suspend fun clearBinding() {
        binding.value = null
    }
}

fun testAdvertisement(
    address: String? = "AA:BB:CC:DD:EE:01",
    name: String? = "Generic device",
    rssi: Int = -60,
    serviceUuids: List<String> = emptyList(),
    manufacturerData: List<ManufacturerDataRecord> = emptyList(),
    observedAtNanos: Long = 1_000_000_000L,
    receivedAtNanos: Long = observedAtNanos + 1_000_000L,
    observedAtEpochMillis: Long = 1_700_000_000_000L,
    receivedAtEpochMillis: Long = observedAtEpochMillis + 1L,
    payloadFingerprint: String = "fingerprint-$address-$observedAtNanos-$rssi",
    deliverySequence: Long = 1L,
): BleAdvertisement = BleAdvertisement(
    address = normalizeBleAddress(address),
    deviceName = name,
    rssi = rssi,
    serviceUuids = serviceUuids,
    manufacturerData = manufacturerData,
    observedAtNanos = observedAtNanos,
    receivedAtNanos = receivedAtNanos,
    observedAtEpochMillis = observedAtEpochMillis,
    receivedAtEpochMillis = receivedAtEpochMillis,
    payloadFingerprint = payloadFingerprint,
    deliverySequence = deliverySequence,
)

fun testBinding(
    targetId: String = "target-1",
    address: String = "AA:BB:CC:DD:EE:01",
    displayName: String? = "Generic device",
    verifiedIdentityToken: String? = null,
    verifiedIdentityDescription: String? = null,
    lastAction: TargetAction = TargetAction.BOUND,
    lastActionAtEpochMillis: Long = 1_700_000_000_000L,
): BoundTarget = BoundTarget(
    targetId = targetId,
    displayName = displayName,
    address = normalizeBleAddress(address) ?: error("invalid test address"),
    boundAtEpochMillis = 1_700_000_000_000L,
    verifiedIdentityToken = verifiedIdentityToken,
    verifiedIdentityDescription = verifiedIdentityDescription,
    lastAction = lastAction,
    lastActionAtEpochMillis = lastActionAtEpochMillis,
)
