# BYD Proximity Lab V1 现场交接

本文是 12 的现场交付前提，供 [13 可控 BLE 台架验收](../.scratch/byd-proximity-lab-v1/issues/13-controlled-ble-acceptance.md) 和 [14 目标车与目标手机验收](../.scratch/byd-proximity-lab-v1/issues/14-target-vehicle-acceptance.md) 直接使用。软件侧验证结论见文末“软件验收记录”；现场票独立记录物理设备与车辆结果，不能引用本页代替。

## 1. 版本与产物

| 项 | 值 |
| --- | --- |
| Git 基线 | `6f6122295c982ba956e0466d0d21def26ad131e2`（12 的软件验收在工作树中执行，未提交） |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 包名 / versionCode / versionName | `com.byd.proximitylab` / `1` / `1.0` |
| APK SHA-256（本次构建） | `6754a81b9aff00f82ec7468d76275d840f723b68e7a48b38c726f0d1ff32791a` |
| 构建 JDK | `.tooling/jdk-21`，Eclipse Temurin 21.0.13+9-LTS |
| Gradle / AGP | `9.9.1` / `9.5.1` |
| Kotlin / Compose Compiler | `2.4.21` / `2.4.21` |
| KSP | `2.3.14`（KSP2） |
| SDK | `compileSdk = 37`、`targetSdk = 36`、`minSdk = 31` |

版本核对命令：

```bash
JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:buildEnvironment --console=plain
"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk
"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump permissions app/build/outputs/apk/debug/app-debug.apk
```

构建命令：

```bash
JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:assembleDebug --console=plain
```

## 2. 安装与启动

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" devices -l
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <serial> shell am start -W -n com.byd.proximitylab/.MainActivity
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <serial> shell dumpsys package com.byd.proximitylab | rg "versionName|versionCode"
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <serial> shell getprop ro.build.version.sdk
```

确认版本时以 `dumpsys package` 的 `versionName=1.0`、`versionCode=1` 为准；SHA-256 只匹配本页记录的本次构建，重新构建后需要重新记录。

### 权限与只读约束

- Manifest 只申请 `BLUETOOTH_SCAN`（`neverForLocation`）、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`POST_NOTIFICATIONS`、`VIBRATE`，没有 `INTERNET`、`BLUETOOTH_CONNECT` 或定位权限。
- Android 12（API 31）：在设备页首次开始扫描时授予“附近的设备”权限。
- Android 13+：在监控页开始模拟测试时另行授予通知权限；拒绝通知不会阻止测试开始，也不会删除已记录事件。
- `POST_NOTIFICATIONS` 被拒绝或通知渠道关闭时，事件仍写入 Room，反馈列显示“通知不可用”；锁屏是否实际呈现由现场观察记录。
- 采样和模拟测试由用户在可见页面主动开始；`connectedDevice` 前台服务只承接用户已启动的 Session。App 不配对、不连接、不执行 GATT，也不发送车辆指令。

## 3. 初始状态与准备

每次现场验收开始前记录：

1. 手机型号、Android 版本、ROM/构建号、App versionName/versionCode 与 APK SHA-256。
2. 车辆车型/年款/配置；是否锁车、静置时长、钥匙在场/离场状态。
3. 场地、天气/遮挡、手机放置方式（手持/口袋/背包）、相对车辆的位置与朝向。
4. App 内当前生效的算法配置名称与参数（算法、进入/离开阈值、确认方式与数值、样本有效期、EMA 系数）。
5. 可控 BLE 设备（仅 13）的标识、广播参数、功率或距离设置。

建议清空旧数据后复验：

```bash
adb -s <serial> shell pm clear com.byd.proximitylab
```

清空后重新授予权限。随后从设备页开始扫描、确认候选可见并人工绑定目标，记录目标标识与绑定地址。13 使用可控广播设备；14 使用目标车广播或现场可见对照。

## 4. 13 可控 BLE 台架前提

设备与准备按 13 票执行。建议按以下顺序在 App 内操作并记录：

| 场景 | App 内操作 | 需要记录 |
| --- | --- | --- |
| 发现与采样 | 设备页扫描 → 人工绑定 → 采样页保存定点/行走 Session | Session 名称/标识、采样类型、位置、放置方式、估计距离、原始样本数、曲线与时间线 |
| 基本触发 | 监控页开始模拟测试 → 可控设备先发弱信号确认 `FAR` → 走到/衰减到 `NEAR` 并停留 | 当次配置名称与参数、首次 `FAR` 时刻、事件时刻、RSSI、原因、事件数、反馈状态 |
| 折返与近端启动 | 同一次测试退回 `FAR` 后折返；另开一次近端测试 | 折返是否再次触发；近端开始是否 0 事件 |
| 中断与恢复 | 测试中切换蓝牙或让目标消失至样本过期，再在近端恢复 | `UNKNOWN` 原因、恢复首批强样本是否补触发、恢复后 `FAR → NEAR` 事件数 |
| 锁屏继续 | 先开始测试，锁屏并放入口袋，再用可控设备建立 `FAR → NEAR` | 锁屏起止时间、样本覆盖、事件是否产生、通知是否实际呈现、最大空窗 |
| 比较与评分 | 回放正样本、明确无需触发样本和未标记样本 | Session 标识、人工标记、评分结论（提前/按时/迟到/漏触发/误触发/观测不足/跳过）、配置与版本 |

台架判定只看实验工具与所用测试设备，不代表目标车型结论。通知“已提交系统通知”与“锁屏实际呈现”分列；没有事件时通知列写“不适用”。

## 5. 14 目标车与目标手机前提

按 14 票执行，现场至少记录：

- 车辆：车型/年款/配置、锁车与静置阶段、醒车对照、相邻同款车情况。
- 手机：型号、Android/ROM、权限状态、电源/后台限制、放置方式。
- App：版本、算法/触发规则版本、配置快照、Session 标识。
- 采集：目标是否可见、能否唯一关联、地址/载荷变化、观测间隔、最长可观察空窗。
- 判定：是否在 `NEAR` 前取得可信 `FAR`、事件数量与时点、`UNKNOWN` 原因、近端开始和停扫恢复是否零补触发。
- 通知：事件是否写入、App 反馈状态、锁屏是否实际呈现；两列独立记录。

判读顺序遵循 14 票：先确认对照扫描链，再确认本车关联，再检查 `FAR` 证据，最后分别核对事件与通知。无法稳定识别本车、休眠后缺少 `FAR` 或采集不连续时，写清现象与 Session，结论保留“不通过”或“待测”，不推定准确率、延迟或耗电上限。

## 6. 两类验收独立记录

13/14 各自使用独立 Session，不要把同一条 Session 同时作为台架和目标车结论。软件侧已自动验证的是：

- 事件是否由真实判定与存储路径产生，以及通知平台边界返回的结果。
- 回放与对比是否不触发实时通知、不改写历史事件。

现场才验证的是：

- 真实 BLE 广播在 `neverForLocation` 下是否可见、能否唯一关联。
- 锁屏时样本是否连续、通知是否实际呈现、系统是否终止或恢复会话。
- 车辆休眠、地址轮换、相邻车辆与不同接近方向的实际表现。

## 7. 软件验收记录（12 执行）

详细命令、测试类和结果见 12 的 Answer；本节只列现场可复用的结论。

| 场景 | 软件结果 | 自动化证据 |
| --- | --- | --- |
| 主路径：人工绑定 → 行走采样 → 模拟测试 → Session 详情 → 回放/对比 | 通过（真实 `MainActivity` + Compose Navigation + 页面点击；仅 BLE/通知/前台服务为可控替身） | `SoftwareVerificationNavigationTest.navigatesFromDeviceBindingThroughSamplingSimulationDetailReplayAndComparison` |
| 服务入口串联（补充） | 通过（生产 AppContainer/ViewModel/Room/共享判定，便于定位失败层） | `SoftwareVerificationFlowTest.mainPathBindsSamplesSimulatesAndComparesThroughProductionEntries` |
| 冷启动近端与同一帧重复投递 | 通过：近端开始 0 事件；重复帧不累积证据 | `SoftwareVerificationBoundaryTest.duplicateFramesDoNotAccumulateAndNearOnlyStartDoesNotTrigger` |
| 样本过期、停扫恢复、恢复后 FAR → NEAR | 通过：过期/停扫回 `UNKNOWN`，恢复不补触发；可信 FAR 后只产生一次事件 | `SoftwareVerificationBoundaryTest.expiryAndScanRestartNeverReuseOldFarButFreshFarThenNearTriggersOnce` |
| 身份不确定 | 通过：新地址无唯一证据时观测不入判定，原地址恢复后正常触发 | `SoftwareVerificationBoundaryTest.identityUncertaintySuppressesObservationsUntilOriginalAddressReturns` |
| 配置快照、编辑/删除原配置、原样回放、对比评分 | 通过：快照不变；同版本重现两条事件；严格配置显示漏触发；回放/对比不触发通知 | `SoftwareVerificationFlowTest`、`SessionReplayRoomTest`、`SessionComparisonRoomTest` |
| 中断覆盖不足 | 通过：重启恢复为 `INTERRUPTED`，详情可查；评分记观测不足而非漏触发 | `SoftwareVerificationBoundaryTest.interruptedSessionIsRecoveredOnNextStartupAndCannotBeScoredAsMissed` |
| 通知不可用 | 通过：事件仍入库，反馈写回不可用；回放不产生实时通知 | `SoftwareVerificationBoundaryTest.unavailableNotificationKeepsEventAndReplayNeverReachesFeedback` |
| 通知正常提交 | 通过：已授予权限时系统通知带稳定事件标签提交 | `AndroidSimulationAlarmTest` |
| 前台服务与权限 Manifest | 通过：`connectedDevice` 服务类型与最少权限 | `ProximityLabForegroundServiceTest` |

本次实际执行结果：

- `:app:testDebugUnitTest --rerun-tasks`：197 tests、0 failures、0 errors。
- `:app:lintDebug`：0 errors、3 warnings（既有 OldTargetApi / ObsoleteSdkInt / MonochromeLauncherIcon）。
- `:app:connectedDebugAndroidTest`：92 tests、0 failures、0 errors、1 skipped（既有真实 BLE 广告者用例无广告者时按 Assume 跳过），覆盖 Room、Compose 页面、真实 MainActivity 导航主路径和生产接线。
- 安装与启动：`adb install -r`/全新安装均成功；在全新启动的 API 37 模拟器实例上冷启动 `Status: ok`、`LaunchState: COLD`、`TotalTime: 1810ms`、`MainActivity` 为 topResumed；截图见 `build/verification/launch.png`（本地构建产物，不入库）。此前同一 AVD 的旧运行实例曾因长时间运行退化而出现 `Status: timeout`（Activity 仍 topResumed），新建模拟器实例后不再复现。

复验时如果既有 Compose 页面测试偶发 `performMeasureAndLayout called during measure layout` 进程崩溃（10 已记录），直接重跑；本票最终全量运行通过。

软件侧未覆盖、留给现场或后续环境：

- Android 12（API 31）和 Android 13（API 33）安装/运行未执行；本机只有 API 37 模拟器（`Medium_Phone_API_37.0`，`sdk_gphone64_x86_64`）。
- 真实 BLE 广告者的 fields 用例在无广告者时按 Assume 跳过；真实锁屏收包与通知呈现未执行。
- 通知呈现只在 instrumentation 中检查 `NotificationManager.activeNotifications`，没有人工目视或锁屏截图。
- 未执行 release 构建、release Lint 或签名交付流程。

## 8. 复验命令

```bash
# JVM 单元测试、debug 构建与 Lint
JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug --console=plain

# 全量 Room/Compose/instrumentation（需要已启动模拟器或真机）
JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain

# 仅 12 的端到端与反例
JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.byd.proximitylab.verification.SoftwareVerificationNavigationTest,com.byd.proximitylab.verification.SoftwareVerificationFlowTest,com.byd.proximitylab.verification.SoftwareVerificationBoundaryTest \
  --console=plain
```

## 9. 现场记录模板

```text
日期/场地：
手机型号 / Android / ROM / 电源与后台设置：
车辆车型 / 锁车与静置阶段 / 钥匙状态：
App versionName / versionCode / APK SHA-256：
算法配置名称 / 参数 / 快照标识：
可控 BLE 设备与广播参数（13）：
Session 名称与标识 / 采样类型：
人工参考标记与整次标签：
原始样本数 / 观测间隔 / 最大空窗：
FAR 证据（是否取得 / 时刻 / RSSI）：
事件（数量 / 时刻 / RSSI / 原因 / 版本）：
App 反馈（已提交 / 通知不可用 / 失败）：
锁屏通知实际呈现（是 / 否 / 未观察）：
锁屏起止时间：
结论（通过 / 失败 / 待测）与证据路径：
复现步骤：
```
