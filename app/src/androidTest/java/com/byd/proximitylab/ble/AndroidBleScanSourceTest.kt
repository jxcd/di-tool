package com.byd.proximitylab.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AndroidBleScanSourceTest {

    @Test
    fun appManifestRequestsScanWithNeverForLocationAndNoConnectOrInternet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions?.toList().orEmpty()

        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
        assertFalse(Manifest.permission.BLUETOOTH in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_ADMIN in permissions)
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in permissions)

        val index = permissions.indexOf(Manifest.permission.BLUETOOTH_SCAN)
        val flags = packageInfo.requestedPermissionsFlags?.getOrNull(index) ?: 0
        assertTrue(
            "BLUETOOTH_SCAN 必须声明 neverForLocation",
            flags and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION != 0,
        )
    }

    @Test
    fun realScanRecordIsParsedIntoAdvertisementFields() {
        val rawRecord = advertisingBytes()
        val scanRecord = parseScanRecord(rawRecord)

        val advertisement = AndroidScanRecordParser.toAdvertisement(
            address = " aa:bb:cc:dd:ee:01 ",
            scanRecord = scanRecord,
            rssi = -55,
            timestampNanos = 5_000_000_000L,
            receivedAtNanos = 5_250_000_000L,
            receivedAtEpochMillis = 1_800_000_000_000L,
            deliverySequence = 42L,
        )

        assertEquals("AA:BB:CC:DD:EE:01", advertisement.address)
        assertEquals("BYD Test", advertisement.deviceName)
        assertEquals(-55, advertisement.rssi)
        assertEquals(
            listOf(
                "0000180F-0000-1000-8000-00805F9B34FB",
                "0000180A-0000-1000-8000-00805F9B34FB",
            ),
            advertisement.serviceUuids,
        )
        assertEquals(1, advertisement.manufacturerData.size)
        assertEquals(0x3494, advertisement.manufacturerData.single().companyId)
        assertEquals("DEADBEEF", advertisement.manufacturerData.single().payloadHex)
        assertEquals(5_000_000_000L, advertisement.observedAtNanos)
        assertEquals(5_250_000_000L, advertisement.receivedAtNanos)
        assertEquals(1_800_000_000_000L - 250L, advertisement.observedAtEpochMillis)
        assertEquals(1_800_000_000_000L, advertisement.receivedAtEpochMillis)
        assertEquals(42L, advertisement.deliverySequence)
        assertEquals(PayloadFingerprint.of(scanRecord.bytes), advertisement.payloadFingerprint)
        assertEquals(64, advertisement.payloadFingerprint.length)
        assertEquals(
            advertisement.payloadFingerprint.lowercase(Locale.ROOT),
            advertisement.payloadFingerprint,
        )
    }

    @Test
    fun realScanResultFieldsAreMappedIntoAdvertisement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        assumeTrue("设备没有蓝牙适配器，跳过真实 ScanResult 字段映射", adapter != null)
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        val device = adapter!!.getRemoteDevice("AA:BB:CC:DD:EE:02")
        @Suppress("DEPRECATION")
        val result = ScanResult(device, null, -55, 5_000_000_000L)

        val advertisement = AndroidScanResultMapper.toAdvertisement(
            result = result,
            receivedAtNanos = 5_250_000_000L,
            receivedAtEpochMillis = 1_800_000_000_000L,
            deliverySequence = 42L,
        )

        assertEquals("AA:BB:CC:DD:EE:02", advertisement.address)
        assertEquals(-55, advertisement.rssi)
        assertNull(advertisement.deviceName)
        assertTrue(advertisement.serviceUuids.isEmpty())
        assertTrue(advertisement.manufacturerData.isEmpty())
        assertEquals(5_000_000_000L, advertisement.observedAtNanos)
        assertEquals(5_250_000_000L, advertisement.receivedAtNanos)
        assertEquals(1_800_000_000_000L - 250L, advertisement.observedAtEpochMillis)
        assertEquals(1_800_000_000_000L, advertisement.receivedAtEpochMillis)
        assertEquals(42L, advertisement.deliverySequence)
        assertEquals(64, advertisement.payloadFingerprint.length)
    }

    @Test
    fun missingScanRecordKeepsFieldsUnknownInsteadOfInventingValues() {
        val advertisement = AndroidScanRecordParser.toAdvertisement(
            address = null,
            scanRecord = null,
            rssi = -70,
            timestampNanos = 0L,
            receivedAtNanos = 7_000_000_000L,
            receivedAtEpochMillis = 1_800_000_100_000L,
            deliverySequence = 1L,
        )

        assertNull(advertisement.address)
        assertNull(advertisement.deviceName)
        assertTrue(advertisement.serviceUuids.isEmpty())
        assertTrue(advertisement.manufacturerData.isEmpty())
        assertEquals(7_000_000_000L, advertisement.observedAtNanos)
        assertTrue(advertisement.payloadFingerprint.isNotBlank())
        assertEquals(64, advertisement.payloadFingerprint.length)
    }

    @Test
    fun readOnlyScanStartsAndStopsOnAvailableAdapter() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        assumeTrue("设备没有蓝牙适配器，跳过真实只读扫描", adapter != null)
        assumeTrue("系统蓝牙未开启，跳过真实只读扫描", adapter!!.isEnabled)

        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.BLUETOOTH_SCAN,
        )

        val source = AndroidBleScanSource(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val states = CopyOnWriteArrayList<ScanAvailability>()
        val observations = CopyOnWriteArrayList<BleAdvertisement>()
        scope.launch { source.scanState.collect { states += it } }
        scope.launch { source.advertisements.collect { observations += it } }
        delay(200L)

        try {
            source.start()
            val scanning = withTimeoutOrNull(5_000L) {
                source.scanState.first { it is ScanAvailability.Scanning }
            }
            assertTrue("期望真实扫描进入 Scanning，实际状态：$states", scanning is ScanAvailability.Scanning)

            delay(3_000L)
            source.stop()
            val stopped = withTimeoutOrNull(2_000L) {
                source.scanState.first { it is ScanAvailability.Stopped }
            }
            assertTrue("期望扫描可由用户停止，实际状态：$states", stopped is ScanAvailability.Stopped)

            // 用户停止后应能重新开始，并从系统重新注册扫描。
            source.start()
            val restarted = withTimeoutOrNull(5_000L) {
                source.scanState.first { it is ScanAvailability.Scanning }
            }
            assertTrue("期望停止后可重新开始扫描，实际状态：$states", restarted is ScanAvailability.Scanning)
            delay(500L)
            source.stop()
            val stoppedAgain = withTimeoutOrNull(2_000L) {
                source.scanState.first { it is ScanAvailability.Stopped }
            }
            assertTrue("期望第二次停止生效，实际状态：$states", stoppedAgain is ScanAvailability.Stopped)

            println(
                "只读扫描状态=$states，观测数=${observations.size}，" +
                    "地址=${observations.mapNotNull { it.address }.distinct()}",
            )
        } finally {
            source.close()
            scope.cancel()
        }
    }

    @Test
    fun realAdvertisementsExposeFieldsWhenAnAdvertiserIsAvailable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        assumeTrue("设备没有蓝牙适配器，跳过真实广播字段验证", adapter != null)
        assumeTrue("系统蓝牙未开启，跳过真实广播字段验证", adapter!!.isEnabled)
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.BLUETOOTH_SCAN,
        )

        val source = AndroidBleScanSource(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val observations = CopyOnWriteArrayList<BleAdvertisement>()
        scope.launch { source.advertisements.collect { observations += it } }
        delay(200L)
        try {
            source.start()
            delay(3_000L)
            source.stop()
            assumeTrue(
                "本次运行没有可用的 BLE 广告者；真实广播字段验证记为待 13/14，" +
                    "ScanRecord→BleAdvertisement 解析由 realScanRecordIsParsedIntoAdvertisementFields 覆盖",
                observations.isNotEmpty(),
            )
            observations.forEach { observation ->
                assertEquals(
                    "payloadFingerprint 应为 SHA-256 hex",
                    64,
                    observation.payloadFingerprint.length,
                )
                assertTrue("观测时刻应有效", observation.observedAtNanos > 0L)
                assertTrue("App 收到时刻应有效", observation.receivedAtNanos > 0L)
                assertTrue("观测墙钟时间应有效", observation.observedAtEpochMillis > 0L)
                assertTrue("接收墙钟时间应有效", observation.receivedAtEpochMillis > 0L)
                assertTrue("投递序号应有效", observation.deliverySequence > 0L)
                observation.address?.let { address ->
                    assertEquals(address.uppercase(Locale.ROOT), address)
                }
                assertTrue(observation.serviceUuids.all { it == it.uppercase(Locale.ROOT) })
                assertTrue(
                    observation.manufacturerData.all {
                        it.payloadHex == it.payloadHex.uppercase(Locale.ROOT)
                    },
                )
            }
        } finally {
            source.close()
            scope.cancel()
        }
    }

    /**
     * 标准 BLE 广播：flags、完整名称、两个 16 位 Service UUID、一条厂商数据。
     */
    private fun advertisingBytes(): ByteArray {
        val name = "BYD Test".toByteArray(Charsets.US_ASCII)
        return byteArrayOf(
            0x02, 0x01, 0x06,
            (name.size + 1).toByte(), 0x09,
        ) + name + byteArrayOf(
            0x05, 0x03, 0x0F, 0x18, 0x0A, 0x18,
            0x07, 0xFF.toByte(), 0x94.toByte(), 0x34,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )
    }

    /**
     * 测试专用：公开 SDK 不暴露 ScanRecord 的构造入口，用平台隐藏的 parseFromBytes 从广播字节得到真实记录。
     * 目标系统不提供该入口时跳过，而不是用伪造对象冒充真实 ScanRecord。
     */
    private fun parseScanRecord(rawRecord: ByteArray): ScanRecord {
        val method = ScanRecord::class.java.declaredMethods.firstOrNull {
            it.name == "parseFromBytes" &&
                it.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
        }
        assumeTrue("目标系统未提供 ScanRecord.parseFromBytes，跳过真实记录解析", method != null)
        method!!.isAccessible = true
        val parsed = runCatching { method.invoke(null, rawRecord) as? ScanRecord }.getOrNull()
        assumeTrue("目标系统拒绝构造 ScanRecord，跳过真实记录解析", parsed != null)
        return parsed!!
    }
}
