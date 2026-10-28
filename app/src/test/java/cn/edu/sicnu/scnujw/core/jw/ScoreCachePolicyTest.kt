package cn.edu.sicnu.scnujw.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 缓存策略的纯逻辑：按天/按周判断 + 新增两个视图缓存的键。
 * 这些规则决定「什么时候才联网」，改动前先让用例通过。
 */
class ScoreCachePolicyTest {

    private fun daysAgo(days: Int): Long =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis

    @Test
    fun `按天刷新：无缓存和昨天都要刷新，今天不刷`() {
        assertTrue(ScoreCache.needDailyRefresh(null))
        assertTrue(ScoreCache.needDailyRefresh(daysAgo(1)))
        assertFalse(ScoreCache.needDailyRefresh(System.currentTimeMillis()))
    }

    @Test
    fun `按周刷新：6 天内不刷，超过 7 天才刷`() {
        assertTrue(ScoreCache.needWeeklyRefresh(null))
        assertFalse(ScoreCache.needWeeklyRefresh(daysAgo(6)))
        assertTrue(ScoreCache.needWeeklyRefresh(daysAgo(8)))
    }

    @Test
    fun `选课结果视图键区分学期与来源，且「全部」只有一种写法`() {
        val k1 = ScoreCache.electResultViewKey("25262", "3")
        val k2 = ScoreCache.electResultViewKey("25262", "3")
        assertEquals(k1, k2)
        assertEquals(ScoreCache.electResultViewKey(null, null), ScoreCache.electResultViewKey("-1", "-1"))
        assertTrue(k1 != ScoreCache.electResultViewKey("25262", null))
        assertTrue(k1 != ScoreCache.electResultViewKey("26271", "3"))
    }

    @Test
    fun `执行计划详情键按学期+课程隔离`() {
        val a = ScoreCache.planDetailKey("25262", "CS101")
        assertEquals(a, ScoreCache.planDetailKey("25262", "CS101"))
        assertTrue(a != ScoreCache.planDetailKey(null, "CS101"))
        assertTrue(a != ScoreCache.planDetailKey("25262", "CS102"))
    }
}
