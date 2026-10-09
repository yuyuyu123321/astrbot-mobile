package com.astrbot.mobile.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext

/**
 * AstrBot WebUI 主页面：全功能 WebView 壳。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(serverUrl: String) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    fun openBrowser() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl))) }
        catch (_: Exception) { loadError = "未找到可打开此地址的浏览器" }
    }

    // 文件上传（WebUI 内 <input type="file">）支持
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    // 返回键：优先 WebView 后退
    BackHandler(enabled = canGoBack && loadError == null) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("AstrBot", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = {
                            loadError = null
                            webView?.loadUrl(serverUrl)
                        }) {
                            Icon(Icons.Rounded.Home, contentDescription = "首页")
                        }
                        IconButton(onClick = {
                            loadError = null
                            loading = true
                            progress = 0
                            canGoBack = false
                            reloadTrigger++
                        }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                        }
                        TextButton(onClick = { openBrowser() }) { Text("浏览器") }
                    }
                )
                if (loading && loadError == null) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
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
            key(serverUrl, reloadTrigger) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        DashboardWebResources.configure(this)
                        val client = object : DashboardWebClient(context,
                            onState = { pending, error -> loading = pending; loadError = error },
                            onHistory = { canGoBack = it }) {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val url = request.url.toString()
                                return if (url.startsWith("http://") || url.startsWith("https://")) {
                                    false
                                } else {
                                    // 外部协议（intent://、tel: 等）交给系统
                                    try {
                                        if (request.isForMainFrame && request.hasGesture()) view.context.startActivity(
                                            Intent(Intent.ACTION_VIEW, request.url)
                                        )
                                    } catch (_: Exception) {
                                    }
                                    true
                                }
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                dispose()
                                loading = false
                                canGoBack = false
                                loadError = "WebView 渲染进程已结束，请重新加载客户端。"
                                (view.parent as? ViewGroup)?.removeView(view)
                                view.tag = null
                                view.destroy()
                                if (webView === view) webView = null
                                return true
                            }
                        }
                        tag = client
                        webViewClient = client

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onShowFileChooser(
                                webView: WebView,
                                callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = callback
                                return try {
                                    fileChooserLauncher.launch(params.createIntent())
                                    true
                                } catch (_: Exception) {
                                    filePathCallback = null
                                    false
                                }
                            }
                        }

                        // 下载交给系统浏览器/下载器处理
                        setDownloadListener { url, _, _, _, _ ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {
                            }
                        }

                        loadUrl(serverUrl)
                        webView = this
                    }
                },
                onRelease = { view ->
                    // A newer view may already be assigned during keyed replacement.
                    // Release this view regardless of the global reference, exactly once.
                    (view.tag as? DashboardWebClient)?.let {
                        it.dispose()
                        view.tag = null
                        view.stopLoading()
                        view.destroy()
                    }
                    if (webView === view) webView = null
                },
                modifier = Modifier.fillMaxSize()
            )
            }

            // 加载失败的错误遮罩
            loadError?.let { error ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "客户端暂未就绪",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请确认 AstrBot 服务已启动，且 $serverUrl 可访问",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            loadError = null
                            loading = true
                            progress = 0
                            canGoBack = false
                            reloadTrigger += 1
                        },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(18.dp)
                        )
                        Text("重新加载")
                    }
                    TextButton(onClick = { openBrowser() }) { Text("用系统浏览器打开") }
                }
            }
        }
    }
}