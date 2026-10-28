package cn.edu.sicnu.scnujw.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.jw.UsualPrefetch
import cn.edu.sicnu.scnujw.core.jwcxk.JwcxkClient
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import java.util.regex.Pattern

/**
 * MFA 官方验证页（产品决策：不逆向，WebView 直跳）。
 * 完成后 authserver 会带 ticket 跳回 CasLogin.aspx → Index.aspx?sid=...
 * 在此截获 sid 与各域 Cookie，回灌 SessionManager。
 *
 * 外层是完整的 Compose 外壳：页头（返回）、加载进度、失败面板与重试；
 * 离开页面时销毁 WebView，并禁止退出后仍执行导航。
 */
private val SID_PAT = Pattern.compile("[Ii]ndex\\.aspx\\?sid=([0-9A-F]+)")

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MfaWebViewScreen(
    nav: NavController,
    startUrl: String,
    donePattern: String? = null,
    returnRoute: String = "schedule",
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var disposed by remember { mutableStateOf(false) }
    var navDone by remember { mutableStateOf(false) }

    var done by remember { mutableStateOf(false) }
    // webauth 模式：落地后依次加载 course-table、room-free 并抓取 HTML
    var pendingCourseTable by remember { mutableStateOf(false) }
    var tableSaved by remember { mutableStateOf(false) }
    var pendingRoomFree by remember { mutableStateOf(false) }
    var roomFreeSaved by remember { mutableStateOf(false) }

    val pageBackground = MaterialTheme.colorScheme.surface.toArgb()
    val title = if (donePattern != null) "登录课表系统" else "统一身份验证"

    /** 回到发起页；退出后或已导航过就不再执行（postDelayed 的回调不会随组合取消） */
    fun finish() {
        if (disposed || navDone) return
        navDone = true
        // 同 AuthWebViewScreen：以课表为栈底清栈，再回发起页，保证底栏 tab 的 popUpTo 目标存在
        nav.navigate("schedule") { popUpTo(nav.graph.id) { inclusive = false } }
        if (returnRoute != "schedule") nav.navigate(returnRoute) { launchSingleTop = true }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    // 重试：回到登录入口重新走一遍（一次性 ticket 不能重放）
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) {
            errorMsg = null
            done = false
            pendingCourseTable = false
            tableSaved = false
            pendingRoomFree = false
            roomFreeSaved = false
            navDone = false
            webView?.loadUrl(startUrl)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(title = title, onBack = { nav.navigateUp() })
        if (progress in 1..99) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        val tag = "ScnuJwWV"
                        // 远程调试仅 debug 构建开启（chrome://inspect 可查 WebView DOM）
                        if (cn.edu.sicnu.scnujw.BuildConfig.DEBUG)
                            WebView.setWebContentsDebuggingEnabled(true)
                        // 风控加固：去掉 WebView 的 "; wv" UA 标记（金智前端会识别内嵌环境）
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "").replace(" Version/4.0", "")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // 认证页(https)会 302 到教务(http)，放行混合内容
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        setBackgroundColor(pageBackground)
                        // 前端控制台日志透传到 logcat（TAG=ScnuJwWV），白屏/风控问题可远程诊断
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(v: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.d(tag, "[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                cn.edu.sicnu.scnujw.core.diag.Diag.captureWebView(view, url)
                                if (disposed) return
                                // webauth 课表页渲染完成（Vue SPA 需等 DOM）→ 抓 HTML 入缓存，
                                // 然后接着抓 room-free 页（空闲教室的校区/类型列表只在该页内嵌）
                                if (pendingCourseTable && url.contains("/student/for-std/course-table") && !tableSaved) {
                                    // SPA 数据是异步拉的：延迟 2.5s 再抓 DOM，课程已渲染进文档
                                    view.postDelayed({
                                        if (tableSaved || disposed) return@postDelayed
                                        view.evaluateJavascript(
                                            "(function(){return document.documentElement.outerHTML;})()") { htmlJs ->
                                            val html = jsHtml(htmlJs)
                                            if (html.length > 5000) {
                                                tableSaved = true
                                                SessionManager.putJwcxkPage(JwcxkClient.ROUTE_COURSE_TABLE, html)
                                                android.util.Log.i(tag, "✅ 课表渲染页已缓存 ${html.length} 字符")
                                                // 同一 WebView 会话继续抓 room-free
                                                pendingRoomFree = true
                                                view.loadUrl("https://jwcxk.sicnu.edu.cn" + JwcxkClient.ROUTE_ROOM_FREE)
                                            } else {
                                                // 还没渲染完，再等一轮
                                                view.postDelayed({
                                                    if (tableSaved || disposed) return@postDelayed
                                                    view.evaluateJavascript(
                                                        "(function(){return document.documentElement.outerHTML;})()") { retryJs ->
                                                        val h2 = jsHtml(retryJs)
                                                        if (h2.length > 5000) {
                                                            tableSaved = true
                                                            SessionManager.putJwcxkPage(JwcxkClient.ROUTE_COURSE_TABLE, h2)
                                                            android.util.Log.i(tag, "✅ 课表渲染页已缓存 ${h2.length} 字符(重试)")
                                                            pendingRoomFree = true
                                                            view.loadUrl("https://jwcxk.sicnu.edu.cn" + JwcxkClient.ROUTE_ROOM_FREE)
                                                        } else {
                                                            // 两次都抓不到：不再静默停在课表页，给用户重试入口
                                                            android.util.Log.w(tag, "课表页两次抓取均不足 5000 字符")
                                                            errorMsg = "课表页没有加载完整。请点「重新加载」再试一次。"
                                                        }
                                                    }
                                                }, 3000)
                                            }
                                        }
                                    }, 2500)
                                    return
                                }
                                // room-free 页渲染完成 → 抓 HTML（含 _campusList / _roomTypeList）后回课表
                                if (pendingRoomFree && url.contains("/student/for-std/room-free") && !roomFreeSaved) {
                                    view.postDelayed({
                                        if (roomFreeSaved || disposed) return@postDelayed
                                        view.evaluateJavascript(
                                            "(function(){return document.documentElement.outerHTML;})()") { htmlJs ->
                                            val html = jsHtml(htmlJs)
                                            if (html.contains("_campusList")) {
                                                roomFreeSaved = true
                                                SessionManager.putJwcxkPage(JwcxkClient.ROUTE_ROOM_FREE, html)
                                                android.util.Log.i(tag, "✅ room-free 页已缓存 ${html.length} 字符")
                                            } else {
                                                android.util.Log.w(tag, "room-free 页缺 _campusList（${html.length} 字符），放弃缓存")
                                            }
                                            // 重定向回发起页：从空闲教室进来就回空闲教室（带着新会话重新初始化）
                                            finish()
                                        }
                                    }, 1500)
                                    return
                                }
                                if ("reAuthCheck" in url) {
                                    // 移动主题 MFA 页根因（CDP 实测确认）：主题布局塌陷——
                                    // .mobile-page-center-box 高度 0、.mobile-page-content-box 仅 32px
                                    // 且 overflow:auto，把整张表单卡片裁剪到不可见（"白屏"假象，
                                    // DOM 实际完好）。修复：CSSOM（<style> 注入会被页面 CSP 拦截，
                                    // 必须逐元素 setProperty）把表单卡片 fixed 定位拔出裁剪上下文。
                                    // 注入色调用「瓶绿」，不再使用与项目无关的蓝色。
                                    view.evaluateJavascript(
                                        """
                                        (function(){
                                          var out=[];
                                          var bg=document.querySelectorAll('.common-mobile-page-title-box,.top-box');
                                          for(var i=0;i<bg.length;i++){
                                            bg[i].style.setProperty('background','linear-gradient(180deg,#1A4429,#10291B)','important');
                                          }
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
                                            out.push('unplugged');
                                          } else { out.push('no-white-box'); }
                                          return out.join(',');
                                        })()
                                        """.trimIndent()
                                    ) { r -> android.util.Log.i(tag, "MFA视觉修补 $r") }
                                    // 内容状态探测：白屏问题可从 logcat 直接判断服务端是否降级了挑战
                                    view.evaluateJavascript(
                                        "(function(){var v=document.querySelectorAll('[id*=Div],[class*=reauth]').length;" +
                                            "var body=document.body?document.body.innerText.length:0;" +
                                            "return '内容探测: 可见块='+v+' 正文长度='+body;})()"
                                    ) { result -> android.util.Log.i(tag, "MFA $result") }
                                } else if ("authserver/login" in url) {
                                    // CAS 密码页：仅背景兜底。不做 scrollIntoView——
                                    // 移动主题布局塌陷下滚动会把容器 scrollTop 推大加重裁剪（CDP 实测）
                                    view.evaluateJavascript(
                                        "(function(){var h=document.querySelector('.mo-bg,.head,.top,[class*=banner]');" +
                                            "if(h){h.style.background='linear-gradient(135deg,#143A21,#1A4429)';" +
                                            "return '背景兜底OK';}return '无背景元素';})()"
                                    ) { r -> android.util.Log.i(tag, "MFA视觉修补 $r") }
                                }
                            }
                            override fun onReceivedHttpError(
                                view: WebView, req: WebResourceRequest, resp: android.webkit.WebResourceResponse
                            ) {
                                // 只打 host+path，不打印 query（可能含 CAS ticket / sid）
                                if (cn.edu.sicnu.scnujw.BuildConfig.DEBUG)
                                    android.util.Log.w(tag, "HTTP ${resp.statusCode} ${req.url.host}${req.url.path}")
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                if (cn.edu.sicnu.scnujw.BuildConfig.DEBUG)
                                    android.util.Log.i(tag, "导航: ${android.net.Uri.parse(url).host}${android.net.Uri.parse(url).path}")
                                super.doUpdateVisitedHistory(view, url, isReload)
                            }
                            override fun onReceivedError(
                                view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError
                            ) {
                                if (cn.edu.sicnu.scnujw.BuildConfig.DEBUG)
                                    android.util.Log.e(tag, "加载失败 ${req.url.host}${req.url.path} : ${err.description}")
                                if (!req.isForMainFrame) return
                                // 不再用 loadData 覆盖错误页：错误交给 Compose 面板，带重试
                                progress = 100
                                errorMsg = err.description?.toString() ?: "网络错误"
                            }
                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest): Boolean {
                                var url = request.url.toString()
                                // WebView 禁明文：SSO 链上 CAS 会以 http 形式 302，统一升级 https 重载
                                if (url.startsWith("http://") &&
                                    !url.startsWith("http://202.115.194.60")) {
                                    view.loadUrl(url.replaceFirst("http://", "https://"))
                                    return true
                                }
                                // jwcxk SSO 模式：落地 /student/home 后，先回灌 Cookie，
                                // 再由 WebView 直接加载课表页并把渲染 HTML 交给 OkHttp 侧缓存
                                // （实测：jwcxk 会话只活在 WebView 上下文，回灌 OkHttp 后
                                //   __pstsid__ 不被服务端认可，网络侧重走 SSO 又缺 CASTGC）
                                if (donePattern != null && url.contains(donePattern) && !done) {
                                    done = true
                                    android.util.Log.i(tag, "✅ webauth 落地 $donePattern")
                                    // CookieManager.getCookie 按 URL 路径匹配：jwcxk 的 SESSION/__pstsid__
                                    // 挂在 Path=/student、srv_id 挂在 Path=/manager，只传 origin 拿不到，
                                    // 必须逐路径回灌，否则 store 里留着旧会话值导致 API 全部 302
                                    listOf(
                                        "https://authserver.sicnu.edu.cn/authserver/" to "authserver.sicnu.edu.cn",
                                        "http://202.115.194.60/" to "202.115.194.60",
                                        "https://jwcxk.sicnu.edu.cn/student/" to "jwcxk.sicnu.edu.cn",
                                        "https://jwcxk.sicnu.edu.cn/manager/" to "jwcxk.sicnu.edu.cn",
                                        "https://jwcxk.sicnu.edu.cn/" to "jwcxk.sicnu.edu.cn",
                                        "http://jwcxk.sicnu.edu.cn/student/" to "jwcxk.sicnu.edu.cn",
                                    ).forEach { (probeUrl, domain) ->
                                        CookieManager.getInstance().getCookie(probeUrl)?.let {
                                            SessionManager.importFromWebView(domain, it)
                                        }
                                    }
                                    // WebView 内继续取课表渲染页（同一会话），onPageFinished 抓 HTML 入缓存
                                    view.loadUrl("https://jwcxk.sicnu.edu.cn/student/for-std/course-table")
                                    pendingCourseTable = true
                                    return true
                                }
                                val m = SID_PAT.matcher(url)
                                if (m.find() && !done) {
                                    done = true
                                    android.util.Log.i("ScnuJwWV", "✅ 会话截获（sid 已隐藏）")
                                    // WebView Cookie 全量回灌（含 authserver 的信任票据）
                                    listOf(
                                        "https://authserver.sicnu.edu.cn",
                                        "http://202.115.194.60",
                                        "https://jwcxk.sicnu.edu.cn"
                                    ).forEach { origin ->
                                        CookieManager.getInstance().getCookie(origin)?.let {
                                            SessionManager.importFromWebView(
                                                origin.removePrefix("https://").removePrefix("http://"), it)
                                        }
                                    }
                                    SessionManager.onLoginSuccess(m.group(1)!!)
                                    finish()
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(startUrl)
                        webView = this
                    }
                },
            )

            errorMsg?.let { msg ->
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    ScnuStatePanel(
                        title = "页面没有打开",
                        body = msg,
                        isError = true,
                        actionLabel = "重新加载",
                        onAction = { reloadTick++ },
                        secondaryLabel = "返回",
                        onSecondaryAction = { nav.navigateUp() },
                    )
                }
            }
        }
    }
}

/** evaluateJavascript 返回的是带转义的 JS 字符串，这里还原成 HTML */
private fun jsHtml(raw: String?): String = raw?.trim()?.removeSurrounding("\"")
    ?.replace("\\u003C", "<")?.replace("\\u003E", ">")
    ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    ?.replace("\\n", "\n")?.replace("\\t", "\t") ?: ""
