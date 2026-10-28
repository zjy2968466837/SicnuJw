package cn.edu.sicnu.scnujw.ui.exams

import cn.edu.sicnu.scnujw.ui.common.DataIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试页「切学期失败」的行内提示必须如实说明屏幕上的列表属于哪个学期。
 *
 * 背景：切到没有缓存的学期时联网失败，旧列表仍留在屏幕上；如果文案只说「刷新失败」，
 * 用户会把上学期的考试当成新学期的安排。这里锁住三种情况：
 *  1) 该学期缓存已上屏 → 只说明刷新失败 + 显示的是该学期缓存；
 *  2) 该学期从未加载成功 → 同时点名「想看的学期」和「正在显示的学期」；
 *  3) 连想看的学期都没有（默认视图）→ 只说显示上次缓存。
 */
class ExamTermNoticeTest {

    private val terms = mapOf(
        "26271" to "2026-2027学年度第一学期",
        "25262" to "2025-2026学年度第二学期",
        "25261" to "2025-2026学年度第一学期",
        // 未知代码（例如教务新增但下拉里还没有名字）：退回原始代码
    )

    @Test
    fun `学期代码映射中文名，未知代码原样返回`() {
        assertEquals("2026-2027学年度第一学期", examTermLabel("26271", terms))
        assertEquals("24241", examTermLabel("24241", terms))
        // 空白 / "-1"（教务无学期视图）/ null 都不当作学期名
        assertNull(examTermLabel("-1", terms))
        assertNull(examTermLabel("  ", terms))
        assertNull(examTermLabel(null, terms))
    }

    @Test
    fun `该学期缓存已上屏时只说刷新失败`() {
        val msg = examTermFailureNotice(
            wanted = "25262",
            cachedShown = true,
            shown = "25262",
            terms = terms,
            kind = DataIssue.NO_CAMPUS,
        )
        assertEquals(
            "「2025-2026学年度第二学期」刷新失败：无法连接校园网，当前显示该学期的上次缓存",
            msg,
        )
    }

    @Test
    fun `该学期从未加载成功时同时点名想看与在显示的学期`() {
        val msg = examTermFailureNotice(
            wanted = "26271",
            cachedShown = false,
            shown = "25262",
            terms = terms,
            kind = DataIssue.NO_NETWORK,
        )
        // 两个学期名都要出现：只写一个会把旧数据误认成新学期的安排
        assertTrue(msg.contains("「2026-2027学年度第一学期」暂时没有读取成功"))
        assertTrue(msg.contains("仍显示「2025-2026学年度第二学期」"))
        assertTrue(msg.contains("设备没有可用网络"))
    }

    @Test
    fun `显示中的学期代码不认识时退回原始代码，仍能说明在显示谁`() {
        val msg = examTermFailureNotice(
            wanted = "26271",
            cachedShown = false,
            shown = "23242",
            terms = terms,
            kind = DataIssue.SERVER,
        )
        assertTrue(msg.contains("仍显示「23242」"))
    }

    @Test
    fun `缓存页里没有学期名时只说不是你要的那个学期`() {
        val msg = examTermFailureNotice(
            wanted = "26271",
            cachedShown = false,
            shown = "-1",
            terms = terms,
            kind = DataIssue.AUTH_EXPIRED,
        )
        assertEquals(
            "「2026-2027学年度第一学期」暂时没有读取成功：登录已失效，仍显示原有考试安排",
            msg,
        )
    }

    @Test
    fun `默认视图刷新失败（没有想看的学期）只说显示上次缓存`() {
        val msg = examTermFailureNotice(
            wanted = null,
            cachedShown = false,
            shown = "26271",
            terms = terms,
            kind = DataIssue.EMPTY,
        )
        assertEquals("刷新失败：暂时没有数据，当前显示上次缓存", msg)
    }
}
