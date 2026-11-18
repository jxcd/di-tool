# Android 工具链选型依据

核对日期为 2026-11-18。建议锁定 AGP `9.5.1`、Gradle `9.9.1`、JDK `21`、Kotlin `2.4.21`、Compose Compiler `2.4.21` 和 KSP2 `2.3.14`。

| 项目 | V1 锁定值 | 官方依据 |
| --- | --- | --- |
| Android SDK | `compileSdk = 37`、`targetSdk = 36`、`minSdk = 31` | Android 17 于 2026-06-16 正式发布，编译使用基础 API 37。SDK Manager 稳定频道包为 `platforms;android-37.0`（revision 2）。按 V1 范围保留 Android 16（API 36）目标行为，最低安装版本为 Android 12（API 31）。 [Android 17 发布公告](https://developer.android.com/blog/posts/android-17-is-here) · [Android 17 SDK 配置](https://developer.android.com/about/versions/17/setup-sdk) · [SDK 仓库清单](https://dl.google.com/android/repository/repository2-3.xml) · [Android API 级别](https://developer.android.com/guide/topics/manifest/uses-sdk-element#ApiLevels) |
| Android Gradle Plugin | `com.android.application` `9.5.1` | Google Maven 清单截至 11-16 的最高无预发布后缀版本为 `9.5.1`，对应 Android Studio Rabbit 2 Patch 1。`9.4.1` 是 9.4 系列最后一个稳定补丁。AGP 9.5 官方表列出最高 API 37、最低及默认 Gradle `9.6.0`、Build Tools `36.0.0` 和 JDK `17`。SDK 安装指引另列 Build Tools 37，清单中稳定包为 `build-tools;37.0.0`。构建配置可沿用 AGP 的 Build Tools 默认值。 [Google Maven 元数据](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml) · [AGP 9.5 发布说明](https://developer.android.com/build/releases/agp-9-5-0-release-notes) · [Rabbit 2 Patch 1](https://developer.android.com/studio/releases/fixed-bugs/studio/2026.2.2) |
| Gradle Wrapper / JDK | Gradle `9.9.1`，Gradle JVM 使用 JDK `21`，Java/Kotlin 字节码目标 `17` | `9.9.1` 于 11-10 发布，当前稳定版，含安全修复。`9.6.1` 是 AGP 默认版本所在 9.6 系列的最后一个补丁。Gradle 9.9.1 支持用 JDK 21 运行，Java/Kotlin 字节码目标由项目单独配置。 [Gradle 稳定版 API](https://services.gradle.org/versions/current) · [Gradle 9.9.1 发布说明](https://docs.gradle.org/9.9.1/release-notes.html) · [Gradle 9.6.1 发布说明](https://docs.gradle.org/9.6.1/release-notes.html) · [Gradle 兼容表](https://docs.gradle.org/9.9.1/userguide/compatibility.html) · [Android 构建的 Java 版本](https://developer.android.com/build/jdks) |
| Kotlin / Compose Compiler | AGP 内置 Kotlin，显式将 KGP 提升至 `2.4.21`。Compose 模块应用 `org.jetbrains.kotlin.plugin.compose` `2.4.21` | AGP `9.5.1` 和 `9.4.1` 的 POM 与 Gradle 模块均声明 KGP 运行时依赖 `2.2.10`。Google 允许在根 `buildscript` 提升此依赖并继续保留内置 Kotlin。JetBrains 采用与 Kotlin 同版本的 Compose Compiler。KGP、Kotlin 编译器及 Compose 编译器 `2.4.21` 已发布。 [9.5.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.5.1/gradle-9.5.1.pom) · [9.5.1 Gradle 模块](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.5.1/gradle-9.5.1.module) · [9.4.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom) · [Google 的 KGP 升级方法](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade_to_a_higher_kgp_version) · [Kotlin 编译器 POM](https://maven-central.storage-download.googleapis.com/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.21/kotlin-compiler-embeddable-2.4.21.pom) · [Compose Compiler 配置](https://kotlinlang.org/docs/compose-compiler-migration-guide.html) · [Compose Compiler 插件版本](https://plugins.gradle.org/plugin/org.jetbrains.kotlin.plugin.compose/2.4.21) |
| KSP | `com.google.devtools.ksp` `2.3.14`，使用 KSP2 | `2.3.14` 于 11-13 发布并修复 `2.3.13` 的并行执行回归。AGP `.module` 中 `2.2.10-2.0.2` 为依赖约束，Google 提供显式提升方式。KSP2 基于 Analysis API，发布标签含 AGP 内置 Kotlin 集成测试，覆盖 AGP 9.0 预发布版与 KGP `2.4.0`。 [KSP 2.3.14](https://github.com/google/ksp/releases/tag/2.3.14) · [Maven Central 发布清单](https://maven-central.storage-download.googleapis.com/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml) · [KSP2 说明](https://github.com/google/ksp/blob/2.3.14/docs/ksp2.md) · [上游集成测试](https://raw.githubusercontent.com/google/ksp/2.3.14/integration-tests/src/test/kotlin/com/google/devtools/ksp/test/agp/AGPVersionBuiltInKotlinIT.kt) |

通过根 `buildscript` 提升 `org.jetbrains.kotlin:kotlin-gradle-plugin` 和 `com.google.devtools.ksp:symbol-processing-gradle-plugin`。这两个坐标与应用依赖和插件的版本统一定义在 Version Catalog，Compose Compiler 与有效 KGP 引用同一个 Kotlin 版本。应用模块使用 AGP 内置 Kotlin 和根 classpath 中的 KSP 插件。启用 `buildFeatures.compose`，应用同版本 Compose Compiler。保持 `org.jetbrains.kotlin.android` 未应用，使用 Kotlin `compilerOptions`，避免旧的 Compose 编译器扩展版本配置。Gradle Kotlin DSL 的内置语言版本由 Gradle 单独管理。 [AGP 升级依赖说明](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade_to_a_higher_kgp_version) · [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) · [Compose 配置](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler) · [Gradle 兼容表](https://docs.gradle.org/9.9.1/userguide/compatibility.html)

JetBrains 对 KGP `2.4.21` 的完全支持上界为 Gradle `9.7.0`、AGP `9.3.1`，并允许使用较新版本。Gradle `9.9.1` 的自有测试表覆盖 Kotlin `2.4.21`，AGP 测试上界为 `9.5.0-alpha10`。已核对发布记录、SDK 清单和 Maven 元数据。所选整组版本尚待项目首次同步时确认实际 KGP 解析结果，并运行 Room 探针所在变体的 KSP 任务、`:app:assembleDebug` 和 `:app:lintDebug`。 [JetBrains KGP 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin) · [Gradle 兼容表](https://docs.gradle.org/9.9.1/userguide/compatibility.html)

选择 Gradle `9.9.1` 是因为该补丁修复了 Version Catalog 和守护进程相关的安全问题。01 使用干净的工程实际检验组合。如果遇到有效插件版本冲突或任务失败，先记录失败命令和依赖解析结果，再评估稳定组合。固定的 SDK 三项由用户决定。 [Gradle 9.9.1 发布说明](https://docs.gradle.org/9.9.1/release-notes.html)

## App 与测试依赖

| 用途 | 固定版本 | 使用说明及来源 |
| --- | --- | --- |
| Compose BOM | `2026.09.00` | BOM POM 映射 UI、Runtime 和 Compose UI 测试为 `1.12.1`，Material 3 为 `1.4.0`。分别在 App 和适用的测试配置引入同一 `platform`，对应组件声明不单独填版本。 [Google Maven 发布清单](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml) · [BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom) · [官方 BOM 使用方式](https://developer.android.com/develop/ui/compose/bom) |
| AndroidX Activity | `1.13.0` | 使用 `activity-compose`。 [发布说明](https://developer.android.com/jetpack/androidx/releases/activity) · [Google Maven 发布清单](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml) |
| AndroidX Lifecycle | `2.11.0` | 使用 `lifecycle-viewmodel-compose`、`lifecycle-runtime-compose`，用 ViewModel / StateFlow 管理界面状态。 [发布说明](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| AndroidX Navigation | `2.10.2` | 需要 Navigation 时使用 `navigation-compose`。成熟的 Compose 页面导航满足当前四页及详情入口。项目只采用一套导航。Navigation 3 已有稳定 `1.2.1`，本期可继续使用简单的 Navigation Compose 路由。 [Navigation 发布说明](https://developer.android.com/jetpack/androidx/releases/navigation) · [Navigation 3 发布清单](https://dl.google.com/dl/android/maven2/androidx/navigation3/navigation3-ui/maven-metadata.xml) |
| Room / SQLite | `2.8.5` | 统一 `room-runtime`、`room-compiler`，仅在 schema 验证需要时加 `room-testing`。Android-only 使用 Room 2.x 和系统 SQLite，Kotlin DAO 走 `suspend` / `Flow`，用 KSP2 生成。Room 3.x 的稳定版本为 `3.0.3`，要求新的包和 SQLiteDriver 接法。V1 当前用例可由成熟的 2.x 完成，无需额外驱动或 native SQLite 包。 [Room 2.x](https://developer.android.com/jetpack/androidx/releases/room) · [Room 3.x](https://developer.android.com/jetpack/androidx/releases/room3) |
| Coroutines | `1.11.0` | `kotlinx-coroutines-android` 和测试的 `kotlinx-coroutines-test` 同版，纯 Kotlin 入口按需使用 core。Android 制品 POM 依赖同版 core-jvm 及 Kotlin stdlib `2.2.20`，由所选编译工具链覆盖该版本。 [Android 制品 POM](https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-android/1.11.0) · [测试制品](https://central.sonatype.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-test/1.11.0) |
| JVM / Android 测试 | JUnit 4 `4.13.2`、AndroidX Test runner `1.7.0`、ext.junit `1.3.0` | JVM 测试覆盖纯 Kotlin 与流程，AndroidJUnitRunner 跑真实 Room，Compose UI 测试用 BOM 对齐的 `ui-test-junit4` / `ui-test-manifest`。只有直接调用 AndroidX Test Core 时显式加入 `1.7.0`，其余随 runner 解析。 [JUnit 4 发布](https://github.com/junit-team/junit4/releases/tag/r4.13.2) · [AndroidX Test 发布](https://developer.android.com/jetpack/androidx/releases/test) · [Room 测试](https://developer.android.com/training/data-storage/room/testing-db) |

### 可核验的 Android 版本下限

从 Google Maven 发布的 Android AAR 读取 Manifest 与 AAR 元数据。下列是库的声明下限。

| Android 制品 | 最低运行 API | 最低编译 API | 最低 AGP |
| --- | --- | --- | --- |
| Compose UI `1.12.1` | 23 | 37.0 | `9.1.0` |
| Material 3 `1.4.0` | 21 | 35 | `8.6.0` |
| Activity Compose `1.13.0` | 23 | 36 | `8.9.1` |
| Lifecycle Compose `2.11.0`，Runtime 与 ViewModel | 23 | 37.0 | `9.1.0` |
| Navigation Compose `2.10.2` | 24 | 37.0 | `9.1.0` |
| Room Runtime `2.8.5` | 23 | 34 | `8.1.1` |
| AndroidX Test runner `1.7.0` 与 ext.junit `1.3.0` | 21 | AAR 未声明 | AAR 未声明 |

已查的直接制品符合 API 31 安装和 API 37 编译要求。AGP 的最终解析仍要验证所有传递依赖的 AAR Metadata、Compose 编译和 Room 生成。构建工具和 Android SDK 由 Wrapper/SDK 管理，其余依赖在 `gradle/libs.versions.toml` 统一固定，使用 `google()`、`mavenCentral()` 及必要的 Gradle Plugin Portal。应用的离线运行约束与构建期下载依赖分开验证。 [Version Catalog](https://docs.gradle.org/9.9.1/userguide/version_catalogs.html)

## Android 权限和后台边界

- API 31 起用 `BLUETOOTH_SCAN`，声明 `neverForLocation`，名称优先读 `ScanRecord`。保持无定位、连接或网络权限。设备实际广播可能受该标志过滤，由现场票验证。用途变化或实测发现过滤障碍时再决定是否修改权限方案。 [蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) · [BluetoothLeScanner](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner)
- 由用户在可见页面主动开启 `connectedDevice` Foreground Service。API 34+ 类型及权限校验可以用获授的 `BLUETOOTH_SCAN` 满足设备通信运行条件。API 33+ 的通知权限单独处理。锁屏扫描、事件持久化、系统通知呈现分别留证。 [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types) · [后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) · [通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)
