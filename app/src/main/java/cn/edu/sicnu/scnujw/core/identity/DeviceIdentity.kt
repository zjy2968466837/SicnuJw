package cn.edu.sicnu.scnujw.core.identity

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 匿名设备/账号标识：随机 UUID，Keystore AES-GCM 加密后存 prefs。
 *
 * ## 这是什么、不是什么
 * - 存的是**随机数**，与学号无关、不可反推；加密是纵深防御（防止本机其它应用读取）。
 * - 换机/清数据会重新生成 → 统计里会多一台设备，这是预期（宁可多算设备，也不引入账号关联）。
 * - 它不是硬件标识（不用 ANDROID_ID / IMEI / 序列号），也不是登录凭据；
 *   更新票据只把它当作「同一安装」的限额键，不证明用户身份。
 *
 * Keystore 不可用时退回明文 prefs（值本身仍是随机 UUID），并只记一条 debug 日志。
 *
 * 2026-10-29 从 `StatsReporter` 的私有对象抽出来：统计与更新取票需要同一个 device_id，
 * 两处必须用同一份存储（`scnu_stats_secure` / 别名 `scnujw_stats_v1`），
 * **改这里的 prefs 名或别名等于给所有老用户生成新设备号**。
 */
object DeviceIdentity {
    private const val ALIAS = "scnujw_stats_v1"
    private const val PREFS = "scnu_stats_secure"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 更新取票用的稳定设备号；首次调用时生成，之后不再变。 */
    fun deviceId(ctx: Context): String = getOrCreate(ctx, "device_id")

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
    }.getOrNull() ?: run {
        Log.d("DeviceIdentity", "Keystore 不可用，退回明文 prefs（值仍是随机 UUID）")
        null
    }

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
