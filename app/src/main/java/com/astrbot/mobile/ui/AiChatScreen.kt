package com.astrbot.mobile.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrbot.mobile.SettingsRepository
import com.astrbot.mobile.ai.AiAgent
import com.astrbot.mobile.ai.fetchAvailableModels
import com.astrbot.mobile.ai.normalizeBaseUrl
import kotlinx.coroutines.launch
private data class Provider(
    val name: String,
    val protocol: String,
    val base: String,
    val modelHint: String
)

private val PROVIDERS = listOf(
    Provider("DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat"),
    Provider("Kimi（Moonshot）", "openai", "https://api.moonshot.cn/v1", "kimi-k2-0905-preview"),
    Provider("Kimi Code（Claude 协议）", "anthropic", "https://api.kimi.com/coding/v1", "kimi-for-coding"),
    Provider("Claude 官方", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-5"),
    Provider("硅基流动", "openai", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
    Provider("OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o-mini"),
    Provider("自定义", "openai", "", "")
)

private data class UiMessage(
    val id: Long,
    val role: String,           // user / assistant / tool
    val text: String,
    val toolId: String? = null,
    val toolStatus: AiAgent.ToolStatus? = null,
    val toolResult: String = ""
)

/**
 * 内置 AI 助手：对话式控制整个 App。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    onOpenClient: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(settings.aiKey) }
    var showConfig by remember { mutableStateOf(settings.aiKey.isBlank()) }

    val agent = remember {
        AiAgent(context).apply { this.onOpenClient = { onOpenClient() } }
    }

    var messages by remember { mutableStateOf(listOf<UiMessage>()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var msgSeq = remember { 0L }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        input = ""
        sending = true
        msgSeq++
        messages = messages + UiMessage(msgSeq, "user", text)
        scope.launch {
            var streamId: Long? = null
            var accumulated = ""
            try {
                val reply = agent.send(
                    text,
                    onTextDelta = { d ->
                        accumulated += d
                        mainHandler.post {
                            val sid = streamId
                            if (sid == null) {
                                msgSeq++
                                streamId = msgSeq
                                messages = messages + UiMessage(msgSeq, "assistant", accumulated)
                            } else {
                                messages = messages.map {
                                    if (it.id == sid) it.copy(text = accumulated) else it
                                }
                            }
                        }
                    },
                    onTool = { ev ->
                        mainHandler.post {
                            val idx = messages.indexOfFirst { it.toolId == ev.id }
                            if (idx >= 0) {
                                messages = messages.toMutableList().apply {
                                    set(idx, get(idx).copy(toolStatus = ev.status, toolResult = ev.result))
                                }
                            } else {
                                msgSeq++
                                messages = messages + UiMessage(
                                    msgSeq, "tool",
                                    "${ev.name}  ${ev.argsSummary}",
                                    toolId = ev.id, toolStatus = ev.status, toolResult = ev.result
                                )
                            }
                        }
                    }
                )
                mainHandler.post {
                    val sid = streamId
                    val finalText = accumulated.ifBlank { reply }
                    if (sid != null) {
                        messages = messages.map { if (it.id == sid) it.copy(text = finalText) else it }
                    } else {
                        msgSeq++
                        messages = messages + UiMessage(msgSeq, "assistant", finalText)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    msgSeq++
                    messages = messages + UiMessage(msgSeq, "assistant", "出错了：${e.message}")
                }
            } finally {
                mainHandler.post { sending = false }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 助手", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        agent.reset()
                        messages = emptyList()
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "清空对话")
                    }
                    IconButton(onClick = { showConfig = !showConfig }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "接口配置")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ---------- 接口配置 ----------
            if (showConfig) {
                var base by remember { mutableStateOf(settings.aiBase) }
                var key by remember { mutableStateOf(settings.aiKey) }
                var model by remember { mutableStateOf(settings.aiModel) }
                var protocol by remember { mutableStateOf(settings.aiProtocol) }
                var probedModels by remember { mutableStateOf<List<String>?>(null) }
                var probing by remember { mutableStateOf(false) }
                var probeError by remember { mutableStateOf<String?>(null) }

                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showConfig = false }
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState())
                                .imePadding()
                        ) {
                            Text(
                                "模型接口（OpenAI 兼容）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // 服务商预设
                            var provider by remember {
                                mutableStateOf(
                                    PROVIDERS.firstOrNull {
                                        it.base == settings.aiBase && it.protocol == settings.aiProtocol
                                    } ?: PROVIDERS.last()
                                )
                            }
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it }
                            ) {
                                OutlinedTextField(
                                    value = provider.name,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("服务商") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    PROVIDERS.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p.name) },
                                            onClick = {
                                                provider = p
                                                expanded = false
                                                if (p.base.isNotEmpty()) base = p.base
                                                protocol = p.protocol
                                                if (p.modelHint.isNotEmpty()) model = p.modelHint
                                                probedModels = null
                                            }
                                        )
                                    }
                                }
                            }

                            // 自定义服务商时可手动切换协议
                            if (provider.name == "自定义") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = protocol == "openai",
                                        onClick = { protocol = "openai" },
                                        label = { Text("OpenAI 兼容") }
                                    )
                                    FilterChip(
                                        selected = protocol == "anthropic",
                                        onClick = { protocol = "anthropic" },
                                        label = { Text("Anthropic") }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = base,
                                onValueChange = {
                                    base = it
                                    probedModels = null
                                },
                                label = { Text("Base URL") },
                                minLines = 1,
                                maxLines = 4,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = key,
                                onValueChange = {
                                    key = it
                                    probedModels = null
                                },
                                label = { Text("API Key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // 模型探针
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        probing = true
                                        probeError = null
                                        try {
                                            probedModels = fetchAvailableModels(base.trim(), key.trim(), protocol)
                                            if (probedModels!!.isEmpty()) {
                                                probeError = "接口未返回任何模型"
                                            }
                                        } catch (e: Exception) {
                                            probeError = "探测失败：${e.message}"
                                        }
                                        probing = false
                                    }
                                },
                                enabled = !probing && base.isNotBlank() && key.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (probing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                } else {
                                    Icon(
                                        Icons.Rounded.Sync,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size(16.dp)
                                    )
                                }
                                Text("探测可用模型")
                            }

                            probeError?.let {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            probedModels?.let { list ->
                                if (list.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "可用模型（${list.size} 个，点击选择）",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 220.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            list.forEach { m ->
                                                Surface(
                                                    color = if (m == model)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else Color.Transparent,
                                                    onClick = { model = m },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(
                                                            horizontal = 12.dp,
                                                            vertical = 10.dp
                                                        )
                                                    ) {
                                                        if (m == model) {
                                                            Icon(
                                                                Icons.Rounded.CheckCircle,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.size(8.dp))
                                                        }
                                                        Text(
                                                            m,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = model,
                                onValueChange = { model = it },
                                label = { Text("模型名称") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { showConfig = false },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("取消") }
                                Button(
                                    onClick = {
                                        settings.aiBase = normalizeBaseUrl(base)
                                        settings.aiKey = key.trim()
                                        settings.aiModel = model.trim()
                                        settings.aiProtocol = protocol
                                        apiKey = key.trim()
                                        showConfig = false
                                    },
                                    enabled = key.isNotBlank() && model.isNotBlank(),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("保存") }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (protocol == "anthropic")
                                    "Anthropic 协议：Kimi Code 填 https://api.kimi.com/coding/v1，Claude 官方填 https://api.anthropic.com/v1"
                                else
                                    "OpenAI 兼容：DeepSeek、Kimi(api.moonshot.cn/v1)、硅基流动、OpenAI、OneAPI、本地网关等",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---------- 消息列表 ----------
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 60.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.SmartToy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("我可以帮你管理整个 App", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "试试：\n「帮我初始化环境并部署 AstrBot」\n「查看控制台日志」\n「安装 NapCat」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    when (msg.role) {
                        "user" -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Text(
                                    msg.text,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }

                        "tool" -> ToolBubble(msg)

                        else -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Text(
                                    msg.text,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 输入区 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(if (apiKey.isBlank()) "请先点右上角配置 API Key" else "下达指令…") },
                    enabled = apiKey.isNotBlank() && !sending,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.size(8.dp))
                FilledIconButton(
                    onClick = { send() },
                    enabled = input.isNotBlank() && !sending && apiKey.isNotBlank()
                ) {
                    if (sending) {
                        Icon(
                            Icons.Rounded.Sync,
                            contentDescription = "发送中",
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolBubble(msg: UiMessage) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (msg.toolStatus) {
                        AiAgent.ToolStatus.RUNNING -> Icons.Rounded.Sync
                        AiAgent.ToolStatus.ERROR -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                    tint = when (msg.toolStatus) {
                        AiAgent.ToolStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                        AiAgent.ToolStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    msg.text,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (msg.toolResult.isNotBlank() && msg.toolStatus != AiAgent.ToolStatus.RUNNING) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    msg.toolResult.take(300),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}