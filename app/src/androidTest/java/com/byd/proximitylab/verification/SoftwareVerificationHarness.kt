package com.byd.proximitylab.verification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.byd.proximitylab.AppContainer
import com.byd.proximitylab.ble.ScanAvailability
import com.byd.proximitylab.ble.ScanPermissionChecker
import com.byd.proximitylab.session.SamplingSession
import com.byd.proximitylab.session.SessionTimelineEvent
import com.byd.proximitylab.session.SessionTimelineItem
import com.byd.proximitylab.simulation.SessionRunEvent
import com.byd.proximitylab.target.BoundTarget
import com.byd.proximitylab.testing.ControllableBleScanSource
import com.byd.proximitylab.testing.RecordingForegroundSessionLauncher
import com.byd.proximitylab.testing.RecordingSimulationAlarm
import com.byd.proximitylab.testing.testAdvertisement
import com.byd.proximitylab.ui.AppViewModelProvider
import com.byd.proximitylab.ui.device.DeviceViewModel
import com.byd.proximitylab.ui.lab.AlgorithmLabViewModel
import com.byd.proximitylab.ui.monitor.MonitorViewModel
import com.byd.proximitylab.ui.sampling.SamplingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.util.UUID

/**
 * 12 的软件验收夹具：只替代 BLE 系统回调、通知/前台服务和独立数据库名，
 * 其余从 [AppContainer] 到 Room、Session、判定、回放与 ViewModel 接线全部使用生产实现。
 *
 * 每个用例使用独立数据库；容器重启场景可复用同一数据库名，模拟进程终止后重新启动。
 */
class SoftwareVerificationHarness(
    private val context: Context = ApplicationProvider.getApplicationContext(),
) {

    val databaseName: String = "software-verification-${UUID.randomUUID()}.db"

    val source: ControllableBleScanSource = ControllableBleScanSource()

    val alarm: RecordingSimulationAlarm = RecordingSimulationAlarm()

    val launcher: RecordingForegroundSessionLauncher = RecordingForegroundSessionLauncher()

    var container: AppContainer = newContainer()
        private set

    private val viewModelStores = mutableListOf<ViewModelStore>()
    private var closed = false
    private var sequence = 0L

    /**
     * 通过生产设备页 ViewModel 启动扫描、接收广播并完成人工绑定。
     */
    fun bindThroughDeviceEntry(
        address: String = TARGET_ADDRESS,
        displayName: String = TARGET_NAME,
    ): BoundTarget {
        val viewModel = viewModel<DeviceViewModel>(
            AppViewModelProvider.deviceFactory(container.targetMonitor),
        )
        viewModel.startScan()
        waitUntil("扫描启动") {
            container.targetMonitor.state.value.scanState is ScanAvailability.Scanning
        }
        feed(rssi = -70, address = address)
        waitUntil("候选设备出现") {
            container.targetMonitor.state.value.candidates.any { candidate -> candidate.address == address }
        }
        viewModel.bind(address)
        waitForBinding(address)
        viewModel.stopScan()
        waitUntil("扫描停止") {
            container.targetMonitor.state.value.scanState is ScanAvailability.Stopped
        }
        waitForBinding(address)
        return requireNotNull(container.targetMonitor.state.value.binding)
    }

    fun samplingViewModel(): SamplingViewModel = viewModel(
        AppViewModelProvider.samplingFactory(
            recorder = container.sessionRecorder,
            store = container.sessionStore,
            observationSource = container.targetMonitor,
            markerRecorder = container.sessionMarkerRecorder,
            simulationStore = container.simulationRunStore,
            replayService = container.sessionReplayService,
        ),
    )

    fun monitorViewModel(): MonitorViewModel = viewModel(
        AppViewModelProvider.monitorFactory(
            controller = container.liveMonitorController,
            configStore = container.algorithmConfigStore,
            sessionStore = container.sessionStore,
            simulationStore = container.simulationRunStore,
            markerRecorder = container.sessionMarkerRecorder,
            simulationAlarm = alarm,
            foregroundCoordinator = container.foregroundSessionCoordinator,
        ),
    )

    fun labViewModel(): AlgorithmLabViewModel = viewModel(
        AppViewModelProvider.labFactory(
            store = container.algorithmConfigStore,
            comparisonService = container.sessionComparisonService,
        ),
    )

    /**
     * 模拟一次 BLE 广播回调。默认每次使用新的扫描帧标识；需要重复投递同一帧时显式复用
     * `address`、`observedAtNanos` 与 `payloadFingerprint`。
     */
    fun feed(
        rssi: Int,
        address: String = TARGET_ADDRESS,
        observedAtEpochMillis: Long = System.currentTimeMillis(),
        receivedAtEpochMillis: Long = observedAtEpochMillis + 1L,
        observedAtNanos: Long = nextObservedAtNanos(),
        payloadFingerprint: String = "software-verification-frame-${sequence + 1}",
        deliverySequence: Long = sequence + 1,
    ) {
        sequence = maxOf(sequence + 1L, deliverySequence)
        val emitted = source.emit(
            testAdvertisement(
                address = address,
                rssi = rssi,
                observedAtNanos = observedAtNanos,
                receivedAtNanos = observedAtNanos + 1_000_000L,
                observedAtEpochMillis = observedAtEpochMillis,
                receivedAtEpochMillis = receivedAtEpochMillis,
                payloadFingerprint = payloadFingerprint,
                deliverySequence = deliverySequence,
            ),
        )
        assertTrue("扫描事件缓冲拒绝投递，广播未被处理", emitted)
    }

    /**
     * 模拟进程终止后重新启动：释放旧容器的 ViewModel 与协程作用域，用同一数据库打开新容器，
     * 由生产启动恢复逻辑处理遗留的采集中 Session。
     */
    fun restartContainer(): AppContainer {
        clearViewModels()
        container.close()
        container = newContainer()
        return container
    }

    fun session(sessionId: String): SamplingSession? =
        runBlocking { container.sessionStore.loadSession(sessionId) }

    fun timeline(sessionId: String): List<SessionTimelineItem> =
        runBlocking { container.sessionStore.loadTimeline(sessionId) }

    fun observations(sessionId: String): List<SessionTimelineEvent.Observation> =
        timeline(sessionId).mapNotNull { item -> item.event as? SessionTimelineEvent.Observation }

    fun events(sessionId: String): List<SessionRunEvent> =
        runBlocking { container.simulationRunStore.loadEvents(sessionId) }

    fun markers(sessionId: String) =
        runBlocking { container.sessionStore.loadReferenceMarkers(sessionId) }

    /**
     * 判定入口异步消费已持久化时间线。等待到指定处理时刻，避免用固定 sleep 猜测消费完成。
     */
    fun waitForTestDecisionAt(atEpochMillis: Long) {
        waitUntil("判定消费到 $atEpochMillis") {
            val decision = container.liveMonitorController.state.value.test?.decision
            val decidedAt = decision?.lastDecisionAtEpochMillis
            decidedAt != null && decidedAt >= atEpochMillis
        }
    }

    fun waitUntil(
        message: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue("等待「$message」超时（${timeoutMillis}ms）", condition())
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        clearViewModels()
        runCatching { runBlocking { container.sessionRecorder.stop() } }
        runCatching { container.close() }
        context.deleteDatabase(databaseName)
    }

    private fun clearViewModels() {
        viewModelStores.forEach { store -> store.clear() }
        viewModelStores.clear()
    }

    private inline fun <reified T : ViewModel> viewModel(factory: ViewModelProvider.Factory): T {
        val store = ViewModelStore()
        viewModelStores += store
        return ViewModelProvider(store, factory)[T::class.java]
    }

    private fun waitForBinding(address: String) {
        waitUntil("人工绑定完成且状态与存储一致") {
            val stored = runBlocking { container.bindingStore.loadBinding() }
            stored?.address == address &&
                container.targetMonitor.state.value.binding?.address == address
        }
    }

    private fun newContainer(): AppContainer = AppContainer(
        context = context,
        databaseName = databaseName,
        scanSource = source,
        // 本夹具只验证软件接线；真实 BLUETOOTH_SCAN 与系统服务由 11 的专门测试和现场票覆盖。
        scanPermissionChecker = ScanPermissionChecker.ALWAYS_GRANTED,
        foregroundSessionLauncher = launcher,
        simulationAlarm = alarm,
    )

    private fun nextObservedAtNanos(): Long {
        sequence += 1L
        return sequence * 1_000_000_000L
    }

    companion object {
        const val TARGET_ADDRESS: String = "AA:BB:CC:DD:EE:12"
        const val OTHER_ADDRESS: String = "AA:BB:CC:DD:EE:13"
        const val TARGET_NAME: String = "软件验收目标"

        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L
        private const val POLL_INTERVAL_MILLIS: Long = 20L
    }
}
