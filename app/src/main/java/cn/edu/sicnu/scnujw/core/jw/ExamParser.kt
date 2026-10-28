package cn.edu.sicnu.scnujw.core.jw

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 结构化考场。以 "成龙校区第一教学楼1-B213" 为例：
 * campus = "成龙校区"，building = "第一教学楼1"，room = "B213"。
 */
data class RoomParts(
    val campus: String,   // 无"XX校区"前缀时为空串
    val building: String,
    val room: String,     // 最后一段房间号
)

/** 一场考试（考试安排表实测 5 列：日期/时间/科目/考场/座位号） */
data class ExamRow(
    val date: LocalDate,
    val timeStart: LocalTime,
    val timeEnd: LocalTime,
    val course: String,
    val room: String,        // 原始考场串，如 "成龙校区第一教学楼1-B213"（去掉前导 '-'）
    val seat: String,
    val term: String,        // 学期码
    val termLabel: String,
) {
    val startAt: LocalDateTime get() = LocalDateTime.of(date, timeStart)
    val endAt: LocalDateTime get() = LocalDateTime.of(date, timeEnd)

    /** 考场三段结构（首次访问时解析并缓存） */
    val roomParts: RoomParts by lazy { ExamParser.parseRoom(room) }

    /** 距开考（负数=已考完） */
    fun countdownDays(now: LocalDateTime): Long =
        Duration.between(now, startAt).toDays()

    /** 距开考的精确时长（负数=已考完） */
    fun countdownDuration(now: LocalDateTime): Duration =
        Duration.between(now, startAt)
}

object ExamParser {

    /**
     * 把原始考场串解析为 campus / building / room 三段。
     * 规则（格式 "XX校区+楼栋+房间号"）：
     *  1. 以 "校区" 关键字切出校区（含"校区"二字），前缀即校区名；
     *  2. 余下部分按最后一个 '-' 切分，前面是楼栋、后面是房间号。
     * 容错：前导 '-'、不间断空格会被清理；无"校区"时 campus 为空；
     * 无 '-' 时整段视为楼栋、room 为空。
     */
    fun parseRoom(raw: String): RoomParts {
        val text = raw.replace('\u00a0', ' ').trim().removePrefix("-").trim()
        if (text.isEmpty()) return RoomParts("", "", "")

        val campusEnd = text.indexOf("校区").let { if (it >= 0) it + "校区".length else -1 }
        val campus = if (campusEnd >= 0) text.substring(0, campusEnd).trim() else ""
        val rest = if (campusEnd >= 0) {
            text.substring(campusEnd).trim().trimStart('-', ' ')
        } else {
            text
        }
        if (rest.isEmpty()) return RoomParts(campus, "", "")

        val dash = rest.lastIndexOf('-')
        return when {
            dash < 0 -> RoomParts(campus, rest.trim(), "")
            dash == 0 -> RoomParts(campus, "", rest.substring(1).trim())
            else -> RoomParts(
                campus = campus,
                building = rest.substring(0, dash).trim(),
                room = rest.substring(dash + 1).trim(),
            )
        }
    }

    /** 日期单元格：实测 "2026-07-06"；容忍未补零的 "2026-7-6" */
    private val DATE_CELL = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")

    /**
     * 时间单元格：实测 "10:20-12:20"。
     * 注意个位数小时**不补零**（实测 "8:00-10:00"），而 LocalTime.parse 用的
     * ISO_LOCAL_TIME 要求小时两位，直接解析会抛异常——不能用它来判定/丢弃整行。
     */
    private val TIME_RANGE = Regex("""(\d{1,2}):(\d{2})\s*(?:-|~|～|至)\s*(\d{1,2}):(\d{2})""")

    private fun parseDateCell(raw: String): LocalDate? {
        val m = DATE_CELL.matchEntire(raw.trim()) ?: return null
        val (y, mo, d) = m.destructured
        return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    }

    private fun parseTimeCell(raw: String): Pair<LocalTime, LocalTime>? {
        val m = TIME_RANGE.find(raw) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        return runCatching {
            LocalTime.of(h1.toInt(), m1.toInt()) to LocalTime.of(h2.toInt(), m2.toInt())
        }.getOrNull()
    }

    /**
     * 解析回发后的考试安排页。定位规则（实测）：
     * 表头行是 <td>日期|时间|科目|考场|座位号</td>（全站无 th），
     * 数据行紧随其后；考场列带前导 '-' 需 strip。
     *
     * 容错：
     *  - 时间列个位数小时不补零（"8:00-10:00"）必须能解析，否则会静默丢行；
     *  - 数据行的日期列可能被上一行 rowspan 合并（本行只剩 4 列、首列是时间段），
     *    此时沿用上一行日期；5 列但日期单元格为空同样沿用上一行日期；
     *  - 页脚行（打印时间/页码）跳过而不是终止扫描，避免单行异常吞掉后面所有考试。
     */
    fun parse(html: String, term: String, termLabel: String): List<ExamRow> {
        val doc: Document = Jsoup.parse(html)
        val rows = ArrayList<ExamRow>()
        var headerSeen = false
        var lastDate: LocalDate? = null
        for (tr in doc.select("tr")) {
            val tds = tr.select("td")
            fun cell(i: Int) = tds.getOrNull(i)?.text()?.replace('\u00a0', ' ')?.trim() ?: ""
            if (!headerSeen) {
                if (cell(0) == "日期" && cell(1) == "时间" && cell(2) == "科目") headerSeen = true
                continue
            }
            // 日期列被上方 rowspan 合并的两种情况：
            //  a) 4 列且首列是时间段（日期列整列不存在）；
            //  b) 5 列但日期单元格为空（列还在、内容落在上一行）。
            val dateCell = parseDateCell(cell(0))
            val mergedColumn = dateCell == null && tds.size == 4 && parseTimeCell(cell(0)) != null
            val blankDateCell = dateCell == null && tds.size >= 5 && cell(0).isEmpty()
            val inheritDate = (mergedColumn || blankDateCell) && lastDate != null
            val date = dateCell ?: if (inheritDate) lastDate else null
            val shift = if (mergedColumn) 1 else 0
            val times = parseTimeCell(cell(if (mergedColumn) 0 else 1))
            if (date == null || times == null) continue // 页脚/空行/异常行：跳过
            rows.add(
                ExamRow(
                    date = date,
                    timeStart = times.first, timeEnd = times.second,
                    course = cell(2 - shift),
                    room = cell(3 - shift).removePrefix("-").trim(),
                    seat = cell(4 - shift),
                    term = term, termLabel = termLabel,
                )
            )
            lastDate = date
        }
        // rowspan 拆行/重复行可能把同一场考试解析多次：按
        // (日期, 开始时间, 科目, 考场, 座位) 去重，保留首次出现的顺序
        return rows.distinctBy { "${it.date}|${it.timeStart}|${it.course}|${it.room}|${it.seat}" }
    }

    /** 学期码→名称映射（学期下拉选项） */
    fun termMap(html: String): Map<String, String> {
        val doc = Jsoup.parse(html)
        val map = LinkedHashMap<String, String>()
        doc.selectFirst("select[id*=wucXndXq] option[selected]")?.let { sel ->
            // 默认选中项放最前（通常是最新的可查学期）
            map[sel.attr("value")] = sel.text().trim()
        }
        doc.select("select[id*=wucXndXq] option").forEach {
            val v = it.attr("value").trim()
            if (v.isNotEmpty()) map.putIfAbsent(v, it.text().trim())
        }
        return map
    }

    val displayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
}
