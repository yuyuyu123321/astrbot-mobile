package com.astrbot.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.astrbot.mobile.runtime.ShizukuBridge
import com.astrbot.mobile.ui.AiChatScreen
import com.astrbot.mobile.ui.ServerScreen
import com.astrbot.mobile.ui.SetupScreen
import com.astrbot.mobile.ui.WebScreen
import com.astrbot.mobile.ui.theme.AstrBotTheme

/**
 * 主入口：底部导航四 Tab —— 客户端 / 服务端 / AI 助手 / 设置。
 */
class MainActivity : ComponentActivity() {

    private enum class Tab(val label: String, val icon: ImageVector) {
        Client("客户端", Icons.Rounded.WebAsset),
        Server("服务端", Icons.Rounded.Dns),
        Ai("AI 助手", Icons.Rounded.SmartToy),
        Settings("设置", Icons.Rounded.Settings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuBridge.init()
        // 清理旧版本残留的看门狗任务（组件已删除，否则会周期性触发 ClassNotFound 崩溃）
        try {
            getSystemService(android.app.job.JobScheduler::class.java)?.cancelAll()
        } catch (_: Exception) {
        }

        val settings = SettingsRepository(this)

        setContent {
            AstrBotTheme {
                var tabIndex by remember {
                    mutableIntStateOf(if (settings.isConfigured) 0 else 3)
                }
                var serverUrl by remember { mutableStateOf(settings.serverUrl) }

                fun goClient(local: Boolean) {
                    if (local) {
                        settings.serverUrl = SettingsRepository.DEFAULT_URL
                        serverUrl = SettingsRepository.DEFAULT_URL
                    }
                    tabIndex = Tab.Client.ordinal
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = tabIndex == tab.ordinal,
                                    onClick = { tabIndex = tab.ordinal },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (Tab.entries[tabIndex]) {
                            Tab.Client -> WebScreen(serverUrl = serverUrl)

                            Tab.Server -> ServerScreen(
                                onOpenClient = { goClient(local = true) }
                            )

                            Tab.Ai -> AiChatScreen(
                                onOpenClient = { goClient(local = true) }
                            )

                            Tab.Settings -> SetupScreen(
                                initialUrl = serverUrl,
                                onConnect = { url ->
                                    settings.serverUrl = url
                                    serverUrl = url
                                    tabIndex = Tab.Client.ordinal
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}