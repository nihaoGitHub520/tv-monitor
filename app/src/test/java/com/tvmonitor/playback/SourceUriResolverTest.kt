package com.tvmonitor.playback

import com.tvmonitor.data.SourceType
import com.tvmonitor.data.VideoSource
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.errorOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SourceUriResolver] 协议扩展点单元测试（T3 验收 / ARCHITECTURE §11.9）。
 *
 * 覆盖可隔离的纯逻辑部分：
 * 1. scheme → 解析器映射（supports 矩阵）；
 * 2. [ResolverRegistry] 注册顺序与 resolverFor 查找；
 * 3. 无解析器支持 → [ErrorCode.PROTOCOL_UNSUPPORTED] 明确失败（rtmp / http-flv）。
 *
 * 边界说明：各解析器 `resolve()` 的成功路径依赖 `android.net.Uri`（AGP 单测 mockable jar
 * 中方法体被替换）与 Keystore 解密，无法在纯 JVM 单测隔离执行，改由手动测试矩阵覆盖
 * （见 docs/QA-REPORT.md「已知限制」节）；不支持的协议路径（无解析器命中）不触达
 * Uri/Keystore，已在本类覆盖。
 */
class SourceUriResolverTest {

    private lateinit var registry: ResolverRegistry

    @Before
    fun setUp() {
        registry = ResolverRegistry.createDefault()
    }

    private fun source(
        type: SourceType,
        address: String,
    ) = VideoSource(id = "s1", name = "测试源", type = type, address = address)

    // ===================== RtspStreamResolver =====================

    @Test
    fun `rtsp resolver supports rtsp and rtsps schemes case-insensitively`() {
        val resolver = RtspStreamResolver()

        assertTrue(resolver.supports(source(SourceType.STREAM, "rtsp://192.168.1.61:554/live")))
        assertTrue(resolver.supports(source(SourceType.STREAM, "RTSP://192.168.1.61:554/live")))
        assertTrue(resolver.supports(source(SourceType.ONVIF, "rtsps://cam.local/live")))
        assertFalse(resolver.supports(source(SourceType.STREAM, "http://host/live.ts")))
        assertFalse(resolver.supports(source(SourceType.STREAM, "rtmp://host/live")))
        assertFalse(resolver.supports(source(SourceType.STREAM, "")))
        assertFalse(resolver.supports(source(SourceType.STREAM, "not-a-url")))
    }

    // ===================== HlsResolver =====================

    @Test
    fun `hls resolver supports m3u8 urls with query and type override`() {
        val resolver = HlsResolver()

        assertTrue(resolver.supports(source(SourceType.M3U8, "http://host/live/index.m3u8")))
        assertTrue(resolver.supports(source(SourceType.M3U8, "https://host/live/index.M3U8?token=abc")))
        assertTrue(resolver.supports(source(SourceType.M3U8, "http://host/live/noext"))) // 类型显式为 M3U8
        assertFalse(resolver.supports(source(SourceType.STREAM, "http://host/live/index.ts")))
        assertFalse(resolver.supports(source(SourceType.ONVIF, "http://host/x.m3u8")))
        assertFalse(resolver.supports(source(SourceType.M3U8, "file:///usb/a.m3u8")))
    }

    // ===================== FileResolver =====================

    @Test
    fun `file resolver supports file content schemes and FILE type`() {
        val resolver = FileResolver()

        assertTrue(resolver.supports(source(SourceType.FILE, "file:///usb/media/video.mp4")))
        assertTrue(resolver.supports(source(SourceType.FILE, "content://media/external/video/media/42")))
        assertTrue(resolver.supports(source(SourceType.FILE, "/usb/media/video.mp4"))) // 裸路径 + FILE 类型
        // scheme 优先：file:// 地址即使类型标为 STREAM 也按本地文件解析（地址形态是权威依据）
        assertTrue(resolver.supports(source(SourceType.STREAM, "file:///usb/video.mp4")))
        assertFalse(resolver.supports(source(SourceType.M3U8, "http://host/a.m3u8")))
    }

    // ===================== DirectResolver =====================

    @Test
    fun `direct resolver covers remaining http https links but not rtmp or onvif`() {
        val resolver = DirectResolver()

        assertTrue(resolver.supports(source(SourceType.STREAM, "http://host/live.ts")))
        assertTrue(resolver.supports(source(SourceType.M3U8, "https://host/live/index.mp4")))
        assertFalse(resolver.supports(source(SourceType.STREAM, "rtmp://host/live")))
        assertFalse(resolver.supports(source(SourceType.STREAM, "http-flv://host/live")))
        assertFalse(resolver.supports(source(SourceType.ONVIF, "http://host/onvif/device_service")))
    }

    // ===================== ResolverRegistry =====================

    @Test
    fun `registry maps each source kind to first matching resolver`() {
        assertTrue(registry.resolverFor(source(SourceType.ONVIF, "rtsp://cam/live")) is RtspStreamResolver)
        assertTrue(registry.resolverFor(source(SourceType.M3U8, "http://host/a.m3u8")) is HlsResolver)
        assertTrue(registry.resolverFor(source(SourceType.FILE, "file:///usb/v.mp4")) is FileResolver)
        assertTrue(registry.resolverFor(source(SourceType.STREAM, "http://host/a.ts")) is DirectResolver)
        // rtmp / http-flv 无解析器（P0 协议边界）
        assertNull(registry.resolverFor(source(SourceType.STREAM, "rtmp://host/live")))
        assertNull(registry.resolverFor(source(SourceType.STREAM, "http://host/live.flv")))
    }

    @Test
    fun `registry default registers four builtin resolvers`() {
        assertEquals(4, registry.allResolvers.size)
        assertTrue(registry.allResolvers[0] is RtspStreamResolver)
        assertTrue(registry.allResolvers[1] is HlsResolver)
        assertTrue(registry.allResolvers[2] is FileResolver)
        assertTrue(registry.allResolvers[3] is DirectResolver)
    }

    @Test
    fun `register prepends custom resolver for extension`() {
        val custom = object : SourceUriResolver {
            override fun supports(source: VideoSource) = source.address.startsWith("webrtc://")
            override suspend fun resolve(source: VideoSource) =
                throw UnsupportedOperationException("测试替身不执行 resolve")
        }
        registry.register(custom)

        assertEquals(custom, registry.resolverFor(source(SourceType.STREAM, "webrtc://host/live")))
        // 内置解析器行为不受影响
        assertTrue(registry.resolverFor(source(SourceType.STREAM, "rtsp://cam/live")) is RtspStreamResolver)
    }

    // ===================== PROTOCOL_UNSUPPORTED =====================

    @Test
    fun `resolve without matching resolver returns PROTOCOL_UNSUPPORTED`() = runBlocking {
        val rtmpSource = source(SourceType.STREAM, "rtmp://live.example.com/app/stream")

        val result = registry.resolve(rtmpSource)

        assertTrue(result.isFailure)
        val error = result.errorOrNull()
        assertNotNull(error)
        assertEquals(ErrorCode.PROTOCOL_UNSUPPORTED, error!!.code)
        // 错误信息需带上协议名，便于 UI 映射“暂不支持该协议”文案
        assertTrue(error.message.contains("rtmp"))
    }

    @Test
    fun `resolve without matching resolver handles http-flv scheme`() = runBlocking {
        val result = registry.resolve(source(SourceType.STREAM, "http://host/live.flv"))

        assertTrue(result.isFailure)
        assertEquals(ErrorCode.PROTOCOL_UNSUPPORTED, result.errorOrNull()!!.code)
    }

    @Test
    fun `empty registry reports unsupported for any source`() = runBlocking {
        val emptyRegistry = ResolverRegistry(emptyList())

        val result = emptyRegistry.resolve(source(SourceType.STREAM, "rtsp://cam/live"))

        assertTrue(result.isFailure)
        assertEquals(ErrorCode.PROTOCOL_UNSUPPORTED, result.errorOrNull()!!.code)
    }
}
