# BYD BLE 接近检测技术交叉复审

复审日期 2026-11-18。以 3.24.12 为主，3.22.17 只用于指定的版本对照。

## 1. 复审结论摘要

**Android BLE 只读扫描原型可以开始。RSSI 状态机已有技术依据，进入车型适配和自动动作设计仍有前置条件。真实自动解锁为 NO-GO。** Android 可以直接从广播回调取得候选特征和 RSSI。能否在目标宋 PLUS DM-i 2024 荣耀版上持续识别本车，取决于休眠广播、地址和载荷轮换、以及手机实际收到的广告。

Codex 报告在被动采集与控车认证的边界上较可靠，对状态机保持了适当的不确定性，但漏掉了近端归位与动作的区别、主动检测直连分支以及真实云端路由。DSH 报告给出的滤波公式、配置默认值和状态机定位多数可以重现，但以下更正会影响开发决策。

- manufacturer identity 只覆盖带经典 UUID 的广告，稳定性未证实。Car ID 前缀存在大小写不一致，且命中可写回新地址。首次自动绑车还会验证本地已配置的钥匙。
- `scan.connLostLockMs` 通过 Companion getter 实际参与失联判断。`pathBend().slope` 参与近端和远端判断。口袋补偿只对接近线产生有效下移。
- 双帧可只同步状态。冷启动强信号补判和采集恢复是独立动作分支，普通平滑上穿并非全局前置条件。默认 60 秒失联分支另有 RSSI、时点和连接闸门。
- 云端有直接路由和 BLE 解锁失败后的补发路由。磁力入口的 live 检查无法约束之后的所有执行路由。应用报告的 BLE 写入成功与车门状态是不同的观察量。

### 样本与验证方法

| 样本（输入位于 `.temp/`） | Manifest | SHA-256 |
|---|---|---|
| `qinl-car-3.24.12-release.apk` | `com.qinl.car.pub`，3.24.12 / 869，min 25 / target 34 | `70803c7b21d4e9c0a8d3e3165e6d9840ccb960bee03b76be4e22d6aea74a265d` |
| `BYD控车-3.22.17（手机安装）.apk` | 同包，3.22.17 / 751，min 25 / target 34 | `6d7efd29c36a2a1271718ee29ff140c173b4d7ef9f00140db9bedceaa652ed5e` |

两版均有 v2 签名记录，签名者指纹相同，REA 报告签名内容摘要自洽。发布者及官方来源未建立。当前目录没有 Git 元数据，`git status` 无法读取分支。已核对项目 `AGENTS.md`，完整阅读两份报告（包括 DSH 复审附录），按候选、滤波、状态、动作、车内、后台列出待核验结论。

独立复查使用 REA MCP 的 Manifest、方法及引用查询，实际后端为 JADX headless 0.7.1 / Java 21。对关键分支另外使用同一 JAR 内的 Baksmali API 从两份 APK 读取 DEX，核对方法、寄存器及跳转。Baksmali 命令行入口缺少 JCommander，API 路径成功。下文 `@0x…` 是**相对所列方法起点的 DEX code-unit 偏移**，目标类位于 `classes2.dex`，默认包名 `com.qinl.car.pub`。小节末尾的 REA ID 及末尾索引与这些位置配套使用。分析仅覆盖交付 APK 的静态调用路径。

## 2. 结论交叉验证矩阵

各状态含义如下。**Confirmed** 代码和调用证实，**Partially Confirmed** 只证实部分，**Inferred** 有间接依据，**Unverified** 需外部证据，**Incorrect** 与 APK 矛盾。

| 主题 | Codex 结论 | DSH 结论 | APK 独立验证 | 最终判断 |
|---|---|---|---|---|
| 候选与扫描过滤 | 三 UUID、厂商 ID、名称候选 | 同，绑车后加 UUID filters | `looksLikeBydCar`、`inAppFilters`、`uuidOnlyFilters`。软件识别与系统过滤范围不同 | Confirmed |
| 广播稳定身份 | 经典 UUID 路径，可跨部分地址变化 | 6 字节稳定身份 | `mfrIdentityMac` 六字节算法成立，真实稳定性和唯一性未知 | Partially Confirmed |
| Car ID 关联 | 前缀辅助绑定 | 大写载荷按两类前缀匹配 | `consumeScanResult @0x1fc/0x22f` 区分大小写，对已小写配置比较，字母前缀可失败 | Partially Confirmed，DSH 前缀适用性有遗漏 |
| 初次自动绑定、地址跟随 | 候选与目标分开 | 候选进入自动绑车，地址漂移跟随 | `autoLearn` 先验证钥匙。`rebindToLiveAddress` 是另一路，只依据广播匹配 | Confirmed，需区分两条路径 |
| 无凭据检测 | 广播可先于认证，原应用可用直连 RSSI | 原应用检测只依赖广播 | 广播取样无 GATT 前置。判定优先取有效直连，且可引入 boost Beacon | Confirmed。DSH 对原判定的限定 Incorrect |
| EMA、回归与野值 | 回归辅助自适应 EMA，默认值保守留空 | 提供算法和默认值 | `feed/regressionPrediction` 和 `AdvancedConfig.<clinit>` 证实。拟合包含当前点，超限只重置回归窗与计数 | Confirmed |
| 口袋补偿 | 修正阈值 | 两条阈值一起下调 | `evaluateProximity @0x3c–0x42` 与 `PocketSense.offsetDb`，有效补偿只降 enter | DSH Incorrect |
| 步进、持远与断联配置 | 实际存在，部分时值未确定 | 20 dB / 5 s，失联参数未找到读取 | 直接读取 `stepJumpDb`，Companion 读取持远及失联配置 | Confirmed。DSH 失联读取结论 Incorrect |
| 曲率、多源 | 旧曲率修正消失 | 多源移除，推断 Bend 仅诊断 | 旧版调用 `blend`，新版去旧曲率闸，保留 Bend 斜率判断 | Confirmed。Bend 仅诊断推断 Incorrect |
| 接近与双帧 | 双帧近证据可进入动作评估 | 主路径平滑上穿，冷启动与恢复有补判 | `evaluateProximity` 的状态同步和动作是不同分支，普通上穿以 `prev < enter <= current` 比较 | Partially Confirmed，见 §5 |
| 失联与离车 | 无样本可归 UNKNOWN，FAR 有持续确认 | NEAR/末值越线/60 秒 | 失联有两个锁车分支，长失联允许从 UNKNOWN 评估 FAR，空窗不必然触发 | Partially Confirmed，见 §5 |
| BLE 解锁与等待 | 有直连、签名、云认证边界 | live 优先，等待 ACK，按 `cloud.timeoutMs` | `bleJob$1` 的等待是失败后等日志行。锁车另走链 | DSH 的 ACK/超时叙述 Incorrect |
| 云端与磁力 | 具体路由未完全展开 | BLE 帧未写出才补发，磁力只走 BLE | 另有直接云路由。磁力 flag 未传到内层补发判断 | DSH 的全局保证 Incorrect |
| Cabin、Beacon、媒体 | 车内拦截链存在，步态活跃性未证实 | in/out Beacon 与磁力兜底 | Beacon 参与车内判定。媒体与磁力各有独立用途，无 Beacon 的车内态不可推定 | Partially Confirmed |
| Android 后台 | FGS、回调、PendingIntent。称 offload 受硬件能力闸控 | 绑车 LOW_LATENCY，15 s 稀疏 offload | 绑车循环低功耗回调有独立条件，PendingIntent 另行尝试，硬件探测做诊断 | 两份报告此处均有 Incorrect |
| 宋 PLUS 休眠、身份、后台 | 需实测 | 报告也列为未验证 | 当前证据来自两份 APK | Unverified |

## 3. BLE 身份识别与被动扫描

| 层次 | 3.24.12 已核实行为 |
|---|---|
| BYD 候选 | `bluetooth.BydBleSignClient$Companion.looksLikeBydCar` 依次匹配经典 UUID `42594420-4155-544F-E0A9-E50E24DCCA9E`、两种 `00003008` UUID（`00003008-427D-EE7D-6974-7DC1FA381C07`、`00003008-0000-1000-8000-00805F9B34FB`）、Manufacturer ID `0x3494`、转大写名称含 `BYD` 或 `BLE3`。任一命中即可。服务取 `BluetoothDevice.getName()`。 |
| 目标关联 | `consumeScanResult` 在目标 MAC 非空时，先按忽略大小写的地址相等，或非空 manufacturer identity 等于目标 MAC / 已存 identity 接收。直接命中优先。失败后才比较 `prox.boundCarIdHex` 与逗号分隔的 `prox.allowedCarIds`。绑定分支未将 `looksLikeBydCar` 的结果作为附加接收条件。 |
| 凭据验证后的绑定 | 无目标时通过软件候选筛选并记住更强候选。`autoLearn` 要求候选 ≥ −85 dBm、本地数字钥匙或旧版 BLE 配置就绪，调用 `verifyVehicleHandshake` 成功后复查尚未绑定才写入。轮询只读原型可用人工确认的本地标识替代。 |

`mfrIdentityMac` 和 `mfrPayloadHex` 都要求广告含经典 UUID，未对 Manufacturer ID 强制等于 `0x3494`。前者取首个四字节载荷且拼接结果非全零的条目，按 **ID 低字节、ID 高字节、四个载荷字节** 形成 6 字节标志，输出大写冒号分隔 hex。后者取首个非空载荷，输出大写连续 hex，范围可与前者选中的条目不同。配置前缀经 `trim().lowercase(Locale.ROOT)`，两个 `startsWith` 在 DEX 中均以区分大小写调用，数字前缀仍可命中。新世代 UUID 单独出现时这两个解析均返回空。

任一绑定匹配通过且当前地址非空、不同，`consumeScanResult @0x307` 调用 `rebindToLiveAddress`。后者按 8 秒节流，规范化非空 identity 后更新 identity、目标 MAC 和 `scanFilterEpoch`。因此前缀重叠、四字节复用或用户误选均可使地址关联错误。代码未对这些广播标识做保密性或真实性验证。本车身份不明确时，独立检测应输出未知并保留现有本地关联。

`inAppFilters` 在未绑车时返回空过滤配置，绑车后只有三个 Service UUID filter，故仅名称或 Manufacturer ID 命中的候选并不保证在该模式下被系统交付。`consumeScanResult` 先读取 RSSI，`[-95,-1]` 更新有效广播，`@0x3bb` 对 ≥ −85 dBm 调用 `onCarDiscovered`，`@0x3c9` 将判定合并排队。`onCarDiscovered$lambda$2` 要求会话已启用、蓝牙未关、会话未 LIVE 且没有正在进行的建连，之后按重试状态决定是否开 GATT 或重建。原 APK 把只读采样与建连放在同一服务，广播读取和纯广播 RSSI 计算本身可独立。

## 4. RSSI 算法与配置基线

### 数据来源与滤波

`evaluateProximity @0x0–0x15` 在动作忙时返回，其他情况下可调用直连启动逻辑。`@0xab–0x148` 首选有绑定、`lastAliveAt>0`、年龄 ≤3 s 且 RSSI 在 `[-95,-1]` 的直连输入。否则用同范围且年龄 ≤`scan.advFallbackMs` 的广播样本。可取 `bcnOutRssi()` 提供的新鲜 boost Beacon RSSI 与选定车辆 RSSI 中的较大值。无新鲜车辆源时，Beacon 单独不能产生 feed。新样本取决于所选来源时间戳与对应上次 feed 记号不同，与 RSSI 是否变化无关。重复时间戳复用 EMA，EMA 为空时取当前原始值。`pushConnSample` 的“中位”诊断实际是 `round(10·log10(mean(10^(dBm/10))))` 的功率域平均，读取 `scan.connWindow`，在已检查的状态分支中未作为闸门。

```text
有效输入间隔 > 40000 ms 时全量 reset
追加 (now, raw)，回归队列最多 W+1 点
用最近 W 点（含刚追加点）在 now 取 OLS 拟合值
有效点不足 3 或时间方差过小时取末点，否则四舍五入并钳制至 [-120,0]
a = alpha
若 abs(raw - pred) > outlierDb：
    rejections += 1
    若达到 maxReject，清回归队列和 rejections，保留 EMA 和两个辅助窗
    否则 a = clamp(rejections * 0.024, 0.02, alpha)
否则清 rejections
ema = raw（首点）或 ema + a * (raw - ema)
取整的 ema 入 slopeWin / minWin 并返回
```

回归以首个选中样本为时间零点，`slope = (n·Σxy − Σx·Σy)/(n·Σxx − (Σx)²)`，截距为 `(Σy − slope·Σx)/n`。当前样本参与拟合，其残差与留出当前点预测的算法不同。降权时仍采纳样本，默认前两次异常的 alpha 分别为 0.024、0.048，到第三次回到配置 alpha。`lastTriple` 在有至少三点时返回最新、次新、第三新，`stepDeltaDb` 为最新减次新，少于两点返回 0。

`slopeWin` 至多 32 点且保留 6 分钟内数据，`minWin` 至多 2048 点且保留 5 分钟内数据。`pathBend` 按 `lastFeedAt-windowMs` 选 `minWin` 中至少三点，返回点数、最小值、末点减最小值、绝对差累加、最大绝对二阶差分、最大点间隔和最小二乘斜率。二阶差分的两个间隔都须 ≤1500 ms。REA incoming refs 与 Smali 均看到 `evaluateProximity` 调用，动作分支使用 `Bend.slope`，局部弯折统计仍可用于诊断。

| 配置 | 代码默认值（写入范围） | 运行时读取及用途 |
|---|---|---|
| `prox.emaAlpha` | 0.2（0.02–1） | `alpha()` 另钳制 0.02–1，影响每次 feed |
| `prox.regWindow` | 12（3–30） | `regWindow()` 另保底 ≥3，影响拟合 |
| `prox.outlierDb` | 8 dB（2–30） | `outlierDb()` 另保底 ≥0，影响残差分类 |
| `prox.maxReject` | 3（1–20） | `maxReject()` 另保底 ≥1，决定何时清回归窗 |
| `prox.stepJumpDb` | 20 dB（2–60） | `evaluateProximity` 直接读，影响普通接近、恢复和离车分支 |
| `prox.farConfirmMs` | 5000 ms（0–120000） | `getFAR_CONFIRM_MS()` → 普通低信号持续窗。断联路径另算 |
| `scan.advFallbackMs` / `scan.connLostLockMs` | 45000 / 60000 ms（0–300000 / 3000–600000） | 广播有效期、失联公式的输入。失联键经 Companion getter 读取 |
| `prox.lockInterruptRssi` | 读取结果为 0 或异常时回退 −95 dBm | `getLOCK_INTERRUPT_RSSI()` → 短断联分支。未在本版参数表定位到可调入口 |
| `rssi_enter` / `rssi_exit` | −70 / −80 dBm。由 `CredentialManager.getInt` 读取 | 入/出边界。默认差 10 dB，配置与有效口袋补偿会改变差值 |
| `pocket.enable` / `offsetDb` / `maxOffsetDb` / `guardMs` | 开 / 0（自动）/ 8 dB / 3000 ms | `PocketSense.offsetDb` 只有启用且袋内等条件满足才返回有效值。判定中 enter 减 offset，启用时 exit 保持原值。翻转回调设置静默窗并重置相关滤波。 |

范围是配置写入约束。读取器会解析已有值或声明默认值，部分调用者另做保护，故不能把写入范围当成所有数据来源的统一钳制。DSH 对两阈值一起下移的表述来自配置说明，和运行分支不一致。

**版本差异。** 3.22.17 中 `ProximityRssiFilter.feed` 有 `prox.curvEnable` / `prox.curvFilterDb` 及 0.35 回拉，旧 `evaluateProximity` 读取 `prox.curvBaseDb` / `prox.curvBaseDbLock`。旧 `MultiSourceRssi` 在 `consumeScanResult @0x271/0x344` 接收环境/主样本，`evaluateProximity @0xf3` 调 `blend`。3.24.12 对应类、旧曲率键和调用消失，保留两类车辆源优先级、回归 EMA 和 Bend 趋势统计。滤波效果变化及移除原因仍是推断。

## 5. 最终状态机与保护条件

### 原 APK 已确认行为

下表的“请求”指调用 `autoAction`，后续仍可被静默、策略、认证或执行失败拦截。状态写入发生在动作请求之前。`enter`、`exit` 分别为本轮实际阈值，跨步通过为 `abs(step) <= effectiveStepJumpDb`。普通判定有新旧样本区分，远离持续窗口可复用仍新鲜的样本。

| 当前状态 | 触发与分支条件 | 写入及副作用 | `evaluateProximity` DEX |
|---|---|---|---|
| UNKNOWN / FAR | 普通接近。新样本，`prev < enter <= current`，跨步通过，斜率无值或通过负斜率下限，且避开采集空窗恢复阶段和口袋静默 | NEAR，进入解锁动作评估 | `@0xa4f–0xb31`、`@0xcac → 0xdc0` |
| UNKNOWN / FAR | 未走动作分支，当前和前一净化点均 ≥ enter | NEAR，仅状态归位，置归位来源和时点。双帧本身不发动作 | `@0x1148–0x1162` |
| NEAR（刚归位） | 普通补判。归位 ≤10 s，非突发空窗归位且无近期充电恢复，新样本达 enter、跨步通过。从 FAR 归位还要求 30 s 内曾低于 enter | 保持 NEAR，可请求解锁 | `@0xbd2–0xc1c`、`@0xdc0` |
| NEAR（冷启动归位或恢复） | UNKNOWN 归位前两个净化点均 ≥ enter+10，且广播计数 ≤3 或前一间隔 ≥60 s 时置冷启动强信号标志。恢复近期持久化 NEAR 也可置该标志。归位后 3–60 s，无近期充电恢复，新样本 ≥ enter+6 且跨步通过 | 保持 NEAR，可在后续帧请求解锁。归位动作与首次进入 NEAR 分开 | `@0x118e–0x11b4`、`@0xc1c–0xc49`、`@0xdc0` |
| UNKNOWN / FAR | 采集恢复直通。新样本，`collectResumeAt` 有值且 60 s 窗内，当前未处于 NEAR，净化值 ≥ enter，跨步通过 | NEAR，可请求解锁。条件允许恢复首帧，也可在后续帧成立 | `@0xc49–0xc82`、`@0xdc0` |
| UNKNOWN / NEAR | 普通持续远离。有效净化值 ≤ exit，跨步合格可开窗。已有确认窗时也可在未明显回升的趋势条件下延续。排除周期恢复、近期成功解锁 <8 s、近期归位 <12 s、口袋静默等，满足时长 `farConfirmMs` | FAR，可请求锁车。该分支允许复用同一新鲜低值推进时间。普通远离可从 UNKNOWN 触发 | `@0xdc4–0xee7 → 0xfa6` |
| NEAR | 短中断判锁。静默 ≥1 s、选中末次时间存在、无周期恢复/直连宽限/口袋闸、无近期解锁或归位，本分支距前次 ≥30 s，另需已有远离窗或最近 ≤3 s 的 EMA ≤ `LOCK_INTERRUPT_RSSI` | FAR，可请求锁车。使用独立时间条件 | `@0x74c–0x843 → 0x8d2` |
| NEAR / UNKNOWN | 长失联闸通过，旧 EMA 仍存在且 ≤ exit | FAR，可请求锁车。无必须曾 NEAR 的闸 | `@0x8d6–0x961 → 0x9cd` |
| NEAR | 长失联闸通过且无旧 EMA | UNKNOWN，无锁车调用。UNKNOWN 留在 UNKNOWN。末值仍高于 exit 时保持原状态 | `@0x8d6–0x925` |
| UNKNOWN | 执行前面分支后仍为 UNKNOWN，连续两个净化点 ≤ exit，命中弱双帧归位 | FAR，只同步状态 | `@0x1230–0x124c` |

接近动作门由四路布尔量 OR 后进入 `@0xc82–0xca4`。已 NEAR 时还须近期归位（该公共闸为 <120 s）。跨步使用 `lastTriple.first - second` 或 `stepDeltaDb()`，前值来自 `lastTriple.second` 或 `prevValue()`。普通近端按 5/10/15 s 取样窗调整斜率下限，存在 Bend 时要求斜率大于 `-4×4.8/窗长秒数`（`@0xaa0–0xb0e`）。常规远离的趋势条件检查斜率是否达到 `8×3.6/窗长秒数` 或比确认窗基线回升 ≥3 dB。跨步通过有独立放行分支，回升没有一律否决。`exit−6` 在已检查的普通离车位置用于分支描述/诊断。遮挡可能令测得的信号低过 exit。

**长失联基本块。** 无任何新鲜车辆源、周期判定间隔未触发 >5 s 的恢复闸、有正的末次命中时点且未处于新连直连等待 RSSI 的约 45 s 宽限时，才比较间隔是否达到 `max(scan.connLostLockMs, scan.advFallbackMs + 15000)`。未绑车用 `lastAdvHitAt`，已绑车优先用正的 `lastAliveAt`，在选定扫描模式下才回落到广告时点。默认 `max(60000,45000+15000)=60000`，仍要按上表检查末净化值。DSH 从“交给 60 秒失联兜底”日志得出的固定时长、没有参数读取、需要先 NEAR 三点均不成立。普通低信号的 5 s 持续确认、短断联独立计时、长失联动态门限和动作层锁车冷却分别生效。

**采集空窗与服务重建。** 新帧间隔达到 `max(3 s,3×平均帧间隔)` 时，`@0x17b–0x29b` 有采集恢复/空窗分支，重置滤波及功率平均窗，再以当前点启动计算。周期扫描协程传入实际 tick 间隔，广播 `postJudge` 可使用默认间隔参数，恢复闸由每次调用的实参决定。`exitIdleMode` 的“首帧只归位”是日志，采集恢复直通没有统一的充电首帧禁止。`persistProximityState` 在变化或 180 s 心跳时写入。`restoreProximityState` 接受年龄在 0–600 s 的有效存档，存档 FAR 降为 UNKNOWN，NEAR 保留并设归位补判标志。服务 `onCreate @0x71–0x7b` 接着设约 3 s 动作静默。恢复后的补判仍可在静默结束后触发。

### 新项目建议采取的安全策略

| 情形 | 建议状态／事件行为 |
|---|---|
| 已人工确认目标，扫描健康，有独立新鲜样本 | 用迟滞、跨步/异常过滤和多帧证据生成可重放的只读接近或离开事件。阈值和时间按车型、手机、车位实测校准 |
| 启动、恢复、换地址、已有状态但第一批数据不足 | 只建立当前观测状态，重置过期窗口与计时，待独立新样本确认后才产生边沿事件 |
| 扫描不可用、长空窗、目标身份不明确 | 输出对应不可用/未知状态，撤销持远计时，不推断离开或发出车辆动作 |
| 车内证据缺失或互相矛盾 | 车内态标记未知，任何未来执行器应单独做认证、方向抑制与幂等判定 |

## 6. 车内外检测与后台扫描

### 车内外与其他信号

| 信号 | 实际引用和可依赖程度 |
|---|---|
| `CabinPresence` | `init @0x17–0x1e` 从本地偏好读 `intercept_on`（默认 true），`isInterceptOn` 读内存字段，`WebBridge.setCabinGuard` 可设。`isInside` 清旧状态并返回 null，`onStep` 空，`onCarCondition` 仅更新时间。拦截开关不是车内判定状态。 |
| `InCarProbe.beaconVerdict` | Beacon 开关默认开，并须有已配置地址、非 boost 模式、≤3 s 新鲜且 RSSI 达该信标阈值。任一合格 out 优先于任一 in，均无命中返回空。`inCarNow = interceptOn && verdict == "in"`，`suppressUnlockNow` 取它，`suppressLockNow` 还可取临时离车。动作层实际调用两者。 |
| boost Beacon | `bcnOutRssi` 名称容易误读，实际取满足新鲜性的 boost 模式最强 RSSI，不以角色 out 为门，参与已有车辆 RSSI 输入。boost 与 in/out 判定的命中列表分开。 |
| 媒体蓝牙 | `MediaBtProbe` 有绑定、配置、车机连接态及对应锁/解锁抑制开关。对应抑制开关默认关。开启时可在媒体已连接或刚断开时限制自动动作，其中解锁断开保护为 10 s。这是独立保护输入，不能建立通用车内态。 |
| 磁力手势 | `InCarProbe.countChange` 经时间/次数门槛产生有意手势，使用临时离车/磁力回调。实际动作受该模式及车机连接策略影响。手势触发与自动车内分类分开。 |
| 步态、车况、座椅 | `AccelSense.gaitJudge` 有实现，REA 未发现直接运行时来边。`OccupancyCache.updateFromBrief` 有 WebBridge 调用，`current/getOccupied` 未找到判定调用。车辆快照在动作保护等路径可用，未恢复出用门事件、步态或座椅生成 Cabin verdict 的分支。 |

**没有已配置且近期命中的 in/out Beacon 时，原 APK 的自动车内判定没有有效结论，即使车内拦截开关开启。** 媒体蓝牙和临时离车可以在各自条件下抑制动作。车内外真实性、车门开合、相邻车位串扰和 Beacon 漏包都要实测。

### Android 扫描与恢复

| 路径 | 新 APK 静态行为 |
|---|---|
| 主进程回调 | `BleScanService$startScanning$1.invokeSuspend @0xed–0x12e` 未绑定以 BALANCED 持续请求扫描并触发 `autoLearn`。`@0x193–0x265` 已绑定使用 LOW_POWER 回调，须开启 `ble.offloadSample`，并满足任一条件：`keepalive.lowScan`、标记为 BLE5 车机，或直连不可用且末次广告为空或超过 15 s。其他情况停止回调。此分支没有直接的屏幕状态检查。3.22.17 已绑定时按 `getKeepAliveLowScan()` 选 BALANCED 或停止（旧版同方法 `@0x129–0x15e`）。 |
| 扩展广播 | API 26+ 且 `ble5.extScan` 开时，BALANCED / LOW_LATENCY 使用的 `buildScanSettings` 可设 `setLegacy(false)`、`setPhy(1)`。两类 LOW_POWER 路径使用单独 builder，未设置这两项。两版均已有配置键，UUID 本身无法确定 PDU 类型。 |
| PendingIntent | `ensureOffloadScan @0x13–0x43/@0x94–0xb8` 独立于上述 15 s 回调分支尝试注册。API 26+，UUID 过滤、LOW_POWER、ALL_MATCHES、5000 ms report delay。硬件过滤和批处理能力查询用于诊断。注册同步 int 返回值未处理。异步错误（已注册码除外）或异常约 60 s 后重试。距最近注册/投递 ≥180 s 且近 60 s 仍有目标广告时怀疑静默失效。 |
| 回调自愈 | `ensureScanAlive @0x09–0x38` 已绑车主要恢复未运行的任务。未绑车另查近期错误、≥6 s 零回调 warm-up（默认 5 次、重建间隔默认 20 s）及 ≥10 s 常规停滞。5 次只限 warm-up。主循环在曾收到结果且约 480 s 沉默后另有停进程回调分支。共用 `consumeScanResult` 在筛车前更新收包时间和 warm-up 计数，PendingIntent 也能更新。注册预算是共享 4 次/30 s，回调注册失败可冷却 30 s。判定周期默认 500 ms，实际 loop 对配置再次限到 100–1000 ms。 |
| 停采、蓝牙切换 | `enterIdleMode` 取消扫描/看门狗，停止两类扫描及直连等输入，另有 2 s 轮询和受暂停闸限制的 watchdog。蓝牙 OFF/TURNING_OFF 时取消扫描任务、清扫描标记和远离窗口、暂停 GATT。蓝牙 ON 后设 3 s 动作静默并请求重建/重扫。上述 OFF 处理没有直接锁车调用。 |
| 进程、开机、前台服务 | Manifest 的 `BleScanService` 是非导出 `connectedDevice` FGS，`stopWithTask=false`。`BleOffloadReceiver` 将结果交给 live 服务，否则在未暂停时排队并尝试拉起 FGS。`BootReceiver` 按事件做启动态安排及梯级闹钟，`ServiceWatchdogReceiver.Companion` 调度 service PendingIntent，精确闹钟不可用时会退化。服务被系统回收后能否恢复取决于投递权限、启动限制和 ROM。 |
| 无障碍 | `KeepAliveA11yService.onAccessibilityEvent` 为空，配置禁止取窗口内容。连接后加 1×1 overlay 并尝试启服务。它是需另行启用的保活手段。第一版的扫描能力无需依赖此服务。 |

第一版可以使用官方 `BluetoothLeScanner` 回调和带过滤的 API 26+ PendingIntent，按需要使用由用户启动的 `connectedDevice` FGS。官方说明未加过滤的 ScanCallback 扫描在息屏时停扫，进程内回调依赖进程存活。PendingIntent 可唤起应用处理匹配结果，启动 FGS 仍受 Android 12+ 后台规则约束。Android 14+ 需要匹配的 FGS 类型、权限及运行前置条件。Manifest 声明的 `neverForLocation` 要与用途相符，且可能过滤部分 BLE Beacon。用最终权限声明核对目标车辆广告。不能把 OEM 保活、无障碍 overlay 或多重闹钟当作持续采样条件。[BluetoothLeScanner API](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner) · [BLE 后台通信](https://developer.android.com/develop/connectivity/bluetooth/ble/background) · [蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) · [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types) · [后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) · [闹钟](https://developer.android.com/develop/background-work/services/alarms)

## 7. 检测层与控车执行层的安全边界

### 3.24.12 执行链

1. `BleScanService.autoAction` 在状态判定之后接收动作，也有磁力等其他入口。该方法及外层协程检查服务重建静默、车内/临时离车、按配置启用的媒体蓝牙抑制、距记录的上次成功锁车时间 60 s 冷却、同向任务/动作占用等。BLE 路径可在后续复核前写锁车时间。`CarStateGate.gate` 检查门、后备箱或行车等状态，缺失、过期快照可放行。外层同向在途跳过，反向可取消前一外层作业并清上报状态。内层 BLE 作业另用 `beginAction/endAction`。磁力入口当时 `!isLive()` 就返回，`fromMag` 未传入内层 BLE 作业，所以入口日志无法约束补发分支。
2. `BleScanService$autoAction$job$1.invokeSuspend @0x520–0x5f9` 的 BLE 路由要求目标非空，本地数字钥匙或旧版 BLE 配置就绪，同时未命中弱信号云锁例外。弱信号云锁另查锁方向、最后 RSSI 范围、云就绪、可用网络及无 live 会话。`@0x788–0x8f8` 在 BLE 路由不可用时另有直接云动作，包含 near 解锁，受状态去重及独立云冷却等约束。该路由不读取 `ble.unlockCloudFallback`。
3. `BleScanService$autoAction$job$1$bleJob$1.invokeSuspend` 进入 `beginAction`。解锁实时检查 `BleDirectSession.isLive()`，为真走认证会话上的 `sendCommandNoWait`，为假调用绑定 MAC 对应的 `BydBleSignClient.executeSignedCommand`。isLive 包括已启用、会话处于 LIVE 且不在暂停期。签名客户端分支等待同一直接会话最多 6 s 进入 LIVE 后使用相同发送入口，写入失败时有最多 4 s 的重连等待和一次重试。锁车由独立的 `BleLinkage.runLockChain` 和后续复核处理。定位 `bleJob.invokeSuspend @0x64–0xcc/@0x23e`，`executeSignedCommand-0E7RQCE @0x102–0x104/@0x1c0/@0x223–0x242`。
4. BLE 解锁成功分支记录写入结果，外层有提前上报状态/反馈。撤销异常分支记录并退出，未进入普通失败回滚或补发。其他 Result 失败等待至多 2 s 让外层日志行可修订，随后修订失败记录、清成功反馈并回滚上报标记。这里的 latch 等的是日志，不是车端 ACK，也不读取 `cloud.timeoutMs`。定位 `@0xe1–0x1cb`。
5. 普通 BLE 解锁失败后，`ble.unlockCloudFallback` 默认开，距上次云动作达到 `scan.autoActionCooldownMs`（默认 10000 ms）时调用一次 `reportGate("near",true,…)`。补发以应用的 Result 分类为准，车端是否已收到要靠独立反馈确认。定位 `@0x1cd–0x237`。`reportGate` 的 near 路由再经 guarded `CloudCmdQueue`，包括云路由就绪、风控等待与 `cloud.guardMs`、队列容量。客户端 `CloudRoute.ready` 接受账号或 Watch 路由，DSH 的通用“账号+PIN”前置条件未被证实。云路径与本地 BLE 钥匙是独立的认证依赖。

**派发后的动作可以在缺少新鲜检测确认时继续。** 磁力入口和恢复补判有独立于普通 RSSI 边沿的触发逻辑。内层执行依据派发及自身闸门，已核查路径未发现重新确认新鲜的只读目标样本。没有证据表明单靠公开广播就能完成已授权动作。日志中的“成功”或 BLE 写入返回也不足以确认车锁实际状态。

独立检测侧只保留广播、目标关联、RSSI 和观测事件。任何后续执行需走独立且授权的接口，明确动作来源和许可，处理不确定输入、会话撤销、重复动作、通道切换及最终车门状态。真实执行前应能证明不存在未经授权的自动云回退。

## 8. 可用于后续 to-spec 的技术基线

- **可独立开始的能力**：使用 Android 官方 API 收集广告与 RSSI，以本地人工确认的目标做只读关联，加迟滞、异常降权、时间与多帧证据的可回放状态机。静态代码提供样本有效性、滤波公式和有界参数参照。
- **车型与后台门槛**：系统可投递广告、休眠后仍有足够样本且地址/载荷可区分本车，才具备持续的本车接近判断。先验证最终权限、锁屏分布、重启和蓝牙切换，记录扫描不可用。
- **可借鉴机制**：样本过期作废、恢复归位、跨步约束、接近/远离迟滞、持续确认、同一状态边沿去重、事实与设备验证独立记录。
- **原行为中的误触路径**：弱关联自动改绑、无新帧时沿用 EMA 产生离车、启动或恢复后补触发、失联直接判动作、共享云回退。它们适合作为回放反例。
- **独立输入与执行门槛**：车内 Beacon、媒体蓝牙等可作为可选实测输入。三态 proximity 或公开广播身份不承担认证。车辆控制需要合法授权和独立验证依据。

## 9. 未解决问题与验证优先级

| 优先级 | 缺少的证据及影响 | 能否静态解决／实测验收条件 |
|---|---|---|
| P0 | 宋 PLUS DM-i 2024 荣耀版锁车、休眠、重唤醒时的广播可见性与身份稳定性。阻碍车型级本车检测承诺 | 必须只读实车。目标手机按最终权限分别记录前台/熄屏的样本，覆盖多个休眠时长、钥匙在场/不在场、地址变化与相邻同品牌车。能重复关联本车，冲突给出未知，记录最长可观测空窗 |
| P0 | RSSI 与真实进出车位的对应关系，遮挡的假边沿。阻碍阈值定稿与自动动作 | 必须实车采集后做离线回放。覆盖方向、口袋、隔墙、门边、停留、回到车边和停采恢复。真实边沿可重复，扫描缺失与抖动不产生错误事件。保留误判、漏判和延迟统计 |
| P0 | 合法可用的执行器、可信目标身份、对撤销/重复/失败的反馈及端到端授权。阻碍真实解锁 | APK 静态路由只证明客户端尝试。后续另行授权后以模拟器/受控接口先验证幂等、终态确认与禁止静默换通道，之后才评估受控实车验收 |
| P1 | 无 Beacon 与有 Beacon 的车内误判边界。阻碍可靠车内保护承诺 | 实车覆盖车内外、车门开闭、手机位置、Beacon 掉线/冲突、媒体断开。无证据时保持未知，安装方案能稳定区分已测关键场景 |
| P1 | Android 目标 ROM 真实后台可用性、恢复与耗电。阻碍支持范围定稿 | 需真机观察锁屏、系统回收、重启、蓝牙切换、用户停用、附近设备授权撤销。记录最大漏采时间、能否恢复、失败可辨识、耗电。优先单独验证标准 FGS/过滤 PendingIntent |
| P2 | 非核心旁路、动态配置下的完整执行覆盖，以及其他平台采样。对只读原型无阻碍 | 若后续需要，可扩展 DEX 来边和配置来源核对。实际运行值、iOS 采样和 ROM 差异仍需对应设备实测 |

## 10. 最终 Go / No-Go 结论

| 决策 | 结论 | 依据 |
|---|---|---|
| 开始 BLE 只读扫描原型 | **GO** | Android 官方 API 可获取候选广告与 RSSI，读取本身不需 GATT、账号、钥匙 |
| 实现 RSSI 接近状态机 | **CONDITIONAL GO** | 算法与状态分支的静态证据已足够做只读实现和回放。车型阈值、稳定身份、失联降级和设备后台边界待标定 |
| 开始车内外判断模块 | **CONDITIONAL GO** | 可研究可选 Beacon 接入和未知态。无 Beacon 的自动车内外判断缺少可靠依据 |
| 已具备可靠自动解锁执行方案 | **NO-GO** | 静态识别出候选路由与保护条件，未建立可授权、可复验及可确认车辆最终状态的执行方案 |
| 启用真实自动解锁 | **NO-GO** | 等待实车确认本车身份、后台和接近可靠性、车内抑制，取得独立授权执行通道，并验证撤销、断联、重复动作、失败反馈与云回退策略 |

可以进入只读检测相关的 to-spec，目标车型和手机实测应以明确的验收条件进入下一阶段。无需等待原 APK 每个旁路被完整还原。

## 证据索引

本轮 REA Evidence 与 DEX 位置可独立重查。下面优先列父 Evidence ID。完整 REA 记录（212 条）保存在 [rea-evidence.json](../.temp/cross-review-evidence/rea-evidence.json)。原报告 ID 为各自会话的历史索引，见两份输入的 §10。

| 路径 | 本轮 REA Evidence ID | APK 重查入口 |
|---|---|---|
| 样本 Manifest（主 / 旧） | `ev_70aa64e02afd8ffbceafe252614a45f1e7803efb258e1073e6c129f4739b7615` / `ev_aeb9ba4750857be5d3320fd2d600d4e3c3c0e73cd66b381751d9b8e6d9669527` | `AndroidManifest.xml`，主版本 Manifest 权限与组件段 |
| 签名记录（主 / 旧） | `ev_2c1bbc51c4938ae246d0b5fa3834b2f62f6b0f67ccde100f57663767b4ebb40f` / `ev_d5c3f03ed4bdd9d2ced938b0417b9d3bec37a79a51ad0bd550181500189f0a32` | `inspect_apk_signature` |
| 候选、identity、payload | `ev_26fe20af26c9d577052e92ebe8639893466d06ce26da5e4292b3bde699a5bd17` / `ev_51537c0d3a838bc6a957c83d87381f56124790214a29e4ba351fae0f36e5c463` / `ev_78373227d692f649c10fff3fb80c2745b8ad0c20f5c2db446868d3e33195620d` | `bluetooth.BydBleSignClient$Companion` 三个同名方法 |
| 消费、绑车、改绑 | `ev_74a38b5ecee28cf14d5f222eefb8573f9171e1f5352eae93e31990295ac572ea` / `ev_2dd19af458e8d2278e5838a93722cacbc5d9e3f60b873c4e04651aa8dfc8a165` / `ev_9913b421d5d579eb67ec58457adcdc308c2335a7c9646b79e476980bac1cb48c` | `consumeScanResult`、`autoLearn`、`BleScanService$Companion.rebindToLiveAddress` |
| 滤波及趋势 | `ev_451b33e33098785e98a0c1259d1c7b32e7058f41a35e1baf0938c19577d0bb12` / `ev_0bd535af3ca5fa3c2f385dd8bed10d3b77d34fa2bdf409ea0af35683a1562307` / `ev_600948574fa3595e4ca95e72c3b4aec9427e12ff62e66328c3ad9ab1c49eccc6` | `ProximityRssiFilter.feed`、`regressionPrediction`、`pathBend` incoming。配置表用原 DEX 的 `AdvancedConfig.<clinit>` 核对 |
| 状态机与关键读取 | `ev_e378de911059565545279cb24085af0ea07784d6e3b6d46d0c1f275cd826b99c` / `ev_c87b08d992a79ba18d99361763819ca28ce6f9aac4afec317d8afc63dad45ce1` / `ev_809e12e251f01677bab2ed7d12b3f7256bc90c25bd18632988a39a7a6473e54e` | `BleScanService.evaluateProximity(J)V`，`Companion.getCONN_LOST_LOCK_MS/getFAR_CONFIRM_MS` |
| 状态恢复 / 口袋偏移 / 功率平均 | `ev_8a5ee451efcfc6b24692f2719cf3da9598e0957997c1f6632938370dd369ebfd` / `ev_123bcc080bef0631d75e1eca0dd2b2e6d15814315ad8393b9e402d1fa17ff952` / `ev_f5428351b9025c4f3e84bc01f46721f6db2fc9dd65fd623fd966f453a7983800` | `restoreProximityState`、`PocketSense.offsetDb`、`pushConnSample` |
| 自动动作 / 路由 / BLE 执行 | `ev_834875933a71470fdc74eaba91005d02cc72e219fff9c4c7cf19c7087d2f12de` / `ev_a1378b078d8cb79ffe655f8579d777a1b74f853902a8b24c5b7b6ac02e9725ee` / `ev_85adf4bb792dba272dfc7ebf4145da312ef5f94c478fc5960ee058ded80f09c7` | `autoAction`、`$autoAction$job$1.invokeSuspend`、`$autoAction$job$1$bleJob$1.invokeSuspend` |
| 签名客户端 / 队列 / 云就绪 | `ev_18a09661e6e273d4d0b89c085a93516701c948fa5b53722d02b5c1245c9f267f` / `ev_2daa4a3424a5481e23d467a7b84f87ffe5fbccdf072cca5b99c71de88ee2150b` / `ev_cf0200d9a6d24fb98b61f38cf2aa2def16a0c561ac88899d82448fb68f883257` | `bluetooth.BydBleSignClient.executeSignedCommand-0E7RQCE`、`CloudCmdQueue.submit`、`CloudRoute.ready` |
| Cabin / Beacon / Boost / 步态引用 | `ev_6cf02ee589a8dc5f464dc6aa40eae7bb6f4da99093982336f36c48c379c81260` / `ev_e50a3cabfecdd64d50d98195c544dbd28dd93ba04e80ea692f63b7a0bcda6d5c` / `ev_5695272633c42211f707dcd8a2f6d5dcfc304b7f3e4a0581f83ec128ef68aa4b` / `ev_fc000bda7bd947d4ce05f5d6eab2f7742c4025cd1bc3660ad5fb65604b67e50a` | `CabinPresence.isInside`、`InCarProbe.beaconVerdict/bcnOutRssi`、`AccelSense` incoming |
| 后台主循环 / PendingIntent / 自愈 | `ev_ff233c24e999da2ac22240031b5fad65b33d1a6537de56fca10fa36d029fed72` / `ev_d817fe13726cad1b2e5a424a7d0ca70f1cdb751c7eba5d5a72ccab93452bac18` / `ev_fbd6187a1a62c8d5cb8958c9b57bf88ef5eeb87367ca5925f0151fce15ab6cde` | `BleScanService$startScanning$1.invokeSuspend`、`ensureOffloadScan`、`ensureScanAlive` |
| 开机 / Watchdog / 无障碍 | `ev_8a3e386be8c410edd3a87a78593cf3dce47822fce7ca7ea45895c0ba28a50fd9` / `ev_c0cefe045765ae4ee2ef84e350bb15cea6e7e1d31983ecb6b23367a5ece93665` / `ev_a52326e46b9072f08ad117d0295d8e062d8d8e1a9938b81e1006aee374b7d16b` | `BootReceiver.onReceive`、`ServiceWatchdogReceiver$Companion`、`KeepAliveA11yService.onServiceConnected` |
| 多源类（新 / 旧） | `ev_a7a41c2c816f83442b2380e0350cb5ff095444c9741192768493ca5898126fdc` / `ev_014e86e00f0bb47f77840e8e902073e403a9e7d3d90c897244a4e5bfc0771026` | `search_android_classes` 搜索 `MultiSourceRssi`。旧版服务调用位置见 §4 |

复现时可用 REA `inspect_android_method` 查询绝对 APK 路径、全限定类名、方法名。`detail=summary` 的 `summary.source.text` 提供 Java/Smali。若反编译失败，可用本轮只读反汇编辅助脚本 `../.temp/cross-review-execution/Decode.java`，以 `java -cp <jadx-headless-mcp-0.7.1-all.jar> <Decode.java> <apk> <临时输出目录> Lcom/qinl/car/pub/BleScanService` 输出带偏移的 Smali。JAR SHA-256 为 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`。

本轮验证为静态，未做 APK 安装运行、真机后台/广播记录、账号登录、钥匙认证或车辆控制。交付为复审报告。
