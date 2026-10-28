package cn.edu.sicnu.scnujw.core.jwcxk

import cn.edu.sicnu.scnujw.core.auth.AuthGuard
import cn.edu.sicnu.scnujw.core.auth.AuthSystem
import cn.edu.sicnu.scnujw.core.auth.CasTicketClient
import cn.edu.sicnu.scnujw.core.net.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生课表（`LOGIN_SSO_PLAN.md` Step 10）。
 *
 * 数据链（2026-10-26 复核）：
 *   GET /student/for-std/course-table            → 学期 option 列表（153=26-27 第一学期）
 *   GET /student/home/get-current-teach-week     → {"currentSemester":"…","weekIndex":8}
 *   GET /student/for-std/course-table/semester/{id}/print-data?semesterId={id}&hasExperiment=false
 *                                                → 128503 B，studentTableVms[0].activities 22 条
 *
 * `activities[].weekIndexes` 是精确周次（可非连续），比 weeksStr 可靠。
 */
object CourseTableApi {

    data class Result(
        val semesterLabel: String,
        val weekIndex: Int,
        val termStart: String?,
        val sessions: List<CourseSession>,
    )

    class NoSemesterException : Exception("未找到可用学期")

    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        AuthGuard.ensure(AuthSystem.JWCXK)

        val tableHtml = get("/student/for-std/course-table")
        val semesters = parseSemesters(tableHtml)
        val weekJson = runCatching { get("/student/home/get-current-teach-week") }.getOrNull()
        val currentName = weekJson?.let { runCatching { JSONObject(it).optString("currentSemester") }.getOrNull() }
        val weekIndex = weekJson?.let {
            runCatching { JSONObject(it).optInt("weekIndex", 1) }.getOrDefault(1)
        } ?: 1

        val semester = semesters.firstOrNull { it.second.trim() == currentName?.trim() }
            ?: semesters.firstOrNull()
            ?: throw NoSemesterException()

        val printJson = get(
            "/student/for-std/course-table/semester/${semester.first}/print-data" +
                "?semesterId=${semester.first}&hasExperiment=false"
        )
        val termStart = runCatching {
            JSONObject(get("/student/ws/semester/get/${semester.first}")).optString("startDate")
        }.getOrNull()?.ifBlank { null }

        Result(
            semesterLabel = semester.second.trim(),
            weekIndex = weekIndex,
            termStart = termStart,
            sessions = parseActivities(printJson),
        )
    }

    private fun get(path: String): String {
        val resp = SessionManager.client.newCall(
            Request.Builder()
                .url("https://jwcxk.sicnu.edu.cn$path")
                .header("User-Agent", CasTicketClient.UA)
                .build()
        ).execute()
        resp.use {
            if (it.code == 302 || it.code == 401) {
                throw AuthGuard.AuthExpiredException(AuthSystem.JWCXK, "jwcxk 会话失效（HTTP ${it.code}）")
            }
            check(it.code == 200) { "jwcxk 请求失败 ${it.code}: $path" }
            return it.body?.string() ?: ""
        }
    }

    private fun parseSemesters(html: String): List<Pair<String, String>> =
        Regex("<option value=\"(\\d+)\"[^>]*>([^<]+)").findAll(html)
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinctBy { it.first }
            .toList()

    private fun parseActivities(json: String): List<CourseSession> {
        val root = JSONObject(json)
        val vms = root.optJSONArray("studentTableVms") ?: return emptyList()
        if (vms.length() == 0) return emptyList()
        val activities = vms.getJSONObject(0).optJSONArray("activities") ?: return emptyList()
        val out = ArrayList<CourseSession>(activities.length())
        val seen = HashSet<String>()
        for (i in 0 until activities.length()) {
            val a = activities.optJSONObject(i) ?: continue
            val lessonId = a.optString("lessonId")
            val name = a.optString("courseName").ifBlank { a.optString("lessonName") }
            if (name.isBlank()) continue
            val day = a.optInt("weekday", 0)
            val start = a.optInt("startUnit", 0)
            val end = a.optInt("endUnit", start)
            if (day !in 1..7 || start <= 0) continue

            val weeks = parseWeeks(a)
            if (weeks.isEmpty()) continue
            val key = "$lessonId:$day:$start:$end:${weeks.hashCode()}"
            if (!seen.add(key)) continue

            out += CourseSession(
                lessonId = lessonId.ifBlank { name },
                name = name,
                dayOfWeek = day,
                startPeriod = start,
                spanPeriods = (end - start + 1).coerceAtLeast(1),
                weekStart = weeks.min(),
                weekEnd = weeks.max(),
                startTime = a.optString("startTime"),
                endTime = a.optString("endTime"),
                campus = a.optString("campus"),
                room = a.optString("room"),
                teacher = joinArray(a.optJSONArray("teachers")),
                weeks = weeks,
            )
        }
        return out
    }

    private fun parseWeeks(a: JSONObject): Set<Int> {
        val arr = a.optJSONArray("weekIndexes")
        if (arr != null && arr.length() > 0) {
            val set = LinkedHashSet<Int>()
            for (i in 0 until arr.length()) set += arr.optInt(i)
            if (set.isNotEmpty()) return set.sorted().toSet()
        }
        return parseWeeksStr(a.optString("weeksStr"))
    }

    /** "1~4,6~13" / "11~12" / "1-16" / "5" */
    internal fun parseWeeksStr(s: String): Set<Int> {
        if (s.isBlank()) return emptySet()
        val out = LinkedHashSet<Int>()
        s.split(',', '，', '、', ' ').forEach { part ->
            val t = part.trim()
            if (t.isEmpty()) return@forEach
            val m = Regex("(\\d+)\\s*[~～\\-—]\\s*(\\d+)").find(t)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: return@forEach
                val b = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (a <= b && b - a <= 40) for (w in a..b) out += w
            } else {
                t.toIntOrNull()?.let { out += it }
            }
        }
        return out.sorted().toSet()
    }

    private fun joinArray(arr: JSONArray?): String {
        if (arr == null) return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String && v.isNotBlank()) names += v
        }
        return names.joinToString("、")
    }
}
