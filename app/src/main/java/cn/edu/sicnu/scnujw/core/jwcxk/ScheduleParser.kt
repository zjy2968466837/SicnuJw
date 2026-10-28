package cn.edu.sicnu.scnujw.core.jwcxk

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 课表数据模型与解析器。
 * 实测 DOM 结构（树维 EAMS /student/for-std/course-table 渲染页）：
 *  table.courseTable > tbody > tr（行=节次，class 为行号）
 *   ├ td.dayPartUnit           节次号
 *   └ td.td-content N（N=星期1..7，rowspan=跨节数）
 *       └ div.tdHtml[lessonid]（同一格多门课/多周次会有多个，opacity:0 的是重复变体）
 *          ├ .course-name      课程名
 *          └ div               "(1~16周) (1-2节 8:10-9:45) 成龙校区 1-B202  张红杰"
 */
data class CourseSession(
    val lessonId: String,
    val name: String,
    val dayOfWeek: Int,          // 1=周一 … 7=周日
    val startPeriod: Int,
    val spanPeriods: Int,
    val weekStart: Int,
    val weekEnd: Int,
    val startTime: String,
    val endTime: String,
    val campus: String,
    val room: String,
    val teacher: String,
    /** 精确周次（可非连续）；HTML 路径按 weekStart..weekEnd 生成，原生路径由 weekIndexes 提供 */
    val weeks: Set<Int> = (weekStart..weekEnd).toSet(),
) {
    fun activeOn(week: Int) = week in weeks

    /** 展示用周次标签：1-4,6-13 周 */
    val weeksLabel: String
        get() {
            if (weeks.isEmpty()) return "$weekStart-$weekEnd 周"
            val sorted = weeks.sorted()
            val sb = StringBuilder()
            var start = sorted.first()
            var prev = start
            for (w in sorted.drop(1)) {
                if (w == prev + 1) { prev = w; continue }
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(if (start == prev) "$start" else "$start-$prev")
                start = w
                prev = w
            }
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(if (start == prev) "$start" else "$start-$prev")
            return "$sb 周"
        }
}

data class ParsedSchedule(
    val semesterLabel: String,
    val termStart: String?,      // yyyy-MM-dd，用于计算当前周
    val sessions: List<CourseSession>,
)

object ScheduleParser {

    fun parse(html: String): ParsedSchedule {
        val doc: Document = Jsoup.parse(html)
        val body = doc.body()?.text() ?: ""

        val semester = Regex("(\\d{2}-\\d{2}学年.{0,3}第[一二三四]学期)")
            .find(body)?.groupValues?.get(1) ?: "未知学期"
        val termStart = Regex("学期起始日期[:：]\\s*(\\d{4}-\\d{2}-\\d{2})")
            .find(body)?.groupValues?.get(1)

        val seen = LinkedHashSet<String>()
        val sessions = ArrayList<CourseSession>()

        for (td in doc.select("td.td-content")) {
            val day = td.className().substringAfterLast(' ').toIntOrNull() ?: continue
            val span = td.attr("rowspan").toIntOrNull() ?: 1
            val startPeriod = td.closest("tr")?.className()?.trim()?.toIntOrNull() ?: continue
            for (block in td.select("div.tdHtml")) {
                val lessonId = block.attr("lessonid")
                val name = block.selectFirst(".course-name")?.text()?.trim() ?: continue
                val info = block.select("div")?.last()?.text()?.trim() ?: ""
                val parsed = parseInfo(info) ?: continue
                val key = "$lessonId:$day:$startPeriod:" +
                    (parsed.weeks?.sorted()?.joinToString(",") ?: "${parsed.weekStart}")
                if (seen.add(key)) {
                    sessions.add(
                        CourseSession(
                            lessonId = lessonId.ifBlank { name },
                            name = name,
                            dayOfWeek = day,
                            startPeriod = startPeriod,
                            spanPeriods = span,
                            weekStart = parsed.weekStart,
                            weekEnd = parsed.weekEnd,
                            startTime = parsed.startTime,
                            endTime = parsed.endTime,
                            campus = parsed.campus,
                            room = parsed.room,
                            teacher = parsed.teacher,
                            weeks = parsed.weeks ?: (parsed.weekStart..parsed.weekEnd).toSet(),
                        )
                    )
                }
            }
        }
        return ParsedSchedule(semester, termStart, sessions)
    }

    private data class Info(
        var weekStart: Int = 1, var weekEnd: Int = 20,
        /** 精确周次；HTML 里的非连续写法（如 1~4,6~13 周）解析成功时非 null */
        var weeks: Set<Int>? = null,
        var startTime: String = "", var endTime: String = "",
        var campus: String = "", var room: String = "", var teacher: String = "",
    )

    /** 周次片段：支持 1~16 周 / 1-16 周 / 5 周 / 1~4,6~13 周 / 1,3,5 周 */
    private val WEEK_TOKEN = Regex(
        "\\(\\s*(\\d+(?:\\s*[~～\\-–—]\\s*\\d+)?(?:\\s*[,，]\\s*\\d+(?:\\s*[~～\\-–—]\\s*\\d+)?)*)\\s*周\\s*\\)"
    )

    /** 解析 "(1~16周) (1-2节 8:10-9:45) 成龙校区 1-B202  张红杰" */
    private fun parseInfo(info: String): Info? {
        val i = Info()
        WEEK_TOKEN.find(info)?.let { m ->
            val weeks = LinkedHashSet<Int>()
            m.groupValues[1].split(',', '，').forEach { part ->
                val nums = Regex("\\d+").findAll(part).map { it.value.toInt() }.toList()
                when {
                    nums.size >= 2 -> {
                        val a = minOf(nums[0], nums[1])
                        val b = maxOf(nums[0], nums[1])
                        for (w in a..b) weeks.add(w)
                    }
                    nums.size == 1 -> weeks.add(nums[0])
                }
            }
            if (weeks.isNotEmpty()) {
                i.weeks = weeks
                i.weekStart = weeks.minOrNull() ?: 1
                i.weekEnd = weeks.maxOrNull() ?: 20
            }
        }
        Regex("\\((\\d+)-(\\d+)节\\s*([\\d:]+)-([\\d:]+)\\)").find(info)?.let {
            i.startTime = it.groupValues[3]; i.endTime = it.groupValues[4]
        }
        // 校区+教室：最后一个右括号后、教师前的部分
        val lastParen = info.lastIndexOf(')')
        val afterTime = (if (lastParen >= 0) info.substring(lastParen + 1) else info).trim()
        val m = Regex("^(.*?)\\s+([\\w\\u4e00-\\u9fa5-]+\\d*[\\w-]*)\\s+(\\S.*)$").find(afterTime)
        if (m != null) {
            i.campus = m.groupValues[1].trim()
            i.room = m.groupValues[2].trim()
            i.teacher = m.groupValues[3].trim()
        }
        return if (i.startTime.isEmpty() && i.room.isEmpty()) null else i
    }
}
