package com.astrbot.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** Exact, version-pinned resources only. No token/cookie bridge and no HTTP proxy. */
object DashboardWebResources {
    private val scripts = mapOf(
        "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.22.0/dist/ort.wasm.min.js" to "ort.wasm.min.js",
        "https://cdn.jsdelivr.net/npm/@ricky0123/vad-web@0.0.29/dist/bundle.min.js" to "vad.bundle.min.js"
    )

    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.isForMainFrame) return null
        val name = scripts[request.url.toString()] ?: return null
        return try {
            WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
                context.assets.open("web-vendor/$name"))
        } catch (_: java.io.IOException) {
            // Fail visibly instead of silently waiting for the CDN again.
            WebResourceResponse("text/plain", "UTF-8", 503, "Bundled resource unavailable",
                emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString AstrBotAndroidApp/1.3"
        }
    }
}

/** Shared by the visible client and headless tests; HTTP completion is not DOM readiness. */
open class DashboardWebClient(
    context: Context,
    private val onState: (loading: Boolean, error: String?) -> Unit,
    private val onHistory: (Boolean) -> Unit = {},
    private val timeoutMs: Long = 20000L
) : WebViewClient() {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var done = false
    private var disposed = false

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        DashboardWebResources.intercept(appContext, request)
            ?: super.shouldInterceptRequest(view, request)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (disposed) return
        generation++
        done = false
        handler.removeCallbacksAndMessages(null)
        onState(true, null)
        val current = generation
        handler.postDelayed({
            if (!disposed && current == generation && !done) {
                done = true
                onState(false, "页面在 ${timeoutMs / 1000} 秒内未完成显示。可能是服务器响应、前端脚本或渲染异常；可重新加载，或用系统浏览器对照。")
            }
        }, timeoutMs)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (!disposed && !done) inspectDom(view, generation)
    }

    private fun inspectDom(view: WebView, current: Int) {
        if (disposed || done || generation != current) return
        // Boolean only: never log DOM text, credentials, localStorage or tokens.
        view.evaluateJavascript("""(function(){var a=document.getElementById('app');return !!(a ? a.childElementCount>0 && a.textContent.trim().length>0 : document.body && document.body.innerText.trim().length>0);})()""") { value ->
            if (!disposed && !done && generation == current) {
                if (value == "true") {
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    onState(false, null)
                } else handler.postDelayed({ inspectDom(view, current) }, 750)
            }
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (!disposed) onHistory(view.canGoBack())
    }

    private fun fail(message: String) {
        if (disposed) return
        done = true
        handler.removeCallbacksAndMessages(null)
        onState(false, message)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) fail("无法连接到 AstrBot：${error.description}")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) fail("服务器返回 HTTP ${response.statusCode}")
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        fail("证书验证失败，已停止连接；请检查服务器 HTTPS 证书。")
    }

    fun dispose() {
        disposed = true
        generation++
        handler.removeCallbacksAndMessages(null)
    }
}
