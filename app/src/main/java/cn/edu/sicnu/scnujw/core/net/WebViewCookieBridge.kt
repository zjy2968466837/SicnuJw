package cn.edu.sicnu.scnujw.core.net

import android.content.Context
import android.webkit.CookieManager
import cn.edu.sicnu.scnujw.core.auth.CasSession

/**
 * WebView → OkHttp 的 Cookie 回灌（`LOGIN_SSO_PLAN.md` Step 5）。
 *
 * 关键点：`CookieManager.getCookie(url)` 按路径匹配，只探测 origin 拿不到
 * `Path=/authserver` 的 CASTGC。这里按「深路径 + origin」两档探测，
 * 再按 CookieSpecs 归类，绝不按「探测的是哪个 URL」直接定 path。
 */
object WebViewCookieBridge {

    private data class Probe(val url: String, val host: String, val path: String)

    private val PROBES = listOf(
        Probe("https://authserver.sicnu.edu.cn/authserver/", "authserver.sicnu.edu.cn", "/authserver"),
        Probe("https://authserver.sicnu.edu.cn/", "authserver.sicnu.edu.cn", "/"),
        Probe("http://202.115.194.60/Index.aspx", "202.115.194.60", "/"),
        Probe("http://202.115.194.60/", "202.115.194.60", "/"),
        Probe("https://jwcxk.sicnu.edu.cn/student/", "jwcxk.sicnu.edu.cn", "/student/"),
        Probe("https://jwcxk.sicnu.edu.cn/manager/", "jwcxk.sicnu.edu.cn", "/manager"),
        Probe("https://jwcxk.sicnu.edu.cn/", "jwcxk.sicnu.edu.cn", "/"),
        Probe("https://zhxg.sicnu.edu.cn/edu/", "zhxg.sicnu.edu.cn", "/"),
        Probe("https://zhxg.sicnu.edu.cn/", "zhxg.sicnu.edu.cn", "/"),
    )

    data class ExportResult(
        val imported: Int,
        val names: List<String>,
        val castgcFound: Boolean,
        val fingerprintFound: Boolean,
    )

    /**
     * 把 WebView 里当前所有相关域的 Cookie 分类写入 CookieStore，并同步 CasSession。
     * 必须在 IO 线程调用（getCookie 会做跨进程读取）。
     */
    fun exportAfterLogin(context: Context): ExportResult {
        val cm = CookieManager.getInstance()
        val seen = HashMap<String, String>() // host|name -> value（深档优先）
        val importedNames = LinkedHashSet<String>()

        PROBES.forEach { probe ->
            val header = runCatching { cm.getCookie(probe.url) }.getOrNull()
                ?: return@forEach
            header.split(";").map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isBlank()) return@forEach
                val dedupeKey = "${probe.host}|$name"
                if (seen.containsKey(dedupeKey)) return@forEach // 深档先到，保留更具体路径的值
                seen[dedupeKey] = value

                val spec = CookieSpecs.resolve(probe.host, name, probe.path)
                if (name == "platformMultilingual") return@forEach
                val now = System.currentTimeMillis()
                SessionManager.cookieStore.put(
                    CookieRecord(
                        name = name,
                        value = value,
                        domain = spec.domain,
                        hostOnly = !spec.domain.startsWith("."),
                        path = spec.path,
                        expiresAt = Long.MAX_VALUE, // 外部凭据的真实过期时间由各系统 Cookie 决定；此处不臆造
                        secure = spec.httpsOnly,
                        httpOnly = CookieSpecs.isSensitive(name),
                        setAt = now,
                    )
                )
                importedNames += name
            }
        }

        val castgc = seen["authserver.sicnu.edu.cn|CASTGC"].orEmpty()
        val fingerprint = seen["authserver.sicnu.edu.cn|MULTIFACTOR_BROWSER_FINGERPRINT"].orEmpty()
        if (castgc.isNotBlank() || fingerprint.isNotBlank()) {
            CasSession.save(
                context,
                castgc.ifBlank { null },
                fingerprint.ifBlank { null },
            )
        }
        runCatching { cm.flush() }
        // CookieStore.put 不经过 CookieJar，必须显式落盘，否则冷启动会丢回灌的会话
        SessionManager.persistNow()
        return ExportResult(
            imported = importedNames.size,
            names = importedNames.toList(),
            castgcFound = castgc.isNotBlank(),
            fingerprintFound = fingerprint.isNotBlank(),
        )
    }

    /** 只把 CAS 域回推到 WebView（重验证前用，避免旧业务会话让 WebView 跳过 CAS） */
    fun pushCasCookies() {
        val cm = CookieManager.getInstance()
        CasSession.castgc()?.takeIf { it.isNotBlank() }?.let {
            cm.setCookie("https://authserver.sicnu.edu.cn/authserver/", "CASTGC=$it; Path=/authserver; Secure")
        }
        CasSession.fingerprint()?.takeIf { it.isNotBlank() }?.let {
            cm.setCookie(
                "https://authserver.sicnu.edu.cn/",
                "MULTIFACTOR_BROWSER_FINGERPRINT=$it; Path=/; Secure"
            )
        }
        runCatching { cm.flush() }
    }
}
