# 09 从 Session 详情重现一次原配置回放

Type: task
Status: resolved
Blocked by: 08
User stories: 24, 32, 36

## 任务

从 08 的详情运行一次回放，查看共享接近判定生成的滤波曲线、状态和事件。先完成用当次快照复现原始模拟测试的闭环，10 再开放多配置比较。

**交付物** 以 Session 和配置为输入的回放入口，返回可绘图与评分的结果，并能区分原始运行结果和新回放。

## 工作内容

1. 按 04 和 05 约定读取 Session 输入，保持判定实际收到的先后关系。保留样本观测时刻、收到时刻、扫描/身份变化及结束时间。回放使用相对于 Session 的时间推进，适当在空窗和最后一帧后推进时间，让数据过期可以出现。切勿靠删除空窗或连续无间隔投递 RSSI 来加速回放。
2. 每项回放从同一 Session 初始条件独立运行，调用 03 实际入口。可以使用当次快照或由上层指定的具名配置快照，输出滤波曲线、状态、趋势、事件时刻和原因。
3. 保留 Session 原来发生的状态和模拟事件。新回放结果带算法、参数快照及触发规则版本。旧配置删除后仍能读取当次快照。同版本用原快照重现结果。运行版本变化时，用当前实现和可用的快照重算，标明版本差异，保留原记录供比较。
4. 从详情使用当次快照发起回放。有原始输入却无当次算法快照的采样 Session 可用当前选中配置进行分析，标为新算结果。各次回放启动独立判定上下文。中断 Session 只重放已记录范围，对缺少数据给出可辨识结果。回放不写实时事件历史或触发通知、震动。

## 回放样例

沿用 [03 的 P4](03-proximity-engine.md#判定用例) 参数。两条弱帧分别在 `0 s` 和 `1 s` 被观测并处理。之后直到 `8 s` 才收到新近端帧，`9 s` 收到第二条近端帧。

| 会话时刻 | 实时和回放共同的可观察结果 |
| --- | --- |
| `1 s` | 确认 `FAR` |
| `6 s` | 无新帧也因样本过期进入 `UNKNOWN`，触发资格取消 |
| `9 s` | 用新证据确认 `NEAR`，累计 0 条事件 |

另外回放 03 的 P6，按已记录的处理顺序投递，保留强帧真正的观测时刻。两条强帧即使在列表末尾到达也属于旧数据，仍是 0 条事件。若一条记录在 `4 s` 中断且已知覆盖仅到 `4 s`，回放止于 `4 s`，不利用重新打开 App 的时间推断 `6 s` 的状态。

## 验收与交接

- 用 05 实际实时流程记录一段含空窗及停扫恢复的样本。相同版本及快照回放后，逐项核对状态、事件相对时刻和原因。须能重现在没有新帧期间进入 `UNKNOWN`。
- 用两套有意不同的配置跑同一 Session，断言一项能触发而另一项不触发，且重复回放结果一致。关闭并重开 App 后，各份回放结果可按版本独立查询。原配置删除和旧版本记录仍可查询，运行回放前后实时历史和通知输出不变。
- [x] 用真实 Session 存储与共享判定自动化验证实时/回放等价，覆盖 P4、P6、中断覆盖终点、配置删除后快照和版本差异。确认回放一条事件也不会调用实时反馈。
- Answer 记录回放入口、配置/快照参数、时间输出和版本字段，供 10 直接消费。该票不实现评分或实验室布局。

## Answer

### 完成内容

- 回放域新增 `com.byd.proximitylab.replay`：`SessionReplayService` 以 Session 与配置为输入运行回放，`SessionReplayEngine` 复用 03 的 `ProximityEngine`，每次回放在独立判定上下文中运行。
- 时间推进遵循 Session 相对时间：按 sequence 应用全部时间线（`ReplayModels.kt:299-351`），在空窗中按 `sampleValidity` 边界显式 `advanceTo`，末尾推进到 `coverageEnd`；不删除空窗，也不连续无间隔投递 RSSI。保留观测时刻、收到时刻、扫描/身份变化与覆盖终点。
- 配置来源为 `ReplayConfigRequest.OriginalSnapshot` / `NamedSnapshot` / `CurrentSelection`。缺当次快照时退回当前生效配置并标记 `NEW_CALCULATION`；运行版本与快照版本不同时用当前实现重算并标记 `VERSION_RECOMPUTED`，记录版本差异并保留原记录供比较。
- 输出 `SessionReplayRecord` / `SessionReplayResult`：滤波曲线样本、状态点、事件及时刻/原因、`ReplayInputCoverage`、`ReplayVersionInfo`、`ReplayConfigSource` 与回放结论。
- Room v6 新增回放持久化：`ProximityLabDatabase` 的 `sessionReplayDao()`、`MIGRATION_5_6`（`ProximityLabDatabase.kt:313`）与导出 `app/schemas/com.byd.proximitylab.data.ProximityLabDatabase/6.json`；`ProximityLabApplication` 装配 `sessionReplayStore`/`sessionReplayService`。
- Session 详情增加回放区与 `SessionReplayChart`（复用 07 图表契约），可运行回放并查看摘要、配置、覆盖、状态与事件。
- 回放只写回放记录，不写实时事件历史，不触发通知或震动。

### 实际验证

以下由实施与脚本 gate 执行，本记录轮未重跑：

- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --rerun-tasks --console=plain` → exit 0，BUILD SUCCESSFUL；29 个测试类共 172 tests、0 failures、0 errors、0 skipped。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --tests 'com.byd.proximitylab.replay.*' --console=plain` → BUILD SUCCESSFUL；`SessionReplayEngineTest` 6 tests、0 failures（P4/P6/中断/输入缺口/不同配置确定性/独立初始上下文）。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain` → BUILD SUCCESSFUL；`TEST-Medium_Phone_API_37.0(AVD) - 17.xml` 为 tests=70、failures=0、errors=0、skipped=1（既有 `AndroidBleScanSourceTest` 真实广告者 Assume 跳过），其中 `SessionReplayRoomTest` 6、`SessionReplayRoomStoreTest` 1、`SamplingReplayScreenTest` 2、`ProximityLabDatabaseMigrationTest` 5 通过。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` → BUILD SUCCESSFUL；`app/build/reports/lint-results-debug.txt` 为 0 errors、3 warnings（既有 OldTargetApi/ObsoleteSdkInt/MonochromeLauncherIcon）。
- `"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk` → compileSdk 37、minSdk 31、targetSdk 36；权限仅 `BLUETOOTH_SCAN`（neverForLocation）与 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无 `INTERNET`。
- `git diff --check 6deb7c0c32624ee209664bc188659efd566d0d4c` → exit 0（仅 Git LF→CRLF 提示），无空白错误。
- `rg -n '^import android' app/src/main/java/com/byd/proximitylab/replay` 仅匹配 Room 持久化文件，纯 Kotlin 判定层无 Android import 泄漏。

独立复审的窄范围复验：`JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --tests "com.byd.proximitylab.replay.SessionReplayEngineTest" --console=plain` → BUILD SUCCESSFUL，刷新后的 XML 为 6 tests、0 failures、0 errors、0 skipped；复审读取 connected XML 确认 70 tests、0 failures、1 skipped 及上述回放/迁移用例通过。

关键用例覆盖：

- P4/P6 与真实 Room：`SessionReplayRoomTest.kt:93-143` 断言 P4 的 6 秒过期与 9 秒恢复 `NEAR`、P6 的 0 事件与最终 `FAR`（重复帧与过期帧被拒绝）；`SessionReplayEngineTest` 断言 P4 的 0/1/6/8/9 秒与 P6 的 0/2/5.2 秒状态序列。
- 中断：测试证明覆盖止于 4 秒，不利用重开时间推断 6 秒过期。
- 不同配置：`SessionReplayRoomTest.kt:202-279` 用两套阈值配置回放同一 Session，触发配置 1 事件、不触发配置 0 事件；重复回放除 `replayId`/`createdAt` 外一致，重复构造 runner 无共享判定状态。
- 配置删除/版本差异/重开：`SessionReplayRoomTest.kt:324-421` 删除原用户配置后仍按当次快照回放并标 `REPRODUCED`；旧版本快照标 `VERSION_RECOMPUTED` 且记录两处版本差异；`SessionReplayRoomStoreTest.kt:65-116` 重开 Room 后按版本读取记录并验证级联删除。
- 回放隔离：`SessionReplayRoomTest.kt:146-199` 用真实 05 流程录制含空窗与停扫恢复的 Session，实时与回放 states/events 一致；回放前后 `session_run_event`/`session_run_state` 与 `DELIVERED` 反馈不变；`rg "Notification|Vibrat" app/src/main/java` 无匹配。
- 迁移：`MIGRATION_5_6` 与 `6.json` 的列/索引/外键逐项一致，v5→v6 用例在 connected XML 中通过。

已知 flake：首次全量 connected 出现一次既有 `MonitorScreenTest.injectedClockExpiresTestWithoutNewSamplesAndRecoveryEmitsNothing` 的 Compose 内部 crash（performMeasureAndLayout called during measure layout），导致进程中断；单独重跑该测试与随后两次全量 connected 均通过，按既有 Compose 测试 flake 记录，未弱化测试。

### 公开入口与下游

- `com.byd.proximitylab.replay.SessionReplayService`（`SessionReplayService.kt:22`）：`replay(sessionId, request=ReplayConfigRequest.OriginalSnapshot)` 返回 `SessionReplayResult`；`observeReplays(sessionId)`、`loadReplays(sessionId)`、`loadReplay(replayId)` 供 10 查询/比较。
- `ReplayConfigRequest`：`OriginalSnapshot`、`NamedSnapshot(snapshot)`、`CurrentSelection`；`SessionReplayRecord`/`SessionReplaySummary`/`ReplaySample`/`ReplayStatePoint`/`ReplayEvent`/`ReplayInputCoverage`/`ReplayVersionInfo`/`ReplayOutcome`/`ReplayConfigSource` 提供配置参数快照、样本与状态相对/epoch 时刻、事件原因、当次/本次/执行版本、覆盖与回放结论。
- `SessionReplayStore`/`RoomSessionReplayStore`（`SessionReplayStore.kt`、`data/SessionReplayPersistence.kt:507`）：`observeReplaySummaries`、`loadReplaySummaries`、`loadReplay`、`loadReplays`、`saveReplay`。
- `ProximityLabDatabase` v6：`sessionReplayDao()`、`MIGRATION_5_6`（`ProximityLabDatabase.kt:51`、`:63`、`:313`）与导出 schema；`AppContainer.sessionReplayStore`/`sessionReplayService`（`ProximityLabApplication.kt:91-104`）。
- `com.byd.proximitylab.replay.SessionReplayChart.build(session, timeline, record, referenceMarkers)` 生成 07 契约的原始+回放滤波曲线、阈值、事件和状态标记。
- UI：`SamplingViewModel.replaySession(sessionId)`（`SamplingViewModel.kt:273`）、`SessionDetailState.replay/replayChart/replayHistory/replayMessage`（`:65`）、详情测试标签 `sampling_detail_replay_run/summary/config/coverage/states/events`（`SamplingScreen.kt:774-845`）；`AppViewModelProvider.samplingFactory(..., replayService=...)`。
- 纯 Kotlin 复用入口：`SessionReplayEngine.replay(session, timeline, config)`（`ReplayModels.kt:291`）与 `SessionTimelineProximityRunner`（`ReplayModels.kt:312`）及测试夹具，供 10、12 复用。

### 未测项

- 未在真实 Android 12–17 手机或真实 BLE 广播/目标车辆环境执行；connected 仅在 API 37 模拟器 `Medium_Phone_API_37.0` 运行，既有真实广告者用例 1 skipped。
- 未观察真实 Android 通知或震动副作用：票据 11 才实现反馈通道；本票回放路径不依赖通知/震动实现，测试只确认持久化的实时事件反馈字段未被回放修改。
- 未执行 release 构建、真实进程 kill 后手动重开 UI、超过 5 分钟的长时间 Session 图表走查和现场设备验收；重开通过关闭/重开 Room、重建 Store/Service/ViewModel 覆盖。
- 未做评分或实验室对比布局（属于票据 10）。

## Comments

- claimed at HEAD 6deb7c0c32624ee209664bc188659efd566d0d4c
