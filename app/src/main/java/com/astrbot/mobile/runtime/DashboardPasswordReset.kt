package com.astrbot.mobile.runtime

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 本机内嵌 AstrBot 的 dashboard 密码管理。
 *
 * 两件事：
 * 1. [reset]：用户在客户端主动重置密码（离线，要求服务已停止）。
 * 2. [ensureInitialPassword]：首次部署时写入统一初始密码，并标记「必须修改」。
 *
 * 注意 AstrBot 的校验分两层（见 astrbot/core/utils/auth_password.py）：
 * - 写入/修改密码时走 `validate_dashboard_password`，要求 ≥8 位且含大小写与数字；
 * - 登录时走 `verify_dashboard_password`，只比对哈希，不检查复杂度。
 * 因此初始密码可以是弱口令，但用户自行修改时必须通过强校验。
 */
object DashboardPasswordReset {
    private const val ITERATIONS = 600_000 // Matches AstrBot's auth_password.py

    /** 统一初始密码：登录可用，但会强制要求修改（改密时受复杂度策略约束）。 */
    const val INITIAL_PASSWORD = "123456"

    /** 客户端在登录后是否仍需强制改密 */
    private const val PREFS = "astrbot_password"
    private const val KEY_MUST_CHANGE = "must_change_password"

    fun isChangeRequired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUST_CHANGE, false)

    fun setChangeRequired(context: Context, required: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUST_CHANGE, required).apply()
    }

    fun configFile(context: Context) =
        File(RuntimeEnv.rootfs(context), "root/AstrBot/data/cmd_config.json")

    /** 校验配置文件路径合法（限本 App 运行时目录，且不是符号链接） */
    fun requireValidConfig(context: Context): File {
        val config = configFile(context)
        require(config.isFile) { "找不到本机 AstrBot 配置文件" }
        require(
            !java.nio.file.Files.isSymbolicLink(config.toPath()) &&
                config.canonicalFile.parentFile ==
                File(RuntimeEnv.rootfs(context), "root/AstrBot/data").canonicalFile
        ) { "配置文件路径异常" }
        return config
    }

    /**
     * 首次部署时写入统一初始密码，并把 `password_change_required` 置为 true。
     * 只在配置文件已存在且尚未设置密码时调用，避免覆盖用户已改过的密码。
     *
     * @return true 表示写入了初始密码
     */
    fun ensureInitialPassword(context: Context): Boolean {
        val config = requireValidConfig(context)
        val root = JSONObject(config.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val dashboard = root.optJSONObject("dashboard") ?: return false

        val existing = dashboard.optString("pbkdf2_password", "")
        if (existing.isNotBlank()) return false // 已有密码，不覆盖

        applyPassword(dashboard, INITIAL_PASSWORD)
        dashboard.put("password_change_required", true)
        writeConfig(config, root)
        setChangeRequired(context, true)
        return true
    }

    /** 用户主动重置密码 */
    fun reset(context: Context, password: String): String {
        require(password.length >= 8 && password.any(Char::isUpperCase) &&
            password.any(Char::isLowerCase) && password.any(Char::isDigit)) {
            "密码至少 8 位，须包含大小写字母和数字"
        }
        require(!ServerHealth.portOpen()) { "请先在服务端页面停止服务，再重置密码" }
        require(RuntimeEnv.isDeployed(context)) { "本机尚未部署 AstrBot" }
        val config = requireValidConfig(context)

        val root = JSONObject(config.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val dashboard = root.getJSONObject("dashboard")
        applyPassword(dashboard, password)
        dashboard.put("password_change_required", false)
        // Recheck just before writing: AstrBot must not have started while hashing.
        require(!ServerHealth.portOpen()) { "服务端已启动，重置已取消；请先停止服务端" }
        writeConfig(config, root)
        setChangeRequired(context, false)
        return "重置成功。请在服务端页面重新启动 AstrBot，并用新密码登录。"
    }

    /** 写入 PBKDF2 + MD5 双哈希，与 AstrBot 的存储格式保持一致 */
    private fun applyPassword(dashboard: JSONObject, password: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val key = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val digest = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).encoded
        } finally {
            key.clearPassword()
        }
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        dashboard.put(
            "pbkdf2_password",
            "pbkdf2_sha256\u0024$ITERATIONS\u0024${salt.hex()}\u0024${digest.hex()}"
        )
        dashboard.put(
            "password",
            MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8)).hex()
        ) // AstrBot's legacy config fallback
        dashboard.put("password_storage_upgraded", true)
    }

    private fun writeConfig(config: File, root: JSONObject) {
        val atomic = AtomicFile(config)
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