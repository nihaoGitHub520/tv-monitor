package com.tvmonitor

import android.app.Application
import com.tvmonitor.util.TvLog

/**
 * tv-monitor 全局 Application。
 *
 * 职责：
 * 1. 持有进程级单例，供非 UI 组件获取 Context（日志、存储路径等）；
 * 2. 初始化全局日志开关（随 BuildConfig.DEBUG，可在设置内覆写）。
 *
 * 线程模型：onCreate 主线程调用；本类不做任何阻塞 IO。
 */
class TvMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        TvLog.i(TAG, "tv-monitor 启动，version=${BuildConfig.VERSION_NAME}")
    }

    companion object {
        private const val TAG = "TvMonitor:App"

        /** 进程级 Application 单例；经 [TvMonitorApp.onCreate] 赋值，之后只读。 */
        lateinit var instance: TvMonitorApp
            private set
    }
}
