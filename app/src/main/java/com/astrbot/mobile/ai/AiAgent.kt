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
                    return@withContext reply.text.ifBlank { "（无回复内容）" }
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
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        onDataLine(line.substring(5).trim())
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
            .put("tools", buildOpenAiTools())
            .put("tool_choice", "auto")
            .put("stream", true)

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
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .map { ToolCall(it.first, it.second, it.third.toString()) }
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
            .put("tools", buildAnthropicTools())
            .put("tool_choice", JSONObject().put("type", "auto"))
            .put("stream", true)

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
        return """
你是 AstrBot 安卓客户端的内置 AI 助手，拥有对本 App 的完全控制权，可通过工具执行：
- initialize_runtime：初始化内嵌 Linux 运行环境（Alpine + proot，约 10MB 下载）
- deploy_astrbot：一键部署 AstrBot 服务端（安装 Python、拉源码、装依赖，耗时数分钟）
- start_server / stop_server：启动 / 停止 AstrBot 服务端（WebUI 端口 6185）
- install_adapter：安装 NapCat 或 LLOneBot QQ 适配器
- exec_shell：在内嵌 Linux 环境（proot, Alpine）中执行任意 shell 命令
- read_console_log：查看控制台日志（排查问题首选）
- get_app_status：查询当前各项状态
- open_client：打开 AstrBot WebUI 客户端界面

当前状态快照：运行环境=${if (ready) "就绪" else "未初始化"}，服务端=${if (deployed) "已部署" else "未部署"}${if (running) "，运行中" else ""}

行为准则：
1. 收到任务直接行动，按依赖顺序调用工具（例如先初始化环境再部署再启动）
2. 耗时操作前用一句话告知用户你打算做什么
3. 破坏性操作（删除数据、重置环境）执行前先征得用户同意
4. 工具失败时先调用 read_console_log 排查，再向用户解释原因
5. 用简洁的中文回答
""".trim()
    }

    // ---------------- 工具实现 ----------------

    private suspend fun executeTool(name: String, args: JSONObject): String {
        return when (name) {
            "get_app_status" -> JSONObject()
                .put("runtimeReady", RuntimeEnv.isReady(context))
                .put("astrbotDeployed", RuntimeEnv.isDeployed(context))
                .put("serverProcessAlive", RuntimeService.isServerProcessAlive)
                .put("webUiReady", com.astrbot.mobile.runtime.ServerHealth.isReady())
                .put("napcatInstalled", RuntimeEnv.adapterMarker(context, "NapCat").exists())
                .put("llonebotInstalled", RuntimeEnv.adapterMarker(context, "LLOneBot").exists())
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

            "install_adapter" -> {
                val adapterName = args.optString("name", "NapCat")
                val source = args.optString("source", "ghproxy")
                val (repo, asset, extra) = when (adapterName.lowercase()) {
                    "napcat" -> Triple(
                        "NapNeko/NapCatQQ", "NapCat.Shell.zip",
                        "NapCat.Shell 需配合 Linux QQ 在 proot 中运行，参考 napcat.icu 文档"
                    )

                    else -> Triple(
                        "LLOneBot/LLOneBot", "LLOneBot.zip",
                        "LLOneBot 是 LiteLoaderQQNT 插件，Android 上需要 Root 并修改 QQ 客户端"
                    )
                }
                val display = if (adapterName.lowercase() == "napcat") "NapCat" else "LLOneBot"
                RuntimeEnv.installAdapter(context, display, repo, asset, source, extra) {}
                    .fold({ "$display 安装完成（位于 /root/$display）\n$extra" }, { throw it })
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
                "install_adapter", "安装 QQ 平台适配器",
                objParams(
                    JSONObject()
                        .put("name", enumProp("NapCat", "LLOneBot", desc = "适配器名称"))
                        .put("source", enumProp("ghproxy", "gh-proxy", "moeyy", "official", desc = "GitHub 加速源")),
                    "name"
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