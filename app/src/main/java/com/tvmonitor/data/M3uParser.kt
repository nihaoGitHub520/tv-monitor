package com.tvmonitor.data

import com.tvmonitor.util.TvLog
import java.io.File
import java.net.URI

/**
 * M3U / M3U8 播放列表解析器（FR-P1-03 / US-05）。
 *
 * 能力矩阵（T2 验收标准）：
 * 1. **编码降级**：UTF-8（含 BOM）优先，非法 UTF-8 序列降级 GBK（国内平台常见）；
 * 2. **EXTINF 属性**：支持单引号 / 双引号 / 无引号三种属性写法；
 * 3. **相对地址拼接**：相对 URL 基于 [baseUrl] 拼接为绝对地址；
 * 4. **协议过滤**：`rtmp://`、`http(s)-flv` 标记为 UNSUPPORTED（media3 不支持，P0 边界）；
 * 5. **去重**：同一 URL 只保留首条，重复计数；
 * 6. **坏行容错**：EXTINF 无后续 URL、属性缺失等按 parseError 计数，不抛异常；
 * 7. **导入汇总**：[M3uParseReport] 给出成功 / 跳过 / 失败数量。
 *
 * 纯函数式、无 IO 副作用，可在 JVM 单测直接覆盖。
 */
object M3uParser {

    private const val TAG = "TvMonitor:M3uParser"

    /** EXTINF 行：`#EXTINF:<时长> <属性...>,<标题>`；时长与标题均可缺省。 */
    private val EXTINF_ATTR = Regex("([\\w-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s,]+))")
    private val EXTINF_LINE = Regex("^#EXTINF\\s*:\\s*(-?[\\d.]+)?\\s*(.*)$")

    /** 暂不支持协议（rtmp 拉流 / http-flv 超出 media3 原生能力）。 */
    private val UNSUPPORTED_SCHEME = listOf("rtmp", "rtmps", "http-flv", "https-flv")

    /**
     * 解析文本（调用方需保证已做编码探测，或直接用 [parseBytes]）。
     *
     * @param text 播放列表文本；
     * @param baseUrl 相对地址拼接基准（文件路径场景传 null 时相对项按坏行计数）。
     */
    fun parse(text: String, baseUrl: String? = null): M3uParseReport {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return parseLines(lines, baseUrl)
    }

    /**
     * 解析字节流：自动 UTF-8/GBK 降级与 BOM 剥离。
     *
     * @param baseUrl 拼接基准；从本地文件导入时可传文件父目录，从网络导入传文档 URL。
     */
    fun parseBytes(bytes: ByteArray, baseUrl: String? = null): M3uParseReport {
        val text = decodeBytes(bytes)
        return parse(text, baseUrl)
    }

    /** 从文件读取并解析（导入 U 盘 m3u 的入口）。 */
    fun parseFile(file: File, baseUrl: String? = null): AppResultWrap {
        return try {
            val report = parseBytes(file.readBytes(), baseUrl)
            AppResultWrap.Ok(report)
        } catch (e: Exception) {
            TvLog.e(TAG, "读取 m3u 文件失败：${file.name}", e)
            AppResultWrap.Err("读取文件失败：${e.message ?: "未知错误"}")
        }
    }

    // ===================== 内部实现 =====================

    private fun parseLines(lines: List<String>, baseUrl: String?): M3uParseReport {
        val entries = mutableListOf<M3uEntry>()
        val seen = mutableSetOf<String>()
        var duplicate = 0
        var unsupported = 0
        var parseError = 0

        var pendingExtinf: PendingExtInf? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    // 上一条 EXTINF 尚未配对 URL 即被本条覆盖：按坏行计数（中段悬挂）
                    if (pendingExtinf != null) parseError++
                    pendingExtinf = parseExtInf(line)
                    if (pendingExtinf == null) parseError++
                }
                // 其他指令（#EXTM3U / #EXTGRP / #EXTVLCOPT...）忽略
                continue
            }

            // 非注释行 = 媒体地址
            val absolute = resolveUrl(line, baseUrl)
            if (absolute == null) {
                parseError++
                pendingExtinf = null
                continue
            }
            val scheme = schemeOf(absolute)
            if (scheme != null && UNSUPPORTED_SCHEME.any { it.equals(scheme, ignoreCase = true) }) {
                unsupported++
                pendingExtinf = null
                continue
            }
            // http(s) 直链 FLV：scheme 合法但 media3 不支持 http-flv 容器，
            // 同样标记 UNSUPPORTED（导入即用户可见“暂不支持”，避免进错误态）
            if ((scheme == "http" || scheme == "https") && isFlvUrl(absolute)) {
                unsupported++
                pendingExtinf = null
                continue
            }
            if (!seen.add(absolute)) {
                duplicate++
                pendingExtinf = null
                continue
            }
            val info = pendingExtinf
            val title = info?.title?.takeIf { it.isNotBlank() }
                ?: fallbackTitle(absolute)
            entries += M3uEntry(
                title = title,
                url = absolute,
                attributes = info?.attributes ?: emptyMap(),
            )
            pendingExtinf = null
        }

        // 尾部悬挂 EXTINF（无后续 URL）
        if (pendingExtinf != null) parseError++

        TvLog.i(
            TAG,
            "m3u 解析完成：有效=${entries.size} 重复=$duplicate 不支持=$unsupported 坏行=$parseError",
        )
        return M3uParseReport(
            entries = entries,
            duplicateCount = duplicate,
            unsupportedCount = unsupported,
            parseErrorCount = parseError,
        )
    }

    /** EXTINF 行解析：属性三种引号写法 + 逗号后标题。 */
    private fun parseExtInf(line: String): PendingExtInf? {
        val match = EXTINF_LINE.matchEntire(line) ?: return null
        val rest = match.groupValues[2]
        // 逗号分隔属性与标题（标题可能含逗号，只按第一个逗号切）
        val commaIndex = rest.indexOf(',')
        val attrPart = if (commaIndex >= 0) rest.substring(0, commaIndex) else rest
        val title = if (commaIndex >= 0) rest.substring(commaIndex + 1).trim() else ""
        val attributes = mutableMapOf<String, String>()
        for (m in EXTINF_ATTR.findAll(attrPart)) {
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
            attributes[m.groupValues[1].lowercase()] = value
        }
        return PendingExtInf(title = title, attributes = attributes)
    }

    /** 相对地址拼接；非法地址返回 null（按坏行计数）。 */
    private fun resolveUrl(url: String, baseUrl: String?): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val scheme = schemeOf(trimmed)
        if (scheme != null) {
            // 绝对地址：仅做最小合法性校验
            return trimmed
        }
        if (baseUrl.isNullOrBlank()) return null
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (e: Exception) {
            TvLog.w(TAG, "相对地址拼接失败：$trimmed")
            null
        }
    }

    /** 提取 scheme（小写）；非法返回 null。 */
    private fun schemeOf(url: String): String? {
        val index = url.indexOf("://")
        if (index <= 0) return null
        val scheme = url.substring(0, index)
        if (!scheme.first().isLetter()) return null
        if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) return null
        return scheme.lowercase()
    }

    /** 无标题时用 URL 末段做兜底名。 */
    private fun fallbackTitle(url: String): String {
        val last = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return last.ifBlank { url }
    }

    /** http(s) 直链 FLV 判定（忽略查询参数/片段；media3 不支持，P0 边界）。 */
    private fun isFlvUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.endsWith(".flv", ignoreCase = true)
    }

    /**
     * 编码探测：UTF-8 严格解码，失败降级 GBK。
     * 剥离 UTF-8/UTF-16 BOM。
     */
    private fun decodeBytes(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16 BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 严格 UTF-8 校验：用 CharsetDecoder 检测非法序列
        if (isStrictUtf8(bytes)) return String(bytes, Charsets.UTF_8)
        return String(bytes, charset("GBK"))
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (e: java.nio.charset.CharacterCodingException) {
            false
        }
    }

    private data class PendingExtInf(
        val title: String,
        val attributes: Map<String, String>,
    )
}

/**
 * 文件解析结果轻量封装（避免本层反向依赖 AppResult，保持解析器纯函数性）。
 */
sealed interface AppResultWrap {
    data class Ok(val report: M3uParseReport) : AppResultWrap
    data class Err(val message: String) : AppResultWrap
}
