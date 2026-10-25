# tv-monitor — TV 监控四分格播放器

Android TV 原生应用（Kotlin + media3 ExoPlayer）：2x2 四分格同屏播放 4 路视频，
支持 ONVIF 摄像头（WS-Discovery 自动发现）、RTSP/RTSPS 推流直链、M3U8/HLS 直播、
本地视频文件四类来源；提供单预览（仿监控录像机交互）、断线指数退避重连、
WakeLock 防休眠、凭据 Keystore 加密等能力。

- 包名：`com.tvmonitor`
- 最低系统：Android 7.0（API 24），目标 TV/盒子
- 播放内核：media3 ExoPlayer（BOM 1.5.x）

---

## 1. 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17（AGP 8.7 要求） |
| Android Studio | Ladybug (2024.2) 或更新 |
| Android SDK | compileSdk 35（Android 15） |
| Gradle | 8.10.2（由 wrapper 管理） |
| Kotlin | 2.0.21 |

> 本仓库已包含 Gradle Wrapper 配置（`gradle/wrapper/gradle-wrapper.properties`）。
> **注意**：`gradle/wrapper/gradle-wrapper.jar` 为二进制文件，未随源码入库。
> 首次使用任选其一：
> 1. Android Studio 打开工程后 **Sync Project with Gradle Files**，IDE 会自动补全 wrapper；
> 2. 或本机已有 Gradle 8.10.2 时，在工程根目录执行 `gradle wrapper --gradle-version 8.10.2` 生成。

## 2. 用 Android Studio 打开并构建

1. `File → Open`，选择本仓库根目录（含 `settings.gradle.kts` 的目录）；
2. 等待 Gradle Sync 完成（首次会下载依赖，需联网）；
3. 如提示 SDK 缺失：`Tools → SDK Manager` 安装 Android SDK 35 与 Build-Tools；
4. 菜单 `Build → Make Project`（或 `Ctrl+F9`）编译；
5. 构建 Debug APK：`Build → Build Bundle(s)/APK(s) → Build APK(s)`，
   产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

命令行方式（工程根目录）：

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

## 3. 安装到 TV / 盒子

### 3.1 通过 adb（有线/无线）

电视/盒子需开启「开发者选项 → USB 调试」（部分 TV 需在「关于」中连续点击版本号开启开发者选项）。

```bash
# 1) 确认设备已连接
adb devices

# 2) 安装 Debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3) 启动（TV 启动器入口已注册 LEANBACK_LAUNCHER）
adb shell monkey -p com.tvmonitor.debug -c android.intent.category.LEANBACK_LAUNCHER 1
```

> Debug 包名带 `.debug` 后缀（`com.tvmonitor.debug`），与 Release 可共存；
> Release 包名为 `com.tvmonitor`。

### 3.2 直接安装 APK 文件

将 `app-debug.apk` 拷贝到 U 盘，插入电视/盒子，用文件管理器打开安装
（需在系统设置中允许「未知来源」安装）。

### 3.3 常用调试命令

```bash
# 查看日志（TAG 规范：TvMonitor:<模块>，密码已脱敏）
adb logcat -s TvMonitor:PlaybackSlot TvMonitor:QuadManager TvMonitor:MainActivity

# 卸载
adb uninstall com.tvmonitor.debug
```

## 4. 首次使用指引

1. 应用启动后进入四分格，未配置的格显示「未配置」占位；
2. 按遥控器 **菜单键** 进入「源管理」→「添加源」；
3. 选择源类型：
   - **ONVIF 设备**：`自动发现` 扫描局域网设备，或手动填 IP/端口/账号密码 →
     `连接设备` 拉取主/子码流（默认推荐子码流）；
   - **推流/直链**：粘贴 `rtsp://` / `rtsps://` / `http(s)://` 地址，可先 `测试连接`；
   - **M3U8/HLS**：填写链接，或 `导入 m3u/m3u8 文件` 批量添加；
   - **本地文件**：SAF 选择器浏览 U 盘/本地视频；
4. 命名 → 选择格位 → 保存，返回主界面该格自动起播；
5. 按键说明：
   - 四分格：方向键移动焦点格，**确认键**进入单预览，**菜单键**源管理，
     **设置键**系统设置，**返回键**退出确认；
   - 单预览：**左/右键**切换上一路/下一路，**Info 键**播放信息（3s 自动隐藏），
     **静音键**仅取消当前路静音，**确认键**播放/暂停，**返回键**回四分格。

## 5. 工程结构

```
tv-monitor/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml          # 依赖版本目录（唯一依赖来源）
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── layout/                # activity_main / view_slot / view_quad_grid
        │   │                           # fragment_source_manage / fragment_add_source / fragment_settings
        │   │                           # item_source / item_onvif_device / item_onvif_profile
        │   ├── values/                # strings(全量中文) / colors / themes
        │   ├── drawable/              # 焦点描边、状态点、图标族
        │   └── mipmap-anydpi*/        # 启动图标
        └── java/com/tvmonitor/
            ├── TvMonitorApp.kt        # Application 入口
            ├── util/                  # TvLog(脱敏) / AppResult(统一错误)
            ├── data/                  # Models / SourceStore / JsonSourceStore(原子写)
            │                           # CredentialCrypto(Keystore) / M3uParser / SettingsStore
            ├── onvif/                 # OnvifSoap / OnvifDiscovery / OnvifClient / OnvifPtzClient(P1)
            ├── playback/              # SourceUriResolver(协议扩展点) / PlaybackSlot / QuadPlaybackManager
            └── ui/                    # MainActivity(+SinglePreviewOverlay) / QuadGridView / SlotView
                                    # SourceManageFragment / AddSourceWizardFragment / SettingsFragment
```

## 6. 已知限制

1. **RTMP / HTTP-FLV 拉流暂不支持**（P0 边界，架构裁决）：输入时给出明确「暂不支持该协议」提示；
   后续可在 `playback/SourceUriResolver.kt` 新增实现并注册到 `ResolverRegistry`，不侵入 UI；
2. **PTZ 云台为 P1 预留**：`onvif/OnvifPtzClient.kt` 仅提供接口与空实现，编译不受影响；
3. **gradle-wrapper.jar 未入库**（二进制）：首次 Sync 或 `gradle wrapper` 生成，见第 1 节；
4. **开机自启、轮巡、分组、布局保存、截图录像** 为 P1/P2，不在本版本范围；
5. 本地视频格式（ts/mkv/H.265）以目标机型 media3 实测能力为准，失败有明确提示；
6. 本机无 JDK/SDK 环境时未执行编译验证，首次构建请以 Android Studio Sync 结果为准。
