# 11 实现锁屏测试、通知和中断记录

Type: task
Status: resolved
Blocked by: 08
User stories: 7, 21, 29, 30, 31, 37

## 任务

让用户在 App 内主动开启的采样或模拟测试能尽可能在锁屏继续运行。复用 05 的事件和 Session 生命周期，并保留 Android 实际可观测的结果。

**交付物** 运行中的会话能跨锁屏、换页继续采集，并能从记录分辨无事件、通知不可用和中断。

## 工作内容

1. 从 App 可见页面的用户操作启动 `connectedDevice` Foreground Service，声明 `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_CONNECTED_DEVICE`。服务开始前检查已授予的 `BLUETOOTH_SCAN`，启动时按系统要求提供服务通知。Android 13+ 单独处理 `POST_NOTIFICATIONS`。拒绝通知权限不抹掉模拟事件或阻止合法开始采样。 [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types) · [通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)
2. 复用 04/05 会话控制和 02 的扫描源。锁屏使用标准系统过滤，接收后仍经 02 的目标关联。使用通用目标验证过滤条件，避免品牌提示成为所有目标的唯一过滤条件。缺少合适的锁屏过滤条件时明确显示限制。只有确有需要时引入 `PendingIntent`。 [BLE 后台指南](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
3. 消费主动模拟测试已经持久化的事件，以稳定的事件标识提交本地通知或震动。分别记录事件是否产生、通知尝试状态及可识别的权限或系统失败。App 无法确认通知是否在锁屏呈现时，保持可追溯的提交状态。
4. 提供运行状态和结束测试的操作。用户停止、权限撤销或蓝牙关闭时停止不可用的采集并记录能观察到的变化。会话仍可合法运行时，锁屏解锁、换页和扫描恢复都沿用同一会话。恢复按 03/05 重新建立 `FAR`。若服务已结束，由用户在 App 可见时重新开始。进程终止或重启后重开 App 可查询已写入信息与中断状态。

## 反馈记录

| 试验观察 | 本地记录 | 验收证据 |
| --- | --- | --- |
| 没有符合条件的接近事件 | 0 条模拟事件 | 查状态、扫描样本与原因，通知不单独按事件失败计数 |
| 已有模拟事件，系统禁止通知 | 一条正常事件，反馈标为通知不可用并附已知原因 | 事件历史可查，另登记权限和锁屏未呈现 |
| App 成功提交通知 | 一条正常事件，反馈记录为已提交 | 锁屏界面是否呈现由 13/14 现场观察记录 |
| 通知尚未提交、系统返回错误或进程中止 | 已持久化事件保留，反馈保持真实的未提交、失败或未确认状态 | 下次打开 App 能查，恢复界面不会补发旧事件 |

## 验收与交接

- 在真机可用时，从 App 开始模拟测试，熄屏后用可控 BLE 输入造出 `FAR → NEAR`，确认记录继续、产生一条事件并观察锁屏通知。无设备时保留直接的执行步骤和待测状态。
- 关闭通知权限后重复验证，事件仍在历史可查。切换蓝牙或撤销扫描权限时，确认状态变化和恢复时零补触发。终止测试进程后重开，验证 Session 中断且已保存记录完整。
- 从用户开始操作分别进入前台和锁屏，记录所用过滤条件是否实际接收目标广播。若只在前台可用，补充目标过滤特征与锁屏未测或失败证据，供 13、14 判定。
- 检查应用 Manifest 和入口仍满足离线、只读扫描与用户主动启动。Answer 记录 Android 版本、实际使用的扫描及前台服务策略、权限行为、通知结果字段与现场步骤。

- [ ] 自动化覆盖服务与同一 Session 的开始/停止接合、扫描权限判断、通知被禁时事件仍可查、停止/恢复没有重复采集或补触发。仅在 Android 平台边界使用受控替身。真实锁屏收包和通知用设备证据确认。

## Answer

### 完成内容

- connectedDevice 前台服务：用户从 App 可见页面开始采样或模拟测试时，`AppContainer` 观察 `activeSession` 并经 `AndroidForegroundSessionLauncher` 启动 `ProximityLabForegroundService`；Manifest 声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE` 与 `foregroundServiceType=connectedDevice`。
- 启动前权限检查：`SessionRecorder` 在创建 Session、启动扫描和前台服务之前检查已授予的 `BLUETOOTH_SCAN`，缺失时返回 `ScanPermissionMissing`；`SessionStartResult` / `SimulationStartResult` 暴露该分支。
- 通知与震动反馈：`SimulationFeedbackCoordinator.follow(sessionId)` 消费已持久化的模拟事件，按稳定 eventId 提交通知或震动，并单独写回反馈状态与原因；事件事实字段不被修改。Android 13+ 由 `AndroidSimulationAlarm` 单独处理 `POST_NOTIFICATIONS` 及通知渠道状态，拒绝通知权限不抹掉事件、不阻止合法开始采样。
- 运行中恢复与中断：`SessionScanWatchdog` 周期复查扫描可用性，权限撤销或蓝牙关闭时记录可观察变化并停止不可用采集，恢复时沿用同一 Session 且零补触发；进程终止后重开由 `recoverInterruptedSessions` 标记 INTERRUPTED，已写入的时间线、事件反馈与配置快照仍可查。
- 锁屏采集限制说明：扫描使用标准系统过滤 `startScan(null, ...)`，未按品牌过滤，通用目标同样适用；`MonitorScreen` 明确显示该限制及锁屏呈现需 13/14 现场确认。
- 复审 low 问题（保留）：采样页没有 `POST_NOTIFICATIONS` 申请入口，仅监控页在开始模拟测试时申请。

### 实际验证

- `JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug --console=plain` → BUILD SUCCESSFUL；汇总 `app/build/test-results/testDebugUnitTest/*.xml` 为 196 tests、0 failures、0 errors、0 skipped；`lint-results-debug.txt` 为 0 errors、3 warnings（既有 OldTargetApi、mipmap ObsoleteSdkInt、MonochromeLauncherIcon）。（由实施执行）
- `JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain` → BUILD SUCCESSFUL；`TEST-Medium_Phone_API_37.0(AVD) - 17.xml` 根节点 tests=85、failures=0、errors=0、skipped=1（既有 `AndroidBleScanSourceTest.realAdvertisementsExposeFieldsWhenAnAdvertiserIsAvailable` 无真实广告者时 Assume 跳过）。（由实施执行）
- 聚焦 JVM：`./gradlew :app:testDebugUnitTest --tests 'com.byd.proximitylab.feedback.*' --tests 'com.byd.proximitylab.session.SessionScanPermissionTest'` → 16 tests、0 failures（ForegroundSessionCoordinatorTest 4、SimulationFeedbackCoordinatorTest 7、SessionScanPermissionTest 5）。
- 聚焦 connected 迭代：feedback 三组 7/7；MonitorProductionWiringTest + SamplingProductionWiringTest + AppContainerWiringTest 4/4；MonitorScreenTest + MonitorChartHistoryTest + SamplingScreenTest + SamplingProductionWiringTest + SamplingReplayScreenTest 16/16；AlgorithmConfigSimulationIntegrationTest 3/3，均 BUILD SUCCESSFUL；最终全量 connected 85/0 失败已再次覆盖。
- APK 权限：`aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk` → BLUETOOTH_SCAN(neverForLocation)、FOREGROUND_SERVICE、FOREGROUND_SERVICE_CONNECTED_DEVICE、POST_NOTIFICATIONS、VIBRATE、DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，无 INTERNET/CONNECT/定位；`dump badging` → minSdkVersion 31、targetSdkVersion 36、compileSdkVersion 37；`xmltree` → foregroundServiceType=0x00000010（connectedDevice）。
- `git diff --check 4b4c495d22966cc62ae73491110c7f1e4f290eae` → exit 0（仅有 Git 的 LF→CRLF 提示，无空白错误）。
- 脚本 gate：`.tooling/jdk-21/bin/java.exe ... :app:assembleDebug :app:testDebugUnitTest` → exit 0；`:app:connectedDebugAndroidTest` → exit 0；APK 检查 min/target/compile=true/true/true、INTERNET=false。
- 复审实跑：`JAVA_HOME=D:/data/workspace/di-tool/.tooling/jdk-21 ./gradlew.bat :app:testDebugUnitTest --tests "com.byd.proximitylab.feedback.ForegroundSessionCoordinatorTest" --tests "com.byd.proximitylab.feedback.SimulationFeedbackCoordinatorTest" --tests "com.byd.proximitylab.session.SessionScanPermissionTest" --offline --console=plain` → BUILD SUCCESSFUL；并读取 gate 生成的 `TEST-Medium_Phone_API_37.0(AVD) - 17.xml`（85 tests、0 failures、0 errors、1 skipped，唯一 skip 为既有真实 BLE 广告者用例）。
- 本记录轮未重跑测试或构建；以上为实施、脚本 gate 与复审的实际运行结果。

### 公开入口与下游

- `com.byd.proximitylab.feedback.SimulationAlarm` / `SimulationAlertCapabilities` / `SimulationAlertDelivery` / `SimulationFeedbackWriter`：反馈平台边界与可观察结果模型（`feedback/FeedbackModels.kt`）。
- `com.byd.proximitylab.feedback.SimulationFeedbackCoordinator.follow(sessionId: String?)`：按稳定 eventId 消费已持久化模拟事件并写回反馈（`SimulationFeedbackCoordinator.kt:48`）。
- `com.byd.proximitylab.feedback.ForegroundSessionLauncher` / `ForegroundSessionStartResult` / `ForegroundSessionStatus` / `ForegroundSessionCoordinator.status`：活动 Session 与前台服务接合（`ForegroundSessionCoordinator.kt:18`）。
- `com.byd.proximitylab.feedback.ProximityLabForegroundService.start(context, sessionId)` / `stop(context)` / `runningSessionId` 与 `FeedbackNotifications` 渠道/通知常量：connectedDevice 前台服务入口（`ProximityLabForegroundService.kt:79`）。
- `com.byd.proximitylab.feedback.AndroidSimulationAlarm`、`AndroidForegroundSessionLauncher`：生产 Android 通知/震动与前台服务启动实现。
- `com.byd.proximitylab.ble.ScanPermissionChecker` / `AndroidScanPermissionChecker`：开始前 `BLUETOOTH_SCAN` 判断；`SessionRecorder` 构造参数 `scanPermissionChecker`。
- `SessionStartResult.ScanPermissionMissing` 与 `SimulationStartResult.ScanPermissionMissing`：开始入口的下游结果分支（`session/SessionModels.kt`、`live/SimulationResults.kt`）。
- `com.byd.proximitylab.ble.BleScanSource.revalidate()` / `resumeIfReady()`、`TargetMonitor.revalidateScanAvailability()` / `resumeScanIfReady()`、`SessionScanWatchdog`：运行中权限/蓝牙恢复与同一 Session 扫描恢复（`SessionScanWatchdog.kt:20`）。
- `AppContainer` 新增参数 `scanPermissionChecker`、`foregroundSessionLauncher`、`simulationAlarm`；新增 `foregroundSessionCoordinator`、`simulationFeedbackCoordinator`、`scanWatchdog`（`ProximityLabApplication.kt:62-186`）。
- `MonitorViewModel.alertCapabilities` / `foregroundStatus` / `refreshAlertCapabilities` 与 `monitorFactory` 新增 `simulationAlarm`、`foregroundCoordinator` 参数；`MonitorScreen` 新增标签 `monitor_alert_card`、`monitor_foreground_status`、`monitor_notification_status`、`monitor_lock_screen_limit`、`monitor_request_notifications`。
- 测试替身：sharedTest 的 `RecordingSimulationAlarm`、`RecordingForegroundSessionLauncher`；androidTest 的 `AndroidTestPermissions`，供 12 及后续集成复用。

### 未测项

- 真实锁屏收包与锁屏通知呈现未验证：当前只有 API 37 模拟器 `emulator-5554`，没有可控 BLE 广告者、真实手机或目标车辆。现场步骤：安装 debug APK 并授予 `BLUETOOTH_SCAN`，绑定目标后从监控页开始模拟测试，熄屏后用可控 BLE 源按绑定地址先发两次低于离开阈值的广播确认 FAR、再发两次高于进入阈值的广播确认 NEAR；检查事件历史为 1 条 `ENTERED_RANGE`、通知反馈为已提交，并观察锁屏是否显示通知；随后关闭通知权限重复，确认事件仍在历史中且反馈为通知不可用。
- 真实 Android 13+ `POST_NOTIFICATIONS` 拒绝、通知渠道被用户关闭且进程仍存活的场景未在设备上执行（撤销运行时权限会终止目标进程，无法在 instrumentation 内安全复现）；自动化使用平台能力读取器的受控替身覆盖 UNAVAILABLE 分支，真实通知发布用已授权权限验证。
- 真实蓝牙开关切换、运行中撤销 `BLUETOOTH_SCAN` 后的扫描恢复未在真实 Adapter 上执行；JVM 测试用 `ControllableBleScanSource` 的 revalidate/resume 状态变化验证看门狗、时间线记录与恢复后零补触发，`AndroidBleScanSource.revalidate`/`resumeIfReady` 的 Adapter 行为需真机确认。
- 未用 adb force-stop 或真实进程终止，而是取消协程并关闭/重开 Room 模拟进程终止；已写入时间线、事件反馈、配置快照与 INTERRUPTED 状态在该方式下验证通过。
- 未执行 release 构建或 release lint；仅 debug assemble、debug lint 和 debug connected。
- 未做真实 Android 12 设备兼容验证；本票 connected 均在 API 37 模拟器运行。
- 未在 13 可控 BLE 台架或 14 目标车/目标手机现场验收；现场需记录实际扫描过滤接收、锁屏收包、通知呈现、中断与耗电表现。
- 复审 low 问题未修复：采样页缺少 `POST_NOTIFICATIONS` 申请入口。

## Comments

- claimed at HEAD 4b4c495d22966cc62ae73491110c7f1e4f290eae
