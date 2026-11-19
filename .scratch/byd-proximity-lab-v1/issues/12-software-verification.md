# 12 完成软件端到端验证和现场交接

Type: task
Status: resolved
Blocked by: 10, 11
User stories: 1–37

## 任务

确认各票从正常 App 入口连成可运行的 V1。产出可安装构建、已执行的软件验收记录和两张现场票可直接使用的操作前提。

**交付物** 可安装 APK、覆盖软件关键路径的执行记录，以及照着做就能复验的现场交接信息。

## 工作内容

1. 按 01 入口构建可交付的 debug APK，执行 JVM、Room/Android instrumentation、Lint 和适用的 Compose 验证。核对 Version Catalog、有效 Kotlin/Compose Compiler/KSP、实际 SDK 及合并 Manifest。记录 Git 版本、命令、环境和产物路径。
2. 从正常 App 导航串一次发现并绑定、定点或行走采样、实时模拟测试、事件历史、算法管理、原样回放、不同配置对比和带标记评分。平台边界可用可控的 BLE 数据或扫描替身，使用实际存储和判定路径。标明使用模拟器、测试输入还是真机，分别记录证据。
3. 选择贯穿全链路的几个反例验证边界。建议包括近端冷启动零触发、同一帧重复投递、身份冲突、停扫过期后首批强信号、恢复后的可信 `FAR → NEAR`、测试期间配置不变、通知禁止仍有本地事件、历史回放无实时通知。优先复用前置票的用例和夹具。
4. 确认 App 权限与只读约束，核对两类验收判定可独立记录。整理现场要用的设备、APK、配置、初始状态、对照试验方式和记录字段。13 执行通用工具验收，14 执行目标车型验收。
5. 在 Android 12（API 31）设备或模拟器验证安装和启动。补充 API 33 通知、API 34+ 前台服务与 API 36/37 运行行为。同类行为可按代表版本验证，缺设备时记录未测版本和场景。

## 验收矩阵

| 场景 | 入口和输入 | 应保留的通过证据 |
| --- | --- | --- |
| 软件主路径 | 设备页人工绑定 → 定点或行走采样 → 监控页模拟测试 → Session 详情 | 能查询原始时间线、标记、配置快照及事件，并标明扫描输入来自哪里 |
| 冷启动与恢复 | 先在普通监控确认 `FAR`，新测试只收到近端帧。另以 [03 的 P4、P5](03-proximity-engine.md#判定用例) 重走过期和失联 | 近端开局及恢复均无补触发。随后确认 `FAR → NEAR` 只产生一次事件 |
| 配置与回放 | 保存测试后，编辑或删除当次的具名配置。以原快照和另一配置比较 | 原事件保留，同版本以原快照可复现，新配置差异可查，回放无反馈 |
| 评分与中断 | 用 10 的固定时间窗验证提前、按时、遗漏与额外事件。另验证中断覆盖不足 | 结果符合 [评分判定表](10-comparison-scoring.md#评分判定表)，中断记录仍可查 |
| Android 外部行为 | 用可用的 Android 环境观察正常通知、无通知权限、可从后台恢复或确认已终止的会话 | 并列记录事件生成、App 感知的通知结果、是否现场呈现及待测项。锁屏和车型结论留给现场证据 |

记录可以按「场景｜输入来源／环境｜预期｜实际｜通过／失败／待测｜证据」填写。构建成功后仍需列出各软件流程的实际结果。

## 验收与交接

- Answer 逐项记录命令、覆盖的实际入口、结果和未测项。确有软件缺陷时定位负责票，按最小范围修复或记录阻塞。设备不在场时，可执行的软件入口照常验证，留出现场未测项。
- 复核 [规格](../spec.md) 中的 37 条用户故事均有票据及验收归属。可控 BLE 台架和目标车结果留给相应现场票。
- 给出可复现的安装与运行方式，现场人员能确认 App 版本并走完两类独立验收。

## Answer

### 完成内容

- 新增 `SoftwareVerificationHarness` 与三个 instrumentation 验收类：`SoftwareVerificationNavigationTest`（真实 `MainActivity` + Compose Navigation + 页面点击）、`SoftwareVerificationFlowTest`（生产 `AppContainer`/ViewModel/Room/共享判定）、`SoftwareVerificationBoundaryTest`（5 条反例与恢复路径）。夹具只替代 BLE 系统回调、通知/前台服务边界与独立数据库名。
- 主路径串联：设备页扫描与人工绑定 → 算法实验室新建并选择配置 → 行走采样与人工标记 → 监控页模拟测试与 `FAR → NEAR` 事件 → Session 详情（快照/原始时间线/标记/事件）→ 原样回放 → 另一套配置对比与评分。
- 反例覆盖：近端冷启动 0 事件与同一扫描帧重复投递不累积证据；样本过期和停扫恢复回到 `UNKNOWN` 且不补触发，恢复后可信 `FAR → NEAR` 只产生一次事件；身份不确定时观测不入判定；中断 Session 启动恢复为 `INTERRUPTED` 且评分记观测不足；通知不可用仍写事件并标反馈不可用；回放与对比不触发实时通知、不改写历史。
- 修复 `TargetMonitor` 绑定竞态（负责票 04）：`observeBinding()` 的首次空发射若在保存后送达会覆盖新绑定；改为每次发射后以 `bindingStore.loadBinding()` 的当前存储值为准（公开 API 不变）。新增 `TargetMonitorTest.delayedInitialNullEmissionDoesNotClearBindingSavedAfterMonitorStart` 回归用例。
- 新增测试接缝 `ProximityLabApplication.setContainerForTest(AppContainer?)`：仅供 instrumentation 在 `MainActivity` 启动前注入只替代 BLE/通知/前台服务边界的容器，传入 `null` 恢复生产容器；生产 `container` 仍按需创建唯一默认实例，公开类型不变。
- 新增 `docs/field-handover-v1.md`：版本/APK/SHA-256/SDK、安装启动命令、权限与只读约束、初始状态、13/14 场景与判读、两类验收独立记录、复验命令、未测项与记录模板。
- 产出 debug APK `app/build/outputs/apk/debug/app-debug.apk`（`com.byd.proximitylab`，versionCode 1 / versionName 1.0，SHA-256 `6754a81b9aff00f82ec7468d76275d840f723b68e7a48b38c726f0d1ff32791a`）。

### 实际验证

下文结果由实施、脚本 gate 与独立复审执行；本次记录轮只复跑标注「本次记录轮复核」的两项。

- `JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain` → BUILD SUCCESSFUL in 4m45s；`TEST-Medium_Phone_API_37.0(AVD) - 17.xml` 为 92 tests、0 failures、0 errors、1 skipped（既有真实 BLE 广告者用例按 Assume 跳过）；三个 verification 类全部 0 failures。
- `JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug --console=plain` → BUILD SUCCESSFUL in 3m10s；JVM 197 tests、0 failures、0 errors、0 skipped；lint 0 errors、3 warnings（OldTargetApi / ObsoleteSdkInt / MonochromeLauncherIcon）。
- 聚焦主路径：`... --class=com.byd.proximitylab.verification.SoftwareVerificationNavigationTest` → BUILD SUCCESSFUL in 57s；1 test、0 failures。
- 聚焦竞态回归：`:app:testDebugUnitTest --tests "com.byd.proximitylab.target.TargetMonitorTest.delayedInitialNullEmissionDoesNotClearBindingSavedAfterMonitorStart"` → 1 test、0 failures。
- 复审窄跑三个 verification 类：7 tests、0 failures（navigation 43.648s、flow 3.365s、boundary 5 tests 11.271s）。
- `:app:buildEnvironment` → Daemon JVM 为 Eclipse Temurin 21.0.13+9-LTS（`.tooling/jdk-21`）；Gradle 9.9.1、AGP 9.5.1、Kotlin/Compose Compiler 2.4.21、KSP 2.3.14（未发现 alpha/beta/rc/动态版本）。
- 安装/启动（实施与 gate）：`adb install app/build/outputs/apk/debug/app-debug.apk` → Success；`am start -W -n com.byd.proximitylab/.MainActivity` → Status: ok、LaunchState: COLD、TotalTime 1810ms；`ro.build.version.sdk=37`，`dumpsys package` 为 versionCode=1 / versionName=1.0 / minSdk=31 / targetSdk=36。
- `aapt2 dump badging`/`dump permissions` 与合并 Manifest：compileSdk 37 / minSdk 31 / targetSdk 36；权限仅 `BLUETOOTH_SCAN`(neverForLocation)、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`POST_NOTIFICATIONS`、`VIBRATE` 等，无 `INTERNET`/`BLUETOOTH_CONNECT`/定位；`ProximityLabForegroundService` 为 exported=false、foregroundServiceType=connectedDevice。
- 本次记录轮复核：`sha256sum app/build/outputs/apk/debug/app-debug.apk` → `6754a81b9aff00f82ec7468d76275d840f723b68e7a48b38c726f0d1ff32791a`（与交接文档一致）；`git diff --check 6f6122295c982ba956e0466d0d21def26ad131e2` → exit 0。
- 用户故事：脚本读取 01–14 的 `User stories` 元数据，1–37 均有至少一张票据归属，missing=[]。
- 已知偶发：既有 Compose 页面测试可能出现 `performMeasureAndLayout called during measure layout` 进程崩溃（10 已记录），重跑后本次全量通过（见 `docs/field-handover-v1.md` §7）。

### 公开入口与下游

- `ProximityLabApplication.setContainerForTest(container: AppContainer?)`：instrumentation 专用注入接缝；生产容器仍按需创建唯一默认实例。
- `TargetMonitor` 绑定同步以 `bindingStore.loadBinding()` 为权威值（公开 API 不变），负责票 04。
- 验收用例：`SoftwareVerificationNavigationTest.navigatesFromDeviceBindingThroughSamplingSimulationDetailReplayAndComparison`、`SoftwareVerificationFlowTest.mainPathBindsSamplesSimulatesAndComparesThroughProductionEntries`、`SoftwareVerificationBoundaryTest`（5 条反例），以及 `SoftwareVerificationHarness`。
- 现场交接：`docs/field-handover-v1.md`，供 13/14 使用安装与版本核对、初始状态、场景步骤、记录字段与复验命令。
- 可安装产物：`app/build/outputs/apk/debug/app-debug.apk`（SHA-256 `6754a81b9aff00f82ec7468d76275d840f723b68e7a48b38c726f0d1ff32791a`）。

### 未测项

- Android 12（API 31）与 Android 13（API 33）安装/启动未执行：本机只有 API 37 模拟器（`Medium_Phone_API_37.0`）；API 37 代表覆盖 API 33+ 通知权限与 API 34+ connectedDevice 前台服务路径，实际 API 36 设备未测。
- 真实 BLE 广告者、真实锁屏收包与锁屏通知实际呈现未执行；`AndroidBleScanSourceTest` 真实广告者用例按 Assume 跳过，通知呈现仅在 instrumentation 中检查 `NotificationManager.activeNotifications`。
- API 33+ 真机撤销 `POST_NOTIFICATIONS` 导致进程终止的场景未执行；通知不可用分支由 `RecordingSimulationAlarm` 受控能力与既有测试覆盖。
- 未执行 release 构建、release Lint 或签名交付；仅执行 debug assemble/lint/connected。
- 未在 13 可控 BLE 台架或 14 目标车/目标手机现场验收；车辆休眠、地址轮换、相邻车辆、锁屏连续性与耗电表现待现场记录。
- 未在真实 Android 12/13 设备上执行 MainActivity 导航主路径；导航验证在 API 37 模拟器完成。
- 复审遗留（low）：EARLY/LATE 评分由 10 的 `SessionScoringTest` 覆盖（gate 全量 JVM 197 tests 内），但未在 12 的新用例与现场交接表单中单独列出；不影响通过结论。

## Comments

- claimed at HEAD 6f6122295c982ba956e0466d0d21def26ad131e2
