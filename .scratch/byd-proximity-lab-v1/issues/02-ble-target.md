# 02 实现 BLE 发现、人工绑定和目标关联

Type: task
Status: resolved
Blocked by: 01
User stories: 1, 2, 3, 4, 5, 6, 7

## 任务

从设备页面完成通用 BLE 扫描及首次人工绑定，为实时与采样提供可订阅的目标观测和扫描诊断。设计以一个当前绑定目标为单位。

**交付物** 可人工确认目标的设备页，以及包含目标身份、扫描状态和原始观测的采集入口。05 直接接入该入口。

## 工作内容

1. 使用标准 Android BLE 扫描读取广播及扫描响应。API 31 起仅申请 `BLUETOOTH_SCAN`（`neverForLocation`）。名称从广播/扫描响应读取，避开要求 `BLUETOOTH_CONNECT` 的调用。处理蓝牙关闭、权限缺失或撤销、启动错误与停止。设备页提供扫描开始和停止、空结果提示与最近观测时间。应用自己的扫描生命周期应能随用户操作结束。
2. 显示设备名、RSSI、可取得的地址、Service UUID 和 Manufacturer Data。比亚迪提示作为候选分类，未命中提示的通用设备仍可选择。设备字段为空时按未知显示。
3. 人工选择并确认后保存一个绑定目标。为目标提供 App 内稳定标识，广播地址作为当前观测属性。观察到新地址时，只在存在经验证且能唯一关联原目标的证据时延续关联。将判定依据交给诊断展示。报告中的品牌 UUID、厂商类型、名称、RSSI 强弱和未实测的载荷前缀均不能作为本车唯一证据。
4. 地址变化或相似候选出现且无法唯一确认时保留原绑定，输出身份不确定。提供重新人工确认入口。扫描请求状态与目标发现情况分别输出，即使持续没有回调也不能据此断言车辆没有广播。
5. 向 05 提供原始 RSSI、观测时刻、App 收到时刻、可用于识别重复投递的观测信息，以及扫描/身份状态变化。05 负责对接 03 的判定入口。04、05 和 11 复用同一来源。锁屏扫描及前台服务由 11 接入。

## 目标关联判定表

| 看到的情况 | 对绑定做什么 | 交给判定入口 |
| --- | --- | --- |
| 已绑定的 A 正常出现，旁边 B 更强或同品牌 | 保持 A | 仅接受 A 的有效观测 |
| 在缺少可信关联证据时收到新地址 B | 保持原绑定，展示待确认候选 | B 不进入接近判断。已确认 A 仍可观测时继续使用 A |
| A 消失，出现疑似轮换地址的候选，但缺乏唯一证据或多个候选冲突 | 保持原绑定，提示重新确认 | 有身份歧义时输出身份不确定。只缺观测时单独报告目标未见，并由判定过期处理 |
| 经验证的证据只匹配一个新地址，且无冲突 | 将新地址关联至原目标 | 使用同一个目标标识，记录关联依据 |
| 用户明确确认当前候选是原设备，或明确改绑 | 前者延续原目标标识，后者建立当前新目标 | 记录确认或改绑动作。失去确认期间的旧触发资格由 03/05 清除 |

同一观测重复投递应能被 03 识别。收到时间晚、RSSI 相同、地址轮换等情况不能只凭 RSSI 值判断是否重复。只展示系统提供的扫描和候选事实，保留对无法观测原因的不确定性。

## 验收与交接

- 向真实设备页/发现流程送入测试扫描结果，验证通用设备可发现，名称缺失可展示。两个同品牌候选同时存在、另一候选 RSSI 更强时，原绑定保持。重新打开 App 后能查询已保存绑定。
- 验证身份冲突或旧地址消失且新地址无可靠证据时，报告不确定。若实现了基于可复验证据的地址延续，覆盖唯一匹配、两个候选同时匹配与证据消失。实车唯一特征缺失时采用保守分支，注明待 14 验证。
- 验证蓝牙关闭、权限缺失及无目标样本三种情况各有可辨识状态。有设备时做一次只读扫描。Answer 写明绑定和观测读取入口、可用关联策略及人工恢复路径。全过程无配对、连接、GATT 或车辆指令。


## 自动化验收

- [x] 从设备页驱动可控扫描来源，覆盖空字段、通用目标、品牌提示、权限拒绝或失效、蓝牙关闭、扫描错误和重新开始。检查扫描可用性与目标未见有不同输出。
- [x] 在真实关联逻辑验证表中保守分支。用本地持久化入口复验重新进入后能读取绑定。一次人工改绑产生新的目标标识，重新确认原目标保留标识。真实车地址轮换的唯一依据仍留给 14。

## Answer

### 阻塞

复审未通过，本票未完成，不能标记 resolved。阻塞项：

1. `app/src/main/java/com/byd/proximitylab/target/TargetMonitor.kt:98-129,167-202,204-213`（状态复制见 `:370-386`）：绑定变化（清绑后重新 bind、rebindTo、以及从 store 载入新绑定）没有清空 per-target 观测状态，`targetObservationCount` / `lastTargetObservation` / `targetObservedInCurrentScan` 仍属于上一个目标；改绑后 UI 与 05 读取的 `TargetMonitorState` 会把旧目标观测显示或提供给新目标。
2. `app/src/main/java/com/byd/proximitylab/target/TargetMonitor.kt:220-235`；`app/src/main/java/com/byd/proximitylab/target/TargetBinding.kt:111-121`；`app/src/main/java/com/byd/proximitylab/ui/device/DeviceScreen.kt:517-521`：每次进入 Scanning（包括用户停止后重新开始、蓝牙或权限恢复后重试）都会把身份报告无条件替换为 `TargetIdentityReport.persisted(status=CONFIRMED)` 并清空 candidates 与待确认候选；已出现的 UNCERTAIN（身份冲突）在没有新观测或人工重新确认的情况下被清除，UI 重新显示“身份已确认”。
3. `app/src/main/java/com/byd/proximitylab/ui/device/DeviceScreen.kt:587-601`；`app/src/androidTest/java/com/byd/proximitylab/ui/device/DeviceScreenTest.kt:106-107`：Service UUID 或 Manufacturer Data 为空时 UI 显示“无”，测试也断言“无”；票据要求设备字段为空时按未知显示，空列表或缺失 ScanRecord 不等于设备明确没有这些字段。
4. `.scratch/byd-proximity-lab-v1/issues/02-ble-target.md:4,38,43-44`：票据仍为 `Status: claimed`，没有 `## Answer`，自动化验收复选框未勾选；票据要求 Answer 写明绑定与观测读取入口、关联策略和人工恢复路径。
5. `app/src/androidTest/java/com/byd/proximitylab/ble/AndroidBleScanSourceTest.kt:56-89`；`app/src/main/java/com/byd/proximitylab/ble/AndroidBleScanSource.kt:207-224`：真实 `AndroidBleScanSource` 测试只断言 Scanning 与 Stopped，观测列表只 println 不参与断言；实际日志观测数为 0，`ScanResult`→`BleAdvertisement` 的名称读取、RSSI、地址、UUID、Manufacturer Data、时间字段与 fingerprint 未被真实广播验证；该测试还硬依赖 BLE adapter，缺少 Assume/skip。
6. `app/src/test/java/com/byd/proximitylab/testing/TestFixtures.kt:73-97`；`app/src/androidTest/java/com/byd/proximitylab/testing/DeviceTestFixtures.kt:48-72`：test 与 androidTest 各有一份同名 `ControllableBleScanSource` / `testAdvertisement` / `testBinding`，默认值和行为（如 `observedAtEpochMillis` 固定值 vs `System.currentTimeMillis()`）已分叉，后续修改容易只改一边导致测试语义不一致。记录本票阻塞时，上述两个路径已不存在，夹具集中在 `app/src/sharedTest/java/com/byd/proximitylab/testing/TestFixtures.kt` 并由 `app/build.gradle.kts` 的 sourceSets 同时挂到 test 与 androidTest，需在解除阻塞前复验该项。
7. `app/src/main/java/com/byd/proximitylab/target/data/TargetBindingDatabase.kt:75-86`：02 新增独立 `target-binding.db` / `TargetBindingDatabase`；04 计划建立“唯一 Room 存储”并让后续切片“沿用同一数据库”。02 的持久化要求本身成立，但 04 前需要明确复用或迁移，否则会出现两个 Room 数据库。

### 已尝试的恢复

- 已按票据完成初版实现（BLE 扫描、人工绑定、目标关联、设备页与测试），并提交复审。
- 复审未通过后未进行针对性代码修复：本次仅按指示记录阻塞并保留未完成实现，除票据文档外未修改任何实现文件，未勾选自动化验收复选框，不声称任何未运行的验证已通过。
- 未运行 Gradle 构建或测试；阻塞判定依据为复审给出的代码位置与测试证据。

### 解除条件

1. 绑定变化（清绑后重新 bind、`rebindTo`、从 store 载入新绑定）时清空 per-target 观测状态，使 `TargetMonitorState` 不再携带上一个目标的 `targetObservationCount` / `lastTargetObservation` / `targetObservedInCurrentScan`，并补充对应单元测试。
2. 扫描重新开始或重试不再无条件把身份报告重置为 `persisted(CONFIRMED)`；保留已记录的 UNCERTAIN 与待确认候选，仅在出现新观测或用户人工重新确认后更新身份状态，并补充“停止后重新开始”“蓝牙/权限恢复后重试”用例。
3. Service UUID 与 Manufacturer Data 缺失时按“未知”展示，测试断言改为未知语义，区分“空列表或缺失 ScanRecord”与“设备明确没有该字段”。
4. `AndroidBleScanSourceTest` 对真实扫描解析结果做断言（名称、RSSI、地址、UUID、Manufacturer Data、时间字段与 fingerprint），无法提供 BLE adapter 时使用 Assume/skip 跳过而不是失败；必要时补充可控广播夹具。
5. 确认 test/androidTest 夹具只保留一份共享实现（记录时工作树已迁移到 `app/src/sharedTest/`），并复验两侧默认值一致。
6. 明确 `target-binding.db` 与 04“唯一 Room 存储”的复用或迁移方案，避免留下两个 Room 数据库。
7. 重新执行票据“验收与交接”和“自动化验收”，勾选复选框，并在 Answer 写明绑定与观测读取入口、可用关联策略及人工恢复路径。

### WIP 分支与提交

- 分支：`wip/02-blocked`
- 提交：`ea57830bc3bee2613dd64623e807790eaf637891`（`wip(02): 未完成`）
- 内容：票据 02 当前的代码与测试改动，不含票据文档；实施分支 `codex/byd-proximity-lab-v1` 只保留本票据文档的阻塞记录提交。

### 阻塞（resume-2）

本轮实施结果返回“未完成，不能标记 resolved”；阻塞在票据收尾，不在代码缺陷：

- 本轮开始时票据仍为 `Status: claimed`，`## Answer` 仍是上一轮阻塞记录；实施者按约定未修改 `Status` 与 `## Answer`，只按实际验证勾选了“自动化验收”复选框。
- 截至本次记录，`## Answer` 尚未按“验收与交接”要求写明绑定与观测读取入口、可用关联策略和人工恢复路径；补齐前本票不能标记 resolved。
- 本次仅记录阻塞并保存未完成代码，未逐项复审全部实现，也不声称任何未运行的验证已通过。

### 已尝试的恢复（resume-2）

- 本轮实施者完成票据 02 的代码与测试改动，工作树改动保存到 WIP 分支 `wip/02-blocked-2`，提交 `0915b048db77817aee177a5682e3bcddc87fb820`（29 个文件，+4139/−24，不含票据文档）。
- 本次核对过的代码位置：绑定变化（从 store 载入、bind、reconfirm、rebind、clear）会清空 per-target 观测状态，见 `app/src/main/java/com/byd/proximitylab/target/TargetMonitor.kt:61-74,116,149,187,215`；扫描重新开始时保留已记录的 UNCERTAIN 与待确认候选，见同文件 `:233-245`；Room 存储统一为 `app/src/main/java/com/byd/proximitylab/data/ProximityLabDatabase.kt`，绑定表、DAO 与存储适配位于 `app/src/main/java/com/byd/proximitylab/target/data/TargetBindingPersistence.kt`，原独立 `TargetBindingDatabase.kt` 已移除。
- 构建产物中的验证结果（本次票据管理员未重跑 Gradle，结果读自 `app/build`）：
  - 单元测试 `app/build/test-results/testDebugUnitTest/`：`TargetMonitorTest` 12 tests/0 failures、`TargetAssociationPolicyTest` 9/0、`BleAdvertisementMapperTest` 3/0，时间戳 2026-11-18T15:30Z。
  - 仪器测试 `app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone_API_37.0(AVD) - 17.xml`：5 个测试类共 13 tests、0 failures、0 errors、0 skipped，`test-result-exit-code.txt` 为 `0`，时间戳 2026-11-18T15:39:33。
- 未运行/未覆盖：本轮未执行 Gradle 构建或测试；未在真实车辆或真实 BLE 设备上复测，实车唯一身份特征仍待 14。

### 解除条件（resume-2）

1. 在 `## Answer` 写明绑定与观测读取入口（`TargetObservationSource` 的 `state`/`events`/`startScan`/`stopScan` 与 `TargetBindingStore`）、可用关联策略（`TargetAssociationPolicy`：地址匹配、原绑定地址仍在候选、可复验身份 token 唯一匹配；默认 `NoVerifiedIdentityEvidence`，品牌、名称、厂商 ID、RSSI 与未实测载荷前缀不作为唯一证据）及人工恢复路径（设备页重新确认、改绑、解除绑定）。
2. 复核“自动化验收”与“验收与交接”结果，并注明实车唯一特征待 14 时采用保守分支。
3. 完成后将 `Status` 改为 `resolved`。

### WIP 分支与提交（resume-2）

- 分支：`wip/02-blocked-2`（基于 `544881080943321ae4ccb994532de0e824f51d3b`）
- 提交：`0915b048db77817aee177a5682e3bcddc87fb820`（`wip(02): 未完成`）
- 内容：票据 02 的代码与测试改动，不含票据文档；实施分支 `codex/byd-proximity-lab-v1` 只保留本票据文档提交。

### 阻塞（resume-3）

复审后 gate 仍未通过，本票不能标记 resolved。运行 `.tooling/jdk-21/bin/java.exe … :app:connectedDebugAndroidTest` 的结果为 exit 1（构建阶段任务均为 UP-TO-DATE，失败发生在仪器测试执行阶段）。阻塞项：

1. `.scratch/byd-proximity-lab-v1/issues/02-ble-target.md:4,46-109`：票据收尾未完成。`Status` 仍为 `claimed`，`## Answer` 仍是上一轮阻塞与 resume-2 记录，未追加本轮完成内容和当前 gate 结果，未形成最终交接。
2. `app/src/androidTest/java/com/byd/proximitylab/ble/AndroidBleScanSourceTest.kt:124-208`：真实扫描测试在本次 gate 中观测数为 0，`observations.forEach`（`:207-219`）的断言循环未执行；`ScanResult.toAdvertisement` 的实际回调字段路径仍未被真实广播端到端验证。该用例已有 adapter/蓝牙开关的 Assume 跳过（`:163-165`），但“有适配器、无广播”时仍会以 0 观测通过状态断言。
3. `app/src/main/java/com/byd/proximitylab/target/TargetMonitor.kt:233-247`：重新开始扫描时，`:229` 已 `candidates.clear()`，但 `preservedUncertainty`（`:237-242`）按 `status/targetId/address` 保留整个 UNCERTAIN 报告并在 `:246` 原样复制，`pendingCandidateAddresses` 与 `evidenceDescription`（字段定义见 `app/src/main/java/com/byd/proximitylab/target/TargetBinding.kt:86-94`）仍来自上一会话。UI 可能显示已不在当前候选列表中的待确认候选，以及 `BOUND_ADDRESS_RECENTLY_OBSERVED` / `VERIFIED_IDENTITY_TOKEN` 等过期匹配依据。

### 已尝试的恢复（resume-3）

- 本轮未修改任何实现代码，只按指示记录阻塞状态与阻塞说明。
- 工作树中本票的 29 个实现/测试文件改动（均在 `app/` 下，+4369/−24）已保存到 WIP 分支 `wip/02-blocked-3`，提交 `3063e7fe11547efeee2624e5928c8219c532cebb`（`wip(02): 未完成`），不含票据文档。
- 实施分支 `codex/byd-proximity-lab-v1` 只保留本票据文档的阻塞记录提交。
- 本轮未运行 Gradle 构建或测试；gate 失败结论读自复审输入，未在本次会话中复跑。

### 解除条件（resume-3）

1. 修复 `TargetMonitor.kt:223-250` 的重新扫描身份报告保留逻辑：保留 UNCERTAIN 时同步清理 `pendingCandidateAddresses` 与过期 `evidenceDescription`，使报告与已清空的候选集合一致；补充“停止后重新开始”“蓝牙/权限恢复后重试”用例。
2. 调整 `AndroidBleScanSourceTest` 的真实扫描用例：观测数为 0 时明确 skip 或单独报告，不把未执行的循环算作通过；能收到广播时对 `ScanResult.toAdvertisement` 的名称、RSSI、地址、UUID、Manufacturer Data、时间字段与 fingerprint 做真实断言。
3. 补齐 `## Answer` 的绑定与观测读取入口、可用关联策略、人工恢复路径和实车唯一特征待 14 的保守分支说明；复跑 `:app:connectedDebugAndroidTest` 与单元测试，把实际命令与结果写入 Answer。
4. 全部通过后将 `Status` 改为 `resolved`。

### WIP 分支与提交（resume-3）

- 分支：`wip/02-blocked-3`（基于 `9983fec61a602bd94e48db32b5893ae082336f64`）
- 提交：`3063e7fe11547efeee2624e5928c8219c532cebb`（`wip(02): 未完成`）
- 内容：`app/` 下 29 个文件的实现与测试改动，+4369/−24，不含票据文档。


### 恢复后完成

本轮从 `wip/02-blocked-3` 恢复代码，按上一轮复审的 7 项阻塞逐项修复，并由独立复审员复核通过（approved=true；仅 low 项已处理或记录）。

#### 完成内容

- 只读 BLE 扫描：`BLUETOOTH_SCAN`（`neverForLocation`），名称从扫描记录读取，无 `BLUETOOTH_CONNECT`、配对、连接或 GATT；提供扫描状态、目标观测、候选与设备页入口。
- 单绑定目标与身份关联：`TargetAssociationPolicy` 支持地址匹配、原绑定地址仍在最近候选、可复验 token 唯一匹配；默认 `NoVerifiedIdentityEvidence`，品牌、名称、厂商 ID、RSSI 与未实测载荷前缀不作为唯一证据；身份冲突保留原绑定并输出 `UNCERTAIN`；提供人工重新确认、改绑、解除绑定。
- 绑定持久化统一到 `ProximityLabDatabase`（`proximity-lab.db`）的 TargetBinding 表/DAO；04 沿用该数据库，不再保留独立 `target-binding.db`。
- 设备页展示名称、RSSI、地址、Service UUID、Manufacturer Data（空字段显示“未知”）、最近发现时间、扫描/身份状态、候选冲突与恢复入口。
- `TargetMonitor` 在 bind/reconfirm/rebind/clear/store reload/地址延续时重置 per-target 观测状态；扫描重启、蓝牙或权限恢复保留 `UNCERTAIN`，但清空过期 `pendingCandidateAddresses` 与 `evidenceDescription`/`uncertainty.detail`。

#### 实际验证

- `JAVA_HOME=$PWD/.tooling/jdk-21 ... :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --rerun-tasks` → BUILD SUCCESSFUL；JVM 67 tests、0 failures、0 skipped；lint 0 errors、3 warnings（OldTargetApi、ObsoleteSdkInt、MonochromeLauncherIcon，均为 01 既有基线）。
- `... :app:connectedDebugAndroidTest --rerun-tasks`（emulator-5554/API 37）→ BUILD SUCCESSFUL；15 tests、0 failures、0 errors、1 skipped（真实广告者字段用例在无 BLE 广告者时 Assume 跳过）；`test-result-exit-code` 为 0。
- `aapt2 dump badging/permissions` → compileSdk 37、minSdk 31、targetSdk 36；仅 `BLUETOOTH_SCAN(neverForLocation)` 与系统 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；无 `INTERNET`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`、`BLUETOOTH_ADMIN` 或定位权限。
- `rg` 检查 app/src/main、test、androidTest、sharedTest → 无 `createBond`/`pairDevice`/`connectGatt`/`BluetoothGatt`/`BluetoothSocket`/`BLUETOOTH_CONNECT`/`INTERNET` 调用；仅说明性注释。
- 真实 `ScanRecord` 解析：`realScanRecordIsParsedIntoAdvertisementFields` 使用平台 `ScanRecord.parseFromBytes` 断言名称、RSSI、地址、UUID、Manufacturer Data、观测/接收时间与 fingerprint；`missingScanRecordKeepsFieldsUnknown...` 断言缺失字段为未知。
- 只读扫描生命周期：`readOnlyScanStartsAndStopsOnAvailableAdapter` 在 API 37 模拟器真实执行 Idle→Scanning→Stopped→Scanning→Stopped。
- 夹具：`app/src/sharedTest/java/com/byd/proximitylab/testing/TestFixtures.kt` 为唯一实现，`app/build.gradle.kts:56-66` 同时挂到 test 与 androidTest。
- 本轮额外修复：扫描重启保留 `UNCERTAIN` 时同步清理 `uncertainty.detail`；`publishIdentity` 的变化判定纳入 `pendingCandidateAddresses`/`evidenceDescription`/`uncertainty.detail`，确保只订阅事件的消费者也能看到清理。

#### 7 项阻塞关闭情况

1. per-target 观测状态：bind/reconfirm/rebind/clear/store reload/地址延续均调用重置；`TargetMonitorTest` 15 tests（含 `scanRestartDropsConfirmedSessionEvidenceAndPendingCandidates`、`storeReloadOfNewBindingResetsTargetObservationState`）0 failures。
2. 扫描重启身份报告：保留 `UNCERTAIN`（targetId/bindingAddress 一致），清理 `pendingCandidateAddresses`/`evidenceDescription`/`uncertainty.detail`；`CONFIRMED` 回落 `PERSISTED_BINDING`；`TargetMonitorTest` 与 `DeviceScreenTest` 覆盖停止重启、蓝牙恢复、权限恢复。
3. 空字段未知：`DeviceScreen` 对 Service UUID、Manufacturer Data、名称为空显示“未知/名称未知”；`DeviceScreenTest` 断言未知语义。
4. 票据收尾：本节 + `Status: resolved` + `map.md` 更新。
5. 真实扫描测试：生命周期真实执行；字段断言独立用例在无广告者时显式 `Assume` skip；真实广播端到端字段值待 13/14。
6. 夹具唯一：sharedTest 单份，sourceSets 同时挂载。
7. 统一 Room：`ProximityLabDatabase`（`proximity-lab.db`）为唯一生产数据库；`TargetBindingPersistence` 复用；无 `TargetBindingDatabase`。

#### 公开入口与下游

- `TargetObservationSource`：`state`/`events`/`startScan`/`stopScan`；`TargetBindingStore`；`TargetAssociationPolicy`；设备页人工恢复入口（重新确认/改绑/解除绑定）。
- 05 接入 `state`/`events`，使用 `TargetObservation` 与 `TargetIdentityReport`；04 复用 `ProximityLabDatabase` 的 TargetBinding 表并新增 Session 表。
- 身份策略默认 `NoVerifiedIdentityEvidence`；品牌、名称、厂商 ID、RSSI、未实测载荷前缀不作为唯一证据；实车唯一特征由 14 验证。

#### 未测项

- 真实 BLE 广告者的端到端字段值：本机无广告者，待 13/14；解析器字段映射已由真实 `ScanRecord` 测试覆盖。
- 目标车地址轮换的唯一关联证据、锁车休眠广播、相邻同款车干扰：待 14。
- Android 12（API 31）设备：当前仅 API 37 模拟器。
- 锁屏/前台服务通知：由 11/13/14。

## Comments
- claimed at HEAD e734234601e9aac37b36ee7d67d18994827cd69d
- resumed from wip/02-blocked at 544881080943321ae4ccb994532de0e824f51d3b
- claimed at HEAD 544881080943321ae4ccb994532de0e824f51d3b
- resumed from wip/02-blocked-2 at 9983fec61a602bd94e48db32b5893ae082336f64
- claimed at HEAD 9983fec61a602bd94e48db32b5893ae082336f64
