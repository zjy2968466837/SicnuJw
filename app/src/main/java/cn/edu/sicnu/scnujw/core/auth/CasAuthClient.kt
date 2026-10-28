package cn.edu.sicnu.scnujw.core.auth

import cn.edu.sicnu.scnujw.core.crypto.CasCipher
import cn.edu.sicnu.scnujw.core.net.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

/** 登录链路结果 */
sealed class AuthResult {
    /** 账密通过但被 MFA 拦截，需 WebView 打开该官方页完成验证 */
    data class NeedMfa(val mfaUrl: String) : AuthResult()
    /** 全链路完成，落在教务主页 */
    data class Success(val sid: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/**
 * 川师大统一认证（金智 CAS）账密登录。
 * 链路（实测）：GET login 取 salt/execution → POST 加密密码 →
 * 302 reAuthCheck(强制MFA) 或 302 ticket → CasLogin.aspx → Index.aspx?sid=...
 */
object CasAuthClient {
    private const val AUTH = "https://authserver.sicnu.edu.cn/authserver"
    private const val SERVICE = "http://202.115.194.60/CasLogin.aspx"
    private const val JW_INDEX = "http://202.115.194.60/Index.aspx"
    private val SID_PAT = Pattern.compile("[Ii]ndex\\.aspx\\?sid=([0-9A-F]+)")

    suspend fun login(username: String, password: String): AuthResult =
        withContext(Dispatchers.IO) {
            try {
                val page = SessionManager.client
                    .newCall(Request.Builder().url("$AUTH/login?service=$SERVICE").build())
                    .execute().use { it.body?.string() ?: return@withContext AuthResult.Error("登录页为空") }
                val doc = Jsoup.parse(page)
                val salt = doc.selectFirst("#pwdEncryptSalt")?.attr("value")
                    ?: return@withContext AuthResult.Error("未取到 pwdEncryptSalt（页面结构变化？）")
                val execution = doc.selectFirst("input[name=execution]")?.attr("value") ?: ""
                if (doc.selectFirst("#captcha")?.attr("style")?.contains("display: none") == false) {
                    return@withContext AuthResult.Error("触发图形验证码，请稍后重试或在官方页面登录")
                }

                val form = FormBody.Builder()
                    .add("username", username)
                    .add("password", CasCipher.encrypt(password, salt))
                    .add("_eventId", "submit")
                    .add("cllt", "userNameLogin")
                    .add("dllt", "generalLogin")
                    .add("lt", "")
                    .add("execution", execution)
                    .build()
                val resp = SessionManager.client
                    .newCall(Request.Builder().url("$AUTH/login?service=$SERVICE").post(form).build())
                    .execute()

                val loc = resp.header("Location")
                when {
                    resp.code in 301..302 && loc != null -> when {
                        loc.contains("reAuthCheck") -> AuthResult.NeedMfa(loc)
                        loc.contains("ticket=") -> followTicket(loc)
                        else -> AuthResult.Error("意外跳转: $loc")
                    }
                    else -> {
                        val err = Jsoup.parse(resp.body?.string() ?: "")
                            .selectFirst("#showErrorTip span")?.text()
                        AuthResult.Error(err ?: "登录失败(${resp.code})")
                    }
                }
            } catch (e: Exception) {
                AuthResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }

    /** 跟随 ticket 链直至 Index.aspx?sid= */
    private fun followTicket(ticketUrl: String): AuthResult {
        var url: String = ticketUrl
        repeat(8) {
            val resp = SessionManager.client
                .newCall(Request.Builder().url(url).build()).execute()
            val finalUrl = resp.request.url.toString()
            val m = SID_PAT.matcher(finalUrl)
            if (m.find()) {
                val sid = m.group(1)!!
                SessionManager.onLoginSuccess(sid)
                return AuthResult.Success(sid)
            }
            val loc = resp.header("Location")
                ?: return AuthResult.Error("链路中断于 $finalUrl")
            url = if (loc.startsWith("http")) loc
            else resp.request.url.resolve(loc)?.toString() ?: return AuthResult.Error("非法跳转: $loc")
        }
        return AuthResult.Error("跳转链过深")
    }

    /** 会话存活探测：教务带 sid 直连 200 即活，302→error.aspx 即失效 */
    suspend fun isSessionAlive(): Boolean = withContext(Dispatchers.IO) {
        val sid = SessionManager.sid ?: return@withContext false
        try {
            val req = Request.Builder().url("$JW_INDEX?sid=$sid").build()
            SessionManager.client.newCall(req).execute().use { it.code == 200 }
        } catch (_: Exception) { false }
    }
}
