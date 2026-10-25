package com.tvmonitor.data

import kotlinx.serialization.Serializable

/**
 * 视频源类型（PRD 四类来源）。
 */
@Serializable
enum class SourceType {
    /** ONVIF 设备（WS-Discovery 发现 / 手动 IP）。 */
    ONVIF,

    /** 推流 / 直链（rtsp://、rtsps://、http(s) ts 等 media3 可拉取的流）。 */
    STREAM,

    /** M3U8 / HLS 直播。 */
    M3U8,

    /** 本地视频文件（MediaStore / SAF / U 盘）。 */
    FILE,
}

/** ONVIF 码流种类：主码流 / 子码流。 */
@Serializable
enum class StreamKind {
    MAIN,
    SUB,
}

/**
 * 视频源实体（持久化于 `files/sources.json`）。
 *
 * 安全约定：密码不明文落盘。[passwordEnc] 为 [CredentialCrypto] 加密结果，
 * 格式 `v1:<Base64(IV)>:<Base64(密文)`；无密码时为空串。
 *
 * @property id 源唯一标识（UUID），格位绑定引用此字段；
 * @property address 播放地址：ONVIF 为码流 URI，STREAM/M3U8 为直链，FILE 为 content:// 或 file://；
 * @property preferSubStream ONVIF 添加时是否优先选子码流（主理人裁决 4）；
 * @property profileToken ONVIF 媒体配置 token，编辑重取流时使用；
 * @property createdAt / updatedAt epoch 毫秒。
 */
@Serializable
data class VideoSource(
    val id: String,
    val name: String,
    val type: SourceType,
    val address: String,
    val username: String = "",
    val passwordEnc: String = "",
    val preferSubStream: Boolean = true,
    val profileToken: String = "",
    val streamKind: StreamKind = StreamKind.SUB,
    val onvifIp: String = "",
    val onvifPort: Int = 80,
    val deviceVendor: String = "",
    val deviceModel: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 是否携带凭据（决定保存时是否需要加密、播放时是否附带认证）。 */
    val hasCredentials: Boolean
        get() = username.isNotEmpty() || passwordEnc.isNotEmpty()
}

/**
 * 单个格位的绑定关系（持久化于 `files/state.json`）。
 *
 * @property slotIndex 格位下标 0..3（2x2，从左到右、从上到下）；
 * @property sourceId 绑定的视频源 id；null 表示未配置。
 */
@Serializable
data class SlotBinding(
    val slotIndex: Int,
    val sourceId: String? = null,
)

/**
 * 四分格布局状态（`state.json` 根对象）。
 *
 * 持久化 JSON 带 schema 版本字段（§11 约定）；新增字段必须给默认值，
 * 由 [JsonSourceStore] 做前向兼容加载。
 */
@Serializable
data class QuadLayoutState(
    val schema: Int = SCHEMA_VERSION,
    val slots: List<SlotBinding> = List(SLOT_COUNT) { SlotBinding(it) },
) {
    init {
        // 防御：反序列化得到的 slots 数量不足时补齐，避免 UI 越界
        require(slots.size == SLOT_COUNT) { "slots 数量必须为 $SLOT_COUNT" }
    }

    companion object {
        const val SLOT_COUNT = 4
        const val SCHEMA_VERSION = 1
    }
}

/** 播放状态（格位角标 / 单预览状态）。 */
enum class PlaybackStatus {
    /** 空闲（未配置源）。 */
    IDLE,

    /** 起流加载中。 */
    LOADING,

    /** 播放中。 */
    ONLINE,

    /** 断线重连中（指数退避）。 */
    RECONNECTING,

    /** 连续失败后的错误态（保留手动重试入口）。 */
    ERROR,
}

/** 播放器内部状态（细粒度，供内部状态机与日志使用）。 */
enum class PlaybackState {
    IDLE,
    PREPARING,
    BUFFERING,
    PLAYING,
    PAUSED,
    RECONNECTING,
    ERROR,
    ENDED,
}

/**
 * 单路播放统计（US-10 info overlay 数据源）。
 *
 * @property videoWidth / videoHeight 视频分辨率；
 * @property frameRate 帧率（fps）；
 * @property videoBitrateBps / audioBitrateBps 视/音频码率（bps）；
 * @property bufferMs 缓冲延迟（当前缓冲时长，毫秒）；
 * @property droppedFrames 丢帧计数。
 */
data class PlaybackStats(
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 0f,
    val videoBitrateBps: Long = 0L,
    val audioBitrateBps: Long = 0L,
    val bufferMs: Long = 0L,
    val droppedFrames: Int = 0,
) {
    /** 分辨率文本，如 `1920x1080`；未知返回空串由调用方兜底。 */
    val resolutionText: String
        get() = if (videoWidth > 0 && videoHeight > 0) "${videoWidth}x$videoHeight" else ""
}

/**
 * ONVIF 发现设备（WS-Discovery ProbeMatch）。
 *
 * @property endpoint ONVIF 设备服务地址，如 `http://192.168.1.61/onvif/device_service`；
 * @property ip / port 地址与端口；
 * @property name 设备名称（Scopes 提取，缺省用 IP）。
 */
data class OnvifDevice(
    val endpoint: String,
    val ip: String,
    val port: Int,
    val name: String,
    val hardware: String = "",
    val location: String = "",
)

/** ONVIF 设备信息（GetDeviceInformation）。 */
data class OnvifDeviceInfo(
    val vendor: String,
    val model: String,
    val firmware: String,
)

/**
 * ONVIF 媒体配置（Profile）摘要。
 *
 * @property token profile token（GetStreamUri 入参）；
 * @property name 配置名；
 * @property width / height 分辨率；@property encoding 编码（H264/H265）；
 * @property frameRate 帧率；@property bitrateBps 码率。
 */
data class OnvifProfile(
    val token: String,
    val name: String,
    val width: Int = 0,
    val height: Int = 0,
    val encoding: String = "",
    val frameRate: Int = 0,
    val bitrateBps: Long = 0L,
)

/**
 * M3U 解析出的条目。
 *
 * @property title EXTINF 显示名（缺失时用文件名兜底）；
 * @property url 解析后的绝对地址；
 * @property attributes EXTINF 属性键值对（tvg-id / tvg-name / logo / group-title 等）。
 */
data class M3uEntry(
    val title: String,
    val url: String,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * M3U 导入汇总（US-05：成功/失败数量与原因）。
 *
 * @property entries 可用条目（已去重、已过滤不支持协议）；
 * @property duplicateCount 重复跳过数；
 * @property unsupportedCount rtmp/flv 等暂不支持协议数；
 * @property parseErrorCount 坏行数。
 */
data class M3uParseReport(
    val entries: List<M3uEntry> = emptyList(),
    val duplicateCount: Int = 0,
    val unsupportedCount: Int = 0,
    val parseErrorCount: Int = 0,
)

/**
 * 重连策略配置（ARCHITECTURE §6.4 / US-07：指数退避 5/10/20/40/60s 封顶，
 * 默认 10 次后保留错误态并每 60s 后台保活重试）。
 *
 * @property delaysSec 退避序列（秒）；第 n 次重连等待 delaysSec[min(n, size-1)]；
 * @property maxAttempts 单轮最大重试次数，超过后进入 ERROR 态（保留手动重试）；
 * @property keepaliveIntervalSec ERROR 态后台保活重试间隔；
 * @property firstFrameTimeoutRtspSec RTSP 首帧 watchdog 超时；
 * @property firstFrameTimeoutHlsSec HLS 首帧 watchdog 超时。
 */
data class ReconnectConfig(
    val delaysSec: List<Int> = listOf(5, 10, 20, 40, 60),
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val keepaliveIntervalSec: Int = DEFAULT_KEEPALIVE_SEC,
    val firstFrameTimeoutRtspSec: Int = DEFAULT_FIRST_FRAME_RTSP_SEC,
    val firstFrameTimeoutHlsSec: Int = DEFAULT_FIRST_FRAME_HLS_SEC,
) {
    /** 第 [attempt] 次（从 1 计）重连前的等待秒数，封顶最后一档。 */
    fun delayForAttempt(attempt: Int): Int {
        if (delaysSec.isEmpty()) return 0
        val index = (attempt - 1).coerceIn(0, delaysSec.size - 1)
        return delaysSec[index]
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10
        const val DEFAULT_KEEPALIVE_SEC = 60
        const val DEFAULT_FIRST_FRAME_RTSP_SEC = 15
        const val DEFAULT_FIRST_FRAME_HLS_SEC = 25
    }
}

/**
 * 应用设置快照（SharedPreferences 持久化，SettingsFragment 编辑）。
 *
 * @property reconnect 重连策略；
 * @property defaultMute 默认四路全部静音（US-09）；
 * @property preferSubStream ONVIF 默认子码流（主理人裁决 4）；
 * @property bufferLimitMb 单路缓冲上限 MB（§6.2：8MB）；
 * @property maxResolutionHeight 解码分辨率高度上限（1080 保护低端盒；0 不限制）；
 * @property logEnabled 日志开关（同步到 TvLog.enabled）。
 */
data class AppSettings(
    val reconnect: ReconnectConfig = ReconnectConfig(),
    val defaultMute: Boolean = true,
    val preferSubStream: Boolean = true,
    val bufferLimitMb: Int = DEFAULT_BUFFER_LIMIT_MB,
    val maxResolutionHeight: Int = DEFAULT_MAX_RESOLUTION_HEIGHT,
    val logEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_BUFFER_LIMIT_MB = 8
        const val DEFAULT_MAX_RESOLUTION_HEIGHT = 1080
    }
}
