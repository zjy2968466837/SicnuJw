package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 学习进度页内层课程明细表里的一门课程（一个"数据行"）。
 *
 * 实测样本（2026-10 `cache/pages/ScoreQuery_wp_MyLearnProgress_Query.aspx`）数据行 18 格：
 *   0 序号 | 1 课程代码 | 2 课程名称 | 3 课程学分 | 4 理论学时 | 5 实践学时 |
 *   6 折算后总学时 | 7..15 开课学期或成绩获得学期（9 列） | 16 获得学分 | 17 绩点
 * 页面没有独立的"课程类别"列，[category] 取课程名里的"（属性：X）"标记
 * （如 传统文化 / 美育课程 / 选修中的必选课程），无标记时为 null。
 * 成绩列除数字外还可能是"优"或"×"（× 表示尚未修读/未出成绩）。
 */
data class LearnCourse(
    val code: String,        // 课程代码，如 251700
    val name: String,        // 课程名称
    val category: String?,   // 课程类别/属性：取课程名"（属性：X）"，缺列为 null
    val credit: Double?,     // 课程学分；解析失败为 null
    val score: String?,      // 成绩：数字或"优"；"×"表示未修/未出成绩
    val term: String?,       // 成绩获得学期，如"第二学年一"；无标记为 null
)

/**
 * 一个培养方案学分模块（学习进度页的六张分类表之一）。
 *
 * 实测样本（2026-10 `cache/pages/ScoreQuery_wp_MyLearnProgress_Query.aspx`）：
 * 模块外层表每两行一组——
 *   第 1 行：[模块名 | 课程明细表]
 *   第 2 行：[最低学分要求：41]（选修模块数字后还跟限选/美育/传统文化说明）
 * 课程明细表末行是合计行：首格 "27门"，倒数第二格为"获得-学分"合计，最后一格为绩点。
 *
 * 注意：表尾"N门"是服务端统计口径，不一定等于明细行数。实测文化素质必修块内层表
 * 32 个数据行、表尾写 27 门（差 5 行恰为成绩列标"×"的形势与政策5~8 与职业发展（2），
 * 英语 5 门均有成绩）。故 [courses] 如实保存全部数据行，[pageCount] 另存表尾数字。
 */
data class CreditCategory(
    val name: String,        // 如 文化素质教育课程-必修课
    val earned: Double,      // 已修（已获）学分：表尾"获得-学分"合计
    val required: Double?,   // 应修：最低学分要求；页面未给出时为 null
    val note: String = "",   // 模块备注（选修模块的限选/美育/传统文化说明等）
    /** 内层明细表的全部数据行（含成绩为"×"的未修行）；表头与表尾合计行不计入 */
    val courses: List<LearnCourse> = emptyList(),
    /** 内层表尾"N门"（服务端统计口径；与 courses.size 可能不同） */
    val pageCount: Int? = null,
) {
    /** 完成率（0..1，封顶 100%）；无最低要求时为 null */
    val ratio: Float?
        get() = required?.takeIf { it > 0 }?.let { (earned / it).toFloat().coerceIn(0f, 1f) }

    /** 是否已达到最低学分要求 */
    val finished: Boolean
        get() = required != null && earned + 1e-6 >= required

    /** 已出成绩的课程数（排除成绩为 "×" 或空的未修/未出分行） */
    val gradedCount: Int
        get() = courses.count { it.score != null && it.score != "×" }
}

/** 学习进度整页：学分模块 + 汇总（已修 / 应修） */
data class LearnProgress(
    val categories: List<CreditCategory>,
) {
    /** 已修学分合计（各类别表尾合计相加） */
    val totalEarned: Double get() = categories.sumOf { it.earned }

    /** 应修学分合计（各类别最低学分要求相加） */
    val totalRequired: Double get() = categories.mapNotNull { it.required }.sumOf { it }

    /** 总完成率（0..1，封顶 100%） */
    val totalRatio: Float
        get() = if (totalRequired > 0) {
            (totalEarned / totalRequired).toFloat().coerceIn(0f, 1f)
        } else 0f
}

object ProgressParser {

    private val REQUIRE = Regex("""最低学分要求\s*[：:]\s*([0-9]+(?:\.[0-9]+)?)""")
    private val FOOTER_COUNT = Regex("""^(\d+)\s*门$""")
    private val COURSE_ATTR = Regex("""属性\s*[：:]\s*([^（()）]+)""")

    /**
     * 内层明细表 9 个学期列的顺序（对应表头"第一学年(一/二) … 小学期 … 第四学年(一/二)"，
     * 小学期插在第三学年与第四学年之间）。实测每个数据行只会有其中一格非空。
     */
    private val TERM_LABELS = listOf(
        "第一学年一", "第一学年二",
        "第二学年一", "第二学年二",
        "第三学年一", "第三学年二",
        "小学期",
        "第四学年一", "第四学年二",
    )

    fun parse(html: String): LearnProgress = parse(Jsoup.parse(html))

    /**
     * 页面骨架是否完整（用于区分「新生暂无数据」与「结构异常」）。
     * 判据：有表格，且页面出现「最低学分要求」或「学习进度」字样。
     */
    fun hasProgressPage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        val text = doc.text()
        return doc.selectFirst("table") != null &&
            (text.contains("最低学分要求") || text.contains("学习进度"))
    }

    fun parse(doc: Document): LearnProgress {
        // 逐表尝试：模块外层表命中；内层课程表/布局表不满足"两格模块行 + 下一行最低学分要求"结构
        val categories = LinkedHashMap<String, CreditCategory>()
        for (table in doc.select("table")) {
            for (c in parseModuleTable(table)) categories.putIfAbsent(c.name, c)
        }
        return LearnProgress(categories.values.toList())
    }

    /** 解析一张模块外层表，返回其中包含的模块（实测 6 个模块顺序排列） */
    private fun parseModuleTable(table: Element): List<CreditCategory> {
        val rows = directRows(table)
        if (rows.size < 2) return emptyList()

        val out = ArrayList<CreditCategory>()
        var i = 0
        while (i < rows.size - 1) {
            val tds = directCells(rows[i])
            val inner = if (tds.size == 2) tds[1].selectFirst("table") else null
            if (inner == null) {
                i++
                continue
            }

            val name = clean(tds[0].text())
            val reqText = clean(directCells(rows[i + 1]).firstOrNull()?.text() ?: "")
            val req = REQUIRE.find(reqText)
            if (name.isEmpty() || !reqText.startsWith("最低学分要求") || req == null) {
                i++
                continue
            }

            out += CreditCategory(
                name = name,
                earned = earnedCredits(inner),
                required = req.groupValues[1].toDoubleOrNull(),
                // 数字之后的部分是模块备注（如"注：该模块选修课程至少选修9个学分……"）
                note = reqText.substring(req.range.last + 1).trim(),
                courses = parseCourses(inner),
                pageCount = pageCount(inner),
            )
            i += 2
        }
        return out
    }

    /**
     * 课程明细：只取"数据行"（首格序号为纯数字）。表头 3 行格数不足，
     * 表尾合计行首格是"27门"，均被排除。
     */
    private fun parseCourses(table: Element): List<LearnCourse> {
        val out = ArrayList<LearnCourse>()
        for (row in directRows(table)) {
            val cells = directCells(row)
            // 数据行 18 格；首格序号必须是数字（排除表头/表尾）
            if (cells.size < 10) continue
            if (clean(cells[0].text()).toIntOrNull() == null) continue

            val name = clean(cells[2].text())
            if (name.isEmpty()) continue

            // 学期/成绩列 7..(size-3)，共 9 列；取第一个非空格（数字 / 优 / ×）
            var score: String? = null
            var term: String? = null
            val termEnd = (cells.size - 3).coerceAtMost(7 + TERM_LABELS.size - 1)
            for (k in 7..termEnd) {
                val v = clean(cells[k].text())
                if (v.isNotEmpty()) {
                    score = v
                    term = TERM_LABELS.getOrNull(k - 7)
                    break
                }
            }

            out += LearnCourse(
                code = clean(cells[1].text()),
                name = name,
                category = COURSE_ATTR.find(name)?.groupValues?.get(1)?.trim(),
                credit = clean(cells[3].text()).toDoubleOrNull(),
                score = score,
                term = term,
            )
        }
        return out
    }

    /** 表尾"N门"中的门数（服务端统计，可能小于明细行数） */
    private fun pageCount(table: Element): Int? {
        for (row in directRows(table).asReversed()) {
            val m = FOOTER_COUNT.find(clean(directCells(row).firstOrNull()?.text() ?: "")) ?: continue
            return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    /**
     * 已获学分：优先取合计行"获得-学分"（合计行首格形如 "27门"，
     * 最后一格是绩点，故学分合计在倒数第二格）。
     * 页面无合计行时退化为逐课程行累加（数据行 18 格，同样取倒数第二格）。
     */
    private fun earnedCredits(table: Element): Double {
        val rows = directRows(table)
        for (row in rows.asReversed()) {
            val cells = directCells(row)
            if (cells.isEmpty() || !FOOTER_COUNT.matches(clean(cells[0].text()))) continue
            return clean(cells.getOrNull(cells.size - 2)?.text() ?: "").toDoubleOrNull() ?: 0.0
        }

        var sum = 0.0
        for (row in rows) {
            val cells = directCells(row)
            // 数据行至少 17 格；首格序号为纯数字，可排除表头与合计行
            if (cells.size < 17 || clean(cells[0].text()).toIntOrNull() == null) continue
            sum += clean(cells[cells.size - 2].text()).toDoubleOrNull() ?: 0.0
        }
        return sum
    }

    /** 取直接子行：跳过 tbody/thead 包装，避免把内层表的行也算进来 */
    private fun directRows(table: Element): List<Element> {
        val out = ArrayList<Element>()
        for (child in table.children()) {
            when (child.tagName()) {
                "tr" -> out += child
                "tbody", "thead", "tfoot" ->
                    child.children().filter { it.tagName() == "tr" }.forEach { out += it }
            }
        }
        return out
    }

    private fun directCells(row: Element): List<Element> =
        row.children().filter { it.tagName() == "td" || it.tagName() == "th" }

    private fun clean(s: String): String =
        s.replace('\u00a0', ' ').trim().replace(Regex("\\s+"), " ")
}
