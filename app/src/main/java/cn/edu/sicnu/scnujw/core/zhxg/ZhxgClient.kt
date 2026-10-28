package cn.edu.sicnu.scnujw.core.zhxg

import cn.edu.sicnu.scnujw.core.auth.AuthGuard
import cn.edu.sicnu.scnujw.core.auth.AuthSystem
import cn.edu.sicnu.scnujw.core.auth.CasTicketClient
import cn.edu.sicnu.scnujw.core.net.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.LocalDate

/**
 * 智慧学工（zhxg.sicnu.edu.cn，金智「学生通」）客户端。
 *
 * 会话模型（2026-10-26 实测，见 LOGIN_SSO_PLAN.md §2）：
 *  - zhxg 与教务是不同 CAS service，下游会话不能互相顶替；
 *  - 但同一份 CASTGC + MULTIFACTOR_BROWSER_FINGERPRINT 可为 zhxg 静默换 ST；
 *  - 换票/落地/复验统一交给 [AuthGuard]，本类只负责业务请求与失效识别。
 *
 * 接口信封统一为 { success, resultCode, errorMsg, result: { data, total } }（HolidayParser 解包）。
 */
object ZhxgClient {
    private const val BASE = "https://zhxg.sicnu.edu.cn"

    /** 兼容旧调用点：真正的判定已上移到 AuthGuard.InteractiveAuthRequired */
    class NotLoggedInException : Exception("zhxg 会话缺失，请重新登录")

    /** 登出/换号后把该域标脏，下次请求会重新换票 */
    fun reset() = AuthGuard.markDead(AuthSystem.ZHGX)

    private suspend fun httpGet(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        SessionManager.client.newCall(
            Request.Builder().url(url).header("User-Agent", CasTicketClient.UA).build()
        ).execute().use { r -> r.code to (r.body?.string() ?: "") }
    }

    /**
     * 统一入口：先 ensure（可能静默换票），401/403/301 判失效并重跑一次。
     * 301 是匿名访问 /edu/api/ 下接口的实际表现（见 AUTH_STATES.md §389 记录）。
     */
    private suspend fun get(path: String): String = AuthGuard.require(AuthSystem.ZHGX) {
        val (code, body) = httpGet("$BASE$path")
        if (code == 301 || code in 401..403) {
            throw AuthGuard.AuthExpiredException(AuthSystem.ZHGX, "zhxg 会话失效（HTTP $code）")
        }
        check(code == 200) { "zhxg 请求失败 $code: $path" }
        body
    }

    /* ─────────────────────────── 业务接口（只读） ─────────────────────────── */

    /** 当前假期批次（无则 null） */
    suspend fun defaultBatch(schoolYear: String = currentSchoolYear()): HolidayBatch? =
        HolidayParser.parseBatch(
            get("/site/holiday/destination/batch/defaultBatch.rst?schoolYear=$schoolYear")
        )

    /** 历史/全部假期批次下拉（用于历史登记切换） */
    suspend fun holidayNames(schoolYear: String = currentSchoolYear()): List<HolidayBatch> =
        HolidayParser.parseHolidayNames(
            get("/site/holiday/destination/batch/queryHolidayName.rst?schoolYear=$schoolYear")
        )

    /** 我的假期登记（含去向登记明细 registers 与返校登记 returnRegisters） */
    suspend fun myHoliday(batchId: Long, studentCode: String): MyHoliday =
        HolidayParser.parseMyHoliday(
            get(
                "/site/holiday/destination/register/myHoliday.rst" +
                    "?batchId=$batchId&studentCode=$studentCode&isReturn=false"
            )
        )

    /**
     * 当前用户信息（学号/姓名）。
     * 认证失败会抛 [AuthGuard.InteractiveAuthRequired]，由 UI 决定是否弹验证；
     * 只有「200 但信封未登录」才返回 null。
     */
    suspend fun userInfo(): Pair<String, String>? {
        val body = get("/edu/api/v1/user/getinfo")
        if (!HolidayParser.isLoggedIn(body, 200)) return null
        return HolidayParser.parseUserInfo(body)
    }

    /** 当前学年（9 月起算新学年），格式 2026-2027 */
    private fun currentSchoolYear(): String {
        val now = LocalDate.now()
        val start = if (now.monthValue >= 9) now.year else now.year - 1
        return "$start-${start + 1}"
    }
}
