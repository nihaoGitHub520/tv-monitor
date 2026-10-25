package com.tvmonitor.util

import android.util.Log
import com.tvmonitor.BuildConfig

/**
 * 全局日志工具（ARCHITECTURE.md §11 共享约定）。
 *
 * 约定：
 * 1. TAG 统一格式 `TvMonitor:<模块>`（如 `TvMonitor:Playback`），可用 [tagOf] 生成；
 * 2. 开关默认取 `BuildConfig.DEBUG`，设置页可运行时覆写 [enabled]；
 * 3. **严禁打印密码/凭据**：所有可能含敏感信息的文本先经 [maskCredentials] 脱敏；
 * 4. 只输出到 logcat，不落盘（避免明文凭据外泄）。
 */
object TvLog {

    /** 日志总开关；UI 线程写、任意线程读，需 volatile。 */
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    /** 生成本工程规范 TAG：`TvMonitor:<模块>`。 */
    fun tagOf(module: String): String = "$PREFIX:$module"

    /** 详情日志：仅在 Debug 且开关开启时输出。 */
    fun v(tag: String, msg: String) {
        if (enabled) Log.v(tag, maskCredentials(msg))
    }

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, maskCredentials(msg))
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, maskCredentials(msg))
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (enabled) Log.w(tag, maskCredentials(msg), tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (enabled) Log.e(tag, maskCredentials(msg), tr)
    }

    /**
     * 脱敏密码类敏感字段。
     *
     * 两级脱敏：
     * 1. 匹配 `password=xxx` / `"pwd": "xxx"` / `token: xxx` 等常见写法，
     *    仅保留前 2 个字符，其余替换为 `****`；
     * 2. 匹配 URL 内嵌凭据 `scheme://user:pass@host`（如 ONVIF 注入后的 rtsp 地址），
     *    用户信息整体替换为 `***`（ARCHITECTURE §11.3：凭据严禁进 logcat）。
     * 正常地址、IP 等不受影响。
     */
    fun maskCredentials(msg: String): String {
        if (msg.isEmpty()) return msg
        val masked = SENSITIVE_PATTERN.replace(msg) { m ->
            val key = m.groupValues[KEY_GROUP]
            val sep = m.groupValues[SEP_GROUP]
            val value = m.groupValues[VALUE_GROUP]
            val prefix = if (value.length > MIN_KEEP) value.take(MIN_KEEP) else ""
            "$key$sep$prefix****"
        }
        return URL_CREDENTIAL_PATTERN.replace(masked) { m ->
            "${m.groupValues[URL_SCHEME_SEP_GROUP]}***@"
        }
    }

    private const val PREFIX = "TvMonitor"
    private const val MIN_KEEP = 2
    private const val KEY_GROUP = 1
    private const val SEP_GROUP = 2
    private const val VALUE_GROUP = 3

    /** [URL_CREDENTIAL_PATTERN] 分组下标：`://` 分隔符。 */
    private const val URL_SCHEME_SEP_GROUP = 1

    /** key(密码类关键字) + 分隔符 + 值（长度 >= 3 才脱敏，避免误伤短值）。 */
    private val SENSITIVE_PATTERN = Regex(
        "(?i)\\b(password|passwd|pwd|secret|token|credential|authorization)" +
            "([\"'\\s:=]+)([^\\s,;&\"'}\\)\\]\\[]{3,})",
    )

    /**
     * URL 内嵌凭据：`://` + `user:pass` + `@`（用户名/密码不含 `/@` 与空白）。
     * 替换保留 `://`，用户信息整体打码。
     */
    private val URL_CREDENTIAL_PATTERN = Regex("(://)[^/@\\s]+:[^/@\\s]+@")
}
