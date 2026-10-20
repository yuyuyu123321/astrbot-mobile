package com.astrbot.mobile.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * QQ 官方机器人（qq_official）配置的读写。
 *
 * 写入目标是 AstrBot 的 `data/cmd_config.json` 中的 `platform` 数组：
 *
 * ```json
 * "platform": [
 *   {
 *     "id": "qqbot",
 *     "type": "qq_official",
 *     "enable": true,
 *     "appid": "102xxxxx",
 *     "secret": "xxxx",
 *     "enable_group_c2c": true,
 *     "enable_guild_direct_message": true,
 *     "use_markdown": true
 *   }
 * ]
 * ```
 *
 * 字段定义见 AstrBot `astrbot/core/config/default.py` 的 CONFIG_METADATA_2。
 */
object QQBotConfig {

    const val ADAPTER_TYPE = "qq_official"
    private const val ENTRY_ID = "astrbot_qqbot"

    data class Settings(
        val enabled: Boolean = false,
        val appId: String = "",
        val secret: String = "",
        val groupC2C: Boolean = true,
        val guildDirectMessage: Boolean = true,
        val useMarkdown: Boolean = true,
    ) {
        val isComplete: Boolean
            get() = appId.isNotBlank() && secret.isNotBlank()
    }

    /** 读取当前配置；配置文件不存在或没有 qq_official 条目时返回默认值。 */
    fun load(context: Context): Settings {
        val file = DashboardPasswordReset.configFile(context)
        if (!file.isFile) return Settings()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            val platform = root.optJSONArray("platform") ?: return Settings()
            val entry = findEntry(platform) ?: return Settings()
            Settings(
                enabled = entry.optBoolean("enable", false),
                appId = entry.optString("appid", ""),
                secret = entry.optString("secret", ""),
                groupC2C = entry.optBoolean("enable_group_c2c", true),
                guildDirectMessage = entry.optBoolean("enable_guild_direct_message", true),
                useMarkdown = entry.optBoolean("use_markdown", true),
            )
        } catch (e: Exception) {
            Settings()
        }
    }

    /**
     * 写入配置。服务运行中时拒绝写入——AstrBot 会在退出时把内存中的配置
     * 覆盖回文件，此时改了也会丢。
     *
     * @throws IllegalStateException 服务正在运行，或配置文件损坏
     */
    fun save(context: Context, settings: Settings) {
        check(!ServerHealth.portOpen()) {
            "AstrBot 正在运行，请先停止服务端再修改 QQ Bot 配置"
        }
        val file = DashboardPasswordReset.configFile(context)
        require(file.isFile) { "找不到 AstrBot 配置文件，请先完成部署并启动一次" }

        val root = JSONObject(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val platform = root.optJSONArray("platform") ?: JSONArray().also {
            root.put("platform", it)
        }

        // 移除旧条目（可能不止一个，历史上手动配置过的情况）
        val kept = JSONArray()
        for (i in 0 until platform.length()) {
            val item = platform.optJSONObject(i) ?: continue
            if (item.optString("type") != ADAPTER_TYPE) kept.put(item)
        }

        val entry = JSONObject().apply {
            put("id", ENTRY_ID)
            put("type", ADAPTER_TYPE)
            put("enable", settings.enabled)
            put("appid", settings.appId.trim())
            put("secret", settings.secret.trim())
            put("enable_group_c2c", settings.groupC2C)
            put("enable_guild_direct_message", settings.guildDirectMessage)
            put("use_markdown", settings.useMarkdown)
        }
        kept.put(entry)

        root.put("platform", kept)
        writeAtomic(file, root)
    }

    /** 关闭 QQ Bot（保留已填写的凭证，只是不启用）。 */
    fun disable(context: Context) {
        val current = load(context)
        if (!current.isComplete) return
        save(context, current.copy(enabled = false))
    }

    private fun findEntry(platform: JSONArray): JSONObject? {
        for (i in 0 until platform.length()) {
            val item = platform.optJSONObject(i) ?: continue
            if (item.optString("type") == ADAPTER_TYPE) return item
        }
        return null
    }

    /**
     * 原子写入，避免写一半断电导致配置文件损坏。
     * 与 DashboardPasswordReset 的做法保持一致（AtomicFile）。
     */
    private fun writeAtomic(file: File, root: JSONObject) {
        require(!Files.isSymbolicLink(file.toPath())) { "配置文件是符号链接，拒绝写入" }

        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(root.toString(4).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}