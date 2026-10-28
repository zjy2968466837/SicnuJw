package cn.edu.sicnu.scnujw.core.stats

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import cn.edu.sicnu.scnujw.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用统计上报（登录后一次/24 小时）。
 *
 * ## 只上传什么（改这段之前先读完）
 * 上传字段**只有**：学号前 4 位（年级前缀）+ 随机生成的匿名设备/账号标识 + 应用与系统版本 +
 * 厂商/机型/语言 + 本设备首次上报时间。见 [buildPayload]。
 *
 * **绝不上传**：完整学号、姓名、成绩、课表、Cookie/票据、任何页面内容、IMEI/序列号/广告 ID。
 * 完整学号在本机只用于缓存归属（`ScoreCache.profileCode`），本文件只对它调用 `take(4)`，
 * 不写入日志、不落盘、不进上报体。
 *
 * ## 关于「同账号多设备只算一次」
 * 这条**在只上传前 4 位的前提下做不到**，服务端只能按（设备, 账号标识）去重：
 * 同一台设备上同一账号重复上报只更新一行；换设备会算成两台设备。
 * 想跨设备合并就必须让服务端拿到可关联账号的标识，而学号只有 10 位、可枚举，
 * 任何哈希都能被穷举还原——所以本实现**不**做“把学号哈希后上传”这种假匿名。
 * 统计口径因此在服务端是「设备数」而不是「学生数」，摘要接口里也如实标成 devices。
 *
 * ## 加密
 * - 传输：HTTPS（复用更新站点的 8443 端口，nginx `/v1/stats`）。明文 HTTP 一律不发。
 * - 本地：设备标识/账号标识用 Android Keystore AES-GCM 加密后存 SharedPreferences。
 * - 服务端：落库前把两个标识换成 `HMAC-SHA256(服务端 pepper)`，数据库里没有原始标识。
 * - 不做客户端“应用层 AES”：客户端会开源，内嵌密钥等于公开，只会制造安全错觉。
 *
 * ## 开关
 * 「我的 → 诊断日志」页里有「参与使用统计」开关（默认开）；关闭后不再发送任何请求。
 */
object StatsReporter {
    private const val TAG = "Stats"
    private const val PREFS = "scnu_stats"
    private const val KEY_STATS_ENABLED = "stats_enabled"
    private const val KEY_LAST_SENT = "last_sent_"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val SCHEMA = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * 同一前缀的并发上报闸门。
     *
     * 为什么需要：`onAccountKnown` 在一次登录里会被调用多次（缓存回填 + 在线拉取 + 学工身份刷新），
     * 而 24h 节流的时间戳是「发送成功后才写」，多个协程同时读到空值就会一起发出去
     * （2026-10-28 真机实测：一次登录上报 3 次，服务端 reports 2→5）。
     * 进程内先抢占，抢不到的直接放弃；跨进程靠 prefs 时间戳。
     */
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val client by lazy {
        // 独立 client：不带 CookieJar，绝不把教务 Cookie 发到统计服务
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STATS_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_STATS_ENABLED, enabled).apply()
    }

    /**
     * 账号身份确认时调用（`ScoreCache.onAccountKnown` 是唯一入口，
     * 登录成功、换票回填、学生卡加载都会经过它）。
     *
     * 这里只取 `take(4)`；`studentCode` 的其余部分不会离开这个函数。
     */
    fun onAccountSeen(ctx: Context, studentCode: String) {
        val app = ctx.applicationContext
        if (!isEnabled(app)) return
        val prefix = studentCode.trim().take(4)
        // 只接受 4 位数字（学号前缀）；否则直接放弃，不做任何兜底上传
        if (prefix.length != 4 || prefix.any { it !in '0'..'9' }) return

        scope.launch {
            // 进程内先抢占：同一次登录里并发的多次 onAccountKnown 只允许一个真的发出去
            if (!inflight.add(prefix)) return@launch
            try {
                runCatching {
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    if (now - prefs.getLong(KEY_LAST_SENT + prefix, 0L) < INTERVAL_MS) return@runCatching
                    val endpoint = endpoint() ?: return@runCatching

                    val deviceId = StatsIdentity.getOrCreate(app, "device_id")
                    val accountRef = StatsIdentity.getOrCreate(app, "account_$prefix")
                    val firstSeen = StatsIdentity.get(app, "first_seen")
                        ?: now.toString().also { StatsIdentity.put(app, "first_seen", it) }

                    val body = buildPayload(prefix, deviceId, accountRef, firstSeen.toLongOrNull() ?: now)
                    if (post(endpoint, body)) {
                        prefs.edit().putLong(KEY_LAST_SENT + prefix, now).apply()
                    }
                }.onFailure { Log.d(TAG, "统计上报未完成: ${it.javaClass.simpleName}") }
            } finally {
                inflight.remove(prefix)
            }
        }
    }

    /** 统计接口地址从更新清单地址推导：同 host、同端口、同证书，避免第二套配置 */
    internal fun endpoint(): String? {
        val base = BuildConfig.UPDATE_MANIFEST_URL.trim()
        if (!base.startsWith("https://")) return null   // 只走 TLS
        val cut = base.lastIndexOf('/')
        if (cut <= "https://".length) return null
        return base.substring(0, cut) + "/v1/stats"
    }

    /** 上报体：字段即白名单，新增字段前先确认它不含任何个人身份信息 */
    internal fun buildPayload(
        prefix4: String,
        deviceId: String,
        accountRef: String,
        firstSeenAt: Long,
    ): String = JSONObject()
        .put("v", SCHEMA)
        .put("prefix4", prefix4)
        .put("deviceId", deviceId)
        .put("accountRef", accountRef)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("manufacturer", Build.MANUFACTURER.take(32))
        .put("model", Build.MODEL.take(48))
        .put("locale", Locale.getDefault().toLanguageTag())
        .put("firstSeenAt", firstSeenAt)
        .toString()

    private fun post(url: String, json: String): Boolean = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", "SicnuJw-Stats/${BuildConfig.VERSION_NAME}")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp -> resp.isSuccessful }
    }.getOrDefault(false)
}

/**
 * 设备/账号匿名标识：随机 UUID，Keystore AES-GCM 加密后存 prefs。
 *
 * 注意：这里存的是**随机数**，与学号无关、不可反推；加密是纵深防御（防止本机其它应用读取）。
 * 换机/清数据会重新生成 → 统计里会多一台设备，这是预期（宁可多算设备，也不引入账号关联）。
 * Keystore 不可用时退回明文 prefs（值本身仍是随机 UUID），并只记一条 debug 日志。
 */
private object StatsIdentity {
    private const val ALIAS = "scnujw_stats_v1"
    private const val PREFS = "scnu_stats_secure"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun get(ctx: Context, key: String): String? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return decrypt(raw) ?: raw.takeIf { !it.startsWith("enc:") }
    }

    fun put(ctx: Context, key: String, value: String) {
        val stored = encrypt(value)?.let { "enc:$it" } ?: value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, stored).apply()
    }

    fun getOrCreate(ctx: Context, key: String): String {
        get(ctx, key)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        put(ctx, key, fresh)
        return fresh
    }

    private fun decrypt(stored: String): String? = runCatching {
        if (!stored.startsWith("enc:")) return null
        val bytes = Base64.decode(stored.removePrefix("enc:"), Base64.NO_WRAP)
        if (bytes.size <= IV_LEN) return null
        val iv = bytes.copyOfRange(0, IV_LEN)
        val ct = bytes.copyOfRange(IV_LEN, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance("AES", KEYSTORE)
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

}
