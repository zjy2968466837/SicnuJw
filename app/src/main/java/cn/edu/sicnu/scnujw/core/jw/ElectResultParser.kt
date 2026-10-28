package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 一门已选课程（我的选课结果表实测 12 列）。
 * 样本：scnu-jiaowu/cache/fullsite/pages/m6_CurriculumSelectByStudent_wp_ElectCourseResult_List_aspx.html
 */
data class ElectCourseRow(
    val source: String,     // 课程来源：教学计划 / 重新学习 / 缓考 / 补考 / 清考 / 校外考试
    val college: String,    // 开课学院
    val category: String,   // 课程类别（重新学习来源的课可为空，如某重修课程）
    val code: String,       // 课程代码，如 071127
    val name: String,       // 课程名称，如 某课程
    val campus: String,     // 上课校区，如 成龙校区
    val timePlace: String,  // 上课时间地点（多段以 ';' 分隔；尚无排课时为空）
    val teacher: String,    // 上课教师（单元格内是开课详情链接，取链接文字）
    val credit: Double,     // 总学分，如 0.25
    val students: Int?,     // 选课人数（服务端直出，解析失败为 null）
    val status: String,     // 状态：选中 / 已审查
    /**
     * 执行计划代号。取自行内「开课详情」按钮：
     * `<input ... value="开课详情" onclick="return openWinZxjhDetails('<Zxjhdm>');">`。
     * 用于拼 `Common/wpExecutePlanDetails.aspx?Zxjhdm=<val>`；行内无按钮时为 null。
     */
    val zxjhdm: String? = null,
) {
    /** 拆分多段上课时间地点；空列表 = 尚未排课 */
    val schedules: List<String>
        get() = timePlace.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** 非普通"选中"（重新学习课需教务审查，实测状态"已审查"） */
    val reviewed: Boolean get() = status.isNotEmpty() && status != "选中"
}

/** 我的选课结果整页：课程行 + 两个筛选下拉 + 分页信息 */
data class ElectResultPage(
    val courses: List<ElectCourseRow>,
    val termMap: Map<String, String>,     // 学期码 → 名称（不含 -1=全部）
    val sourceMap: Map<String, String>,   // 课程来源码 → 名称（不含 -1=全部）
    val selectedTerm: String,             // 学期下拉当前值；-1=全部
    val selectedSource: String,           // 来源下拉当前值；-1=全部
    val totalCount: Int,                 // 分页"共 N 条"
    val currentPage: Int,
    val totalPages: Int,
)

/**
 * 解析选课结果页（GET 首屏或筛选/分页回发后均可）。
 * 定位规则（实测）：表头行是 <tr class="table_title"> 内的 <td>（全站无 th），
 * 以"课程名称 + 上课时间地点"两列唯一定位表格；数据行 class="p_item_0/1"。
 */
object ElectResultParser {

    private fun norm(text: String): String =
        text.replace('\u00a0', ' ').replace(Regex("\\s+"), "")

    fun parse(html: String): ElectResultPage {
        val doc: Document = Jsoup.parse(html)

        val termMap = options(doc, "select[id*=wucXndXq]")
        val sourceMap = options(doc, "select[id*=wucTaskSource]")
        val selectedTerm = selectedOption(doc, "select[id*=wucXndXq]")
        val selectedSource = selectedOption(doc, "select[id*=wucTaskSource]")

        val table = doc.select("table").firstOrNull { t ->
            val header = t.select("tr").firstOrNull { tr ->
                tr.select("td").any { norm(it.text()) == "课程名称" }
            }
            header != null && header.select("td").any { norm(it.text()) == "上课时间地点" }
        }
        if (table == null) {
            return ElectResultPage(
                courses = emptyList(),
                termMap = termMap, sourceMap = sourceMap,
                selectedTerm = selectedTerm, selectedSource = selectedSource,
                totalCount = 0, currentPage = 0, totalPages = 0,
            )
        }

        // 表头列名 → 下标（兼容服务端列顺序变化）
        var headerSeen = false
        val cols = LinkedHashMap<String, Int>()
        val rows = ArrayList<ElectCourseRow>()

        for (tr in table.select("tr")) {
            val tds = tr.select("td")
            if (!headerSeen) {
                if (tds.any { norm(it.text()) == "课程名称" }) {
                    headerSeen = true
                    tds.forEachIndexed { i, td -> cols.putIfAbsent(norm(td.text()), i) }
                }
                continue
            }
            if (tds.isEmpty()) continue
            fun cell(column: String): String {
                val i = cols[column] ?: return ""
                return tds.getOrNull(i)?.text()?.replace('\u00a0', ' ')?.trim() ?: ""
            }
            val name = cell("课程名称")
            if (name.isEmpty()) continue // 无数据行 / 页脚行
            rows.add(
                ElectCourseRow(
                    source = cell("课程来源"),
                    college = cell("开课学院"),
                    category = cell("课程类别"),
                    code = cell("课程代码"),
                    name = name,
                    campus = cell("上课校区"),
                    timePlace = cell("上课时间地点"),
                    teacher = cell("上课教师"),
                    credit = cell("总学分").toDoubleOrNull() ?: 0.0,
                    students = cell("选课人数").toIntOrNull(),
                    status = cell("状态"),
                    zxjhdm = zxjhdmOf(tr),
                )
            )
        }

        val total = doc.selectFirst("span[id*=lblTotalCount]")
            ?.text()?.trim()?.toIntOrNull() ?: rows.size
        val current = doc.selectFirst("span[id*=lblCurPageCount]")
            ?.text()?.trim()?.toIntOrNull() ?: 1
        val pages = doc.selectFirst("span[id*=lblTotalPageCount]")
            ?.text()?.trim()?.toIntOrNull() ?: 1

        return ElectResultPage(
            courses = rows,
            termMap = termMap, sourceMap = sourceMap,
            selectedTerm = selectedTerm, selectedSource = selectedSource,
            totalCount = total, currentPage = current, totalPages = pages,
        )
    }

    /** onclick="return openWinZxjhDetails('20260530110907661');" */
    private val zxjhDetailsRe = Regex("""openWinZxjhDetails\(\s*['"]([^'"]+)['"]\s*\)""")

    /** 兜底：教师链接 onclick="return openWinZgDetails('<同一 Zxjhdm>');" */
    private val zgDetailsRe = Regex("""openWinZgDetails\(\s*['"]([^'"]+)['"]\s*\)""")

    /**
     * 取一行的执行计划代号。实测每行末列是「开课详情」按钮，onclick 里带 Zxjhdm；
     * 教师列链接（openWinZgDetails）带的是同一个值，作为按钮缺失时的兜底。
     */
    private fun zxjhdmOf(tr: Element): String? {
        val fromDetail = tr.select("[onclick*=openWinZxjhDetails]").firstOrNull()
            ?.attr("onclick")
            ?.let { zxjhDetailsRe.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() }
        if (fromDetail != null) return fromDetail
        return tr.select("[onclick*=openWinZgDetails]").firstOrNull()
            ?.attr("onclick")
            ?.let { zgDetailsRe.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() }
    }

    /** 下拉选项 → 码/名映射（跳过 -1=全部） */
    private fun options(doc: Document, selector: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        doc.select("$selector option").forEach {
            val v = it.attr("value").trim()
            if (v.isNotEmpty() && v != "-1") map[v] = it.text().trim()
        }
        return map
    }

    private fun selectedOption(doc: Document, selector: String): String =
        doc.selectFirst("$selector option[selected]")?.attr("value")?.trim()
            ?: doc.selectFirst("$selector option")?.attr("value")?.trim()
            ?: ""
}
