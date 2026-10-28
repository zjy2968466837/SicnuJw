package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 平时成绩构成项：表头"名称(满分)" + 数据行得分（实测如 课堂表现(10) → 9.0） */
data class UsualScoreItem(
    val name: String,       // 构成项名称，如 课堂表现 / 实践教学：个人读书报告
    val full: Double?,      // 满分（表头括号内，如 10）
    val score: Double?,     // 得分（服务端直出如 9.0）
)

/** 一门课程的平时成绩块（rpResult 单元格，实测一学期 14 块） */
data class UsualScoreCourse(
    val term: String,       // 学期码（块标题"学期：25262"，选全部学期时各块不同）
    val termLabel: String,  // 学期名（下拉映射补全）
    val source: String,     // 来源：教学计划 / 重新学习
    val college: String,    // 开课学院（重新学习课可为空）
    val category: String,   // 课程列表，如 文化素质教育课程-必修课（可为空）
    val code: String,       // 课程代码，如 251609
    val name: String,       // 课程名，如 某课程
    val teacher: String,    // 任课教师（多人逗号分隔）
    val items: List<UsualScoreItem>,    // 构成项明细；空 = 「本门课程暂无平时成绩」
) {
    /** 无平时成绩：没有构成项，或构成项全部没有得分（items 非空但 score 全为 null 也算无成绩） */
    val empty: Boolean get() = items.all { it.score == null }
    /** 构成项得分合计 */
    val total: Double get() = items.sumOf { it.score ?: 0.0 }
    /** 满分合计（各项均有满分时才有值） */
    val fullTotal: Double?
        get() = items.map { it.full }.takeIf { fulls -> fulls.none { it == null } }
            ?.sumOf { it ?: 0.0 }
}

/** 平时成绩整页：课程块 + 学期下拉映射 + 当前选中学期码 */
data class UsualScorePage(
    val courses: List<UsualScoreCourse>,
    val termMap: Map<String, String>,   // 学期码 → 名称（不含 -1=全部）
    val selectedTerm: String,           // 下拉当前选中项（可为 -1）
)

object UsualScoreParser {

    // 块标题字段实测顺序："学期：25262 来源：教学计划 开课学院：外国语学院
    // 课程列表：文化素质教育课程-必修课 课程：<课程代码>-<课程名> 任课教师：某某老师"
    private val TITLE_KEYS = listOf("学期", "来源", "开课学院", "课程列表", "课程", "任课教师")

    /**
     * 解析平时成绩页（GET 首屏或学期回发后均可）。定位规则（实测样本 2026-10）：
     * 每门课一块外层 table.table4[border=1]，首格即标题（内层构成项表 gvXsug 同样式但无标题）；
     * 构成项表头是 th（本页例外地用 th），其后数据行各 td 为得分；无成绩课块只有
     * "本门课程暂无平时成绩"一行。
     */
    fun parse(html: String): UsualScorePage {
        val doc: Document = Jsoup.parse(html)

        val termMap = LinkedHashMap<String, String>()
        doc.select("select[id*=wucXndXq] option").forEach {
            val v = it.attr("value").trim()
            if (v.isNotEmpty() && v != "-1") termMap[v] = it.text().trim()
        }
        val selected = doc.selectFirst("select[id*=wucXndXq] option[selected]")
            ?.attr("value")?.trim() ?: ""

        val courses = courseBlocks(doc).map { parseCourse(it, termMap) }
        return UsualScorePage(courses, termMap, selected)
    }

    /** 课程块定位：table.table4[border=1] 且首格以「学期：」开头（内层构成项表无标题） */
    private fun courseBlocks(doc: Document): List<Element> =
        doc.select("table.table4[border=1]")
            .filter {
                it.selectFirst("td")?.text()?.replace('\u00a0', ' ')?.trim()
                    ?.startsWith("学期：") == true
            }

    /**
     * 页面是否真的有课程块。parse() 在页面结构变化/半渲染时也返回空课程，
     * 调用方必须用本函数区分「页面结构异常」与「确实没有平时成绩」。
     */
    fun hasCourseBlocks(html: String): Boolean = courseBlocks(Jsoup.parse(html)).isNotEmpty()

    /**
     * 页面是不是「有效的平时成绩页」（即使没有任何课程块）。
     * 新生/本学期没选课时，教务只渲染学期下拉、没有课程块——属正常空态。
     * 判据：有课程块，或有学期下拉（页面骨架完整）。
     */
    fun hasUsualPage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return courseBlocks(doc).isNotEmpty() ||
            doc.selectFirst("select[id*=wucXndXq]") != null
    }

    private fun parseCourse(block: Element, termMap: Map<String, String>): UsualScoreCourse {
        val fields = parseTitle(block.selectFirst("td")?.text() ?: "")

        // 课程字段 "<课程代码>-<课程名>"：首个 '-' 前为课程代码
        val course = fields["课程"] ?: ""
        val dash = course.indexOf('-')
        val code = if (dash > 0) course.substring(0, dash) else ""
        val name = if (dash > 0) course.substring(dash + 1) else course

        // 构成项：内层 gvXsug 表头 th=名称(满分)，数据行 td=得分，按下标对齐
        val gv = block.selectFirst("table[id*=gvXsug]")
        val heads = gv?.select("th")?.map { it.text().trim() } ?: emptyList()
        val scores = gv?.select("tr")
            ?.filter { it.select("th").isEmpty() }
            ?.flatMap { it.select("td") }
            ?.map { it.text().trim() } ?: emptyList()
        val items = heads.mapIndexed { i, h ->
            val m = Regex("^(.*)[（(]([^（）()]+)[）)]\\s*$").find(h)
            UsualScoreItem(
                name = (m?.groupValues?.get(1) ?: h).trim(),
                full = m?.groupValues?.get(2)?.toDoubleOrNull(),
                score = scores.getOrNull(i)?.toDoubleOrNull(),
            )
        }

        val term = fields["学期"] ?: ""
        return UsualScoreCourse(
            term = term,
            termLabel = termMap[term] ?: term,
            source = fields["来源"] ?: "",
            college = fields["开课学院"] ?: "",
            category = fields["课程列表"] ?: "",
            code = code,
            name = name,
            teacher = fields["任课教师"] ?: "",
            items = items,
        )
    }

    /** 标题拆字段：&nbsp; 分隔的 token 带已知键则开新字段，否则并入上一字段（容课程名含空格） */
    private fun parseTitle(t: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tok in t.replace('\u00a0', ' ').trim().split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            val i = tok.indexOf('：')
            val key = if (i > 0) tok.substring(0, i) else ""
            if (key in TITLE_KEYS) out[key] = tok.substring(i + 1).trim()
            else out.keys.lastOrNull()?.let { last -> out[last] = (out[last] ?: "") + " " + tok }
        }
        return out
    }
}
