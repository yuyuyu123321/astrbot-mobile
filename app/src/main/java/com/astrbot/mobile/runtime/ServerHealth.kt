package com.astrbot.mobile.runtime

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Bounded network checks; callers must run off the UI thread. */
object ServerHealth {
    fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 6185), 800) }
        true
    } catch (_: Exception) { false }

    fun isReady(): Boolean {
        val conn = URL("http://127.0.0.1:6185/").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.instanceFollowRedirects = false
            conn.responseCode == 200 && conn.inputStream.bufferedReader().use {
                val buffer = CharArray(8192)
                val count = it.read(buffer)
                count > 0 && String(buffer, 0, count).contains("astrbot", ignoreCase = true)
            }
        } catch (_: Exception) { false } finally { conn.disconnect() }
    }
}
