# 05 从前台模拟测试保存一次真实判定结果

Type: task
Status: resolved
Blocked by: 04
User stories: 8, 11, 12, 21, 24, 25, 26, 27, 28, 30, 31, 32

## 任务

从监控入口开启一次前台模拟测试，使用 03 的判定和 04 的统一会话存储。在屏幕上看到 `FAR → NEAR` 后的一次模拟事件，结束后仍可从记录中查到。07 扩展图表和完整历史，11 接入系统反馈。

**交付物** [07](07-live-monitor-ui.md)、[08](08-sampling-session-ui.md) 和 [11](11-lock-screen-test.md) 共用的实时入口，以及 [09](09-replay-engine.md) 可读取的真实时间线。

## 工作内容

1. 将目标样本、扫描/身份变化和时间推进序列化到同一处理顺序。将观测与收到时刻转换到可比较的会话时间基准，并保留处理顺序。用可取消的计时按实际处理时间推进样本过期，无新回调时也能输出 `UNKNOWN`。将同样的时间线保存给 09 回放，含最后一个样本之后已记录的时间范围。
2. 实时监控公开原始和滤波值、近段时间序列、状态、趋势、阈值、判定原因、最后观测与扫描诊断。展示数据与需要存档的 Session 区分开，监控的环形窗口不限制 Session 保存长度。
3. 在现有监控入口提供开始/结束、当前状态和最近一条模拟事件，走通一次前台测试。复用 04 的会话控制。开始时为当前绑定目标和默认配置建立新的判定上下文，从 `UNKNOWN` 开始。平时监控已确认的 `FAR` 不会带入。目标的手动切换需结束当前实验或拒绝切换并说明原因，避免混合数据。
4. 在同一 Room 数据库扩展模拟测试的配置快照、版本和原始运行结果。运行时读取本次快照，用户配置管理由 06 接入。用 04 的相对时基将状态、事件和实际输入关联，保持原始结果可重查。
5. 只让主动运行的模拟测试写入实时 `ENTERED_RANGE` 历史。事件持久化后提供反馈入口，11 接入。本票以本地可见的模拟事件为验收，不使用 Android 通知或震动。反馈成败只更新通知结果，界面重新订阅不会再次产生事件。
6. 普通界面旋转、换页或重新订阅不会使正在运行的 Session 结束。进程中止沿用 04 的会话标记和保留策略，11 使用同一入口处理锁屏。

## 会话模式

| 模式 | 何时开始 | 持久化 | 模拟反馈 |
| --- | --- | --- | --- |
| 普通监控 | 经实时监控入口查看当前目标 | 短时曲线和状态用于显示 | 无 |
| 定点或行走采样 | 用户选类型并点开始 | 沿用 04 的 Session 和时间线，08 接入行走采样及标记 | 无 |
| 模拟测试 | 用户点开始，判定从 `UNKNOWN` 起步 | 保存 Session、配置快照、时间线和原始事件 | 仅对本测试真实产生的事件派发 |
| 历史回放 | 用户选择 Session 与配置 | 由 09 保存新算结果 | 无 |

验收顺序可以直接用 [03 的 P1–P5](03-proximity-engine.md#判定用例)。重点检查「普通监控已 `FAR` → 开始模拟测试 → 立即只有近端样本」依然为 0 条模拟事件。普通监控和保存中的 Session 即使共用同一数据源，也各自按以上规则输出。

## 验收与交接

- 向实际实时入口喂入固定时间线。先在监控确认 `FAR`，开始模拟测试后只喂近端样本，预期显示 `NEAR` 且零历史事件。再确认 `FAR → NEAR`，预期唯一事件及可查询原因。
- 验证无新样本时状态过期、身份冲突和重新建立 `FAR` 后折返。对同一已保存 Session 检查样本、扫描变化、结束相对时间、快照、状态与事件能关联。运行中编辑具名配置的验收在 06 完成。
- [ ] 以可控 BLE 来源从正常监控入口验证测试开始、状态变化、事件持久化和停止。注入时钟复验空窗过期。重开界面不补发。快照与数据在重开后保留，未正常结束的会话变中断。
- [ ] 从 04 的 Room schema 升级后打开已有定点 Session，确认原始样本、顺序和覆盖范围仍可读取。
- Answer 写明监控订阅、测试控制、配置快照接入点、历史查询和反馈接口，供 06、07、08、09、11 复用。

## Answer

### 完成内容

- 新增实时监控层 `com.byd.proximitylab.live`：`LiveMonitorController` 在应用级作用域管理普通监控与前台模拟测试。普通监控订阅当前绑定目标的样本；模拟测试复用 04 的会话控制，为当前绑定目标和默认配置单独建立从 `UNKNOWN` 起步的判定上下文，普通监控已确认的 `FAR` 不会带入测试。目标手动切换需结束实验或拒绝并说明原因。
- 实时与回放共用同一时间线映射：`SessionTimelineProximityRunner` 把 04 的会话时间线事件（样本、扫描/身份变化、时间推进）按同一处理顺序送入 03 的 `ProximityEngine`，并用可取消的计时按实际处理时间推进样本过期，无新回调时也能输出 `UNKNOWN`；同一时间线连同最后一个样本之后已记录的时间范围一并持久化，供 09 回放。
- Room 扩展模拟测试数据：`ProximityLabDatabase` 升到 version 3，新增配置快照与原始运行结果（`SessionRunEvent`/`SessionRunState`），导出 `app/schemas/com.byd.proximitylab.data.ProximityLabDatabase/3.json`，提供 `MIGRATION_2_3`；04 的既有定点 Session 原始样本、顺序与覆盖范围在升级后仍可读。运行时读取本次落库的快照，用户配置管理由 06 接入。
- 只有主动运行的模拟测试写入实时 `ENTERED_RANGE` 历史；事件持久化后才提供反馈入口，`updateEventFeedback` 只更新通知字段（状态、说明、更新时间），不改变事件事实。界面重新订阅不会再次产生事件。
- 监控页 `MonitorScreen`/`MonitorViewModel` 提供开始/结束测试、当前状态和最近一条模拟事件，并展示原始/滤波值、近段时间序列、状态、趋势、阈值、判定原因、最后观测与扫描诊断；监控环形窗口只用于显示，不限制 Session 保存长度。旋转、换页或重新订阅不会结束运行中的 Session。

### 实际验证

实施阶段（由实施 Agent 实际执行，本轮按要求未重复运行）：

- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL，exit 0；13 个测试类共 98 tests，0 failures、0 errors、0 skipped。新增 `LiveMonitorControllerTest` 11（含外部共享停止、自定义 EMA、自定义阈值、反馈、重订阅等）与 `SessionTimelineProximityRunnerTest` 4。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain` → BUILD SUCCESSFUL，exit 0；`TEST-Medium_Phone_API_37.0(AVD) - 17.xml` 为 tests=36、failures=0、errors=0、skipped=1（02 的真实广告者用例按 Assume 跳过）。`MonitorScreenTest` 4/4（含最终 UNKNOWN/SCAN_UNAVAILABLE 断言）、`MonitorProductionWiringTest` 1/1（含导航换页与清理）、`SimulationRoomStoreTest` 2/2、`ProximityLabDatabaseMigrationTest` 2/2 通过。此前一次全量 connected 出现过 04 `SamplingScreenTest` 既有语义树 flake，单独重跑该类及最终全量均通过。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:assembleDebug :app:lintDebug --console=plain` → BUILD SUCCESSFUL，exit 0；`app/build/reports/lint-results-debug.txt` 为 0 errors、3 warnings（OldTargetApi、ObsoleteSdkInt、MonochromeLauncherIcon，均为 01 基线）。
- `"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk` → compileSdk 37、minSdk 31、targetSdk 36；权限只有 `BLUETOOTH_SCAN`（neverForLocation）与系统 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无 `INTERNET`、`BLUETOOTH_CONNECT` 或定位权限。
- `git diff --check` → exit 0（仅有 Git 的 LF→CRLF 行尾提示，无空白错误）。
- 实现遵循先测试后实现：行为测试使用真实 `TargetMonitor`、`SessionRecorder`、`ProximityEngine`、Room 和 Compose 入口，只在 BLE 系统回调与时钟边界使用可控替身。

独立复审（由复审 Agent 实际运行）：

- 通过：实际实时入口 `FAR` 后开始模拟测试、只喂近端样本得到 `NEAR` 且零事件。`JAVA_HOME=$PWD/.tooling/jdk-21 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.byd.proximitylab.ui.monitor.MonitorScreenTest` → BUILD SUCCESSFUL，4 tests / 0 failures；`normalMonitorFarIsNotCarriedAndOnlyNearSimulationKeepsZeroEvents` 断言普通监控 `FAR`、测试 `UNKNOWN`/`AWAITING_OBSERVATION`、`NEAR` 时 events=0 且 Room 无事件。
- 通过：先 `FAR` 后 `NEAR` 唯一事件及可查询原因。`JAVA_HOME=$PWD/.tooling/jdk-21 ./gradlew :app:testDebugUnitTest --tests 'com.byd.proximitylab.live.*' --tests 'com.byd.proximitylab.session.SessionTimelineProximityRunnerTest'` → BUILD SUCCESSFUL；`LiveMonitorControllerTest` 11 tests、`SessionTimelineProximityRunnerTest` 4 tests，均 0 failures/0 skipped；`confirmedFarThenNear…` 断言唯一 `ENTERED_RANGE` / `CONFIRMED_NEAR_AFTER_CONFIRMED_FAR`、frameId、raw/filtered RSSI 和版本。
- 通过：空窗过期、身份冲突、重新确认 `FAR` 后折返。`timerExpiresSamplesWithInjectedClockAndRecoveryDoesNotEmit` 用 250ms ticker + 注入时钟断言 `SAMPLES_EXPIRED` 且有对应运行状态；`scanStopAndIdentityConflict…` 断言停扫和身份冲突回到 `UNKNOWN` 且恢复不补发；`MonitorScreenTest.injectedClockExpiresTestWithoutNewSamplesAndRecoveryEmitsNothing` 在 Room 和真实页面上复验空窗过期。
- 通过：同一已保存 Session 的样本、扫描变化、结束相对时间、快照、状态与事件关联。`MonitorScreenTest.confirmedFarThenNearPersistsOneEventAndReopeningUiDoesNotReemit` 断言 4 条 Observation、扫描 true/false、`durationMillis=4000`、`DEFAULT` 快照、`NEAR`/`CONFIRMED_NEAR` 状态、末尾 `UNKNOWN`/`SCAN_UNAVAILABLE`，且事件 frameId 能在时间线 Observation 中找到。
- 通过（测试组合覆盖）：以可控 BLE 来源从监控入口验证开始、状态变化、事件持久化和停止。`MonitorScreenTest` 使用 `ControllableBleScanSource` + 真实 Room/`TargetMonitor`/`SessionRecorder`/`LiveMonitorController`/`MonitorScreen`；`MonitorProductionWiringTest` 经 `MainActivity`/`AppContainer` 验证生产接线、快照、换页不结束和停止，复审实际运行该类 → 1 test / 0 failures。提醒（low）：没有单一测试同时使用 `MainActivity`/`AppContainer` 与可控 BLE 来源，属覆盖拆分，非行为失败。
- 通过：重开界面不补发、快照与数据保留、未正常结束的会话变中断。覆盖来自 `MonitorScreenTest` 的 ViewModel 重建、数据库关闭重开和 `interruptedSimulationKeepsSnapshotTimelineAndEvents`，以及 `LiveMonitorControllerTest.resubscribingStateDoesNotReemitPersistedEvents`。
- 通过：04→05 Room schema 升级后已有定点 Session 的原始样本、顺序和覆盖范围可读。`JAVA_HOME=$PWD/.tooling/jdk-21 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.byd.proximitylab.data.ProximityLabDatabaseMigrationTest,com.byd.proximitylab.simulation.data.SimulationRoomStoreTest` → BUILD SUCCESSFUL；Migration 2 tests、SimulationRoomStore 2 tests，均 0 failures；`version2SessionTimelineSurvivesMigrationAndStaysReadable` 断言 sequence 1,2,3、RSSI、processedAt、coverageEnd、`user_version=3`，且迁移后即可写入 run event。
- 反馈边界：05 内没有回放入口，实时反馈只经 `LiveMonitorController.updateEventFeedback` 更新已持久化事件的通知字段（`feedbackUpdatesOnlyNotificationFieldsAndKeepsEventFacts` 通过）；09 尚未实现，因此「09 回放时不派发反馈」没有可实测行为，复审未将其列为已执行检查。
- 构建/测试 gate：脚本报告 `:app:assembleDebug :app:testDebugUnitTest` 与 `:app:connectedDebugAndroidTest` 均 exit 0；复审未重复运行全量 gate，也未运行 lint/APK 安装检查。仓库约定核对：AndroidManifest 无 `INTERNET`、仅 `BLUETOOTH_SCAN`（neverForLocation）；`libs.versions.toml` 版本固定、无动态或预览版；compileSdk 37/minSdk 31/targetSdk 36 与报告一致；未新增依赖。

### 公开入口与下游

- `com.byd.proximitylab.live.LiveMonitorController`：`state: StateFlow<LiveMonitorState>`、`startMonitoring()`、`suspend startSimulation()`、`suspend startSimulation(configSnapshot, name)`、`suspend stopSimulation()`、`suspend updateEventFeedback(eventId, status, detail)`、`suspend refreshNow(atEpochMillis)`、`suspend loadConfigSnapshot(sessionId)`、`clearMessage()`；生产实例为 `AppContainer.liveMonitorController`（应用级作用域，界面重建不结束 Session）。控制器订阅 `SessionRecorder.activeSession`，外部共享停止会清理 testRun 且不会误停后续 Session。
- `com.byd.proximitylab.live.LiveMonitorState`：`binding`、`scanState`、`identityStatus/identityDetail`、`diagnostics`、`targetObservationCount`、`droppedTargetEventCount`、`monitor`、`monitorSamples`（环形窗口，不限制 Session 长度）、`test: SimulationTestView?`、`displayDecision`、`simulationRunning`。
- `com.byd.proximitylab.live.SimulationTestView`：`session`、`config`、`decision`、`samples`、`events: List<SessionRunEvent>`、`states: List<SessionRunState>`、`running`、`latestEvent`。`LiveDecisionView` 暴露 state/reason/trend/raw/filtered/lastObservation/阈值/算法；`LiveReadingPoint` 供 07 图表。
- `com.byd.proximitylab.session.SessionConfigSnapshot`、`BuiltInSessionConfigs.BASELINE/EMA/DEFAULT`：06 构造具名配置快照并传给 `startSimulation(snapshot)`；快照含 `snapshotId`、`sourceConfigId`、`ProximityConfig`、`algorithmVersion`、`triggerRuleVersion`、`schemaVersion`。
- `com.byd.proximitylab.session.SessionTimelineProximityRunner`：`apply(SessionTimelineEvent)`、`advanceTo(atEpochMillis)`、`sessionTimeOf(epochMillis)`、`proximityEngine`；05 实时与 09 回放共用同一「时间线→03 判定」映射。
- `com.byd.proximitylab.session.SessionRecorder`：新增 `recordedEvents: SharedFlow<SessionTimelineItem>`（按数据库顺序广播、带背压）；`start` 接受 `SessionStartRequest.configSnapshot` 并写入模拟测试起点扫描/身份快照。`SessionStopResult.Stopped` 新增 `finalScanEvent`，供控制器把最终停扫写入原始运行状态。
- `com.byd.proximitylab.session.SessionStore`：新增 `createSimulationSession(session, snapshot)`、`saveConfigSnapshot(sessionId, snapshot)`、`loadConfigSnapshot(sessionId)`、`observeConfigSnapshot(sessionId)`、`deleteSession(sessionId)`（Room 级联删除时间线/快照/运行结果；08 可复用删除入口）。
- `com.byd.proximitylab.simulation.SimulationRunStore`：`observeEvents/loadEvents`、`observeStates/loadStates`、`appendEvent`、`appendState`、`updateEventFeedback`；模型 `SessionRunEvent`（稳定 `eventId`、事件事实、`feedbackStatus/feedbackDetail/feedbackUpdatedAtEpochMillis`）与 `SessionRunState`；`SimulatedEventFeedbackStatus` 供 11。
- `com.byd.proximitylab.data.ProximityLabDatabase` version 3、`simulationRunDao()`、`MIGRATION_2_3`；导出 `app/schemas/com.byd.proximitylab.data.ProximityLabDatabase/3.json`。`AppContainer.simulationRunStore`、`AppContainer.liveMonitorController`。
- `com.byd.proximitylab.ui.monitor.MonitorViewModel`（可注入 controller）与 `MonitorScreen(viewModel=...)`；测试 tag：`monitor_state`、`monitor_reason`、`monitor_test_state`、`monitor_event_count`、`monitor_latest_event`、`monitor_start_test`、`monitor_stop_test`；`AppViewModelProvider.monitorFactory(controller)`。
- `SamplingType.SIMULATION`、`SessionStartRequest.configSnapshot` 供 06/08 复用；`SessionRunEvent`/`SessionRunState` 的 `atSessionMillis` 与 04 时间线共用会话时基。

### 未测项

- 未在真实 Android 12 真机或真实车辆 BLE 广播上运行；connected 使用 API 37 模拟器，02 的真实广告者字段用例本轮 1 skipped。
- 未执行真实 `adb shell am force-stop`/进程 kill；中断恢复通过取消协程作用域、关闭 Room 连接、重建控制器并调用 `recoverInterruptedSessions()` 模拟。
- 未实现/验证 06 的运行中具名配置编辑；本票只提供 `startSimulation(configSnapshot)` 接入点，并用自定义 EMA/阈值快照覆盖行为与持久化。
- 未实现/运行 09 回放；`SessionTimelineProximityRunner` 已用固定时间线做 JVM 测试，但回放服务、版本差异和「回放不派发反馈」的完整链路留给 09/12。
- 未验证 11 的通知权限、通知提交与锁屏表现；本票仅提供事件反馈字段和 `updateEventFeedback` 入口，不发送 Android 通知或震动。
- 未运行 release 构建、未做手工 UI 走查和 Android 12–17 多设备矩阵。

## Comments

- claimed at HEAD db436b099a805a902e8c1017774f661bfee9d559
