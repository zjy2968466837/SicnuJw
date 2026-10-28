package cn.edu.sicnu.scnujw.core.jw

import android.os.SystemClock
import cn.edu.sicnu.scnujw.core.auth.AuthGuard
import cn.edu.sicnu.scnujw.core.auth.AuthSystem
import cn.edu.sicnu.scnujw.core.net.JwTraffic
import cn.edu.sicnu.scnujw.core.net.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 教务系统（ASP.NET WebForms，202.115.194.60）客户端。
 * 全部数据页服务端渲染；查询 = 下拉框 __doPostBack + VIEWSTATE 重放。
 * 端点清单实测自 2026-10 探测，详见 scnu-jiaowu/REPORT.md。
 */
object JwClient {
    private const val BASE = "http://202.115.194.60"

    /**
     * 全量平时成绩回发专用：选「全部学期」(-1) 会把所有课程块整页重渲染，
     * 真机实测约 110s（2026-10-27，15 门课/35 个学期选项），单学期慢速 client 的 45s 远不够。
     * 放宽到 180s 留出波动余量；只在本类内新建，不动 SessionManager 的共享 client。
     */
    private val bulkClient: OkHttpClient by lazy {
        SessionManager.client.newBuilder()
            .readTimeout(java.time.Duration.ofSeconds(180))
            .build()
    }

    // —— 核心端点（探测确认）——
    const val STUDENT_INDEX = "Index/wp_StudentIndex.aspx"
    const val LEARN_PROGRESS = "ScoreQuery/wp_MyLearnProgress_Query.aspx"       // 含个人信息
    const val BYXW_PROGRESS = "ScoreQuery/wp_MyByXwProgress_Query.aspx"         // 毕业学位条件
    const val USUAL_SCORE = "ScoreQuery/wp_MyUsualScore_Query.aspx"             // 平时成绩(回发)
    const val ELECT_SCORE = "ScoreQuery/wp_MyElectCourseScore_Query.aspx"       // 选课成绩(回发)
    const val OTHER_SCORE = "ScoreQuery/wp_MyOtherScore_Query.aspx"             // 校外成绩(回发)
    const val EXAM_ARRANGE = "ExamManage/wp_StudentExamArrangeResult_PrintBySelf.aspx" // 考试(回发)
    const val TEACHING_PLAN = "TeachingPlan/wp_MyTeachingPlan_Details.aspx"
    const val ELECT_RESULT = "CurriculumSelectByStudent/wp_ElectCourseResult_List.aspx" // 选课结果

    private fun sid(): String =
        SessionManager.sid ?: throw IllegalStateException("未登录")

    /**
     * 正方在会话失效时会返回 **HTTP 200 + 固定错误页**（title=「系统错误提示」、正文「登录已超时」），
     * 只判 HTTP 码会把它当业务页解析成空结果，并被上游无条件写进当天缓存、覆盖有效缓存。
     * 这里统一判为会话失效，让调用方走失败分支。
     */
    private fun checkPageValid(html: String, path: String) {
        if (html.contains("系统错误提示") || html.contains("登录已超时") ||
            html.contains("Runtime Error") || html.contains("服务器错误")
        ) {
            throw AuthGuard.AuthExpiredException(AuthSystem.ZF, "教务会话已失效或页面异常: $path")
        }
    }

    /**
     * 可取消的 OkHttp 调用：协程取消时 cancel() 掉 socket。
     *
     * 教务是 ASP.NET Session，一个请求全程占住会话锁；离开页面后若请求还挂在后台，
     * 成绩/考试/进度等其他模块会全部排队到超时（2026-10-27 真机实测）。
     * 用 enqueue + invokeOnCancellation 保证离页立即释放会话。
     */
    private suspend fun execute(
        client: OkHttpClient,
        request: Request,
        lowPriority: Boolean = false,
    ): Pair<Int, String> {
        // 普通请求计数：平时成绩的低优先级循环在每学期请求前等待它归零
        if (!lowPriority) JwTraffic.enter()
        try {
            return suspendCancellableCoroutine { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                // 失败耗时要能区分「1 秒就被拒/被取消」与「45 秒读超时」，从 enqueue 前开始计时；
                // 只在失败路径记，成功路径不记（每次刷新都带耗时会淹没真正有用的记录）
                val startedAt = SystemClock.elapsedRealtime()
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        // 诊断事件：只记 host+path、异常类名 + message 前 80 字与耗时。
                        // URL 不带 query（sid 在 query 里）；IOException 的 message 常回显整条 URL，
                        // 不截断会被 MAX_EVENT_CHARS 截成「...」反而看不到异常本身，脱敏仍由 Diag 兜底。
                        val where = "${request.url.host}${request.url.encodedPath}"
                        val msg = e.message.orEmpty().take(80)
                        val why = if (msg.isEmpty()) {
                            e.javaClass.simpleName
                        } else {
                            "${e.javaClass.simpleName}: $msg"
                        }
                        cn.edu.sicnu.scnujw.core.diag.Diag.recordEvent(
                            "net",
                            "$where（$why，${SystemClock.elapsedRealtime() - startedAt}ms）",
                            // 同一页面连续同类型失败 60s 内只落首条 + 汇总，避免 11 条重复占满日志
                            repeatKey = "net:$where:${e.javaClass.simpleName}",
                        )
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { r ->
                            val body = try {
                                r.body?.string() ?: ""
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resumeWithException(e)
                                return
                            }
                            // 诊断日志：记录解析器实际拿到的原始响应（默认开启，只落本机，脱敏见 Diag）
                            cn.edu.sicnu.scnujw.core.diag.Diag.recordHttp(
                                url = request.url.toString(),
                                status = r.code,
                                body = body,
                                contentType = r.header("Content-Type"),
                            )
                            if (cont.isActive) cont.resume(r.code to body)
                        }
                    }
                })
            }
        } finally {
            if (!lowPriority) JwTraffic.exit()
        }
    }

    suspend fun get(path: String): Document = withContext(Dispatchers.IO) {
        val url = "$BASE/$path?sid=${sid()}"
        val (code, html) = execute(SessionManager.client, Request.Builder().url(url).build())
        check(code == 200) { "教务请求失败 $code: $path（会话可能被踢）" }
        checkPageValid(html, path)
        Jsoup.parse(html)
    }

    /**
     * 平时成绩首屏 GET：用慢速 client（45s），并标记为低优先级。
     * 调用方（[UsualRepository]）在每个学期请求前用 `JwTraffic.awaitIdle()` 让普通页面先跑。
     */
    suspend fun getUsualPage(): Document = withContext(Dispatchers.IO) {
        val url = "$BASE/$USUAL_SCORE?sid=${sid()}"
        val (code, html) = execute(
            SessionManager.slowClient,
            Request.Builder().url(url).build(),
            lowPriority = true,
        )
        check(code == 200) { "教务请求失败 $code: $USUAL_SCORE（会话可能被踢）" }
        checkPageValid(html, USUAL_SCORE)
        Jsoup.parse(html)
    }

    /**
     * WebForms 回发重放：携带页面 VIEWSTATE 提交 __doPostBack(target, args)。
     * @param extraFields 随表单提交的字段（如下拉框选中值）
     * @param slow 整页重渲染的慢回发页（平时成绩）用慢速 client，避免 20s 超时误坠兜底
     * @param bulk 全量平时成绩回发（学期 -1）：整页重渲染全部课程块，用 180s 的 [bulkClient]
     */
    suspend fun postBack(path: String, doc: Document, eventTarget: String,
                         eventArgument: String = "",
                         extraFields: Map<String, String> = emptyMap(),
                         slow: Boolean = false,
                         bulk: Boolean = false,
                         lowPriority: Boolean = false): Document =
        withContext(Dispatchers.IO) {
            fun hidden(name: String): String =
                doc.selectFirst("input[name=$name]")?.attr("value") ?: ""

            val form = FormBody.Builder()
                .add("__EVENTTARGET", eventTarget)
                .add("__EVENTARGUMENT", eventArgument)
                .add("__VIEWSTATE", hidden("__VIEWSTATE"))
                .add("__VIEWSTATEGENERATOR", hidden("__VIEWSTATEGENERATOR"))
                .add("__EVENTVALIDATION", hidden("__EVENTVALIDATION"))
            extraFields.forEach { (k, v) -> form.add(k, v) }
            val client = when {
                bulk -> bulkClient
                slow -> SessionManager.slowClient
                else -> SessionManager.client
            }
            val (code, html) = execute(
                client,
                Request.Builder()
                    .url("$BASE/$path?sid=${sid()}")
                    .post(form.build()).build(),
                lowPriority = lowPriority,
            )
            check(code == 200) { "教务回发失败 $code: $path（会话可能被踢）" }
            checkPageValid(html, path)
            Jsoup.parse(html)
        }

    /**
     * 选课成绩（全学期成绩单）。实测三步回发链：
     * ① 学期下拉 ddlItem 值 -1=全部学期
     * ② pager 每页条数下拉 ddlChangePageSize 值 0=全部（默认 10 条/页、全量 77 条 8 页，
     *    不设此步会丢数据——2026-10 真机只显示 10 条的根因）
     */
    suspend fun queryAllElectScores(): Document = withContext(Dispatchers.IO) {
        var doc = get(ELECT_SCORE)
        val ddl = doc.selectFirst("select[id*=ddlItem]")
            ?: return@withContext doc // 无下拉则页面本身即数据
        val ddlName = ddl.attr("name")
        doc = postBack(ELECT_SCORE, doc, ddlName, extraFields = mapOf(ddlName to "-1"))

        // 第②步拿不到「每页条数」下拉时不能把第①步（默认 10 条/页）当全量返回：
        // 那会让 10 条顶掉 77 条旧缓存，且当天不再自动刷新。宁可抛错，让调用方保留旧数据并报错。
        val sizeDdl = doc.selectFirst("select[id*=ddlChangePageSize]")
            ?: throw IllegalStateException(
                "选课成绩页缺少每页条数下拉（ddlChangePageSize）：无法确认已取到全量，拒绝用第一页数据"
            )
        val sizeName = sizeDdl.attr("name")
        val extras = buildMap {
            put(ddlName, "-1")
            put(sizeName, "0")
            doc.selectFirst("select[id*=ddlChangePage]")?.attr("name")?.let { put(it, "1") }
        }
        postBack(ELECT_SCORE, doc, sizeName, extraFields = extras)
    }

    /** 学习进度页（服务端直出 HTML；解析器要扫表格，必须给完整 HTML，不能只给 body 文本） */
    suspend fun fetchLearnProgress(): String = withContext(Dispatchers.IO) {
        get(LEARN_PROGRESS).html()
    }

    /**
     * 学生信息卡页。实测（2026-10 样本）：wp_StudentIndex.aspx 是"学生首页-快速"
     * 静态链接页，不含学生信息卡；卡片（wucStudentDetails1 控件：学号/姓名/学院/专业/
     * 班级/年级/学制/类型 + 证件照）挂在 wp_MyUsualScore_Query.aspx 与
     * wp_MyOtherScore_Query.aspx 页头直出。策略：先抓约定入口学生首页，
     * 无卡片则回退抓我的校外考试成绩页（只读、GET 首屏即卡片）。
     */
    suspend fun fetchStudentIndex(): Document = withContext(Dispatchers.IO) {
        val home = get(STUDENT_INDEX)
        if (StudentInfoParser.findInfoTable(home) != null) home else get(OTHER_SCORE)
    }

    /**
     * 考试安排。实测：学期下拉 wucXndXq1$ddlItem + 考试类型 wucExamType1$ddlItem(-1=全部)，
     * 回发后表头行（td）为 日期|时间|科目|考场|座位号。
     * @param term 学期码；null=页面默认（最新学期）
     * @return Triple(html, 实际学期码, 学期名)
     */
    suspend fun queryExams(term: String? = null): Triple<String, String, String> =
        withContext(Dispatchers.IO) {
            val first = get(EXAM_ARRANGE)
            val ddl = first.selectFirst("select[id*=wucXndXq]")
                ?: return@withContext Triple(first.html(), "", "")
            val name = ddl.attr("name")
            val sel = term ?: ddl.selectFirst("option[selected]")?.attr("value") ?: "-1"
            val extras = buildMap {
                put(name, sel)
                first.selectFirst("select[id*=wucExamType]")?.attr("name")?.let { put(it, "-1") }
            }
            val doc = postBack(EXAM_ARRANGE, first, name, extraFields = extras)
            val label = ddl.select("option[value=$sel]").firstOrNull()?.text()?.trim() ?: sel
            Triple(doc.html(), sel, label)
        }

    /**
     * 平时成绩。实测：GET 首屏即直出默认学期数据（每课一块 rpResult），
     * 学期下拉 wucXndXq1$ddlItem 回发换学期（-1=全部学期）。
     * @param term 学期码；null=页面默认（最新学期）
     * @return Pair(html, 实际学期码)
     */
    suspend fun queryUsualScores(term: String? = null): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val first = get(USUAL_SCORE)
            val ddl = first.selectFirst("select[id*=wucXndXq]")
                ?: return@withContext Pair(first.html(), "")
            val name = ddl.attr("name")
            val sel = term ?: ddl.selectFirst("option[selected]")?.attr("value") ?: "-1"
            // 期末回发要整页重渲染，实测可超 20s——必须走慢速 client
            val doc = postBack(USUAL_SCORE, first, name, extraFields = mapOf(name to sel), slow = true)
            Pair(doc.html(), sel)
        }

    /**
     * 平时成绩单学期回发：复用上一页的 VIEWSTATE（不再每学期重新 GET），
     * 45s 慢速 client + 低优先级。返回页的选中学期必须由调用方核对。
     */
    suspend fun postUsualTerm(doc: Document, term: String): Document = withContext(Dispatchers.IO) {
        val ddl = doc.selectFirst("select[id*=wucXndXq]")
            ?: throw IllegalStateException("平时成绩页缺少学期下拉，无法切换学期")
        val name = ddl.attr("name")
        postBack(
            USUAL_SCORE, doc, name,
            extraFields = mapOf(name to term),
            slow = true,
            lowPriority = true,
        )
    }

    /**
     * 平时成绩全量（学期参数 "-1"）：一次回发取回所有学期课程块。
     * **页面已不再调用**（2026-10-27 改为按学期增量，见 UsualRepository）；
     * 保留仅用于排查/兼容，避免误用长回发。
     * 该回发可能远超 45s 慢速 client，走 [bulkClient]（readTimeout 180s）。
     * @return Pair(html, 返回页实际选中的学期码)。服务端可能忽略 -1 仍回默认单学期页，
     *         调用方必须核对返回页 selectedTerm == "-1" 才允许当「全部学期」缓存。
     */
    suspend fun queryAllUsualScores(): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val first = get(USUAL_SCORE)
            val ddl = first.selectFirst("select[id*=wucXndXq]")
                ?: return@withContext Pair(first.html(), "")
            val name = ddl.attr("name")
            val doc = postBack(USUAL_SCORE, first, name, extraFields = mapOf(name to "-1"), bulk = true)
            val selected = doc.selectFirst("select[id*=wucXndXq] option[selected]")
                ?.attr("value")?.trim() ?: ""
            Pair(doc.html(), selected)
        }

    /**
     * 选课结果（执行计划映射来源）。页面学期下拉 wucXndXq1$ddlItem，
     * 回发换学期后每行有 openWinZxjhDetails('<Zxjhdm>') 按钮。
     * @param term 学期码；null=页面默认（最新学期）
     * @return Pair(html, 实际学期码)
     */
    suspend fun queryElectResult(term: String? = null): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val first = get(ELECT_RESULT)
            val ddl = first.selectFirst("select[id*=wucXndXq]")
                ?: return@withContext Pair(first.html(), "")
            val name = ddl.attr("name")
            val sel = term ?: ddl.selectFirst("option[selected]")?.attr("value") ?: "-1"
            val current = ddl.selectFirst("option[selected]")?.attr("value") ?: ""
            if (sel == current || sel == "-1") {
                return@withContext Pair(first.html(), sel)
            }
            val doc = postBack(ELECT_RESULT, first, name, extraFields = mapOf(name to sel), slow = true)
            Pair(doc.html(), sel)
        }

    /**
     * 执行计划详情页（选课结果→详情）。该页只认 Cookie，sid 参数照带无害；
     * 实测服务端会先 302 一次（curl 默认跟随所以直出内容），而全局 client
     * 为登录链路关闭了 followRedirects——这里用跟随重定向的变体抓取。
     */
    suspend fun fetchPlanDetail(zxjhdm: String): Document =
        withContext(Dispatchers.IO) {
            val follow = SessionManager.client.newBuilder().followRedirects(true).build()
            val url = "$BASE/Common/wpExecutePlanDetails.aspx?Zxjhdm=$zxjhdm&sid=${sid()}"
            val (code, html) = execute(follow, Request.Builder().url(url).build())
            // 不要把 URL 拼进异常消息：URL 带 sid，消息会直接上屏
            check(code == 200) { "详情页请求失败 $code（会话可能被踢）" }
            checkPageValid(html, "Common/wpExecutePlanDetails.aspx")
            Jsoup.parse(html)
        }
}
