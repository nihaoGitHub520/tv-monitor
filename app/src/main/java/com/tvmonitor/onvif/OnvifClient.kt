package com.tvmonitor.onvif

import com.tvmonitor.data.OnvifDeviceInfo
import com.tvmonitor.data.OnvifProfile
import com.tvmonitor.util.AppError
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.util.TvLog
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ONVIF 设备客户端（设备服务 + 媒体服务）。
 *
 * 职责链路（US-03）：
 * 1. [getDeviceInfo]：GetDeviceInformation 拉取厂商/型号；
 * 2. [resolveMediaEndpoint]：GetServices 定位媒体服务 XAddr（失败回退设备服务地址）；
 * 3. [getProfiles]：GetProfiles 拉取主/子码流配置列表；
 * 4. [getStreamUri]：GetStreamUri 取 rtsp 播放地址；
 * 5. [getProfilesWithStreamUris]：上述链路组合，供添加向导展示码流单选列表。
 *
 * 安全约定：密码仅用于 SOAP 头摘要计算，经 [TvLog] 脱敏，绝不落盘/外发。
 * 传输：OkHttp（[OnvifSoap.CONTENT_TYPE]），错误码映射见各函数注释。
 */
class OnvifClient(
    /** 设备服务地址（发现结果或手填 IP 拼接），如 `http://192.168.1.61/onvif/device_service`。 */
    private val deviceEndpoint: String,
    private val username: String = "",
    private val password: String = "",
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /** 设备信息（厂商/型号/固件）。 */
    suspend fun getDeviceInfo(): AppResult<OnvifDeviceInfo> = withContext(Dispatchers.IO) {
        val body = "<td:GetDeviceInformation/>"
        when (val resp = post(body)) {
            is AppResult.Failure -> resp
            is AppResult.Success -> {
                val info = OnvifSoap.parseDeviceInfo(resp.data)
                if (info == null) {
                    AppResult.Failure(
                        AppError(ErrorCode.PARSE_FAILED, "设备信息解析失败"),
                    )
                } else {
                    TvLog.i(TAG, "设备信息：${info.vendor} ${info.model}")
                    AppResult.Success(info)
                }
            }
        }
    }

    /**
     * 连接测试：能拉到设备信息即视为连通（T5 验收：保存时校验连通性）。
     */
    suspend fun testConnection(): AppResult<Unit> = when (val r = getDeviceInfo()) {
        is AppResult.Failure -> r
        is AppResult.Success -> AppResult.Success(Unit)
    }

    /** 媒体服务地址；GetServices 失败时回退设备服务地址（多数设备复用同一端点）。 */
    suspend fun resolveMediaEndpoint(): AppResult<String> = withContext(Dispatchers.IO) {
        val body = "<td:GetServices><td:IncludeCapability>false</td:IncludeCapability></td:GetServices>"
        when (val resp = post(body)) {
            is AppResult.Failure -> {
                // GetServices 非关键路径：失败时回退设备端点，不阻断后续流程
                TvLog.w(TAG, "GetServices 失败，回退设备端点")
                AppResult.Success(deviceEndpoint)
            }
            is AppResult.Success -> {
                val media = OnvifSoap.parseMediaServiceEndpoint(resp.data)
                if (media.isNullOrBlank()) {
                    AppResult.Success(deviceEndpoint)
                } else {
                    AppResult.Success(media)
                }
            }
        }
    }

    /** 媒体配置列表（主/子码流）。 */
    suspend fun getProfiles(mediaEndpoint: String): AppResult<List<OnvifProfile>> =
        withContext(Dispatchers.IO) {
            val body = "<trt:GetProfiles/>"
            val endpoint = mediaEndpoint.ifBlank { deviceEndpoint }
            when (val resp = post(body, endpoint)) {
                is AppResult.Failure -> resp
                is AppResult.Success -> {
                    val profiles = OnvifSoap.parseProfiles(resp.data)
                    if (profiles.isEmpty()) {
                        AppResult.Failure(
                            AppError(ErrorCode.NOT_FOUND, "设备未返回码流配置"),
                        )
                    } else {
                        AppResult.Success(profiles)
                    }
                }
            }
        }

    /** 取指定配置的流地址（rtsp）。 */
    suspend fun getStreamUri(
        mediaEndpoint: String,
        profileToken: String,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val body = buildString {
            append("<trt:GetStreamUri>")
            append("<trt:StreamSetup>")
            append("<tt:Stream>RTP-Unicast</tt:Stream>")
            append("<tt:Transport><tt:Protocol>UDP</tt:Protocol></tt:Transport>")
            append("</trt:StreamSetup>")
            append("<trt:ProfileToken>").append(profileToken).append("</trt:ProfileToken>")
            append("</trt:GetStreamUri>")
        }
        val endpoint = mediaEndpoint.ifBlank { deviceEndpoint }
        when (val resp = post(body, endpoint)) {
            is AppResult.Failure -> resp
            is AppResult.Success -> {
                val uri = OnvifSoap.parseStreamUri(resp.data)
                if (uri.isNullOrBlank()) {
                    AppResult.Failure(AppError(ErrorCode.PARSE_FAILED, "流地址解析失败"))
                } else {
                    AppResult.Success(uri)
                }
            }
        }
    }

    /**
     * 组合链路：定位媒体服务 → 取配置列表 → 逐个取流地址。
     * 供添加向导展示“主/子码流 + 地址”列表（默认推荐子码流由 UI 排序实现）。
     */
    suspend fun getProfilesWithStreamUris(): AppResult<List<Pair<OnvifProfile, String>>> =
        withContext(Dispatchers.IO) {
            when (val endpointResult = resolveMediaEndpoint()) {
                is AppResult.Failure -> return@withContext endpointResult
                is AppResult.Success -> {
                    val mediaEndpoint = endpointResult.data
                    when (val profilesResult = getProfiles(mediaEndpoint)) {
                        is AppResult.Failure -> return@withContext profilesResult
                        is AppResult.Success -> {
                            val pairs = mutableListOf<Pair<OnvifProfile, String>>()
                            for (profile in profilesResult.data) {
                                when (val uriResult = getStreamUri(mediaEndpoint, profile.token)) {
                                    is AppResult.Success ->
                                        pairs += profile to uriResult.data
                                    is AppResult.Failure ->
                                        TvLog.w(TAG, "配置 ${profile.token} 取流失败：${uriResult.error.message}")
                                }
                            }
                            if (pairs.isEmpty()) {
                                AppResult.Failure(
                                    AppError(ErrorCode.UNKNOWN, "所有码流取流地址失败"),
                                )
                            } else {
                                AppResult.Success(pairs.toList())
                            }
                        }
                    }
                }
            }
        }

    // ===================== 传输层 =====================

    /**
     * SOAP POST。错误映射：
     * - HTTP 401/403 → [ErrorCode.AUTH_FAILED]；
     * - SOAP Fault → [ErrorCode.SOAP_FAULT] / [ErrorCode.AUTH_FAILED]（NotAuthorized）；
     * - 超时 → [ErrorCode.TIMEOUT]；IO 异常 → [ErrorCode.NETWORK]。
     */
    private suspend fun post(bodyXml: String, endpoint: String = deviceEndpoint): AppResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val envelope = OnvifSoap.buildEnvelope(
                    username = username,
                    password = password,
                    bodyXml = bodyXml,
                )
                val request = Request.Builder()
                    .url(endpoint)
                    .post(envelope.toRequestBody(SOAP_MEDIA_TYPE))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val code = response.code
                        return@use if (code == 401 || code == 403) {
                            AppResult.Failure(
                                AppError(ErrorCode.AUTH_FAILED, "认证失败（HTTP $code），请检查账号密码"),
                            )
                        } else {
                            AppResult.Failure(
                                AppError(ErrorCode.NETWORK, "设备返回 HTTP $code"),
                            )
                        }
                    }
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) {
                        return@use AppResult.Failure(
                            AppError(ErrorCode.NETWORK, "设备返回空响应"),
                        )
                    }
                    val fault = OnvifSoap.parseFault(text)
                    if (fault != null) {
                        return@use AppResult.Failure(fault)
                    }
                    AppResult.Success(text)
                }
            } catch (e: SocketTimeoutException) {
                AppResult.Failure(AppError(ErrorCode.TIMEOUT, "设备响应超时", e))
            } catch (e: IOException) {
                AppResult.Failure(
                    AppError(ErrorCode.NETWORK, "网络错误：${e.message ?: "连接失败"}", e),
                )
            } catch (e: IllegalArgumentException) {
                // url 非法（手填 IP 校验疏漏场景）
                AppResult.Failure(
                    AppError(ErrorCode.INVALID_ARGUMENT, "设备地址无效：${e.message}", e),
                )
            }
        }

    companion object {
        private const val TAG = "TvMonitor:OnvifClient"

        private val SOAP_MEDIA_TYPE = OnvifSoap.CONTENT_TYPE.toMediaType()

        /** 默认传输客户端：连接 5s / 读写 10s（内网设备响应快，超时短于公网）。 */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
