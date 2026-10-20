package com.astrbot.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrbot.mobile.SettingsRepository
import com.astrbot.mobile.runtime.OneTapDeploy
import com.astrbot.mobile.runtime.RuntimeEnv
import com.astrbot.mobile.runtime.RuntimeService
import com.astrbot.mobile.runtime.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.net.InetSocketAddress
import java.net.Socket

private val ConsoleBg = Color(0xFF17141F)
private val ConsoleText = Color(0xFFB8AEE8)
private val StatusGreen = Color(0xFF4CAF50)

/** HTTP 200 plus AstrBot page identity; called on Dispatchers.IO. */
private fun probeServer(): Boolean = com.astrbot.mobile.runtime.ServerHealth.isReady()

/**
 * 服务端管理页：内嵌 Linux 运行时状态、一键部署、服务启停、适配器、保活、日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onOpenClient: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // ---------- 状态 ----------
    var runtimeReady by remember { mutableStateOf(RuntimeEnv.isReady(context)) }
    var deployed by remember { mutableStateOf(RuntimeEnv.isDeployed(context)) }
    var serverRunning by remember { mutableStateOf(false) }
    var serviceBusy by remember { mutableStateOf(false) }
    var alpineMirror by remember { mutableStateOf(settings.alpineMirror) }
    var gitMirror by remember { mutableStateOf(settings.adapterSource) }
    var logText by remember { mutableStateOf("") }
    var batteryIgnored by remember { mutableStateOf(false) }
    var shizukuAvailable by remember { mutableStateOf(ShizukuBridge.available()) }
    var shizukuGranted by remember { mutableStateOf(ShizukuBridge.hasPermission()) }
    val logScroll = rememberScrollState()

    // 耗时操作状态
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    /** 一次性提示（部署结果等） */
    var toast by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            toast = null
        }
    }

    // Shizuku 授权结果监听
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            shizukuGranted = ShizukuBridge.hasPermission()
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (_: Throwable) {
        }
        onDispose {
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (_: Throwable) {
            }
        }
    }

    // 通知权限（Android 13+）
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 状态轮询
    LaunchedEffect(Unit) {
        while (true) {
            serviceBusy = RuntimeService.isBusy
            runtimeReady = RuntimeEnv.isReady(context)
            deployed = RuntimeEnv.isDeployed(context)
            serverRunning = withContext(Dispatchers.IO) { probeServer() }
            logText = withContext(Dispatchers.IO) { RuntimeEnv.tailLog(context) }
            batteryIgnored = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
            shizukuAvailable = ShizukuBridge.available()
            shizukuGranted = ShizukuBridge.hasPermission()
            delay(2000)
        }
    }
    LaunchedEffect(logText) { logScroll.scrollTo(logScroll.maxValue) }

    fun runTask(label: String, block: suspend () -> Result<Unit>) {
        if (busyLabel != null) return
        busyLabel = label
        progress = 0f
        scope.launch {
            val r = block()
            busyLabel = null
            r.onFailure {
                RuntimeEnv.appendLog(context, "!! 操作失败: ${it.message}")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("服务端管理", fontWeight = FontWeight.Bold) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ---------- 运行状态 ----------
            SectionCard(title = "运行状态", icon = Icons.Rounded.Memory) {
                StatusRow("运行环境", if (runtimeReady) "就绪" else "未初始化", runtimeReady)
                StatusRow("AstrBot", if (deployed) "已部署" else "未部署", deployed)
                StatusRow(
                    "服务端",
                    when {
                        serverRunning -> "WebUI 可访问（HTTP 已验证）"
                        else -> "未运行"
                    },
                    serverRunning
                )
                if (serverRunning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenClient,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开 AstrBot 客户端") }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 一键部署 ----------
            SectionCard(title = "一键部署", icon = Icons.Rounded.CloudDownload) {
                val needSetup = !runtimeReady || !deployed || !serverRunning
                Text(
                    when {
                        serverRunning -> "服务已在运行。"
                        needSetup -> "点一次即可完成：准备环境 → 校验本体 → 启动服务 → 等待就绪。"
                        else -> "环境已就绪。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        if (busyLabel != null) return@Button
                        busyLabel = OneTapDeploy.Stage.PREPARING.label
                        progress = 0f
                        scope.launch {
                            val r = OneTapDeploy.run(
                                context = context,
                                mirror = alpineMirror,
                                // Compose state 可从任意线程写入，这里直接更新即可
                                onStage = { stage -> busyLabel = stage.label },
                                onProgress = { f -> progress = f },
                                onLog = { line ->
                                    // 追加日志涉及文件写入，放到 IO 上执行
                                    scope.launch(Dispatchers.IO) { RuntimeEnv.appendLog(context, line) }
                                }
                            )
                            busyLabel = null
                            r.onSuccess { out ->
                                toast = out.message
                                runtimeReady = out.runtimeReady
                                deployed = out.deployed
                                serverRunning = out.serverReady
                            }
                            r.onFailure { e ->
                                toast = "部署失败：${e.message}"
                            }
                            logText = withContext(Dispatchers.IO) { RuntimeEnv.tailLog(context) }
                        }
                    },
                    enabled = busyLabel == null && !serviceBusy && !serverRunning,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.RocketLaunch, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        when {
                            serverRunning -> "服务已在运行"
                            busyLabel != null -> "部署中…"
                            !runtimeReady -> "一键部署并启动（约 300MB 解压）"
                            else -> "一键启动 AstrBot"
                        }
                    )
                }

                if (serverRunning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenClient,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("打开 AstrBot WebUI")
                    }
                }

                busyLabel?.let { label ->
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 高级选项（原来的分步按钮） ----------
            SectionCard(title = "高级选项", icon = Icons.Rounded.Tune) {
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (expanded) "收起分步操作" else "展开分步操作（排错用）")
                }

                if (expanded) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Alpine 镜像源", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RuntimeEnv.ALPINE_MIRRORS.forEach { (key, label) ->
                            FilterChip(
                                selected = alpineMirror == key,
                                onClick = {
                                    alpineMirror = key
                                    settings.alpineMirror = key
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            runTask(if (runtimeReady) "重新初始化运行环境…" else "准备运行环境…") {
                                RuntimeEnv.bootstrap(context, alpineMirror) { stage, frac ->
                                    busyLabel = stage
                                    progress = frac
                                }
                            }
                        },
                        enabled = busyLabel == null && !serviceBusy && !serverRunning,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                runtimeReady -> "重新准备运行环境"
                                RuntimeEnv.hasBundledRootfs(context) -> "1. 解压内置运行环境（离线，约 300MB）"
                                else -> "1. 初始化运行环境（约 10MB）"
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("GitHub 加速源", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RuntimeEnv.GIT_MIRRORS.forEach { (key, label) ->
                            FilterChip(
                                selected = gitMirror == key,
                                onClick = {
                                    gitMirror = key
                                    settings.adapterSource = key
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            runTask("正在部署 AstrBot（耗时数分钟，请保持前台）…") {
                                RuntimeEnv.deployAstrBot(context, gitMirror, alpineMirror)
                            }
                        },
                        enabled = busyLabel == null && runtimeReady && !serviceBusy && !serverRunning,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                deployed -> "重新部署 AstrBot"
                                RuntimeEnv.isBundledDeploy(context) -> "2. AstrBot 已内置（无需部署）"
                                else -> "2. 部署 AstrBot 服务端"
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 服务控制 ----------
            SectionCard(title = "服务控制", icon = Icons.Rounded.PowerSettingsNew) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { RuntimeService.startServer(context) },
                        enabled = deployed && !serverRunning && !serviceBusy && busyLabel == null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("启动")
                    }
                    OutlinedButton(
                        onClick = { RuntimeService.stopServer(context) },
                        enabled = serverRunning || serviceBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("停止")
                    }
                }
                if (!shizukuGranted) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "服务端目前由 App 托管；无看门狗、无自动重启。厂商冻结仍可能中断服务。Shizuku 启动暂未开放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- QQ 官方机器人 ----------
            QQBotSectionCard(
                runtimeReady = runtimeReady,
                serverRunning = serverRunning,
                onToast = { toast = it },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 后台保活 ----------
            SectionCard(title = "后台保活", icon = Icons.Rounded.BatterySaver) {
                // 厂商冻结警告
                val maker = Build.MANUFACTURER.lowercase()
                val brand = Build.BRAND.lowercase()
                val romWarning = when {
                    maker.contains("huawei") || brand.contains("huawei") || brand.contains("honor") ->
                        "华为/HarmonyOS 可能冻结前台服务。可在应用启动管理中允许后台活动，但无法保证长期保活。"

                    maker.contains("vivo") || brand.contains("iqoo") ->
                        "vivo/iQOO 可能冻结后台应用；可允许后台高耗电，但仍需实际验证。"

                    else -> null
                }
                romWarning?.let { warning ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Shizuku 状态
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (shizukuGranted) Icons.Rounded.CheckCircle else Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (shizukuGranted) StatusGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        when {
                            shizukuGranted -> "Shizuku 已授权；shell 服务端启动暂未开放"
                            shizukuAvailable -> "Shizuku 未授权；shell 服务端启动暂未开放"
                            else -> "未检测到 Shizuku；当前服务端不依赖它"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (false && shizukuAvailable && !shizukuGranted) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { ShizukuBridge.requestPermission() },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("授权 Shizuku（实验功能）") }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 电池优化白名单
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (batteryIgnored) Icons.Rounded.CheckCircle else Icons.Rounded.BatterySaver,
                        contentDescription = null,
                        tint = if (batteryIgnored) StatusGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        if (batteryIgnored) "已加入电池优化白名单" else "未加入电池优化白名单",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!batteryIgnored) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                )
                            } catch (_: Exception) {
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("加入电池优化白名单") }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- 控制台日志 ----------
            SectionCard(title = "控制台日志", icon = Icons.Rounded.Terminal) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ConsoleBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = logText.ifBlank { "（暂无日志）" },
                        color = ConsoleText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .heightIn(min = 120.dp, max = 320.dp)
                            .verticalScroll(logScroll)
                            .padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        RuntimeEnv.logFile(context).writeText("")
                        logText = ""
                    },
                    shape = RoundedCornerShape(10.dp)
                ) { Text("清空日志") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = if (ok) StatusGreen else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}