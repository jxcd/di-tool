package com.byd.proximitylab.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android 标准 BLE 只读扫描实现。
 *
 * 仅使用 `BLUETOOTH_SCAN`。设备名从 [android.bluetooth.le.ScanRecord] 读取，不调用需要
 * `BLUETOOTH_CONNECT` 的 `BluetoothDevice.getName()`；不配对、不连接、不执行 GATT。
 */
class AndroidBleScanSource(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : BleScanSource {

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)

    private val _scanState = MutableStateFlow<ScanAvailability>(ScanAvailability.Idle)
    override val scanState: StateFlow<ScanAvailability> = _scanState.asStateFlow()

    private val _advertisements = MutableSharedFlow<BleAdvertisement>(
        extraBufferCapacity = 128,
    )
    override val advertisements: Flow<BleAdvertisement> = _advertisements.asSharedFlow()

    private val deliverySequence = AtomicLong(0L)
    private val receiverRegistered = AtomicBoolean(false)
    private val scanRequested = AtomicBoolean(false)
    private var permissionWasGranted: Boolean = hasScanPermission()

    private val scanSettings: ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let(::deliverResult)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach(::deliverResult)
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanRequested.compareAndSet(true, false)) {
                return
            }
            stopScannerInternal()
            unregisterAdapterReceiver()
            _scanState.value = ScanAvailability.Failed(scanFailureReason(errorCode))
        }
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                return
            }
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF,
                -> {
                    scanRequested.set(false)
                    stopScannerInternal()
                    unregisterAdapterReceiver()
                    _scanState.value = ScanAvailability.BluetoothOff
                }

                BluetoothAdapter.STATE_ON -> {
                    if (_scanState.value is ScanAvailability.BluetoothOff) {
                        _scanState.value = ScanAvailability.Idle
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!hasScanPermission()) {
            _scanState.value = ScanAvailability.PermissionMissing(revoked = permissionWasGranted)
            return
        }
        permissionWasGranted = true

        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            _scanState.value = ScanAvailability.BluetoothUnsupported
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            _scanState.value = ScanAvailability.BluetoothUnsupported
            return
        }
        if (!adapter.isEnabled) {
            _scanState.value = ScanAvailability.BluetoothOff
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _scanState.value = ScanAvailability.Failed("BLE 扫描器不可用")
            return
        }

        stopScannerInternal()
        registerAdapterReceiver()

        try {
            scanner.startScan(null, scanSettings, scanCallback)
            scanRequested.set(true)
            _scanState.value = ScanAvailability.Scanning
        } catch (securityException: SecurityException) {
            permissionWasGranted = false
            unregisterAdapterReceiver()
            _scanState.value = ScanAvailability.PermissionMissing(revoked = true)
        } catch (throwable: Throwable) {
            unregisterAdapterReceiver()
            _scanState.value = ScanAvailability.Failed(
                throwable.message?.takeIf { it.isNotBlank() } ?: "启动扫描失败",
            )
        }
    }

    override fun stop() {
        val wasRequested = scanRequested.getAndSet(false)
        stopScannerInternal()
        unregisterAdapterReceiver()
        val current = _scanState.value
        if (wasRequested || current is ScanAvailability.Scanning) {
            _scanState.value = ScanAvailability.Stopped
        }
    }

    /**
     * 显式复查权限。Android 在扫描运行中撤销 `BLUETOOTH_SCAN` 后可能不再回调结果，
     * 会话看门狗借此把撤销转为可观察的扫描状态。
     */
    override fun revalidate() {
        if (!scanRequested.get() || hasScanPermission()) {
            return
        }
        if (!scanRequested.compareAndSet(true, false)) {
            return
        }
        permissionWasGranted = false
        stopScannerInternal()
        unregisterAdapterReceiver()
        _scanState.value = ScanAvailability.PermissionMissing(revoked = true)
    }

    /**
     * 平台条件恢复后在同一 Session 内重新提交扫描。只在以下可恢复状态自动重启：
     * 蓝牙重新开启（来源把 `BluetoothOff` 转为 `Idle`）、权限重新授予、或扫描退回到初始状态。
     * 用户主动停止（`Stopped`）与失败状态保持原样，避免覆盖用户操作。
     */
    override fun resumeIfReady() {
        if (scanRequested.get()) {
            return
        }
        when (_scanState.value) {
            ScanAvailability.Idle,
            ScanAvailability.BluetoothOff,
            -> start()

            // 权限仍缺失时不调用 start()，避免把 revoked 状态改成普通缺失；授权后下一轮再恢复。
            is ScanAvailability.PermissionMissing -> if (hasScanPermission()) {
                start()
            }

            else -> Unit
        }
    }

    override fun close() {
        stop()
    }

    private fun deliverResult(result: ScanResult) {
        if (!scanRequested.get()) {
            return
        }
        if (!hasScanPermission()) {
            permissionWasGranted = false
            scanRequested.set(false)
            stopScannerInternal()
            unregisterAdapterReceiver()
            _scanState.value = ScanAvailability.PermissionMissing(revoked = true)
            return
        }
        val receivedNanos = elapsedRealtimeNanos()
        val receivedEpochMillis = clock()
        _advertisements.tryEmit(
            AndroidScanResultMapper.toAdvertisement(
                result = result,
                receivedAtNanos = receivedNanos,
                receivedAtEpochMillis = receivedEpochMillis,
                deliverySequence = deliverySequence.incrementAndGet(),
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopScannerInternal() {
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // 权限已撤销时停止本身也可能失败；状态由调用方标记。
        } catch (_: Throwable) {
            // 停止失败不改变“已请求停止”的用户语义。
        }
    }

    private fun registerAdapterReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) {
            return
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(adapterReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(adapterReceiver, filter)
            }
        } catch (_: Throwable) {
            receiverRegistered.set(false)
        }
    }

    private fun unregisterAdapterReceiver() {
        if (!receiverRegistered.compareAndSet(true, false)) {
            return
        }
        try {
            appContext.unregisterReceiver(adapterReceiver)
        } catch (_: Throwable) {
            // 接收器可能已被系统回收。
        }
    }

    private fun hasScanPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun scanFailureReason(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "扫描注册失败"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "系统内部错误"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "设备不支持所需扫描特性"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "扫描硬件资源不足"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "扫描启动过于频繁"
        else -> "扫描失败（错误码 $errorCode）"
    }
}

/**
 * 把 [ScanResult] 实际提供的字段映射为 [BleAdvertisement]。
 *
 * 生产扫描回调与 instrumentation 测试共用此入口，确保测试覆盖 `device.address`、`rssi`、
 * `timestampNanos` 和 `scanRecord` 的真实读取路径，而不是只验证解析器参数。
 */
internal object AndroidScanResultMapper {

    fun toAdvertisement(
        result: ScanResult,
        receivedAtNanos: Long,
        receivedAtEpochMillis: Long,
        deliverySequence: Long,
    ): BleAdvertisement = AndroidScanRecordParser.toAdvertisement(
        address = result.device?.address,
        scanRecord = result.scanRecord,
        rssi = result.rssi,
        timestampNanos = result.timestampNanos,
        receivedAtNanos = receivedAtNanos,
        receivedAtEpochMillis = receivedAtEpochMillis,
        deliverySequence = deliverySequence,
    )
}

/**
 * 把 [ScanRecord] 提供的字段转成 [BleAdvertisement]。
 *
 * 从 [AndroidBleScanSource] 抽离后，instrumentation 测试可以用真实 [ScanRecord] 验证名称、UUID、厂商数据、
 * 地址和时间的读取，不必依赖现场存在 BLE 广播者。缺失的记录保持 null/空列表，不推断设备没有这些字段。
 */
internal object AndroidScanRecordParser {

    @SuppressLint("UseKtx")
    fun toAdvertisement(
        address: String?,
        scanRecord: ScanRecord?,
        rssi: Int,
        timestampNanos: Long,
        receivedAtNanos: Long,
        receivedAtEpochMillis: Long,
        deliverySequence: Long,
    ): BleAdvertisement {
        val serviceUuids = scanRecord?.serviceUuids
            ?.map { parcelUuid -> parcelUuid.uuid.toString() }
            ?: emptyList()

        val manufacturerData = buildList {
            val sparse = scanRecord?.manufacturerSpecificData
            if (sparse != null) {
                for (index in 0 until sparse.size()) {
                    add(
                        ManufacturerDataRecord(
                            companyId = sparse.keyAt(index),
                            payloadHex = sparse.valueAt(index).toHexString(),
                        ),
                    )
                }
            }
        }

        val observedAtNanos = timestampNanos.takeIf { it > 0L } ?: receivedAtNanos

        return BleAdvertisementMapper.map(
            address = address,
            deviceName = scanRecord?.deviceName,
            rssi = rssi,
            serviceUuids = serviceUuids,
            manufacturerData = manufacturerData,
            rawRecord = scanRecord?.bytes,
            observedAtNanos = observedAtNanos,
            receivedAtNanos = receivedAtNanos,
            receivedAtEpochMillis = receivedAtEpochMillis,
            deliverySequence = deliverySequence,
        )
    }
}
