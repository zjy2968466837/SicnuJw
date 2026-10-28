package cn.edu.sicnu.scnujw.core.diag

import android.content.Context
import android.webkit.WebView
import cn.edu.sicnu.scnujw.BuildConfig
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 诊断日志：记录「发到解析器手里的原始响应」与关键事件，用于排查页面结构变化与登录/网络故障。
 *
 * ## 为什么要有
 * 教务页是服务端渲染 + 回发，解析失败时用户只能看到「读取失败」，我们看不到页面长什么样；
 * 登录失败、网络失败、崩溃更是只剩一句「暂时读不到」。记录响应 + 事件后，导出成文本就能离线复现问题。
 *
 * ## 为什么默认开启
 * 排查依赖「故障发生时那一次」的现场，事后让用户去设置里打开已经晚了（故障不可复现）。
 * 默认开启的正当性由下面几条共同保证，缺一条都不成立：
 * - **只落本机**：`files/diag/`（应用私有目录），不联网、不上传；只有用户点「导出」才写出文件。
 * - **写入前脱敏**：本人学号/姓名按精确值替换，票据/Cookie/密码参数、身份证号、手机号、
 *   邮箱、10 位学号全部打码（见 [sanitize]）；导出时再脱敏一次（纵深防御）。
 * - **用户可随时关**：设置页开关仍然保留，关掉后连 WebView 的 JS 都不注入。
 * - **禁区不抓**：`zhxg.sicnu.edu.cn` 整域禁止（含假期去向登记），见 [isBlocked]。
 * - **体积与留存可控**：单文件上限 8 MB、超出轮转，随时可清空，卸载即删。
 *
 * ## 线程
 * 抓取发生在 OkHttp 回调线程 / WebView 线程，写入统一丢到单线程池，调用方零阻塞；
 * 任何异常都吞掉（诊断功能不能影响业务）。唯一例外是崩溃事件：进程马上会被杀，
 * 异步排队会丢，见 [recordEvent] 的 `awaitWrite`。
 */
object Diag {
    private const val PREFS = "scnu_diag"
    private const val KEY_ENABLED = "capture"
    private const val DIR = "diag"
    private const val FILE = "capture.jsonl"
    private const val ROTATED = "capture.1.jsonl"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_BODY_CHARS = 200_000
    private const val MAX_WEB_BODY_CHARS = 400_000
    private const val MAX_EVENT_CHARS = 2_000

    /**
     * 抓取默认开启：排查看的是「故障发生那一次」的现场，事后开关已经错过。
     * 安全性由「本机私有目录 + 写入/导出双重脱敏 + 不联网 + 可随时关闭 + zhxg 整域禁抓」保证，
     * 见文件头注释。用户显式关掉后写 false，一律以用户选择为准。
     */
    private const val DEFAULT_ENABLED = true

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-io").apply { isDaemon = true }
    }
    @Volatile private var lastAt = 0L
    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun isEnabled(ctx: Context? = null): Boolean {
        val c = ctx?.applicationContext ?: appCtx ?: return false
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) init(ctx)
    }

    data class Stats(val entries: Long, val bytes: Long, val enabled: Boolean, val lastAt: Long)

    fun stats(ctx: Context): Stats {
        val f = file(ctx)
        val rotated = File(f.parentFile, ROTATED)
        return Stats(
            entries = lineCount(f) + lineCount(rotated),
            bytes = (if (f.isFile) f.length() else 0L) + (if (rotated.isFile) rotated.length() else 0L),
            enabled = isEnabled(ctx),
            lastAt = lastAt,
        )
    }

    private fun lineCount(f: File): Long =
        if (!f.isFile) 0L
        else runCatching { f.useLines { seq -> seq.count().toLong() } }.getOrDefault(0L)

    /** JwClient 等 OkHttp 响应入口。url 里的 sid/ticket 会一起脱敏。 */
    fun recordHttp(url: String, status: Int, body: String?, contentType: String? = null) {
        record("http", url, status, contentType, body, MAX_BODY_CHARS)
    }

    /**
     * WebView 主框架页：等 DOM 稳定后取 outerHTML。
     * 只在开启抓取时注入 JS，避免影响正常渲染。
     */
    fun captureWebView(view: WebView, url: String) {
        if (!isEnabled() || !url.startsWith("http")) return
        if (isBlocked(url)) return
        try {
            view.postDelayed({
                if (view.url != url) return@postDelayed
                runCatching {
                    view.evaluateJavascript(
                        "(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()",
                    ) { raw ->
                        val html = decodeJsString(raw) ?: return@evaluateJavascript
                        if (html.length < 200) return@evaluateJavascript
                        record("webview", url, 200, "text/html", html, MAX_WEB_BODY_CHARS)
                    }
                }
            }, 800)
        } catch (_: Throwable) {
        }
    }

    private fun record(
        source: String,
        url: String,
        status: Int,
        contentType: String?,
        body: String?,
        maxChars: Int,
    ) {
        val ctx = appCtx ?: return
        if (!isEnabled(ctx)) return
        // 硬性禁止：学工（含假期去向登记）整域不抓。用户明确要求，别改成“只抓部分路径”。
        if (isBlocked(url)) return
        val text = body ?: ""
        val truncated = text.length > maxChars
        val kept = if (truncated) text.take(maxChars) else text
        val safeUrl = sanitize(ctx, url)
        val safeBody = sanitize(ctx, kept)
        val obj = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("src", source)
            .put("url", safeUrl)
            .put("status", status)
            .put("ctype", contentType ?: "")
            .put("chars", text.length)
            .put("truncated", truncated)
            .put("body", safeBody)
        lastAt = System.currentTimeMillis()
        append(ctx, obj)
    }

    // ─────────────────────────── 事件记录 ───────────────────────────

    /**
     * 事件型记录：登录节点（auth）、解析失败（parse）、网络失败（net）、崩溃（crash）。
     *
     * 与 [recordHttp]/[captureWebView] 的区别：没有 URL 与正文，只有一句人能直接读的摘要。
     * [detail] 一律先脱敏再落盘（调用方仍然可以放心把异常摘要、页面路径放进来，
     * 但**永远不要**主动拼学号/票据/密码——脱敏是兜底，不是许可）。
     *
     * 本函数自身吞掉所有异常，调用方不需要 try，也不该因为诊断失败影响业务。
     *
     * @param awaitWrite 崩溃路径专用：进程在 uncaught handler 返回后马上被杀，
     *   异步排队大概率丢；置 true 时同步等一次写入（最多 2 秒）再返回。
     */
    fun recordEvent(type: String, detail: String, awaitWrite: Boolean = false) {
        runCatching {
            val ctx = appCtx ?: return
            if (!isEnabled(ctx)) return
            // 值本身没意义，但万一 detail 里带了禁抓域（例如异常消息回显了 URL），一并丢弃
            if (isBlocked(detail)) return
            val safe = sanitize(ctx, detail).take(MAX_EVENT_CHARS)
            val obj = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("src", "event")
                .put("type", type)
                .put("detail", safe)
            lastAt = System.currentTimeMillis()
            append(ctx, obj, awaitWrite)
        }
    }

    /** 追加一行：轮转规则只有这一处，HTTP/WebView/事件共用 */
    private fun append(ctx: Context, obj: JSONObject, awaitWrite: Boolean = false) {
        val line = obj.toString()
        val task = Runnable {
            runCatching {
                val f = file(ctx)
                if (f.isFile && f.length() > MAX_FILE_BYTES) {
                    val old = File(f.parentFile, ROTATED)
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line + "\n")
            }
        }
        if (!awaitWrite) {
            io.execute(task)
            return
        }
        runCatching { io.submit(task).get(2, TimeUnit.SECONDS) }
    }

    /** 清空捕获文件。同步删除（只有两个小文件），调用方负责放到 IO 线程。 */
    fun clear(ctx: Context) {
        runCatching {
            file(ctx).delete()
            File(file(ctx).parentFile, ROTATED).delete()
            lastAt = 0L
        }
    }

    /** 导出为纯文本：头部写清版本/设备/脱敏规则，后面逐条正文 */
    fun export(ctx: Context, out: OutputStream) {
        out.write(buildHeader(ctx).toByteArray(Charsets.UTF_8))
        listOf(File(file(ctx).parentFile, ROTATED), file(ctx)).forEach { f ->
            if (!f.isFile) return@forEach
            f.forEachLine { line ->
                if (!line.startsWith("{")) return@forEachLine
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val ts = o.optLong("ts", 0L)
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
                // 二次脱敏（纵深防御）：记录时已脱敏一次，导出再跑一次，
                // 防止将来新增的捕获路径漏了脱敏、或旧文件是旧版本写的。
                val src = o.optString("src")
                if (src == "event") {
                    // 事件行只有一句摘要；同样过一遍 sanitize
                    val block = buildString {
                        append("\n===== ").append(time)
                        append("  event  ").append(o.optString("type"))
                        append(" =====\n")
                        append(sanitize(ctx, o.optString("detail")))
                        if (!endsWith("\n")) append('\n')
                    }
                    out.write(block.toByteArray(Charsets.UTF_8))
                    return@forEachLine
                }
                val url = sanitize(ctx, o.optString("url"))
                val body = sanitize(ctx, o.optString("body"))
                val block = buildString {
                    append("\n===== ").append(time)
                    append("  ").append(src)
                    append("  HTTP ").append(o.optInt("status"))
                    append("  ").append(o.optString("ctype"))
                    append("  ").append(o.optInt("chars")).append(" chars")
                    if (o.optBoolean("truncated")) append(" (truncated)")
                    append(" =====\n")
                    append(url).append('\n')
                    append(body)
                    if (!endsWith("\n")) append('\n')
                }
                out.write(block.toByteArray(Charsets.UTF_8))
            }
        }
        out.flush()
    }

    private fun buildHeader(ctx: Context): String = buildString {
        append("# SicnuJw 诊断日志\n")
        append("# 导出时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        append("# 应用版本: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("# 设备: ").append(android.os.Build.MANUFACTURER).append(' ')
        append(android.os.Build.MODEL).append(" / Android ").append(android.os.Build.VERSION.RELEASE)
        append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        val s = stats(ctx)
        append("# 记录条数: ").append(s.entries).append("，占用: ").append(s.bytes / 1024).append(" KB\n")
        append("# 说明: 已按规则脱敏——本人学号/姓名按精确值替换，CASTGC/会话 Cookie/票据参数、\n")
        append("#       密码参数、身份证号、手机号、邮箱、10 位学号全部替换为占位符。\n")
        append("#       公开分享前请再自行过目；页面里可能仍有课程名/教师名等校内信息。\n")
        append("# 记录类型: http/webview = 页面原文；event = 关键事件（auth 登录 / parse 解析失败 /\n")
        append("#           net 网络失败 / crash 崩溃摘要），detail 只有一句脱敏后的描述。\n")
        append("#\n")
        append("# 排查用法: 找到对应的 url + body，核对表格列头/字段名与解析器 (core/jw/*Parser.kt) 的取值；\n")
        append("#           先看 crash/net 定位环境问题，再看 parse 附近的 http 记录核对页面结构。\n\n")
    }

    private fun file(ctx: Context): File {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        return File(dir, Diag.FILE)
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    // ─────────────────────────── 脱敏 ───────────────────────────

    private val RE_TICKET = Regex(
        "(?i)\\b(CASTGC|JSESSIONID|SESSION|__pstsid__|srv_id|ticket|tgc|st)(\\s*[=:]\\s*)([^;&\\s\"'<>]*)",
    )
    private val RE_PASSWORD = Regex("(?i)\\b(password|pwd|passwd)(\\s*=\\s*)([^&\\s\"'<>]*)")
    // 表单里的密码值不在 k=v 形式里：<input name=password value=xxx>
    private val RE_INPUT_TAG = Regex("(?i)<input\\b[^>]*>")
    private val RE_INPUT_VALUE = Regex("(?i)(value\\s*=\\s*)([\"']?)([^\"'>]*)(\\2)")
    private val RE_SID_QUERY = Regex("(?i)([?&]sid=)([^&\\s\"'<>]*)")
    private val RE_ID_10 = Regex("(?<!\\d)20\\d{8}(?!\\d)")
    private val RE_ID_18 = Regex("(?<!\\d)\\d{17}[\\dXx](?!\\d)")
    private val RE_PHONE = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val RE_EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")

    /** 精确值（本人学号/姓名）缓存 60 秒，避免每条都去读一次缓存 */
    @Volatile private var idCacheAt = 0L
    @Volatile private var mySid: String? = null
    @Volatile private var myName: String? = null

    private fun identity(ctx: Context): Pair<String?, String?> {
        val now = System.currentTimeMillis()
        if (now - idCacheAt > 60_000L) {
            mySid = runCatching { ScoreCache.profileCode(ctx) }.getOrNull()
            myName = runCatching { ScoreCache.profileName(ctx) }.getOrNull()
                ?.takeIf { it.length >= 2 }
            idCacheAt = now
        }
        return mySid to myName
    }

    internal fun sanitize(ctx: Context, text: String): String {
        if (text.isEmpty()) return text
        val (sid, name) = identity(ctx)
        return sanitizeText(text, sid, name)
    }

    /**
     * 纯函数版脱敏（便于单元测试；不依赖 Context）。
     * [sid]/[name] 是本人学号与姓名，用来做精确替换。
     */
    internal fun sanitizeText(text: String, sid: String?, name: String?): String {
        if (text.isEmpty()) return text
        var s = text
        // 参数类先脱敏（占位符本身以 < 开头，正则不会再匹配，保证幂等——导出时会再跑一遍）
        s = RE_SID_QUERY.replace(s) { m ->
            if (m.groupValues[2].isEmpty()) m.value else "${m.groupValues[1]}<SID>"
        }
        s = RE_TICKET.replace(s) { m ->
            if (m.groupValues[3].isEmpty()) m.value else "${m.groupValues[1]}${m.groupValues[2]}<REDACTED>"
        }
        s = RE_PASSWORD.replace(s) { m ->
            if (m.groupValues[3].isEmpty()) m.value else "${m.groupValues[1]}${m.groupValues[2]}<REDACTED>"
        }
        // 密码输入框：整段 <input> 里只要出现 password/passwd/pwd，就把 value 换掉
        // 用户名输入框：CAS 登录页的 username 值就是学号——RE_ID_10 只兜得住 20 开头的
        // 十位数，兜不住非标准号（研究生等），且明文留在 HTML 里没有排查价值，一并打码
        s = RE_INPUT_TAG.replace(s) { m ->
            val tag = m.value
            val sensitive = tag.contains("password", true) || tag.contains("passwd", true) ||
                tag.contains("pwd", true) || tag.contains("username", true) ||
                tag.contains("user", true) || tag.contains("account", true)
            if (!sensitive) {
                tag
            } else {
                RE_INPUT_VALUE.replace(tag) { v ->
                    if (v.groupValues[3].isEmpty()) v.value
                    else "${v.groupValues[1]}${v.groupValues[2]}<REDACTED>${v.groupValues[4]}"
                }
            }
        }
        // 精确值：姓名没有固定格式，只能按本人缓存值替换
        if (!name.isNullOrBlank()) s = s.replace(name, "<NAME>")
        if (!sid.isNullOrBlank()) s = s.replace(sid, "<SID>")
        s = RE_ID_18.replace(s, "<ID18>")
        s = RE_PHONE.replace(s, "<PHONE>")
        s = RE_EMAIL.replace(s, "<EMAIL>")
        // 10 位学号放最后：前面已经把本人学号换成 <SID>，这里兜住其它人的学号
        s = RE_ID_10.replace(s, "<SID10>")
        return s
    }

    /**
     * 抓取黑名单：学工域（`zhxg.sicnu.edu.cn`）整域禁止，包含假期去向登记。
     *
     * 这是产品要求，不是“恰好没抓到”：去向登记涉及离校/留校/返校等行踪信息，
     * 即便用户开了诊断日志也不应落盘。新增其它禁止抓取的域时，直接加到这里。
     */
    internal fun isBlocked(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        return u.contains("zhxg.sicnu.edu.cn")
    }
}
