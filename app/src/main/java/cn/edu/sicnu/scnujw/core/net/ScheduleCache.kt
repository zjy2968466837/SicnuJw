package cn.edu.sicnu.scnujw.core.net

import android.content.Context
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import cn.edu.sicnu.scnujw.core.jwcxk.CourseSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 课表磁盘快照（跨进程重启首帧上屏用） */
data class CachedSchedule(
    /** 快照归属学号（写入时取 ScoreCache.profileCode）；读取时不一致即视为他人数据 */
    val studentCode: String,
    val fetchedAt: Long,
    val semesterLabel: String,
    val termStart: String?,
    /** 抓取时的教学周；termStart 缺失时用它兜底算当前周 */
    val weekIndex: Int,
    val sessions: List<CourseSession>,
)

/**
 * 课表持久缓存：`filesDir/score_cache/schedule.json`（org.json 手写序列化，不引依赖）。
 *
 * 为什么放 score_cache 目录：它与成绩等业务缓存共用「按学号归属」的生命周期——
 * `ScoreCache.clearAll()` 在换号时整目录清空，课表快照会随之消失，不会把旧账号的课表
 * 留给新账号。退出登录按设计不清数据缓存（重登同一学号直接复用），但此时不可达：
 * 登出已把返回栈清到图根，课表/我的两个 entry 的 ViewModel 一并销毁。
 *
 * 读取时学号与当前账号不一致 → 删除文件并返回 null（宁可多拉一次网络，也不串号）。
 */
object ScheduleCache {
    private const val DIR = "score_cache"
    private const val FILE = "schedule.json"

    private fun file(ctx: Context): File =
        File(File(ctx.filesDir, DIR).apply { mkdirs() }, FILE)

    fun read(ctx: Context): CachedSchedule? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val owner = o.optString("studentCode")
            // 归属校验（schema 里的 studentCode 是唯一凭据，文件名固定、不按学号分开）：
            //  - 快照无主（owner 为空）：写入时还不知道学号（首次登录前 / 身份捕获失败），
            //    无法证明它属于当前账号；当前账号已知（profileCode 非空）时按他人数据删除重拉；
            //  - 快照有主、当前无账号（profileCode 为空）：同样无法证明归属，一并删除；
            //  - 两边都为空：没有任何账号可串号，才允许上屏。
            // 宁可多拉一次网络，也不把上一账号的课表展示给新账号。
            if (owner != ScoreCache.profileCode(ctx).orEmpty()) {
                f.delete()
                return null
            }
            CachedSchedule(
                studentCode = owner,
                fetchedAt = o.getLong("fetchedAt"),
                semesterLabel = o.optString("semesterLabel"),
                termStart = o.optString("termStart").ifBlank { null },
                weekIndex = o.optInt("weekIndex", 0),
                sessions = parseSessions(o.optJSONArray("sessions")),
            )
        } catch (_: Exception) {
            // JSON 损坏：删掉按无缓存处理
            f.delete()
            null
        }
    }

    /**
     * 写入快照。[sessions] 为空时不写：服务端偶尔会因学期切换返回空课表，
     * 用它覆盖掉一份有效快照比不更新更糟（与 UsualPrefetch「空页保留旧缓存」同口径）。
     */
    fun write(
        ctx: Context,
        semesterLabel: String,
        termStart: String?,
        weekIndex: Int,
        sessions: List<CourseSession>,
    ) {
        if (sessions.isEmpty()) return
        try {
            val o = JSONObject()
                .put("studentCode", ScoreCache.profileCode(ctx).orEmpty())
                .put("fetchedAt", System.currentTimeMillis())
                .put("semesterLabel", semesterLabel)
                .put("termStart", termStart ?: "")
                .put("weekIndex", weekIndex)
                .put("sessions", JSONArray().apply {
                    sessions.forEach { s ->
                        put(JSONObject().apply {
                            put("lessonId", s.lessonId); put("name", s.name)
                            put("dayOfWeek", s.dayOfWeek)
                            put("startPeriod", s.startPeriod); put("spanPeriods", s.spanPeriods)
                            put("weekStart", s.weekStart); put("weekEnd", s.weekEnd)
                            put("startTime", s.startTime); put("endTime", s.endTime)
                            put("campus", s.campus); put("room", s.room)
                            put("teacher", s.teacher)
                            // 精确周次（可非连续）必须原样保留，否则 1~4,6~13 会被摊平
                            put("weeks", JSONArray().apply { s.weeks.sorted().forEach { put(it) } })
                        })
                    }
                })
            file(ctx).writeText(o.toString())
        } catch (_: Exception) {
            // 缓存写失败不影响主流程
        }
    }

    private fun parseSessions(arr: JSONArray?): List<CourseSession> {
        if (arr == null) return emptyList()
        val out = ArrayList<CourseSession>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            val weekStart = o.optInt("weekStart", 1)
            val weekEnd = o.optInt("weekEnd", weekStart)
            val weeks = LinkedHashSet<Int>()
            o.optJSONArray("weeks")?.let { wa ->
                for (j in 0 until wa.length()) weeks += wa.optInt(j)
            }
            out += CourseSession(
                lessonId = o.optString("lessonId"),
                name = name,
                dayOfWeek = o.optInt("dayOfWeek", 1),
                startPeriod = o.optInt("startPeriod", 1),
                spanPeriods = o.optInt("spanPeriods", 1).coerceAtLeast(1),
                weekStart = weekStart,
                weekEnd = weekEnd,
                startTime = o.optString("startTime"),
                endTime = o.optString("endTime"),
                campus = o.optString("campus"),
                room = o.optString("room"),
                teacher = o.optString("teacher"),
                weeks = if (weeks.isNotEmpty()) weeks else (weekStart..weekEnd).toSet(),
            )
        }
        return out
    }
}
