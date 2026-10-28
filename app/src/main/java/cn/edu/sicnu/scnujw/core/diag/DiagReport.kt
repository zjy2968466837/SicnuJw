package cn.edu.sicnu.scnujw.core.diag

import android.content.Context
import android.os.Build
import android.util.Log
import cn.edu.sicnu.scnujw.BuildConfig
import cn.edu.sicnu.scnujw.core.identity.DeviceIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「发送诊断」：用户主动点按钮时，把**已脱敏**的诊断文本 + 文字说明发给统计服务器。
 *
 * ## 边界（改之前先读）
 * - **只在用户明确点击时发送**，绝不自动上传；界面弹窗写明会发送什么。
 * - 文本与「导出日志」同源、同样二次脱敏（[Diag.buildReportText]），默认最多 512 KB
 *   （保留最近记录，8 MB 轮转文件不整包上传）。
 * - 说明文字也过一遍 sanitize（用户可能把学号/姓名写进去）。
 * - 服务器按设备 3 次/小时、按 IP 5 次/小时限流，超限 429；本地再挡 60 秒防连点。
 * - 服务器只存 HMAC(deviceId)，不存 IP、不存原始设备号；日志内容仅用于排查。
 */
object DiagReport {
    private const val TAG = "DiagReport"
    private const val MAX_TEXT_CHARS = 2000
    private const val LOCAL_COOLDOWN_MS = 60_000L
    private const val PREFS = "scnu_diag_report"
    private const val KEY_LAST_AT = "last_sent_at"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 公开构建没配统计服务器时不显示「发送」入口 */
    fun isConfigured(): Boolean = endpoint() != null

    /** /v1/stats → /v1/report；地址来自同一个 BuildConfig，不新增配置项 */
    internal fun endpoint(): String? {
        val stats = BuildConfig.STATS_ENDPOINT.trim()
        if (!stats.startsWith("https://")) return null
        val base = stats.substringBefore("/v1/", missingDelimiterValue = "")
        return if (base.isBlank()) null else "$base/v1/report"
    }

    /** 60 秒内重复点按直接拒绝（服务器还有更严格的小时级限流） */
    fun onCooldown(ctx: Context): Boolean {
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AT, 0L)
        return System.currentTimeMillis() - last < LOCAL_COOLDOWN_MS
    }

    /** 上报体字段白名单：不含学号/姓名/账号，只有设备随机号、版本、机型与用户描述 */
    internal fun buildPayload(ctx: Context, description: String, log: String): String {
        val safeText = Diag.sanitize(ctx, description.trim()).take(MAX_TEXT_CHARS)
        return JSONObject()
            .put("v", 1)
            .put("deviceId", DeviceIdentity.deviceId(ctx))
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER.take(32))
            .put("model", Build.MODEL.take(48))
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("description", safeText)
            .put("log", log)
            .toString()
    }

    /**
     * 发送一次诊断。返回 null=成功，否则是一句可展示给用户的原因。
     * 网络/服务器异常不抛出：调用方（诊断页）只负责 Toast。
     */
    fun send(ctx: Context, description: String): String? {
        val url = endpoint() ?: return "当前构建未配置反馈服务器"
        if (onCooldown(ctx)) return "刚刚已经发送过一次，请稍后再试"
        val log = runCatching { Diag.buildReportText(ctx) }.getOrNull()
            ?: return "读取诊断日志失败"
        if (log.isBlank()) return "暂无可发送的诊断记录"
        val json = runCatching { buildPayload(ctx, description, log) }.getOrNull()
            ?: return "整理诊断信息失败"
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "SicnuJw-Report/${BuildConfig.VERSION_NAME}")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
                        null
                    }
                    resp.code == 429 -> "发送太频繁，请稍后再试"
                    resp.code == 413 -> "诊断内容过大，请清空日志后重试"
                    else -> "发送失败（HTTP ${resp.code}）"
                }
            }
        }.getOrElse {
            Log.w(TAG, "发送失败: ${it.javaClass.simpleName}")
            "网络异常，发送失败"
        }
    }
}
