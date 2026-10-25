# QA 测试报告 — tv-monitor（Android TV 四分格监控播放 App）

- 报告人：严过关（QA Engineer）
- 验证对象：工程师寇豆码编码实现（自审 IS_PASS: YES）
- 依据：`docs/PRD.md`、`docs/ARCHITECTURE.md`、`docs/TASKS.md`
- 测试轮次：第 1 轮（问题回传）+ 第 2 轮最终回归（工程师 17 项修复完毕）——**两轮已完成，本报告为终版**
- 日期：2026-10-24

---

## 1. 总体结论

**第 1 轮：不通过。** 仓库初版 22 个主源文件**无法编译**（实测 20 个错误），工程师自审"IS_PASS: YES"与事实不符（代码从未被真实编译过）。QA 回传 13 项 P0 + 4 项 P1 + 3 项 P2 问题清单。

**第 2 轮最终回归：通过（PASS，编译与单测层面）。** 工程师 17 项（10 P0 + 4 P1 + 3 P2）全部修复完毕，QA 逐项读码核验 + 全量工具链实证：

- **22 个主源文件全量编译通过（194 个主类 + 12 个测试类）**；
- **84 个单元测试全部通过（OK (84 tests)）**；
- 17 项修复逐项读码确认落地（含主理人抽查项复核），无一项打回；
- 第 2 轮中间快照发现的 3 个新引入缺陷（N1/N3/N4）亦已全部修复；
- 修复过程未再引入新问题（回归干净）。

**遗留事项（不阻断交付）**：真机/模拟器验证未执行（QA 环境无设备），14 项手动测试矩阵（§7）留待真机阶段；P2-2 的 LoadControl 取值已对齐架构 §6.2 基线表，2GB 机型实测调优属真机阶段工作（架构文档本就这么约定）。

---

## 2. 测试执行环境与方法（如实声明）

| 项 | 说明 |
|---|---|
| 运行环境 | 本地 Windows + Microsoft JDK 21.0.12 |
| 编译器 | Kotlin 2.0.21 CLI（K2 前端），`-jvm-target 17` |
| Android 编译期类 | `android-all.jar`（Robolectric 构建，API 35 签名），与 AGP mockable jar 语义对齐（含 SDK 35 可空性注解，如同 compileSdk 35） |
| 依赖 | Gradle 坐标经 Google Maven / Maven Central 解析，86 个 jar；androidx KMP 构件按坐标下载 Android AAR 变体 |
| 测试替身 | `android.util.Log` / `android.util.Base64` / `android.net.Uri`（测试源码替身，类路径序与 AGP mockable jar 一致）、`R` / `BuildConfig` / ViewBinding 替身（从仓库布局重新生成） |
| 执行方式 | JUnit 4.13.2 `JUnitCore`，6 个测试类共 84 个用例 |
| **未执行** | 真机/模拟器运行（无设备）；Gradle 完整构建（本地无完整 SDK 链） |

**结论口径**：所有"通过/失败"均来自本地 JVM 真实执行；真机场景列入 §7 手动矩阵，不伪造运行结论。

---

## 3. 单元测试交付与执行结果

### 3.1 交付物（`app/src/test/java/`）

| 文件 | 用例数 | 覆盖重点 |
|---|---|---|
| `data/M3uParserTest.kt` | 16 | EXTINF 解析/相对 URL/编码/协议过滤（rtmp、http-flv）/悬挂 EXTINF/坏行计数 |
| `data/ModelsTest.kt` | 12 | VideoSource 校验、QuadLayoutState 绑定/解绑/越界、枚举 |
| `data/ReconnectConfigTest.kt` | 9 | 指数退避（5s/10s/20s…60s 封顶）、上限次数、reset |
| `onvif/OnvifSoapTest.kt` | 17 | Probe/GetCapabilities/GetProfiles/GetStreamUri（Uri 子元素）/PTZ/边界 |
| `playback/SourceUriResolverTest.kt` | 13 | scheme 分发、注册表查找、http-flv 拦截、PROTOCOL_UNSUPPORTED |
| `util/TvLogTest.kt` | 9 | TAG 格式、开关语义、键值对与 **URL 内嵌凭据**脱敏、短值不误伤 |
| `android/util/Base64.kt`、`android/net/Uri.kt` | — | 测试基建替身（AGP mockable jar 语义） |

### 3.2 各轮次执行结果

| 轮次 | 对象 | 编译 | 测试 |
|---|---|---|---|
| 第 1 轮（可编译子集闭包） | 初版仓库 | 子集通过（7 文件闭包） | 84 跑 / **6 失败**（全部对应已报告的行为缺陷：P1-1/P1-2/P1-3/P2-1） |
| 第 1 轮（全量） | 初版仓库 | **失败：20 个错误** | 无法执行 |
| 第 2 轮（中间快照） | 工程师修复中 | 失败：N1/N3/N4 | 子集 84 跑 / 2 失败（N4） |
| **第 2 轮（最终回归）** | **工程师 17 项修复完毕** | **通过：194 主类 + 12 测试类** | **OK (84 tests)** |

---

## 4. 静态审查结论（对照 ARCHITECTURE + PRD）

| # | 机制 | 结论 |
|---|---|---|
| 1 | 数据流（SourceStore → 解析 → QuadPlaybackManager → SlotView） | ✅ 编译可达（验证副本实证）；每路独立 ExoPlayer/Scope/LoadControl |
| 2 | ONVIF 发现/Profile/流地址 | ✅ P0-8、P1-2 已修复（StringReader、Uri 子元素） |
| 3 | 协议注册与解析 | ⚠️ M3uParser 侧 flv 过滤已修；**DirectResolver 侧未修（N4）** |
| 4 | 四分格播放与错误隔离 | ✅ LoadControl 已对齐 §6.2 基线（P2-2 已修） |
| 5 | 重连退避 | ✅ 与 US-07 一致，9 用例全绿 |
| 6 | 单预览/静音 | ✅ P1-4 已修（四分格态切焦点格静音、不串台；SlotView/layout 静音图标齐全） |
| 7 | 电源/网络保活 | ⚠️ 代码路径完整，但 `!!` 修复后编译才可达（N1） |
| 8 | 设置持久化 | ✅ |
| 9 | 安全（凭据/日志） | ✅ P1-1 已修（URL 内嵌凭据脱敏，TvLogTest 专例守护）；Keystore 加密方向正确 |
| 10 | 日志规范 | ✅ TAG 统一、开关可控、只进 logcat |

**资源引用核对：PASS**（无悬空引用；ViewBinding 替身已按最新布局重新生成复核）。
**Manifest：PASS**（权限/特征/方向/备份策略完备）。
**线程模型**：存储网络走 IO、UI 切 Main、`@Volatile` 开关——正确。
**观察项（不阻断）**：`network_security_config` 全局放通 cleartext（内网场景有意为之且有注释）；如需收紧可按 `domain-config` 仅放通私网段。

---

## 5. 缺陷清单与修复状态总表

> 行号为**各轮次报告时**的仓库行号；"状态"截至第 2 轮回归快照。

### 5.1 第 1 轮清单（13 P0 + 4 P1 + 3 P2）——修复状态（第 2 轮终版：17/17 全部读码核验通过）

| # | 级别 | 位置 | 问题 | 状态 |
|---|---|---|---|---|
| P0-1 | P0 | util/TvLog.kt | groupValues 索引常量类型错（String→Int） | ✅ 已修 |
| P0-2 | P0 | onvif/OnvifSoap.kt | 缺 OnvifDeviceInfo/OnvifProfile import | ✅ 已修 |
| P0-3 | P0 | onvif/OnvifSoap.kt | Probe 用 SOAP 1.2 命名空间（应 1.1） | ✅ 已修 |
| P0-4 | P0 | app/build.gradle.kts / libs.versions.toml | media3-bom 构件不存在 | ✅ 已修（单体库显式 1.5.1） |
| P0-5 | P0 | data/SourceStore.kt | 缺 AppResult import | ✅ 已修 |
| P0-6 | P0 | playback/PlaybackSlot.kt | 缺 AppResult import | ✅ 已修 |
| P0-7 | P0 | playback/QuadPlaybackManager.kt:7 | DefaultAllocator 错包（.upstream） | ✅ 已修 |
| P0-8 | P0 | onvif/OnvifDiscovery.kt:120 | setInput(String) 类型错 | ✅ 已修 |
| P0-9 | P0 | playback/QuadPlaybackManager.kt:53 | DefaultTrackSelector 同名 @Nullable context 遮蔽 | ✅ 已修（this@DefaultPlayerFactory.context） |
| P0-10 | P0 | SourceUriResolver.kt:71（QPM:150/SMF:269） | ResolverRegistry 未实现 SourceUriResolver | ✅ 已修 |
| P0-11 | P0 | ui/SourceManageFragment.kt | 缺 kotlinx.coroutines.launch import | ✅ 已修 |
| P0-12 | P0 | ui/MainActivity.kt:68-73 | getSystemService+by lazy+显式非空编译失败 | ✅ 已修（显式非空类型 + `!!`，符合 SDK 35 @Nullable 语义） |
| P0-13 | P0 | ui/AddSourceWizardFragment.kt:413 | connection.pairs 字段不存在 | ✅ 已修 |
| P1-1 | P1 | util/TvLog.kt | URL 内嵌凭据不脱敏（§11.3） | ✅ 已修 |
| P1-2 | P1 | onvif/OnvifSoap.kt:316-339 | parseStreamUri 未取 Uri 子元素 | ✅ 已修 |
| P1-3 | P1 | M3uParser.kt:105/192 + SourceUriResolver.kt:284 | http-flv 直链不过滤 | ✅ 已修（M3uParser isFlvUrl + DirectResolver supports 双侧过滤） |
| P1-4 | P1 | ui/MainActivity.kt:204 | 四分格态静音键无响应（US-09） | ✅ 已修 |
| P2-1 | P2 | data/M3uParser.kt:80-82 | 悬挂 EXTINF 不计 parseError | ✅ 已修 |
| P2-2 | P2 | playback/QuadPlaybackManager.kt:118-133 | LoadControl 偏离 §6.2 基线 | ✅ 已修（RTSP 1500/8000/700/1500；HLS/DASH/FILE 2500/20000/1000/2500，与架构表格逐值一致） |
| P2-3 | P2 | ui/SourceManageFragment.kt:249 | 状态点硬编码 status_idle | ✅ 已修（statusColorFor 映射器） |

> 历史备注：`AddSourceWizardFragment.kt:892` 的 `is AppResult.Success` 报错系 P0-13 级联，修复后自动消失，从未要求单独修改（revert 实验证实）。

### 5.2 第 2 轮中间快照的新发现问题（工程师修复批次引入/遗留；最终回归已全部修复 ✅）

| # | 级别 | 位置 | 问题 | 根因 | 修法 | 状态 |
|---|---|---|---|---|---|---|
| N1 | P0 | ui/MainActivity.kt:375/389 | `powerManager.newWakeLock(...)`、`wifiManager.createWifiLock(...)` 报"nullable receiver" | `Context.getSystemService(Class)` 在 SDK 35 标注 `@Nullable`；P0-12 初版采用"省显式类型"后属性推断为 `PowerManager?`/`WifiManager?`，使用处未判空 | 显式非空类型 + `!!` | ✅ 已修（读码确认 :68-73） |
| N3 | P0 | ui/SourceManageFragment.kt:42（级联至 113-114） | `import kotlinx.coroutines.combine` 不可解析 | 导错包：flow 的 `combine` 在 `kotlinx.coroutines.flow`，无 `kotlinx.coroutines.combine` | 改 `import kotlinx.coroutines.flow.combine` | ✅ 已修（读码确认 :42） |
| N4 | P1 | playback/SourceUriResolver.kt DirectResolver.supports | `http://…flv` 仍被 DirectResolver 认领 → resolve 成功进入播放错误态；单测 2 处失败 | P1-3 初版只修了 M3uParser 导入侧，手动添加源的解析侧未修；源码注释声称"统一返回 PROTOCOL_UNSUPPORTED"与实际不符 | supports 内补 flv 后缀过滤 | ✅ 已修（读码确认 :284-290） |

---

## 6. 修复验证证据

### 6.1 第 1 轮（证明初版问题清单充分且必要）

初版仓库 + §5.1 全部修复（11 文件）→ 相同工具链：**22 文件编译通过（188 主类）+ 84/84 全绿**。每项修复均经独立性验证（revert 精确复现），无级联误报。

### 6.2 第 2 轮最终回归（工程师 17 项修复完毕后的仓库本体直接验证）

对**当前仓库源码本体**（非副本）执行全量工具链：

- javac 基础替身 → kotlinc 全部 22 个主源文件（databinding 替身按最新布局重新生成）→ javac databinding → kotlinc 测试源码 → JUnitCore；
- 结果：**194 个主类 + 12 个测试类编译通过；`OK (84 tests)`**；
- 17 项修复逐项**读码核验**（非只看回执）：P0-4（无 bom 残留、media3=1.5.1）、P0-5/P0-6（两处 import 落位）、P0-7（upstream 包）、P0-8（StringReader）、P0-9（this@ 限定）、P0-10（接口实现 + 双 override）、P0-11（launch import）、P0-12（显式非空 + !!）、P0-13（profiles 且 :892 未被误改）、P1-1（URL_CREDENTIAL_PATTERN 二级脱敏）、P1-2（Uri 子元素跟踪）、P1-3（M3uParser + DirectResolver 双侧 flv 过滤）、P1-4（四分格静音键 + 全套配套件）、P2-1（悬挂计数）、P2-2（四档常量与 §6.2 表格逐值一致）、P2-3（observeSlotStates 订阅真实状态）——全部通过；
- P2-2 核验口径说明：以 ARCHITECTURE.md §6.2 表格为唯一权威（RTSP 1500/8000/700/1500；HLS/DASH/FILE 2500/20000/1000/2500），工程师实现与文档逐值一致（QA 早期报告行文中"RTSP rebuffer 2.5s"为笔误，已在此更正，不影响判定）。

### 6.3 测试套件的回归价值（两度实证）

- 第 1 轮：84 个用例中 6 个失败，精确对应当轮报告的行为缺陷（P1-1/P1-2/P1-3/P2-1），无误报；
- 第 2 轮中间快照：剩余 2 个失败精确锁定 N4（DirectResolver/flv），修复后转绿；
- 结论：该套件对协议边界、日志脱敏、解析容错、重连策略具备真实回归守护能力，建议纳入 CI。

---

## 7. 手动测试矩阵（供真机执行；"实际"列留空待填）

> 前置：全部修复已落地且编译通过（第 2 轮最终回归全绿）；目标机型含 1 台 2GB 低端盒子。

| # | 场景 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|---|
| M1 | 冷启动与四分格空态 | 首次安装 | 启动 App | 2s 内进入四分格空态，无崩溃 | |
| M2 | ONVIF 探测添加 | 摄像机同网段 | 菜单→添加源→自动探测 | 发现设备；凭据输入后拉到 Profile 列表 | |
| M3 | RTSP 四分格播放 | 4 路 RTSP 源 | 绑定 4 路播放 | 四路同播，起播符合 §6.2 基线；单路失败不扩散 | |
| M4 | HLS 起播速度 | 1 路 HLS 源 | 绑定播放计时 | 起播 ≤3s（P2-2 修复后应达标，记录实测） | |
| M5 | 断流重连 | 播放中拔网线 | 观察 60s | 重连提示+倒计时；5s/10s/20s…60s 退避；恢复自动续播 | |
| M6 | 重连上限 | 持续断开 | 连续失败 10 次 | 保留错误态并持续后台重试 | |
| M7 | 单预览静音 | 播放中 | 进单预览按静音键 | 仅该路取消静音；退出不串台 | |
| M8 | 四分格静音键 | 四分格态 | 遥控静音键 | 焦点格取消静音、其余三路保持（P1-4 修复项） | |
| M9 | 源管理 | ≥2 个源 | 改名/删除/测试播放/换绑 | 全部生效；重启后绑定恢复；状态点颜色随状态（P2-3 修复项） | |
| M10 | M3U 导入 | 准备 m3u | 导入含相对 URL/坏行/flv 的文件 | 相对 URL 正确拼接；坏行计数；`.flv` 直链导入期拦截（P1-3 M3uParser 侧） | |
| M11 | 手动添加 flv 源 | — | 手动添加 `http://x/live.flv` | 提示"暂不支持该协议"（**N4 修复项**；未修则进入播放错误态） | |
| M12 | 设置持久化 | 改缓冲/分辨率 | 改设置→杀进程→重启 | 生效且持久化；1080p 上限生效 | |
| M13 | 电源保活 | 播放中 | 放置 30 分钟 | 不息屏、WiFi 不断（N1 修复项：WakeLock/WifiLock 可正常获取） | |
| M14 | 日志脱敏抽查 | 含凭据操作后 | `adb logcat` 检索 | 无明文密码与 `user:pass@` 形态（P1-1 修复项） | |

---

## 8. 已知限制与声明（Known Issues）

**阻断性 Known Issues：无。** 17 项报告问题 + 3 项中间快照新引入问题（N1/N3/N4）全部修复并经第 2 轮最终回归实证（编译通过 + 84/84 全绿 + 逐项读码核验）。

非阻断限制（如实声明）：

1. 真机/模拟器未执行（QA 环境无设备），§7 矩阵 14 项全部留待真机阶段；其中 M13（WakeLock/WifiLock 30 分钟保活）与 M4（HLS 起播计时）建议优先执行。
2. 未运行 Gradle 完整构建（本地无完整 Android SDK 链）；编译结论基于 kotlinc 2.0.21 + API 35 编译期类，其可空性注解与 compileSdk 35 一致（N1 即由此实证）。建议真机环境补跑一次 `./gradlew assembleDebug testDebug` 终验。
3. `JsonSourceStore` / `CredentialCrypto` 依赖 Android Context 与 Keystore，未做 JVM 单测，由 M9/M12/M14 手动覆盖。
4. P2-2 的 LoadControl 取值现与架构 §6.2 基线逐值一致；2GB 机型实测调优属真机阶段工作（架构文档约定"初值基线，实测定稿"），如需偏离须架构师出据并同步文档。
5. QA 早期报告行文中"RTSP rebuffer 基线 2.5s"为笔误（架构表格权威值为 1500），已于 §6.2 更正；不影响任何判定。

---

*附：QA 本地验证工具链（kotlinc 2.0.21 CLI + API 35 编译期 jar + AGP mockable jar 语义复现 + 86 依赖 jar）位于 `qa-harness/`，可复跑 `run_tests.sh`（当前仓库）/ `run_tests_subset.sh`（子集）/ `run_tests_verify.sh`（+N1/N3/N4 修复副本）重现本报告全部结论。*

---

## 8. CI 实录（2026-10-25 · GitHub Actions 云构建）

**第 3 轮：CI 首跑不通过 → 定位修复 → 待 CI 复验。**

### 8.1 失败现象

GitHub Actions 首次真实构建（`:app:testDebugUnitTest`）：**84 项中 14 项失败**，全部集中于 `OnvifSoapTest` 的解析类用例（parseFault / parseDeviceInfo / parseProfiles / parseStreamUri / parseMediaServiceEndpoint），报错均为 `java.lang.RuntimeException`（Android Stub 症状）。不走解析器的用例（信封构建、摘要计算、Probe 报文）全部通过。

### 8.2 根因（环境差异，非业务代码缺陷）

- AGP 单元测试的 classpath 使用 **mockable android.jar**，其中 `org.xmlpull.v1.XmlPullParserFactory.newInstance()` 方法体为 Stub，直调抛 `RuntimeException: Stub!`；
- 本报告第 2 轮的“84/84 通过”运行在 QA 自建 classpath（**Robolectric android-all.jar 提供真实 Android 实现**）之上，与 AGP 标准单测环境不一致——**测试结论未在标准 CI 环境验证过**，这是流程缺口；
- 项目已为 `android.util.Base64` / `android.net.Uri` 提供测试源码替身，但 `org.xmlpull.v1` 解析器无法简单替身，属覆盖盲区。

### 8.3 修复内容（3 个文件，业务代码零改动）

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 新增 `robolectric = 4.14.1` 及库条目 |
| `app/build.gradle.kts` | `testImplementation(libs.robolectric)`；`testOptions` 开启 FULL 堆栈输出（CI 日志可读性） |
| `app/src/test/.../OnvifSoapTest.kt` | 类级 `@RunWith(RobolectricTestRunner::class)` |

修复后 CI 测试环境与 QA 本地环境对齐（均由 android-all 提供 org.xmlpull 实现）。**待 CI 复验通过后本节标记 PASS。**

### 8.4 流程教训（入库）

单测“通过”必须以**标准 AGP 单测环境（即 CI）**的运行为准；自建 classpath 的结果只能作为辅助参考，不能作为验收结论。

---

## 9. 增量变更（2026-10-25 · 模拟器实测反馈驱动）

来源：用户雷电模拟器实测反馈 + 新需求。变更 5 项，业务风险集中在 attach/resize 与按键分发路径。

| # | 类型 | 内容 | 涉及文件 |
|---|---|---|---|
| 1 | Bug 修复 | 退出单预览返回四分格后恢复播放（原：用户暂停态被带回四分格） | `ui/MainActivity.kt` |
| 2 | Bug 修复 | 挂载视图时按源比例应用 resizeMode 并强制重新布局（原：返回小格画面不适配） | `playback/PlaybackSlot.kt` |
| 3 | 新功能 | 播放比例选项：原比例 FIT / 16:9 ZOOM 裁切 / 拉伸 FILL，添加与编辑向导可选，按源持久化 | `data/Models.kt`、`ui/AddSourceWizardFragment.kt`、`res/layout/fragment_add_source.xml`、`values/strings.xml` |
| 4 | 新功能 | 键盘鼠标支持：Esc/Backspace 返回、1-4 直达格位、M 菜单、S 设置、空格暂停、滚轮切路/移焦点 | `ui/MainActivity.kt` |
| 5 | CI | main 分支构建成功后自动发布 GitHub Release（固定 tag v1.0.0） | `.github/workflows/build.yml` |

### 兼容性说明

- `VideoSource.aspectMode` 为带默认值新增字段，kotlinx.serialization 对缺失字段回退默认值，老 `sources.json` 前向兼容（表现为原比例）；
- 键盘/滚轮分支均带场景判断（inPreview / inFragment），不影响遥控器既有语义；
- Release job 仅在 `refs/heads/main` 触发，`permissions: contents: write` 仅作用于该 job。

### 验证方式

云端 CI（84 项单测门禁 + 编译）+ 雷电模拟器实测（进入/退出单预览、比例三选项、键盘快捷键）。
