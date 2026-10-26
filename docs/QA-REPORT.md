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

---

## 10. 增量变更（2026-10-25 · 二轮反馈：单预览返回冻结整体修复 + 4 项新需求）

来源：用户二轮反馈（上一轮 resume() 修复未覆盖真实根因 + 4 项交互需求）。
变更 5 项，核心风险集中在 PlayerView surface 切换路径。

| # | 类型 | 内容 | 涉及文件 |
|---|---|---|---|
| 1 | Bug 修复（根因级） | 单预览返回后画面冻结（用户观感=暂停）：attach 顺序改为"先挂新视图再摘旧视图"（对齐 media3 官方 `Util.switchTargetView` 模式）。旧代码先摘后挂，每次进出预览都走"清 surface → 解码器释放 → 重建"，在雷电模拟器/低端盒上重建挂起即冻结 | `playback/PlaybackSlot.kt`、`playback/QuadPlaybackManager.kt` |
| 2 | Bug 修复（兜底） | surface 切换 stall 看门狗：挂载后 5s 未回 STATE_READY 且应播，自动释放并重建 player 恢复（8s 窗口 + 15s 冷却，避免与网络缓冲混淆） | `playback/PlaybackSlot.kt` |
| 3 | 新功能 | 源管理页点击/确认已添加的源 = 进入编辑向导（原为测试播放；测试播放改由菜单键入口） | `ui/SourceManageFragment.kt` |
| 4 | 新功能 | 预览 chrome 定时隐藏：左上角流信息（标题）+ 静音图标进入预览 3s 后消失（Info 键/静音键/切路复现 3s）；底部操作方式每次进入预览显示 5s 后消失 | `res/layout/activity_main.xml`、`ui/MainActivity.kt` |
| 5 | 新功能 | 底部键盘操作提示（主界面常显两行：遥控 + 键盘）；设置页新增"开机自动启动"开关（BootReceiver 读取后拉起主界面） | `values/strings.xml`、`data/Models.kt`、`data/SettingsStore.kt`、`ui/SettingsFragment.kt`、`res/layout/fragment_settings.xml`、`BootReceiver.kt`、`AndroidManifest.xml` |

### 根因分析（入库）

- media3 `PlayerView.setPlayer(null)` → `ExoPlayer.clearVideoSurfaceView(holder)`：**身份守卫**（`surfaceHolder == this.surfaceHolder` 才清除）——先给新视图挂 player 再摘旧视图时，摘旧视图的 clear 命中守卫为空操作，**不释放解码器**；
- SurfaceView surface 生命周期绑定视图可见性（API < 34）：overlay 隐藏即销毁 surface，故进入预览存在"等 surface 创建"窗口（既有行为，观感正常）；
- 结论：surface 直切 + 身份守卫 = 不释放解码器； stall 看门狗 = 挂起场景 5s 内自愈。

### 兼容性说明

- `AppSettings.autoStart` 带默认值新增字段，SharedPreferences 缺键回退 false（默认关，不改变既有升级行为）；
- 源列表点击语义变更（测试播放 → 编辑），测试播放仍可通过菜单键操作菜单进入；
- 预览 chrome 自动隐藏不影响任何既有按键语义（Info/静音/切路均会复现 chrome）。

### 验证方式

云端 CI（单测门禁 + 编译 + 自动 Release b8）+ 待雷电模拟器实测（进入/退出单预览冻结复测、点击编辑、chrome 3s/5s 隐藏、开机启动开关）。

---

## 11. 增量变更（2026-10-25 · 三轮反馈：单预览返回冻结根因级修复——双播放器架构）

来源：用户三轮反馈（b8"surface 直切 + stall 看门狗"在雷电模拟器仍 100% 冻结，两次进出均复现）。

### 11.1 根因（现场取证 + media3 源码逐行核对）

现象：主界面按 `1` 进入单预览播放正常，Esc 返回四分格后该格画面永久冻结在最后一帧（2 分钟观察 + 两次进出均复现；再次进入单预览解码器是活的，证明解码器没死，是 surface 热切换后渲染循环没恢复）。

日志证据（logcat，tag TvMonitor）：

- 退出单预览后该 slot 无任何后续日志：无强制恢复、无重连、无错误；
- framework 同期日志：`MediaCodec: [OMX.google.h264.decoder] setting surface generation` + `SurfaceUtils: connecting to surface ... / disconnecting from surface ...`——`MediaCodec.setOutputSurface()` 调用成功，解码器确实切到四分格 surface 并渲染了最后一帧，但之后不再产出新帧；
- 播放器始终 STATE_READY、playWhenReady=true → b8 的 surface stall 看门狗（检测到 READY 即退出）不触发；重连逻辑（只认 PlaybackException）不触发 → 永久冻结。

根因结论：**对运行中的软件解码器执行 setOutputSurface 后，该解码器停止出帧**——media3 官方 issue #1433 已知模式（"video remains stuck on the initial frame, although the audio continues to play correctly"）。雷电模拟器（x86 无硬件解码器，用 OMX.google.h264.decoder）必现；部分真机盒子也需 `codecNeedsSetOutputSurfaceWorkaround` 兜底。

历史方案复盘：b7"先摘后挂"（清 surface → 解码器释放重建）冻结；b8"先挂后摘"（无缝 setOutputSurface）也冻结。**结论：本环境对运行中的 player 做 surface 热切换不可靠，必须消除热切换本身。**

### 11.2 修复方案：双播放器架构（previewSlot）

media3 维护者在 #1433 的官方建议原话："You can use two different surfaces/views and just mask one depending on which player needs to play"。

核心思想：四分格 player 全程不摘卸、不停播；单预览 overlay 使用独立的第 5 个 PlaybackSlot（previewSlot），有自己的 player、surface、状态机。进入预览 = 起预览 player；退出 = 释放预览 player；四分格 player 从未被触碰 → 返回即时是活的。彻底不存在 surface 热切换。

| # | 文件 | 改动要点 |
|---|---|---|
| 1 | `playback/QuadPlaybackManager.kt` | 新增第 5 个 PlaybackSlot（previewSlot，下标 4 仅日志用），复用 PlaybackSlot 全部机制（状态机/重连/首帧 watchdog/统计/静音）；`enterSinglePreview` = previewSlot.bind(source) + attachView(overlay) + setMuted（四分格 slots 全程不碰）；`exitSinglePreview` = detachView(overlay) → bind(null) 释放（顺序不能反）；`switchSinglePreview` = previewSlot 换源（同视图 setMediaItem+prepare，旧 codec 整体释放/新 codec 新连 surface，属正常路径）；`toggleMute` 同步预览 player；新增 `pausePreview/resumePreview`；`release()` 追加 previewSlot.release() |
| 2 | `ui/MainActivity.kt` | 新增 `observePreviewState()` 协程 collect `previewState`（预览 overlay 的加载/错误/重连倒计时直接反映预览 player 真实状态，取代原四分格状态代理）；`exitSinglePreview` 删除 `slots[it].resume()` 补丁；`refreshPreviewOverlay`/`showInfoOverlay` 数据源改 `previewState`；`togglePlayPause` 改调 `pausePreview/resumePreview` |
| 3 | `playback/PlaybackSlot.kt` | `prepareMedia` 增加绑定时效校验（2 行，非重构）：双播放器架构下 bind 是异步起流（解析可能走网络），解析在途时若已退出预览 / 连续切路，丢弃在途结果——否则会起预览态之外的孤儿流（后台出声/占带宽）或起错源。既有 attach/detach/bind/release 与 stall 看门狗均未改动 |

范围说明：第 3 项是双播放器 bind 生命周期的必要竞态防护（不加上线，"进入预览后立刻 Esc"与"快速连续切路"会引入比原 bug 更隐蔽的新缺陷），故超出主理人给定文件清单，特此申报。

### 11.3 取舍（如实声明）

- **收益**：返回四分格即时是活的（四分格 player 从未被触碰）；彻底消除 surface 热切换类冻结（含未来其它 surface 切换路径）。
- **代价**：预览期间该路带宽/解码开销 x2（四分格仍在后台播同一路）；预览起播比热切换多一次起流耗时（RTSP 约 1-2s，观感为"进入预览后稍候出画"，可接受）。
- 模拟器上 5 路软件解码 CPU 压力高于 4 路，低端真机需关注（当前用户环境为雷电模拟器，无真机数据）。

### 11.4 验证方式与实测结论

云端 CI（84 项单测门禁 + assembleDebug + 自动 Release）+ 雷电模拟器实测。

**实测结论（b10，2026-10-25 下午，CI run #10 全绿后下载 Release APK 覆盖安装）**：

- **主验收（返回不冻结）**：进入单预览（slot0）→ Esc 返回 → 间隔 4s 连截两张图，左上格画面持续流动（同一纪录片镜头平移；另一次返回后直接切换到全新场景）；logcat 显示 `slot=4 解绑` + `退出单预览，恢复四分格（slot=0 全程未触碰）`，framework 层退出瞬间仅释放预览 player 自己的 codec surface，**四分格 codec 无任何 setOutputSurface**（b8 同位置必有 `setting surface generation`）；
- **对照实验**：无任何预览操作时，若上游 CDN live edge 停滞（manifest 序列号 10s 不变 / 直连 curl 为空），四路同样静止——证明"静态帧"是上游行为而非播放器卡死；上游活跃窗口内不进预览帧也在持续变化；
- **回归**：进/出/切路（左右键，日志 `单预览切路：1 -> 3`）、预览内静音（Toast + 图标）、播放暂停（标题"（已暂停）"）、返回后静音图标同步、预览错误态（死链显示"网络中断，X 秒后第 N 次重试"倒计时，info 面板统计取预览 player）均正常；
- **源管理页点击编辑入口**：与 b8 做 A/B（同序列 M → tap 行1 / D-pad），行为字节级一致——模拟器 `input tap` 不触发行点击、D-pad 焦点穿透到四分格均为 b8 既有表现，非本次回归；
- **已知遗留**：预览期间该路带宽/解码 x2；预览起播比热切换多 1-2s 缓冲（HLS）；测试源（cctv9hd/cztv）本身间歇性不可用，实测窗口需避开上游停滞期。

---

## 12. 增量变更（2026-10-25 · 四轮反馈：b10 双播放器实测 FAIL——SurfaceView 图层生命周期触发模拟器合成器异常）

来源：QA 独立实测判定 b10 FAIL——主验收不过，且出现比原 bug 更严重的新症状（非预览格串台）。3 次独立复现，已排除上游流停顿与测试方法问题（探针法证明两路源当时均活）。

### 12.1 QA 失败现象与鉴别（采信）

- **现象**：单预览中预览 player **已渲染画面后**退出 → ①该格冻结；②**非预览格串显被预览流的内容**（预览 slot0 后 slot1 格显示 CCTV9 内容；预览 slot1 后 slot0 格显示浙江卫视内容）；③连 player 重建（新 codec + 新 surface generation）都不恢复；
- **日志**：退出后四分格 player 仍在正常重连/起流（`slot=1 起流`、`slot=0 第 1 次重连`）——**player 活着，但 player→瓷砖的画面链路被永久破坏**；
- **相关性**：预览未渲染时退出（黑屏退出 / 死链预览）瓷砖不受影响；**预览已渲染时退出必冻**；
- **对照实验**：源管理 Fragment（全屏 overlay 但无 SurfaceView）开关后两格照常播放 → 冻结**专属于单预览 PlayerView/SurfaceView 生命周期**。

### 12.2 二轮根因

预览 overlay 的 SurfaceView 图层"**创建 → 收到帧 → 销毁**"这套生命周期触发了雷电模拟器合成器的异常（每格 SurfaceView 是独立 SurfaceFlinger 图层；app 启动日志即有 `duplicate layer name` 告警，模拟器对同名图层的增删处理可疑）。四分格 SurfaceView 图层自 inflate 起稳定不变本无事，是预览图层的建立 + 收帧 + 销毁把其它图层的呈现带崩。b8 时代该图层也在，但被"热切换冻结"掩盖，且退出时格位 player 重新 attach 恰好把该格 surface 重建了，所以没观察到串台。

### 12.3 修复方案

| # | 文件 | 改动要点 |
|---|---|---|
| 1 | `res/layout/activity_main.xml` | `single_preview_player` 的 `surface_type` 由 `surface_view` 改 **`texture_view`**：TextureView 在 app 窗口内合成，不产生独立 SurfaceFlinger 图层；预览 surface 的建立/销毁完全不碰图层栈，四分格 SurfaceView 图层不受扰动。布局内已加警示注释防回改 |
| 2 | `res/layout/activity_main.xml` | `single_preview_title` maxWidth 360dp → 560dp（重连倒计时"网络中断，X 秒后第 N 次重试"原先被截断） |
| 3 | `res/layout/activity_main.xml` | `single_preview_player` 的 `keep_content_on_player_reset` true → false：死链预览 / 切路不再显示上一路残留帧（误导用户以为旧内容还在播），改为加载 / 错误态黑 shutter。代价：切路 ~0.5-2s 黑屏，属正常视频 UX |
| 4 | `playback/PlaybackSlot.kt` | `prepareMedia` 失败分支补 `source === src` 守卫，与成功分支对称：解析在途时已换绑 / 解绑则丢弃本次失败，避免对新源产生无谓的重连计数 / 倒计时噪声 |

### 12.4 验证结论

云端 CI（run #12 全绿，84 项单测 + assembleDebug + Release）+ 雷电模拟器实测（b12 APK 覆盖安装，asset `tv-monitor-1.0.0-debug-b12.apk`）。

**主验收（预览已渲染后退出不冻结、不串台）——通过**：

- Round 1 / 2 / 3 + 30s 停留轮（A4）：slot0 预览 10s → Esc → 每 4s 连截 3 张，返回后两格均在动（slot0 91%/93%、slot1 79%+；Round3 首段 0% 经目视觉确认为纪录片静止镜头，次段 90.8% 证明活性）；
- 逐格裁剪对比（非整屏哈希），返回后无串台：slot0=CCTV9《致命丛林》、slot1=cztv 蓝天各自内容；
- framework 层日志：退出瞬间仅释放预览 player 自己的 codec surface，`退出单预览，恢复四分格（slot=1 全程未触碰）`。

**反向串台验证——通过**：预览 slot1 后 slot0 显示 CCTV9 内容且 93.3% 强动态，无浙江卫视/cztv 串入。

**回归——全过**：B1 秒退无孤儿流（解绑后无 slot=4 后续活动）；B2 快速切路 0→1→0→1→3 终态正确；C2"（已暂停）"；C3 静音 Toast + 返回图标同步；C4"网络中断，54 秒后第 8 次重试"全文完整（560dp 生效）；C5 Info 统计取预览 player；C6 源管理页正常。

**texture_view 副作用评估——可接受**：预览正常渲染（豹子/尼罗鳄/小象/玉米广告等）；死链预览黑色 shutter（keep_content_on_player_reset=false 生效，无上一路残留帧）；FIT 与 RATIO_16_9 冒烟通过（16:9 内容在 16:9 视图下三模式目视无差异属预期，resizeMode 为视图层共享机制）。现场已恢复全 FILL 设置。

**最终疑点排查（slot1 长时间 0% 静态）——判定为上游冻结，非本 bug 复发**：

- 时序：18:45-18:46 连续三帧 slot1 0% 静态，**当时并未进行任何预览操作**（18:47:35 才进入 slot1 预览）——冻结早于且独立于预览退出；
- 上游证据：curl `https://ali-m-l.cztv.com/channels/lantian/channel008/1080p.m3u8` 得 `#EXT-X-MEDIA-SEQUENCE:360257101`，与一轮取证时（数小时前）完全一致——**上游 live edge 已停止推进**；
- QA 探针法：预览 player（全新 bind 同一 URL）18:47-18:52 渲染 100% 动态画面（播放冻结播放列表内的存量片段），约 5 分钟后片段耗尽，自 18:53:23 起每 ~30s `slot=4 第 1 次重连`——**全新 player 同样无法从该上游持续取流**，证明非该 player 个体卡死；
- 退出后（18:55:59，日志 `slot=4 解绑` + `slot=1 全程未触碰`）连截 12s：slot1 仍 0%，但与 18:46 冻结帧 93.2% 不同、区域亮度 101.8→121.4（真实内容非黑 shutter）——四分格 player 存活，18:46-18:56 间推进过画面（追踪上游间歇性供流），当前再次冻结与上游同步；
- 无串台：终帧逐格扫描 slot0 93.3% 在动（CCTV9 鬣狗）、slot1 0%（cztv 蓝天《海天雄鹰》自身内容）、slot2 0%（历史性静态，R1/R2/A4 各轮即 0%，不在验收范围）、slot3 1.4%（浙江民生上游死，首帧超时重连循环）；
- 历史佐证：b10（旧架构）一轮验收期同样出现过无预览参与的四分格静态帧，经 A/B 对照 + manifest 探针归因同一 CDN live edge 冻结——该上游停滞现象与本次架构改动无关。

**遗留项（如实申报）**：stall 看门狗只覆盖"首帧超时"，不覆盖"播放中上游停供"的 mid-stream 冻结（slot1 静默冻结 10 分钟无重连动作）——既有设计，非本轮引入；如需增强（如基于渲染帧间隔的二次看门狗）可另立需求。

---

## 13. 增量变更（2026-10-26 · 三轮反馈：源管理交互 + 界面清理 4 项）

来源：用户三轮反馈（b12 交付后）：①源管理列表显示通道号、点击多次才进编辑；②四分格底部提示取消、提示移入退出框；③四分格源标题不显示；④单预览比例不一致时透出四分格画面。

| # | 类型 | 内容 | 涉及文件 |
|---|------|------|----------|
| 1 | Bug 修复（根因级） | 源管理"点很多下才进编辑"：行布局移除 `focusableInTouchMode`。AOSP `View.onTouchEvent` ACTION_UP：`focusTaken = isFocusable() && isFocusableInTouchMode() && !isFocused(); if (!focusTaken) performClick();`——未聚焦行首次点击只抢焦点不触发点击，每行都要点两次 | `res/layout/item_source.xml` |
| 2 | Bug 修复（加固） | 点击改用 bind 时固化的源引用，不再依赖 `bindingAdapterPosition`（适配器刷新窗口内为 NO_POSITION，`getOrNull(-1)` 静默丢失点击）；状态点仅在变化时刷新（消除 500ms 无条件 `notifyDataSetChanged` 的点击丢失窗口） | `ui/SourceManageFragment.kt` |
| 3 | 新功能 | 源列表显示通道号（绑定格位 1~4 / 未绑定），订阅 `layoutState` Flow | `ui/SourceManageFragment.kt`、`res/layout/item_source.xml`、`values/strings.xml` |
| 4 | 新功能 | 源管理进入页面聚焦首行（一次性守卫）：修复菜单键上下文菜单无效（focusedChild 恒为 null）、D-pad 焦点穿透到四分格、确认键编辑入口缺失 | `ui/SourceManageFragment.kt` |
| 5 | UI 变更 | 四分格底部常显提示条取消（`hint_bar` 移除，QuadGridView 铺满全屏，底部不留黑）；操作提示并入退出确认对话框 | `res/layout/activity_main.xml`、`ui/MainActivity.kt`、`values/strings.xml` |
| 6 | UI 变更 | 四分格槽位源名标题移除（保留状态点/静音图标） | `res/layout/view_slot.xml`、`ui/SlotView.kt` |
| 7 | Bug 修复 | 单预览 fit 黑边透出四分格：TextureView 黑边区透明，overlay 根与 PlayerView 原无背景。改双不透明黑底（根 + PlayerView） | `res/layout/activity_main.xml` |

### 根因分析（入库）

- **focusTaken 机制**（item 1）：`focusableInTouchMode="true"` + 未聚焦 → 首次点击 `requestFocus()` 后 `performClick()` 被跳过。这是 TV/列表场景经典坑；按钮不设该属性所以一次即灵。移除后首次点击即触发；`focusable="true"` 保留，D-pad/遥控焦点不受影响。实测：b14 起 `input tap`/`input mouse tap` 一次点击即打开编辑向导且地址预填（b12 及以前同坐标多次点击无响应）。
- **黑边透出机制**（item 7）：TextureView 只绘制视频纹理区域；`resize_mode=fit` 时 AspectRatioFrameLayout 按视频宽高比收缩内容帧，PlayerView 其余区域无背景即透明，透出底层四分格。加载期（首帧前）同样透出。修复：overlay 根与 PlayerView 均设 `@color/black` 双保险。
- 点击加固（item 2）：`notifyDataSetChanged()` 后至下次布局前 `bindingAdapterPosition` 返回 NO_POSITION；500ms 状态轮询刷新使该窗口反复出现，是"点了没反应"的次要诱因。

### 兼容性说明

- `main_hint_bar` 字符串删除（底部提示条移除），操作提示内容移入 `exit_confirm_hints`；
- 源列表行、四分格槽位移除 `focusableInTouchMode`：触摸/鼠标首次点击即触发（原需两次）；D-pad 焦点语义不变；
- 通道号读既有 `SlotBinding`，无数据模型变更、无迁移。

### 验证方式（雷电模拟器，b14/b15 实测）

- 四分格主界面：底部无提示条、无黑条、铺满全屏；槽位无源名标题（仅状态点+静音图标）；
- 退出对话框：显示"遥控 + 键盘"两行操作提示；
- 源管理：通道号徽标（通道1~4）与绑定一致；**一次点击（touch/mouse 源均验）即开编辑向导且地址预填**；
- 单预览：加载期纯黑无透出（原透出四分格），起流后全屏；
- 回归：Esc 返回后各格持续播放（4s 间隔双帧对比），日志 `退出单预览，恢复四分格（slot=0 全程未触碰）`；
- 待验证（b16）：源管理首行聚焦 + 菜单键上下文菜单 + D-pad 行导航。

### 附：CI run #16/#17/#18 三连败的真实根因（2026-10-26 下午，用 token 下载 job 日志后定位）

- **现象**：三个 run 均夭折在 step 6"运行单元测试（84 项门禁）"（105s vs 成功 run 的 5:11），其中 #17 是纯文档提交（docs/QA-REPORT.md）同样失败。
- **误判过程**：因 docs-only 提交也失败 + `return@doOnLayout` 经查为合法 Kotlin，一度推断为 runner 环境/依赖下载故障（Maven Central、Google Maven 本地探测均可达；GitHub 状态页无事件）。
- **真相**（下载 run #18 job 日志 zip，step 6 尾部）：
  ```
  > Task :app:compileDebugKotlin FAILED
  e: SourceManageFragment.kt:122:37 Unresolved reference 'findViewHolderForAdapterPosition'.
  e: SourceManageFragment.kt:124:32 Unresolved reference 'itemView'.
  ```
  即**编译失败**而非测试失败：`androidx.core.view.doOnLayout` 的 lambda 参数类型是 `View` 而非 `RecyclerView`，`rv.findViewHolderForAdapterPosition(0)` / `holder.itemView` 无法解析。docs-only 提交失败是因为它**继承了 #16 的坏代码**，不能作为"环境问题"的证据。
- **修复**（commit 5ecf4dc）：先取局部强类型引用 `val rv = binding.rvSources` 再闭包捕获；`rv.doOnLayout { val holder = rv.findViewHolderForAdapterPosition(0) ... }`。
- **教训**：
  1. 本机无 Android SDK，Kotlin 编译从未本地验证过——凡涉及 API 类型推断的改动（尤其扩展函数 lambda 参数类型），CI 前应先用 kotlinc + stub 或至少人工核对接收者类型；
  2. "docs-only 提交也失败 ⇒ 环境问题"的推断只有在 docs 提交**不包含坏代码**时才成立，应先 git log 确认代码基线；
  3. step 时长异常（105s vs 5:11）本身就是信号：失败发生在编译期而非测试期，被步骤名"运行单元测试"掩盖。

## 14. 源管理首行聚焦与刷新保焦（b23 / commit 5d5b808 实测通过）

### 真实问题
- 进入源管理页时 `onViewCreated` 里执行 `binding.btnAdd.requestFocus()`，焦点停在「添加源」按钮上，`rvSources.focusedChild` 恒为 null。
- 后果：菜单键处理 `onMenuKey()` 需要 `rvSources.focusedChild`，取不到就返回 false → **上下文菜单（测试播放/编辑/删除/绑定）一直无效**；D-pad 行导航体验差；确认键编辑入口只剩鼠标点击一条路。

### 修复（两处配合，缺一不可）
1. **首行自动聚焦**：`sources` 观察者里用 `OnGlobalLayoutListener` **重试**拿首行 ViewHolder——每次全局布局后查 `findViewHolderForAdapterPosition(0)`，拿到即 `requestFocus()` 并撤监听，拿不到（子项尚未随 submitList 重布局）就等下一次全局布局。
   - 为什么不用 `doOnLayout` / `OnLayoutChangeListener`：若 `sources` 观察者在 `rv` **已布局之后**才触发，两者会「立即执行」，此时子项还没随重布局出现，holder 为 null，一次性监听随即移除 → 首行永远聚焦不上。`OnGlobalLayoutListener` 重试彻底消除该竞态。
2. **刷新保焦点**：`submitList` 改 `DiffUtil.calculateDiff` 增量更新（`VideoSource` 是 `data class`，结构性相等可正确判内容）；`setChannels` / `refreshStatus` 由 `notifyDataSetChanged` 改为 `notifyItemRangeChanged(0, size)`——**保留 ViewHolder 实例**，已聚焦行不被 detach，焦点不丢。

### 关键测试陷阱（本次踩坑教训）
- **`KeyEvent.KEYCODE_M = 41`，不是 46**。`adb shell input keyevent 46` 注入的是 **KEYCODE_R**，不会打开源管理页。
- 此前用 46 连续多轮验证「焦点仍是 slot_0」的结论**全部无效**——源管理页根本没打开，`slot_0` 只是四分格默认焦点。改正为 41 后立即复现正确行为。凡 Android keycode 测试，务必先 `logcat | grep injectKeyEvent` 核对注入的 keyCode 名称，不要凭数字记忆。

### 验证结果（b23，emulator-5556，uiautomator dump 实证）
| 项 | 结果 |
|---|---|
| 首行获焦 | ✅ 焦点为行容器 LinearLayout `[36,107][1244,191]`（此前是 slot_0 `[3,3][637,357]`）|
| M 键上下文菜单 | ✅ 弹出「测试播放 / 编辑 / 删除 / 绑定到格位」|
| D-pad DOWN | ✅ 焦点下移到第二行 `[36,191][1244,275]`|
| CENTER 确认键 | ✅ 打开「编辑视频源」向导，地址预填 `http://204.12.221.218:8181/3m1080p/cctv1.m3u8`|
| 返回回归 | ✅ Esc 返回四分格不冻结（b14 起持续验证）|
