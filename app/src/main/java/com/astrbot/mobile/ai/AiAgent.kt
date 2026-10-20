package com.astrbot.mobile.ai

import android.content.Context
import com.astrbot.mobile.SettingsRepository
import com.astrbot.mobile.runtime.RuntimeEnv
import com.astrbot.mobile.runtime.RuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** 规范化 Base URL：补全协议头、去末尾斜杠 */
fun normalizeBaseUrl(url: String): String {
    var u = url.trim()
    if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
        u = "https://$u"
    }
    return u.trimEnd('/')
}

/** 探针：拉取可用模型列表（OpenAI: GET /models；Anthropic: GET /models + anthropic-version） */
suspend fun fetchAvailableModels(base: String, key: String, protocol: String): List<String> =
    withContext(Dispatchers.IO) {
        val conn = (URL(normalizeBaseUrl(base) + "/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 20000
            if (protocol == "anthropic") {
                setRequestProperty("x-api-key", key)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                setRequestProperty("Authorization", "Bearer $key")
            }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 404) throw Exception("该接口不支持模型列表（/models 不存在），请手动填写模型名称")
            if (code !in 200..299) throw Exception("HTTP $code：${text.take(200)}")
            val data = JSONObject(text).getJSONArray("data")
            (0 until data.length())
                .map { data.getJSONObject(it).getString("id") }
                .sorted()
        } finally {
            conn.disconnect()
        }
    }

/**
 * 内置 AI Agent：支持 OpenAI / Anthropic 双协议 + 工具调用，
 * 赋予 AI 对本 App 的完全控制权（部署、启停、适配器、shell、日志、导航）。
 */
class AiAgent(private val context: Context) {

    enum class ToolStatus { RUNNING, SUCCESS, ERROR }

    data class ToolEvent(
        val id: String,
        val name: String,
        val argsSummary: String,
        val status: ToolStatus,
        val result: String
    )

    private data class ToolCall(val id: String, val name: String, val argsRaw: String)

    private sealed class Entry {
        data class User(val text: String) : Entry()
        data class AssistantText(val text: String) : Entry()
        data class AssistantCalls(val calls: List<ToolCall>) : Entry()
        data class ToolResult(val id: String, val content: String) : Entry()
    }

    private data class ApiReply(val text: String, val calls: List<ToolCall>)

    /** AI 可通过 open_client 工具触发跳转客户端 */
    var onOpenClient: (() -> Unit)? = null

    private val history = mutableListOf<Entry>()

    fun reset() = history.clear()

    /**
     * 发送用户消息，返回 AI 最终文本回复。
     * 文本通过 onTextDelta 流式输出，工具调用通过 onTool 回调（开始/结束各一次）。
     */
    suspend fun send(userText: String, onTextDelta: (String) -> Unit, onTool: (ToolEvent) -> Unit): String =
        withContext(Dispatchers.IO) {
            val settings = SettingsRepository(context)
            if (settings.aiKey.isBlank()) throw Exception("请先配置 API Key")

            history.add(Entry.User(userText))
            repeat(8) {
                val reply = callApi(settings, onTextDelta)
                if (reply.calls.isEmpty()) {
                    history.add(Entry.AssistantText(reply.text))
                    return@withContext reply.text.ifBlank { "（模型没有返回内容，可能是接口不支持该模型或参数有误）" }
                }
                // 纯聊天模式不该出现工具调用；若出现了，直接返回文本并丢弃调用，
                // 否则会在「模型想调工具 → 工具不存在」之间反复循环。
                if (!settings.aiToolsEnabled) {
                    history.add(Entry.AssistantText(reply.text))
                    return@withContext reply.text.ifBlank { "（模型返回了无法执行的调用，请检查模型是否支持对话）" }
                }
                history.add(Entry.AssistantCalls(reply.calls))

                for (call in reply.calls) {
                    val summary = summarizeArgs(call.name, call.argsRaw)
                    withContext(Dispatchers.Main) {
                        onTool(ToolEvent(call.id, call.name, summary, ToolStatus.RUNNING, ""))
                    }
                    val (ok, result) = try {
                        true to executeTool(call.name, JSONObject(call.argsRaw.ifBlank { "{}" }))
                    } catch (e: Exception) {
                        false to "执行失败：${e.message}"
                    }
                    withContext(Dispatchers.Main) {
                        onTool(
                            ToolEvent(
                                call.id, call.name, summary,
                                if (ok) ToolStatus.SUCCESS else ToolStatus.ERROR, result
                            )
                        )
                    }
                    history.add(Entry.ToolResult(call.id, result.take(4000)))
                }
            }
            return@withContext "（工具调用轮次已达上限，请重新描述需求）"
        }

    // ---------------- API 调用 ----------------

    private suspend fun callApi(settings: SettingsRepository, onTextDelta: (String) -> Unit): ApiReply =
        if (settings.aiProtocol == "anthropic") callAnthropic(settings, onTextDelta)
        else callOpenAi(settings, onTextDelta)

    /** 流式 POST：逐行读取 SSE，data: 行交给 onDataLine */
    private fun httpPostStream(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        onDataLine: (String) -> Unit
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 180000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val hint = when (code) {
                    400 -> "请求被拒绝：模型名称可能不正确，或该接口与所选协议不匹配"
                    401 -> "API Key 无效或未授权"
                    404 -> "接口地址不存在，请检查 Base URL 与协议选择"
                    429 -> "触发限流/上游负载饱和，请稍后重试"
                    else -> null
                }
                throw Exception("API 返回 HTTP $code${hint?.let { "（$it）" } ?: ""}：${text.take(300)}")
            }
            conn.inputStream.bufferedReader().use { reader ->
                var done = false
                while (!done) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") {
                        done = true
                    } else if (payload.isNotEmpty()) {
                        onDataLine(payload)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, body: JSONObject, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 180000
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val hint = when (code) {
                    400 -> "请求被拒绝：模型名称可能不正确，或该接口与所选协议不匹配"
                    401 -> "API Key 无效或未授权"
                    404 -> "接口地址不存在，请检查 Base URL 与协议选择"
                    429 -> "触发限流，请稍后重试"
                    else -> null
                }
                throw Exception("API 返回 HTTP $code${hint?.let { "（$it）" } ?: ""}：${text.take(300)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // -------- OpenAI 协议（SSE 流式） --------

    private fun callOpenAi(settings: SettingsRepository, onTextDelta: (String) -> Unit): ApiReply {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        history.forEach { e ->
            when (e) {
                is Entry.User -> messages.put(JSONObject().put("role", "user").put("content", e.text))
                is Entry.AssistantText -> messages.put(
                    JSONObject().put("role", "assistant").put("content", e.text)
                )

                is Entry.AssistantCalls -> {
                    val calls = JSONArray()
                    e.calls.forEach { c ->
                        calls.put(
                            JSONObject()
                                .put("id", c.id)
                                .put("type", "function")
                                .put(
                                    "function", JSONObject()
                                        .put("name", c.name)
                                        .put("arguments", c.argsRaw.ifBlank { "{}" })
                                )
                        )
                    }
                    messages.put(JSONObject().put("role", "assistant").put("tool_calls", calls))
                }

                is Entry.ToolResult -> messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", e.id)
                        .put("content", e.content)
                )
            }
        }

        val body = JSONObject()
            .put("model", settings.aiModel)
            .put("messages", messages)
            .put("stream", true)

        // 只有开启工具且确实需要时才带 tools：
        // 部分模型/中转接口不支持 function calling，带上 tools 会直接 400 或返回空回复。
        if (settings.aiToolsEnabled) {
            body.put("tools", buildOpenAiTools())
            body.put("tool_choice", "auto")
        }

        val textSb = StringBuilder()
        // index -> (id, name, argsBuilder)
        val callParts = sortedMapOf<Int, Triple<String, String, StringBuilder>>()

        httpPostStream(
            normalizeBaseUrl(settings.aiBase) + "/chat/completions",
            body,
            mapOf("Authorization" to "Bearer ${settings.aiKey}")
        ) { line ->
            if (line == "[DONE]") return@httpPostStream
            try {
                val chunk = JSONObject(line)
                val choices = chunk.optJSONArray("choices") ?: return@httpPostStream
                if (choices.length() == 0) return@httpPostStream
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@httpPostStream

                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    textSb.append(content)
                    onTextDelta(content)
                }

                delta.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.getJSONObject(i)
                        val idx = tc.optInt("index", 0)
                        var (id, name, args) = callParts.getOrPut(idx) { Triple("", "", StringBuilder()) }
                        if (tc.has("id")) id = tc.getString("id")
                        tc.optJSONObject("function")?.let { fn ->
                            if (fn.has("name")) name = fn.getString("name")
                            args.append(fn.optString("arguments", ""))
                        }
                        callParts[idx] = Triple(id, name, args)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val calls = callParts.values
            .filter { it.second.isNotEmpty() }
            .mapIndexed { idx, it ->
                // 某些中转接口不返回 tool_call 的 id，缺失时补一个占位，
                // 否则这条调用会被丢弃，模型永远等不到结果。
                val id = it.first.ifBlank { "call_${System.currentTimeMillis()}_$idx" }
                ToolCall(id, it.second, it.third.toString())
            }
        return ApiReply(textSb.toString(), calls)
    }

    // -------- Anthropic 协议（SSE 流式） --------

    private fun callAnthropic(settings: SettingsRepository, onTextDelta: (String) -> Unit): ApiReply {
        val messages = JSONArray()
        var i = 0
        while (i < history.size) {
            when (val e = history[i]) {
                is Entry.User -> messages.put(JSONObject().put("role", "user").put("content", e.text))
                is Entry.AssistantText -> messages.put(
                    JSONObject().put("role", "assistant").put("content", e.text)
                )

                is Entry.AssistantCalls -> {
                    val content = JSONArray()
                    e.calls.forEach { c ->
                        content.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", c.id)
                                .put("name", c.name)
                                .put("input", JSONObject(c.argsRaw.ifBlank { "{}" }))
                        )
                    }
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                }

                is Entry.ToolResult -> {
                    // Anthropic 要求 tool_result 放在 user 消息中，连续的合并为一条
                    val content = JSONArray()
                    var j = i
                    while (j < history.size && history[j] is Entry.ToolResult) {
                        val tr = history[j] as Entry.ToolResult
                        content.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", tr.id)
                                .put("content", tr.content)
                        )
                        j++
                    }
                    messages.put(JSONObject().put("role", "user").put("content", content))
                    i = j
                    continue
                }
            }
            i++
        }

        val body = JSONObject()
            .put("model", settings.aiModel)
            .put("max_tokens", 4096)
            .put("system", systemPrompt())
            .put("messages", messages)
            .put("stream", true)

        if (settings.aiToolsEnabled) {
            body.put("tools", buildAnthropicTools())
            body.put("tool_choice", JSONObject().put("type", "auto"))
        }

        val textSb = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var curId = ""
        var curName = ""
        var curArgs = StringBuilder()
        var inTool = false

        httpPostStream(
            normalizeBaseUrl(settings.aiBase) + "/messages",
            body,
            mapOf(
                "x-api-key" to settings.aiKey,
                "anthropic-version" to "2023-06-01"
            )
        ) { line ->
            try {
                val ev = JSONObject(line)
                when (ev.optString("type")) {
                    "content_block_start" -> {
                        val block = ev.optJSONObject("content_block")
                        if (block?.optString("type") == "tool_use") {
                            inTool = true
                            curId = block.getString("id")
                            curName = block.getString("name")
                            curArgs = StringBuilder()
                        }
                    }

                    "content_block_delta" -> {
                        val d = ev.optJSONObject("delta") ?: return@httpPostStream
                        when (d.optString("type")) {
                            "text_delta" -> {
                                val t = d.optString("text")
                                if (t.isNotEmpty()) {
                                    textSb.append(t)
                                    onTextDelta(t)
                                }
                            }

                            "input_json_delta" -> curArgs.append(d.optString("partial_json"))
                        }
                    }

                    "content_block_stop" -> {
                        if (inTool) {
                            calls.add(ToolCall(curId, curName, curArgs.toString()))
                            inTool = false
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ApiReply(textSb.toString(), calls)
    }

    // ---------------- 系统提示 ----------------

    private fun systemPrompt(): String {
        val ready = RuntimeEnv.isReady(context)
        val deployed = RuntimeEnv.isDeployed(context)
        val running = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 6185), 800) }
            true
        } catch (_: Exception) {
            false
        }
        val toolsEnabled = SettingsRepository(context).aiToolsEnabled

        val statusLine = "当前状态：运行环境=${if (ready) "已就绪" else "未初始化"}，" +
            "AstrBot=${if (deployed) "已部署" else "未部署"}" +
            if (running) "，服务正在运行" else ""

        return if (!toolsEnabled) {
            // 纯聊天模式：没有工具，侧重答疑和说明，避免 AI 承诺做不到的操作
            """
你是 AstrBot 安卓客户端里的助手，负责解答用户关于这个 App 和 AstrBot 的问题。

$statusLine

能力边界（重要）：
- 当前运行在「纯聊天」模式，你无法直接操作 App，也不能部署、启停服务或执行命令。
- 如果用户想让你动手做这些事，请告诉他在设置里打开「允许工具调用」，或直接使用界面按钮。

可以做的事情：
- 介绍 AstrBot 是什么、能做什么
- 解释界面上的功能和按钮含义
- 指导用户去 https://q.qq.com 创建 QQ 官方机器人
- 回答常见的部署、端口、密码问题
- 排查用户描述的错误现象

回答要求：
- 用中文，语气自然，像在跟人聊天，不要用生硬的条目罗列一切
- 不知道的就说不知道，不要编造功能
- 回答简洁，除非用户要求详细说明
""".trim()
        } else {
            """
你是 AstrBot 安卓客户端的内置 AI 助手，拥有对本 App 的控制能力，可通过工具执行：

- initialize_runtime：初始化内嵌 Linux 运行环境（Alpine + proot）
- deploy_astrbot：部署 AstrBot 服务端（安装 Python、拉源码、装依赖，耗时数分钟）
- start_server / stop_server：启动 / 停止 AstrBot 服务端（WebUI 端口 6185）
- configure_qq_bot：配置 QQ 官方机器人（腾讯开放平台 q.qq.com，需 AppID + AppSecret）
- exec_shell：在内嵌 Linux 环境中执行 shell 命令
- read_console_log：查看控制台日志（排查问题首选）
- get_app_status：查询当前各项状态
- open_client：打开 AstrBot WebUI 客户端界面

$statusLine

行为准则：
1. 用户提问时先回答，不要为了用工具而用工具；能直接答的直接答
2. 需要操作时按依赖顺序调用（先初始化环境，再部署，再启动）
3. 耗时操作前用一句话说明你打算做什么
4. 破坏性操作（删除数据、重置环境）必须先征得用户同意
5. 工具失败先 read_console_log 排查，再解释原因
6. 用中文回答，语气自然，不要机械罗列
""".trim()
        }
    }

    // ---------------- 工具实现 ----------------

    private suspend fun executeTool(name: String, args: JSONObject): String {
        return when (name) {
            "get_app_status" -> JSONObject()
                .put("runtimeReady", RuntimeEnv.isReady(context))
                .put("astrbotDeployed", RuntimeEnv.isDeployed(context))
                .put("serverProcessAlive", RuntimeService.isServerProcessAlive)
                .put("webUiReady", com.astrbot.mobile.runtime.ServerHealth.isReady())
                .put("qqBotConfigured", com.astrbot.mobile.runtime.QQBotConfig.load(context).isComplete)
                .toString()

            "initialize_runtime" -> {
                val mirror = args.optString("mirror", "aliyun")
                RuntimeEnv.bootstrap(context, mirror) { _, _ -> }
                    .fold({ "运行环境初始化完成（Alpine + proot）" }, { throw it })
            }

            "deploy_astrbot" -> {
                val gh = args.optString("git_mirror", "ghproxy")
                val pip = args.optString("pip_mirror", "tuna")
                RuntimeEnv.deployAstrBot(context, gh, pip)
                    .fold({ "AstrBot 部署完成，可以调用 start_server 启动" }, { throw it })
            }

            "start_server" -> {
                if (!RuntimeEnv.isDeployed(context)) throw Exception("AstrBot 尚未部署")
                RuntimeService.startServer(context)
                "服务端启动指令已发出，WebUI 将在 http://127.0.0.1:6185 提供访问"
            }

            "stop_server" -> {
                RuntimeService.stopServer(context)
                "停止指令已发出；请再次查询状态确认，不代表已停止"
            }

            "configure_qq_bot" -> {
                val appId = args.optString("appid", "")
                val secret = args.optString("secret", "")
                if (appId.isBlank() || secret.isBlank()) {
                    throw Exception("需要同时提供 appid 和 secret（在 q.qq.com 的机器人资料页获取）")
                }
                val enabled = args.optBoolean("enable", true)
                val groupC2C = args.optBoolean("group_c2c", true)
                val guildDm = args.optBoolean("guild_dm", true)
                val md = args.optBoolean("use_markdown", true)

                val verify = com.astrbot.mobile.runtime.QQBotAuth.verify(appId, secret)
                val verifyNote = when (verify) {
                    is com.astrbot.mobile.runtime.QQBotAuth.Result.Ok ->
                        "凭证校验通过（有效期 ${verify.expiresIn} 秒）"
                    is com.astrbot.mobile.runtime.QQBotAuth.Result.Failed ->
                        "凭证校验未通过：${verify.message}"
                }

                com.astrbot.mobile.runtime.QQBotConfig.save(
                    context,
                    com.astrbot.mobile.runtime.QQBotConfig.Settings(
                        enabled = enabled,
                        appId = appId,
                        secret = secret,
                        groupC2C = groupC2C,
                        guildDirectMessage = guildDm,
                        useMarkdown = md,
                    )
                )
                "$verifyNote\nQQ 官方机器人配置已写入（${if (enabled) "已启用" else "未启用"}），重启服务端后生效。\n" +
                    "注意：还需在 q.qq.com 后台把消息接收方式设为 WebSocket。"
            }

            "exec_shell" -> {
                if (!RuntimeEnv.isReady(context)) throw Exception("运行环境未初始化")
                val command = args.getString("command")
                RuntimeEnv.appendLog(context, "\n\$ $command")
                val (code, out) = RuntimeEnv.execInRootfs(context, command)
                "exit=$code\n${out.takeLast(3000)}"
            }

            "read_console_log" -> {
                val lines = args.optInt("lines", 60)
                RuntimeEnv.tailLog(context, lines).ifBlank { "（日志为空）" }
            }

            "open_client" -> {
                withContext(Dispatchers.Main) { onOpenClient?.invoke() }
                "已切换到客户端界面"
            }

            else -> "未知工具：$name"
        }
    }

    private fun summarizeArgs(name: String, argsRaw: String): String {
        return try {
            val obj = JSONObject(argsRaw)
            when (name) {
                "exec_shell" -> obj.optString("command").take(80)
                else -> obj.keys().asSequence()
                    .joinToString("  ") { "$it=${obj.opt(it)}" }.take(80)
            }
        } catch (_: Exception) {
            argsRaw.take(80)
        }
    }

    // ---------------- 工具 Schema ----------------

    /** 工具定义：name / description / parameters JSONSchema */
    private fun toolDefs(): List<Triple<String, String, JSONObject>> {
        fun enumProp(vararg values: String, desc: String = ""): JSONObject =
            JSONObject().put("type", "string")
                .put("enum", JSONArray(values.toList()))
                .put("description", desc)

        fun objParams(props: JSONObject, vararg required: String): JSONObject =
            JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required.toList()))

        fun emptyParams() = JSONObject().put("type", "object").put("properties", JSONObject())

        return listOf(
            Triple("get_app_status", "查询 App 当前状态（运行环境/部署/服务/适配器）", emptyParams()),
            Triple(
                "initialize_runtime", "初始化内嵌 Linux 运行环境（下载 Alpine + 内置 proot，约 10MB）",
                objParams(
                    JSONObject().put("mirror", enumProp("aliyun", "tuna", "official", desc = "Alpine 镜像源"))
                )
            ),
            Triple(
                "deploy_astrbot", "一键部署 AstrBot 服务端（安装 Python、拉取源码、安装依赖）",
                objParams(
                    JSONObject()
                        .put("git_mirror", enumProp("ghproxy", "gh-proxy", "moeyy", "official", desc = "GitHub 加速源"))
                        .put("pip_mirror", enumProp("tuna", "aliyun", "official", desc = "PyPI 镜像源"))
                )
            ),
            Triple("start_server", "启动 AstrBot 服务端（前台服务保活，WebUI 端口 6185）", emptyParams()),
            Triple("stop_server", "停止 AstrBot 服务端", emptyParams()),
            Triple(
                "configure_qq_bot", "配置 QQ 官方机器人（腾讯开放平台 q.qq.com）",
                objParams(
                    JSONObject()
                        .put("appid", JSONObject().put("type", "string").put("description", "机器人 AppID"))
                        .put("secret", JSONObject().put("type", "string").put("description", "机器人 AppSecret"))
                        .put("enable", JSONObject().put("type", "boolean").put("description", "是否启用，默认 true"))
                        .put("group_c2c", JSONObject().put("type", "boolean").put("description", "接收消息列表私聊，默认 true"))
                        .put("guild_dm", JSONObject().put("type", "boolean").put("description", "接收频道私聊，默认 true"))
                        .put("use_markdown", JSONObject().put("type", "boolean").put("description", "主动消息用 Markdown，默认 true")),
                    "appid", "secret"
                )
            ),
            Triple(
                "exec_shell", "在内嵌 Linux 环境（Alpine/proot）中执行 shell 命令",
                objParams(
                    JSONObject().put(
                        "command",
                        JSONObject().put("type", "string").put("description", "要执行的 shell 命令")
                    ),
                    "command"
                )
            ),
            Triple(
                "read_console_log", "读取控制台日志",
                objParams(
                    JSONObject().put(
                        "lines",
                        JSONObject().put("type", "integer").put("description", "读取行数，默认 60")
                    )
                )
            ),
            Triple("open_client", "打开 AstrBot WebUI 客户端界面（通常在服务端运行后调用）", emptyParams())
        )
    }

    private fun buildOpenAiTools(): JSONArray {
        val arr = JSONArray()
        toolDefs().forEach { (name, desc, params) ->
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", name).put("description", desc).put("parameters", params)
                )
            )
        }
        return arr
    }

    private fun buildAnthropicTools(): JSONArray {
        val arr = JSONArray()
        toolDefs().forEach { (name, desc, params) ->
            arr.put(
                JSONObject().put("name", name).put("description", desc).put("input_schema", params)
            )
        }
        return arr
    }
}