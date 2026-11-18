# 01 固定 Android 构建基线并启动四页工程

Type: task
Status: blocked
User stories: 37

## 目标与范围

从初始工作树建立可构建、可安装的 Compose / Material 3 工程。交付 Gradle Wrapper、Version Catalog、可运行的调试 APK、四个可导航的空状态页面、后续票复用的构建和测试入口。

## 实施步骤

1. 按 [Android 技术基线](../android-toolchain.md) 固定全部版本。SDK 为 `minSdk = 31`、`compileSdk = 37`、`targetSdk = 36`。配置 Gradle Kotlin DSL、Wrapper 及其校验值、Version Catalog 和正式制品仓库。Gradle JDK 用 21，Java/Kotlin 字节码目标用 17。
2. Android 模块保留 AGP 内置 Kotlin。用同一个目录版本固定有效 KGP 和 Compose Compiler，按基线接入 KSP2。建立 Compose BOM、Activity、Lifecycle、Coroutines 和测试依赖的目录条目。Room/KSP 用兼容性探针验证，正式 Room 数据库由 04 落地。
3. 提供设备、实时监控、采样、算法实验室四个导航入口。用 Material 3 和适当的窗口 Insets，后续票沿用页面入口。界面通过 ViewModel/StateFlow 读状态，使用普通构造器接线。纯 Kotlin 判定可以先放在同一模块中的独立包。
4. 固定构建入口和必要的开发约定。记录使用的 SDK 包、JDK、Wrapper 命令和输出目录。本机 SDK 路径、代理参数和构建产物保持本机配置。后续各票按需接入 BLE 与通知权限。

## 验收

- [ ] 用 Wrapper 真实运行 `--version`、`buildEnvironment`、`:app:assembleDebug`、`:app:testDebugUnitTest` 和 `:app:lintDebug`。记录有效 Gradle/JDK、KGP、Compose 插件和实际依赖解析结果。在初始化验证中编译最小 Compose 页面，并通过临时或测试专用 Room 探针运行实际 KSP 生成任务。若探针放在测试源集，记录对应变体任务和生成物。
- [ ] 从合并 Manifest 和调试 APK 核对实际 min/target SDK，结合 Gradle 配置或构建元数据核对 compile SDK。确认无 `INTERNET` 和超出本票需求的权限，依赖树无预览版及动态版本。
- [ ] 使用可用设备或模拟器启动 APK 并走通四页、返回和基础 Insets。无设备时仅这部分记为待测。Wrapper/编译/依赖解析失败应写明真实失败点，不能用遗留 APK 代替验证。
- [ ] Answer 记录实现位置、正常 App 入口、可用命令、产物、测试入口和仍待测试的设备行为。后续票直接使用这一套入口。

## Answer

### 阻塞

票据 01 未完成，不能标记 resolved。构建/测试 gate 的失败结果为：

- APK 检查：min/target/compile = true/true/true，`INTERNET` = false，静态清单与 SDK 核对通过。
- 安装并启动 `com.byd.proximitylab/com.byd.proximitylab.MainActivity` 后，`pidof com.byd.proximitylab` 输出为空，应用进程未存活。

失败点是启动后进程未存活，因此验收第三条（设备/模拟器走通四页、返回和基础 Insets）无法执行，票据停在 `blocked`。

### 已尝试的恢复

未记录到已实施且有效的恢复动作；本节不虚构尝试历史。目前仅有的依据是上述 gate 输出：安装与启动命令已执行，失败发生在运行期存活，而非构建或安装阶段。后续应按下列顺序取证后再修复：

1. 用 `adb logcat -d`（`crash`/`main` buffer，或按 pid 过滤）取启动即退出的 `FATAL EXCEPTION` 与崩溃栈。
2. 核对合并 Manifest 的 launcher Activity、主题和 Application 初始化是否与 `MainActivity` 对齐。
3. 在模拟器上复现，区分设备侧产物问题与运行期初始化问题。

以上是排障方向，不是已完成的操作记录。

### 解除条件

- 修复后重新执行 gate：安装并启动应用后 `pidof com.byd.proximitylab` 能返回存活进程。
- 用 Wrapper 重跑 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` 并记录真实结果。
- 在设备/模拟器走通四页、返回与基础 Insets，补全验收所需的 Answer 后再将 `Status` 置为 `resolved`。

### WIP 分支与提交

- 分支：`wip/01-blocked`，从 `codex/byd-proximity-lab-v1` 的 `06419cd8a3b9f4c69018e647bfc4c200b8633e18` 创建。
- 提交：`9ef0ce292959f73f30c090fc3b112fa983169f9e`（`wip(01): 未完成`）。
- 内容：Gradle Wrapper 与 Version Catalog、`settings.gradle.kts`、根与 `app` 构建脚本、四页 Compose 骨架、测试与 Room 探针、`/.tooling/` 忽略规则。
- `.tooling/` 是本地 JDK 21 与 Gradle 9.9.1（实测约 476M），按约定不入库，已在 `.git/info/exclude` 本地忽略。

## Comments

- claimed at HEAD 06419cd8a3b9f4c69018e647bfc4c200b8633e18
