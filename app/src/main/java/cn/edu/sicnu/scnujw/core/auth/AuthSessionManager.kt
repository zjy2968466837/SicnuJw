package cn.edu.sicnu.scnujw.core.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import cn.edu.sicnu.scnujw.core.jw.UsualPrefetch
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.core.zhxg.ZhxgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import kotlin.coroutines.resume

/**
 * 统一登出（`LOGIN_SSO_PLAN.md` Step 16）。
 *
 * 顺序：先请求 CAS logout 销毁 TGT，再清 WebView（await 回调），最后清本地票据与缓存。
 * 注意：CAS 登出不会立即废止已建立的下游会话；正方/jwcxk/zhxg 各自在自己的超时前仍可能有效。
 */
object AuthSessionManager {
    private const val CAS_LOGOUT = "https://authserver.sicnu.edu.cn/authserver/logout"

    /** WebView 清 Cookie 的等待上限：回调不来（主线程无 Looper / 系统挂起）也不能卡住登出 */
    private const val COOKIE_CLEAR_TIMEOUT_MS = 3_000L

    suspend fun logout(context: Context) {
        // 1) 尽力销毁 TGT；失败不阻塞
        withContext(Dispatchers.IO) {
            runCatching {
                SessionManager.client.newCall(
                    Request.Builder().url(CAS_LOGOUT).build()
                ).execute().use { }
            }
        }
        // 2) WebView 侧清 Cookie（异步 API，必须等回调；超时或异常都只记录，不能跳过后续本地清理）
        try {
            withTimeout(COOKIE_CLEAR_TIMEOUT_MS) { clearWebViewCookies() }
        } catch (_: TimeoutCancellationException) {
            Log.w("AuthSessionManager", "WebView 清 Cookie 超时（${COOKIE_CLEAR_TIMEOUT_MS}ms），继续本地登出")
        } catch (e: Exception) {
            // 保留真正的取消：只忽略非取消类异常
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("AuthSessionManager", "WebView 清 Cookie 失败: ${e.javaClass.simpleName}: ${e.message}")
        }
        // 3) 本地票据/缓存：每步独立，任何一步失败都不能让 TGT 留在盘上
        //    先作废在途的平时成绩全量预取，避免登出后旧账号结果落到下一账号的缓存里
        UsualPrefetch.invalidate()
        runCatching { SessionManager.clear() }
            .onFailure { Log.w("AuthSessionManager", "本地会话清理失败: ${it.javaClass.simpleName}") }
        runCatching { AccountIdentityStore.clear(context) }
        SessionRegistry.reset()
        ZhxgClient.reset()
    }

    private suspend fun clearWebViewCookies() = suspendCancellableCoroutine { cont ->
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            cm.flush()
            if (cont.isActive) cont.resume(Unit)
        }
    }
}
