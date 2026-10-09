package com.astrbot.mobile.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.astrbot.mobile.R
import java.io.File
import kotlin.concurrent.thread

/** Explicit start/stop only: no watchdog and no automatic restart. */
class RuntimeService : Service() {

    companion object {
        const val ACTION_START = "com.astrbot.mobile.action.START_SERVER"
        const val ACTION_STOP = "com.astrbot.mobile.action.STOP_SERVER"
        private const val CHANNEL_ID = "astrbot_runtime_v2"
        private const val NOTIF_ID = 1001

        @Volatile
        private var serverProcess: Process? = null

        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null

        /** 用户是否请求停止（区分异常退出与主动停止） */
        @Volatile
        private var stopRequested = false

        // State is shared with UI because the service now runs in the main app process.
        @Volatile var isBusy = false
            private set

        val isServerProcessAlive: Boolean
            get() = serverProcess?.isAlive == true

        fun startServer(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeService::class.java).setAction(ACTION_START)
            )
        }

        fun stopServer(context: Context) {
            context.startService(
                Intent(context, RuntimeService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val guard = Any()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var worker: Thread? = null
    private var latestStartId = 0
    private var diagnosticRun = false
    private var diagnosticLevel = 1
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(android.app.job.JobScheduler::class.java)?.cancelAll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                createChannel()
                val notif = buildNotification("AstrBot 服务端", "启动检查中；尚未确认 WebUI 就绪")
                if (Build.VERSION.SDK_INT >= 34)
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else startForeground(NOTIF_ID, notif)
                if (worker == null) {
                    diagnosticRun = intent.getBooleanExtra("diagnostic", false)
                    diagnosticLevel = intent.getIntExtra("diagnosticLevel", 1).coerceIn(1, 9)
                    launchWorker(false)
                }
                else if (stopRequested) RuntimeEnv.appendLog(this, "正在停止，请等待结束后再启动")
            }
            ACTION_STOP -> {
                // The debug shell instance has a different SELinux domain. Its own
                // controller handles the exact PID+starttime stop request.
                requestDebugShellStop()
                synchronized(guard) {
                    stopRequested = true
                    serverProcess?.destroy()
                }
                if (worker == null) launchWorker(true)
            }
            else -> if (worker == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun requestDebugShellStop() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val dir = RuntimeEnv.runtimeDir(this)
        val pidFile = File(dir, "debug-shell.pid")
        try {
            val identity = pidFile.readText().trim()
            if (!Regex("[0-9]+ [0-9]+").matches(identity)) return
            File(dir, "debug-shell-stop.request").writeText("$identity\n")
            RuntimeEnv.appendLog(this, "已请求调试 shell 实例停止；等待 HTTP 状态确认")
        } catch (e: Exception) {
            RuntimeEnv.appendLog(this, "调试 shell 实例停止请求失败: ${e.message}")
        }
    }

    private fun launchWorker(stopOnly: Boolean) {
        stopRequested = stopOnly
        isBusy = true
        worker = thread(start = false, name = "astrbot-server") {
            try {
                if (stopOnly) {
                    RuntimeEnv.killStrayServer(this)
                    RuntimeEnv.appendLog(this, "已向本应用残留服务端发送停止信号；请以 HTTP 状态确认")
                } else runServer()
            } catch (e: Exception) {
                RuntimeEnv.appendLog(this, "!! 服务端操作失败: ${e.message}（不会自动重启）")
            } finally {
                synchronized(guard) {
                    serverProcess?.destroy()
                    serverProcess = null
                }
                main.post {
                    worker = null
                    isBusy = false
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!destroyed) stopSelfResult(latestStartId)
                }
            }
        }.also { it.start() }
    }

    private fun runServer() {
        val dir = RuntimeEnv.runtimeDir(this).apply { mkdirs() }
        java.io.RandomAccessFile(File(dir, "server.lock"), "rw").use { file ->
            val lock = file.channel.tryLock()
                ?: throw IllegalStateException("已有启动操作持有服务端锁")
            try {
                if (stopRequested) return
                if (ServerHealth.portOpen()) {
                    RuntimeEnv.appendLog(this, if (ServerHealth.isReady())
                        "检测到已有 AstrBot HTTP 服务；跳过启动，不宣称已接管该进程"
                    else "!! 6185 端口已占用，未确认是 AstrBot；取消启动，不终止占用者")
                    return
                }
                val rootfs = RuntimeEnv.rootfs(this)
                check(File(rootfs, "root/AstrBot/main.py").isFile) { "AstrBot 尚未部署" }
                val tmp = File(rootfs, "tmp").apply { mkdirs() }
                installBundledTimezoneData(rootfs)
                val cmd = listOf(
                    RuntimeEnv.prootBin(this).absolutePath, "-v", if (diagnosticRun) diagnosticLevel.toString() else "0", "-0", "-r", rootfs.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${tmp.absolutePath}:/tmp",
                    "-w", "/root/AstrBot", "/usr/bin/env", "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TERM=xterm-256color", "TZ=Asia/Shanghai", "PYTHONUNBUFFERED=1", "PYTHONFAULTHANDLER=1",
                    "/bin/sh", "-c", "exec python3 -u main.py"
                )
                val rawLog = if (diagnosticRun && diagnosticLevel > 1)
                    File(dir, "syscall-trace.log").apply { writeText("") }
                else RuntimeEnv.logFile(this)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                RuntimeEnv.appendLog(this, "\n[$stamp] ===== 启动 AstrBot（诊断版） =====")
                // Write stdout/stderr directly to an append-only file. A Java pipe-reader
                // failure must not close the pipe and kill the child with SIGPIPE.
                val p = synchronized(guard) {
                    if (stopRequested) return
                    ProcessBuilder(cmd).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(rawLog)).apply {
                        environment()["TMPDIR"] = tmp.absolutePath
                        environment()["PROOT_TMP_DIR"] = tmp.absolutePath
                        if (diagnosticRun && diagnosticLevel > 1) environment()["PROOT_NO_SECCOMP"] = "1"
                    }.start().also { serverProcess = it }
                }
                main.post { if (!destroyed && !stopRequested) acquireWakeLock() }
                RuntimeEnv.appendLog(this, "===== App 身份启动 AstrBot；异常退出不自动重启 =====")
                var ready = false
                try {
                    while (p.isAlive && !stopRequested) {
                        val nowReady = ServerHealth.isReady()
                        if (nowReady != ready) {
                            ready = nowReady
                            main.post {
                                if (!destroyed && !stopRequested)
                                    getSystemService(NotificationManager::class.java).notify(
                                        NOTIF_ID, buildNotification("AstrBot 服务端", if (nowReady)
                                            "WebUI HTTP 检查通过：127.0.0.1:6185"
                                        else "进程存在，但 WebUI 暂未响应"))
                            }
                        }
                        Thread.sleep(1500)
                    }
                    // Record the original result BEFORE cleanup can send any signal.
                    if (!p.isAlive) {
                        val code = p.exitValue()
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        RuntimeEnv.appendLog(this,
                            "===== 服务端退出 exit=$code elapsedMs=$elapsed stopRequested=$stopRequested =====")
                    }
                } finally {
                    RuntimeEnv.killStrayServer(this)
                    if (p.isAlive) {
                        p.destroy()
                        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                    }
                }
                RuntimeEnv.appendLog(this, if (stopRequested) "===== 停止操作结束 ====="
                    else "===== 服务端进程已结束，不自动重启；原因见上方原始日志 =====")
            } finally { lock.release() }
        }
    }

    override fun onDestroy() {
        destroyed = true
        synchronized(guard) {
            stopRequested = true
            serverProcess?.destroy()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun installBundledTimezoneData(rootfs: File) {
        try {
            val zoneDir = File(rootfs, "usr/share/zoneinfo/Asia").apply { mkdirs() }
            assets.open("zoneinfo/UTC").use { input ->
                File(rootfs, "usr/share/zoneinfo/UTC").outputStream().use { input.copyTo(it) }
            }
            assets.open("zoneinfo/Asia/Shanghai").use { input ->
                File(zoneDir, "Shanghai").outputStream().use { input.copyTo(it) }
            }
            File(rootfs, "etc/localtime").delete()
            File(rootfs, "etc/localtime").parentFile?.mkdirs()
            File(rootfs, "etc/localtime")
                .writeBytes(File(rootfs, "usr/share/zoneinfo/Asia/Shanghai").readBytes())
            RuntimeEnv.appendLog(this, "时区数据库已从 APK 内置资源恢复")
        } catch (e: Exception) {
            RuntimeEnv.appendLog(this, "!! 内置时区资源解包失败: ${e.message}")
            throw e
        }
    }

    /** 持有部分唤醒锁：息屏后 CPU 不休眠，保证服务端持续响应网络请求 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "astrbot:server"
            ).apply { acquire() }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            // IMPORTANCE_MIN：静默通知，只在通知栏角落显示，不打扰
            NotificationChannel(CHANNEL_ID, "服务端运行状态", NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }
}