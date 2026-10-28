package cn.edu.sicnu.scnujw.ui.dev

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens

private const val TAG = "ScnuJwWV"
private const val JW_HOST = "202.115.194.60"
private const val DEFAULT_URL = "http://202.115.194.60/"

/**
 * 教务域 URL 补 sid：仅当 host 为 202.115.194.60 且当前没有有效 sid 参数、
 * 且应用持有会话 sid 时追加；其余 URL 原样返回。
 */
private fun withSid(raw: String): String {
    val url = raw.trim()
    if (url.isEmpty()) return url
    val uri = Uri.parse(url)
    if (!JW_HOST.equals(uri.host, ignoreCase = true)) return url
    // 只要已带 sid 参数（即使为空）就不再追加，避免重复 append 造成导航循环
    if (uri.getQueryParameter("sid") != null) return url
    val sid = SessionManager.sid?.takeIf { it.isNotBlank() } ?: return url
    // 安全：日志只留 scheme+host+path，绝不打印 sid / ticket
    Log.i(TAG, "补 sid 后加载: ${redact(url)}")
    return uri.buildUpon().appendQueryParameter("sid", sid).build().toString()
}

/** 供日志使用：去掉 query，避免把 sid / ticket 写进 logcat */
private fun redact(raw: String): String = runCatching {
    val u = Uri.parse(raw)
    "${u.scheme}://${u.host}${u.path}"
}.getOrDefault("(url)")

/**
 * 调试浏览器：地址栏 + 全功能 WebView，用于配合 chrome://inspect(CDP) 抓任意教务页。
 * 仅 debug 构建从「我的」页进入（见 ProfileScreen 的 FEATURES）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DevBrowseScreen(initialUrl: String, nav: NavController? = null) {
    val startUrl = remember(initialUrl) { initialUrl.trim().ifBlank { DEFAULT_URL } }
    val firstLoadUrl = remember(startUrl) { withSid(startUrl) }
    var address by remember(startUrl) { mutableStateOf(startUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun load(raw: String) {
        val target = raw.trim().ifBlank { DEFAULT_URL }
        address = target
        val url = withSid(target)
        Log.i(TAG, "手动加载: ${redact(url)}")
        webView?.loadUrl(url)
    }

    // 离开页面时销毁 WebView，避免后台继续持有会话/内存
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(title = "网页浏览", onBack = nav?.let { n -> { n.navigateUp() } })
        Row(
            Modifier.fillMaxWidth().padding(ScnuSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                label = { Text("地址") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { load(address) }),
            )
            Button(
                onClick = { load(address) },
                shape = MaterialTheme.shapes.small,
            ) {
                Text("打开", style = MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
            thickness = ScnuTokens.Hairline,
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    // 远程调试仅 debug 构建开启，与 MfaWebViewScreen 一致
                    if (cn.edu.sicnu.scnujw.BuildConfig.DEBUG)
                        WebView.setWebContentsDebuggingEnabled(true)
                    // 风控加固：去掉 WebView 的 "; wv" UA 标记（金智前端会识别内嵌环境）
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv", "").replace(" Version/4.0", "")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // 认证页(https)会 302 到教务(http)，放行混合内容
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        /**
                         * 主框架导航到教务域且 URL 无 sid 时，补 sid 后重新加载。
                         * 子框架不在此重写：view.loadUrl 会顶掉主框架，无公开 API 重写子框架 URL。
                         */
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean {
                            val url = request.url.toString()
                            Log.i(TAG, "导航请求: ${redact(url)}")
                            if (!request.isForMainFrame) return false
                            val rewritten = withSid(url)
                            if (rewritten == url) return false
                            view.loadUrl(rewritten)
                            return true
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView, url: String, isReload: Boolean
                        ) {
                            Log.i(TAG, "导航: ${redact(url)}${if (isReload) " (reload)" else ""}")
                            super.doUpdateVisitedHistory(view, url, isReload)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            Log.i(TAG, "加载完成: ${redact(url)}")
                            super.onPageFinished(view, url)
                            cn.edu.sicnu.scnujw.core.diag.Diag.captureWebView(view, url)
                        }

                        override fun onReceivedHttpError(
                            view: WebView, request: WebResourceRequest, response: WebResourceResponse
                        ) {
                            Log.w(TAG, "HTTP ${response.statusCode} ${redact(request.url.toString())}")
                        }

                        override fun onReceivedError(
                            view: WebView, request: WebResourceRequest, error: WebResourceError
                        ) {
                            Log.e(TAG, "加载失败 ${redact(request.url.toString())} : ${error.description}")
                        }
                    }
                    webView = this
                    loadUrl(firstLoadUrl)
                }
            },
        )
    }
}
