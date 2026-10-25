package com.tvmonitor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.tvmonitor.R
import com.tvmonitor.data.PlaybackStatus
import com.tvmonitor.playback.SlotUiState
import com.tvmonitor.util.ErrorCode
import com.tvmonitor.databinding.ViewSlotBinding

/**
 * 四分格单格视图（T4）。
 *
 * 职责：
 * 1. 承载 PlayerView（由 [com.tvmonitor.playback.QuadPlaybackManager] 挂载/摘除 player，
 *    本类不直接持有播放器，避免视图与播放生命周期耦合）；
 * 2. 渲染 [SlotUiState]：源名角标、状态点、重连倒计时、错误浮层 + 手动重试；
 * 3. 焦点描边：获得 D-pad 焦点时显示 3dp 主题色边框。
 *
 * 线程：公开方法主线程调用。
 */
class SlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewSlotBinding.inflate(LayoutInflater.from(context), this, true)

    /** 格位下标 0..3（由 QuadGridView 注入）。 */
    var slotIndex: Int = -1
        private set

    /** 供播放管理器挂载播放器的 PlayerView。 */
    val playerView: androidx.media3.ui.PlayerView
        get() = binding.playerView

    /** 手动重试点击（错误态浮层按钮）。 */
    var onRetryClick: (() -> Unit)? = null

    init {
        binding.slotRetryButton.setOnClickListener { onRetryClick?.invoke() }
        setOnFocusChangeListener { _, hasFocus ->
            binding.slotFocusBorder.isVisible = hasFocus
        }
        isClickable = true
    }

    /** 绑定格位下标（QuadGridView 初始化时调用）。 */
    fun bindIndex(index: Int) {
        slotIndex = index
    }

    /** 静音态图标（US-09：角标内喇叭图标，仅反映本槽静音状态）。 */
    fun setMuted(muted: Boolean) {
        binding.slotMuteIcon.isVisible = muted
    }

    /** 渲染状态。 */
    fun render(state: SlotUiState) {
        binding.slotSourceName.text =
            state.source?.name ?: context.getString(R.string.slot_unconfigured)
        binding.slotPlaceholder.isVisible = state.source == null
        binding.slotBadge.isVisible = state.source != null

        val dotColorRes = when (state.status) {
            PlaybackStatus.ONLINE -> R.color.status_online
            PlaybackStatus.LOADING -> R.color.status_loading
            PlaybackStatus.RECONNECTING -> R.color.status_reconnecting
            PlaybackStatus.ERROR -> R.color.status_offline
            PlaybackStatus.IDLE -> R.color.status_idle
        }
        binding.slotStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, dotColorRes),
        )

        when (state.status) {
            PlaybackStatus.RECONNECTING -> {
                binding.slotErrorContainer.isVisible = true
                binding.slotRetryButton.isVisible = false
                binding.slotErrorText.text = context.getString(
                    R.string.reconnect_countdown_format,
                    state.countdownSec,
                    state.reconnectAttempt,
                )
            }

            PlaybackStatus.ERROR -> {
                binding.slotErrorContainer.isVisible = true
                binding.slotRetryButton.isVisible = true
                binding.slotErrorText.text = errorCodeToUserMessage(
                    context = context,
                    code = state.errorCode,
                    fallback = state.errorMessage,
                )
            }

            else -> binding.slotErrorContainer.isVisible = false
        }
    }
}

/**
 * 错误码 → 用户可见文案（strings.xml 全量中文化的唯一定制点）。
 *
 * 播放层只产出 [ErrorCode] 与面向日志的 message；UI 文案统一在此映射，
 * 满足“用户可见文案全部进 strings.xml”的共享约定。
 */
internal fun errorCodeToUserMessage(
    context: Context,
    code: ErrorCode?,
    fallback: String?,
): String = when (code) {
    ErrorCode.PROTOCOL_UNSUPPORTED ->
        context.getString(R.string.error_unsupported_protocol)
    ErrorCode.AUTH_FAILED ->
        context.getString(R.string.error_auth_failed)
    ErrorCode.TIMEOUT ->
        context.getString(R.string.error_timeout)
    ErrorCode.NETWORK, ErrorCode.CONNECTION_REFUSED ->
        context.getString(R.string.error_network)
    ErrorCode.NOT_FOUND ->
        context.getString(R.string.error_source_not_found)
    ErrorCode.PARSE_FAILED ->
        context.getString(R.string.playback_error_format, context.getString(R.string.error_parse))
    ErrorCode.INVALID_ARGUMENT ->
        context.getString(R.string.error_invalid_input)
    else -> fallback ?: context.getString(R.string.error_unknown)
}

/**
 * [com.tvmonitor.util.AppError] 的用户可见文案快捷方式：
 * 已知错误码映射 strings.xml；未登记错误码（如 SOAP_FAULT 设备原文）回退 message。
 */
internal fun com.tvmonitor.util.AppError.toUserMessage(context: Context): String =
    errorCodeToUserMessage(context, code, message)
