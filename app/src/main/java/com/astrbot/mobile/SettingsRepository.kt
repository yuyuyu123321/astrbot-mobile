package com.astrbot.mobile

import android.content.Context

/**
 * 设置存储：服务器地址、镜像源、AI 助手配置。
 * 纯粹的 SharedPreferences 封装，不含任何业务逻辑。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 服务器 ----------

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    /** 用户是否已经保存过服务器地址 */
    val isConfigured: Boolean
        get() = prefs.contains(KEY_SERVER_URL)

    // ---------- 下载镜像 ----------

    /** GitHub 加速源（AstrBot 源码与适配器下载）：ghproxy / gh-proxy / moeyy / official */
    var adapterSource: String
        get() = prefs.getString(KEY_ADAPTER_SOURCE, "ghproxy") ?: "ghproxy"
        set(value) = prefs.edit().putString(KEY_ADAPTER_SOURCE, value).apply()

    /** Alpine 镜像源：aliyun / tuna / official */
    var alpineMirror: String
        get() = prefs.getString(KEY_ALPINE_MIRROR, "aliyun") ?: "aliyun"
        set(value) = prefs.edit().putString(KEY_ALPINE_MIRROR, value).apply()

    // ---------- AI 助手 ----------

    var aiBase: String
        get() = prefs.getString(KEY_AI_BASE, "https://api.deepseek.com/v1")
            ?: "https://api.deepseek.com/v1"
        set(value) = prefs.edit().putString(KEY_AI_BASE, value).apply()

    var aiKey: String
        get() = prefs.getString(KEY_AI_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AI_KEY, value).apply()

    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, "deepseek-chat") ?: "deepseek-chat"
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value).apply()

    /** AI 接口协议：openai / anthropic */
    var aiProtocol: String
        get() = prefs.getString(KEY_AI_PROTOCOL, "openai") ?: "openai"
        set(value) = prefs.edit().putString(KEY_AI_PROTOCOL, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val PREFS_NAME = "astrbot_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ADAPTER_SOURCE = "adapter_source"
        private const val KEY_AI_BASE = "ai_base"
        private const val KEY_AI_KEY = "ai_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_PROTOCOL = "ai_protocol"
        private const val KEY_ALPINE_MIRROR = "alpine_mirror"

        /** AstrBot WebUI 默认端口 */
        const val DEFAULT_URL = "http://127.0.0.1:6185"

        /** 规范化用户输入：补全协议头、去掉末尾斜杠 */
        fun normalizeUrl(input: String): String {
            var url = input.trim()
            if (url.isEmpty()) return DEFAULT_URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }

        fun isValidUrl(url: String): Boolean {
            val regex = Regex("^https?://[\\w.\\-]+(:\\d{1,5})?(/.*)?$")
            return regex.matches(url)
        }
    }
}