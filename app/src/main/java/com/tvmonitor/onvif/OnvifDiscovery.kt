package com.tvmonitor.onvif

import android.content.Context
import android.net.wifi.WifiManager
import com.tvmonitor.data.OnvifDevice
import com.tvmonitor.util.AppError
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * ONVIF 设备发现（WS-Discovery）。
 *
 * 机制：
 * - 向组播地址 `239.255.255.250:3702` 发送 [OnvifSoap.buildProbeMessage]（Probe）；
 * - 监听 [listenTimeoutMs]（默认 4s）内的 ProbeMatch 响应，流式解析 XAddrs / Scopes；
 * - 部分电视/盒子需 MulticastLock 才能收组播，按 best-effort 申请（失败不影响单播发现）；
 * - 结果按 endpoint 去重；`onvif://www.onvif.org/name/xxx` 提取设备名，缺省用 IP。
 *
 * 取消语义：调用方协程取消时关闭 socket，立即退出等待。
 */
class OnvifDiscovery(
    private val context: Context? = null,
    private val listenTimeoutMs: Long = DEFAULT_LISTEN_TIMEOUT_MS,
) {

    /**
     * 执行一次探测。
     *
     * @return 设备列表（可能为空 = 网内无设备）；网络异常返回
     *         [ErrorCode.DISCOVERY_FAILED] / [ErrorCode.NETWORK]。
     */
    suspend fun discover(): AppResult<List<OnvifDevice>> = withContext(Dispatchers.IO) {
        var socket: MulticastSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            socket = MulticastSocket(DISCOVERY_PORT).apply { soTimeout = RECV_TIMEOUT_MS }
            val group = InetAddress.getByName(MULTICAST_ADDRESS)

            // best-effort 组播锁：某些 WLAN 芯片默认过滤组播
            multicastLock = acquireMulticastLock()
            runCatching { socket.joinGroup(group) }
                .onFailure { TvLog.w(TAG, "加入组播组失败：${it.message}") }

            val probe = OnvifSoap.buildProbeMessage().toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(probe, probe.size, group, DISCOVERY_PORT))
            TvLog.i(TAG, "WS-Discovery Probe 已发送，监听 ${listenTimeoutMs}ms")

            val devices = mutableMapOf<String, OnvifDevice>() // endpoint -> device（去重）
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val deadline = System.currentTimeMillis() + listenTimeoutMs

            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val xml = String(
                        packet.data, packet.offset, packet.length, Charsets.UTF_8,
                    )
                    parseProbeMatches(xml).forEach { device ->
                        devices.putIfAbsent(device.endpoint, device)
                    }
                } catch (e: SocketTimeoutException) {
                    // 单次接收超时：继续等待直到总截止
                    continue
                }
            }
            val list = devices.values.sortedBy { it.ip }
            TvLog.i(TAG, "发现完成：${list.size} 台设备")
            AppResult.Success(list)
        } catch (e: IOException) {
            TvLog.e(TAG, "设备发现失败", e)
            AppResult.Failure(
                AppError(
                    ErrorCode.DISCOVERY_FAILED,
                    "设备扫描失败：${e.message ?: "网络不可用"}",
                    e,
                ),
            )
        } finally {
            runCatching { socket?.close() }
            runCatching {
                multicastLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    /** 申请组播锁（best-effort，无 wifi 服务时返回 null）。 */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val ctx = context ?: return null
        val wifiManager = ctx.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            wifiManager.createMulticastLock("tvmonitor_onvif").apply {
                setReferenceCounted(true)
                acquire()
            }
        }.onFailure { TvLog.w(TAG, "MulticastLock 申请失败：${it.message}") }.getOrNull()
    }

    /**
     * 流式解析 ProbeMatches 响应。
     * 兼容 `d:` / `wsdis:` 前缀差异（按 local name 匹配）。
     */
    private fun parseProbeMatches(xml: String): List<OnvifDevice> {
        val devices = mutableListOf<OnvifDevice>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        var inProbeMatch = false
        var currentTag: String? = null
        var xAddrs: String? = null
        var scopes: String? = null

        fun flush() {
            val addr = xAddrs?.trim().orEmpty()
            if (inProbeMatch && addr.isNotEmpty()) {
                // XAddrs 可能含多个地址（空格分隔），取首个 http(s) 地址
                val endpoint = addr.split(' ', '\n', '\t')
                    .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                    ?: addr
                val (ip, port) = hostPortOf(endpoint)
                val scopeList = scopes?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
                devices += OnvifDevice(
                    endpoint = endpoint,
                    ip = ip,
                    port = port,
                    name = extractName(scopeList) ?: ip,
                    hardware = extractScopeValue(scopeList, "hardware").orEmpty(),
                    location = extractScopeValue(scopeList, "location").orEmpty(),
                )
            }
            xAddrs = null
            scopes = null
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name?.substringAfterLast(':').orEmpty()
                    currentTag = local
                    if (local == "ProbeMatch") inProbeMatch = true
                }
                XmlPullParser.TEXT -> {
                    if (!inProbeMatch) {
                        event = parser.next(); continue
                    }
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) {
                        event = parser.next(); continue
                    }
                    when (currentTag) {
                        "XAddrs" -> xAddrs = text
                        "Scopes" -> scopes = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name?.substringAfterLast(':') == "ProbeMatch") {
                        flush()
                        inProbeMatch = false
                    }
                }
            }
            event = parser.next()
        }
        return devices
    }

    /** 从 endpoint 提取 host 与 port（异常时兜底）。 */
    private fun hostPortOf(endpoint: String): Pair<String, Int> = runCatching {
        val uri = URI(endpoint)
        val host = uri.host ?: return@runCatching ("" to 80)
        val port = if (uri.port > 0) uri.port else 80
        host to port
    }.getOrDefault("" to 80)

    /** 提取 name scope：`onvif://www.onvif.org/name/xxx`（URL 解码）。 */
    private fun extractName(scopes: List<String>): String? {
        val raw = extractScopeValue(scopes, "name") ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    /** 提取指定分类的 scope 值（取末段）。 */
    private fun extractScopeValue(scopes: List<String>, category: String): String? =
        scopes.firstOrNull { it.contains("/$category/") }
            ?.substringAfter("/$category/")
            ?.trimEnd('/')

    companion object {
        private const val TAG = "TvMonitor:OnvifDiscovery"

        /** WS-Discovery 标准组播地址与端口。 */
        const val MULTICAST_ADDRESS = "239.255.255.250"
        const val DISCOVERY_PORT = 3702

        /** 默认监听窗口 4s（T2 验收：4s 内列出设备）。 */
        const val DEFAULT_LISTEN_TIMEOUT_MS = 4_000L

        /** 单次 receive 超时 500ms，保证取消及时响应。 */
        private const val RECV_TIMEOUT_MS = 500

        /** UDP 报文缓冲 64KB（XAddrs+Scopes 富设备足够）。 */
        private const val MAX_PACKET_SIZE = 64 * 1024
    }
}
