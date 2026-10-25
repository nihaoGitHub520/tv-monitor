package com.tvmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvmonitor.data.SettingsStore
import com.tvmonitor.util.TvLog

/**
 * 开机自动启动接收器（2026-10-25 用户需求：设置页新增“开机自动启动”开关）。
 *
 * 行为：收到开机广播后读取 [SettingsStore.autoStart]，开启则拉起 [com.tvmonitor.ui.MainActivity]
 * （主界面创建即恢复四路播放，见 QuadPlaybackManager.restoreBindings）。
 *
 * 兼容性：
 * - `ACTION_BOOT_COMPLETED`：标准开机广播（需 RECEIVE_BOOT_COMPLETED 权限）；
 * - `ACTION_LOCKED_BOOT_COMPLETED`（API 24+）：部分设备直接解锁场景；
 * - `QUICKBOOT_POWERON`：部分电视盒子/定制 ROM 的“快速启动”广播。
 *
 * 注意：BOOT_COMPLETED 接收器在 Android 10+ 被临时豁免后台启动 Activity 限制，
 * 可直接 startActivity；用 FLAG_ACTIVITY_NEW_TASK（Receiver 上下文必需）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_LOCKED_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON
        ) {
            return
        }
        val autoStart = SettingsStore(context).snapshot().autoStart
        if (!autoStart) {
            TvLog.i(TAG, "开机广播已接收，但未开启开机自动启动")
            return
        }
        val launch = Intent(context, com.tvmonitor.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        runCatching { context.startActivity(launch) }
            .onFailure { TvLog.w(TAG, "开机启动失败：${it.message}") }
        TvLog.i(TAG, "开机自动启动：已拉起主界面")
    }

    companion object {
        private const val TAG = "TvMonitor:BootReceiver"
        private const val ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"
        private const val ACTION_QUICKBOOT_POWERON = "org.quicksilver.intent.action.QUICKBOOT_POWERON"
    }
}
