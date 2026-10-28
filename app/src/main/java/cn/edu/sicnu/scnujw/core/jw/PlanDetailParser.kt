package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.nodes.Document

/** 教材详情一行 */
data class Textbook(
    val isbn: String,
    val name: String,
    val author: String,
    val publisher: String,
    val date: String,
    val price: String,
    val type: String,
)

/** 执行计划详情：上半部键值对（课程/教师/学时/考核方式…）+ 教材详情表 */
data class PlanDetail(
    val info: LinkedHashMap<String, String>,
    val textbooks: List<Textbook>,
)

/**
 * 解析"执行计划详情"页（ Common/wpExecutePlanDetails.aspx?Zxjhdm=… ）。
 * 实测（2026-10 样本已入 cache/probe2/detail2.html）：
 *  - 键值表：每行 6 个 td 成对排列（键|值|键|值|键|值），值带 &nbsp; 前缀；
 *  - 教材表：表头 序号|ISBN|教材名称|编者|出版社|出版年月|单价|类型，可能无数据行。
 */
object PlanDetailParser {
    /** 大纲链接类伪键，不算课程信息 */
    private val SKIP_KEYS = setOf("教学大纲", "实验大纲", "实施大纲")

    fun parse(doc: Document): PlanDetail {
        val info = LinkedHashMap<String, String>()

        val kvTable = doc.select("table").firstOrNull { t ->
            t.select("td").any { it.text().replace('\u00a0', ' ').trim() == "执行计划代码" }
        }
        if (kvTable != null) {
            for (tr in kvTable.select("tr")) {
                val cells = tr.select("td").map {
                    it.text().replace('\u00a0', ' ').trim()
                }
                var i = 0
                while (i + 1 < cells.size) {
                    val k = cells[i]
                    val v = cells[i + 1]
                    if (k.isNotEmpty() && k !in SKIP_KEYS && !info.containsKey(k)) {
                        info[k] = v
                    }
                    i += 2
                }
            }
        }

        val textbooks = ArrayList<Textbook>()
        val bookTable = doc.select("table").firstOrNull { t ->
            t.select("td").any { it.text().replace('\u00a0', ' ').trim() == "ISBN" }
        }
        bookTable?.select("tr")?.forEach { tr ->
            val c = tr.select("td").map { it.text().replace('\u00a0', ' ').trim() }
            // 数据行：8 列且 ISBN 列不是表头
            if (c.size >= 8 && c[1] != "ISBN" && (c[1].isNotEmpty() || c[2].isNotEmpty())) {
                textbooks.add(
                    Textbook(
                        isbn = c[1], name = c[2], author = c[3], publisher = c[4],
                        date = c[5], price = c[6], type = c[7],
                    )
                )
            }
        }
        return PlanDetail(info, textbooks)
    }
}
