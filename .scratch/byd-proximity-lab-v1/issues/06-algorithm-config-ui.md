# 06 实现算法配置管理界面

Type: task
Status: resolved
Blocked by: 05
User stories: 22, 23, 24

## 任务

让用户在算法实验室选 `Baseline` 或 `EMA`，维护具名参数配置并选定下一次模拟测试生效的配置。

**交付物** 可管理并启用配置的界面，以及 10 可复用的配置选择入口。

## 工作内容

1. 在 04 建立的 Room 数据库中增加用户配置和当前选择，保留旧 Session。接入 05 的测试开始流程。列表显示两种内置算法、配置和生效项，默认配置可复制。
2. 提供新建、查看详情、编辑、复制、删除用户配置与选为生效的操作。删除使用明确的目标名称和确认操作，删除当前用户配置时一致地回退到可用默认项。快照不依赖原配置表记录仍存在。
3. 参数表单复用 03 的参数边界校验，将错误对准对应的字段。标明 RSSI 单位、进入/离开阈值、有效帧数或时长、过期条件，以及 EMA 参数。配置名和参数有效后方可保存。
4. 显示修改后的参数从下一次模拟测试生效的说明。监控和回放分别读取 05 的会话快照及所选回放配置，历史 Session 不因编辑表单而变化。

## 操作样例

| 操作 | 应看到的结果 |
| --- | --- |
| 从内置 `EMA` 复制，命名「口袋」，修改平滑参数并保存 | 列表出现用户配置，默认项参数不变。生效项仍是用户原来所选的配置 |
| 将「口袋」设为生效并开始模拟测试，测试中再编辑配置 | 配置页显示新设置。运行中的测试仍使用原快照。结束后开启新测试才使用新设置 |
| 交换进出阈值，或输入无效 EMA 参数 | 对应字段显示原因，保存前的有效配置仍可用 |
| 删除当前用户配置 | 可用的内置默认项生效，仍能查看引用旧快照的 Session |

## 验收与交接

- 通过 UI 创建两个不同参数的配置，复制并编辑其中一份，选择生效后重新打开 App，确认选择与参数均能恢复。
- 测试不合法参数不会保存，删除当前用户配置后默认项可用，且已有 Session 的配置快照能继续查询。核对内置算法一直可选择。
- [ ] 自动化测试通过真实配置存储和 05 的测试开始入口验证当前配置选择、运行中编辑、删除当前配置后的默认值，以及旧 Session 的快照不变。
- Answer 说明编辑入口、各字段的语义、默认配置和生效时点，供 10 复用配置选择逻辑。回放对比界面由 10 实现。

## Answer

### 完成内容

- 在 04 的 Room 数据库上新增用户算法配置与当前生效选择：`ProximityLabDatabase` 升到 version 4，新增 `algorithmConfigDao()` 与 `MIGRATION_3_4`，导出 `app/schemas/com.byd.proximitylab.data.ProximityLabDatabase/4.json`，旧 Session 保留；删除当前生效用户配置时 `AlgorithmConfigDao.deleteConfigAndFallback` 在同一 `@Transaction` 中删除并回退到 `BuiltInAlgorithmConfigs.DEFAULT`（`app/src/main/java/com/byd/proximitylab/algorithmconfig/data/AlgorithmConfigPersistence.kt:151-163`）。
- 内置算法由 `BuiltInAlgorithmConfigs.BASELINE/EMA/ALL/DEFAULT` 固定提供（`AlgorithmConfigModels.kt:56-80`），不写入数据库；可被复制和选为生效，不可编辑或删除，默认项为 `BASELINE`。复制内置或用户配置都会得到新的普通用户配置。
- `AlgorithmLabScreen`/`AlgorithmLabViewModel`（`ui/lab`）展示内置区块、用户配置区块与「当前生效」卡（`lab_selected_config`、`lab_selected_params`、`lab_effective_note`），提供新建、详情、编辑、复制、删除与设为生效；内置项也可选择。删除弹窗显示目标名称「确定删除配置「…」？」并说明回退后果（`AlgorithmLabScreen.kt:599-617`）。表单弹窗 `config_editor` 覆盖配置名、算法、进入/离开阈值、确认方式与数值、样本过期时间、EMA α。
- 参数表单复用 03 的边界：`AlgorithmConfigValidator` 以 `ProximityConfig.RSSI_MIN_DBM..RSSI_MAX_DBM` 校验阈值，要求进入阈值大于离开阈值，校验确认值（连续帧数 ≥ 1 / 时长 > 0 ms）、样本过期时间 > 0 ms、EMA α ∈ (0, 1]，最终仍由 `ProximityConfig` 构造器兜底；错误按 `AlgorithmConfigField` 定位到对应输入框（`AlgorithmConfigValidator.kt:85-173`）。字段语义：阈值为 dBm 且进入 > 离开；确认方式为「连续有效帧数」或「持续时长」；过期条件为毫秒数；EMA α 为 (0, 1]。配置名与参数同时有效才可保存。
- 生效时点：`MonitorViewModel.startSimulationNow()` 先调用 `configStore?.snapshotForNextSimulation()` 生成快照，再交给 `controller.startSimulation(snapshot)`（`MonitorViewModel.kt:38-40`）；快照只保存名称与参数、不引用配置行，运行中编辑/删除只影响下一次测试。界面说明为「修改后的参数从下一次模拟测试生效；运行中的测试和已保存 Session 继续使用开始时的快照。」
- 生产接线：`ProximityLabApplication` 的 `AppContainer` 提供 `algorithmConfigStore`，`AppViewModelProvider.labFactory(store)` 与 `monitorFactory(controller, configStore)` 注入算法实验室与监控页。

### 实际验证

实施与脚本 gate 记录（由实施 Agent 和脚本实际执行，本轮按要求未重跑）：

- `JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL；按 test-results XML 汇总 121 tests、0 failures、0 errors、0 skipped。新增 `AlgorithmConfigValidatorTest` 13、`AlgorithmConfigModelsTest` 5、`AlgorithmLabViewModelTest` 6、`PageViewModelTest` 1 均通过。
- `JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:connectedDebugAndroidTest --console=plain` → 最终全量 BUILD SUCCESSFUL；`TEST-Medium_Phone_API_37.0(AVD) - 17.xml` 为 tests=48、failures=0、errors=0、skipped=1（`AndroidBleScanSourceTest` 真实广告者按既有 Assume 跳过）。其中 `AlgorithmConfigRoomStoreTest` 6、`AlgorithmConfigSimulationIntegrationTest` 2、`AlgorithmLabScreenTest` 3、`ProximityLabDatabaseMigrationTest` 3 全部通过。首次全量出现过 1 个既有 04 `SamplingScreenTest` 语义树 flake（`sampling_session_detail` 未显示）；单独重跑该等价类 2/2 通过，最终全量 48/0 通过。
- `JAVA_HOME="$PWD/.tooling/jdk-21" ./gradlew :app:assembleDebug :app:lintDebug --console=plain` → BUILD SUCCESSFUL；`app/build/reports/lint-results-debug.txt` 为 0 errors、3 warnings（`OldTargetApi`、`ObsoleteSdkInt`、`MonochromeLauncherIcon`，均为既存基线）。
- `"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump badging app/build/outputs/apk/debug/app-debug.apk` → compileSdk 37、minSdk 31、targetSdk 36；权限仅 `BLUETOOTH_SCAN(neverForLocation)` 与系统 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无 `INTERNET`、`BLUETOOTH_CONNECT` 或定位权限。
- `git diff --check` → exit 0（仅 Git 的 LF→CRLF 提示，无空白错误）。
- 脚本 gate：`:app:assembleDebug :app:testDebugUnitTest` exit 0；`:app:connectedDebugAndroidTest` exit 0；APK 检查 min/target/compile = true/true/true、INTERNET=false。

独立复审（由复审 Agent 实际运行）结论：

- 通过：具名配置 CRUD、内置 Baseline/EMA、当前生效项。`AlgorithmConfigStore` 提供 observe/create/update/duplicate/delete/select/snapshot；`AlgorithmLabScreen.kt:82-139、194-288` 展示内置项、用户配置、当前生效卡和操作按钮；Room/UI/集成用例均覆盖。
- 通过：参数表单复用 03 边界校验并字段定位。`AlgorithmConfigValidator` 使用 `ProximityConfig.RSSI_MIN_DBM/RSSI_MAX_DBM` 并在最后用 `ProximityConfig` 构造器兜底，字段错误映射到 `AlgorithmConfigField`；`AlgorithmConfigValidatorTest` 13 项通过，覆盖范围越界、交换/相等阈值、α、计数与时长。
- 通过：运行中编辑只影响下次测试、开始测试固定快照。`MonitorViewModel.startSimulationNow` 先调用 `configStore.snapshotForNextSimulation()` 再进入 `LiveMonitorController.startSimulation(snapshot)`；`AlgorithmConfigSimulationIntegrationTest.kt:84-142` 断言测试中编辑后运行快照仍为旧参数、下一次测试使用新参数。
- 通过：删除当前配置回退默认且旧快照仍可解释。`AlgorithmConfigDao.deleteConfigAndFallback` 在同一 `@Transaction` 中删除并回退；`AlgorithmConfigRoomStoreTest`、`AlgorithmLabScreenTest` 与 `AlgorithmConfigSimulationIntegrationTest.kt:144-177` 断言回退到 `DEFAULT`、内置项仍可选、旧 Session 快照参数不变。
- 行为通过：重开 App 恢复选择与参数。`AlgorithmConfigRoomStoreTest.kt:102-111` 关闭并重开数据库恢复选择/参数；`AlgorithmConfigSimulationIntegrationTest.kt:180-254` 重建 `AppContainer` 后恢复并用于开始测试；UI 用例的重启断言边界见「未测项」的旧提醒。
- 通过：内置算法一直可选择。删除当前用户配置后 `AlgorithmLabScreenTest.kt:268-275` 实际点击 `builtin-ema-v1` 并断言生效；`AlgorithmConfigRoomStoreTest` 也选择内置 EMA。
- 通过：自动化验收（真实配置存储 + 05 测试开始入口）。集成用例使用真实 `RoomAlgorithmConfigStore` 与 `MonitorViewModel`→`LiveMonitorController.startSimulation(snapshot)`，覆盖当前选择、运行中编辑、删除回退、旧快照；connected 结果 XML 中该 suite 为 tests=2、failures=0。
- 通过：仓库约定。AndroidManifest 仅有 `BLUETOOTH_SCAN`（neverForLocation）、无 `INTERNET`；`gradle/libs.versions.toml` 均为固定版本、无动态/预览版本；新增代码无网络访问、无新权限、无 BLE 写入；`git diff --check be81abe6ebcb65c36c8bea366752c4cf9939384e` 退出码 0。
- 复审提醒（low）：重开 App 恢复选择通过关闭并重开 Room 与重建生产 `AppContainer` 验证，不等同于真实进程重启后重开，UI 用例对进程重启的断言边界有限；见「未测项」。

### 公开入口与下游

- `com.byd.proximitylab.algorithmconfig.AlgorithmConfigStore`：`observeConfigs()`、`observeSelectedConfigId()`、`loadConfig(id)`、`loadSelectedConfigId()`、`loadSelectedConfig()`、`createConfig(name, config)`、`updateConfig(id, name, config)`、`duplicateConfig(sourceId, name)`、`deleteConfig(id)`、`selectConfig(id)`、`snapshotForNextSimulation()`、`snapshotIdOf(config)`；生产实例为 `AppContainer.algorithmConfigStore`。
- `com.byd.proximitylab.algorithmconfig.AlgorithmConfig`：`configId`、`name`、`config: ProximityConfig`、`builtIn`、`updatedAtEpochMillis`、`defaultSnapshotId()`、`toSessionSnapshot(snapshotId)`；`BuiltInAlgorithmConfigs.BASELINE/EMA/DEFAULT/ALL`。
- `com.byd.proximitylab.algorithmconfig.data.RoomAlgorithmConfigStore` 与 Room 实体 `AlgorithmConfigEntity`/`AlgorithmSelectionEntity`；`ProximityLabDatabase.version = 4`、`algorithmConfigDao()`、`MIGRATION_3_4`。
- `com.byd.proximitylab.ui.lab.AlgorithmLabViewModel` / `AlgorithmLabScreen`：新建、详情、编辑、复制、删除确认、设为生效；测试标签 `create_config`、`lab_config_<id>`、`detail_config_<id>`、`copy_config_<id>`、`edit_config_<id>`、`delete_config_<id>`、`select_config_<id>`、`lab_selected_config`、`config_editor`、`config_name`、`config_enter_threshold`、`config_exit_threshold`、`config_confirmation_value`、`config_validity_millis`、`config_ema_alpha`、`config_save`、`config_delete_dialog`、`config_delete_confirm`。
- `com.byd.proximitylab.ui.monitor.MonitorViewModel.startSimulationNow()`/`startSimulation()`：从生效配置生成快照并交给 `LiveMonitorController.startSimulation`；`AppViewModelProvider.labFactory(store)`、`monitorFactory(controller, configStore)`。
- `com.byd.proximitylab.algorithmconfig.AlgorithmConfigValidator` / `AlgorithmConfigInput` / `AlgorithmConfigField` / `ConfirmationKind`：可复用配置表单校验与字段错误。10 可用 `loadConfig`、`snapshotIdOf`、`toSessionSnapshot` 复用配置选择。

### 未测项

- 未在真实 Android 12–17 多设备矩阵或真车 BLE 环境运行；connected 仅在 API 37 模拟器 `emulator-5554` 执行。
- 未执行真实 `adb shell am force-stop`/进程 kill 后重开 App；选择恢复通过关闭并重开 Room 与重建生产 `AppContainer` 验证，不等同于实际进程重启（复审保留的 low 提醒：UI 用例的重启断言边界有限）。
- 10 的回放对比界面与「所选回放配置」消费未实现；本票提供 `AlgorithmConfigStore.loadConfig`/`snapshotIdOf`/`toSessionSnapshot` 供 10 复用。
- 首次全量 connected 出现 1 个既有 04 `SamplingScreenTest` 语义树 flake（`sampling_session_detail` 未显示），单独重跑通过，未定位根因。

## Comments

- claimed at HEAD be81abe6ebcb65c36c8bea366752c4cf9939384e
