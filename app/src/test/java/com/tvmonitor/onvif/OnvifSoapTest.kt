package com.tvmonitor.onvif

import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import java.security.MessageDigest
import java.util.Base64 as JvmBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OnvifSoap] 单元测试（T2 验收 / ARCHITECTURE §7）。
 *
 * 覆盖：
 * 1. SOAP 1.2 信封结构（命名空间 / Header / Body / 业务体直通）；
 * 2. WS-Security 摘要认证：passwordDigest = Base64(SHA1(Nonce + Created + Password))
 *    固定输入向量验证 + 信封内 Nonce/Created/Digest 自洽性验证；
 * 3. GetProfiles / GetStreamUri / GetDeviceInformation / GetServices 样例响应解析
 *    （正常 / Fault / 缺字段三种样例）；
 * 4. WS-Discovery Probe 报文结构。
 *
 * 环境说明：`android.util.Base64` 在 JVM 单测中由测试源码替身
 * （app/src/test/java/android/util/Base64.kt）提供，与 Android 语义一致；
 * `org.xmlpull.v1.XmlPullParserFactory` 依赖 Robolectric 提供的真实 Android 实现
 * （android.jar 在 JVM 单测中为 Stub，直调会抛 RuntimeException，见 build.gradle.kts 注释）。
 */
@RunWith(RobolectricTestRunner::class)
class OnvifSoapTest {

    @Before
    fun silenceLog() {
        TvLog.enabled = false
    }

    // ===================== 信封结构 =====================

    @Test
    fun `buildEnvelope produces SOAP 12 envelope without header when anonymous`() {
        val envelope = OnvifSoap.buildEnvelope(
            username = null,
            password = null,
            bodyXml = "<td:GetDeviceInformation/>",
        )

        assertTrue(envelope.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(envelope.contains("<env:Envelope"))
        // SOAP 1.2 命名空间（ARCHITECTURE §7.2）
        assertTrue(envelope.contains("xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\""))
        assertTrue(envelope.contains("xmlns:td=\"http://www.onvif.org/ver10/device/wsdl\""))
        assertTrue(envelope.contains("xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""))
        assertTrue(envelope.contains("xmlns:tt=\"http://www.onvif.org/ver10/schema\""))
        // 业务体直通
        assertTrue(envelope.contains("<env:Body><td:GetDeviceInformation/></env:Body>"))
        assertTrue(envelope.endsWith("</env:Envelope>"))
        // 匿名访问不输出 Security 头
        assertFalse(envelope.contains("wsse:Security"))
        assertFalse(envelope.contains("<env:Header>"))
    }

    @Test
    fun `buildEnvelope omits security header when password missing`() {
        val envelope = OnvifSoap.buildEnvelope(username = "admin", password = null, bodyXml = "<td:GetServices/>")

        assertFalse(envelope.contains("wsse:Security"))
    }

    @Test
    fun `buildEnvelope includes WS-Security header with credentials`() {
        val envelope = OnvifSoap.buildEnvelope(
            username = "admin",
            password = "Secret123",
            bodyXml = "<td:GetDeviceInformation/>",
        )

        assertTrue(envelope.contains("<env:Header>"))
        assertTrue(envelope.contains("<wsse:Security env:mustUnderstand=\"true\""))
        assertTrue(envelope.contains("xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\""))
        assertTrue(envelope.contains("xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\""))
        assertTrue(envelope.contains("<wsse:UsernameToken>"))
        assertTrue(envelope.contains("<wsse:Username>admin</wsse:Username>"))
        assertTrue(envelope.contains("Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#PasswordDigest\""))
        assertTrue(envelope.contains("EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd#Base64Binary\""))
        assertTrue(envelope.contains("</wsse:Security></env:Header>"))
    }

    @Test
    fun `buildEnvelope escapes username xml special chars`() {
        val envelope = OnvifSoap.buildEnvelope(
            username = "a&b<c>",
            password = "p",
            bodyXml = "<td:GetDeviceInformation/>",
        )

        assertTrue(envelope.contains("<wsse:Username>a&amp;b&lt;c&gt;</wsse:Username>"))
        assertFalse(envelope.contains("<wsse:Username>a&b<c>"))
    }

    // ===================== 摘要认证 =====================

    @Test
    fun `passwordDigest matches Base64 of SHA1 over Nonce Created Password`() {
        // 固定输入向量（独立复算，不依赖被测实现路径）
        val nonce = "0123456789abcdef".toByteArray(Charsets.UTF_8) // 16 字节
        val created = "2026-10-24T09:00:00Z"
        val password = "Secret123"

        val expected = JvmBase64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").apply {
                update(nonce)
                update(created.toByteArray(Charsets.UTF_8))
                update(password.toByteArray(Charsets.UTF_8))
            }.digest(),
        )

        val actual = OnvifSoap.passwordDigest(nonce, created, password)

        assertEquals(expected, actual)
        // SHA-1 摘要 20 字节 → Base64 28 字符（android.util.Base64.NO_WRAP 保留 '=' 填充，
        // 与 java.util.Base64 一致；WS-Security PasswordDigest 规范亦为标准 Base64）
        assertEquals(28, actual.length)
    }

    @Test
    fun `passwordDigest handles utf-8 password`() {
        val nonce = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, -0x60)
        val created = "2026-01-01T00:00:00Z"
        val password = "中文密码Test"

        val expected = JvmBase64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").apply {
                update(nonce)
                update(created.toByteArray(Charsets.UTF_8))
                update(password.toByteArray(Charsets.UTF_8))
            }.digest(),
        )

        assertEquals(expected, OnvifSoap.passwordDigest(nonce, created, password))
    }

    @Test
    fun `envelope digest is self-consistent with embedded nonce and created`() {
        val password = "Secret123"
        val envelope = OnvifSoap.buildEnvelope("admin", password, "<td:GetDeviceInformation/>")

        // 从信封提取 Nonce / Created / Digest（缺失即测试失败）
        val nonceB64 = checkNotNull(extractTag(envelope, "wsse:Nonce")) { "信封缺少 wsse:Nonce" }
        val created = checkNotNull(extractTag(envelope, "wsu:Created")) { "信封缺少 wsu:Created" }
        val digest = checkNotNull(extractTag(envelope, "wsse:Password")) { "信封缺少 wsse:Password" }

        // Created 为 ISO 8601 UTC 秒级格式
        assertTrue(
            "Created 应为 ISO8601 UTC：$created",
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(created),
        )

        val expected = JvmBase64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").apply {
                update(JvmBase64.getDecoder().decode(nonceB64))
                update(created.toByteArray(Charsets.UTF_8))
                update(password.toByteArray(Charsets.UTF_8))
            }.digest(),
        )
        assertEquals(expected, digest)
    }

    // ===================== Fault 解析 =====================

    @Test
    fun `parseFault maps NotAuthorized reason to AUTH_FAILED`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
             <env:Body>
              <env:Fault>
               <env:Code><env:Value>env:Sender</env:Value></env:Code>
               <env:Reason><env:Text xml:lang="en">The sender is not authorized</env:Text></env:Reason>
              </env:Fault>
             </env:Body>
            </env:Envelope>
        """.trimIndent()

        val error = OnvifSoap.parseFault(xml)

        assertNotNull(error)
        assertEquals(ErrorCode.AUTH_FAILED, error!!.code)
        assertTrue(error.message.contains("设备返回错误"))
    }

    @Test
    fun `parseFault maps generic fault to SOAP_FAULT`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
             <env:Body>
              <env:Fault>
               <env:Code><env:Value>env:Receiver</env:Value></env:Code>
               <env:Reason><env:Text xml:lang="en">Operation timeout</env:Text></env:Reason>
               <env:Detail><terr:ConnectionError xmlns:terr="http://www.onvif.org/ver10/error">device busy</terr:ConnectionError></env:Detail>
              </env:Fault>
             </env:Body>
            </env:Envelope>
        """.trimIndent()

        val error = OnvifSoap.parseFault(xml)

        assertNotNull(error)
        assertEquals(ErrorCode.SOAP_FAULT, error!!.code)
        assertTrue(error.message.contains("Operation timeout"))
        assertTrue(error.message.contains("device busy"))
    }

    @Test
    fun `parseFault returns null for normal response`() {
        val xml = """
            <tds:GetDeviceInformationResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
             <tds:Manufacturer>HIKVISION</tds:Manufacturer>
            </tds:GetDeviceInformationResponse>
        """.trimIndent()

        assertNull(OnvifSoap.parseFault(xml))
    }

    @Test
    fun `parseFault returns null when Fault only appears in text`() {
        val xml = """
            <tds:GetDeviceInformationResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
             <tds:Manufacturer>Fault-Tolerant Inc.</tds:Manufacturer>
            </tds:GetDeviceInformationResponse>
        """.trimIndent()

        assertNull(OnvifSoap.parseFault(xml))
    }

    // ===================== GetDeviceInformation =====================

    @Test
    fun `parseDeviceInfo extracts vendor model firmware`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tds:GetDeviceInformationResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
             <tds:Manufacturer>HIKVISION</tds:Manufacturer>
             <tds:Model>DS-2CD3T46</tds:Model>
             <tds:FirmwareVersion>V5.5.0 build 20240101</tds:FirmwareVersion>
             <tds:SerialNumber>SN-0001</tds:SerialNumber>
             <tds:HardwareId>1.0</tds:HardwareId>
            </tds:GetDeviceInformationResponse>
        """.trimIndent()

        val info = OnvifSoap.parseDeviceInfo(xml)

        assertNotNull(info)
        assertEquals("HIKVISION", info!!.vendor)
        assertEquals("DS-2CD3T46", info.model)
        assertEquals("V5.5.0 build 20240101", info.firmware)
    }

    @Test
    fun `parseDeviceInfo returns null when fields missing`() {
        val xml = """
            <tds:GetDeviceInformationResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
             <tds:SerialNumber>SN-0001</tds:SerialNumber>
            </tds:GetDeviceInformationResponse>
        """.trimIndent()

        assertNull(OnvifSoap.parseDeviceInfo(xml))
    }

    @Test
    fun `parseDeviceInfo tolerates vendor prefix differences`() {
        val xml = """
            <ns1:GetDeviceInformationResponse xmlns:ns1="http://www.onvif.org/ver10/device/wsdl">
             <ns1:Manufacturer>Dahua</ns1:Manufacturer>
             <ns1:Model>IPC-HFW1230</ns1:Model>
             <ns1:FirmwareVersion>2.800</ns1:FirmwareVersion>
            </ns1:GetDeviceInformationResponse>
        """.trimIndent()

        val info = OnvifSoap.parseDeviceInfo(xml)

        assertNotNull(info)
        assertEquals("Dahua", info!!.vendor)
        assertEquals("IPC-HFW1230", info.model)
    }

    // ===================== GetProfiles =====================

    @Test
    fun `parseProfiles extracts main and sub stream configurations`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <trt:GetProfilesResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                                     xmlns:tt="http://www.onvif.org/ver10/schema">
             <trt:Profiles token="Profile_main_1">
              <trt:Name>mainStream</trt:Name>
              <trt:VideoEncoderConfiguration>
               <tt:Encoding>H264</tt:Encoding>
               <tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>
               <tt:FrameRateLimit>25</tt:FrameRateLimit>
               <tt:BitrateLimit>4096</tt:BitrateLimit>
              </trt:VideoEncoderConfiguration>
             </trt:Profiles>
             <trt:Profiles token="Profile_sub_1">
              <trt:Name>subStream</trt:Name>
              <trt:VideoEncoderConfiguration>
               <tt:Encoding>H264</tt:Encoding>
               <tt:Resolution><tt:Width>640</tt:Width><tt:Height>360</tt:Height></tt:Resolution>
               <tt:FrameRateLimit>15</tt:FrameRateLimit>
               <tt:BitrateLimit>512</tt:BitrateLimit>
              </trt:VideoEncoderConfiguration>
             </trt:Profiles>
            </trt:GetProfilesResponse>
        """.trimIndent()

        val profiles = OnvifSoap.parseProfiles(xml)

        assertEquals(2, profiles.size)
        val main = profiles[0]
        assertEquals("Profile_main_1", main.token)
        assertEquals("mainStream", main.name)
        assertEquals(1920, main.width)
        assertEquals(1080, main.height)
        assertEquals("H264", main.encoding)
        assertEquals(25, main.frameRate)
        assertEquals(4096L, main.bitrateBps)
        val sub = profiles[1]
        assertEquals("Profile_sub_1", sub.token)
        assertEquals(640, sub.width)
        assertEquals(360, sub.height)
        assertEquals(512L, sub.bitrateBps)
    }

    @Test
    fun `parseProfiles tolerates missing video encoder configuration`() {
        val xml = """
            <trt:GetProfilesResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
             <trt:Profiles token="Profile_audio_only">
              <trt:Name>audioOnly</trt:Name>
             </trt:Profiles>
            </trt:GetProfilesResponse>
        """.trimIndent()

        val profiles = OnvifSoap.parseProfiles(xml)

        assertEquals(1, profiles.size)
        assertEquals(0, profiles[0].width)
        assertEquals(0, profiles[0].height)
        assertEquals("", profiles[0].encoding)
    }

    @Test
    fun `parseProfiles returns empty list for response without profiles`() {
        val xml = """
            <trt:GetProfilesResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>
        """.trimIndent()

        assertTrue(OnvifSoap.parseProfiles(xml).isEmpty())
    }

    // ===================== GetStreamUri =====================

    @Test
    fun `parseStreamUri extracts rtsp uri`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <trt:GetStreamUriResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                                      xmlns:tt="http://www.onvif.org/ver10/schema">
             <trt:MediaUri>
              <tt:Uri>rtsp://192.168.1.61:554/Streaming/Channels/201</tt:Uri>
              <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
              <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
              <tt:Timeout>PT60S</tt:Timeout>
             </trt:MediaUri>
            </trt:GetStreamUriResponse>
        """.trimIndent()

        val uri = OnvifSoap.parseStreamUri(xml)

        assertEquals("rtsp://192.168.1.61:554/Streaming/Channels/201", uri)
    }

    @Test
    fun `parseStreamUri returns null when MediaUri missing`() {
        val xml = """
            <trt:GetStreamUriResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
             <trt:MediaUri>
              <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
             </trt:MediaUri>
            </trt:GetStreamUriResponse>
        """.trimIndent()

        assertNull(OnvifSoap.parseStreamUri(xml))
    }

    @Test
    fun `parseStreamUri returns null for empty uri`() {
        val xml = """
            <trt:GetStreamUriResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
             <trt:MediaUri><tt:Uri></tt:Uri></trt:MediaUri>
            </trt:GetStreamUriResponse>
        """.trimIndent()

        assertNull(OnvifSoap.parseStreamUri(xml))
    }

    // ===================== GetServices =====================

    @Test
    fun `parseMediaServiceEndpoint returns media service xaddr`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tds:GetServicesResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                                     xmlns:tt="http://www.onvif.org/ver10/schema">
             <tds:Service>
              <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
              <tds:XAddr>http://192.168.1.61/onvif/device_service</tds:XAddr>
              <tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version>
             </tds:Service>
             <tds:Service>
              <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
              <tds:XAddr>http://192.168.1.61/onvif/media_service</tds:XAddr>
              <tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version>
             </tds:Service>
             <tds:Service>
              <tds:Namespace>http://www.onvif.org/ver10/events/wsdl</tds:Namespace>
              <tds:XAddr>http://192.168.1.61/onvif/event_service</tds:XAddr>
             </tds:Service>
            </tds:GetServicesResponse>
        """.trimIndent()

        assertEquals("http://192.168.1.61/onvif/media_service", OnvifSoap.parseMediaServiceEndpoint(xml))
    }

    @Test
    fun `parseMediaServiceEndpoint returns null when media service absent`() {
        val xml = """
            <tds:GetServicesResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
             <tds:Service>
              <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
              <tds:XAddr>http://192.168.1.61/onvif/device_service</tds:XAddr>
             </tds:Service>
            </tds:GetServicesResponse>
        """.trimIndent()

        assertNull(OnvifSoap.parseMediaServiceEndpoint(xml))
    }

    // ===================== WS-Discovery Probe =====================

    @Test
    fun `buildProbeMessage uses SOAP 11 envelope and discovery namespace`() {
        val probe = OnvifSoap.buildProbeMessage()

        // WS-Discovery 走 SOAP 1.1（ARCHITECTURE §7.1）
        assertTrue(probe.contains("xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\""))
        assertTrue(probe.contains("xmlns:wsadis=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""))
        assertTrue(probe.contains("xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\""))
        assertTrue(probe.contains("<wsadis:Probe>"))
        assertTrue(probe.contains("<wsadis:Types>dn:NetworkVideoTransmitter</wsadis:Types>"))
        assertTrue(probe.contains("<wsadis:MessageID>urn:uuid:"))
    }

    // ===================== 辅助 =====================

    /** 提取信封中 `<tag ...>text</tag>` 的文本（兼容开始标签带属性，如 wsse:Nonce EncodingType=...）。 */
    private fun extractTag(xml: String, tag: String): String? {
        val open = "<$tag"
        val start = xml.indexOf(open)
        if (start < 0) return null
        // 防止 `<wsse:Nonce` 误匹配 `<wsse:NonceXxx`
        val after = xml[start + open.length]
        if (after != ' ' && after != '>' && after != '/') return null
        val gt = xml.indexOf('>', start)
        if (gt < 0) return null
        val close = "</$tag>"
        val end = xml.indexOf(close, gt)
        if (end < 0) return null
        return xml.substring(gt + 1, end).trim()
    }
}
