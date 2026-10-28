package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 学生信息卡（wucStudentDetails1 用户控件，服务端直出）。
 * 实测（2026-10 样本 cache/pages/ScoreQuery_wp_MyUsualScore_Query.aspx 页头）：
 * 一张 table.table4，td 成对出现——左格标签、右格值（值前带 &nbsp;）；
 * 第一行末 td[rowspan] 嵌证件照 img[id*=imgphoto]（https://jwc.sicnu.edu.cn/stdPhoto/... 直出）。
 * 卡片实际挂在 wp_MyUsualScore_Query.aspx / wp_MyOtherScore_Query.aspx 页头，
 * wp_StudentIndex.aspx（学生首页-快速）本身无此卡。
 */
data class StudentInfo(
    val sno: String,        // 学号（登录账号本人）
    val name: String,       // 姓名
    val gender: String,     // 性别
    val grade: String,      // 年级，如 2024
    val college: String,    // 学院，如 计算机科学学院
    val major: String,      // 专业，如 软件工程
    val clazz: String,      // 班级，如 计算机科学学院2024级05班
    val eduLength: String,  // 学制，如 4
    val type: String,       // 类型，如 普通类
    val photoUrl: String,   // 证件照地址（https 直出，可为空）
    val extra: Map<String, String>, // 其余字段（考生号/联系电话/身份证号/学生状态/教学计划，含敏感项，UI 不展示）
)

object StudentInfoParser {

    // 卡片已知标签（实测顺序：学号 姓名 性别 年级 学院 专业 班级 学制 类型
    // 考生号 联系电话 学生状态 身份证号 教学计划）。白名单防误配：
    // 值本身也可能是短中文词（如"普通类"），不能靠"短中文即标签"猜测。
    private val LABELS = listOf(
        "学号", "姓名", "性别", "年级", "学院", "专业", "班级", "学制", "类型",
        "考生号", "联系电话", "学生状态", "身份证号", "教学计划",
    )
    private val CORE = setOf("学号", "姓名", "性别", "年级", "学院", "专业", "班级", "学制", "类型")

    fun parse(html: String): StudentInfo? = parse(Jsoup.parse(html))

    fun parse(doc: Document): StudentInfo? {
        val table = findInfoTable(doc) ?: return null
        val tds = table.select("td")
        val pairs = LinkedHashMap<String, String>()
        for (i in tds.indices) {
            val label = tds[i].ownText().replace('\u00a0', ' ').trim()
            if (label !in LABELS) continue
            val value = tds.getOrNull(i + 1)?.ownText()
                ?.replace('\u00a0', ' ')?.trim() ?: ""
            if (value.isNotEmpty()) pairs[label] = value
        }
        val sno = pairs["学号"] ?: return null
        return StudentInfo(
            sno = sno,
            name = pairs["姓名"] ?: "",
            gender = pairs["性别"] ?: "",
            grade = pairs["年级"] ?: "",
            college = pairs["学院"] ?: "",
            major = pairs["专业"] ?: "",
            clazz = pairs["班级"] ?: "",
            eduLength = pairs["学制"] ?: "",
            type = pairs["类型"] ?: "",
            photoUrl = table.selectFirst("img[id*=imgphoto]")?.attr("src")?.trim() ?: "",
            extra = pairs.filterKeys { it !in CORE },
        )
    }

    /**
     * 信息卡定位：候选 = 含证件照的表 或 含"学号"标签且右邻格为纯数字（真学号）的表。
     * 取 td 最少者（最内层）——防止外层布局大表把整页 td 卷进来造成跨行误配。
     */
    fun findInfoTable(doc: Document): Element? =
        doc.select("table")
            .filter { it.selectFirst("img[id*=imgphoto]") != null || hasSnoCell(it) }
            .minByOrNull { it.select("td").size }

    private fun hasSnoCell(table: Element): Boolean =
        table.select("td").any {
            it.ownText().trim() == "学号" &&
                it.nextElementSibling()?.ownText()?.replace('\u00a0', ' ')?.trim()
                    ?.matches(Regex("\\d{6,}")) == true
        }
}
