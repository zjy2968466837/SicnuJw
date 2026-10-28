package cn.edu.sicnu.scnujw.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志的脱敏与抓取黑名单。这些规则直接对应 PRIVACY.md 的对外承诺，
 * 改动 `Diag.sanitizeText` / `Diag.isBlocked` 时先让这些用例通过。
 */
class DiagSanitizeTest {

    private val sid = "2023999999"   // 假学号，仓库公开，禁止用真实值
    private val name = "张三"        // 假姓名

    @Test
    fun `本人姓名与学号替换为占位符`() {
        val html = "<td>学号 $sid</td><td>姓名 $name</td>"
        val out = Diag.sanitizeText(html, sid, name)
        assertEquals("<td>学号 <SID></td><td>姓名 <NAME></td>", out)
    }

    @Test
    fun `sid 查询参数打码`() {
        val url = "http://202.115.194.60/ScoreQuery/wp_MyElectCourseScore_Query.aspx?sid=$sid&x=1"
        val out = Diag.sanitizeText(url, sid, name)
        assertTrue(out.contains("sid=<SID>"))
        assertFalse(out.contains(sid))
    }

    @Test
    fun `票据与会话参数打码`() {
        val body = "CASTGC=TGT-12345-abc; JSESSIONID=ABCDEF; ticket=ST-999-x; __pstsid__=zzz; srv_id=42"
        val out = Diag.sanitizeText(body, sid, name)
        listOf("TGT-12345-abc", "ABCDEF", "ST-999-x", "zzz").forEach {
            assertFalse("不应包含 $it", out.contains(it))
        }
        assertTrue(out.contains("CASTGC=<REDACTED>"))
        assertTrue(out.contains("ticket=<REDACTED>"))
    }

    @Test
    fun `密码参数打码`() {
        val out = Diag.sanitizeText("username=x&password=Secr3t!&pwd=abc", sid, name)
        assertFalse(out.contains("Secr3t!"))
        assertFalse(out.contains("pwd=abc"))
        assertTrue(out.contains("password=<REDACTED>"))
    }

    @Test
    fun `身份证 手机 邮箱打码`() {
        val out = Diag.sanitizeText(
            "身份证 510101199001011234 手机 13800138000 邮箱 someone@example.com",
            sid,
            name,
        )
        assertFalse(out.contains("510101199001011234"))
        assertFalse(out.contains("13800138000"))
        assertFalse(out.contains("someone@example.com"))
        assertTrue(out.contains("<ID18>"))
        assertTrue(out.contains("<PHONE>"))
        assertTrue(out.contains("<EMAIL>"))
    }

    @Test
    fun `他人的 10 位学号也会打码`() {
        val out = Diag.sanitizeText("同学 2022080111 的成绩", sid, name)
        assertEquals("同学 <SID10> 的成绩", out)
    }

    @Test
    fun `姓名为空时不误伤正文`() {
        val out = Diag.sanitizeText("score 88", sid, null)
        assertEquals("score 88", out)
    }

    @Test
    fun `学工与去向登记整域禁止抓取`() {
        assertTrue(Diag.isBlocked("https://zhxg.sicnu.edu.cn/edu/api/v1/user/getinfo"))
        assertTrue(Diag.isBlocked("https://ZHXG.sicnu.edu.cn/holiday/page"))
        assertFalse(Diag.isBlocked("http://202.115.194.60/ScoreQuery/x.aspx"))
        assertFalse(Diag.isBlocked("https://jwcxk.sicnu.edu.cn/student/home"))
        // 只挡学工域，不能把教务/课表一起挡掉
        assertFalse(Diag.isBlocked("https://jw.sicnu.edu.cn/x"))
    }

    @Test
    fun `脱敏是幂等的（导出时会再跑一遍）`() {
        val src = "http://202.115.194.60/x.aspx?sid=$sid&ticket=ST-1 CASTGC=TGT-2 姓名 $name 同学 2022080111"
        val once = Diag.sanitizeText(src, sid, name)
        val twice = Diag.sanitizeText(once, sid, name)
        assertEquals(once, twice)
        assertFalse(once.contains("<SID><SID>"))
        assertFalse(twice.contains("<REDACTED><REDACTED>"))
    }

    @Test
    fun `表单里的密码与用户名输入框也会打码`() {
        val html = "<input type=\"text\" name=\"username\" value=\"2023999999\">" +
            "<input type=\"password\" name=\"password\" value=\"Secr3t!\">" +
            "<input name=password value=Plain9>"
        val out = Diag.sanitizeText(html, sid, name)
        assertFalse(out.contains("Secr3t!"))
        assertFalse(out.contains("Plain9"))
        assertFalse(out.contains("2023999999"))
        assertTrue(out.contains("password\" value=\"<REDACTED>"))
        // 用户名输入框的 value 一律打码：CAS 登录页里它就是学号，
        // 且非标准号（研究生等）兜不住 RE_ID_10，明文留着也没有排查价值
        assertTrue(out.contains("username\" value=\"<REDACTED>"))
    }

    @Test
    fun `非用户名输入框的 value 保留`() {
        // 教务回发控件（__EVENTTARGET 等）的 value 是页面结构的一部分，保留用于排查
        val html = "<input type=\"hidden\" name=\"__EVENTTARGET\" value=\"ddlTerm\">"
        val out = Diag.sanitizeText(html, sid, name)
        assertEquals(html, out)
    }

    @Test
    fun `两次脱敏结果一致`() {
        val once = Diag.sanitizeText("<input type=password value=\"a1b2\">", sid, name)
        assertEquals(once, Diag.sanitizeText(once, sid, name))
    }
}
