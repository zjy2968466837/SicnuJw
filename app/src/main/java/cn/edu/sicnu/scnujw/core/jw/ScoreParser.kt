package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 单条成绩记录（选课成绩表实测字段） */
data class ScoreRow(
    val term: String,        // 学期码，如 25262
    val termLabel: String,   // 2025-2026学年度第二学期
    val source: String,      // 教学计划/选课
    val college: String,
    val category: String,    // 课程类别
    val code: String,        // 课程代码
    val name: String,
    val teacher: String,
    val credit: Double,
    val scoreText: String,   // 成绩（数字或等级）
    val gpa: Double?,        // 绩点（等级制可能为空）
) {
    // 等级制（五级制）判定：系统只给「成绩」文本，计分制列未保留，只能按关键字识别。
    // 「不合格」不含子串「不及格」，必须单独列出；「不通过」同样含「通过」，也需先判失败。
    val failed: Boolean get() = gpa == 0.0 ||
        (scoreText.toDoubleOrNull()?.let { it < 60 } ?: false) ||
        FAIL_TEXTS.any { scoreText.contains(it) }

    /** 通过：绩点>0、数字成绩≥60、或等级成绩属合格/及格/优秀/良好等通过档（不含不合格/不通过） */
    val passed: Boolean get() =
        (gpa != null && gpa > 0.0) ||
            (scoreText.toDoubleOrNull()?.let { it >= 60 } ?: false) ||
            (PASS_TEXTS.any { scoreText.contains(it) } &&
                FAIL_TEXTS.none { scoreText.contains(it) })

    val retake: Boolean get() = source == "重新学习" || source == "重修"

    private companion object {
        /** 通过档（五级制/两级制常见文本） */
        val PASS_TEXTS = listOf("优秀", "良好", "中等", "合格", "及格", "通过")
        /** 失败档；「不合格/不通过/未通过」都含通过档子串，必须先判失败 */
        val FAIL_TEXTS = listOf("不及格", "不合格", "不通过", "未通过")
    }
}

data class ScoreSummary(
    val gpa: Double,        // 加权绩点（重修按最高成绩计）
    val credits: Double,    // 已获学分（通过课程，重修去重）
    val count: Int,         // 课程门数（按课程代码去重，含重修课；不是记录数）
    val retakeCount: Int,   // 重修课程门数（按课程代码去重）
)

object ScoreParser {

    /** 解析选课成绩页（回发后含全学期数据；学期下拉提供码→名映射） */
    fun parse(html: String): Pair<List<ScoreRow>, Map<String, String>> {
        val doc: Document = Jsoup.parse(html)

        // 学期码 → 名称（下拉选项）
        val termMap = mutableMapOf<String, String>()
        doc.select("select[id*=ddlItem] option").forEach { opt ->
            val v = opt.attr("value").trim()
            if (v.isNotEmpty() && v != "-1") termMap[v] = opt.text().trim()
        }

        // 实测该系统整页无 <th>，表头也用 <td>：按"含课程名称列的表"定位，跳过表头行
        val table = scoreTable(doc) ?: return emptyList<ScoreRow>() to termMap

        // 实测 15 列：[0]复选框 [1]学期 [2]来源 [3]开课学院 [4]课程类别 [5]课程代码
        // [6]课程名称 [7]上课教师 [8]学分 [9]计分制 [10]卷面比例 [11]缓考 [12]成绩 [13]绩点
        val rows = ArrayList<ScoreRow>()
        for (tr in table.select("tr")) {
            val tds = tr.select("td")
            if (tds.size < 14) continue
            fun cell(i: Int) = tds.getOrNull(i)?.text()?.replace('\u00a0', ' ')?.trim() ?: ""
            val name = cell(6)
            if (name.isEmpty() || name == "课程名称") continue
            rows.add(
                ScoreRow(
                    term = cell(1),
                    termLabel = termMap[cell(1)] ?: cell(1),
                    source = cell(2),
                    college = cell(3),
                    category = cell(4),
                    code = cell(5),
                    name = name,
                    teacher = cell(7),
                    credit = cell(8).toDoubleOrNull() ?: 0.0,
                    scoreText = cell(12),
                    gpa = cell(13).toDoubleOrNull(),
                )
            )
        }
        return rows to termMap
    }

    /** 成绩表定位：含「课程名称」表头的表（实测整页无 <th>，表头也是 td） */
    private fun scoreTable(doc: Document): Element? =
        doc.select("table").firstOrNull { table ->
            table.select("td").any { td -> td.text().trim() == "课程名称" }
        }

    /**
     * 页面是否真的有一张成绩表。
     * parse() 在找不到表时也返回空列表——调用方必须用本函数区分
     * 「页面结构异常/半渲染」与「确实还没有出分」，不能把前者伪装成空态。
     */
    fun hasScoreTable(html: String): Boolean = scoreTable(Jsoup.parse(html)) != null

    /**
     * 页面是不是「有效的成绩页」（即使一条记录都没有）。
     * 新生（大一上）教务可能只渲染学期下拉、不渲染成绩表——这属于正常空态，不是结构异常。
     * 判据：有成绩表，或有学期下拉/总数控件（页面骨架完整）。
     */
    fun hasScorePage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return scoreTable(doc) != null ||
            doc.selectFirst("select[id*=ddlItem]") != null ||
            doc.selectFirst("[id*=lblTotalCount]") != null
    }

    /** 分页控件声明的总记录数（lblTotalCount，实测「共<span>77</span>条」）；读不到返回 null */
    fun totalCount(html: String): Int? =
        Jsoup.parse(html).selectFirst("[id*=lblTotalCount]")?.text()?.let { t ->
            Regex("\\d+").find(t)?.value?.toIntOrNull()
        }

    /**
     * 加权 GPA。重修课与原考并存于不同学期，官方口径按课程代码取最高成绩：
     * 同 code 取绩点最高一条参与加权；绩点缺失的记录不计入分子分母。
     * 已获学分 = 去重后通过（绩点>0）课程的学分合计。
     */
    fun summary(rows: List<ScoreRow>): ScoreSummary {
        val best = rows
            .filter { it.gpa != null && it.credit > 0 }
            .groupBy { it.code }
            .mapValues { (_, v) -> v.maxByOrNull { it.gpa!! }!! }
        val credits = best.values.sumOf { it.credit }
        val weighted = best.values.sumOf { it.credit * it.gpa!! }
        // 「共 X 门 / 重修 N 门」按课程代码去重（同一门课多次重修只算一门）；
        // 代码缺失时退化为按课程名去重，避免互不相关的课被折叠
        fun key(r: ScoreRow) = r.code.ifBlank { "name:${r.name}" }
        return ScoreSummary(
            gpa = if (credits > 0) weighted / credits else 0.0,
            credits = best.values.filter { it.gpa!! > 0 }.sumOf { it.credit },
            count = rows.distinctBy { key(it) }.size,
            retakeCount = rows.filter { it.retake }.distinctBy { key(it) }.size,
        )
    }

    /** 单学期小计（学期内无重修重复，直接学分加权） */
    fun termSummary(rows: List<ScoreRow>): Double? {
        val valid = rows.filter { it.gpa != null && it.credit > 0 }
        val credits = valid.sumOf { it.credit }
        return if (credits > 0) valid.sumOf { it.credit * it.gpa!! } / credits else null
    }

    // ── 学期裁剪（按入学学年）──

    /**
     * 学期码起始年：5 位码 = 起始年后两位 + 结束年后两位 + 学期(1|2)，
     * 如 25262 → 2025（2025-2026 学年度第二学期）。异常码返回 null（调用方按「不过滤」处理）。
     */
    fun startYear(term: String): Int? {
        if (term.length != 5) return null
        val yy = term.take(2).toIntOrNull() ?: return null
        return 2000 + yy
    }

    /**
     * 按入学学年裁剪学期下拉：只保留起始年 >= [enrollmentYear] 的学期（调用方另加「全部学期」）。
     *
     * 2026 级（2026 年 9 月入学，2026-2027 学年）的 enrollmentYear = 2026：保留 26271/27281…，
     * 隐藏 25262 及更早；入学学年未知（null）时原样返回，绝不凭猜测隐藏数据。
     *
     * @param enrollmentYear 入学学年起始年（2026 级 → 2026，见 ScoreCache.enrollmentYear），
     *              与 [startYear] 同口径（级别年原值，不是级别年减 1）。
     * @param recordTerms 确实有成绩记录/课程块存在的学期码：**一律保留**。
     *              年级字段可能晚于实际修课年份（样例学号 2023、年级 2024，23241/23242 已有成绩），
     *              宁可多显示，不可藏真实数据。
     */
    fun filterTerms(
        terms: Map<String, String>,
        enrollmentYear: Int?,
        recordTerms: Set<String> = emptySet(),
    ): Map<String, String> {
        if (enrollmentYear == null) return terms
        return terms.filterKeys { code ->
            val start = startYear(code)
            start == null || start >= enrollmentYear || code in recordTerms
        }
    }

    // ── 需重修（按课程代码去重）──

    /**
     * 需重修课程：
     *  - 同一课程代码至少有一条 failed 记录；
     *  - 该代码没有任何通过记录（重修已通过 → 彻底从需重修里消失）；
     *  - 每门课只取最近学期的那条未通过记录（视图里一门课一行）。
     * 课程代码为空时退化为按课程名分组，避免互不相关的课被折叠成一行。
     */
    fun needRetakeRows(rows: List<ScoreRow>): List<ScoreRow> =
        rows.groupBy { it.code.ifBlank { "name:${it.name}" } }
            .values
            .mapNotNull { list ->
                if (list.any { it.passed }) return@mapNotNull null
                list.filter { it.failed }.maxByOrNull { it.term }
            }
            .sortedWith(compareByDescending<ScoreRow> { it.term }.thenBy { it.name })
}
