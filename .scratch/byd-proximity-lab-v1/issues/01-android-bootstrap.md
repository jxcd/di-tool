# 01 固定 Android 构建基线并启动四页工程

Type: task
Status: resolved
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

### 完成内容

从初始工作树建立可构建、可安装的 Compose / Material 3 工程：Gradle Wrapper 9.9.1、Version Catalog、AGP 9.5.1 内置 Kotlin、Kotlin/Compose Compiler 2.4.21、KSP2 2.3.14；minSdk 31、compileSdk 37、targetSdk 36；JDK 21 位于工作区 .tooling/jdk-21；设备、实时监控、采样、算法实验室四个导航入口使用 Material 3、WindowInsets、ViewModel/StateFlow 和普通构造器接线；Room 兼容性探针由 KSP 实际生成。

### 实际验证

- Wrapper：.tooling/jdk-21/bin/java.exe ... GradleWrapperMain --version → Gradle 9.9.1，Launcher/Daemon JVM 21.0.13（Eclipse Adoptium），Daemon Location .tooling/jdk-21。
- :app:buildEnvironment → 实际解析 com.android.tools.build:gradle:9.5.1、org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21、com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.14、org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21。
- :app:assembleDebug :app:testDebugUnitTest :app:lintDebug → BUILD SUCCESSFUL；JVM 测试 5 条 0 失败；lint 0 errors、3 warnings。
- :app:connectedDebugAndroidTest → BUILD SUCCESSFUL；emulator-5554（API 37）3 tests、0 failures，覆盖四页导航/返回与真实 Room 探针。
- KSP 生成物：app/build/generated/ksp/debug/kotlin/com/byd/proximitylab/probe/BootstrapProbeDao_Impl.kt、BootstrapProbeDatabase_Impl.kt。
- APK：aapt2 dump badging → package com.byd.proximitylab，compileSdkVersion 37、minSdkVersion 31、targetSdkVersion 36；权限仅 com.byd.proximitylab.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，无 INTERNET；合并 Manifest 同样为 minSdk 31 / targetSdk 36。
- 安装与启动：adb shell am force-stop → adb install -r → Success；adb shell am start -W -n com.byd.proximitylab/com.byd.proximitylab.MainActivity → Status: ok，LaunchState: COLD，TotalTime 3717；随后 pidof 返回 10537。实施节点另以同一序列连续复跑 5/5 次 COLD 启动且 pid 非空。
- 依赖解析：debugRuntimeClasspath 与 debugAndroidTestRuntimeClasspath 均无 alpha/beta/rc/SNAPSHOT 或动态版本；Compose UI 1.12.1、Material3 1.4.0、activity-compose 1.13.0、lifecycle 2.11.0、navigation 2.10.2、Room 2.8.5、coroutines 1.11.0。
- Wrapper 校验：gradle-9.9.1-bin.zip SHA-256 为 d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228，与官方 .sha256 一致；distributionSha256Sum 已写入。
- .tooling/、local.properties、.temp/ 均由 .gitignore 或本地排除覆盖，未进入提交。

### 公开入口与下游

- App 入口：com.byd.proximitylab.MainActivity → ProximityLabApp；四个页面由 ui/navigation/LabDestination.kt 定义，页面 ViewModel 位于 ui/device、ui/monitor、ui/sampling、ui/lab。
- 构建：gradlew / gradlew.bat（Wrapper 9.9.1）；Gradle JVM 21；命令行任务 :app:assembleDebug、:app:testDebugUnitTest、:app:lintDebug、:app:connectedDebugAndroidTest。
- 测试：app/src/test（JVM）与 app/src/androidTest（Compose 导航 + Room 探针）。
- Room/KSP 探针：com.byd.proximitylab.probe.BootstrapRoomProbe；04 将在此基础上落地正式数据库。

### 未测项

- Android 12（API 31）真机/模拟器：当前仅在 API 37 模拟器验证；需现场或补设备。
- 真实 BLE 广播、锁屏、通知与前台服务：由 02、11、13、14 继续。
- Lint 仍有 3 条 warning，未作为本票失败项。

### 阻塞与恢复说明

首次脚本 gate 在 am start 后立即执行 pidof，未等待进程出现，误判为“应用进程未存活”，票据被置为 blocked 并保存到 wip/01-blocked（9ef0ce2）。复核确认这是 gate 的竞态：am start 返回后约 100ms 进程才出现，logcat 无 FATAL/ANR；改为先 force-stop、安装后 COLD 启动并轮询 pidof，复验通过。wip 分支保留为历史记录，代码已恢复到实施分支并随本票提交。


## Comments

- claimed at HEAD 06419cd8a3b9f4c69018e647bfc4c200b8633e18
- 2026-11-18：首次 gate 的 pidof 竞态导致误判阻塞；修正 gate 后重新验证并提交，wip/01-blocked 仅保留为历史记录。
