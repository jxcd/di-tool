package com.byd.proximitylab.ble

import java.util.Locale

/**
 * 比亚迪广播特征，只用于把候选设备排到更容易检查的位置。
 *
 * 这些特征来自公开 APK 的静态复审，真实广播中的稳定性与唯一性未经验证；
 * 不能作为目标身份或地址延续的唯一证据。
 */
enum class BydSignal {
    CLASSIC_SERVICE_UUID,
    BLE3_SERVICE_UUID,
    BLE3_LEGACY_SERVICE_UUID,
    MANUFACTURER_ID,
    NAME,
}

data class BydCandidateHint(
    val matchedSignals: Set<BydSignal>,
)

object BydCandidateClassifier {

    const val CLASSIC_SERVICE_UUID: String = "42594420-4155-544F-E0A9-E50E24DCCA9E"
    const val BLE3_SERVICE_UUID: String = "00003008-427D-EE7D-6974-7DC1FA381C07"
    const val BLE3_LEGACY_SERVICE_UUID: String = "00003008-0000-1000-8000-00805F9B34FB"
    const val BYD_MANUFACTURER_ID: Int = 0x3494

    fun classify(advertisement: BleAdvertisement): BydCandidateHint? {
        val matched = linkedSetOf<BydSignal>()
        val uuids = advertisement.serviceUuids.map(::canonicalUuid).toSet()

        if (CLASSIC_SERVICE_UUID in uuids) {
            matched += BydSignal.CLASSIC_SERVICE_UUID
        }
        if (BLE3_SERVICE_UUID in uuids) {
            matched += BydSignal.BLE3_SERVICE_UUID
        }
        if (BLE3_LEGACY_SERVICE_UUID in uuids) {
            matched += BydSignal.BLE3_LEGACY_SERVICE_UUID
        }
        if (advertisement.manufacturerData.any { it.companyId == BYD_MANUFACTURER_ID }) {
            matched += BydSignal.MANUFACTURER_ID
        }

        val name = advertisement.deviceName?.uppercase(Locale.ROOT)
        if (name != null && (name.contains("BYD") || name.contains("BLE3"))) {
            matched += BydSignal.NAME
        }

        return if (matched.isEmpty()) null else BydCandidateHint(matched)
    }

    /**
     * 把 16 位或 32 位短 UUID 展开为 Android 扫描记录常见的 128 位表示。
     */
    fun canonicalUuid(value: String): String {
        val normalized = value.trim().uppercase(Locale.ROOT)
        return when (normalized.length) {
            4 -> "0000$normalized-0000-1000-8000-00805F9B34FB"
            8 -> "$normalized-0000-1000-8000-00805F9B34FB"
            else -> normalized
        }
    }
}
