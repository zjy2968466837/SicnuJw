package cn.edu.sicnu.scnujw.core.net

import okhttp3.Cookie
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 全字段 Cookie 存储（`LOGIN_SSO_PLAN.md` Step 2）。
 *
 * 与旧实现的区别：
 *  - 键是 `domain|path|name`，同域同名的不同 Path 不再互相覆盖；
 *  - `expiresAt<=now` 只删除同键（CAS 的 `CASTGC=; Max-Age=0; path=/authserver/`
 *    不会再清掉真正挂在 `/authserver` 上的票根）；
 *  - 加载时先过滤过期，再按 RFC6265 匹配；同 domain+name 只发最长 path 的一条并记 DUPLICATE；
 *  - 持久化保留 path/expires/secure/httpOnly，冷启动可按原属性恢复。
 */
data class CookieRecord(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    /** Long.MAX_VALUE 表示会话 Cookie（无 Expires） */
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val setAt: Long,
) {
    fun toCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        // OkHttp 的 domain() 不接受前导点；域匹配语义由 hostOnly 表达
        .apply { if (hostOnly) hostOnlyDomain(domain.removePrefix(".")) else domain(domain.removePrefix(".")) }
        .path(path)
        .apply { if (expiresAt != Long.MAX_VALUE) expiresAt(expiresAt) }
        .apply { if (secure) secure() }
        .apply { if (httpOnly) httpOnly() }
        .build()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("value", value)
        put("domain", domain)
        put("hostOnly", hostOnly)
        put("path", path)
        put("expiresAt", expiresAt)
        put("secure", secure)
        put("httpOnly", httpOnly)
        put("setAt", setAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = CookieRecord(
            name = o.optString("name"),
            value = o.optString("value"),
            domain = o.optString("domain"),
            hostOnly = o.optBoolean("hostOnly", false),
            path = o.optString("path", "/"),
            expiresAt = o.optLong("expiresAt", Long.MAX_VALUE),
            secure = o.optBoolean("secure", false),
            httpOnly = o.optBoolean("httpOnly", false),
            setAt = o.optLong("setAt", 0L),
        )
    }
}

class CookieStore {
    private val map = ConcurrentHashMap<String, CookieRecord>()

    private fun key(domain: String, path: String, name: String) =
        "${domain.lowercase()}|$path|$name"

    fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { c ->
            val k = key(c.domain, c.path, c.name)
            if (c.expiresAt <= now) {
                // 只删同键：清 /authserver/ 不会动 /authserver
                map.remove(k)
            } else {
                map[k] = CookieRecord(
                    name = c.name,
                    value = c.value,
                    domain = c.domain,
                    hostOnly = c.hostOnly,
                    path = c.path,
                    expiresAt = c.expiresAt,
                    secure = c.secure,
                    httpOnly = c.httpOnly,
                    setAt = now,
                )
            }
        }
    }

    fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matched = map.values.filter { r ->
            r.expiresAt > now && (!r.secure || url.isHttps) && r.toCookie().matches(url)
        }
        if (matched.isEmpty()) return emptyList()
        // 同 domain+name 只发最长 path 的一条（RFC 未规定服务端取舍，这里选更具体的）
        val best = HashMap<String, CookieRecord>()
        matched.forEach { r ->
            val k = "${r.domain.lowercase()}|${r.name}"
            val cur = best[k]
            if (cur == null || r.path.length > cur.path.length) {
                if (cur != null) Log.duplicate(k, cur.path, r.path)
                best[k] = r
            }
        }
        return best.values.map { it.toCookie() }
    }

    fun put(record: CookieRecord) {
        // 统一去掉前导点：OkHttp 不接受，且我们按 hostOnly=false 表达域匹配
        val r = if (record.domain.startsWith("."))
            record.copy(domain = record.domain.removePrefix("."), hostOnly = false)
        else record
        if (r.value.isEmpty() && r.expiresAt <= System.currentTimeMillis()) {
            map.remove(key(r.domain, r.path, r.name))
        } else {
            map[key(r.domain, r.path, r.name)] = r
        }
    }

    fun remove(domain: String, path: String, name: String) {
        map.remove(key(domain, path, name))
    }

    fun snapshot(): List<CookieRecord> = map.values.toList()

    fun clearDomain(domain: String) {
        val d = domain.lowercase()
        map.keys.filter { it.startsWith("$d|") }.forEach { map.remove(it) }
    }

    fun clear() = map.clear()

    fun toJson(): String {
        val arr = JSONArray()
        map.values.sortedWith(compareBy({ it.domain }, { it.path }, { it.name }))
            .forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun fromJson(raw: String) {
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val r = CookieRecord.fromJson(arr.getJSONObject(i))
                if (r.name.isNotBlank() && r.domain.isNotBlank()) put(r)
            }
        }
    }

    /** 旧版 `cookies` 结构迁移：domain -> {name: value} */
    fun migrateLegacy(raw: String) {
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { domain ->
                val inner = obj.getJSONObject(domain)
                inner.keys().forEach { name ->
                    val spec = CookieSpecs.resolve(domain, name, "/")
                    put(
                        CookieRecord(
                            name = name,
                            value = inner.getString(name),
                            domain = spec.domain,
                            hostOnly = true,
                            path = spec.path,
                            expiresAt = Long.MAX_VALUE,
                            secure = spec.httpsOnly,
                            httpOnly = false,
                            setAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
    }

    private object Log {
        private val seen = HashSet<String>()
        fun duplicate(key: String, oldPath: String, newPath: String) {
            if (seen.add("$key|$oldPath->$newPath")) {
                android.util.Log.w("CookieStore", "DUPLICATE $key: 发送最长 path=$newPath（原 $oldPath）")
            }
        }
    }
}

/**
 * Cookie 属性归类（`LOGIN_SSO_PLAN.md` Step 2）。
 * 目的不是猜服务端，而是保证回灌时把 Cookie 落到它真正的 Path 上。
 */
object CookieSpecs {
    data class Spec(val domain: String, val path: String, val httpsOnly: Boolean)

    private val authserverPathSensitive = setOf("CASTGC", "route", "JSESSIONID")

    fun resolve(hostOrDomain: String, name: String, fallbackPath: String = "/"): Spec {
        val host = hostOrDomain.removePrefix(".").lowercase()
        return when {
            host.endsWith("authserver.sicnu.edu.cn") -> when (name) {
                in authserverPathSensitive ->
                    Spec("authserver.sicnu.edu.cn", "/authserver", true)
                "MULTIFACTOR_BROWSER_FINGERPRINT", "happyVoyage" ->
                    Spec("authserver.sicnu.edu.cn", "/", true)
                "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE" ->
                    Spec("authserver.sicnu.edu.cn", "/", false)
                "platformMultilingual" ->
                    Spec("authserver.sicnu.edu.cn", "/", false)
                else -> Spec(host, fallbackPath, false)
            }
            host == "jwcxk.sicnu.edu.cn" -> when (name) {
                "SESSION" -> Spec("jwcxk.sicnu.edu.cn", "/student/", true)
                "__pstsid__" -> Spec("jwcxk.sicnu.edu.cn", "/student", true)
                "srv_id" -> Spec("jwcxk.sicnu.edu.cn", "/manager", false)
                else -> Spec(host, fallbackPath, false)
            }
            host.endsWith("zhxg.sicnu.edu.cn") -> Spec("zhxg.sicnu.edu.cn", "/", true)
            host == "202.115.194.60" -> Spec(host, "/", false)
            else -> Spec(host, fallbackPath, false)
        }
    }

    /** 敏感 Cookie：不允许写日志全文、不允许走明文 */
    fun isSensitive(name: String): Boolean = name in setOf(
        "CASTGC", "MULTIFACTOR_BROWSER_FINGERPRINT", "SESSION", "__pstsid__",
        "_sop_session_", "ASP.NET_SessionId", "sid", "JSESSIONID", "route", "happyVoyage",
    )
}
