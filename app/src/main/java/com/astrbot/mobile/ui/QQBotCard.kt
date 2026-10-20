package com.astrbot.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.astrbot.mobile.runtime.QQBotAuth
import com.astrbot.mobile.runtime.QQBotConfig
import kotlinx.coroutines.launch

private val QqOk = Color(0xFF4CAF50)
private val QqErr = Color(0xFFE53935)

/**
 * QQ 官方机器人配置卡片。
 *
 * 只写 AstrBot 的 `platform` 配置，不涉及任何私有协议：
 * 走的是 https://q.qq.com 的官方 Bot，不需要 QQ 客户端、不需要 proot 额外依赖。
 */
@Composable
fun QQBotSectionCard(
    runtimeReady: Boolean,
    serverRunning: Boolean,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var appId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var groupC2C by remember { mutableStateOf(true) }
    var guildDm by remember { mutableStateOf(true) }
    var useMarkdown by remember { mutableStateOf(true) }

    var loaded by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var verifyOk by remember { mutableStateOf(false) }

    // 首次进入时读取已有配置
    LaunchedEffect(runtimeReady) {
        if (!runtimeReady || loaded) return@LaunchedEffect
        val s = QQBotConfig.load(context)
        enabled = s.enabled
        appId = s.appId
        secret = s.secret
        groupC2C = s.groupC2C
        guildDm = s.guildDirectMessage
        useMarkdown = s.useMarkdown
        loaded = true
    }

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    "QQ 官方机器人",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "接入腾讯官方开放平台（q.qq.com），不模拟客户端、无封号风险，" +
                    "不需要额外安装环境。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))

            // 启用开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用", fontWeight = FontWeight.Medium)
                    Text(
                        "关闭时保留已填写的凭证",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it.trim() },
                label = { Text("AppID") },
                placeholder = { Text("在 q.qq.com 的机器人资料页获取") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it.trim() },
                label = { Text("AppSecret") },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    Text(
                        if (showSecret) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickableText { showSecret = !showSecret },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "接收范围",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            ToggleRow("消息列表私聊（C2C）", "需先在 QQ 里扫码把机器人加为好友", groupC2C) { groupC2C = it }
            ToggleRow("频道私聊", "接收 QQ 频道的私信消息", guildDm) { guildDm = it }
            ToggleRow("主动消息用 Markdown", "部分客户端不支持，遇到乱码可关闭", useMarkdown) { useMarkdown = it }

            // 校验结果
            verifyMessage?.let { msg ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (verifyOk) QqOk.copy(alpha = 0.12f) else QqErr.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            if (verifyOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (verifyOk) QqOk else QqErr,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (verifyOk) QqOk else QqErr,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            verifying = true
                            verifyMessage = null
                            when (val r = QQBotAuth.verify(appId, secret)) {
                                is QQBotAuth.Result.Ok -> {
                                    verifyOk = true
                                    verifyMessage = "凭证有效（有效期 ${r.expiresIn} 秒）。" +
                                        "还需在 q.qq.com 后台开启 WebSocket 才能收消息。"
                                }
                                is QQBotAuth.Result.Failed -> {
                                    verifyOk = false
                                    verifyMessage = r.message
                                }
                            }
                            verifying = false
                        }
                    },
                    enabled = !verifying && appId.isNotBlank() && secret.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("验证凭证")
                }

                Button(
                    onClick = {
                        try {
                            QQBotConfig.save(
                                context,
                                QQBotConfig.Settings(
                                    enabled = enabled,
                                    appId = appId,
                                    secret = secret,
                                    groupC2C = groupC2C,
                                    guildDirectMessage = guildDm,
                                    useMarkdown = useMarkdown,
                                )
                            )
                            onToast(
                                if (enabled) "已保存，重启服务端后生效"
                                else "已保存（未启用）"
                            )
                        } catch (e: Exception) {
                            onToast(e.message ?: "保存失败")
                        }
                    },
                    enabled = runtimeReady && appId.isNotBlank() && secret.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("保存") }
            }

            if (serverRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "服务端运行中，配置改动会在重启后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!runtimeReady) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "请先在「一键部署」里完成运行环境初始化。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 让文字看起来像按钮的点击样式。 */
private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.clickable { onClick() }