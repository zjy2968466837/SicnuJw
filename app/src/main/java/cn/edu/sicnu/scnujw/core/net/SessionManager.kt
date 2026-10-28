package cn.edu.sicnu.scnujw.core.net

import android.content.Context
import android.os.Looper
import android.util.Log
import cn.edu.sicnu.scnujw.core.auth.CasSession
import cn.edu.sicnu.scnujw.core.auth.SecureStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 会话中枢：全字段 Cookie 存储 + 正方 sid + jwcxk 渲染页缓存。
 *
 * 2026-10-26 起 Cookie 存储改由 [CookieStore] 承担：
 *  - 键为 domain|path|name，CASTGC 的 `/authserver` 与清票头的 `/authserver/` 不再互相覆盖；
 *  - 持久化保留 path/expires/secure/httpOnly；
 *  - CASTGC/指纹另存于 [CasSession]（加密），不参与通用字典的 name 覆盖。
 */
object SessionManager {
    private const val PREFS = "scnu_session"
    private const val KEY_SID = "sid"
    private const val KEY_COOKIE_RECORDS = "cookie_records"
    private const val KEY_LEGACY_COOKIES = "cookies"

    @Volatile var sid: String? = null
        private set

    val cookieStore = CookieStore()

    private lateinit var appContext: Context

    /** 单线程落盘器：Keystore 加密 / 序列化 / SharedPreferences 写入一律不放在主线程 */
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scnu-session-persist").apply { isDaemon = true }
    }

    /** 会话代数：clear() 递增，用于丢弃「登出前排队、登出后才执行」的回写任务 */
    @Volatile private var sessionGeneration = 0

    // jwcxk（课表系统）渲染页缓存：webauth 落地时由 WebView 抓取回填。
    // 票据齐备后 OkHttp 也能建会话；HTML 缓存保留为兜底与首屏加速。
    private val jwcxkPages = ConcurrentHashMap<String, String>()

    private fun jwcxkFile(route: String) =
        java.io.File(appContext.filesDir, "jwcxk_" + route.trim('/').replace('/', '_') + ".html")

    fun putJwcxkPage(route: String, html: String) {
        jwcxkPages[route] = html
        if (::appContext.isInitialized) runCatching { jwcxkFile(route).writeText(html) }
    }

    fun getJwcxkPage(route: String): String? {
        jwcxkPages[route]?.let { return it }
        if (!::appContext.isInitialized) return null
        return runCatching {
            val f = jwcxkFile(route)
            if (f.exists()) f.readText().also { jwcxkPages[route] = it } else null
        }.getOrNull()
    }

    private fun clearJwcxkPages() {
        jwcxkPages.clear()
        if (::appContext.isInitialized) runCatching {
            appContext.filesDir.listFiles()
                ?.filter { it.name.startsWith("jwcxk_") }
                ?.forEach { it.delete() }
        }
    }

    val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.saveFromResponse(url, cookies)
            persist()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.loadForRequest(url)
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false) // 登录链路需逐步跟 302，手工控制
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(20))
            .build()
    }

    /** 跟随重定向的镜像 client（换票落地用）；连接池与 cookieJar 复用 */
    val followClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(true).build()
    }

    /**
     * 慢速回发专用：wp_MyUsualScore_Query 等整页重渲染的回发实测超过 20s
     * （默认 readTimeout 会提前超时并误判为失败），放宽到 45s。
     */
    val slowClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(45))
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sid = prefs.getString(KEY_SID, null)

        val records = prefs.getString(KEY_COOKIE_RECORDS, null)
        if (records != null) {
            // 新格式：Keystore 密文；旧格式（历史版本）是明文 JSON 数组，读取后下次落盘即转成密文
            val plain = if (records.startsWith("[")) records else SecureStore.decrypt(records)
            if (plain != null) {
                cookieStore.fromJson(plain)
                // 旧明文格式：尽快改写成密文，不把 CASTGC 留在磁盘明文里
                if (records.startsWith("[")) persist()
            } else {
                Log.w("SessionManager", "Cookie 记录解密失败，按无 Cookie 处理")
                prefs.edit().remove(KEY_COOKIE_RECORDS).apply()
            }
        } else {
            // 旧版只有 domain -> {name: value}：按 CookieSpecs 归类迁移一次
            prefs.getString(KEY_LEGACY_COOKIES, null)?.let { legacy ->
                cookieStore.migrateLegacy(legacy)
                persist()
            }
        }

        // CAS 票据独立加载并注入，保证即使旧字典把 CASTGC 写成空值也能恢复
        CasSession.load(appContext)
        CasSession.injectIntoJar()
    }

    /** WebView（MFA/SSO）产生的 Cookie 同步进来（"k=v; k2=v2" 形态）
     *
     * 调用点位于 WebView 的 shouldOverrideUrlLoading（主线程）：这里只做内存归并与解析，
     * Keystore 加密 / JSON 序列化 / 落盘全部丢给 [persistExecutor]。
     */
    fun importFromWebView(domain: String, cookieHeader: String) {
        cookieHeader.split(";").map { it.trim() }.filter { it.contains('=') }.forEach {
            val (n, v) = it.split("=", limit = 2)
            val spec = CookieSpecs.resolve(domain, n, "/")
            cookieStore.put(
                CookieRecord(
                    name = n,
                    value = v,
                    domain = spec.domain,
                    hostOnly = !spec.domain.startsWith("."),
                    path = spec.path,
                    expiresAt = Long.MAX_VALUE,
                    secure = spec.httpsOnly,
                    httpOnly = CookieSpecs.isSensitive(n),
                    setAt = System.currentTimeMillis(),
                )
            )
        }
        if (!::appContext.isInitialized) return
        val castgc = cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CASTGC=") }
            ?.substringAfter('=')
        val fp = cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("MULTIFACTOR_BROWSER_FINGERPRINT=") }
            ?.substringAfter('=')
        val ctx = appContext
        val generation = sessionGeneration
        runCatching {
            persistExecutor.execute {
                // 排队期间已登出：不要再把 CASTGC/指纹写回加密存储
                if (generation != sessionGeneration) return@execute
                if (!castgc.isNullOrBlank()) CasSession.saveCastgc(ctx, castgc)
                if (!fp.isNullOrBlank()) CasSession.saveFingerprint(ctx, fp)
                writeSnapshot(ctx)
            }
        }.onFailure { Log.w("SessionManager", "Cookie 落盘任务提交失败: ${it.javaClass.simpleName}") }
    }

    fun onLoginSuccess(newSid: String) {
        // 换号判据已上移到 ScoreCache.onAccountKnown（按学号，而不是 sid）：
        // 静默换票、同账号重登都会产生新 sid，不应因此清空数据缓存。
        // 例外：jwcxk 渲染页是「按学期/账号抓的静态快照」，sid 变化后必须重抓（CACHE_MODEL.md）。
        if (sid != null && newSid != sid) clearJwcxkPages()
        sid = newSid
        persist()
    }

    fun clear() {
        sessionGeneration++
        sid = null
        cookieStore.clear()
        clearJwcxkPages()
        if (::appContext.isInitialized) {
            CasSession.clear(appContext)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
        // 数据缓存（score_cache）不在这里清：退出登录后重新登录要复用同一账号的配置。
        // 换号由 ScoreCache.onAccountKnown 按学号判断并清空。
    }

    /**
     * 落盘：Cookie 记录整包用 AndroidKeyStore AES-GCM 加密后再写 SharedPreferences，
     * 不再保留 CASTGC / MULTIFACTOR_BROWSER_FINGERPRINT / SESSION / __pstsid__ 的明文副本。
     * 主线程调用只排队，实际加密与写入在 [persistExecutor]；其它线程直接同步写。
     */
    private fun persist() {
        if (!::appContext.isInitialized) return
        val ctx = appContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { persistExecutor.execute { writeSnapshot(ctx) } }
                .onFailure { Log.w("SessionManager", "Cookie 落盘任务提交失败: ${it.javaClass.simpleName}") }
        } else {
            writeSnapshot(ctx)
        }
    }

    private fun writeSnapshot(ctx: Context) {
        try {
            val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SID, sid)
                .remove(KEY_LEGACY_COOKIES)
            val blob = SecureStore.encrypt(cookieStore.toJson())
            if (blob != null) {
                editor.putString(KEY_COOKIE_RECORDS, blob).apply()
            } else {
                // Keystore 不可用时宁可不落盘，也不把凭据写成明文
                editor.remove(KEY_COOKIE_RECORDS).apply()
                Log.w("SessionManager", "Keystore 不可用，Cookie 仅保留在内存")
            }
        } catch (e: Exception) {
            Log.w("SessionManager", "Cookie 持久化失败: ${e.javaClass.simpleName}")
        }
    }

    /** WebView 回灌后立即落盘：CookieStore.put 不经过 CookieJar，不会自动触发 persist */
    fun persistNow() = persist()

    /** 诊断：当前 Cookie 清单（敏感值完全打码，不输出任何票据片段） */
    fun describeCookies(): List<String> = cookieStore.snapshot().map {
        val v = if (CookieSpecs.isSensitive(it.name)) "***" else it.value
        "${it.name}@${it.domain}${it.path} = $v"
    }
}
