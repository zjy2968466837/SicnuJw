package cn.edu.sicnu.scnujw.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 我的选课结果（模块6·结果查询）。
 *
 * 实测（2026-10 样本 m6_CurriculumSelectByStudent_wp_ElectCourseResult_List_aspx.html）：
 * GET 首屏即直出默认学期全部数据（13 条，无需任何回发）；学期/课程来源两个下拉
 * （wucXndXq1$ddlItem、wucTaskSource1$ddlItem）与分页才走 __doPostBack。
 * 因此默认路径就是 get + 解析；仅当显式传筛选值、或总数超过单页条数时才回发。
 */
/**
 * 抓取原始 HTML（缓存用）；筛选/分页逻辑与 queryElectResults 完全一致。
 * 默认视图（term/source 都为 null）的 HTML 会被页面缓存复用。
 */
suspend fun JwClient.fetchElectResultHtml(
    term: String? = null,
    source: String? = null,
): String = withContext(Dispatchers.IO) {
    var doc = JwClient.get(JwClient.ELECT_RESULT)

    val termDdl = doc.selectFirst("select[id*=wucXndXq]")
    val sourceDdl = doc.selectFirst("select[id*=wucTaskSource]")
    val termName = termDdl?.attr("name")?.takeIf { it.isNotEmpty() }
    val sourceName = sourceDdl?.attr("name")?.takeIf { it.isNotEmpty() }
    val currentTerm = termDdl?.selectFirst("option[selected]")?.attr("value")
    val currentSource = sourceDdl?.selectFirst("option[selected]")?.attr("value")

    // ① 显式切换筛选才回发（默认 GET 首屏即全量数据）
    val switchTerm = term != null && term != currentTerm
    val switchSource = source != null && source != currentSource
    if ((switchTerm || switchSource) && (termName != null || sourceName != null)) {
        val target = if (switchTerm && termName != null) termName
        else sourceName ?: termName!!
        val extras = buildMap {
            termName?.let { put(it, term ?: currentTerm ?: "-1") }
            sourceName?.let { put(it, source ?: currentSource ?: "-1") }
        }
        doc = JwClient.postBack(JwClient.ELECT_RESULT, doc, target, extraFields = extras)
    }

    // ② 分页兜底：默认每页 30 条，若"共 N 条"大于当前页行数，切到"全部(0)"
    val firstParsed = ElectResultParser.parse(doc.html())
    if (firstParsed.courses.size < firstParsed.totalCount) {
        val sizeDdl = doc.selectFirst("select[id$=ddlChangePageSize]")
        if (sizeDdl != null) {
            val extras = buildMap {
                termName?.let { put(it, term ?: currentTerm ?: "-1") }
                sourceName?.let { put(it, source ?: currentSource ?: "-1") }
                doc.selectFirst("select[id$=ddlChangePage]")?.attr("name")
                    ?.takeIf { it.isNotEmpty() }?.let { put(it, "1") }
            }
            doc = JwClient.postBack(
                JwClient.ELECT_RESULT, doc, sizeDdl.attr("name"),
                extraFields = extras + (sizeDdl.attr("name") to "0"),
            )
        }
    }

    doc.html()
}

suspend fun JwClient.queryElectResults(
    term: String? = null,
    source: String? = null,
): ElectResultPage = ElectResultParser.parse(fetchElectResultHtml(term, source))
