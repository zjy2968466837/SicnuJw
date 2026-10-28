package cn.edu.sicnu.scnujw.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 金智 CAS 密码加密（协议实测自 authserver 下发的 encrypt.js）：
 * AES-128-CBC，key = 页面 pwdEncryptSalt(16字节)，iv = 随机16字节(限定字符集)，
 * 明文 = 随机64串 + 密码，PKCS7 填充，输出 Base64。
 */
object CasCipher {
    private val CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678".toCharArray()
    private val rng = SecureRandom()

    fun randomString(n: Int): String = buildString {
        repeat(n) { append(CHARS[rng.nextInt(CHARS.size)]) }
    }

    fun encrypt(password: String, salt: String): String {
        val key = SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec(randomString(16).toByteArray(Charsets.UTF_8))
        val plain = (randomString(64) + password).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // JDK 实现即 PKCS7
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        return android.util.Base64.encodeToString(cipher.doFinal(plain), android.util.Base64.NO_WRAP)
    }
}
