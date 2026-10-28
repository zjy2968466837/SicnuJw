package cn.edu.sicnu.scnujw.core.jw

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 带抓取时间戳的缓存条目 */
data class Cached<T>(val data: T, val fetchedAt: Long)

/**
 * 成绩 / 选课结果的进程外持久缓存（filesDir 下 score_cache 目录里的 json，org.json 序列化）。
 *
 * 刷新策略（按页面）：
 *  - 成绩/选课/考试/学业进度/学生信息卡：缓存不是「今天」时允许自动联网刷新一次；
 *  - 课表快照：7 天内直接用缓存（needWeeklyRefresh）；
 *  - 平时成绩：有缓存即缓存态，进入页面不自动联网（见 UsualRepository.readCache）；
 *  - 无缓存 / JSON 损坏：按无缓存处理（损坏文件删除后重新拉取）。
 *
 * 注：旧版「学期发布窗口期」(inUpdateWindow / needAutoRefresh) 只保留给
 * 执行计划映射（findZxjhdm）等非成绩数据；成绩页已统一改为按天（needDailyRefresh）。
 *
 * 例外：[readStudentPhoto] / [writeStudentPhoto] 的证件照缓存是二进制原图（.img）加一行属主学号（.owner），
 * 不是 JSON；它与其余缓存同目录、同生命周期，但不参与 readJson / readAllUsualTerms 的扫描。
 */
object ScoreCache {
    private const val DIR = "score_cache"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun file(ctx: Context, name: String) = File(dir(ctx), "$name.json")

    private val _generation = MutableStateFlow(0)

    /**
     * 缓存代次：每次 [clearAll] 自增一次。
     * 删磁盘文件不会让界面里已经解码的数据（如证件照 Bitmap）消失，
     * 调用方必须按代次丢弃内存副本，否则换号后屏幕上还留着上一账号的内容。
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** 清空全部缓存（含证件照），并推进 [generation] 让界面丢掉已解码的内存副本；换账号登录时调用，防止读到他人数据 */
    fun clearAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        _generation.value += 1
    }

    /** 成绩发布窗口：7/8/9 月与 12/1/2/3 月（12 月不自动刷新，跨年到 1 月再刷） */
    private fun inUpdateWindow(today: LocalDate = LocalDate.now()): Boolean =
        today.monthValue in setOf(1, 2, 3, 7, 8, 9)

    /** 旧口径（仅执行计划映射等使用）：无缓存 / 发布窗口期内过期才刷新；成绩页走 [needDailyRefresh] */
    fun needAutoRefresh(fetchedAt: Long?): Boolean {
        if (fetchedAt == null) return true
        return inUpdateWindow() && System.currentTimeMillis() - fetchedAt > DAY_MS
    }

    /** 缓存时间标签：统一「缓存 MM-dd HH:mm」（同日也带日期，避免把「今天的 09:12」误读成别的） */
    fun ageLabel(fetchedAt: Long): String = dailyLabel(fetchedAt)

    // ── 通用 JSON 读写 ──

    private fun readJson(ctx: Context, name: String): JSONObject? = try {
        val f = file(ctx, name)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (_: Exception) {
        file(ctx, name).delete()
        null
    }

    private fun writeJson(ctx: Context, name: String, obj: JSONObject) {
        try {
            file(ctx, name).writeText(obj.toString())
        } catch (_: Exception) {
            // 缓存写失败不影响主流程
        }
    }

    private fun safeKey(s: String) = s.replace(Regex("[^0-9A-Za-z_-]"), "_")

    // ── 选课成绩（全学期成绩单）──

    private const val ELECT_SCORES = "elect_scores"

    fun readElectScores(ctx: Context): Cached<List<ScoreRow>>? {
        val o = readJson(ctx, ELECT_SCORES) ?: return null
        return try {
            Cached(parseScoreRows(o.getJSONArray("rows")), o.getLong("fetchedAt"))
        } catch (_: Exception) {
            file(ctx, ELECT_SCORES).delete()
            null
        }
    }

    fun writeElectScores(ctx: Context, rows: List<ScoreRow>) {
        writeJson(
            ctx, ELECT_SCORES,
            JSONObject()
                .put("fetchedAt", System.currentTimeMillis())
                .put("rows", scoreRowsToJson(rows)),
        )
    }

    private fun scoreRowsToJson(rows: List<ScoreRow>): JSONArray = JSONArray().apply {
        rows.forEach { r ->
            put(JSONObject().apply {
                put("term", r.term); put("termLabel", r.termLabel)
                put("source", r.source); put("college", r.college)
                put("category", r.category); put("code", r.code)
                put("name", r.name); put("teacher", r.teacher)
                put("credit", r.credit); put("scoreText", r.scoreText)
                r.gpa?.let { put("gpa", it) }
            })
        }
    }

    private fun parseScoreRows(a: JSONArray): List<ScoreRow> = buildList {
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            add(
                ScoreRow(
                    term = o.optString("term"),
                    termLabel = o.optString("termLabel"),
                    source = o.optString("source"),
                    college = o.optString("college"),
                    category = o.optString("category"),
                    code = o.optString("code"),
                    name = o.optString("name"),
                    teacher = o.optString("teacher"),
                    credit = o.optDouble("credit", 0.0),
                    scoreText = o.optString("scoreText"),
                    gpa = if (o.has("gpa")) o.optDouble("gpa") else null,
                )
            )
        }
    }

    // ── 平时成绩 ──
    // 新口径：按学期分文件（usual_term_<term>）增量落盘，支持逐学期上屏/续传；
    // usual_all 只在所有目标学期补齐后写一份聚合快照（兼容旧读法，不作为部分结果使用）。

    private const val USUAL_ALL = "usual_all"
    private const val USUAL_TERM_PREFIX = "usual_term_"

    private fun readUsualFile(ctx: Context, name: String): Cached<UsualScorePage>? {
        val o = readJson(ctx, name) ?: return null
        return try {
            val termMap = LinkedHashMap<String, String>()
            o.optJSONObject("termMap")?.let { m ->
                m.keys().forEach { k -> termMap[k] = m.optString(k) }
            }
            val courses = ArrayList<UsualScoreCourse>()
            val arr = o.getJSONArray("courses")
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val items = ArrayList<UsualScoreItem>()
                val ia = c.optJSONArray("items")
                if (ia != null) for (j in 0 until ia.length()) {
                    val it = ia.getJSONObject(j)
                    items.add(
                        UsualScoreItem(
                            name = it.optString("name"),
                            full = if (it.has("full")) it.optDouble("full") else null,
                            score = if (it.has("score")) it.optDouble("score") else null,
                        )
                    )
                }
                courses.add(
                    UsualScoreCourse(
                        term = c.optString("term"),
                        termLabel = c.optString("termLabel"),
                        source = c.optString("source"),
                        college = c.optString("college"),
                        category = c.optString("category"),
                        code = c.optString("code"),
                        name = c.optString("name"),
                        teacher = c.optString("teacher"),
                        items = items,
                    )
                )
            }
            Cached(
                UsualScorePage(courses, termMap, o.optString("selectedTerm")),
                o.getLong("fetchedAt"),
            )
        } catch (_: Exception) {
            file(ctx, name).delete()
            null
        }
    }

    private fun usualPageToJson(
        page: UsualScorePage,
        fetchedAt: Long = System.currentTimeMillis(),
    ): JSONObject = JSONObject()
        .put("fetchedAt", fetchedAt)
        .put("selectedTerm", page.selectedTerm)
        .put("termMap", JSONObject().apply { page.termMap.forEach { (k, v) -> put(k, v) } })
        .put("courses", JSONArray().apply {
            page.courses.forEach { c ->
                put(JSONObject().apply {
                    put("term", c.term); put("termLabel", c.termLabel)
                    put("source", c.source); put("college", c.college)
                    put("category", c.category); put("code", c.code)
                    put("name", c.name); put("teacher", c.teacher)
                    put("items", JSONArray().apply {
                        c.items.forEach { itm ->
                            put(JSONObject().apply {
                                put("name", itm.name)
                                itm.full?.let { v -> put("full", v) }
                                itm.score?.let { v -> put("score", v) }
                            })
                        }
                    })
                })
            }
        })

    /**
     * 全量聚合快照（selectedTerm == "-1"）。
     * 只在目标学期全部补齐后写入；读取时仍优先使用分学期文件。
     */
    fun readUsualAll(ctx: Context): Cached<UsualScorePage>? =
        readUsualFile(ctx, USUAL_ALL) ?: readUsual(ctx, "default")

    /** 写入全量聚合快照；调用方应把 page.selectedTerm 置为 "-1"（表示全部学期口径） */
    fun writeUsualAll(ctx: Context, page: UsualScorePage, fetchedAt: Long = System.currentTimeMillis()) {
        writeJson(ctx, USUAL_ALL, usualPageToJson(page, fetchedAt))
    }

    // ── 分学期平时成绩（增量/续传的持久层）──

    fun readUsualTerm(ctx: Context, term: String): Cached<UsualScorePage>? =
        readUsualFile(ctx, USUAL_TERM_PREFIX + safeKey(term))

    fun writeUsualTerm(
        ctx: Context,
        term: String,
        page: UsualScorePage,
        fetchedAt: Long = System.currentTimeMillis(),
    ) {
        writeJson(ctx, USUAL_TERM_PREFIX + safeKey(term), usualPageToJson(page, fetchedAt))
    }

    /** 读取全部已落盘的分学期快照：term -> (page, fetchedAt) */
    fun readAllUsualTerms(ctx: Context): Map<String, Cached<UsualScorePage>> {
        val out = LinkedHashMap<String, Cached<UsualScorePage>>()
        dir(ctx).listFiles()?.forEach { f ->
            val name = f.name
            if (!name.startsWith(USUAL_TERM_PREFIX) || !name.endsWith(".json")) return@forEach
            val entry = readUsualFile(ctx, name.removeSuffix(".json")) ?: return@forEach
            val term = entry.data.selectedTerm
            if (term.isNotBlank()) out[term] = entry
        }
        return out
    }

    /** 旧版按学期分文件读取（缓存兼容保留；新版用 [readUsualTerm]） */
    fun readUsual(ctx: Context, termKey: String): Cached<UsualScorePage>? =
        readUsualFile(ctx, "usual_${safeKey(termKey)}")

    /** 旧版按学期分文件写入（缓存兼容保留；新版用 [writeUsualTerm]） */
    fun writeUsual(ctx: Context, termKey: String, page: UsualScorePage) {
        writeJson(ctx, "usual_${safeKey(termKey)}", usualPageToJson(page))
    }

    // ── 选课结果（执行计划映射，按学期码缓存）──

    fun readElectPlans(ctx: Context, termKey: String): Cached<List<ElectPlanRow>>? {
        val name = "electplans_${safeKey(termKey)}"
        val o = readJson(ctx, name) ?: return null
        return try {
            val rows = ArrayList<ElectPlanRow>()
            val arr = o.getJSONArray("rows")
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                rows.add(
                    ElectPlanRow(
                        zxjhdm = r.optString("zxjhdm"),
                        courseCode = r.optString("courseCode"),
                        courseName = r.optString("courseName"),
                        teacher = r.optString("teacher"),
                        category = r.optString("category"),
                        college = r.optString("college"),
                        campus = r.optString("campus"),
                        timePlace = r.optString("timePlace"),
                    )
                )
            }
            Cached(rows, o.getLong("fetchedAt"))
        } catch (_: Exception) {
            file(ctx, name).delete()
            null
        }
    }

    fun writeElectPlans(ctx: Context, termKey: String, rows: List<ElectPlanRow>) {
        val o = JSONObject()
            .put("fetchedAt", System.currentTimeMillis())
            .put("rows", JSONArray().apply {
                rows.forEach { r ->
                    put(JSONObject().apply {
                        put("zxjhdm", r.zxjhdm); put("courseCode", r.courseCode)
                        put("courseName", r.courseName); put("teacher", r.teacher)
                        put("category", r.category); put("college", r.college)
                        put("campus", r.campus); put("timePlace", r.timePlace)
                    })
                }
            })
        writeJson(ctx, "electplans_${safeKey(termKey)}", o)
    }

    /**
     * 课程 → 执行计划代码（Zxjhdm）。term=null 表示选课结果页默认学期（课表用）。
     * 先按课程代码精确匹配，再按课程名归一化匹配；查询失败降级用已有缓存。
     */
    suspend fun findZxjhdm(ctx: Context, term: String?, code: String, name: String): String? {
        fun norm(s: String) = s.filterNot { it.isWhitespace() }
        val key = term ?: "default"
        var cached = readElectPlans(ctx, key)
        // term=null 指向服务端「选课结果页默认学期」，每学期都会变，且 Zxjhdm 只在学期内唯一：
        // 默认学期按天刷新（不受成绩发布窗口限制），显式学期码仍走窗口策略。
        val stale = if (term == null) needDailyRefresh(cached?.fetchedAt)
        else cached == null || needAutoRefresh(cached.fetchedAt)
        if (stale) {
            try {
                val (html, sel) = JwClient.queryElectResult(term)
                val rows = ElectPlanParser.parse(html)
                if (rows.isNotEmpty()) {
                    writeElectPlans(ctx, key, rows)
                    // 默认学期同时按实际学期码再存一份，成绩页按学期查时可直接命中
                    if (term == null && sel.isNotEmpty() && sel != "-1" && sel != key) {
                        writeElectPlans(ctx, sel, rows)
                    }
                    cached = Cached(rows, System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 拉取失败：有旧缓存则继续用，否则返回 null
            }
        }
        val rows = cached?.data ?: return null
        if (code.isNotBlank()) {
            rows.firstOrNull { it.courseCode == code }?.let { return it.zxjhdm }
        }
        val n = norm(name)
        rows.firstOrNull { norm(it.courseName) == n }?.let { return it.zxjhdm }
        rows.firstOrNull {
            val rn = norm(it.courseName)
            rn.isNotEmpty() && n.isNotEmpty() && (rn.contains(n) || n.contains(rn))
        }?.let { return it.zxjhdm }
        return null
    }

    // ── 原始页面缓存（选课结果 / 考试 / 学业进度 / 学生信息卡）──
    //
    // 这四页的模型结构比成绩复杂，直接缓存服务端返回的 HTML，读时用既有 Parser 重新解析：
    // 解析逻辑只有一份，缓存不会随模型字段变化而失效。

    object Keys {
        const val ELECT_RESULT = "page_elect_result"
        const val EXAMS = "page_exams"
        const val PROGRESS = "page_progress"
        const val STUDENT_INFO = "page_student_info"
    }

    data class CachedPage(
        val html: String,
        /** 页面筛选状态（考试=学期码，选课结果=学期码），默认视图为 null */
        val meta1: String?,
        val meta2: String?,
        val fetchedAt: Long,
    )

    fun readPage(ctx: Context, name: String): CachedPage? {
        val o = readJson(ctx, name) ?: return null
        return try {
            CachedPage(
                html = o.getString("html"),
                meta1 = if (o.has("meta1")) o.optString("meta1") else null,
                meta2 = if (o.has("meta2")) o.optString("meta2") else null,
                fetchedAt = o.getLong("fetchedAt"),
            )
        } catch (_: Exception) {
            file(ctx, name).delete()
            null
        }
    }

    fun writePage(ctx: Context, name: String, html: String, meta1: String? = null, meta2: String? = null) {
        val o = JSONObject()
            .put("fetchedAt", System.currentTimeMillis())
            .put("html", html)
        meta1?.let { o.put("meta1", it) }
        meta2?.let { o.put("meta2", it) }
        writeJson(ctx, name, o)
    }

    // ── 考试筛选视图（按学期分文件）──
    //
    // 默认视图仍存 page_exams.json；用户在下拉里切到某学期时，该学期的页面单独落一份。
    // 这样筛选视图再次进入/重新选择时先读缓存，不会因为「只缓存默认视图」而每次联网刷新。

    private const val EXAM_TERM_PREFIX = "page_exams_term_"

    fun readExamTerm(ctx: Context, term: String): CachedPage? =
        readPage(ctx, EXAM_TERM_PREFIX + safeKey(term))

    fun writeExamTerm(ctx: Context, term: String, html: String, label: String?) {
        writePage(ctx, EXAM_TERM_PREFIX + safeKey(term), html, term, label)
    }

    // ── 证件照（二进制原图缓存 + 属主）──
    //
    // 放在同一个 dir(ctx) 下，与成绩缓存同生命周期：换号登录 onAccountKnown -> clearAll
    // 会连同成绩一起删掉，不会把上一账号的照片留给新账号。
    // 原图（student_photo.img，二进制）与属主学号（student_photo.owner，一行文本）分开存：
    // 照片本身无法自证归属，读时必须比对属主——这与卡片文字字段用 profile.json 校验缓存是同一口径。
    // .img / .owner 都不参与 readJson / readAllUsualTerms 的扫描（后者只认 usual_term_*.json）。
    //
    // 另外 clearAll 会推进 [generation]：磁盘文件删掉后，界面里已 decode 的 Bitmap 不会自己消失，
    // 调用方必须按代次丢弃，否则换号后屏幕上还留着上一账号的照片。

    private const val STUDENT_PHOTO = "student_photo.img"
    private const val STUDENT_PHOTO_OWNER = "student_photo.owner"

    /**
     * 读证件照缓存：只有缓存属主与 [owner] 一致才返回字节。
     * [owner] 是页面上已经过归属校验的学号（或 profile.json 记账学号）；为空、缺属主文件、
     * 归属不符、读失败一律返回 null（按无缓存处理，不抛错）。
     */
    fun readStudentPhoto(ctx: Context, owner: String): ByteArray? {
        val who = owner.trim()
        if (who.isEmpty()) return null
        return try {
            val ownerFile = File(dir(ctx), STUDENT_PHOTO_OWNER)
            val matched = ownerFile.exists() && ownerFile.readText().trim() == who
            if (!matched) {
                null
            } else {
                val image = File(dir(ctx), STUDENT_PHOTO)
                if (image.exists()) image.readBytes().takeIf { it.isNotEmpty() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 写证件照缓存（原图 + 属主 [owner] 学号）；[owner] 为空不写——
     * 不能落一份无法自证归属的照片。写失败静默忽略（下次进入页面按无缓存处理）。
     */
    fun writeStudentPhoto(ctx: Context, bytes: ByteArray, owner: String) {
        val who = owner.trim()
        if (who.isEmpty()) return
        try {
            val ownerFile = File(dir(ctx), STUDENT_PHOTO_OWNER)
            // 先删属主再写图：任一步失败，文件都无法通过归属校验，读侧只会当作无缓存
            ownerFile.delete()
            File(dir(ctx), STUDENT_PHOTO).writeBytes(bytes)
            ownerFile.writeText(who)
        } catch (_: Exception) {
            // 缓存写失败不影响主流程
        }
    }

    // ── 入学年份（学期下拉裁剪依据）──
    //
    // 不新增网络请求：学号取学生信息卡页面缓存（含 sno），回退 profile.json 记账学号；
    // 年级优先学生信息卡页头「年级」，其次平时成绩页头解析出的年级（writeGrade 落盘）。
    // 两者都拿不到就返回 null，调用方必须按「不过滤」处理——绝不凭猜测隐藏数据。

    private const val GRADE = "grade"

    private fun gradeOf(raw: String): Int? =
        Regex("(?:19|20)\\d{2}").find(raw)?.value?.toIntOrNull()

    /** 平时成绩页头解析出的年级缓存（StudentInfoParser.parse(html)?.grade → writeGrade） */
    fun readGrade(ctx: Context): Int? =
        readJson(ctx, GRADE)?.optInt("grade", 0)?.takeIf { it in 2000..2100 }

    /** 写入年级原文（"2024" / "2024级" 均可）；解析不出四位年份则不写 */
    fun writeGrade(ctx: Context, raw: String) {
        val year = gradeOf(raw) ?: return
        writeJson(
            ctx, GRADE,
            JSONObject().put("grade", year).put("updatedAt", System.currentTimeMillis()),
        )
    }

    /**
     * 入学年份 = min(学号前四位年份, 年级)，学号优先。
     *
     * 「年级」字段可能晚于实际修课年份（样例学号 2023 开头、年级 2024，
     * 2023-2024 学年 23241/23242 已有 32 条成绩）：只按年级裁剪会把有成绩的旧学期删掉，
     * 所以取两者中较早的年份。只有一项时用该项；都拿不到返回 null（= 不过滤学期）。
     */
    fun enrollmentYear(ctx: Context): Int? {
        val info = readPage(ctx, Keys.STUDENT_INFO)?.let { StudentInfoParser.parse(it.html) }
        val snoYear = (info?.sno?.takeIf { it.isNotBlank() } ?: profileCode(ctx))
            ?.take(4)?.toIntOrNull()?.takeIf { it in 2000..2100 }
        val gradeYear = info?.grade?.let { gradeOf(it) } ?: readGrade(ctx)
        return when {
            snoYear != null && gradeYear != null -> minOf(snoYear, gradeYear)
            snoYear != null -> snoYear
            else -> gradeYear
        }
    }

    // ── 缓存归属账号（单账号单配置）──
    //
    // profile.json 记住当前配置属于哪个学号：
    //  - 退出登录不清数据缓存；重新登录同一学号直接复用；
    //  - 登录后发现学号不同 → 作废在途预取并清空全部数据缓存，换成新账号的配置（不保留多份）。

    private const val PROFILE = "profile"

    fun profileCode(ctx: Context): String? =
        readJson(ctx, PROFILE)?.optString("studentCode")?.takeIf { it.isNotBlank() }

    fun profileName(ctx: Context): String? =
        readJson(ctx, PROFILE)?.optString("name")?.takeIf { it.isNotBlank() }

    fun onAccountKnown(ctx: Context, studentCode: String, name: String = "") {
        if (studentCode.isBlank()) return
        val old = profileCode(ctx)
        if (old != null && old != studentCode) {
            // 先作废在途的全量平时成绩预取：否则旧账号的结果可能在 clearAll 之后写回新账号
            UsualPrefetch.invalidate()
            clearAll(ctx)
        }
        writeJson(
            ctx, PROFILE,
            JSONObject()
                .put("studentCode", studentCode)
                .put("name", name)
                .put("updatedAt", System.currentTimeMillis()),
        )
        // 使用统计的唯一挂点：只把学号前 4 位交给 StatsReporter，完整学号不出本函数
        // （隐私边界见 core/stats/StatsReporter.kt；开关关闭/未配置服务器时什么都不做）
        cn.edu.sicnu.scnujw.core.stats.StatsReporter.onAccountSeen(ctx, studentCode)
    }

    // ── 按天自动刷新（成绩/选课/考试/进度/学生卡；课表按周；平时成绩不适用）──
    //
    // 上述页面：缓存不是“今天”的就允许自动刷新一次，成功后用当天数据覆盖；
    // 同一天内再次进入页面直接用缓存，不再联网（手动刷新 force=true 除外）。
    // 平时成绩是显式例外：有缓存即缓存态，进入页面不联网，见 UsualRepository.readCache。

    fun isToday(fetchedAt: Long): Boolean =
        Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

    fun needDailyRefresh(fetchedAt: Long?): Boolean = fetchedAt == null || !isToday(fetchedAt)

    /** 周级刷新（课表快照）：7 天内直接用缓存，超过 7 天才允许自动联网 */
    fun needWeeklyRefresh(fetchedAt: Long?): Boolean =
        fetchedAt == null || System.currentTimeMillis() - fetchedAt > WEEK_MS

    /** 缓存时间标签：始终「缓存 MM-dd HH:mm」（日期在前，再接时间） */
    fun dailyLabel(fetchedAt: Long): String {
        val t = Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault())
        return "缓存 ${"%02d-%02d %02d:%02d".format(t.monthValue, t.dayOfMonth, t.hour, t.minute)}"
    }
}
