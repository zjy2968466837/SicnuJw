package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.Jsoup

/** 选课结果行（执行计划映射来源；Zxjhdm 用于打开详情页） */
data class ElectPlanRow(
    val zxjhdm: String,
    val courseCode: String,
    val courseName: String,
    val teacher: String,
    val category: String,
    val college: String,
    val campus: String,
    val timePlace: String,
)

/**
 * 解析"我的选课结果"页（ CurriculumSelectByStudent/wp_ElectCourseResult_List.aspx ）。
 * 实测：表头也是 td；数据行 12 列，行内按钮 onclick 携带
 * openWinZxjhDetails('<Zxjhdm>')（原始 HTML 里单引号被转义为 &#39;，jsoup 解析后还原）。
 */
object ElectPlanParser {
    private val ZX_REGEX = Regex("openWinZxjhDetails\\('([^']+)'\\)")

    fun parse(html: String): List<ElectPlanRow> {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").firstOrNull { t ->
            t.select("td").any { it.text().replace('\u00a0', ' ').trim() == "课程代码" }
        } ?: return emptyList()

        // 用表头行建立「列名 → index」，不依赖列数
        val headerRow = table.select("tr").firstOrNull { tr ->
            tr.select("td").any { it.text().replace('\u00a0', ' ').trim() == "课程代码" }
        }
        val idx = LinkedHashMap<String, Int>()
        headerRow?.select("td")?.forEachIndexed { i, td ->
            val t = td.text().replace('\u00a0', ' ').trim()
            if (t.isNotEmpty() && !idx.containsKey(t)) idx[t] = i
        }

        val rows = ArrayList<ElectPlanRow>()
        for (tr in table.select("tr")) {
            if (tr === headerRow) continue
            val tds = tr.select("td")
            if (tds.size < 6) continue
            val zx = tr.select("[onclick*=openWinZxjhDetails]").firstOrNull()
                ?.attr("onclick")
                ?.let { ZX_REGEX.find(it)?.groupValues?.get(1) }
                ?: continue
            fun cell(name: String): String =
                idx[name]?.let { tds.getOrNull(it)?.text()?.replace('\u00a0', ' ')?.trim() } ?: ""

            val code = cell("课程代码")
            val name = cell("课程名称")
            if (code.isEmpty() && name.isEmpty()) continue
            rows.add(
                ElectPlanRow(
                    zxjhdm = zx,
                    courseCode = code,
                    courseName = name,
                    teacher = cell("上课教师"),
                    category = cell("课程类别"),
                    college = cell("开课学院"),
                    campus = cell("上课校区"),
                    timePlace = cell("上课时间地点"),
                )
            )
        }
        return rows
    }
}
