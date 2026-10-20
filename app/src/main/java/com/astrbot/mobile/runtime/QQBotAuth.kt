package com.astrbot.mobile.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * QQ 官方机器人凭证校验。
 *
 * 直接请求腾讯的 access_token 接口，判断 AppID/AppSecret 是否可用：
 * ```
 * POST https://bots.qq.com/app/getAppAccessToken
 * body: {"appId": "...", "clientSecret": "..."}
 * ```
 * 成功返回 `{"access_token": "...", "expires_in": 7200}`，
 * 失败返回 `{"code": 100016, "message": "..."}`。
 *
 * 这只是「凭证是否有效」，不代表机器人已配置好消息接收方式；
 * 后者需要在 q.qq.com 后台开启 WebSocket。
 */
object QQBotAuth {

    private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
    private const val TIMEOUT_MS = 15_000

    sealed interface Result {
        data class Ok(val expiresIn: Int) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun verify(appId: String, secret: String): Result = withContext(Dispatchers.IO) {
        if (appId.isBlank() || secret.isBlank()) {
            return@withContext Result.Failed("AppID 和 AppSecret 都必须填写")
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().apply {
                put("appId", appId.trim())
                put("clientSecret", secret.trim())
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) {
                return@withContext Result.Failed("腾讯接口返回空响应（HTTP $code）")
            }

            val json = JSONObject(body)
            val token = json.optString("access_token", "")
            if (token.isNotBlank()) {
                return@withContext Result.Ok(json.optInt("expires_in", 0))
            }
            val msg = json.optString("message", "").ifBlank { body.take(200) }
            val errCode = json.optInt("code", 0)
            Result.Failed(if (errCode != 0) "错误码 $errCode：$msg" else msg)
        } catch (e: Exception) {
            Result.Failed("网络错误：${e.message ?: e::class.java.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}