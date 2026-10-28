package cn.edu.sicnu.scnujw.ui.enroll

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.jw.ElectCourseRow
import cn.edu.sicnu.scnujw.core.jw.ElectResultPage
import cn.edu.sicnu.scnujw.core.jw.ElectResultParser
import cn.edu.sicnu.scnujw.core.jw.JwClient
import cn.edu.sicnu.scnujw.core.jw.fetchElectResultHtml
import cn.edu.sicnu.scnujw.core.net.networkHint
import cn.edu.sicnu.scnujw.ui.common.DataIssue
import cn.edu.sicnu.scnujw.ui.common.ScnuBadgeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuInlineNotice
import cn.edu.sicnu.scnujw.ui.common.ScnuMetaLine
import cn.edu.sicnu.scnujw.ui.common.ScnuNoticeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshBar
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshableSourceBadge
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.common.ScnuStatusBadge
import cn.edu.sicnu.scnujw.ui.common.dataIssueOf
import cn.edu.sicnu.scnujw.ui.common.dataIssueShort
import cn.edu.sicnu.scnujw.ui.common.fmtCredit
import cn.edu.sicnu.scnujw.ui.common.issueCopy
import cn.edu.sicnu.scnujw.ui.theme.BadgeShape
import cn.edu.sicnu.scnujw.ui.theme.NumMedium
import cn.edu.sicnu.scnujw.ui.common.ScnuResumeRefresh
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * 我的选课结果（模块6 结果查询）。
 * 在线：GET 首屏直出数据（JwClient.queryElectResults）；失败回退 assets 样本。
 * 课程卡片点击展开完整字段；行内带 Zxjhdm 时可跳「开课详情」页。
 *
 * nav 为可选参数：由主控接线时传入即可跳 devbrowse；
 * 未接线（null）时按钮只 Toast 提示，不影响本屏其余功能。
 */
class ElectResultViewModel : ViewModel() {
    var courses by mutableStateOf<List<ElectCourseRow>>(emptyList())
        private set
    var terms by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var sources by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var currentTerm by mutableStateOf<String?>(null)
        private set
    var currentSource by mutableStateOf<String?>(null)
        private set
    var totalCount by mutableStateOf(0)
        private set
    var loaded by mutableStateOf(false)     // 已完成一次加载
        private set
    var loading by mutableStateOf(false)
        private set
    var source by mutableStateOf("")        // 数据来源角标（校园网 / 缓存时间）
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 最近一次联网刷新的结果：供角标「已更新」判定（null=本次未联网） */
    var lastRefreshOk by mutableStateOf<Boolean?>(null)
        private set

    /** 无数据时的致命错误分类（登录失效/无网/校园网/服务端） */
    var issue by mutableStateOf<DataIssue?>(null)
        private set

    /** 有缓存时的降级提示 */
    var notice by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null

    /** 请求代数：连续切换筛选时以最后一次选择为准，旧请求的结果不得覆盖新选择 */
    private var loadGen = 0

    fun load(ctx: Context, term: String? = null, src: String? = null, force: Boolean = false) {
        // 旧实现 if (loading) return 会静默丢弃用户在加载期间的筛选操作，
        // 表现为「切了学期/来源但列表没变」。改为取消旧请求，最后一次选择胜出。
        loadJob?.cancel()
        val gen = ++loadGen
        loading = true
        error = null
        lastRefreshOk = null
        issue = null
        notice = null
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // 默认视图沿用 page_elect_result.json；学期/来源筛选视图各自一份缓存
            // （键含 term+src，切回看过的组合不再联网）。两者都先上屏，再按天判断是否刷新。
            val cached: cn.edu.sicnu.scnujw.core.jw.ScoreCache.CachedPage? =
                if (term == null && src == null) {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.readPage(
                        ctx, cn.edu.sicnu.scnujw.core.jw.ScoreCache.Keys.ELECT_RESULT
                    )
                } else {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.readElectResultView(ctx, term, src)
                }
            if (cached != null) {
                if (gen != loadGen) return@launch
                val page = ElectResultParser.parse(cached.html)
                val shown = filterBySource(page, src)
                apply(
                    shown, page.selectedTerm, page.selectedSource,
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.dailyLabel(cached.fetchedAt),
                )
                if (!force && !cn.edu.sicnu.scnujw.core.jw.ScoreCache.needDailyRefresh(cached.fetchedAt)) {
                    if (gen == loadGen) loading = false
                    return@launch
                }
            }
            try {
                val html = JwClient.fetchElectResultHtml(term, src)
                if (gen != loadGen) return@launch
                if (term == null && src == null) {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.writePage(
                        ctx, cn.edu.sicnu.scnujw.core.jw.ScoreCache.Keys.ELECT_RESULT, html
                    )
                } else {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.writeElectResultView(ctx, term, src, html)
                }
                val page = ElectResultParser.parse(html)
                // 服务端偶发不回发「来源」筛选（下拉已选中但表仍是全部行，2026-10-27 实测）；
                // 行内本身带来源列，本地兜底按来源过滤，保证筛选可见生效。
                val shown = filterBySource(page, src)
                apply(shown, page.selectedTerm, page.selectedSource, "实时")
                lastRefreshOk = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (gen != loadGen) return@launch
                lastRefreshOk = false
                val kind = dataIssueOf(e)
                // 不再回落示例数据：有缓存降级显示缓存并提示，无缓存给可操作状态
                if (cached != null) {
                    if (gen == loadGen) {
                        notice = "刷新失败：${dataIssueShort(kind)}，当前显示上次缓存"
                        loading = false
                    }
                    return@launch
                }
                issue = kind
                error = "选课结果暂时读不到。" + networkHint(e)
                loaded = true
            } finally {
                if (gen == loadGen) loading = false
            }
        }
    }

    /** 来源兜底过滤：src 为空或「全部来源」时原样返回；行内来源名与下拉映射一致才筛 */
    private fun filterBySource(page: ElectResultPage, src: String?): ElectResultPage {
        if (src == null || src == "-1") return page
        val label = page.sourceMap[src] ?: return page
        val filtered = page.courses.filter { it.source == label }
        return page.copy(courses = filtered, totalCount = filtered.size)
    }

    private fun apply(page: ElectResultPage, term: String?, src: String?, label: String) {
        courses = page.courses
        terms = page.termMap
        sources = page.sourceMap
        currentTerm = term
        currentSource = src
        totalCount = page.totalCount
        source = label
        loaded = true
    }
}

/** 展开态字段表的标签列宽（统一对齐） */
private val DetailLabelWidth = 88.dp

@Composable
fun ElectResultScreen(
    vm: ElectResultViewModel = viewModel(),
    nav: NavController? = null,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (!vm.loaded) vm.load(ctx) }
    // 回到前台且缓存不是今天时静默复检（页面自己的 load 会判断是否联网）
    ScnuResumeRefresh { if (vm.loaded) vm.load(ctx, vm.currentTerm, vm.currentSource) }

    var termMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    val credits = remember(vm.courses) { vm.courses.sumOf { it.credit } }

    // 已展开的课程卡片；rememberSaveable 保证旋转/重建后展开状态还在
    var expanded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet<String>()) }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(
            title = "我的选课结果",
            onBack = nav?.let { n -> { n.navigateUp() } },
            actions = {
                if (vm.loading && !vm.loaded) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    ScnuRefreshableSourceBadge(
                        source = vm.source,
                        refreshing = vm.loading,
                        success = vm.lastRefreshOk == true,
                        onRefresh = { vm.load(ctx, vm.currentTerm, vm.currentSource, force = true) },
                    )
                }
            },
        )

        if (vm.loading && vm.loaded) {
            ScnuRefreshBar("正在刷新选课结果")
        }
        ScnuInlineNotice(vm.notice, tone = ScnuNoticeTone.Warning)

        // ── 汇总（两栏数字卡） + 学期/来源筛选 ──
        Column(
            Modifier.fillMaxWidth().padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.s),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
            ) {
                MetricCard(
                    value = vm.totalCount.toString(),
                    unit = "门",
                    label = "课程数",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    value = fmtCredit(credits),
                    unit = "学分",
                    label = "学分合计",
                    modifier = Modifier.weight(1f),
                    accent = true,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
            ) {
                Box(Modifier.weight(1f)) {
                    FilterButton(
                        label = termLabel(vm.currentTerm, vm.terms),
                        modifier = Modifier.fillMaxWidth(),
                    ) { termMenu = true }
                    DropdownMenu(expanded = termMenu,
                        onDismissRequest = { termMenu = false }) {
                        DropdownMenuItem(text = { Text("全部学期") }, onClick = {
                            termMenu = false; vm.load(ctx, "-1", vm.currentSource)
                        })
                        vm.terms.forEach { (code, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                termMenu = false; vm.load(ctx, code, vm.currentSource)
                            })
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    FilterButton(
                        label = sourceLabel(vm.currentSource, vm.sources),
                        modifier = Modifier.fillMaxWidth(),
                    ) { sourceMenu = true }
                    DropdownMenu(expanded = sourceMenu,
                        onDismissRequest = { sourceMenu = false }) {
                        DropdownMenuItem(text = { Text("全部来源") }, onClick = {
                            sourceMenu = false; vm.load(ctx, vm.currentTerm, "-1")
                        })
                        vm.sources.forEach { (code, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                sourceMenu = false; vm.load(ctx, vm.currentTerm, code)
                            })
                        }
                    }
                }
            }
        }

        when {
            vm.issue != null -> {
                val issue = vm.issue ?: DataIssue.SERVER
                val copy = issueCopy(issue, subject = "选课结果")
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ScnuStatePanel(
                        title = copy.title,
                        body = copy.body,
                        isError = copy.isError,
                        actionLabel = copy.action,
                        onAction = {
                            if (issue == DataIssue.AUTH_EXPIRED && nav != null) {
                                nav.navigate("auth?system=zf&mode=reauth&ret=elect")
                            } else {
                                vm.load(ctx, vm.currentTerm, vm.currentSource, force = true)
                            }
                        },
                    )
                }
            }
            !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScnuStatePanel(
                    title = "正在加载选课结果",
                    body = "首次进入需要从教务系统拉取本学期选课记录。",
                    loading = true,
                )
            }
            vm.courses.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScnuStatePanel(title = "本学期暂无选课记录")
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(vm.courses, key = { i, c -> "$i-${c.code}-${c.name}" }) { _, c ->
                    val key = courseKey(c)
                    ElectCourseCard(
                        c = c,
                        expanded = key in expanded,
                        onToggle = {
                            expanded = if (key in expanded) expanded - key else expanded + key
                        },
                        nav = nav,
                    )
                }
            }
        }
    }
}

/** 顶部汇总数字卡：NumMedium 数字 + 单位 + 说明，描边分层不投影 */
@Composable
private fun MetricCard(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        color = cs.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value,
                    style = NumMedium,
                    color = if (accent) cs.primary else cs.onSurface,
                    maxLines = 1)
                Text(unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (accent) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, bottom = 4.dp))
            }
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant)
        }
    }
}

/** 筛选按钮：文字 + 下拉箭头，文字取正文色，避免金色大面积铺开 */
@Composable
private fun FilterButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}

/** 展开状态的稳定键：优先执行计划代号（一次开课唯一），缺失时用 代码|课名|来源 */
private fun courseKey(c: ElectCourseRow): String =
    c.zxjhdm?.takeIf { it.isNotBlank() } ?: "${c.code}|${c.name}|${c.source}"

@Composable
private fun ElectCourseCard(
    c: ElectCourseRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    nav: NavController?,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScnuSpacing.m, vertical = 3.dp),
    ) {
        Column(Modifier.padding(ScnuSpacing.m)) {
            // ── 可点击头部：展开 / 收起 ──
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        c.code.ifEmpty { null },
                        c.category.ifEmpty { null },
                        c.college.ifEmpty { null },
                    )
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(ScnuSpacing.xs))
                        ScnuMetaLine(meta)
                    }
                }
                if (c.status.isNotEmpty()) {
                    Spacer(Modifier.width(ScnuSpacing.s))
                    ScnuStatusBadge(
                        c.status,
                        tone = if (c.reviewed) ScnuBadgeTone.Muted else ScnuBadgeTone.Accent,
                    )
                }
                Spacer(Modifier.width(ScnuSpacing.xs))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起课程详情" else "展开课程详情",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (!expanded) {
                // ── 收起态摘要：多段排课 + 教师/校区/人数/学分 ──
                Spacer(Modifier.height(6.dp))
                if (c.schedules.isEmpty()) {
                    Text("上课时间地点待定", style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant)
                } else {
                    c.schedules.forEach { s ->
                        Text(s, style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurface,
                            modifier = Modifier.padding(top = ScnuSpacing.xs))
                    }
                }

                HorizontalDivider(
                    Modifier.padding(top = ScnuSpacing.s),
                    color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    thickness = ScnuTokens.Hairline,
                )

                Row(Modifier.padding(top = ScnuSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.teacher.ifEmpty { "任课教师待定" },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (c.campus.isNotEmpty()) {
                        Text(c.campus, style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.width(ScnuSpacing.s))
                    }
                    c.students?.let {
                        Text("${it}人", style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant)
                        Spacer(Modifier.width(ScnuSpacing.s))
                    }
                    Text("${fmtCredit(c.credit)} 学分",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface)
                }
            } else {
                // ── 展开态：两列字段表（label 定宽 88dp）+ 完整字段 ──
                Spacer(Modifier.height(ScnuSpacing.s))
                HorizontalDivider(
                    color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    thickness = ScnuTokens.Hairline,
                )
                Spacer(Modifier.height(ScnuSpacing.s))

                Surface(
                    color = cs.surface,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        ScnuTokens.CardBorder,
                        cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = ScnuSpacing.s, vertical = 6.dp)) {
                        DetailRow("课程来源", c.source.ifEmpty { "—" })
                        DetailRow("开课学院", c.college.ifEmpty { "—" })
                        DetailRow("课程类别", c.category.ifEmpty { "—" })
                        DetailRow("课程代码", c.code.ifEmpty { "—" })
                        ScheduleRow(c.schedules)
                        DetailRow("上课校区", c.campus.ifEmpty { "—" })
                        DetailRow("上课教师", c.teacher.ifEmpty { "待定" })
                        DetailRow("总学分", "${fmtCredit(c.credit)} 学分")
                        DetailRow("选课人数", c.students?.let { "$it 人" } ?: "—")
                        DetailRow("状态", c.status.ifEmpty { "—" })
                    }
                }

                val zxjhdm = c.zxjhdm?.takeIf { it.isNotBlank() }
                if (zxjhdm != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (nav != null) {
                                val detailUrl =
                                    "Common/wpExecutePlanDetails.aspx?Zxjhdm=$zxjhdm"
                                nav.navigate(
                                    "devbrowse?url=" + URLEncoder.encode(detailUrl, "UTF-8")
                                )
                            } else {
                                Toast.makeText(ctx, "导航未就绪，无法打开完整开课详情",
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        // 次级按钮统一 8dp 小圆角（与状态面板的「重新加载」同一形状）
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
                    ) {
                        Text("完整开课详情",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurface)
                    }
                }
            }
        }
    }
}

/** 展开态的单行字段：左标签定宽 88dp（D_MUTED），右值可换行 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DetailLabelWidth))
        Text(value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
    }
}

/** 上课时间地点：已拆段，每段独立一行；空 = 尚未排课 */
@Composable
private fun ScheduleRow(schedules: List<String>) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("上课时间地点",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DetailLabelWidth))
        Column(Modifier.weight(1f)) {
            if (schedules.isEmpty()) {
                Text("待定", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                schedules.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** 一行元信息（代码/类别/学院）：竖线分隔，替代中点连接符 */

private fun termLabel(code: String?, terms: Map<String, String>): String = when {
    code == null -> "选择学期"
    code == "-1" -> "全部学期"
    else -> terms[code] ?: code
}

private fun sourceLabel(code: String?, sources: Map<String, String>): String = when {
    code == null -> "课程来源"
    code == "-1" -> "全部来源"
    else -> sources[code] ?: code
}
