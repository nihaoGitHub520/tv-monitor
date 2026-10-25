package com.tvmonitor.util

/**
 * 跨层异步返回的统一结果封装（ARCHITECTURE.md §11：跨层异步返回 `AppResult<T>`，不抛业务异常）。
 *
 * - [Success]：成功，携带数据；
 * - [Failure]：失败，携带 [AppError]（含错误码、面向用户/日志的消息、原始异常）。
 *
 * 使用方式：仓库、网络、播放内核等所有可能失败的调用一律返回 `AppResult`，
 * UI 层根据 `Failure.error.code` 做文案映射（错误文案同样走 strings.xml，由调用方映射）。
 */
sealed class AppResult<out T> {

    /** 成功结果。 */
    data class Success<out T>(val data: T) : AppResult<T>()

    /** 失败结果；[error] 必非空。 */
    data class Failure(val error: AppError) : AppResult<Nothing>()

    /** 是否成功。 */
    val isSuccess: Boolean get() = this is Success

    /** 是否失败。 */
    val isFailure: Boolean get() = this is Failure
}

/**
 * 统一错误描述。
 *
 * @property code 机器可读错误码，UI/上层据此分支处理；
 * @property message 面向日志的详细消息（已脱敏，禁止含明文密码）；
 * @property cause 原始异常，便于排查；可为空。
 */
data class AppError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null,
)

/** 全工程错误码集中定义（新增错误必须在此登记，不得散落魔法字符串）。 */
enum class ErrorCode {
    /** 网络不可达 / DNS 失败 / 连接重置。 */
    NETWORK,

    /** 连接或读取超时。 */
    TIMEOUT,

    /** 认证失败（ONVIF 摘要认证错误、401/403）。 */
    AUTH_FAILED,

    /** 协议不支持（rtmp/http-flv 等）。 */
    PROTOCOL_UNSUPPORTED,

    /** 报文解析失败（m3u / SOAP / JSON）。 */
    PARSE_FAILED,

    /** 文件读写失败。 */
    IO_FAILED,

    /** 目标不存在（源、文件、设备）。 */
    NOT_FOUND,

    /** 入参非法（表单校验）。 */
    INVALID_ARGUMENT,

    /** 连接被拒绝。 */
    CONNECTION_REFUSED,

    /** SOAP Fault（ONVIF 业务错误）。 */
    SOAP_FAULT,

    /** 设备发现失败。 */
    DISCOVERY_FAILED,

    /** 加密/解密失败（Keystore）。 */
    CRYPTO_FAILED,

    /** 协程被取消（非错误，正常控制流）。 */
    CANCELLED,

    /** 未分类错误。 */
    UNKNOWN,
}

// ===================== 便捷构造 =====================

/** 把数据包成成功结果。 */
fun <T> T.asSuccess(): AppResult<T> = AppResult.Success(this)

/** 把错误包成失败结果。 */
fun AppError.asFailure(): AppResult<Nothing> = AppResult.Failure(this)

/** 快速构造失败结果。 */
fun failureOf(
    code: ErrorCode,
    message: String,
    cause: Throwable? = null,
): AppResult<Nothing> = AppResult.Failure(AppError(code, message, cause))

// ===================== 组合子 =====================

/** 成功时映射数据，失败原样传递。 */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(data))
        is AppResult.Failure -> this
    }

/** 成功时执行副作用（如刷新 UI），失败原样传递。 */
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

/** 失败时执行副作用（如弹错误提示），成功原样传递。 */
inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

/** 成功取数据，失败取 null。 */
fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data

/** 失败取错误，成功取 null。 */
fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error

/**
 * 挂起块安全执行器：捕获非取消类异常并转为 [AppResult.Failure]。
 *
 * - [defaultCode]：异常未匹配到具体错误码时的兜底码；
 * - [CancellationException] 原样重抛，遵守协程取消语义；
 * - 其余异常按类型映射 [ErrorCode]（超时/IO/网络等），消息经 [TvLog.maskCredentials] 脱敏。
 */
suspend fun <T> runCatchingResult(
    defaultCode: ErrorCode = ErrorCode.UNKNOWN,
    block: suspend () -> T,
): AppResult<T> = try {
    AppResult.Success(block())
} catch (ce: kotlinx.coroutines.CancellationException) {
    throw ce
} catch (t: Throwable) {
    val code = when (t) {
        is java.net.SocketTimeoutException -> ErrorCode.TIMEOUT
        is java.net.ConnectException -> ErrorCode.CONNECTION_REFUSED
        is java.net.UnknownHostException -> ErrorCode.NETWORK
        is java.io.IOException -> ErrorCode.NETWORK
        is java.io.FileNotFoundException -> ErrorCode.NOT_FOUND
        is kotlinx.serialization.SerializationException -> ErrorCode.PARSE_FAILED
        else -> defaultCode
    }
    AppResult.Failure(
        AppError(
            code = code,
            message = TvLog.maskCredentials(t.message ?: t::class.java.simpleName),
            cause = t,
        ),
    )
}
