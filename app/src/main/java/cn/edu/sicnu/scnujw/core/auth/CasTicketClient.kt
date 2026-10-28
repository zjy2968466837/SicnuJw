package cn.edu.sicnu.scnujw.core.auth

import android.util.Log
import cn.edu.sicnu.scnujw.core.net.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** 需要下游会话的三个业务系统；service 参数必须逐字匹配（见 LOGIN_SSO_PLAN.md §5 Step 6） */
enum class AuthSystem(val service: String) {
    ZF("http://202.115.194.60/CasLogin.aspx"),
    JWCXK("https://jwcxk.sicnu.edu.cn/student/sso/login"),
    ZHGX("https://zhxg.sicnu.edu.cn/callback/edu/");

    val label: String
        get() = when (this) {
            ZF -> "教务（正方）"
            JWCXK -> "课表（树维）"
            ZHGX -> "智慧学工"
        }

    companion object {
        fun of(name: String?): AuthSystem =
            entries.firstOrNull { it.name.equals(name, true) } ?: ZF
    }
}

sealed interface TicketResult {
    /** CAS 已出票，location 是带 ticket 的跳转地址 */
    data class Ticket(val location: String) : TicketResult

    /** TGT 有效但设备不受信任，必须过一个 MFA */
    data class NeedMfa(val url: String) : TicketResult

    /** 没有有效 TGT，需要完整账密登录 */
    data class NeedPassword(val url: String) : TicketResult

    /** 网络/服务端异常，不应改变会话状态 */
    data class Transient(val message: String) : TicketResult
}

/**
 * CAS 换票客户端（`LOGIN_SSO_PLAN.md` Step 6）。
 *
 * 每次只换一张 ST，不重试同一个 URL；每系统一把 Mutex + 3 秒最小间隔。
 * 请求必须挂在 SessionManager 的 CookieStore 上，才能带上 CASTGC 与信任指纹。
 */
object CasTicketClient {
    private const val CAS = "https://authserver.sicnu.edu.cn"
    private const val LOGIN_PATH = "/authserver/login"
    private const val HOST = "authserver.sicnu.edu.cn"

    /** 与 WebView 接近的 UA：避免 OkHttp 默认 UA 触发风控差异 */
    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    private val mutexes = ConcurrentHashMap<AuthSystem, Mutex>()
    private val lastRequestAt = ConcurrentHashMap<AuthSystem, Long>()
    private const val MIN_INTERVAL_MS = 3_000L

    suspend fun requestTicket(system: AuthSystem): TicketResult = withContext(Dispatchers.IO) {
        val mutex = mutexes.getOrPut(system) { Mutex() }
        mutex.withLock {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - (lastRequestAt[system] ?: 0L))
            if (wait > 0) delay(wait)
            lastRequestAt[system] = System.currentTimeMillis()

            CasSession.injectIntoJar()
            val url = "$CAS$LOGIN_PATH".toHttpUrl().newBuilder()
                .addQueryParameter("service", system.service)
                .build()

            try {
                SessionManager.client.newCall(
                    Request.Builder().url(url).header("User-Agent", UA).build()
                ).execute().use { resp ->
                    val location = resp.header("Location")?.let { resolve(resp.request.url.toString(), it) }
                    when {
                        resp.code in 300..399 && location != null &&
                            location.contains("ticket=ST-", ignoreCase = true) ->
                            TicketResult.Ticket(location)

                        resp.code in 300..399 && location != null &&
                            location.contains("reAuthCheck", ignoreCase = true) ->
                            TicketResult.NeedMfa(safeAuthUrl(location, system))

                        resp.code == 200 -> TicketResult.NeedPassword(safeLoginUrl(system))

                        else -> TicketResult.Transient("CAS 返回 ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                TicketResult.Transient(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loginUrl(system: AuthSystem): String =
        "$CAS$LOGIN_PATH?service=${java.net.URLEncoder.encode(system.service, "UTF-8")}"

    private fun safeLoginUrl(system: AuthSystem) = loginUrl(system)

    /** reAuthCheck 地址只允许回到 authserver；异常值回退登录页，避免被伪造 Location 带走 */
    private fun safeAuthUrl(location: String, system: AuthSystem): String {
        val parsed = runCatching { location.toHttpUrl() }.getOrNull() ?: return loginUrl(system)
        val ok = parsed.isHttps && parsed.host == HOST && parsed.encodedPath.startsWith("/authserver/")
        return if (ok) location else loginUrl(system)
    }

    private fun resolve(base: String, location: String): String? =
        runCatching { base.toHttpUrl().resolve(location)?.toString() }.getOrNull()

    /** 日志脱敏：只留前 8 位 */
    fun mask(value: String?): String = when {
        value.isNullOrBlank() -> "(空)"
        value.length <= 8 -> "***"
        else -> value.take(8) + "…"
    }

    internal fun logTicket(system: AuthSystem, result: TicketResult) {
        val kind = when (result) {
            is TicketResult.Ticket -> "TICKET ${mask(result.location)}"
            is TicketResult.NeedMfa -> "MFA"
            is TicketResult.NeedPassword -> "PASSWORD"
            is TicketResult.Transient -> "TRANSIENT ${result.message}"
        }
        Log.i("CasTicket", "${system.name}: $kind")
    }
}
