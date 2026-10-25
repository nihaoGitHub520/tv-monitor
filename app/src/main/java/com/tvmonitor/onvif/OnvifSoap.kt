package com.tvmonitor.onvif

import android.util.Base64
import com.tvmonitor.data.OnvifDeviceInfo
import com.tvmonitor.data.OnvifProfile
import com.tvmonitor.util.AppError
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * ONVIF SOAP 1.2 协议工具（ONVIF Profile S 设备服务 / 媒体服务）。
 *
 * 覆盖能力（T2 验收）：
 * 1. **SOAP 1.2 信封**：`application/soap+xml`，`env:` 命名空间；
 * 2. **WS-Security 摘要认证**：`PasswordDigest = Base64(SHA1(Nonce + Created + Password))`；
 * 3. **Fault 识别**：解析 `env:Fault` 的 Code/Reason/Detail，映射 [ErrorCode.SOAP_FAULT]
 *    或 [ErrorCode.AUTH_FAILED]（NotAuthorized）；
 * 4. **XmlPullParser 流式解析**：无 DOM 全树驻留，适配大 Profile 响应；
 * 5. **响应解析**：GetDeviceInformation / GetProfiles / GetStreamUri / GetServices。
 *
 * 命名空间处理：统一关闭 namespace processing，按 local name 匹配（兼容各厂商前缀差异）。
 */
object OnvifSoap {

    private const val TAG = "TvMonitor:OnvifSoap"

    /** SOAP 1.2 媒体类型（OkHttp Content-Type）。 */
    const val CONTENT_TYPE = "application/soap+xml; charset=utf-8"

    // ONVIF 命名空间（前缀仅作文档可读性，匹配一律按 local name）
    private const val NS_ENV = "http://www.w3.org/2003/05/soap-envelope"
    private const val NS_ENV_11 = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val NS_WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    private const val NS_WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    private const val NS_TD = "http://www.onvif.org/ver10/device/wsdl"
    private const val NS_TRT = "http://www.onvif.org/ver10/media/wsdl"

    private val random = SecureRandom()

    // ===================== 信封构建 =====================

    /**
     * 构建 SOAP 1.2 请求信封。
     *
     * @param username 账号；为空时省略 WS-Security 头（匿名访问设备）；
     * @param password 明文密码，仅用于本地计算摘要，不写入任何日志/文件；
     * @param bodyXml 业务体 XML（如 `<td:GetDeviceInformation/>`）。
     */
    fun buildEnvelope(username: String?, password: String?, bodyXml: String): String {
        val header = if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            buildSecurityHeader(username, password)
        } else {
            ""
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<env:Envelope")
            append(" xmlns:env=\"$NS_ENV\"")
            append(" xmlns:td=\"$NS_TD\"")
            append(" xmlns:trt=\"$NS_TRT\"")
            append(" xmlns:tt=\"http://www.onvif.org/ver10/schema\">")
            append(header)
            append("<env:Body>")
            append(bodyXml)
            append("</env:Body>")
            append("</env:Envelope>")
        }
    }

    /** WS-Security UsernameToken（摘要）。 */
    private fun buildSecurityHeader(username: String, password: String): String {
        val nonceBytes = ByteArray(16).also { random.nextBytes(it) }
        val created = iso8601UtcNow()
        val digest = passwordDigest(nonceBytes, created, password)
        return buildString {
            append("<env:Header>")
            append("<wsse:Security env:mustUnderstand=\"true\"")
            append(" xmlns:wsse=\"$NS_WSSE\" xmlns:wsu=\"$NS_WSU\">")
            append("<wsse:UsernameToken>")
            append("<wsse:Username>").append(xmlEscape(username)).append("</wsse:Username>")
            append("<wsse:Password Type=\"$NS_WSSE#PasswordDigest\">").append(digest)
            append("</wsse:Password>")
            append("<wsse:Nonce EncodingType=\"$NS_WSSE#Base64Binary\">")
            append(Base64.encodeToString(nonceBytes, Base64.NO_WRAP))
            append("</wsse:Nonce>")
            append("<wsu:Created>").append(created).append("</wsu:Created>")
            append("</wsse:UsernameToken>")
            append("</wsse:Security>")
            append("</env:Header>")
        }
    }

    /**
     * 摘要计算：Base64(SHA1(Nonce 原始字节 + Created 字符串字节 + Password UTF-8 字节))。
     * 与 ONVIF Core Spec §3.1.2 一致。
     */
    fun passwordDigest(nonceBytes: ByteArray, created: String, password: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(nonceBytes)
        md.update(created.toByteArray(Charsets.UTF_8))
        md.update(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    private fun iso8601UtcNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun xmlEscape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    // ===================== Fault 识别 =====================

    /**
     * 解析 SOAP Fault；非 Fault 响应返回 null。
     *
     * ONVIF 认证失败判定：Reason/Detail 文本命中 `NotAuthorized` / `not authorized` /
     * `Sender` + 401 语义 → [ErrorCode.AUTH_FAILED]。
     */
    fun parseFault(xml: String): AppError? {
        if (!xml.contains("Fault")) return null
        val parser = newParser(xml)
        var inFault = false
        var sawFault = false
        var inReason = false
        var inDetail = false
        var codeValue: String? = null
        var reasonText: String? = null
        var detailText: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.localName()) {
                        "Fault" -> {
                            inFault = true
                            sawFault = true
                        }
                        "Value" -> if (inFault) codeValue = parser.nextTextSafely()
                        "Text" -> if (inFault) inReason = true
                        "Detail" -> if (inFault) inDetail = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inReason) reasonText = (reasonText ?: "") + parser.text
                    if (inDetail) detailText = (detailText ?: "") + parser.text
                }
                XmlPullParser.END_TAG -> {
                    when (parser.localName()) {
                        "Text" -> inReason = false
                        "Detail" -> inDetail = false
                        "Fault" -> inFault = false
                    }
                }
            }
            event = parser.next()
        }

        // 未出现真正的 Fault 元素（"Fault" 仅出现在普通文本中）→ 非故障响应
        if (!sawFault) return null

        val combined = "${reasonText.orEmpty()} ${detailText.orEmpty()}"
        val authFailed = combined.contains("NotAuthorized", ignoreCase = true) ||
            combined.contains("not authorized", ignoreCase = true) ||
            combined.contains("unauthorized", ignoreCase = true)
        val code = if (authFailed) ErrorCode.AUTH_FAILED else ErrorCode.SOAP_FAULT
        val message = buildString {
            append("设备返回错误")
            codeValue?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            val reason = reasonText?.trim().orEmpty()
            if (reason.isNotBlank()) append("：$reason")
            val detail = detailText?.trim().orEmpty()
            if (detail.isNotBlank()) append("：$detail")
        }
        TvLog.w(TAG, "SOAP Fault：$message")
        return AppError(code, message)
    }

    // ===================== 业务响应解析 =====================

    /** 解析 GetDeviceInformationResponse。 */
    fun parseDeviceInfo(xml: String): OnvifDeviceInfo? {
        val fields = mutableMapOf<String, String>()
        val parser = newParser(xml)
        var currentTag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> currentTag = parser.localName()
                XmlPullParser.TEXT -> {
                    val tag = currentTag
                    if (tag == "Manufacturer" || tag == "Model" || tag == "FirmwareVersion") {
                        fields[tag] = fields.getOrDefault(tag, "") + parser.text
                    }
                }
            }
            event = parser.next()
        }
        if (fields.isEmpty()) return null
        return OnvifDeviceInfo(
            vendor = fields["Manufacturer"]?.trim().orEmpty(),
            model = fields["Model"]?.trim().orEmpty(),
            firmware = fields["FirmwareVersion"]?.trim().orEmpty(),
        )
    }

    /**
     * 解析 GetProfilesResponse（流式）。
     * 兼容 `trt:Profiles` / `Profiles` 前缀差异；token 取属性 `token`。
     */
    fun parseProfiles(xml: String): List<OnvifProfile> {
        val profiles = mutableListOf<OnvifProfile>()
        val parser = newParser(xml)

        var inProfiles = false
        var token: String? = null
        var name: String? = null
        var inVideoEncoder = false
        var width = 0
        var height = 0
        var encoding = ""
        var frameRate = 0
        var bitrate = 0L
        var currentTag: String? = null

        fun flushProfile() {
            val t = token
            if (t != null) {
                profiles += OnvifProfile(
                    token = t,
                    name = name?.trim().orEmpty(),
                    width = width,
                    height = height,
                    encoding = encoding,
                    frameRate = frameRate,
                    bitrateBps = bitrate,
                )
            }
            token = null
            name = null
            inVideoEncoder = false
            width = 0; height = 0; encoding = ""; frameRate = 0; bitrate = 0L
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.localName()
                    currentTag = local
                    when (local) {
                        "Profiles" -> {
                            inProfiles = true
                            token = parser.getAttributeValue(null, "token")
                        }
                        "VideoEncoderConfiguration" -> if (inProfiles) inVideoEncoder = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (!inProfiles) {
                        event = parser.next(); continue
                    }
                    val text = parser.text.trim()
                    if (text.isEmpty()) {
                        event = parser.next(); continue
                    }
                    when (currentTag) {
                        "Name" -> if (!inVideoEncoder) name = text
                        "Width" -> if (inVideoEncoder) width = text.toIntOrNull() ?: width
                        "Height" -> if (inVideoEncoder) height = text.toIntOrNull() ?: height
                        "Encoding" -> if (inVideoEncoder) encoding = text
                        "FrameRateLimit" -> if (inVideoEncoder) frameRate = text.toIntOrNull() ?: frameRate
                        "BitrateLimit" -> if (inVideoEncoder) bitrate = text.toLongOrNull() ?: bitrate
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.localName()) {
                        "VideoEncoderConfiguration" -> inVideoEncoder = false
                        "Profiles" -> {
                            flushProfile()
                            inProfiles = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        TvLog.i(TAG, "解析到 ${profiles.size} 个媒体配置")
        return profiles
    }

    /**
     * 解析 GetStreamUriResponse → rtsp 地址。
     *
     * ONVIF 规范结构：`trt:MediaUri` 内 `tt:Uri` 子元素存放流地址
     * （另有 InvalidAfterConnect / Timeout 等子元素），必须进入子元素取值，
     * 不能取 MediaUri 内首个文本节点（会误取空白或非 Uri 字段）。
     */
    fun parseStreamUri(xml: String): String? {
        val parser = newParser(xml)
        var inMediaUri = false
        var inUri = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.localName()) {
                        "MediaUri" -> inMediaUri = true
                        "Uri" -> if (inMediaUri) inUri = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inUri) {
                        val uri = parser.text.trim()
                        if (uri.isNotEmpty()) return uri
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.localName()) {
                        "Uri" -> inUri = false
                        "MediaUri" -> inMediaUri = false
                    }
                }
            }
            event = parser.next()
        }
        return null
    }

    /**
     * 解析 GetServicesResponse，提取媒体服务命名空间的 XAddr。
     * @return media service XAddr；未找到返回 null。
     */
    fun parseMediaServiceEndpoint(xml: String): String? {
        val parser = newParser(xml)
        var inService = false
        var namespace = ""
        var xaddr: String? = null
        var currentTag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.localName()
                    if (currentTag == "Service") {
                        inService = true
                        namespace = ""
                        xaddr = null
                    }
                }
                XmlPullParser.TEXT -> {
                    if (!inService) {
                        event = parser.next(); continue
                    }
                    val text = parser.text.trim()
                    if (text.isEmpty()) {
                        event = parser.next(); continue
                    }
                    when (currentTag) {
                        "Namespace" -> namespace = text
                        "XAddr" -> xaddr = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.localName() == "Service") {
                        if (namespace.contains("/ver10/media/wsdl") && !xaddr.isNullOrBlank()) {
                            return xaddr
                        }
                        inService = false
                    }
                }
            }
            event = parser.next()
        }
        return null
    }

    /** WS-Discovery Probe 报文（组播 239.255.255.250:3702）。 */
    fun buildProbeMessage(): String {
        val messageId = "urn:uuid:" + UUID.randomUUID()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<env:Envelope xmlns:env=\"$NS_ENV_11\"")
            append(" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"")
            append(" xmlns:wsadis=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">")
            append("<env:Header>")
            append("<wsadis:MessageID>").append(messageId).append("</wsadis:MessageID>")
            append("</env:Header>")
            append("<env:Body>")
            append("<wsadis:Probe>")
            append("<wsadis:Types>dn:NetworkVideoTransmitter</wsadis:Types>")
            append("</wsadis:Probe>")
            append("</env:Body>")
            append("</env:Envelope>")
        }
    }

    // ===================== 解析器工厂 =====================

    private fun newParser(xml: String): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        return parser
    }

    /** local name（剥离命名空间前缀），匹配兼容各厂商前缀差异。 */
    private fun XmlPullParser.localName(): String =
        name?.substringAfterLast(':').orEmpty()

    /** 安全读取当前元素文本（无 TEXT 时返回空串）。 */
    private fun XmlPullParser.nextTextSafely(): String = try {
        nextText()
    } catch (e: Exception) {
        ""
    }
}
