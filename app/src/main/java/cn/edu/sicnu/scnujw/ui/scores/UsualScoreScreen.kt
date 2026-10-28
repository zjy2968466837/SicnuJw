package cn.edu.sicnu.scnujw.ui.scores

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import cn.edu.sicnu.scnujw.core.jw.ScoreParser
import cn.edu.sicnu.scnujw.core.jw.UsualProgress
import cn.edu.sicnu.scnujw.core.jw.UsualRepository
import cn.edu.sicnu.scnujw.core.jw.UsualScoreCourse
import cn.edu.sicnu.scnujw.core.net.networkHint
import cn.edu.sicnu.scnujw.ui.common.DataIssue
import cn.edu.sicnu.scnujw.ui.common.ScnuBadgeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuInlineNotice
import cn.edu.sicnu.scnujw.ui.common.ScnuMetaLine
import cn.edu.sicnu.scnujw.ui.common.ScnuNoticeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshBar
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshableSourceBadge
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.common.ScnuStatusBadge
import cn.edu.sicnu.scnujw.ui.common.dataIssueOf
import cn.edu.sicnu.scnujw.ui.common.dataIssueShort
import cn.edu.sicnu.scnujw.ui.common.issueCopy
import cn.edu.sicnu.scnujw.ui.theme.NumSmall
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class UsualScoreViewModel : ViewModel() {
    /** 已上屏的课程（全部已加载学期）：学期切换只做本地过滤，绝不联网 */
    private var allCourses by mutableStateOf<List<UsualScoreCourse>>(emptyList())

    /** 入学年级：只读缓存（学生信息卡 → 平时成绩页头），用于裁剪学期下拉；null=未知不过滤 */
    private var grade by mutableStateOf<Int?>(null)

    var terms by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** 当前筛选中：-1 = 全部学期（默认） */
    var currentTerm by mutableStateOf("-1")
        private set

    var loaded by mutableStateOf(false)     // 已完成一次上屏（缓存或网络）
        private set
    var source by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** 最近一次联网刷新的结果：供角标「已更新」判定（null=未联网） */
    var lastRefreshOk by mutableStateOf<Boolean?>(null)
        private set

    /** 已加载学期 / 目标学期 / 本次失败学期：界面用它们显示补齐进度 */
    var loadedTerms by mutableStateOf<Set<String>>(emptySet())
        private set
    var targetTerms by mutableStateOf<Set<String>>(emptySet())
        private set
    var failedTerms by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 部分完成时的非阻塞提示（未补齐/有失败） */
    var partialNotice by mutableStateOf<String?>(null)
        private set

    /** 无任何数据时的致命错误分类（登录失效/无网/校园网/服务端） */
    var issue by mutableStateOf<DataIssue?>(null)
        private set

    private var loadJob: Job? = null
    private var loadGen = 0

    /** 学期切换：纯本地过滤，不发起任何网络请求 */
    fun selectTerm(term: String) {
        currentTerm = term
    }

    /** 该学期是否已经加载（-1 = 全部学期视图，始终可用） */
    fun isTermLoaded(term: String): Boolean = term == "-1" || term in loadedTerms

    /** 当前本学期视图：有平时成绩的在前；同组按学期码倒序、课程名 */
    fun visibleCourses(): List<UsualScoreCourse> =
        (if (currentTerm == "-1") allCourses else allCourses.filter { it.term == currentTerm })
            .sortedWith(
                compareBy<UsualScoreCourse> { if (it.empty) 1 else 0 }
                    .thenByDescending { it.term }
                    .thenBy { it.name },
            )

    /**
     * 缓存态优先 + 分学期增量同步：
     *  - 有缓存 = 缓存态：进入页面只读磁盘立刻上屏，不自动联网、不显示刷新条；
     *  - 只有「完全无缓存」的初次加载或角标手动刷新（force=true）才 sync；
     *  - 联网时每个学期独立成败（缺哪个补哪个），先默认学期再逐个历史学期上屏；
     *  - 离页 [cancel] 只丢当前学期，已完成学期保留在分学期文件；再次进入是缓存态，补齐需手动刷新；
     *  - force=true 重拉全部目标学期（已有缓存继续留在屏幕上）。
     */
    fun load(ctx: Context, force: Boolean = false) {
        if (loadJob?.isActive == true) return
        val gen = ++loadGen
        error = null
        partialNotice = null
        issue = null
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                grade = ScoreCache.enrollmentYear(ctx)

                // ★ 不可回退的规则：有缓存 = 缓存态；进入页面不自动联网，只有「完全无缓存」的
                //   初次加载或角标手动刷新（force=true）才允许 sync。别把这里改回无条件 sync：
                //   设备没有校园网时，先出现的会是整页「正在加载平时成绩」，要等所有目标学期
                //   逐个超时后缓存才上屏。缓存态下 refreshing 保持 false，因此不出刷新条，
                //   下拉里也不会出现「还有 N 个学期加载中…」。
                val cached = UsualRepository.readCache(ctx)
                if (cached != null) {
                    applyProgress(cached)
                    loaded = true
                    // 本次没有联网：角标不算「已更新」，也不该残留错误/部分完成提示
                    lastRefreshOk = null
                    issue = null
                    partialNotice = null
                    // 兜底：applyProgress 在 loadedTerms 为空时会给空串，角标会整块消失；
                    // 用缓存时间兜底，保证手动刷新入口始终可见可点
                    if (source.isBlank()) source = ScoreCache.dailyLabel(cached.latestFetchedAt)
                    if (!force) return@launch
                }

                // 只有「无缓存初次加载」或 force=true 才走到这里；force 时缓存已在上面上屏
                refreshing = true
                lastRefreshOk = null
                // 换号保护：启动时记下归属学号，落盘前再比一次；变了就丢弃本次结果
                val owner = ScoreCache.profileCode(ctx)
                val result = UsualRepository.sync(
                    ctx = ctx,
                    force = force,
                    beforeWrite = { owner == null || owner == ScoreCache.profileCode(ctx) },
                ) { progress ->
                    if (gen == loadGen) applyProgress(progress)
                }
                if (gen != loadGen) return@launch
                if (result == null) {
                    // 仍返回 null 的只剩「换号保护触发」与「无缓存且页面结构异常但没抛异常」
                    // （UsualRepository 的 fallback == null && lastError == null 分支）；外部取消是抛
                    // CancellationException，不会走到这里。网络与登录失败都带着 error 返回空页
                    // （否则 AuthExpiredException 会被降级成 SERVER，「去验证」不可达）。这里只兜底，
                    // 避免页面停在加载态。
                    if (allCourses.isEmpty()) {
                        issue = DataIssue.SERVER
                        error = "平时成绩暂时读不到。请检查设备网络与校园网连接后重试。"
                        loaded = true
                    }
                    lastRefreshOk = false
                    return@launch
                }
                applyProgress(result)
                lastRefreshOk = result.complete && result.failedTerms.isEmpty() && result.fromNetwork
                if (allCourses.isEmpty() && result.error != null) {
                    issue = dataIssueOf(result.error)
                    error = "平时成绩暂时读不到。" + networkHint(result.error)
                    loaded = true
                }
                val reason = result.error?.let { dataIssueShort(dataIssueOf(it)) }
                // 无数据且已落整页错误面板时不再叠加 partialNotice，否则错误面板上方会多一条
                // 「还有 N 个学期未加载」的重复提示；有数据时仍提示部分学期失败/缺失。
                partialNotice = if (issue != null) null else when {
                    result.pendingTerms > 0 ->
                        "还有 ${result.pendingTerms} 个学期未加载" +
                            (reason?.let { "（$it）" } ?: "")
                    result.failedTerms.isNotEmpty() ->
                        "有 ${result.failedTerms.size} 个学期加载失败" +
                            (reason?.let { "（$it）" } ?: "")
                    else -> null
                }
            } catch (e: Exception) {
                // 协程取消必须原样抛出，不能当成普通失败吞掉
                if (e is CancellationException) throw e
                Log.w("UsualScore", "同步失败: ${e.javaClass.simpleName}: ${e.message}")
                val kind = dataIssueOf(e)
                if (allCourses.isEmpty()) {
                    issue = kind
                    error = "平时成绩暂时读不到。" + networkHint(e)
                    loaded = true
                } else {
                    partialNotice = "同步中断：${dataIssueShort(kind)}；已加载的学期已保留"
                }
                lastRefreshOk = false
            } finally {
                if (gen == loadGen) {
                    refreshing = false
                    loadJob = null
                }
            }
        }
    }

    /** 离页/切走「平时成绩」tab：取消当前学期的请求，已完成学期保留 */
    fun cancel() {
        loadGen++
        loadJob?.cancel()
        loadJob = null
        refreshing = false
    }

    private fun applyProgress(p: UsualProgress) {
        allCourses = p.page.courses
        // 下拉只列已加载学期：未加载的学期不显示「暂无平时成绩」，避免误导
        val loadedMap = p.page.termMap.filterKeys { it in p.loadedTerms }
        terms = ScoreParser.filterTerms(loadedMap, grade, p.loadedTerms)
            .toSortedMap(compareByDescending { it })
        loadedTerms = p.loadedTerms
        targetTerms = p.targetTerms
        failedTerms = p.failedTerms
        // 刷新后选中学期可能已不存在：回落到全部学期
        if (currentTerm != "-1" && currentTerm !in terms) currentTerm = "-1"
        loaded = true
        source = when {
            p.loadedTerms.isEmpty() -> ""
            p.pendingTerms == 0 && p.failedTerms.isEmpty() && p.fromNetwork -> "实时"
            else -> ScoreCache.dailyLabel(p.latestFetchedAt)
        }
        if (p.pendingTerms == 0 && p.failedTerms.isEmpty()) error = null
    }
}

@Composable
fun UsualScoreScreen(
    vm: UsualScoreViewModel = viewModel(),
    nav: NavController? = null,
) {
    val ctx = LocalContext.current
    // 每次进入先读磁盘缓存：有缓存就是缓存态（不联网、不出刷新条）；只有完全无缓存才联网首载，
    // 手动刷新走角标（force=true）。
    LaunchedEffect(Unit) { vm.load(ctx) }
    // 切走「平时成绩」tab / 离开页面：取消当前学期请求，已完成的学期保留
    DisposableEffect(Unit) { onDispose { vm.cancel() } }
    var menuOpen by remember { mutableStateOf(false) }
    val courses = vm.visibleCourses()
    // 选"全部学期"时各块学期不同，卡片副标题里补学期名
    val multiTerm = courses.map { it.term }.distinct().size > 1
    val listState = rememberLazyListState()
    // 学期切换是本地过滤：切完回到列表顶部，避免停在上一学期的滚动位置
    LaunchedEffect(vm.currentTerm) { if (courses.isNotEmpty()) listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        // ── 学期选择（本地过滤）+ 数据源 + 手动刷新（强制全量）──
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 学期按钮占满左侧剩余空间，角标始终贴右：不能再用 weight(4f, fill=false)+Spacer，
            // 否则「全部学期」较短时 Spacer 只分到 1/5，角标会落在中间。
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { menuOpen = true },
                    contentPadding = PaddingValues(horizontal = ScnuSpacing.s, vertical = ScnuSpacing.xs),
                ) {
                    Text(
                        if (vm.currentTerm == "-1") "全部学期"
                        else vm.terms[vm.currentTerm] ?: "全部学期",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "切换学期",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("全部学期") },
                        onClick = { menuOpen = false; vm.selectTerm("-1") })
                    vm.terms.forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(label) },
                            onClick = { menuOpen = false; vm.selectTerm(code) })
                    }
                    val pending = (vm.targetTerms - vm.loadedTerms).size
                    // 只在真正联网补齐时提示：缓存态（refreshing=false）不得出现「加载中」的假象
                    if (vm.refreshing && pending > 0) {
                        DropdownMenuItem(
                            text = { Text("还有 $pending 个学期加载中…") },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
            ScnuRefreshableSourceBadge(
                source = vm.source,
                refreshing = vm.refreshing,
                success = vm.lastRefreshOk == true,
                onRefresh = { vm.load(ctx, force = true) },
            )
        }
        HorizontalDivider(
            thickness = ScnuTokens.Hairline,
            color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        )

        // 有数据时的逐学期补齐：只加一条提示，不清空列表
        if (vm.refreshing && vm.loaded) {
            val loaded = vm.loadedTerms.size
            val total = vm.targetTerms.size
            ScnuRefreshBar(
                if (total > 0 && loaded < total) {
                    "正在补齐历史学期 $loaded/$total"
                } else {
                    "正在刷新平时成绩"
                }
            )
        }
        if (!vm.refreshing) {
            ScnuInlineNotice(vm.partialNotice, tone = ScnuNoticeTone.Warning)
        }

        when {
            vm.issue != null -> {
                val issue = vm.issue ?: DataIssue.SERVER
                val copy = issueCopy(issue, subject = "平时成绩")
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    ScnuStatePanel(
                        title = copy.title,
                        body = copy.body,
                        isError = copy.isError,
                        actionLabel = copy.action,
                        onAction = {
                            if (issue == DataIssue.AUTH_EXPIRED && nav != null) {
                                nav.navigate("auth?system=zf&mode=reauth&ret=profile")
                            } else {
                                vm.load(ctx, force = true)
                            }
                        },
                    )
                }
            }
            !vm.loaded -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                ScnuStatePanel(
                    title = "正在加载平时成绩",
                    body = "先显示当前学期，再逐个补齐历史学期。",
                    loading = true,
                )
            }
            courses.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    vm.currentTerm != "-1" && !vm.isTermLoaded(vm.currentTerm) -> ScnuStatePanel(
                        title = "该学期还在加载",
                        body = "正在从教务补齐这个学期的平时成绩。",
                        loading = true,
                    )
                    vm.currentTerm != "-1" -> ScnuStatePanel(
                        title = "该学期暂无平时成绩",
                        body = "任课教师尚未录入该学期的平时成绩构成项。",
                        actionLabel = "查看全部学期",
                        onAction = { vm.selectTerm("-1") },
                    )
                    else -> ScnuStatePanel(
                        title = "本学期暂无平时成绩",
                        actionLabel = "刷新",
                        onAction = { vm.load(ctx, force = true) },
                    )
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(courses, key = { i, c -> "$i-${c.term}-${c.code}" }) { _, c ->
                    UsualScoreCard(c, multiTerm)
                }
            }
        }
    }
}

@Composable
private fun UsualScoreCard(c: UsualScoreCourse, showTerm: Boolean) {
    val empty = c.empty
    val retake = c.source.contains("重新学习") || c.source.contains("重修")
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScnuSpacing.m, vertical = 3.dp),
        shape = MaterialTheme.shapes.medium,
        // 空卡不再比有内容的卡更亮：同一张卡面，靠文字层级区分
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
    ) {
        Column(Modifier.padding(horizontal = ScnuSpacing.m, vertical = ScnuSpacing.m)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (retake) {
                            Spacer(Modifier.width(ScnuSpacing.s))
                            ScnuStatusBadge("重修", tone = ScnuBadgeTone.Accent)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    ScnuMetaLine(
                        listOf(
                            c.category,
                            c.college,
                            if (showTerm) c.termLabel else "",
                            c.teacher,
                        )
                    )
                }
                if (!empty) {
                    Spacer(Modifier.width(ScnuSpacing.m))
                    Row {
                        Text(
                            "%.1f".format(c.total),
                            // 列表行内分数用 NumSmall：不与 GPA 的 56sp 抢戏
                            style = NumSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.alignByBaseline(),
                        )
                        c.fullTotal?.let {
                            Text(
                                " / %.0f".format(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                }
            }
            if (empty) {
                Spacer(Modifier.height(ScnuSpacing.s))
                Text(
                    "本门课程暂无平时成绩",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "任课教师尚未录入本项成绩，可稍后再查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ScnuTokens.MutedAlpha),
                )
            } else {
                c.items.forEach { item ->
                    HorizontalDivider(
                        modifier = Modifier.padding(top = ScnuSpacing.s),
                        thickness = ScnuTokens.Hairline,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = ScnuSpacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            fmtScore(item.score),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(min = 36.dp),
                        )
                        item.full?.let {
                            Text(
                                " / %.0f".format(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 30.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fmtScore(v: Double?): String = v?.let { "%.1f".format(it) } ?: "-"
