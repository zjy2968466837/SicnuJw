package cn.edu.sicnu.scnujw.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.auth.AccountIdentity
import cn.edu.sicnu.scnujw.core.auth.AccountIdentityStore
import cn.edu.sicnu.scnujw.core.auth.AuthGuard
import cn.edu.sicnu.scnujw.core.auth.AuthSystem
import cn.edu.sicnu.scnujw.core.auth.CasTicketClient
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import cn.edu.sicnu.scnujw.core.jw.UsualPrefetch
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.core.net.WebViewCookieBridge
import cn.edu.sicnu.scnujw.ui.update.UpdateCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内嵌浏览器式统一认证页。
 *
 * 设计取舍（结合本项目必须拿到 CASTGC 才能做静默换票）：
 *  - 不能用 Chrome Custom Tabs / 系统浏览器：Cookie 在浏览器进程里，应用读不到；
 *  - 因此用应用内 WebView，但补齐成熟浏览器的基本能力：地址/标题、进度、回退、
 *    刷新、加载失败面板、桌面模式、超时；不再用 `loadData` 覆盖错误页。
 *  - 不再改写任何 URL（旧实现把 http 升级成 https 会丢掉 POST/一次性 ticket）；
 *    明文由 network_security_config 逐域放行。
 *  - CAS 移动主题的布局塌陷用一段幂等的 CSSOM 修补 + MutationObserver 兜住，
 *    只在页面确实空白时提示，不把修补当成登录前提。
 */
private const val TIMEOUT_MS = 90_000L
private const val MOBILE_UA_FALLBACK =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0 Mobile Safari/537.36"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

private data class PageError(val url: String, val message: String, val code: Int = 0)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthWebViewScreen(
    nav: NavController,
    systemName: String,
    mode: String,
    ret: String,
) {
    val ctx = LocalContext.current
    val system = remember(systemName) { AuthSystem.of(systemName) }
    val startUrl = remember(system) { CasTicketClient.loginUrl(system) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var desktopMode by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(100) }
    var title by remember { mutableStateOf(if (mode == "full") "统一身份认证" else "验证 ${system.label}") }
    var error by remember { mutableStateOf<PageError?>(null) }
    var blank by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var timeoutTick by remember { mutableIntStateOf(0) }
    // 页面加载中标志：超时只衡量「页面加载」，不衡量用户在页面上的操作时长。
    // MFA/短信验证码页天然要等短信、输验证码（常超过 90 秒），
    // 旧实现按整段登录计时并在超时后弹全屏 Dialog，把页面盖住导致点不到「登录」。
    var pageLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    // WebView 底色跟随主题，避免深色主题下工具栏与白色内容区之间闪白块
    val pageBackground = MaterialTheme.colorScheme.surface.toArgb()
    val mobileUa = remember {
        android.webkit.WebSettings.getDefaultUserAgent(ctx)
            .replace("; wv", "").replace(" Version/4.0", "")
            .ifBlank { MOBILE_UA_FALLBACK }
    }

    fun ua(): String = if (desktopMode) DESKTOP_UA else mobileUa

    fun reloadWithMode(desktop: Boolean) {
        desktopMode = desktop
        webView?.let {
            it.settings.userAgentString = ua()
            it.reload()
        }
        // 重载后要允许重新走「落地 → 复验」
        done = false
        error = null
        blank = false
        timeoutTick++
    }

    /** 落地：导出 Cookie → 复验 → 回目标页 */
    fun handleLanding(url: String) {
        if (done || !landed(system, url)) return
        done = true
        timedOut = false
        if (system == AuthSystem.ZF) {
            SID_PAT.find(url)?.let { SessionManager.onLoginSuccess(it.groupValues[1]) }
        }
        scope.launch {
            val export = withContext(Dispatchers.IO) { WebViewCookieBridge.exportAfterLogin(ctx) }
            android.util.Log.i(
                "ScnuJwAuth",
                "落地 ${system.name}；回灌 ${export.imported} 条 CASTGC=${export.castgcFound} FP=${export.fingerprintFound}"
            )
            val ok = withContext(Dispatchers.IO) { AuthGuard.verifyAfterLogin(system) }
            if (ok) {
                // 统一落点：清到图根后先以课表为根，再回目标页。
                // 底栏 tab 的 popUpTo("schedule") 依赖课表始终在栈底；否则 tab 点击只会叠加历史页。
                nav.navigate("schedule") { popUpTo(nav.graph.id) { inclusive = false } }
                if (ret != "schedule") nav.navigate(ret) { launchSingleTop = true }
                // 更新检查限定已登录设备：首次登录完成时补一次（启动时因未登录被跳过）。
                // 直接在主线程调用：网络在 UpdateManager 内部切 IO，弹窗状态也在主线程写。
                UpdateCenter.checkNow(ctx)
            } else {
                error = PageError(url, "${system.label}会话未建立。若刚完成登录，可直接重试一次。")
            }
        }
    }

    DisposableEffect(system) {
        AuthGuard.onInteractiveStarted(system)
        // 把已保存的 CAS 票据推给 WebView：有 TGT+指纹时可免账密直接换票
        WebViewCookieBridge.pushCasCookies()
        onDispose {
            AuthGuard.onInteractiveFinished(system)
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    LaunchedEffect(timeoutTick) {
        timedOut = false
        // 页面已加载完成时不计时：MFA 等页面需要用户慢慢操作
        if (!pageLoading) return@LaunchedEffect
        delay(TIMEOUT_MS)
        if (pageLoading && !done) timedOut = true
    }

    BackHandler {
        when {
            webView?.canGoBack() == true -> webView?.goBack()
            else -> nav.navigateUp()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // ── 浏览器工具栏 ──
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (webView?.canGoBack() == true) webView?.goBack() else nav.navigateUp()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            hostOf(currentUrl),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { reloadWithMode(!desktopMode) }) {
                        Text(
                            if (desktopMode) "手机版" else "桌面版",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { nav.navigateUp() }) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = cn.edu.sicnu.scnujw.ui.theme.ScnuTokens.OutlineAlpha),
                    thickness = cn.edu.sicnu.scnujw.ui.theme.ScnuTokens.Hairline,
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }

        // 非阻塞提示条：只在「页面加载慢/空白」时提示，绝不盖住 WebView。
        // MFA 页需要用户等短信、输验证码，全屏 Dialog 会让人点不到页内按钮。
        if (timedOut || blank) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (blank) "页面没有显示可操作内容，可能是排版异常。"
                        else "页面加载超过 ${TIMEOUT_MS / 1000} 秒，网络可能不稳；下方页面仍可操作。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { timedOut = false; blank = false; timeoutTick++; webView?.reload() }) {
                        Text("重试", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { reloadWithMode(!desktopMode) }) {
                        Text(if (desktopMode) "手机版" else "桌面版", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { timedOut = false; blank = false }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "忽略提示",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        if (cn.edu.sicnu.scnujw.BuildConfig.DEBUG)
                            WebView.setWebContentsDebuggingEnabled(true)
                        with(settings) {
                            userAgentString = ua()
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = false
                            displayZoomControls = false
                            setSupportMultipleWindows(false)
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        setBackgroundColor(pageBackground)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(v: WebView, t: String?) {
                                if (!t.isNullOrBlank()) title = t
                            }

                            override fun onCreateWindow(
                                v: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message,
                            ): Boolean {
                                // 同窗口打开，避免弹窗被吞或产生多个 WebView
                                (resultMsg.obj as? WebView.WebViewTransport)?.let { transport ->
                                    transport.webView = v
                                    resultMsg.sendToTarget()
                                }
                                return true
                            }

                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.d("ScnuJwAuth", "[${m.messageLevel()}] ${m.message()}")
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            private var retriedUrl: String? = null

                            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(v, url, favicon)
                                currentUrl = url
                                if (url.startsWith("http")) {
                                    error = null
                                    blank = false
                                    pageLoading = true
                                    timeoutTick++
                                }
                            }

                            override fun onPageFinished(v: WebView, url: String) {
                                super.onPageFinished(v, url)
                                cn.edu.sicnu.scnujw.core.diag.Diag.captureWebView(v, url)
                                currentUrl = url
                                pageLoading = false
                                captureAccount(ctx, url)
                                if (url.startsWith("http")) {
                                    applyLayoutFix(v)
                                    // 空白看门狗：DOM 有文字或有可见表单才算渲染成功
                                    v.postDelayed({
                                        v.evaluateJavascript(BLANK_PROBE) { raw ->
                                            val json = raw?.trim()?.removeSurrounding("\"")
                                                ?.replace("\\\"", "\"") ?: ""
                                            val textLen = Regex("\"textLen\":(\\d+)")
                                                .find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 99
                                            val visible = json.contains("\"visible\":true")
                                            blank = textLen < 10 && !visible
                                        }
                                    }, 1800)
                                }
                                if (url.startsWith("http")) handleLanding(url)
                            }

                            override fun onReceivedError(
                                v: WebView,
                                request: WebResourceRequest,
                                err: WebResourceError,
                            ) {
                                super.onReceivedError(v, request, err)
                                if (!request.isForMainFrame) return
                                val url = request.url.toString()
                                val desc = err.description?.toString() ?: "网络错误"
                                android.util.Log.w("ScnuJwAuth", "加载失败 ${hostOf(url)}: $desc (${err.errorCode})")
                                // 一次性 ticket 不重放；只对入口页自动重试一次
                                val isTicket = url.contains("ticket=", ignoreCase = true)
                                if (!isTicket && retriedUrl != url && url.startsWith("http")) {
                                    retriedUrl = url
                                    v.postDelayed({ v.loadUrl(url) }, 1200)
                                    return
                                }
                                error = PageError(url, desc, err.errorCode)
                                pageLoading = false
                                progress = 100
                            }

                            override fun onReceivedHttpError(
                                v: WebView,
                                request: WebResourceRequest,
                                resp: WebResourceResponse,
                            ) {
                                super.onReceivedHttpError(v, request, resp)
                                if (!request.isForMainFrame) return
                                if (resp.statusCode >= 400) {
                                    error = PageError(
                                        request.url.toString(),
                                        "服务器返回 HTTP ${resp.statusCode}",
                                        resp.statusCode,
                                    )
                                }
                            }

                            override fun onReceivedSslError(
                                v: WebView,
                                handler: SslErrorHandler,
                                e: android.net.http.SslError,
                            ) {
                                handler.cancel()
                                error = PageError(e.url ?: currentUrl, "证书校验失败：${e.primaryError}")
                            }

                            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(v, url, isReload)
                                if (!url.isNullOrBlank()) currentUrl = url
                                if (!url.isNullOrBlank()) captureAccount(ctx, url)
                            }

                            override fun shouldOverrideUrlLoading(
                                v: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                // 不改写 URL：http→https 的改写会丢 POST / 一次性 ticket
                                val url = request.url
                                return when (url.scheme) {
                                    "http", "https" -> false
                                    else -> {
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, url))
                                        }
                                        true
                                    }
                                }
                            }
                        }
                        loadUrl(startUrl)
                        webView = this
                    }
                },
                update = { wv -> if (wv.settings.userAgentString != ua()) wv.settings.userAgentString = ua() },
            )

            error?.let { e ->
                ErrorPanel(
                    system = system,
                    error = e,
                    onRetry = {
                        error = null
                        blank = false
                        // 关键：复位 done，否则「登录后复验失败」的重试只会重载页面、
                        // 不会重跑 verifyAfterLogin，按钮形同失效
                        done = false
                        // ticket 已一次性：重试要回入口页重新换新票
                        val target = if (e.url.contains("ticket=", true)) startUrl else e.url
                        webView?.loadUrl(target)
                        timeoutTick++
                    },
                    onDesktop = { reloadWithMode(true) },
                    onBrowser = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url))) }
                    },
                    onClose = { nav.navigateUp() },
                )
            }
            // blank / timedOut 不再弹全屏 Dialog：改由顶部非阻塞提示条呈现（见上方 banner），
            // 避免盖住 MFA 等需要用户操作的页面。
        }
    }
}

@Composable
private fun ErrorPanel(
    system: AuthSystem,
    error: PageError,
    onRetry: () -> Unit,
    onDesktop: () -> Unit,
    onBrowser: () -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 必须用 Dialog：Compose 覆盖层画在 AndroidView 之上时，触摸仍会被 WebView 吃掉。
    // 点遮罩不关闭，避免误触退出登录页；只能点「关闭」。
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(cs.surface.copy(alpha = 0.97f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                // 与全 App 一致：surface + 1dp 细描边表达层级，不用 tonalElevation / 阴影
                shape = MaterialTheme.shapes.large,
                color = cs.surface,
                border = BorderStroke(
                    1.dp,
                    cs.outline.copy(alpha = cn.edu.sicnu.scnujw.ui.theme.ScnuTokens.OutlineAlpha),
                ),
            ) {
                Column(
                    Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("页面打不开", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium, color = cs.error)
                    Text(
                        hostOf(error.url),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Text(
                        error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Text(
                        "常见原因：校园网未连通，或登录页在当前网络下加载异常。可以先点「再试一次」；仍不行切到桌面版；还不行用系统浏览器确认网络是否可用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRetry,
                            shape = MaterialTheme.shapes.small,
                        ) { Text("再试一次", style = MaterialTheme.typography.labelMedium) }
                        OutlinedButton(
                            onClick = onDesktop,
                            shape = MaterialTheme.shapes.small,
                        ) { Text("切到桌面版", style = MaterialTheme.typography.labelMedium) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onBrowser,
                            shape = MaterialTheme.shapes.small,
                        ) { Text("在系统浏览器确认", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            onClick = onClose,
                            shape = MaterialTheme.shapes.small,
                        ) { Text("关闭", style = MaterialTheme.typography.labelMedium) }
                    }
                    Text(
                        "系统浏览器只用于确认网络；登录仍需回到本页完成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun landed(system: AuthSystem, url: String): Boolean {
    val u = Uri.parse(url)
    return when (system) {
        AuthSystem.ZF -> SID_PAT.containsMatchIn(url)
        AuthSystem.JWCXK -> u.host == "jwcxk.sicnu.edu.cn" && u.path == "/student/home"
        AuthSystem.ZHGX -> u.host == "zhxg.sicnu.edu.cn" &&
            u.path?.startsWith("/edu/") == true && u.path?.startsWith("/edu/api/") != true
    }
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)

private val SID_PAT = Regex("[Ii]ndex\\.aspx\\?sid=([0-9A-Fa-f]+)")
private val XID_PAT = Regex("[?&]xid=(\\d+)")

/**
 * 正方落地链经过 `/pjtj.ashx?...&xid=学号`，在这里捕获学号。
 * 数据缓存按学号归属（单账号单配置）：同学号复用，换号才清空。
 */
private fun captureAccount(ctx: android.content.Context, url: String) {
    XID_PAT.find(url)?.let { m ->
        val code = m.groupValues[1]
        AccountIdentityStore.save(ctx, AccountIdentity(code, "", "zf", System.currentTimeMillis()))
        ScoreCache.onAccountKnown(ctx, code)
    }
}

/**
 * 幂等布局修补：只解裁剪，不重排页面。
 * CAS 移动主题实测：容器高度 0 / overflow:auto 把表单卡片裁出视口；
 * 信任设备弹窗也会被同样裁剪。MutationObserver 兜住异步弹出的节点。
 */
private const val LAYOUT_FIX_JS = """
(function(){
  if (window.__scnuUnclip) { try { window.__scnuUnclip(); } catch(e){} return 'again'; }
  function unclip(el){
    if(!el || !el.style) return;
    el.style.setProperty('overflow','visible','important');
    el.style.setProperty('height','auto','important');
    el.style.setProperty('max-height','none','important');
  }
  function apply(){
    document.querySelectorAll('.mobile-page-center-box,.mobile-page-content-box,.common-mobile-page-content-box')
      .forEach(unclip);
    var wb=document.querySelector('.mobile-page-white-box');
    if(wb){
      var s=wb.style;
      s.setProperty('position','fixed','important');
      s.setProperty('top','64px','important');
      s.setProperty('left','50%','important');
      s.setProperty('transform','translateX(-50%)','important');
      s.setProperty('width','calc(100% - 24px)','important');
      s.setProperty('max-height','calc(100% - 80px)','important');
      s.setProperty('overflow-y','auto','important');
      s.setProperty('z-index','50','important');
      s.setProperty('background','#ffffff','important');
    }
    // 可信设备弹窗：页面样式用 height:100vh；实测本 WebView 里 innerHeight=789 而 100vh 解析为 0，
    // 弹窗会塌成 0 高度（MFA 验证码通过后弹不出来，用户以为「点登录没反应」）。
    // 用 inset:0 钉满视口并放开 PC 端 min-width，内容交给页面自己的 flex 居中。
    document.querySelectorAll('.trust-device-modal').forEach(function(el){
      var m=el.style;
      m.setProperty('position','fixed','important');
      m.setProperty('top','0','important');
      m.setProperty('left','0','important');
      m.setProperty('right','0','important');
      m.setProperty('bottom','0','important');
      m.setProperty('width','auto','important');
      m.setProperty('height','auto','important');
      m.setProperty('min-width','0','important');
      m.setProperty('max-height','none','important');
      m.setProperty('overflow','visible','important');
      m.setProperty('z-index','9999','important');
      var mc=el.querySelector('.trust-device-content');
      if(mc){
        mc.style.setProperty('width','calc(100% - 32px)','important');
        mc.style.setProperty('max-width','428px','important');
      }
    });
    // Bootstrap 弹窗：只解除 PC 端宽度限制，位置仍由页面自己的布局决定
    document.querySelectorAll('.modal.show').forEach(function(el){
      el.style.setProperty('min-width','0','important');
    });
  }
  window.__scnuUnclip=apply;
  apply();
  try{
    new MutationObserver(function(){ apply(); }).observe(document.documentElement,{
      childList:true, subtree:true, attributes:true, attributeFilter:['style','class']
    });
  }catch(e){}
  return 'installed';
})()
"""

private const val BLANK_PROBE = """
(function(){
  var t = document.body ? document.body.innerText.trim() : '';
  var visible = false;
  var nodes = document.querySelectorAll('form,input,button,select,textarea,.mobile-page-white-box');
  for (var i=0;i<nodes.length;i++){
    var r = nodes[i].getBoundingClientRect();
    if (r.width > 10 && r.height > 10 && r.bottom > 0 && r.top < window.innerHeight) { visible = true; break; }
  }
  return JSON.stringify({textLen:t.length, visible:visible});
})()
"""

private fun applyLayoutFix(view: WebView) {
    view.evaluateJavascript(LAYOUT_FIX_JS) { r ->
        android.util.Log.i("ScnuJwAuth", "布局修补 $r")
    }
}
