# 07 实现实时监控、模拟测试入口与事件历史

Type: task
Status: resolved
Blocked by: 05
User stories: 7, 8, 9, 10, 11, 12, 25, 26, 28, 30, 31

## 任务

从实时监控页面展示真实判定结果，提供开始和结束模拟测试的入口，并提供后续页面复用的曲线展示能力。

**交付物** 实时监控页、模拟测试操作和事件历史，以及供 08、10 复用的只读图表。

## 工作内容

1. 订阅 05 的实时数据，呈现当前绑定目标、算法与主要参数、原始/滤波 RSSI、接近状态、运动趋势、最后观测、观测间隔与样本数。分别展示扫描不可用、没有目标样本、过期和已确认 `FAR`。出现身份冲突时可返回设备入口处理。
2. 用轻量 Compose 图表或项目内已有组件画原始与滤波曲线、进出阈值、状态和事件标记。支持近期时间窗切换，可采用规格基线提出的 30 秒、1 分钟和 5 分钟。在空窗处断线，时间窗切换只影响展示。
3. 沿用 05 已实现的开始、结束和事件入口。未绑定或当前已有会话时给出提示。明确显示「模拟」、本次配置、事件时刻、RSSI 和原因。人工参考标记的存储和录入在 08 一起完成。停留在 `NEAR` 时旧事件只显示一次。
4. 事件历史显示时间、目标、算法及配置版本、RSSI、原因、App 所知通知结果。事件已产生但通知被系统禁止、尚未尝试或失败时，仍展示事件事实。11 实现通知实际提交和锁屏呈现验证。
5. 让图表可使用只读时间线数据，不依赖实时扫描或通知，供 08 的 Session 详情及 10 的回放对比复用。
6. ViewModel 公开供 Compose 生命周期感知订阅的展示状态。展示限频、重组和窗口裁剪只作用于图表。Session 记录不从 UI 状态流取样。

## 界面状态对照

| 用户看到的状态 | 展示和操作 |
| --- | --- |
| 无绑定 | 标明未选目标，提供设备页入口 |
| 扫描不可用 | 保留不可用原因，例如蓝牙关闭或权限缺失，图表显示已有记录与空窗 |
| 扫描已开始但无新目标样本，或旧样本已过期 | 显示最近观测时刻或尚无观测，状态显示 `UNKNOWN` |
| 已有效确认 `FAR` | 显示 `FAR`、有效 RSSI 和双阈值，与空窗明确区分 |
| 产生模拟事件但通知未提交或不可用 | 历史仍显示该事件和判定原因，通知状态单独显示 |

## 验收与交接

- 从 App 导航进入实时监控，确认无数据、停扫/蓝牙关闭、有效 `FAR`、过期等界面能区分。验证切换时间窗后阈值和标记仍与正确时刻对齐。
- 通过可控输入先进入 `FAR` 后进入 `NEAR`，确认模拟测试出现一条事件并能在历史查看。重新进入页面不产生新事件。普通监控不显示模拟测试通知。
- [ ] 自动化覆盖窗口切换、无数据与过期、时序标记对齐。向采集入口注入密集样本，验证图表减少展示点后 Room 记录仍含全部原始有效观测。
- Answer 标明主要 UI 入口与复用图表的输入，附实际运行的结果或待验证项。

## Answer

### 完成内容

- 实时监控页由 `MonitorScreen`/`MonitorViewModel` 订阅 05 的 `LiveMonitorController` 展示状态：当前绑定目标、算法与主要参数、原始/滤波 RSSI、接近状态、运动趋势、最后观测、观测间隔与样本数，以及双阈值；`MonitorScreen` 新增 `onOpenDevice` 回调，`ProximityLabApp` 将其接入设备页导航。
- 图表契约：新增 `RssiChartBuilder`/`RssiChartModels`（`chart`）与 `RssiChart`（`ui/chart`），支持多条曲线、阈值线、状态/事件/参考 marker、空窗断线、每曲线抽点与 xFraction/yFraction 对齐；时间窗为 30s/60s/300s，切换只影响展示；抽点不修改输入数据，Room 记录仍保留全部原始有效观测。
- 实时映射与状态口径：`MonitorChartSource` 把 `LiveMonitorState` 映射为实时图表，运行中的模拟测试展示测试曲线与事件 marker，普通监控不产生事件 marker；`MonitorStatusFormatter` 统一无绑定、扫描未开始/已停止/不可用、身份不确定、无样本、过期、`FAR`/`NEAR` 的区分口径，UI 与测试共用。
- 事件历史：`SimulationEventHistoryReader.observeLatest` 从 Room 的 Session、配置快照和原始运行事件读取最近一条模拟测试历史，展示时间、目标、配置名/算法/版本、RSSI、原因，并把事件事实与 App 已知通知结果（尚未尝试/已提交/不可用/失败）分开显示。
- 只读复用：`SessionChartInput.fromTimeline` 把 04 已保存的只读时间线、运行状态和模拟事件映射为图表输入，不依赖实时扫描或通知，供 08 的 Session 详情和 10 的回放对比复用。
- 状态模型扩展：`LiveMonitorState` 新增 `activeSession`、`displaySamples`、`displayLastObservationAtEpochMillis`、`observationIntervalMillis`；`LiveDecisionView` 新增 `configName`、`sampleValidityMillis`、`confirmation`、`emaAlpha`，下游 08/11 可直接读取。
- 生产接线：`AppViewModelProvider.monitorFactory(controller, configStore, sessionStore, simulationStore)`。

### 实际验证

实施与脚本 gate 记录（由实施 Agent 和脚本实际执行，本记录轮未重跑）：

- `JAVA_HOME="D:/data/workspace/di-tool/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL，exit 0；22 个测试类共 143 tests，0 failures、0 errors、0 skipped。
- `JAVA_HOME="D:/data/workspace/di-tool/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain` → BUILD SUCCESSFUL，exit 0；`TEST-Medium_Phone_API_37.0(AVD) - 17.xml` 为 54 tests、0 failures、0 errors、1 skipped（`AndroidBleScanSourceTest` 真实广告者用例按 Assume 跳过）。其中 `MonitorChartHistoryTest` 5 个新用例全部通过，`MonitorScreenTest` 4/4、`MonitorProductionWiringTest` 2/2 通过。
- 中间一次全量 connected 出现既有 04 `SamplingScreenTest.interruptedSessionAfterProcessDeathKeepsWrittenSamples` 的 merged-semantics flake（`useUnmergedNode` 提示，未触及 SamplingScreen 代码）；单独重跑 `com.byd.proximitylab.ui.sampling.SamplingScreenTest` → BUILD SUCCESSFUL（2 tests），随后重跑全量 connected → BUILD SUCCESSFUL（54 tests，0 failures）。
- `JAVA_HOME="D:/data/workspace/di-tool/.tooling/jdk-21" ./gradlew :app:assembleDebug :app:lintDebug --console=plain` → BUILD SUCCESSFUL，exit 0；`app/build/reports/lint-results-debug.txt` 为 0 errors、3 warnings（`OldTargetApi`、`ObsoleteSdkInt`、`MonochromeLauncherIcon`，均为 01 基线）。
- `"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk` → compileSdk 37、minSdk 31、targetSdk 36；permissions 仅有 `BLUETOOTH_SCAN`（neverForLocation）与 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无 `INTERNET`、`BLUETOOTH_CONNECT` 或定位权限。
- `git diff --check` → exit 0（仅有 Git 的 LF→CRLF 提示，无空白错误）。
- 聚焦验证：`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.byd.proximitylab.ui.monitor.MonitorChartHistoryTest` → BUILD SUCCESSFUL（5 tests，0 failures）；`:app:testDebugUnitTest --tests 'com.byd.proximitylab.chart.*' --tests 'com.byd.proximitylab.simulation.*' --tests 'com.byd.proximitylab.ui.monitor.*'` → BUILD SUCCESSFUL。
- 脚本 gate：`:app:assembleDebug :app:testDebugUnitTest` exit 0；`:app:connectedDebugAndroidTest` exit 0；APK 检查 min/target/compile = true/true/true，INTERNET=false。

独立复审（由复审 Agent 实际运行）结论：

- 通过：从 App 导航进入实时监控并可返回设备页。证据：`MonitorProductionWiringTest.monitorDeviceEntryNavigatesToDevicePage` 在 connected XML 中通过；既有 `ProximityLabNavigationTest` 2 项通过。
- 通过：无绑定、扫描不可用（蓝牙关闭/停扫/权限）、无样本、已确认 `FAR`、过期可区分。证据：`MonitorChartHistoryTest.distinguishesNoBindingScanUnavailableNoSamplesFarAndExpired` 通过；`MonitorStatusFormatterTest` 5 项通过。
- 通过：时间窗切换只影响展示，阈值和标记仍与正确时刻对齐。证据：`RssiChartBuilderTest` 9 项、`MonitorChartSourceTest` 4 项通过；`MonitorChartHistoryTest.denseSamplesAreDecimatedInChartWhileRoomKeepsAllAndWindowSwitchOnlyDisplay` 断言 30s/300s 切换后 Room 仍为 160 条 Observation。
- 通过：可控 `FAR`→`NEAR` 产生一条模拟事件并可在历史查看，重新进入页面不补发。证据：`MonitorChartHistoryTest.simulationEventHistoryPersistsAcrossReopenAndNotificationStatusStaysSeparate` 通过；Room 事件数与 `eventHistory` 条数均断言为 1。
- 通过：普通监控不产生模拟历史或事件标记。证据：`MonitorChartHistoryTest.normalMonitorDoesNotCreateSimulationHistoryOrEventMarkers` 通过；`monitor_chart_summary` 断言标记 0，`monitor_test` 不存在。
- 通过：事件历史显示时间、目标、配置名/算法/版本、RSSI、原因和通知结果，事件事实与通知结果分开。证据：`MonitorScreen.kt:440-461`；`MonitorChartHistoryTest` 断言 reason、-60、目标名、算法/规则版本、尚未尝试；`SimulationEventHistoryReaderTest` 3 项通过。
- 通过：图表抽点不改 Room 原始记录。证据：`MonitorChartHistoryTest.denseSamplesAreDecimatedInChartWhileRoomKeepsAllAndWindowSwitchOnlyDisplay` 断言 Room 160 条 Observation、图表 `displayedPointCount < source`；`RssiChartBuilderTest.denseSamplesAreDecimatedButSourceCountIsKept` 通过。
- 通过：只读时间线图表输入可供 08/10 复用。证据：`SessionChartInput.fromTimeline` + `RssiChart`；`SessionChartInputTest` 1 项通过。
- 通过：仓库约定。证据：AndroidManifest 仅 `BLUETOOTH_SCAN`(neverForLocation)、无 `INTERNET`；`git diff` 无 gradle/manifest 变更；`libs.versions.toml` 为固定版本；`:app:lintDebug` BUILD SUCCESSFUL，0 errors/3 baseline warnings；`git diff --check 3169270...` exit 0。
- 复审提醒（low）：复审本轮未复跑全量 gate，脚本 gate 结果按实施记录采用；复审本轮实际重跑了新增 22 个单元测试（BUILD SUCCESSFUL，22 tests/0 failures），connected 结果来自既有 XML（54 tests、0 failures、1 skipped，跳过为既有真实 BLE 广告者 Assume）。
- 收尾项（不阻塞）：复审时票据 Status 仍为 claimed、`## Answer` 未写、自动化复选框未勾选、`map.md` 未更新；按约定由票据管理员在复审通过后写入。本记录轮已完成 Status、Answer 与 map.md；自动化复选框与 05、06 已 resolved 票据保持一致，保持未勾选。

### 公开入口与下游

- `com.byd.proximitylab.ui.monitor.MonitorViewModel`（`app/src/main/java/com/byd/proximitylab/ui/monitor/MonitorViewModel.kt:42/52/91`）：`chartState`、`eventHistory`、`window`、`selectWindow(RssiChartWindow)`；生产接线经 `AppViewModelProvider.monitorFactory(controller, configStore, sessionStore, simulationStore)`。
- `com.byd.proximitylab.ui.monitor.MonitorScreen`（`MonitorScreen.kt:56`）：新增 `onOpenDevice` 回调并由 `ProximityLabApp` 接入导航；关键 testTag：`monitor_status`、`monitor_status_detail`、`monitor_last_observation`、`monitor_observation_interval`、`monitor_sample_count`、`monitor_open_device`、`monitor_chart`、`monitor_chart_summary`、`monitor_chart_canvas`、`monitor_chart_empty`、`monitor_window_30s/60s/300s`、`monitor_history`、`monitor_history_event_<eventId>`、`monitor_history_notification_<eventId>`、`monitor_history_config`、`monitor_history_count`。
- `com.byd.proximitylab.ui.chart.RssiChart`（`app/src/main/java/com/byd/proximitylab/ui/chart/RssiChart.kt:38`）：08/10 复用的只读 Compose 图表；接收 `RssiChartData`，支持多条曲线、阈值线、状态/事件/参考 marker、空窗断线；`testTagPrefix` 可配置。
- `com.byd.proximitylab.chart.RssiChartInput` / `RssiChartWindow` / `RssiChartThreshold` / `RssiChartMarkerInput` / `RssiChartData` / `RssiChartSummary`（`app/src/main/java/com/byd/proximitylab/chart/RssiChartModels.kt`）与 `RssiChartBuilder.build`（`RssiChartBuilder.kt:14`）：窗口裁剪、空窗断线、每曲线抽点、`xFraction`/`yFraction` 对齐；抽点不修改输入数据。
- `com.byd.proximitylab.chart.SessionChartInput.fromTimeline`（`app/src/main/java/com/byd/proximitylab/chart/SessionChartInput.kt:16`）：把 04 已保存的只读时间线、运行状态、模拟事件和 08/10 的参考/滤波曲线映射为图表输入，不依赖实时扫描或通知；08 的 Session 详情和 10 的回放对比可直接复用。
- `com.byd.proximitylab.simulation.SimulationEventHistoryReader.observeLatest`（`app/src/main/java/com/byd/proximitylab/simulation/SimulationEventHistory.kt:93`）及 `SimulationEventHistory`/`SimulationEventHistoryEntry`：从 Room 的 Session、配置快照和原始运行事件读取最近模拟测试历史；11 调用 `updateEventFeedback` 后历史中的通知结果会独立更新。
- `com.byd.proximitylab.ui.monitor.MonitorChartSource.build`（`MonitorChartSource.kt:52`）/ `MonitorChartState` / `MonitorChartMode`：把 `LiveMonitorState` 映射为实时图表；运行中的模拟测试展示测试曲线与事件 marker，普通监控不产生事件 marker。
- `com.byd.proximitylab.ui.monitor.MonitorStatusFormatter.describe`（`MonitorStatusFormatter.kt:36`）/ `MonitorStatusView` / `MonitorHealth`：UI 与测试共用的状态区分口径（无绑定、扫描未开始/已停止/不可用、身份不确定、无样本、过期、`FAR`/`NEAR`）。
- `com.byd.proximitylab.live.LiveMonitorState` 新增 `activeSession`、`displaySamples`、`displayLastObservationAtEpochMillis`（`LiveMonitorModels.kt:129`）及 `observationIntervalMillis`（`LiveMonitorModels.kt:137`）；`LiveDecisionView` 新增 `configName`、`sampleValidityMillis`、`confirmation`、`emaAlpha`，下游 08/11 可直接读取。

### 未测项

- 未在真实 Android 12–17 真机或真实车辆 BLE 广播上运行；connected 使用 API 37 模拟器，真实广告者用例 1 skipped。
- 未实现/验证 11 的 Android 通知提交与锁屏呈现；本票只展示 App 已知的 `feedbackStatus`（尚未尝试/已提交/不可用/失败）并保证事件事实独立可查。
- 未做进程被系统 kill 后冷启动打开监控页查看事件历史的端到端 UI；Room 关闭重开的事件/状态/反馈持久化由既有 `SimulationRoomStoreTest` 覆盖，本票页面重建测试保持同一进程。
- 未验证跨多个模拟 Session 的事件历史汇总；07 事件历史读取最近一条模拟 Session，完整 Session 管理由 08 提供。
- 未运行 release 构建、未做截图/像素级 UI 走查；图表对齐通过纯 Kotlin 几何断言、Compose 语义与 Room 记录验证。
- 复审提醒（low）：全量 gate 未由复审本轮复跑，脚本 gate 结果按实施记录采用，详见「实际验证」。

## Comments

- claimed at HEAD 316927038cafe5a301f143f645e0c6cd486ae0cb
