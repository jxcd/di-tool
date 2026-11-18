package com.byd.proximitylab.ble

import java.security.MessageDigest
import java.util.Locale

/**
 * 一次只读 BLE 广播观测。字段只来自标准扫描回调与 [android.bluetooth.le.ScanRecord]。
 *
 * [address] 是“可取得的广播地址”，不是稳定身份；地址轮换时不能仅凭地址判断目标。
 * [deviceName] 只从扫描记录读取，不调用需要 `BLUETOOTH_CONNECT` 的设备名称接口。
 */
data class BleAdvertisement(
    val address: String?,
    val deviceName: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerData: List<ManufacturerDataRecord>,
    val observedAtNanos: Long,
    val receivedAtNanos: Long,
    val observedAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val payloadFingerprint: String,
    val deliverySequence: Long,
) {
    /**
     * 交给 03/05 的观测标识信息。03 用它识别重复投递，本票不替判定入口决定是否重复。
     *
     * [observedAtNanos] 与 [payloadFingerprint] 描述扫描帧；[receivedAtNanos] 与
     * [deliverySequence] 区分 App 实际收到的投递。RSSI 相同、收到更晚或地址轮换都不是重复依据。
     */
    val identity: ObservationIdentity
        get() = ObservationIdentity(
            address = address,
            observedAtNanos = observedAtNanos,
            receivedAtNanos = receivedAtNanos,
            payloadFingerprint = payloadFingerprint,
            deliverySequence = deliverySequence,
        )
}

data class ManufacturerDataRecord(
    val companyId: Int,
    val payloadHex: String,
)

data class ObservationIdentity(
    val address: String?,
    val observedAtNanos: Long,
    val receivedAtNanos: Long,
    val payloadFingerprint: String,
    val deliverySequence: Long,
) {
    /**
     * 仅描述两次投递是否指向同一个扫描帧。是否按重复处理仍由 03 根据时间和会话状态决定。
     */
    fun hasSameScanFrameAs(other: ObservationIdentity): Boolean =
        address == other.address &&
            observedAtNanos == other.observedAtNanos &&
            payloadFingerprint == other.payloadFingerprint
}

/**
 * 规范化可取得的蓝牙地址；无法取得时保持 null，不生成猜测身份。
 */
fun normalizeBleAddress(address: String?): String? =
    address?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

internal object PayloadFingerprint {

    fun of(rawRecord: ByteArray?): String =
        rawRecord?.let(::sha256Hex) ?: ""

    fun ofFields(
        deviceName: String?,
        serviceUuids: List<String>,
        manufacturerData: List<ManufacturerDataRecord>,
    ): String {
        val canonical = buildString {
            append("fields:")
            append(deviceName.orEmpty())
            append('|')
            serviceUuids.forEach {
                append(it.uppercase(Locale.ROOT))
                append(',')
            }
            append('|')
            manufacturerData.forEach {
                append(it.companyId)
                append('=')
                append(it.payloadHex.uppercase(Locale.ROOT))
                append(',')
            }
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
}

internal fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02X".format(Locale.ROOT, byte) }
