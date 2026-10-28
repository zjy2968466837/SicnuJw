package cn.edu.sicnu.scnujw.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学期裁剪口径：入学学年起始年 = 级别年原值，学期码 startYear 可直接与之比较。
 *
 * 背景：25 级 = 2025 年入学（首学期 25261，2025-2026 学年），26 级 = 2026 年入学
 * （首学期 26271，2026-2027 学年）。修复前年级字段被当成「年份 - 1」换算，且入学学年
 * 未知时 UsualRepository 无脑 take(8)，大一因此看到 2022-2023 起的 8 个历史学期。
 * 这里锁住修复后的口径，改动 enrollmentYearOf / filterTerms / targetsOf 时先让这些用例通过。
 */
class ScoreParserTermFilterTest {

    private val termMap = linkedMapOf(
        "22242" to "2022-2023学年度第二学期",
        "23241" to "2023-2024学年度第一学期",
        "23242" to "2023-2024学年度第二学期",
        "24241" to "2024-2025学年度第一学期",
        "24242" to "2024-2025学年度第二学期",
        "25261" to "2025-2026学年度第一学期",
        "25262" to "2025-2026学年度第二学期",
        "26271" to "2026-2027学年度第一学期",
    )

    @Test
    fun `级别年即入学学年起始年`() {
        // 26 级 = 2026 年入学，2026-2027 学年首学期 26271；不做 year-1
        assertEquals(2026, ScoreCache.enrollmentYearOf(snoYear = 2026, gradeYear = 2026))
        assertEquals(2026, ScoreCache.enrollmentYearOf(snoYear = 2026, gradeYear = null))
        assertEquals(2026, ScoreCache.enrollmentYearOf(snoYear = null, gradeYear = 2026))
        // 25 级 = 2025 年入学，首学期 25261
        assertEquals(2025, ScoreCache.enrollmentYearOf(snoYear = 2025, gradeYear = null))
        // 年级晚于实际修课年份：取较早者（2023 级 → 2023），保住 23241/23242 的成绩
        assertEquals(2023, ScoreCache.enrollmentYearOf(snoYear = 2023, gradeYear = 2024))
        assertEquals(2023, ScoreCache.enrollmentYearOf(snoYear = 2023, gradeYear = null))
        assertNull(ScoreCache.enrollmentYearOf(snoYear = null, gradeYear = null))
        // 越界（不在 2000..2100）视为不可信：返回 null（不过滤），绝不硬夹成 2000 去藏 20xxx 学期
        assertNull(ScoreCache.enrollmentYearOf(snoYear = null, gradeYear = 1999))
        // 2000 级本身在界内，原值保留（不做 year-1 后越界）
        assertEquals(2000, ScoreCache.enrollmentYearOf(snoYear = 2000, gradeYear = null))
    }

    @Test
    fun `startYear 是学期码前两位`() {
        assertEquals(2025, ScoreParser.startYear("25262"))
        assertEquals(2026, ScoreParser.startYear("26271"))
        assertNull(ScoreParser.startYear("2526"))
        assertNull(ScoreParser.startYear("abcd1"))
    }

    @Test
    fun `2026 级只保留 2026-2027 学年起的学期`() {
        // enrollmentYear = 2026（级别年原值）：25262 及更早必须隐藏，只留 26271
        val kept = ScoreParser.filterTerms(termMap, 2026).keys
        assertEquals(setOf("26271"), kept.toSet())
    }

    @Test
    fun `2025 级保留 2025-2026 学年起的学期`() {
        val kept = ScoreParser.filterTerms(termMap, 2025).keys
        assertEquals(setOf("25261", "25262", "26271"), kept.toSet())
    }

    @Test
    fun `有成绩记录的旧学期一律保留`() {
        val kept = ScoreParser.filterTerms(termMap, 2026, setOf("23241", "22242")).keys
        assertTrue("23241" in kept)
        assertTrue("22242" in kept)
        // 无记录且早于入学学年的学期仍然隐藏
        assertFalse("25262" in kept)
    }

    @Test
    fun `入学学年未知时原样返回（绝不猜年份藏数据）`() {
        assertEquals(termMap, ScoreParser.filterTerms(termMap, null))
    }

    @Test
    fun `2026 级目标学期不含入学前的历史学期`() {
        val targets = UsualRepository.targetsOf(
            termMap = termMap,
            enrollmentYear = 2026,
            scoreTerms = emptySet(),
            cachedTerms = emptySet(),
            currentTerm = "26271",
        )
        // 修复前 grade=null 会 take(8) 把 22242 起的 8 个学期全部当目标
        assertEquals(setOf("26271"), targets)
    }

    @Test
    fun `入学学年未知时取当前学期加最近四个，共五个`() {
        val targets = UsualRepository.targetsOf(
            termMap = termMap,
            enrollmentYear = null,
            scoreTerms = emptySet(),
            cachedTerms = emptySet(),
            currentTerm = "26271",
        )
        // 窗口含锚点共 5 个：当前学期 + 之前最近 4 个（倒序 26271、25262、25261、24242、24241）。
        // 只取 4 个会漏掉 24241 这种「有平时成绩、还没出期末成绩」的上一学期
        assertEquals(setOf("26271", "25262", "25261", "24242", "24241"), targets)
        assertFalse("23242" in targets)
    }

    @Test
    fun `入学学年未知时没有当前学期则以最新学期为锚`() {
        // readCache 路径不传 currentTerm：不能因此退化成「全部学期」，也不能看历史学期
        val targets = UsualRepository.targetsOf(
            termMap = termMap,
            enrollmentYear = null,
            scoreTerms = emptySet(),
            cachedTerms = emptySet(),
            currentTerm = null,
        )
        assertEquals(setOf("26271", "25262", "25261", "24242", "24241"), targets)
    }

    @Test
    fun `入学学年未知时缓存与成绩学期不受窗口限制`() {
        val targets = UsualRepository.targetsOf(
            termMap = termMap,
            enrollmentYear = null,
            scoreTerms = setOf("22242"),
            cachedTerms = setOf("23241"),
            currentTerm = "26271",
        )
        // 宁可多显示，不可藏真实数据：窗口外的缓存/成绩学期照样保留
        assertTrue("22242" in targets)
        assertTrue("23241" in targets)
        assertTrue("26271" in targets)
        assertTrue("25262" in targets)
    }
}
