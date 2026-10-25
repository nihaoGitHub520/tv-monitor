package com.tvmonitor.data

import com.tvmonitor.util.TvLog
import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [M3uParser] 单元测试（T2 验收 / FR-P1-03 / US-05）。
 *
 * 覆盖（ARCHITECTURE §8 全特性）：
 * 1. 编码探测：UTF-8（含 BOM 剥离）/ GBK 降级；
 * 2. EXTINF 属性：双引号 / 单引号 / 无引号；时长 -1；标题含逗号；
 * 3. 相对地址拼接（有 / 无 baseUrl）；
 * 4. 协议过滤：rtmp / http-flv → unsupported 计数；
 * 5. URL 去重；
 * 6. 坏行容错：EXTINF 无后续 URL / 悬挂 EXTINF / 空行 / 坏属性行；
 * 7. fallbackTitle 兜底名；
 * 8. parseFile 文件入口（Ok / Err 两路）。
 *
 * 说明：解析器会经 [TvLog] 输出汇总日志；测试内关闭日志开关，
 * 避免依赖 android.util.Log 的单元测试行为（日志本身另有覆盖）。
 */
class M3uParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun silenceLog() {
        TvLog.enabled = false
    }

    // ===================== 编码 =====================

    @Test
    fun `parseBytes strips UTF-8 BOM`() {
        val body = "#EXTM3U\n#EXTINF:-1,带BOM频道\nhttp://host/a.m3u8\n"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            body.toByteArray(Charsets.UTF_8)

        val report = M3uParser.parseBytes(bytes)

        assertEquals(1, report.entries.size)
        assertEquals("带BOM频道", report.entries[0].title)
        assertEquals("http://host/a.m3u8", report.entries[0].url)
        assertEquals(0, report.parseErrorCount)
    }

    @Test
    fun `parseBytes falls back to GBK for invalid UTF-8`() {
        // "你好世界" 的 GBK 字节序列不是合法 UTF-8 → 应降级 GBK 正确解码
        val gbkText = "#EXTM3U\n#EXTINF:-1,你好世界\nhttp://host/gbk.m3u8\n"
        val bytes = gbkText.toByteArray(Charset.forName("GBK"))
        // 自证：该字节序列按 UTF-8 严格解码必然失败
        assertFalse("测试数据应为非法 UTF-8", isStrictUtf8(bytes))

        val report = M3uParser.parseBytes(bytes)

        assertEquals(1, report.entries.size)
        assertEquals("你好世界", report.entries[0].title)
        assertEquals(0, report.parseErrorCount)
    }

    @Test
    fun `parseBytes handles UTF-16 BOM`() {
        val text = "#EXTM3U\n#EXTINF:-1,UTF16\nhttp://host/u16.m3u8\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)

        val report = M3uParser.parseBytes(bytes)

        assertEquals(1, report.entries.size)
        assertEquals("UTF16", report.entries[0].title)
    }

    // ===================== EXTINF 属性 =====================

    @Test
    fun `EXTINF attributes support double single and unquoted forms`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-name='CCTV-1' logo=http://logo/1.png,中央一台
            http://host/cctv1.m3u8
            #EXTINF:-1 tvg-id=cctv2 tvg-name="CCTV-2",中央二台
            http://host/cctv2.m3u8
        """.trimIndent()

        val report = M3uParser.parse(content)

        assertEquals(2, report.entries.size)
        val first = report.entries[0]
        assertEquals("中央一台", first.title)
        assertEquals("cctv1", first.attributes["tvg-id"])
        assertEquals("CCTV-1", first.attributes["tvg-name"])
        assertEquals("http://logo/1.png", first.attributes["logo"])
        val second = report.entries[1]
        assertEquals("cctv2", second.attributes["tvg-id"])
        assertEquals("CCTV-2", second.attributes["tvg-name"])
    }

    @Test
    fun `EXTINF group-title is captured as attribute`() {
        val content = "#EXTINF:-1 group-title=\"车间\",熔铸东\nhttp://host/east.m3u8\n"

        val report = M3uParser.parse(content)

        assertEquals("车间", report.entries[0].attributes["group-title"])
    }

    @Test
    fun `EXTINF title may contain commas`() {
        val content = "#EXTINF:-1,标题, 带逗号\nhttp://host/comma.m3u8\n"

        val report = M3uParser.parse(content)

        assertEquals("标题, 带逗号", report.entries[0].title)
    }

    // ===================== 相对地址 =====================

    @Test
    fun `relative url resolves against baseUrl`() {
        val content = "#EXTM3U\n#EXTINF:-1,相对\nmovie.ts\n"

        val report = M3uParser.parse(content, baseUrl = "http://cdn.example.com/list/index.m3u")

        assertEquals(1, report.entries.size)
        assertEquals("http://cdn.example.com/list/movie.ts", report.entries[0].url)
        assertEquals(0, report.parseErrorCount)
    }

    @Test
    fun `relative url without baseUrl counts as parse error`() {
        val content = "#EXTM3U\n#EXTINF:-1,相对\nmovie.ts\n"

        val report = M3uParser.parse(content, baseUrl = null)

        assertTrue(report.entries.isEmpty())
        assertEquals(1, report.parseErrorCount)
    }

    @Test
    fun `absolute url is kept as-is`() {
        val content = "#EXTINF:-1,绝对\nrtsp://192.168.1.61:554/live\n"

        val report = M3uParser.parse(content, baseUrl = "http://cdn.example.com/list/")

        assertEquals("rtsp://192.168.1.61:554/live", report.entries[0].url)
    }

    // ===================== 协议过滤 =====================

    @Test
    fun `rtmp and http-flv entries count as unsupported`() {
        val content = """
            #EXTINF:-1,rtmp流
            rtmp://live.example.com/app/stream
            #EXTINF:-1,flv流
            http://live.example.com/live.flv
            #EXTINF:-1,https-flv流
            https://live.example.com/live2.flv
            #EXTINF:-1,正常流
            http://host/ok.m3u8
        """.trimIndent()

        val report = M3uParser.parse(content)

        assertEquals(1, report.entries.size)
        assertEquals("http://host/ok.m3u8", report.entries[0].url)
        assertEquals(3, report.unsupportedCount)
    }

    // ===================== 去重 =====================

    @Test
    fun `duplicate urls keep first entry and count skips`() {
        val content = """
            #EXTINF:-1,第一次
            http://host/dup.m3u8
            #EXTINF:-1,第二次
            http://host/dup.m3u8
            #EXTINF:-1,不同
            http://host/other.m3u8
        """.trimIndent()

        val report = M3uParser.parse(content)

        assertEquals(2, report.entries.size)
        assertEquals("第一次", report.entries[0].title)
        assertEquals(1, report.duplicateCount)
    }

    // ===================== 坏行容错 =====================

    @Test
    fun `EXTINF without following URL counts as parse error`() {
        // ARCHITECTURE §8.7：EXTINF 无后续 URL 按 parseError 计数（不得静默丢弃）
        val content = """
            #EXTM3U
            #EXTINF:-1,没有地址的条目
            #EXTINF:-1,正常条目
            http://host/ok.m3u8
        """.trimIndent()

        val report = M3uParser.parse(content)

        assertEquals(1, report.entries.size)
        assertEquals(1, report.parseErrorCount)
    }

    @Test
    fun `dangling EXTINF at end of file counts as parse error`() {
        val content = "#EXTM3U\n#EXTINF:-1,悬挂条目\nhttp://host/ok.m3u8\n#EXTINF:-1,尾部悬挂\n"

        val report = M3uParser.parse(content)

        assertEquals(1, report.entries.size)
        assertEquals(1, report.parseErrorCount)
    }

    @Test
    fun `malformed EXTINF line counts as parse error`() {
        // 缺少冒号的 #EXTINF 行：无法解析属性与标题
        val content = "#EXTM3U\n#EXTINF-1,坏行\nhttp://host/ok.m3u8\n"

        val report = M3uParser.parse(content)

        assertEquals(1, report.entries.size)
        assertEquals(1, report.parseErrorCount)
    }

    @Test
    fun `blank lines and unknown directives are ignored`() {
        val content = """
            #EXTM3U

            #EXTVLCOPT:http-user-agent=TestAgent
            #KODIPROP:some=value

            #EXTINF:-1,有效条目
            http://host/ok.m3u8
        """.trimIndent()

        val report = M3uParser.parse(content)

        assertEquals(1, report.entries.size)
        assertEquals(0, report.parseErrorCount)
    }

    @Test
    fun `CRLF line endings are handled`() {
        val content = "#EXTM3U\r\n#EXTINF:-1,CRLF频道\r\nhttp://host/crlf.m3u8\r\n"

        val report = M3uParser.parse(content)

        assertEquals(1, report.entries.size)
        assertEquals("CRLF频道", report.entries[0].title)
    }

    // ===================== 兜底名 =====================

    @Test
    fun `fallbackTitle uses last url segment when EXTINF missing`() {
        val content = "#EXTM3U\nhttp://host/videos/fallback_name.mp4\n"

        val report = M3uParser.parse(content)

        assertEquals("fallback_name.mp4", report.entries[0].title)
    }

    @Test
    fun `fallbackTitle strips query and fragment`() {
        val content = "http://host/live/stream.ts?token=abc#frag\n"

        val report = M3uParser.parse(content)

        assertEquals("stream.ts", report.entries[0].title)
    }

    // ===================== 文件入口 =====================

    @Test
    fun `parseFile returns Ok for readable file`() {
        val file = tempFolder.newFile("list.m3u")
        file.writeText("#EXTM3U\n#EXTINF:-1,文件频道\nhttp://host/file.m3u8\n", Charsets.UTF_8)

        val result = M3uParser.parseFile(file)

        assertTrue(result is AppResultWrap.Ok)
        assertEquals(1, (result as AppResultWrap.Ok).report.entries.size)
    }

    @Test
    fun `parseFile returns Err for missing file`() {
        val missing = File(tempFolder.root, "not_exist.m3u")

        val result = M3uParser.parseFile(missing)

        assertTrue(result is AppResultWrap.Err)
        assertNotNull((result as AppResultWrap.Err).message)
        assertFalse(result.message.isBlank())
    }

    @Test
    fun `empty content yields empty report`() {
        val report = M3uParser.parse("")
        assertTrue(report.entries.isEmpty())
        assertEquals(0, report.parseErrorCount)
    }

    // ===================== 辅助 =====================

    private fun isStrictUtf8(bytes: ByteArray): Boolean =
        try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (e: java.nio.charset.CharacterCodingException) {
            false
        }
}
