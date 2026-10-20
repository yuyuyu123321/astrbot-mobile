package com.astrbot.mobile.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 一键部署：把「准备运行环境 → 校验 AstrBot 本体 → 启动服务 → 等待可访问」
 * 串成一个动作，避免用户按顺序点三个按钮。
 *
 * 每一步都可重入：已经完成的步骤会被跳过，因此重复点击是安全的。
 */
object OneTapDeploy {

    /** 整体阶段，供 UI 显示 */
    enum class Stage(val label: String) {
        PREPARING("正在准备运行环境…"),
        VERIFYING("正在校验 AstrBot 本体…"),
        STARTING("正在启动服务…"),
        WAITING("正在等待 WebUI 就绪…"),
        DONE("部署完成")
    }

    /** 启动服务后最长等待时间 */
    private const val READY_TIMEOUT_MS = 180_000L
    private const val POLL_INTERVAL_MS = 1_500L

    data class Outcome(
        val runtimeReady: Boolean,
        val deployed: Boolean,
        val serverReady: Boolean,
        val message: String
    )

    /**
     * 执行一键部署。
     *
     * @param onStage 阶段变化回调（在主线程外的 IO 上触发，UI 侧需自行切回）
     * @param onProgress 准备阶段进度 0f..1f
     * @param onLog 追加一行日志
     */
    suspend fun run(
        context: Context,
        mirror: String,
        onStage: (Stage) -> Unit,
        onProgress: (Float) -> Unit,
        onLog: (String) -> Unit
    ): Result<Outcome> = withContext(Dispatchers.IO) {
        try {
            // 1) 运行环境：已就绪则跳过，否则解压内置包（或回退在线下载）
            onStage(Stage.PREPARING)
            if (!RuntimeEnv.isReady(context)) {
                onLog("运行环境未就绪，开始准备…")
                val r = RuntimeEnv.bootstrap(context, mirror) { stage, frac ->
                    onLog(stage)
                    onProgress(frac)
                }
                r.getOrThrow()
            } else {
                onLog("运行环境已就绪，跳过准备")
            }
            onProgress(1f)

            // 2) AstrBot 本体：内置版随 rootfs 一起就位，这里只做校验
            onStage(Stage.VERIFYING)
            val rootfs = RuntimeEnv.rootfs(context)
            val mainPy = java.io.File(rootfs, "root/AstrBot/main.py")
            if (!mainPy.isFile) {
                // 非内置包只能用在线部署补齐
                onLog("未检测到 AstrBot 本体，开始在线部署…")
                RuntimeEnv.deployAstrBot(context, "ghproxy", mirror).getOrThrow()
            }
            if (!java.io.File(rootfs, "root/AstrBot/main.py").isFile) {
                throw IllegalStateException("AstrBot 本体缺失，无法启动")
            }
            if (!RuntimeEnv.isDeployed(context)) {
                RuntimeEnv.deployMarker(context).writeText("bundled")
            }
            onLog("AstrBot 本体校验通过")

            // 3) 启动服务
            onStage(Stage.STARTING)
            if (ServerHealth.isReady()) {
                onLog("检测到服务已在运行，跳过启动")
            } else {
                if (ServerHealth.portOpen()) {
                    throw IllegalStateException("端口 6185 已被其他程序占用，无法启动")
                }
                RuntimeService.startServer(context)
            }

            // 4) 等待就绪
            onStage(Stage.WAITING)
            val ok = awaitReady(context, onLog)
            if (!ok) {
                return@withContext Result.failure(
                    IllegalStateException("服务启动超时，请查看下方日志")
                )
            }

            onStage(Stage.DONE)
            onLog("=== 一键部署完成，可直接打开客户端 ===")
            Result.success(
                Outcome(
                    runtimeReady = RuntimeEnv.isReady(context),
                    deployed = RuntimeEnv.isDeployed(context),
                    serverReady = true,
                    message = "部署完成，可用 astrbot / 123456 登录（请登录后立即修改密码）"
                )
            )
        } catch (e: Exception) {
            onLog("!! 一键部署失败: ${e.message}")
            Result.failure(e)
        }
    }

    /** 轮询直到 WebUI 可访问或超时 */
    private suspend fun awaitReady(context: Context, onLog: (String) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var lastNote = 0L
        while (System.currentTimeMillis() < deadline) {
            if (ServerHealth.isReady()) return true
            // 进程已经退出就没必要继续等
            if (!RuntimeService.isBusy && !RuntimeService.isServerProcessAlive) {
                // 再给一次机会确认，避免启动瞬间的竞态
                delay(POLL_INTERVAL_MS)
                if (ServerHealth.isReady()) return true
                onLog("!! 服务进程已退出，请查看下方日志")
                return false
            }
            val now = System.currentTimeMillis()
            if (now - lastNote > 15_000) {
                lastNote = now
                onLog("等待 WebUI 就绪…")
            }
            delay(POLL_INTERVAL_MS)
        }
        return false
    }
}