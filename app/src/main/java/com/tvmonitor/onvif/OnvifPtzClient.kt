package com.tvmonitor.onvif

import com.tvmonitor.util.AppError
import com.tvmonitor.util.AppResult
import com.tvmonitor.util.ErrorCode

/**
 * ONVIF PTZ 云台客户端接口（FR-P1-01，P1 预留）。
 *
 * 范围说明（T2 验收 5：未实现能力在接口层明确空实现，不影响编译）：
 * - 当前 P0 交付只提供接口与 [NoOpPtzClient]；
 * - 实控实现（ContinuousMove / Stop / GotoPreset）排期 P1，
 *   实现类放在本包（如 `OnvifPtzClientImpl`），不改动接口与调用方。
 */
interface OnvifPtzClient {

    /**
     * 连续转动（按下方向键期间周期调用，松键 stop）。
     *
     * @param profileToken 带 PTZ 配置的媒体 profile token；
     * @param pan 水平速度 -1.0..1.0（负左正右），0 不转；
     * @param tilt 垂直速度 -1.0..1.0（负下正上），0 不转；
     * @param zoom 缩放速度 -1.0..1.0（负拉远正推近），0 不变。
     */
    suspend fun continuousMove(
        profileToken: String,
        pan: Float,
        tilt: Float,
        zoom: Float = 0f,
    ): AppResult<Unit>

    /** 停止云台转动。 */
    suspend fun stop(profileToken: String): AppResult<Unit>

    /** 预置位列表。 */
    suspend fun getPresets(profileToken: String): AppResult<List<PtzPreset>>

    /** 调用预置位。 */
    suspend fun gotoPreset(profileToken: String, presetToken: String): AppResult<Unit>
}

/** 预置位。 */
data class PtzPreset(
    val token: String,
    val name: String,
)

/**
 * 空实现（P0 占位）：所有操作返回“暂不支持”。
 * UI 侧据此置灰云台面板（PRD：设备不支持 PTZ 时面板隐藏或置灰提示）。
 */
class NoOpPtzClient : OnvifPtzClient {

    override suspend fun continuousMove(
        profileToken: String,
        pan: Float,
        tilt: Float,
        zoom: Float,
    ): AppResult<Unit> = unsupported()

    override suspend fun stop(profileToken: String): AppResult<Unit> = unsupported()

    override suspend fun getPresets(profileToken: String): AppResult<List<PtzPreset>> =
        unsupported()

    override suspend fun gotoPreset(
        profileToken: String,
        presetToken: String,
    ): AppResult<Unit> = unsupported()

    private fun unsupported(): AppResult<Nothing> = AppResult.Failure(
        AppError(ErrorCode.PROTOCOL_UNSUPPORTED, "云台控制尚未支持（P1 规划）"),
    )
}
