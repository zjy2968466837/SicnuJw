package cn.edu.sicnu.scnujw.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import cn.edu.sicnu.scnujw.core.net.CookieRecord
import cn.edu.sicnu.scnujw.core.net.SessionManager
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CAS 凭据层（`LOGIN_SSO_PLAN.md` Step 4）。
 *
 * 唯一真源：只保存 CASTGC 与 MULTIFACTOR_BROWSER_FINGERPRINT。
 * 它们不参与通用 Cookie 字典的 name 覆盖，因此 CAS 每次响应下发的
 * `CASTGC=; Max-Age=0; path=/authserver/` 杀不掉真票根。
 *
 * 加密：AndroidKeyStore AES/GCM，别名 scnujw_session_v1；Keystore 不可用时只留内存，不落盘。
 */
object CasSession {
    private const val PREFS = "scnu_session_secure"
    private const val KEY_BLOB = "cas_blob"
    private const val AUTH_HOST = "authserver.sicnu.edu.cn"
    private const val TGT_PATH = "/authserver"

    private const val TGT_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val FP_TTL_MS = 395L * 24 * 60 * 60 * 1000 // 约 13 个月

    @Volatile private var castgc: String? = null
    @Volatile private var fingerprint: String? = null
    private var capturedAt: Long = 0L
    private var assumedExpiresAt: Long = 0L
    @Volatile private var initialized = false

    fun load(context: Context) {
        initialized = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BLOB, null)
        if (raw != null) {
            val plain = SecureStore.decrypt(raw) ?: run {
                Log.w("CasSession", "凭据解密失败，按无票据处理")
                null
            }
            if (plain != null) {
                runCatching {
                    val o = JSONObject(plain)
                    castgc = o.optString("castgc").ifBlank { null }
                    fingerprint = o.optString("fingerprint").ifBlank { null }
                    capturedAt = o.optLong("capturedAt", 0L)
                    assumedExpiresAt = o.optLong("assumedExpiresAt", 0L)
                }.onFailure { Log.w("CasSession", "凭据解析失败: ${it.javaClass.simpleName}") }
            }
        }
        if (castgc.isNullOrBlank() && cn.edu.sicnu.scnujw.BuildConfig.DEBUG) {
            loadDebugBlob(context)
        }
    }

    /**
     * 仅 debug：从 filesDir/cas_debug.json 注入测试票据（设备验收用）。
     * release 构建不会执行；测试后应删除该文件。
     */
    private fun loadDebugBlob(context: Context) {
        runCatching {
            val f = java.io.File(context.filesDir, "cas_debug.json")
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val c = o.optString("castgc").ifBlank { null }
            val fp = o.optString("fingerprint").ifBlank { null }
            if (c == null && fp == null) return
            castgc = c
            fingerprint = fp
            capturedAt = o.optLong("capturedAt", System.currentTimeMillis())
            assumedExpiresAt = capturedAt + TGT_TTL_MS
            // 转存加密存储，便于设备验收 Keystore 路径（debug 文件随后可删除）
            persist(context)
            Log.i("CasSession", "debug 票据注入并转存 castgc=${c?.length ?: 0} fp=${fp?.length ?: 0}")
        }
    }

    fun save(context: Context, newCastgc: String?, newFingerprint: String?, at: Long = System.currentTimeMillis()) {
        if (!newCastgc.isNullOrBlank()) {
            castgc = newCastgc
            capturedAt = at
            assumedExpiresAt = at + TGT_TTL_MS
        }
        if (!newFingerprint.isNullOrBlank()) fingerprint = newFingerprint
        persist(context)
        injectIntoJar()
    }

    fun saveCastgc(context: Context, value: String?) {
        if (value.isNullOrBlank()) return
        save(context, value, null)
    }

    fun saveFingerprint(context: Context, value: String?) {
        if (value.isNullOrBlank()) return
        save(context, null, value)
    }

    fun clear(context: Context?) {
        castgc = null
        fingerprint = null
        capturedAt = 0L
        assumedExpiresAt = 0L
        if (context != null) {
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            }
            SecureStore.deleteKey()
        }
    }

    fun hasTgt(): Boolean = !castgc.isNullOrBlank() && !isExpired()

    fun castgc(): String? = castgc

    fun fingerprint(): String? = fingerprint

    fun capturedAt(): Long = capturedAt

    fun assumedExpiresAt(): Long = assumedExpiresAt

    private fun isExpired(): Boolean =
        assumedExpiresAt > 0 && System.currentTimeMillis() > assumedExpiresAt

    /**
     * 把 CAS 凭据注入 CookieStore。
     * 每次换票前调用一次即可；注入的是独立键（/authserver 与 /），
     * 与通用字典里可能出现的 `/authserver/` 空值是两条记录。
     */
    fun injectIntoJar() {
        val now = System.currentTimeMillis()
        castgc?.takeIf { it.isNotBlank() }?.let {
            SessionManager.cookieStore.put(
                CookieRecord(
                    name = "CASTGC",
                    value = it,
                    domain = AUTH_HOST,
                    hostOnly = true,
                    path = TGT_PATH,
                    expiresAt = if (assumedExpiresAt > now) assumedExpiresAt else now + TGT_TTL_MS,
                    secure = true,
                    httpOnly = true,
                    setAt = capturedAt,
                )
            )
        }
        fingerprint?.takeIf { it.isNotBlank() }?.let {
            SessionManager.cookieStore.put(
                CookieRecord(
                    name = "MULTIFACTOR_BROWSER_FINGERPRINT",
                    value = it,
                    domain = AUTH_HOST,
                    hostOnly = true,
                    path = "/",
                    expiresAt = now + FP_TTL_MS,
                    secure = true,
                    httpOnly = true,
                    setAt = capturedAt,
                )
            )
        }
    }

    private fun persist(context: Context) {
        if (castgc.isNullOrBlank() && fingerprint.isNullOrBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            return
        }
        val json = JSONObject().apply {
            put("castgc", castgc ?: "")
            put("fingerprint", fingerprint ?: "")
            put("capturedAt", capturedAt)
            put("assumedExpiresAt", assumedExpiresAt)
        }.toString()
        val blob = SecureStore.encrypt(json)
        if (blob == null) {
            Log.w("CasSession", "Keystore 不可用，凭据仅保存在内存")
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BLOB, blob).apply()
    }

    /** Keystore 可用性（诊断用） */
    fun isPersistentAvailable(): Boolean = SecureStore.available()
}

/** 账号身份：换票/落地后回填，用于发现换号串数据 */
data class AccountIdentity(
    val studentCode: String,
    val name: String,
    val source: String,
    val verifiedAt: Long,
)

object AccountIdentityStore {
    private const val PREFS = "scnu_identity"
    private const val KEY = "identity"

    fun load(context: Context): AccountIdentity? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            AccountIdentity(
                studentCode = o.optString("studentCode"),
                name = o.optString("name"),
                source = o.optString("source"),
                verifiedAt = o.optLong("verifiedAt"),
            )
        }.getOrNull()
    }

    /** @return true 表示账号发生了变化（调用方应清缓存） */
    fun save(context: Context, identity: AccountIdentity): Boolean {
        val old = load(context)
        val changed = old != null && old.studentCode.isNotBlank() &&
            identity.studentCode.isNotBlank() && old.studentCode != identity.studentCode
        val json = JSONObject().apply {
            put("studentCode", identity.studentCode)
            put("name", identity.name)
            put("source", identity.source)
            put("verifiedAt", identity.verifiedAt)
        }.toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
        return changed
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** AndroidKeyStore AES/GCM 封装；SessionManager 的 Cookie 落盘也复用它，避免同一凭据以明文另存一份 */
internal object SecureStore {
    private const val ALIAS = "scnujw_session_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun available(): Boolean = runCatching { key(); true }.getOrDefault(false)

    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(blob: String): String? = runCatching {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        if (bytes.size <= IV_LEN) return null
        val iv = bytes.copyOfRange(0, IV_LEN)
        val ct = bytes.copyOfRange(IV_LEN, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun deleteKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
