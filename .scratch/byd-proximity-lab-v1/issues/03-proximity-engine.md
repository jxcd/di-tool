# 03 实现实时与回放共用的接近判定

Type: task
Status: resolved
Blocked by: 01
User stories: 11, 12, 22, 25, 26, 27, 28, 32

## 任务

实现纯 Kotlin 判定入口，用有序观测时间线输出状态、趋势、滤波结果和 `ENTERED_RANGE`。同一输入和配置应得到相同判定。

**交付物** 05 和 09 共用的判定入口、带原因的输出、参数校验，以及可复用的固定时间线用例。

## 工作内容

1. 接受目标已关联的 RSSI 观测、扫描/身份可用性变化和时间推进。观测包含区分独立样本的信息。按观测时刻计算样本年龄，按判定收到的顺序和当前会话时间处理。迟到样本已达过期边界时不能补足确认或产生事件。显式时间输入覆盖无新帧时过期，核心逻辑不读取 Android API 或当前系统时间。
2. 实现原始 RSSI 的 `Baseline` 和指数滑动平均 `EMA`。配置包含必要的阈值、证据要求、样本过期条件及 EMA 参数。在配置入口校验进入阈值强于离开阈值、EMA 参数和计数或时长合法。输出原始值、滤波值、接近状态、运动趋势和状态原因。
3. 接近状态从 `UNKNOWN` 开始，只有有效的新鲜观测可以确认 `FAR` 或 `NEAR`。在迟滞区间保留已确认的状态。重复投递同一帧不能累积证据，RSSI 相同的独立样本可以累积。
4. `FAR` 确认后才允许下一次 `NEAR` 产生 `ENTERED_RANGE`。停在 `NEAR` 或仍处于迟滞区间期间不得重复。重新确认 `FAR` 后可再次触发，无独立冷却。趋势可以辅助算法，`APPROACHING` 无统一强制要求。
5. 停扫、身份失去确认或输入过期时输出带原因的 `UNKNOWN`，撤销触发资格和已失效的确认窗口。恢复后的第一批强信号可以确认 `NEAR`，不会补触发。把算法与触发规则版本作为调用方可记录的结果信息。
6. 校验观测的 RSSI、时间和身份。Android API 可提供的 RSSI 范围是 `-127..126 dBm`。越界样本或使最新有效观测时刻倒退的样本不得进入滤波或更新有效时刻，原始时间线仍可供诊断。普通的区间内 RSSI 尖峰由 EMA、迟滞和持续确认处理。`Baseline` 保持原始值。时长确认须有新鲜、持续的证据。 [ScanResult.getRssi](https://developer.android.com/reference/android/bluetooth/le/ScanResult#getRssi())

## 判定用例

以下夹具使用 `Baseline`，进入阈值 `-65 dBm`、离开阈值 `-80 dBm`，用连续两条有效帧确认，样本有效期 5 秒。阈值相等时算作达到对应阈值。当前证据达到有效期时，先做过期处理，再用新观测重新建立状态。数值均为测试输入。除特别指明，连续观测的时间间隔为 1 秒。

| 用例 | 给判定入口的输入 | 预期状态和事件 |
| --- | --- | --- |
| P1 正常进入 | 四条独立帧 `-85, -85, -60, -60` | 第 2 条确认 `FAR`，第 4 条确认 `NEAR`，累计 1 条 `ENTERED_RANGE` |
| P2 冷启动在近端 | `-60, -60` | 确认 `NEAR`，累计 0 条事件 |
| P3 去重与折返 | P1 后持续 `-60, -70`，再给 `-85, -85, -60, -60` | 近端停留与迟滞区波动期间维持 1 条，重新确认 `FAR` 后折返累计 2 条 |
| P4 输入过期 | 两条弱帧先确认 `FAR`。时间推进到最后一次有效观测过期，再给两条新的近端帧 | 空窗期间进入 `UNKNOWN`。恢复确认 `NEAR`，累计 0 条 |
| P5 停扫或身份冲突 | 确认 `FAR` 后传入停扫或身份不确定事件。恢复后只给两条新近端帧 | 证据中断时进入 `UNKNOWN`，恢复到 `NEAR` 无事件。两个中断原因可区分 |
| P6 重复及迟到 | 在 `1 s` 给弱帧及其重复投递，在 `2 s` 补独立弱帧。`5.2 s` 批量收到在 `0 s` 和 `0.1 s` 观测的两条强帧 | 重复帧不确认 `FAR`，补独立帧后才确认。收到过期强帧时仍为 `FAR`，累计 0 条 |
| P7 阈值边界 | `-80, -80, -65, -65` | 刚达到阈值即可按同样的有效帧规则确认，累计 1 条事件 |
| P8 EMA 和异常输入 | 从 `-80 dBm` 开始，`α = 0.5`，随后给独立的 `-60 dBm`、越界的 `127 dBm` 和新鲜的 `-60 dBm` | 对应有效滤波输出 `-80`、`-70`、`-65 dBm`。越界输入不产生滤波输出或延长证据有效期 |
| P9 单次尖峰和时长确认 | 本例改用持续 2 秒确认，先确认 `FAR`，再给 `-60, -85, -60, -85`，最后以 1 秒间隔给 `-60, -60, -60` | 交替的孤立尖峰无事件。连续近端观测满 2 秒后确认 `NEAR` 并触发一次。时间推进至样本过期能打断尚未达成的确认 |

## 验收与交接

- 使用同一公开判定入口写 JVM 用例。示例夹具可设进入阈值 `-65 dBm`、离开阈值 `-80 dBm`、连续两帧，时间间隔保持在该用例有效期内。`-85,-85,-60,-60` 预期先确认 `FAR`，再确认 `NEAR` 并产生一次事件。将重复项改成同一帧投递时，直到真正的独立样本到达才确认。这些参数仅用于测试。
- 覆盖冷启动近端、阈值边界、临界波动、近端停留、退到 `FAR` 后折返、无新增样本超时、停扫或身份冲突后首批强信号。断言状态、事件次数和原因。用已知数值断言 EMA 的可观察结果。
- Answer 记录共享入口、初始状态、时间/有序输入契约、参数校验与版本标识，指向供 05、09 复用的固定测试序列。该票无需 UI、Room 或通知。

## 自动化验收

- [x] 运行 P1–P9 JVM 用例，断言独立输入的滤波输出、状态、事件次数和失败原因。P5 分别注入扫描中断、身份失去确认和手动改绑，证明旧 `FAR` 不可带给新目标。
- [x] 用不同采样间隔覆盖按时长确认，确认状态不会由重复或已过期的样本推进。用固定数值断言 EMA 结果。

## Answer

### 完成内容

新增纯 Kotlin 判定包 `com.byd.proximitylab.proximity`。实时与回放共用 `ProximityEngine`，判定不读取 Android API 或系统时间，所有时间由调用方显式传入。每次输入先推进会话时间并处理过期，再校验观测并累计证据。

### 公开入口与下游

- 入口：`ProximityEngine(config, initialTargetId)`。`observe(observation, receivedAtMillis)` 投递观测；`advanceTo(atMillis)` 处理无新帧时的时间推进与过期；`setScanAvailable`、`setTargetIdentity`、`rebindTarget` 处理扫描、身份和改绑。
- 观测：`RssiObservation(targetId, frameId, rssiDbm, observedAtMillis)`。`frameId` 区分独立样本，重复投递同一帧必须复用；`receivedAtMillis` 是判定收到该帧的会话时间。
- 初始状态：新引擎为 `UNKNOWN / AWAITING_OBSERVATION`。只有先确认 `FAR`、之后确认 `NEAR` 才产生一次 `ENTERED_RANGE`；停留 `NEAR` 或迟滞区不重复，重新确认 `FAR` 后可再次触发。
- 有序性与时间：会话时间单调不减；样本年龄 = `receivedAtMillis - observedAtMillis`，达到 `sampleValidityMillis` 即过期。越界 RSSI、未来/倒序时刻、过期样本、目标不匹配和重复帧不进入滤波、证据或有效观测时刻；原始观测保留在 `ProximityDecision.rejection` 供诊断。
- 中断：停扫、身份失去确认、手动改绑和样本过期都回到 `UNKNOWN` 并撤销触发资格；恢复后直接确认 `NEAR` 不补触发。
- 清单：`AndroidManifest.xml` 声明只读 `BLUETOOTH_SCAN`（`neverForLocation`），无 `INTERNET`；本票不含扫描运行期逻辑，由 02 接入。
- 结果类型（下游直接读取）：`ProximityConfig`、`ProximityAlgorithm`、`ConfirmationRequirement.ConsecutiveSamples(minimumSamples)` / `ConfirmationRequirement.Duration(minimumDurationMillis)`；`RssiObservation`、`ProximityReading`、`ProximityDecision`、`ProximityEvent`、`ProximityState`、`MotionTrend`、`ProximityStateReason`、`TargetIdentityStatus`、`ObservationRejection`、`ObservationRejectionReason`。
- 下游 05/09：按 Session 有序时间线依次投递 `observe` / `setScanAvailable` / `setTargetIdentity` / `advanceTo`，读取 `decision.state`、`reason`、`trend`、`lastReading`、`events`；事件与决策均带算法与触发规则版本，可直接持久化。
- 复用夹具：`app/src/test/java/com/byd/proximitylab/proximity/ProximityTestHarness.kt` 与 `ProximityEngineTest` 的 P1–P9 固定时间线，供 12 复用；清单中的 `BLUETOOTH_SCAN` 供 02 继续接入运行期权限与扫描。

### 参数校验与版本标识

- `ProximityConfig` 构造时校验：进入阈值强于离开阈值、阈值在 `-127..126 dBm`、样本有效期 > 0、连续帧数 ≥ 1、确认时长 > 0、EMA α ∈ (0,1]；`copy()` 同样重新校验。
- `ProximityDecision` 与 `ProximityEvent` 均带 `algorithmVersion = "proximity-algorithms/1"`、`triggerRuleVersion = "confirmed-far-then-near/1"`。

### 供 05、09 复用的固定测试序列

`app/src/test/java/com/byd/proximitylab/proximity/ProximityTestHarness.kt` 与 `ProximityEngineTest.kt` 提供 P1–P9 固定时间线：P1 正常进入、P2 冷启动近端、P3 停留/迟滞/折返、P4 过期、P5 停扫/身份失去确认/手动改绑、P6 重复与迟到、P7 阈值边界、P8 EMA/越界、P9 尖峰与 2 秒时长确认（P9b 过期打断、P9c 不规则间隔、P9d 重复帧不推进）。夹具参数仅用于测试：Baseline、进入 `-65 dBm`、离开 `-80 dBm`、连续两帧、有效期 5 秒。

### 实际验证

- `JAVA_HOME=... ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks --console=plain` → BUILD SUCCESSFUL，exit 0；全量 37 tests，0 failures。
- `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests 'com.byd.proximitylab.proximity.*' --console=plain` → BUILD SUCCESSFUL，32 tests，0 failures。
- `JAVA_HOME=... ./gradlew :app:lintDebug --console=plain` → BUILD SUCCESSFUL。
- `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk` → minSdk 31、targetSdk 36、compileSdk 37、`BLUETOOTH_SCAN`（neverForLocation）、无 `INTERNET`。

补充记录（实施脚本 gate 与独立复审）：

- 全量：`JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks --console=plain` → BUILD SUCCESSFUL，exit=0，45/45 tasks executed；全量 37 tests，0 failures/errors/skipped。
- 聚焦：`JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --tests 'com.byd.proximitylab.proximity.*' --console=plain` → BUILD SUCCESSFUL，exit=0；ProximityEngineTest 19、ProximityConfigTest 6、ProximityObservationValidationTest 7，共 32 tests，0 failures。
- Lint：`JAVA_HOME="$(pwd)/.tooling/jdk-21" ./gradlew :app:lintDebug --console=plain` → BUILD SUCCESSFUL，exit=0；`app/build/reports/lint-results-debug.txt` 为 0 errors、3 warnings。
- 结果 XML：全量运行后 `rg -o 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' app/build/test-results/testDebugUnitTest/*.xml` → 6 个测试类均 0 failures/0 errors/0 skipped，合计 37 tests；随后的聚焦运行把目录覆盖为 3 个类、32 tests，仍为 0 failures。
- APK：`"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk` → minSdkVersion 31、targetSdkVersion 36、compileSdkVersion 37、`BLUETOOTH_SCAN` usesPermissionFlags=neverForLocation，无 `INTERNET`。
- 纯 Kotlin：`rg -n 'import android' app/src/main/java/com/byd/proximitylab/proximity/` 无匹配；测试通过 `ProximityTestHarness` 构造真实 `ProximityEngine`，无 mock/fake。
- 独立复审结论：通过（共享入口、P1–P9、初始 `UNKNOWN`、一次触发与折返、去重、过期/停扫/身份/改绑、EMA 固定值、参数校验、版本标识、时长确认与不规则间隔、Manifest、Scope）。

### 未测项

- 未运行 `:app:connectedDebugAndroidTest`，未做 Android 12 真机或真实 BLE 验证：本票无 UI、Room、服务、通知；BLE 运行期接入由 02、05、13、14 完成。
- 未在模拟器安装或启动 APK：本票无 UI、Room、服务或通知改动；Manifest 变更只通过 APK badging 检查。

### 复审遗留（low）

- `seenFrameIds` 只保留最近 4096 个 `frameId`（`ProximityEngine.kt:425`，`MAX_TRACKED_FRAME_IDS = 4_096`），超限淘汰最早记录；同一帧间隔超过该窗口后重新投递会被当作独立样本。当前扫描速率下不影响夹具，按 low 记录。
- `BLUETOOTH_SCAN` 的权限归属与 02 重叠：03 已在清单声明，02 接入运行期权限时不得重复声明或改变 `neverForLocation`。
- 本票完成摘要此前未写入 `map.md` 的 `## Decisions-so-far`（low），现随本次记录补入。

## Comments
- claimed at HEAD bfe076aa065b0bbe83f68c35293d9845505e00b2
