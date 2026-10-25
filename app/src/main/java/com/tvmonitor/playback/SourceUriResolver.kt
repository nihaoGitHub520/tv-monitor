package com.tvmonitor.playback

import android.net.Uri
import com.tvmonitor.data.CredentialCrypto
import com.tvmonitor.data.SourceType
import com.tvmonitor.data.VideoSource
import com.tvmonitor.util.AppError
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 媒体种类（决定 LoadControl / 首帧 watchdog / 重复模式等播放策略）。
 */
enum class MediaKind {
    /** RTSP / RTSPS（低延迟优先）。 */
    RTSP,

    /** HLS / m3u8（大缓冲优先）。 */
    HLS,

    /** DASH。 */
    DASH,

    /** 渐进式（本地文件 / http mp4-ts）。 */
    PROGRESSIVE,

    /** 其他（交还 media3 自行推断）。 */
    OTHER,
}

/**
 * 解析后的可播放媒体。
 *
 * @property uri 交给 media3 的媒体地址（ONVIF 场景已注入凭据）；
 * @property kind 媒体种类；
 * @property repeatOne 是否单路循环（本地文件填充画面场景，US-06）。
 */
data class ResolvedMedia(
    val uri: Uri,
    val kind: MediaKind,
    val repeatOne: Boolean = false,
)

/**
 * 协议扩展点（ARCHITECTURE §11：新增协议只扩展此接口并注册，不得侵入 UI/管理层）。
 *
 * 实现方约定：
 * - [supports] 必须幂等且无副作用（主线程可调用）；
 * - [resolve] 可能涉及网络（如 ONVIF GetStreamUri），内部切 IO；
 * - 不支持的协议必须返回 [ErrorCode.PROTOCOL_UNSUPPORTED] 的明确失败，
 *   由 UI 层映射 strings.xml 文案，不得静默失败。
 */
interface SourceUriResolver {

    /** 是否支持解析该源。 */
    fun supports(source: VideoSource): Boolean

    /** 解析为可播放媒体。 */
    suspend fun resolve(source: VideoSource): AppResult<ResolvedMedia>
}

/**
 * 解析注册表：按注册顺序取首个 [supports] 命中的实现。
 *
 * 默认注册：Rtsp / Hls / File / Direct 四个实现（T3 范围）。
 * 新协议（如 P2 的 WebRTC）新增一个 [SourceUriResolver] 实现并 [register] 即可。
 *
 * 自身也实现 [SourceUriResolver]：作为门面可直接注入播放槽位 / ViewModel，
 * [supports] 语义为“存在任一子解析器支持”。
 */
class ResolverRegistry(resolvers: List<SourceUriResolver>) : SourceUriResolver {

    private val resolversMutable = resolvers.toMutableList()

    /** 全部已注册解析器（只读视图）。 */
    val allResolvers: List<SourceUriResolver> get() = resolversMutable.toList()

    /** 注册新解析器（插到队首，优先于内置实现）。 */
    fun register(resolver: SourceUriResolver) {
        resolversMutable.add(0, resolver)
    }

    /** 是否存在支持的解析器（[SourceUriResolver.supports] 门面语义）。 */
    override fun supports(source: VideoSource): Boolean =
        resolversMutable.any { it.supports(source) }

    /** 找到首个支持的解析器；无则 null。 */
    fun resolverFor(source: VideoSource): SourceUriResolver? =
        resolversMutable.firstOrNull { it.supports(source) }

    /**
     * 解析源；无解析器支持时返回 [ErrorCode.PROTOCOL_UNSUPPORTED]
     * （message 面向日志；UI 侧按 code 映射“暂不支持该协议”文案）。
     */
    override suspend fun resolve(source: VideoSource): AppResult<ResolvedMedia> {
        val resolver = resolverFor(source)
        if (resolver == null) {
            val scheme = source.address.substringBefore("://", "").ifBlank { "未知" }
            TvLog.w(TAG, "无解析器支持：type=${source.type} scheme=$scheme")
            return AppResult.Failure(
                AppError(
                    ErrorCode.PROTOCOL_UNSUPPORTED,
                    "暂不支持该协议（$scheme）",
                ),
            )
        }
        return resolver.resolve(source)
    }

    companion object {
        private const val TAG = "TvMonitor:ResolverRegistry"

        /** 默认注册表：Rtsp / Hls / File / Direct。 */
        fun createDefault(
            credentialCrypto: CredentialCrypto = CredentialCrypto(),
        ): ResolverRegistry = ResolverRegistry(
            listOf(
                RtspStreamResolver(credentialCrypto),
                HlsResolver(),
                FileResolver(),
                DirectResolver(),
            ),
        )
    }
}

// ===================== 内置实现 =====================

/**
 * RTSP / RTSPS 直链与 ONVIF 码流解析。
 *
 * ONVIF 源在添加时已由向导拿到 rtsp 地址；播放时若携带凭据且地址未内含
 * 用户信息，则解密后注入 `user:pass@`（RTSP 无独立头通道，只能走 URL 凭据）。
 */
class RtspStreamResolver(
    private val credentialCrypto: CredentialCrypto = CredentialCrypto(),
) : SourceUriResolver {

    override fun supports(source: VideoSource): Boolean {
        val scheme = schemeOf(source.address) ?: return false
        return scheme == "rtsp" || scheme == "rtsps"
    }

    override suspend fun resolve(source: VideoSource): AppResult<ResolvedMedia> =
        withContext(Dispatchers.IO) {
            try {
                val address = source.address.trim()
                if (address.isEmpty()) {
                    return@withContext AppResult.Failure(
                        AppError(ErrorCode.INVALID_ARGUMENT, "流地址为空"),
                    )
                }
                val finalUrl = if (source.type == SourceType.ONVIF &&
                    source.passwordEnc.isNotEmpty() &&
                    !address.contains("@")
                ) {
                    val password = credentialCrypto.decryptOrNull(source.passwordEnc)
                    if (password.isNullOrEmpty()) {
                        TvLog.w(TAG, "ONVIF 凭据解密失败，按无认证播放：${source.name}")
                        address
                    } else {
                        injectCredentials(address, source.username, password)
                    }
                } else {
                    address
                }
                AppResult.Success(
                    ResolvedMedia(uri = Uri.parse(finalUrl), kind = MediaKind.RTSP),
                )
            } catch (e: Exception) {
                AppResult.Failure(
                    AppError(ErrorCode.PARSE_FAILED, "RTSP 地址解析失败：${e.message}", e),
                )
            }
        }

    /** 将 user:pass 注入 rtsp URL 的 authority（分别 URL 编码防分隔符冲突）。 */
    private fun injectCredentials(url: String, user: String, password: String): String {
        val marker = "://"
        val schemeEnd = url.indexOf(marker)
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd)
        var rest = url.substring(schemeEnd + marker.length)
        // 去除已存在的用户信息（容错重复注入）
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        val userInfo = "${Uri.encode(user)}:${Uri.encode(password)}@"
        return "$scheme$marker$userInfo$rest"
    }

    private fun schemeOf(url: String): String? {
        val index = url.indexOf("://")
        if (index <= 0) return null
        return url.substring(0, index).lowercase()
    }

    private companion object {
        const val TAG = "TvMonitor:RtspResolver"
    }
}

/** HLS（m3u8）解析：http/https 且指向 m3u8（兼容查询参数）。 */
class HlsResolver : SourceUriResolver {

    override fun supports(source: VideoSource): Boolean {
        if (source.type == SourceType.ONVIF) return false
        val scheme = schemeOf(source.address) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val path = source.address.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8", ignoreCase = true) ||
            source.type == SourceType.M3U8
    }

    override suspend fun resolve(source: VideoSource): AppResult<ResolvedMedia> =
        withContext(Dispatchers.IO) {
            val address = source.address.trim()
            if (address.isEmpty()) {
                AppResult.Failure(AppError(ErrorCode.INVALID_ARGUMENT, "m3u8 链接为空"))
            } else {
                AppResult.Success(ResolvedMedia(uri = Uri.parse(address), kind = MediaKind.HLS))
            }
        }

    private fun schemeOf(url: String): String? {
        val index = url.indexOf("://")
        if (index <= 0) return null
        return url.substring(0, index).lowercase()
    }

    private companion object {
        const val TAG = "TvMonitor:HlsResolver"
    }
}

/** 本地视频文件解析（content:// / file:// / 裸路径），单路循环播放。 */
class FileResolver : SourceUriResolver {

    override fun supports(source: VideoSource): Boolean {
        if (source.type == SourceType.FILE) return true
        val scheme = schemeOf(source.address) ?: return false
        return scheme == "file" || scheme == "content"
    }

    override suspend fun resolve(source: VideoSource): AppResult<ResolvedMedia> =
        withContext(Dispatchers.IO) {
            val address = source.address.trim()
            if (address.isEmpty()) {
                AppResult.Failure(AppError(ErrorCode.INVALID_ARGUMENT, "文件地址为空"))
            } else {
                val uri = if (address.contains("://")) {
                    Uri.parse(address)
                } else {
                    Uri.fromFile(java.io.File(address))
                }
                AppResult.Success(
                    ResolvedMedia(uri = uri, kind = MediaKind.PROGRESSIVE, repeatOne = true),
                )
            }
        }

    private fun schemeOf(url: String): String? {
        val index = url.indexOf("://")
        if (index <= 0) return null
        return url.substring(0, index).lowercase()
    }

    private companion object {
        const val TAG = "TvMonitor:FileResolver"
    }
}

/**
 * 兜底解析器：其余 http/https 直链（progressive / ts 等）。
 *
 * 注意：rtmp / rtmp(s) / http(s)-flv 均不被任何解析器 [supports] 命中，
 * 由 [ResolverRegistry.resolve] 统一返回 PROTOCOL_UNSUPPORTED，
 * UI 提示"暂不支持该协议"（media3 原生不支持 http-flv 容器）。
 */
class DirectResolver : SourceUriResolver {

    override fun supports(source: VideoSource): Boolean {
        if (source.type == SourceType.ONVIF) return false
        val scheme = schemeOf(source.address) ?: return false
        if (scheme != "http" && scheme != "https") return false
        // http(s)-flv 直链：media3 不支持，不予支持（与 M3uParser 导入侧过滤对齐）
        val path = source.address.substringBefore('?').substringBefore('#').lowercase()
        return !path.endsWith(".flv")
    }

    override suspend fun resolve(source: VideoSource): AppResult<ResolvedMedia> =
        withContext(Dispatchers.IO) {
            val address = source.address.trim()
            if (address.isEmpty()) {
                AppResult.Failure(AppError(ErrorCode.INVALID_ARGUMENT, "流地址为空"))
            } else {
                AppResult.Success(
                    ResolvedMedia(uri = Uri.parse(address), kind = MediaKind.OTHER),
                )
            }
        }

    private fun schemeOf(url: String): String? {
        val index = url.indexOf("://")
        if (index <= 0) return null
        return url.substring(0, index).lowercase()
    }

    private companion object {
        const val TAG = "TvMonitor:DirectResolver"
    }
}
