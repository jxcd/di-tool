# 04 从定点采样保存并重开首个 Session

Type: task
Status: resolved
Blocked by: 02, 03
User stories: 13, 14, 18, 21, 32, 37

## 任务

完成首个真实采样闭环。用户在采样页选择定点条件并录制绑定目标，停止后在列表和简单详情中看到已写入的 Session。使用 [技术基线](../android-toolchain.md) 中的 Room / SQLite 和 KSP2，保持纯 Kotlin 判定无 Room 依赖。

**交付物** 定点采样、可重新打开的本地 Session、共用的采集生命周期和 Room 时间线存储。

## 工作内容

1. Session 记录稳定 ID、绑定目标、采样类型、名称、开始/结束与正常结束/中断状态，以及位置、可选估计距离和手机放置方式。
2. 时间线保存原始 RSSI、观测与收到时刻、扫描和身份状态变化。保持 Session 内时间顺序、同一时刻的先后关系和可用的结束时间，供回放重现空窗和过期。统一时间基准与单位，保留用于展示的日期时间。
3. 连接 02 的单一采集源、用户开始/停止操作、时间推进与 Room 写入。保存定点位置、可选距离和手机放置方式，用户可从列表重新打开 Session 并查看时长、样本数量和采样详情。图表、定点统计、筛选和编辑在 08 补齐。
4. 提供共用的会话控制和追加接口，限一个正在采集的 Session。实现正常结束和遗留会话标为中断，保留已写入记录。由 05、08 和 11 沿用同一入口，避免重复开扫描或重建会话管理。
5. 只创建本切片用到的 Room 模型。05 增加快照与实时结果，06 增加可编辑配置，08 增加人工参考标记，09 增加回放结果，均沿用同一数据库。导出 Room schema 给后续验证升级路径，保留已采数据。

## 存储约定

以下是必须能读出的语义，表和实体形状由实现决定。

| 记录 | 供谁读取 | 必须保留的内容 |
| --- | --- | --- |
| Session | 05、08、09 | Session ID、目标、类型、标签、生命周期状态、已记录时间覆盖范围。05 增加开始时的规则和参数快照 |
| 判定输入时间线 | 05、09 | 稳定处理顺序、会话内处理时刻、样本观测时刻和收到时刻、观测标识、原始 RSSI、扫描/身份变化。能区分同一时刻的多条输入 |
| 原始运行结果、人工参考标记 | 05、07、08、09、10 | 05、08 分别随真实用例落地。事件具有稳定标识，标签编辑不修改输入或原始结果 |
| 配置和回放 | 06、09、10 | 06 保存用户配置和当前选择，09 保存各回放结果与所用版本 |

正常结束时可以使用确认的结束时刻。进程异常终止时只保留可证明已记录的覆盖终点。恢复后发现中断的时间可用于诊断，不能充当原 Session 的结束时刻或评分覆盖范围。日期时间只用于显示，运行时判断使用会话相对时间。

## 验收与交接

- [x] 从 App 定点入口开始采样，经过 BLE 采集边界送入可控数据，停止并重开 App，从列表打开详情，核对目标、位置、测试时长和原始样本。
- [x] 用真实 Room 自动化测试验证关闭重开、时间戳相同时的稳定顺序、空窗、正常结束和中断后保留已写数据。使用设备或模拟器运行 Room 测试，不需要实车或实际 BLE 广播。JVM 用例另覆盖会话生命周期。
- [x] Answer 记录 Session、数据追加、关闭/中断、时间基准和覆盖范围的接口与测试结果，供 05、08、09、11 复用。

## Answer

### 完成内容

- 定点采样闭环：`SamplingScreen` / `SamplingViewModel` 通过 `SessionRecorder` 驱动 02 的 `TargetObservationSource`，写入 `RoomSessionStore`。停止后会话进入列表，可从列表重开详情，展示目标、位置、手机放置方式、时长与原始样本。
- Room 数据库升级到 version 2：新增 `sampling_session` 与 `session_timeline`，`MIGRATION_1_2` 的列、索引与外键与 `app/schemas/com.byd.proximitylab.data.ProximityLabDatabase/2.json` 一致；`app/build.gradle.kts:75-78` 配置 `room.schemaLocation`，schema 已导出供 08 验证后续升级路径。
- 会话生命周期：`SessionRecorder` 限制一个正在采集的 Session；支持正常结束，并在启动时把遗留会话标记为中断（`AppContainer` 调用 `recoverInterruptedSessions()`），中断保留已写入记录。
- 时间线：`SessionTimelineEntity` 以自增 `sequence` 提供稳定处理顺序；`Observation` 保存 `observedAtEpochMillis`、`receivedAtEpochMillis`、`processedAtEpochMillis`、`rawRssiDbm`、`observationId`、`scanFrameId`、`deliverySequence`；扫描与身份变化分别落事件；`coverageEndEpochMillis` 对 `COMPLETED` 取 `max(ended, lastRecorded)`，对 `INTERRUPTED`/`RECORDING` 取 `lastRecorded`，`interruptedDetected` 不计入覆盖。
- Room 只存在于 data 层；`SessionRecorder` 与会话模型为纯 Kotlin，不依赖 Room。
- 范围：10 个修改文件与 13 个新增文件均属本票（含 `TargetMonitor` 的加法式丢弃计数）；`git diff --check 051958b4` 返回 0，未发现越界改动。

### 实际验证

以下命令由实施与脚本 gate 执行，记录其主要输出：

- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --console=plain --rerun-tasks` → exit 0，BUILD SUCCESSFUL；12 个测试类共 83 tests，0 failures、0 errors、0 skipped；其中 `SessionRecorderTest` 14、`SamplingViewModelTest` 2。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain`（emulator-5554 / Medium_Phone_API_37.0 / API 37）→ exit 0，BUILD SUCCESSFUL；28 tests，0 failures、0 errors、1 skipped（02 的真实 BLE 广告者字段用例无广告者时 Assume 跳过）。新增 `SessionRoomStoreTest` 8、`ProximityLabDatabaseMigrationTest` 1、`AppContainerWiringTest` 1、`SamplingScreenTest` 2、`SamplingProductionWiringTest` 1 均通过；删除测试重复 import 后又完整跑过一次 connected，结果仍为 28 tests / 0 failures。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:assembleDebug :app:lintDebug --console=plain` → exit 0，BUILD SUCCESSFUL；`app/build/reports/lint-results-debug.txt` 为 0 errors、3 warnings（OldTargetApi、ObsoleteSdkInt、MonochromeLauncherIcon，均为 01 基线）。
- `"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging/permissions app/build/outputs/apk/debug/app-debug.apk` → compileSdk 37、minSdk 31、targetSdk 36；仅有 `BLUETOOTH_SCAN(neverForLocation)` 和系统 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无 `INTERNET`、`BLUETOOTH_CONNECT` 或定位权限。
- `git diff --check 051958b4` → exit 0，无空白错误。
- `node` 解析 `app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` → `<testsuites tests="28" failures="0" errors="0" skipped="1">`。
- 脚本 gate：`:app:assembleDebug :app:testDebugUnitTest` exit 0；`:app:connectedDebugAndroidTest` exit 0。

独立复审结论：

- 验收-1（App 定点入口 → 停止 → 重开 → 列表详情）：`SamplingScreenTest.fixedPointSessionIsSavedAndReopenedFromListWithRawSamples` 通过（connected XML 0 failures），走真实 `SamplingScreen`/`SessionRecorder`/`TargetMonitor`/`RoomSessionStore`，断言目标 Generic device 与地址、车尾、背包、3.5 米、3.0 秒、2 条样本、-82/-81 dBm、返回列表；未在真机上手动重走。
- 验收-2（真实 Room 自动化）：`ProximityLabDatabaseMigrationTest`、`SessionRoomStoreTest`、`AppContainerWiringTest`、`SamplingScreenTest` 均通过；覆盖 v1→v2 迁移并重开、同一时刻 6 条稳定顺序、观测空窗 1000→7000、正常结束、中断只保留已写入覆盖；JVM `SessionRecorderTest` 14 tests 覆盖正常/中断/恢复/单会话/时钟回拨/事件缺口。
- 验收-3（Answer/交接）：本节、复选框、Status 与 map.md 在复审后由票据管理员补齐。

本次票据收尾（记录与提交）未重跑上述 gradle 与 connected 命令；以上结果来自实施和脚本 gate 的实际执行输出，复审亦声明未重跑。

### 公开入口与下游

- `com.byd.proximitylab.session.SessionRecorder`：`activeSession: StateFlow<SamplingSession?>`、`suspend start(SessionStartRequest): SessionStartResult`、`suspend stop(): SessionStopResult`、`suspend recoverInterruptedSessions(atEpochMillis = clock()): Int`（`app/src/main/java/com/byd/proximitylab/session/SessionRecorder.kt`）；05/08/11 复用同一会话控制，05/08 通过 `SessionStore.appendTimeline` 追加。
- `com.byd.proximitylab.session.SessionStore`：`observeSessions`/`observeSession`/`observeTimeline`/`loadSession`/`loadTimeline`/`createSession`/`appendTimeline`/`completeSession`/`markInterrupted`/`loadRecordingSessions`/`markRecordingSessionsInterrupted`（`app/src/main/java/com/byd/proximitylab/session/SessionStore.kt`）。
- `com.byd.proximitylab.session.SamplingSession`、`SessionTimelineEvent`（`Observation`/`ScanStateChanged`/`IdentityChanged`/`EventGap`）、`SessionTimelineItem`、`SessionSummary`、`SamplingType`、`FixedSampleLocation`、`PhonePlacement`、`SessionLifecycleStatus`、`coverageEndEpochMillis`、`durationMillis`、`scanFrameIdOf`（`app/src/main/java/com/byd/proximitylab/session/SessionModels.kt`）；09 直接读取 `sequence`、`processedAtEpochMillis`、`observedAtEpochMillis`、`receivedAtEpochMillis`、`scanFrameId` 与 `deliverySequence` 复现空窗与重复投递。
- `com.byd.proximitylab.session.data.RoomSessionStore`、`SamplingSessionDao`、`SamplingSessionEntity`、`SessionTimelineEntity`（`app/src/main/java/com/byd/proximitylab/session/data/SessionPersistence.kt`）。
- `com.byd.proximitylab.data.ProximityLabDatabase` version 2、`samplingSessionDao()`、`MIGRATION_1_2`（`app/src/main/java/com/byd/proximitylab/data/ProximityLabDatabase.kt`）。
- `com.byd.proximitylab.AppContainer` 的 `sessionStore`、`sessionRecorder`、`close()`（`app/src/main/java/com/byd/proximitylab/ProximityLabApplication.kt`）；应用启动时自动调用 `recoverInterruptedSessions()`。
- `com.byd.proximitylab.ui.AppViewModelProvider.samplingFactory`、`SamplingViewModel`、`SamplingScreen`（`app/src/main/java/com/byd/proximitylab/ui/...`）供 05/08 复用采样入口与详情展示。

### 未测项

- 未在真实 Android 12 真机或实际 BLE 广播上运行；connected 使用 API 37 模拟器，02 的真实广告者字段用例本轮 1 skipped。
- 未执行真实 `adb shell am force-stop` / 进程 kill；中断恢复通过关闭并重开 Room 连接、重建 `TargetMonitor`/`SessionRecorder`/`ViewModel` 以及 AppContainer 启动恢复测试覆盖。
- 未在同一 instrumentation 用例中同时使用 `MainActivity` 生产接线和可控 BLE 源；生产接线（`SamplingProductionWiringTest`）与可控 BLE + 重开（`SamplingScreenTest`）由两个测试分别覆盖。
- 未运行 release 构建、未做手工 UI 走查或 Android 12–17 多设备矩阵。

### 复审遗留提醒（low，不阻塞）

- 全局丢弃计数尚未在 UI 呈现。
- 扫描状态发布顺序。
- start 边界事件。
- 初始扫描状态。
- 测试入口覆盖。
- 测试时钟与 DB 清理。

## Comments
- claimed at HEAD 051958b4d9223375e7a56a6227d73e4aef086782
