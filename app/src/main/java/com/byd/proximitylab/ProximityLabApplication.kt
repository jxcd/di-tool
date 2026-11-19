package com.byd.proximitylab

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore
import com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore
import com.byd.proximitylab.ble.AndroidBleScanSource
import com.byd.proximitylab.ble.AndroidScanPermissionChecker
import com.byd.proximitylab.ble.BleScanSource
import com.byd.proximitylab.ble.ScanPermissionChecker
import com.byd.proximitylab.comparison.SessionComparisonService
import com.byd.proximitylab.data.ProximityLabDatabase
import com.byd.proximitylab.feedback.AndroidForegroundSessionLauncher
import com.byd.proximitylab.feedback.AndroidSimulationAlarm
import com.byd.proximitylab.feedback.ForegroundSessionCoordinator
import com.byd.proximitylab.feedback.ForegroundSessionLauncher
import com.byd.proximitylab.feedback.SimulationAlarm
import com.byd.proximitylab.feedback.SimulationFeedbackCoordinator
import com.byd.proximitylab.replay.SessionReplayService
import com.byd.proximitylab.replay.SessionReplayStore
import com.byd.proximitylab.replay.data.RoomSessionReplayStore
import com.byd.proximitylab.session.SessionMarkerRecorder
import com.byd.proximitylab.session.SessionRecorder
import com.byd.proximitylab.session.SessionScanWatchdog
import com.byd.proximitylab.session.SessionStore
import com.byd.proximitylab.session.data.RoomSessionStore
import com.byd.proximitylab.simulation.SimulationRunStore
import com.byd.proximitylab.simulation.data.RoomSimulationRunStore
import com.byd.proximitylab.target.NoVerifiedIdentityEvidence
import com.byd.proximitylab.target.TargetAssociationPolicy
import com.byd.proximitylab.target.TargetBindingStore
import com.byd.proximitylab.target.TargetMonitor
import com.byd.proximitylab.target.data.RoomTargetBindingStore
import com.byd.proximitylab.live.LiveMonitorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProximityLabApplication : Application() {

    @Volatile
    private var containerOverride: AppContainer? = null

    /**
     * 生产容器使用 Android 权限与前台服务/通知实现。
     *
     * instrumentation 可以通过 [setContainerForTest] 注入只替代 BLE、通知和前台服务边界的容器；
     * 生产路径始终使用按需创建的唯一默认容器。
     */
    val container: AppContainer
        get() = containerOverride ?: defaultContainer

    private val defaultContainer: AppContainer by lazy {
        AppContainer(
            context = this,
            scanPermissionChecker = AndroidScanPermissionChecker(this),
        )
    }

    /**
     * 仅供 instrumentation 使用：在 `MainActivity` 启动前注入可控容器，传入 `null` 恢复生产容器。
     */
    fun setContainerForTest(container: AppContainer?) {
        containerOverride = container
    }
}

/**
 * 普通构造器接线。04/05/11 复用同一 [TargetMonitor]、扫描来源和 Session 控制器，不引入 DI 框架。
 *
 * Session 表扩展 [ProximityLabDatabase] 并通过迁移保留 02 的绑定和已采数据，不新建第二个 Room 数据库。
 */
class AppContainer(
    context: Context,
    databaseName: String = ProximityLabDatabase.NAME,
    scanSource: BleScanSource = AndroidBleScanSource(context.applicationContext),
    scanPermissionChecker: ScanPermissionChecker = AndroidScanPermissionChecker(context.applicationContext),
    foregroundSessionLauncher: ForegroundSessionLauncher =
        AndroidForegroundSessionLauncher(context.applicationContext),
    val simulationAlarm: SimulationAlarm = AndroidSimulationAlarm(context.applicationContext),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: ProximityLabDatabase = Room.databaseBuilder(
        context.applicationContext,
        ProximityLabDatabase::class.java,
        databaseName,
    )
        .addMigrations(
            ProximityLabDatabase.MIGRATION_1_2,
            ProximityLabDatabase.MIGRATION_2_3,
            ProximityLabDatabase.MIGRATION_3_4,
            ProximityLabDatabase.MIGRATION_4_5,
            ProximityLabDatabase.MIGRATION_5_6,
        )
        .build()

    val bindingStore: TargetBindingStore = RoomTargetBindingStore(database.targetBindingDao())

    val scanSource: BleScanSource = scanSource

    /**
     * 06 的算法配置与当前生效项。内置 Baseline/EMA 由代码固定，用户配置与选择保存在同一数据库中。
     */
    val algorithmConfigStore: AlgorithmConfigStore =
        RoomAlgorithmConfigStore(database.algorithmConfigDao())

    /**
     * 生产环境暂不提供可复验身份证据：真实车辆地址轮换的唯一依据留给票据 14。
     * 因此地址变化走保守分支，保留原绑定并报告身份不确定。
     */
    val targetMonitor: TargetMonitor = TargetMonitor(
        scanSource = scanSource,
        bindingStore = bindingStore,
        scope = scope,
        policy = TargetAssociationPolicy(NoVerifiedIdentityEvidence),
    )

    val sessionStore: SessionStore = RoomSessionStore(database.samplingSessionDao())

    val simulationRunStore: SimulationRunStore =
        RoomSimulationRunStore(database.simulationRunDao())

    /**
     * 09 的回放结果存储与入口。回放只写独立表，不依赖实时监控、模拟运行结果或通知/震动。
     */
    val sessionReplayStore: SessionReplayStore =
        RoomSessionReplayStore(database.sessionReplayDao())

    val sessionReplayService: SessionReplayService = SessionReplayService(
        sessionStore = sessionStore,
        replayStore = sessionReplayStore,
        configStore = algorithmConfigStore,
    )

    /**
     * 10 的同一 Session 多配置对比评分入口，复用 09 的真实回放服务与 06 的配置存储。
     */
    val sessionComparisonService: SessionComparisonService = SessionComparisonService(
        sessionStore = sessionStore,
        replayService = sessionReplayService,
        configStore = algorithmConfigStore,
    )

    /**
     * 04/05/08/11 共用的会话控制与追加入口。
     *
     * 11：开始前检查 `BLUETOOTH_SCAN`，未授予时不会创建 Session，也就不会启动前台服务。
     */
    val sessionRecorder: SessionRecorder = SessionRecorder(
        store = sessionStore,
        observationSource = targetMonitor,
        scope = scope,
        scanPermissionChecker = scanPermissionChecker,
    )

    /**
     * 08 的实时人工标记入口，采样页与模拟测试页共用同一 Session 时基。
     */
    val sessionMarkerRecorder: SessionMarkerRecorder = SessionMarkerRecorder(store = sessionStore)

    /**
     * 05 实时监控与前台模拟测试入口。放在应用级作用域，界面旋转、换页或重新订阅不会结束运行中的测试。
     */
    val liveMonitorController: LiveMonitorController = LiveMonitorController(
        observationSource = targetMonitor,
        recorder = sessionRecorder,
        sessionStore = sessionStore,
        simulationStore = simulationRunStore,
        scope = scope,
    )

    /**
     * 11：活动 Session 与 `connectedDevice` 前台服务的唯一接合点。启动失败只记录状态，不结束 Session。
     */
    val foregroundSessionCoordinator: ForegroundSessionCoordinator = ForegroundSessionCoordinator(
        activeSession = sessionRecorder.activeSession,
        launcher = foregroundSessionLauncher,
        scope = scope,
    )

    /**
     * 11：消费已经持久化的模拟事件并提交本地通知/震动。反馈写回同时更新 Room 与实时界面。
     */
    val simulationFeedbackCoordinator: SimulationFeedbackCoordinator = SimulationFeedbackCoordinator(
        store = simulationRunStore,
        alarm = simulationAlarm,
        scope = scope,
        feedbackWriter = { eventId, status, detail ->
            liveMonitorController.updateEventFeedback(eventId, status, detail)
        },
    )

    /**
     * 11：会话期间周期复查扫描权限，把运行中撤销转为可观察的扫描状态变化。
     */
    val scanWatchdog: SessionScanWatchdog = SessionScanWatchdog(
        activeSession = sessionRecorder.activeSession,
        revalidate = targetMonitor::revalidateScanAvailability,
        scope = scope,
        resume = targetMonitor::resumeScanIfReady,
    )

    init {
        // 进程启动时把上次异常终止遗留的采集中 Session 标为中断，只保留已写入的覆盖终点。
        scope.launch {
            sessionRecorder.recoverInterruptedSessions()
        }
        // 运行中的模拟测试出现/结束时跟随其 Session；重开 App 不会补发旧事件。
        scope.launch {
            liveMonitorController.state.collect { state ->
                simulationFeedbackCoordinator.follow(
                    state.test?.takeIf { test -> test.running }?.session?.id,
                )
            }
        }
        scanWatchdog.start()
    }

    /**
     * 释放数据库与协程作用域。生产进程不调用；测试用独立容器验证接线后释放资源。
     */
    fun close() {
        scanWatchdog.stop()
        scope.cancel()
        database.close()
    }
}
