package com.byd.proximitylab.ble

import java.util.Locale

/**
 * 把标准扫描回调提供的原始字段规范化为 [BleAdvertisement]。
 *
 * Android 的 [android.bluetooth.le.ScanResult] 难以在普通 JVM 测试中构造，因此将字段规范化与时间换算放在
 * 这个纯函数中，真实扫描源只负责读取 ScanRecord。缺失的字段保持 null/空列表，不推断设备没有该字段。
 */
object BleAdvertisementMapper {

    private const val NANOS_PER_MILLI: Long = 1_000_000L

    fun map(
        address: String?,
        deviceName: String?,
        rssi: Int,
        serviceUuids: List<String>,
        manufacturerData: List<ManufacturerDataRecord>,
        rawRecord: ByteArray?,
        observedAtNanos: Long,
        receivedAtNanos: Long,
        receivedAtEpochMillis: Long,
        deliverySequence: Long,
    ): BleAdvertisement {
        val normalizedName = deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedServiceUuids = serviceUuids.map { it.uppercase(Locale.ROOT) }
        val normalizedManufacturerData = manufacturerData
            .map { record -> record.copy(payloadHex = record.payloadHex.uppercase(Locale.ROOT)) }
            .sortedBy { it.companyId }
        val observedAtEpochMillis = receivedAtEpochMillis -
            ((receivedAtNanos - observedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLI)
        val payloadFingerprint = rawRecord
            ?.let(PayloadFingerprint::of)
            ?.takeIf { it.isNotEmpty() }
            ?: PayloadFingerprint.ofFields(
                normalizedName,
                normalizedServiceUuids,
                normalizedManufacturerData,
            )

        return BleAdvertisement(
            address = normalizeBleAddress(address),
            deviceName = normalizedName,
            rssi = rssi,
            serviceUuids = normalizedServiceUuids,
            manufacturerData = normalizedManufacturerData,
            observedAtNanos = observedAtNanos,
            receivedAtNanos = receivedAtNanos,
            observedAtEpochMillis = observedAtEpochMillis,
            receivedAtEpochMillis = receivedAtEpochMillis,
            payloadFingerprint = payloadFingerprint,
            deliverySequence = deliverySequence,
        )
    }
}
