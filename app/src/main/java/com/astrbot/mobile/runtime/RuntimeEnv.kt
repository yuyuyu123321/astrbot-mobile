package com.astrbot.mobile.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内嵌 Linux 运行时：在 App 私有目录中引导 Alpine Linux + proot，
 * 无需 Termux、无需 Root、无需任何权限，服务端完全自包含。
 */
object RuntimeEnv {

    /** Alpine 镜像源 */
    val ALPINE_MIRRORS = linkedMapOf(
        "aliyun" to "阿里云",
        "tuna" to "清华 TUNA",
        "official" to "官方源"
    )

    /** GitHub 加速源（用于 AstrBot 源码与适配器下载） */
    val GIT_MIRRORS = linkedMapOf(
        "ghproxy" to "ghproxy",
        "gh-proxy" to "gh-proxy",
        "moeyy" to "moeyy",
        "official" to "GitHub 官方"
    )

    private val ALPINE_BASE = mapOf(
        "aliyun" to "https://mirrors.aliyun.com/alpine",
        "tuna" to "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        "official" to "https://dl-cdn.alpinelinux.org/alpine"
    )

    private val GIT_BASE = mapOf(
        "ghproxy" to "https://ghproxy.net/https://github.com",
        "gh-proxy" to "https://gh-proxy.com/https://github.com",
        "moeyy" to "https://github.moeyy.xyz/https://github.com",
        "official" to "https://github.com"
    )

    private val PIP_BASE = mapOf(
        "aliyun" to "https://mirrors.aliyun.com/pypi/simple/",
        "tuna" to "https://pypi.tuna.tsinghua.edu.cn/simple",
        "official" to "https://pypi.org/simple"
    )

    // ---------------- 路径与状态 ----------------

    fun runtimeDir(context: Context) = File(context.filesDir, "runtime")
    fun rootfs(context: Context) = File(runtimeDir(context), "rootfs")
    fun logFile(context: Context) = File(runtimeDir(context), "runtime.log")
    fun deployMarker(context: Context) = File(runtimeDir(context), "astrbot_installed")
    fun adapterMarker(context: Context, name: String) =
        File(runtimeDir(context), "${name.lowercase()}_installed")

    /** 运行环境（proot）是否就绪 */
    fun isReady(context: Context) =
        prootBin(context).exists() && File(rootfs(context), "lib/ld-musl-aarch64.so.1").exists()

    /**
     * proot 静态二进制位置。
     * Android 10+ 禁止从 App 数据目录 exec 文件（SELinux W^X），
     * 因此 proot 以 libproot.so 名义打包进 jniLibs，从 native lib 目录执行。
     */
    fun prootBin(context: Context) =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    /** AstrBot 是否已部署 */
    fun isDeployed(context: Context) = deployMarker(context).exists()

    fun appendLog(context: Context, text: String) {
        try {
            runtimeDir(context).mkdirs()
            logFile(context).appendText(text + "\n")
        } catch (_: Exception) {
        }
    }

    fun tailLog(context: Context, lines: Int = 120): String {
        val f = logFile(context)
        if (!f.exists()) return ""
        return f.readLines().takeLast(lines).joinToString("\n")
    }

    // ---------------- 网络 ----------------

    /** 浏览器 UA：部分镜像站（如阿里云）会 403 拒绝无 UA 的请求 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 AstrBot/1.3"

    /** 解析失败时的兜底版本（随镜像同步更新） */
    private const val FALLBACK_ROOTFS_VERSION = "3.24.2"
    private const val FALLBACK_PROOT_APK = "proot-static-5.4.0-r2.apk"

    /**
     * 内置 rootfs 归档在 assets 中的位置。
     * 全量内嵌版（bundled）把完整 AstrBot 本体打包进 APK，
     * 首次启动只需解压，不再联网下载 rootfs / clone 源码 / pip 装依赖。
     *
     * 注意：扩展名必须避开 .gz/.tar/.zip —— AAPT2 会把它们当作归档自动解压并改名，
     * 导致 assets 里文件名变化、体积翻倍且 noCompress 失效。用 .bin 可原样打包。
     */
    private const val BUNDLED_ROOTFS_ASSET = "rootfs/rootfs-template.bin"

    /** 内置 AstrBot 本体在 rootfs 内的路径 */
    private const val ASTROBOT_MAIN = "root/AstrBot/main.py"

    /** 内置归档是否随包提供 */
    fun hasBundledRootfs(context: Context): Boolean = try {
        context.assets.open(BUNDLED_ROOTFS_ASSET).use { it.read() >= 0 }
    } catch (_: Exception) {
        false
    }

    /** rootfs 内是否已带完整 AstrBot 本体（全量内嵌版） */
    fun isBundledDeploy(context: Context): Boolean =
        File(rootfs(context), ASTROBOT_MAIN).isFile

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private fun httpGet(url: String): String {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code >= 400) throw Exception("HTTP $code: $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code >= 400) throw Exception("HTTP $code: $url")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        onProgress(1f)
    }

    /** 从 APKINDEX 索引解析指定包的最新文件名（失败时用兜底版本）。
     *  注意：Alpine 仓库文件名不带架构后缀，格式为 pkg-version.apk */
    private fun resolveApkFileName(base: String, repo: String, pkg: String): String {
        val idx = File.createTempFile("apkindex", ".tar.gz")
        try {
            download("$base/latest-stable/$repo/aarch64/APKINDEX.tar.gz", idx) {}
            val text = TarGz.extractEntryText(idx, "APKINDEX") ?: return FALLBACK_PROOT_APK
            val regex = Regex("(?m)^P:${Regex.escape(pkg)}\nV:([^\n]+)")
            val m = regex.find(text) ?: return FALLBACK_PROOT_APK
            return "$pkg-${m.groupValues[1]}.apk"
        } catch (_: Exception) {
            return FALLBACK_PROOT_APK
        } finally {
            idx.delete()
        }
    }

    /** 解析最新 minirootfs 版本号（失败时用兜底版本） */
    private fun resolveRootfsVersion(base: String): String {
        return try {
            val html = httpGet("$base/latest-stable/releases/aarch64/")
            val versions = Regex("alpine-minirootfs-([0-9.]+)-aarch64\\.tar\\.gz")
                .findAll(html).map { it.groupValues[1] }.distinct().toList()
            if (versions.isEmpty()) return FALLBACK_ROOTFS_VERSION
            versions.maxByOrNull { v ->
                v.split('.').fold(0L) { acc, p -> acc * 1000 + (p.toLongOrNull() ?: 0) }
            } ?: FALLBACK_ROOTFS_VERSION
        } catch (_: Exception) {
            FALLBACK_ROOTFS_VERSION
        }
    }

    // ---------------- 引导运行环境 ----------------

    suspend fun bootstrap(
        context: Context,
        mirror: String,
        onProgress: (stage: String, frac: Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val base = ALPINE_BASE[mirror] ?: ALPINE_BASE.getValue("aliyun")
            runtimeDir(context).mkdirs()
            logFile(context).writeText("")
            appendLog(context, "===== 初始化运行环境 =====")

            if (!prootBin(context).exists()) {
                throw Exception("当前设备架构不受支持（仅支持 arm64 设备）")
            }

            val rootfs = rootfs(context)

            // 优先使用随 APK 内置的完整 rootfs（全量内嵌版）：
            // 离线可用、装完即用，且 AstrBot 本体已在内，无需 clone / pip。
            if (hasBundledRootfs(context)) {
                appendLog(context, "检测到内置 rootfs，走离线解压路径")
                onProgress("解压内置运行环境…", 0.05f)
                rootfs.deleteRecursively()
                rootfs.mkdirs()
                TarGz.extractAsset(context, BUNDLED_ROOTFS_ASSET, rootfs) { f ->
                    onProgress("解压内置运行环境（${(f * 100).toInt()}%）", 0.05f + f * 0.85f)
                }
                finalizeRootfs(context, rootfs, base, onProgress)
                if (File(rootfs, ASTROBOT_MAIN).isFile) {
                    deployMarker(context).writeText("bundled")
                    appendLog(context, "内置 AstrBot 本体已就位，跳过联网部署")
                }
                appendLog(context, "===== 运行环境初始化完成（内置） =====")
                return@withContext Result.success(Unit)
            }

            // 兜底：在线下载 Alpine minirootfs（轻量版行为，保持不变）
            onProgress("解析版本信息…", 0.02f)
            val rootfsVer = resolveRootfsVersion(base)
            appendLog(context, "rootfs=$rootfsVer, proot=bundled(proot-static)")

            val rootfsTar = File(runtimeDir(context), "rootfs.tar.gz")

            onProgress("下载 Alpine 根文件系统…", 0.05f)
            download(
                "$base/latest-stable/releases/aarch64/alpine-minirootfs-$rootfsVer-aarch64.tar.gz",
                rootfsTar
            ) { f -> onProgress("下载根文件系统（${(f * 100).toInt()}%）", 0.05f + f * 0.6f) }

            onProgress("解压根文件系统…", 0.7f)
            rootfs.deleteRecursively()
            TarGz.extract(rootfsTar, rootfs)
            rootfsTar.delete()

            finalizeRootfs(context, rootfs, base, onProgress)
            Result.success(Unit)
        } catch (e: Exception) {
            appendLog(context, "!! 初始化失败: ${e.message}")
            Result.failure(e)
        }
    }

    /** 解压/下载完成后的统一收尾：DNS、软件源、自检 */
    private fun finalizeRootfs(
        context: Context,
        rootfs: File,
        base: String,
        onProgress: (stage: String, frac: Float) -> Unit
    ) {
        // DNS 与软件源（proot 内生效）
        File(rootfs, "etc/resolv.conf")
            .writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\n")
        File(rootfs, "etc/apk/repositories")
            .apply { parentFile?.mkdirs() }
            .writeText("$base/latest-stable/main\n$base/latest-stable/community\n")

        listOf("tmp", "var/tmp", "dev", "proc", "sys").forEach {
            File(rootfs, it).mkdirs()
        }

        onProgress("验证运行环境…", 0.92f)
        val (_, testOut) = execInRootfs(context, "echo RUNTIME_OK && python3 --version 2>/dev/null; true")
        if (!testOut.contains("RUNTIME_OK")) {
            throw Exception("运行环境自检失败：$testOut")
        }

        onProgress("完成", 1f)
    }

    // ---------------- 进程执行 ----------------

    private fun runHostProcess(context: Context, cmd: List<String>): String {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"] = runtimeDir(context).absolutePath
        val p = pb.start()
        val sb = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { line ->
            sb.appendLine(line)
            appendLog(context, line)
        }
        p.waitFor()
        return sb.toString()
    }

    /**
     * 在 proot 根文件系统中执行 shell 命令。
     * proot 为静态链接二进制，直接运行即可。
     * @return (exitCode, 输出)
     */
    fun execInRootfs(context: Context, command: String): Pair<Int, String> {
        val rootfs = rootfs(context)
        val tmp = File(rootfs, "tmp").apply { mkdirs() }
        val pb = ProcessBuilder(
            prootBin(context).absolutePath,
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${tmp.absolutePath}:/tmp",
            "-w", "/root",
            "/usr/bin/env",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "TZ=Asia/Shanghai",
            "/bin/sh", "-c", command
        ).redirectErrorStream(true)
        pb.environment()["TMPDIR"] = tmp.absolutePath
        pb.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
        val p = pb.start()
        val sb = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { line ->
            sb.appendLine(line)
            appendLog(context, line)
        }
        p.waitFor()
        return p.exitValue() to sb.toString()
    }

    // ---------------- 部署 AstrBot ----------------

    suspend fun deployAstrBot(
        context: Context,
        gitMirror: String,
        pipMirror: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isReady(context)) throw Exception("运行环境未初始化")

            // 全量内嵌版：AstrBot 本体已随 rootfs 一起解压，无需 clone / pip。
            if (isBundledDeploy(context)) {
                appendLog(context, "检测到内置 AstrBot 本体，跳过联网部署")
                deployMarker(context).writeText("bundled")
                return@withContext Result.success(Unit)
            }

            val gh = GIT_BASE[gitMirror] ?: GIT_BASE.getValue("ghproxy")
            val pip = PIP_BASE[pipMirror] ?: PIP_BASE.getValue("tuna")
            appendLog(context, "\n===== 部署 AstrBot（git: $gitMirror / pip: $pipMirror）=====")

            val script = """
set -x
# 清理历史 --link2symlink 参数遗留的影子文件
find /usr /lib -name '.l2s.*' -delete 2>/dev/null
# 强化 DNS（多服务器轮询，防止单点抖动后全面失灵）
printf 'options timeout:3 attempts:2\nnameserver 223.5.5.5\nnameserver 223.6.6.6\nnameserver 119.29.29.29\nnameserver 1.1.1.1\n' > /etc/resolv.conf
echo '===== [1/3] 安装系统依赖 ====='
# 注：SELinux 禁止 App 在数据目录创建硬链接，binutils/gcc 包无法安装；
# psutil 等需编译的包直接用 Alpine 官方预编译版本（pip 检测到已装会跳过）
# tzdata 可选：rootfs 使用 UTC 运行，避免缺少 zoneinfo 时 tzlocal 阻止服务启动
try=0
while [ ${'$'}try -lt 3 ]; do
    apk add --no-cache python3 py3-pip py3-psutil git bash && break
    try=$((${'$'}try+1))
    echo "apk 安装失败，第 ${'$'}try 次重试…"
    sleep 2
done
python3 --version || exit 1
git --version || exit 1
pip3 --version || python3 -m ensurepip 2>/dev/null || apk add --no-cache py3-pip || true
echo '===== [2/3] 下载 AstrBot 源码 ====='
cd /root
export GIT_TERMINAL_PROMPT=0
if [ -d AstrBot/.git ]; then
    git -C AstrBot pull || true
elif [ -d AstrBot ]; then
    echo "源码目录已存在，跳过下载"
else
    ok=0
    for m in "$gh" "https://gh-proxy.com/https://github.com" "https://github.moeyy.xyz/https://github.com" "https://github.com"; do
        echo "尝试 git clone: ${'$'}m"
        if git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=25 clone --depth 1 "${'$'}m/AstrBotDevs/AstrBot.git"; then
            ok=1; break
        fi
        echo "尝试压缩包下载: ${'$'}m"
        if wget -T 30 -t 2 -O /tmp/astrbot.tar.gz "${'$'}m/AstrBotDevs/AstrBot/archive/refs/heads/master.tar.gz"; then
            mkdir -p AstrBot
            if tar xzf /tmp/astrbot.tar.gz -C AstrBot --strip-components=1; then ok=1; fi
            rm -f /tmp/astrbot.tar.gz
            [ "${'$'}ok" = "1" ] && break
        fi
    done
    [ "${'$'}ok" = "1" ] || exit 1
fi
echo '===== [3/3] 安装 Python 依赖 ====='
cd /root/AstrBot
# 校准 pip 到一致状态：Alpine 自带 pip 若被部分升级，新旧文件混装会
# 损坏 build_env（get_runnable_pip 导入失败），强制重装一个完整版本修复
pip3 install --break-system-packages --force-reinstall --no-cache-dir pip -i $pip || true
pip3 install --break-system-packages -r requirements.txt -i $pip || exit 1
echo '===== [3.5/3] 检查 WebUI 静态资源 ====='
# AstrBot 首次启动会自行下载 dashboard dist，但依赖 soulter.top/GitHub，
# 在受限网络下经常失败导致 WebUI 空白；部署阶段就用镜像链预装
cd /root/AstrBot
if [ ! -f data/dist/assets/version ]; then
    VER=$(python3 -c "from astrbot.core.config.default import VERSION; v=VERSION; print(v if v.startswith('v') else 'v'+v)" 2>/dev/null)
    echo "下载 WebUI 静态资源 (${'$'}VER)…"
    dok=0
    for u in "https://astrbot-registry.soulter.top/download/astrbot-dashboard/${'$'}VER/dist.zip" "$gh/AstrBotDevs/AstrBot/releases/download/${'$'}VER/AstrBot-${'$'}VER-dashboard.zip" "https://gh-proxy.com/https://github.com/AstrBotDevs/AstrBot/releases/download/${'$'}VER/AstrBot-${'$'}VER-dashboard.zip" "https://github.moeyy.xyz/https://github.com/AstrBotDevs/AstrBot/releases/download/${'$'}VER/AstrBot-${'$'}VER-dashboard.zip"; do
        echo "尝试: ${'$'}u"
        if wget -T 45 -t 2 -O /tmp/dashboard.zip "${'$'}u"; then dok=1; break; fi
    done
    if [ "${'$'}dok" = "1" ]; then
        python3 -c "import zipfile; zipfile.ZipFile('/tmp/dashboard.zip').extractall('data')" && echo WEBUI_DIST_OK || echo '!! WebUI 资源解压失败'
    else
        echo '!! WebUI 静态资源下载失败（不影响部署，可在启动后重试）'
    fi
    rm -f /tmp/dashboard.zip
fi
echo DEPLOY_OK
""".trimIndent()
            File(rootfs(context), "root/deploy_astrbot.sh").writeText(script)
            val (_, out) = execInRootfs(context, "sh /root/deploy_astrbot.sh")
            if (!out.contains("DEPLOY_OK")) throw Exception("部署未完成，详情见控制台日志")
            deployMarker(context).writeText("ok")
            appendLog(context, "===== AstrBot 部署完成 =====")
            Result.success(Unit)
        } catch (e: Exception) {
            appendLog(context, "!! 部署失败: ${e.message}")
            Result.failure(e)
        }
    }

    // ---------------- 安装适配器 ----------------

    suspend fun installAdapter(
        context: Context,
        name: String,
        repo: String,
        asset: String,
        gitMirror: String,
        extra: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isReady(context)) throw Exception("运行环境未初始化")
            val gh = GIT_BASE[gitMirror] ?: GIT_BASE.getValue("ghproxy")
            val url = "$gh/$repo/releases/latest/download/$asset"
            appendLog(context, "\n===== 安装 $name =====\n$url")

            val dir = File(rootfs(context), "root/$name").apply { mkdirs() }
            download(url, File(dir, "pkg.zip"), onProgress)

            val (_, out) = execInRootfs(
                context,
                "cd /root/$name && unzip -o pkg.zip && rm -f pkg.zip && echo ADAPTER_OK"
            )
            if (!out.contains("ADAPTER_OK")) throw Exception("解压失败")
            adapterMarker(context, name).writeText("ok")
            appendLog(context, extra)
            appendLog(context, "===== $name 安装完成 =====")
            Result.success(Unit)
        } catch (e: Exception) {
            appendLog(context, "!! $name 安装失败: ${e.message}")
            Result.failure(e)
        }
    }

    /** 清理可能残留的服务端进程（同 uid，可直接杀） */
    fun killStrayServer(context: Context) {
        try {
            File("/proc").listFiles()?.forEach { proc ->
                val pid = proc.name.toIntOrNull() ?: return@forEach
                try {
                    val cmdline = File(proc, "cmdline").readText()
                    // python 进程的 cmdline 只有 "python3 main.py"，不含工作目录，
                    // 需结合 /proc/pid/cwd 判断是否为本 App 的 AstrBot 进程
                    if (cmdline.contains("main.py") && cmdline.contains("python")) {
                        val cwd = File(proc, "cwd").canonicalPath
                        val expected = File(rootfs(context), "root/AstrBot").canonicalPath
                        val uid = android.system.Os.stat(proc.absolutePath).st_uid
                        // proot can keep the host cwd at the app data directory. Verify its
                        // tracer's exact rootfs and guest working directory instead.
                        val tracer = File(proc, "status").readLines()
                            .firstOrNull { it.startsWith("TracerPid:") }
                            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                        val args = if (tracer > 0) File("/proc/$tracer/cmdline")
                            .readText().split('\u0000') else emptyList()
                        val rootIndex = args.indexOf("-r")
                        val workIndex = args.indexOf("-w")
                        val ownedTracer = rootIndex >= 0 && workIndex >= 0 &&
                            args.getOrNull(workIndex + 1) == "/root/AstrBot" &&
                            args.getOrNull(rootIndex + 1)?.let {
                                File(it).canonicalPath == rootfs(context).canonicalPath
                            } == true && args.firstOrNull()?.endsWith("/libproot.so") == true &&
                            android.system.Os.stat("/proc/$tracer").st_uid == android.os.Process.myUid()
                        if ((cwd == expected || ownedTracer) && uid == android.os.Process.myUid()) {
                            android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
                        }
                    }
                } catch (_: Exception) {
                    // Other UIDs and disappearing processes are not ours to clean.
                }
            }
        } catch (e: Exception) {
            appendLog(context, "进程枚举失败: ${e.message}")
        }
    }
}
