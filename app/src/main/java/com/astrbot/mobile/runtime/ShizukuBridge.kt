package com.astrbot.mobile.runtime

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku availability/permission only. Shell server launch is disabled pending isolation tests. */
object ShizukuBridge {

    @Volatile
    private var binderAlive = false

    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky { binderAlive = true }
            Shizuku.addBinderDeadListener { binderAlive = false }
        } catch (_: Throwable) {
        }
    }

    fun available(): Boolean = try {
        binderAlive && Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 触发 Shizuku 授权弹窗（结果由 UI 层注册的 listener 接收） */
    fun requestPermission() {
        try {
            if (available() && !hasPermission()) Shizuku.requestPermission(1001)
        } catch (_: Throwable) {
        }
    }

}
