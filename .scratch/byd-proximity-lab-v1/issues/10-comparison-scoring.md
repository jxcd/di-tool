# 10 实现算法对比和人工标记评分

Type: task
Status: resolved
Blocked by: 09
User stories: 16, 17, 32, 33, 34, 35, 36

## 任务

从实验室或 Session 详情选择一套或多套算法配置回放同一 Session。并排展示状态、事件和标记，用一致的时间窗规则评价标记完整的试验。

**交付物** 同一时间轴的算法对比界面和可复现的评分结果，包含采用的人工参考、时间窗、版本及跳过原因。

## 工作内容

1. 使用 06 的配置选择和 08 的 Session 选择，调用 09 执行回放。复用图表时间轴，展示原始曲线、各配置滤波曲线、状态、事件和人工参考标记。可识别当次运行配置与当前配置，显示算法及触发规则版本。
2. 对明确标为一次预期接近的 Session，以唯一的期望触发标记与本次设定的允许窗口评价事件。界面在评分时展示窗起止、采用的时间单位和相对于标记的偏移。第一条事件早于窗起点记提前，落在窗内记按时，晚于窗终点记迟到，完整试验零事件记漏触发。同次试验多出的事件单独计为重复。窗口参数用于评分，按实测校准，不作为 RSSI 算法输入。
3. 对明确标为无需触发的 Session 统计发生的事件并标出误触发。未标记或存在多个评分参考点的 Session 保留曲线及事件，不推算准确率。中断试验未覆盖所需评分时间窗时，显示观测不足和已有事件，不把未记录区间解释成漏触发或成功。
4. 能区分一次真实正样本中「未取得可信 `FAR` 导致漏触发」与有 `FAR` 后仍未触发。多套配置使用同一 Session 和评价窗口。可追溯每项结果所用配置和判定版本。

## 评分判定表

| 人工参考和时间覆盖 | 事件情况 | 结果 |
| --- | --- | --- |
| 一次预期接近，记录覆盖完整评分窗口 | 先早于窗起点，后在窗内又触发一次 | 「提前」，另记 1 条额外事件 |
| 一次预期接近，记录覆盖完整评分窗口 | 首条恰在窗起点或终点 | 「按时」 |
| 一次预期接近，记录覆盖完整评分窗口 | 首条晚于窗终点，但仍在 Session 内 | 「迟到」 |
| 一次预期接近，记录覆盖完整评分窗口 | 全程 0 条，且一直缺少可验证的 `FAR` | 「漏触发」，记录未确认 `FAR` 的原因 |
| 一次预期接近，记录在窗口结束前中断 | 只能看到已记录部分 | 「观测不足」，展示已记录事件并从评分汇总单列 |
| 明确标为无需触发 | 有事件 | 按可观察事件记录误触发。试验提前中断时另外标注覆盖不全 |
| 明确标为无需触发 | 提前中断且无事件 | 「观测不足」 |
| 无标记、多个期望点或标记相互冲突 | 任意 | 仅诊断，说明为何跳过评分 |

如展示汇总，每个配置都应给出纳入评分的 Session 数及剔除原因。这里的重复计数针对一次评分试验的额外事件，实时事件引擎仍按确认 `FAR` 后允许折返再次触发。

## 验收与交接

- 用一条人工参考标记在 `10 s`、测试允许窗口 `9–12 s` 的夹具覆盖 `8 s` 提前、`10 s` 按时、`13 s` 迟到、零事件漏触发，以及 `10 s` 加 `11 s` 一次额外触发。测试覆盖窗起止边界并校验 UI 所用规则一致。该时间窗仅为测试输入。
- 在真实 App 入口选同一 Session 和两套配置，核对同轴显示、事件原因和评分差异。另验证无需触发且有事件、未标记、多个参考点、中断未覆盖窗口以及旧版本回放各自的界面状态。
- [x] 用固定时间线自动化覆盖评分判定表。用真实 09 回放服务跑两份参数，断言各自结果、版本和评分窗口可查，未标记记录不进入汇总。
- Answer 记录评分入口、窗口与时点约定、各分类和重复计数口径，供现场验收使用。该票无需自动参数推荐或 Session 导出。

## Answer

### 完成内容

- 新增 `com.byd.proximitylab.comparison` 对比与评分域。`SessionComparisonService.compare(sessionId, targets, window)` 对同一 Session 依次回放原始快照或多套配置；`ComparisonTarget` 提供 `OriginalSnapshot` 与 `Config(configId)` 两种比较对象。
- 评分规则集中在纯 Kotlin 的 `SessionScoring.evaluate(session, markers, window, replay)`：`ScoringWindow` 默认提前 1 s、迟到 2 s，评分窗为 `[标记-提前容差, 标记+迟到容差]` 且两端含端点。首条事件早于窗起点记「提前」并另行计额外事件，落在窗内（含端点）记「按时」，晚于窗终点但仍在 Session 内记「迟到」，完整试验零事件按是否取得可信 `FAR` 区分漏触发与未确认 `FAR` 原因。
- 负样本仅由 `SessionEvaluationLabel.NO_TRIGGER` 决定：有事件按可观察事件记误触发，试验提前中断另外标注覆盖不全；提前中断且无事件记「观测不足」。无标记、多个期望点或标记相互冲突的 Session 保留曲线与事件，但进入 `SKIPPED` 诊断并说明跳过原因；中断未覆盖评分窗口记 `INSUFFICIENT_OBSERVATION`，不把未记录区间解释成漏触发或成功。
- `ComparisonSummaryBuilder.build(sessions)` 只统计纳入评分的试验（`TrialScore.includedInSummary` 排除 `SKIPPED` 与 `INSUFFICIENT_OBSERVATION`），逐条记录剔除原因。同次试验多出的事件单独计为额外事件，实时事件引擎在确认 `FAR` 后允许折返再次触发的规则不变。
- `SessionComparisonChart.build(...)` 生成单一 `RssiChartData`，同轴包含原始曲线、各配置滤波曲线、状态、事件、人工参考标记与评分窗标记；`ComparisonTrialResult` 保留回放产生的配置、配置来源、算法/触发规则版本与版本差异，可区分当次运行配置与当前配置。
- 新增 `AlgorithmComparisonSection` 并接入 `AlgorithmLabScreen`：选择 Session、勾选原始快照与多套配置、编辑提前/迟到容差后运行对比，展示采用的时间单位（秒）、窗起止、相对标记偏移、每项结果与汇总。
- `AppContainer.sessionComparisonService` 与 `AppViewModelProvider.labFactory(store, comparisonService)` 完成生产接线。

### 实际验证

以下构建与全量测试由实施与脚本 gate 执行，本记录轮未重跑全量命令；复审只运行了下列标注为复审的窄范围命令。

- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug --console=plain` → BUILD SUCCESSFUL（4m38s）；JVM 30 个测试类共 180 tests、0 failures、0 errors、0 skipped；`:app:assembleDebug` 成功；`app/build/reports/lint-results-debug.txt` 为 `0 errors, 3 warnings`（既有 OldTargetApi/ObsoleteSdkInt/MonochromeLauncherIcon）。
- `JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain` → BUILD SUCCESSFUL（12m25s，exit 0）；`app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` 为 tests=78、failures=0、errors=0、skipped=1（既有 `AndroidBleScanSourceTest.realAdvertisementsExposeFieldsWhenAnAdvertiserIsAvailable` 因 Assume 跳过），其中 comparison 相关 8 个用例全部通过。
- 复审窄范围复验：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.byd.proximitylab.comparison.SessionComparisonRoomTest,com.byd.proximitylab.ui.lab.AlgorithmComparisonScreenTest --console=plain` → BUILD SUCCESSFUL（2m39s），XML 8 tests、0 failures、0 errors、0 skipped；`./gradlew :app:testDebugUnitTest --tests 'com.byd.proximitylab.comparison.*' --console=plain` → BUILD SUCCESSFUL，`SessionScoringTest` 8 tests、0 failures（最终全量单测再次包含该类，XML 仍为 8/0）。
- `"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk` → compileSdkVersion 37、minSdkVersion 31、targetSdkVersion 36；`dump permissions` 仅 `BLUETOOTH_SCAN(neverForLocation)` 与 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无 `INTERNET`。
- `git diff --check 3dd93a9eaaa147c0872fe62daa9a2bcdc6306148` → exit 0，无空白错误；`rg -n "^import android" app/src/main/java/com/byd/proximitylab/comparison` → 无匹配，纯评分/对比层不依赖 Android。

关键用例覆盖：

- 同一 Session 多配置对比：`SessionComparisonService.compare` 对同一 Session 依次回放多个 target；`SessionComparisonChart` 生成一个 `RssiChartData` 同时包含原始曲线、各配置滤波曲线、事件/参考/评分窗标记；`SessionComparisonRoomTest.twoConfigsOnTheSameSessionKeepWindowsVersionsAndReplayRecords` 与 `AlgorithmComparisonScreenTest.twoConfigsShareOneTimelineAndShowDifferentScoresAtTheWindowEndBoundary` 覆盖。
- 评分判定表：`SessionScoringTest` 覆盖 8 s 提前、9 s/10 s/12 s 按时（含端点）、13 s 迟到、无 `FAR`/有 `FAR` 的漏触发、10 s+11 s 额外事件、误触发、中断观测不足、未标记/多标记跳过。
- 窗口边界与单位：UI 测试把提前/迟到容差从 1.0/2.0 改为 0/0 后，12 s 事件从「按时」变为「迟到」；界面显示时间单位与相对标记偏移。
- 未标记不进入汇总：`SessionScoringTest.summaryCountsOnlyScoredTrialsAndKeepsExclusionReasons`、`SessionComparisonRoomTest.unmarkedSessionIsExcludedFromTheSummary...` 与 UI 测试均断言。
- 真实 09 回放服务：`SessionComparisonRoomTest` 使用真实 `RoomSessionStore`/`RoomAlgorithmConfigStore`/`RoomSessionReplayStore`/`SessionReplayService`/`SessionComparisonService`，两份配置各自结果、版本和评分窗口可查；旧版本回放显示 0→1 版本差异。

已知 flake：全量 connected 第一次尝试在既有 `DeviceScreenTest.permissionBluetoothErrorAndTargetNotSeenHaveDistinctOutputs` 触发 Compose `performMeasureAndLayout called during measure layout` 崩溃（09 票记录的同类 flake，进程中断，仅 54 tests），单独重跑该既有用例通过；第二次全量暴露本票 UI 测试对配置列表顺序的假设（`RoomAlgorithmConfigStore` 按 createdAt+configId 排序），已在测试夹具中递增时钟固定顺序后复跑；第三次全量 78 tests 全通过。

### 公开入口与下游

- `com.byd.proximitylab.comparison.SessionComparisonService`（`SessionComparisonService.kt:21`）：`compare(sessionId, targets, window)`（`:48`）、`loadContext(sessionId)`（`:32`）、`observeSessions()`（`:27`）；下游 12 可直接复用。
- `com.byd.proximitylab.comparison.ComparisonTarget`（`ComparisonModels.kt:172`）：`OriginalSnapshot` 与 `Config(configId)`；`ScoringWindow`（`:23`）默认提前 1 s、迟到 2 s；`TrialOutcome`/`MissedTriggerCause`/`TrialScore`/`ComparisonTrialResult`/`SessionComparison`/`ComparisonScoreSummary` 提供结果、窗口、版本、`FAR` 原因与汇总字段。
- 纯 Kotlin 评分入口 `SessionScoring.evaluate(session, markers, window, replay)`（`SessionScoring.kt:30`）与汇总入口 `ComparisonSummaryBuilder.build(sessions)`（`ComparisonSummaryBuilder.kt:21`）；同轴图表 `SessionComparisonChart.build(...)`（`SessionComparisonChart.kt:30`），供 10 UI、12 集成与后续多 Session 汇总复用。
- `AppContainer.sessionComparisonService`（`ProximityLabApplication.kt:106`）与 `AppViewModelProvider.labFactory(store, comparisonService)`（`AppViewModelProvider.kt:26`）完成生产接线。
- `AlgorithmLabViewModel` 对比状态与方法：`ComparisonUiState`（`AlgorithmLabViewModel.kt:43`）、`selectComparisonSession`（`:280`）、`toggleComparisonTarget`（`:313`）、`updateComparisonEarlyTolerance`/`updateComparisonLateTolerance`、`runComparison`（`:355`）。
- UI 入口 `AlgorithmComparisonSection`（`AlgorithmComparisonSection.kt:42`）与 `AlgorithmLabScreen`（`AlgorithmLabScreen.kt:140-148`）；测试标识包括 `lab_compare_section`、`lab_compare_session_<id>`、`lab_compare_original`、`lab_compare_config_<configId>`、`lab_compare_early_window`、`lab_compare_late_window`、`lab_compare_run`、`lab_compare_window`、`lab_compare_chart_*`、`lab_compare_outcome_<index>`、`lab_compare_summary_<index>`。

### 未测项

- 未在真实 Android 12–17 手机或真实 BLE 广播/目标车辆环境执行；connected 仅运行 API 37 模拟器 `Medium_Phone_API_37.0`，既有真实广告者用例 1 skipped。
- 未执行 release 构建；仅 debug assemble/lint/connected。
- 未做人工 UI 走查、截图或像素级验证；界面行为由 Compose 自动化测试覆盖。
- 未验证超过 5 分钟的长时间 Session 全量时间轴显示（沿用既有 30s/60s/5min 图表窗口）。
- 评分结果/评分窗口未单独持久化：回放记录已持久化，重启后需按同一 Session、配置和窗口重新运行；未验证跨进程重启后直接读取上次评分。
- 未自动化验证非法窗口输入（如非数字或超出 0–3600 秒）的界面错误提示；实现中已有校验。

### 复审提示（low，不阻塞）

- 复审结论为屏幕级通过（真实 Compose 页面 + 真实 ViewModel/服务，覆盖两配置同轴、事件原因、评分差异、无需触发有事件、未标记、多参考点、中断未覆盖窗口、旧版本回放），但生产 `AppContainer`/`AppViewModelProvider` 接线未通过 `MainActivity` 端到端测试；接线本身见 `ProximityLabApplication.kt:106` 与 `AppViewModelProvider.kt:26`，建议由 12 的集成验收覆盖。

## Comments

- claimed at HEAD 3dd93a9eaaa147c0872fe62daa9a2bcdc6306148
