package cn.edu.sicnu.scnujw.ui.scores

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.jw.JwClient
import cn.edu.sicnu.scnujw.core.jw.RetakeHiddenStore
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import cn.edu.sicnu.scnujw.core.jw.ScoreParser
import cn.edu.sicnu.scnujw.core.jw.ScoreRow
import cn.edu.sicnu.scnujw.core.net.networkHint
import cn.edu.sicnu.scnujw.ui.common.CourseDetailSheet
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.common.ScnuBadgeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuFilterChip
import cn.edu.sicnu.scnujw.ui.common.DataIssue
import cn.edu.sicnu.scnujw.ui.common.ScnuInlineNotice
import cn.edu.sicnu.scnujw.ui.common.ScnuMetaLine
import cn.edu.sicnu.scnujw.ui.common.ScnuNoticeTone
import cn.edu.sicnu.scnujw.ui.common.dataIssueOf
import cn.edu.sicnu.scnujw.ui.common.dataIssueShort
import cn.edu.sicnu.scnujw.ui.common.issueCopy
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshBar
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshableSourceBadge
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.common.ScnuStatusBadge
import cn.edu.sicnu.scnujw.ui.common.fmtCredit
import cn.edu.sicnu.scnujw.ui.theme.NumMedium
import cn.edu.sicnu.scnujw.ui.theme.NumSmall
import cn.edu.sicnu.scnujw.ui.theme.ScnuPalette
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import cn.edu.sicnu.scnujw.ui.theme.scnuSegmentedButtonColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 学期下拉里「全部学期」的哨兵值（与教务下拉的 -1 一致） */
private const val ALL_TERMS = "-1"

class ScoresViewModel : ViewModel() {
    var rows by mutableStateOf<List<ScoreRow>>(emptyList())
        private set
    var source by mutableStateOf("")
    var loaded by mutableStateOf(false)     // 已完成一次上屏（缓存或网络）
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** 最近一次联网刷新的结果：null=本次不是联网刷新，true/false=成功/失败（供角标「已更新」判定） */
    var lastRefreshOk by mutableStateOf<Boolean?>(null)
        private set

    /** 无缓存时的致命错误分类（登录失效/无网/校园网/服务端）；有缓存时只给 notice */
    var issue by mutableStateOf<DataIssue?>(null)
        private set
    /** 有缓存时的降级提示：刷新失败原因 + 当前显示的是上次缓存 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 入学年份（学号前四位与年级取较早者）：只读缓存，用于裁剪学期下拉；null=未知不过滤 */
    var grade by mutableStateOf<Int?>(null)
        private set

    /** 用户在「只看需重修」里手动删掉的课程（本机隐藏，不动教务数据） */
    var hiddenRetake by mutableStateOf<Set<String>>(emptySet())
        private set

    fun hideRetake(ctx: Context, key: String) {
        RetakeHiddenStore.hide(ctx, key)
        hiddenRetake = RetakeHiddenStore.read(ctx)
    }

    fun restoreHiddenRetake(ctx: Context) {
        RetakeHiddenStore.restoreAll(ctx)
        hiddenRetake = emptySet()
    }

    /**
     * 缓存优先：有缓存立即上屏；自动联网只在「无缓存」或「缓存不是今天」时发生，
     * 同一天内进入页面不再自动打网络（force=true 为手动刷新，始终拉取）。
     */
    fun load(ctx: Context, force: Boolean = false) {
        error = null
        issue = null
        notice = null
        viewModelScope.launch(Dispatchers.IO) {
            // 入学年份只读本地缓存，不新增网络请求
            grade = ScoreCache.enrollmentYear(ctx)
            hiddenRetake = RetakeHiddenStore.read(ctx)
            val cached = ScoreCache.readElectScores(ctx)
            if (cached != null) {
                apply(cached.data, ScoreCache.dailyLabel(cached.fetchedAt))
                if (!force && !ScoreCache.needDailyRefresh(cached.fetchedAt)) return@launch
            }
            refreshing = true
            lastRefreshOk = null
            try {
                val html = JwClient.queryAllElectScores().html()
                val parsed = ScoreParser.parse(html).first
                val total = ScoreParser.totalCount(html)
                when {
                    // 分页/回发不完整（如只拿到第一页 10 条）：不写缓存，明确报错，绝不顶掉全量
                    parsed.isNotEmpty() && total != null && parsed.size < total -> {
                        error = "成绩分页不完整（${parsed.size}/$total 条），本次结果未保存。请稍后重试。"
                        lastRefreshOk = false
                        if (cached == null) issue = DataIssue.SERVER
                        else notice = "刷新失败：成绩分页不完整，当前显示上次缓存"
                    }
                    parsed.isNotEmpty() -> {
                        ScoreCache.writeElectScores(ctx, parsed)
                        apply(parsed, "实时")
                        lastRefreshOk = true
                    }
                    // 表在但无行，或新生页只有学期下拉没有成绩表 = 正常空态（大一上常见）
                    ScoreParser.hasScorePage(html) -> {
                        if (cached == null) {
                            apply(emptyList(), "实时")
                            lastRefreshOk = true
                        } else {
                            // 教务这次返回空表但屏幕仍保留旧缓存：不对外宣称「已更新」
                            lastRefreshOk = false
                            notice = "教务这次没有返回成绩记录，当前显示上次缓存"
                        }
                    }
                    // 页面骨架都不存在 = 结构异常/半渲染，不能伪装成「没有查到成绩记录」
                    else -> {
                        // 诊断事件：只写页面路径与结论，HTML 由 JwClient 的 http 记录负责
                        cn.edu.sicnu.scnujw.core.diag.Diag.recordEvent(
                            "parse",
                            "选课成绩页结构异常：找不到成绩表（${JwClient.ELECT_SCORE}）",
                        )
                        error = "成绩页结构异常（找不到成绩表），本次结果未保存。请稍后重试。"
                        lastRefreshOk = false
                        if (cached == null) issue = DataIssue.SERVER
                        else notice = "刷新失败：教务页面结构异常，当前显示上次缓存"
                    }
                }
            } catch (e: Exception) {
                // 协程取消必须原样抛出，不能当成普通失败吞掉
                if (e is CancellationException) throw e
                // 有缓存则降级显示缓存；无缓存时区分登录失效/无网/校园网/服务端
                val kind = dataIssueOf(e)
                if (cached == null) {
                    issue = kind
                    error = "成绩暂时读不到。" + networkHint(e)
                } else {
                    notice = "刷新失败：${dataIssueShort(kind)}，当前显示上次缓存"
                }
                lastRefreshOk = false
            } finally {
                refreshing = false
            }
        }
    }

    private fun apply(r: List<ScoreRow>, src: String) {
        rows = r; source = src; loaded = true
    }
}

/** 成绩页子页：同 Screen 内切换；initialTab="usual" 从"我的"直达平时成绩 */
private enum class ScoreTab(val label: String) { ELECT("选课成绩"), USUAL("平时成绩") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoresScreen(
    initialTab: String = "",
    vm: ScoresViewModel = viewModel(),
    nav: NavController? = null,
) {
    var tab by rememberSaveable {
        mutableStateOf(if (initialTab == "usual") ScoreTab.USUAL.name else ScoreTab.ELECT.name)
    }
    // 筛选状态提到 Screen 层：切到「平时成绩」再切回来时不丢失（子页离开组合会丢弃内部 rememberSaveable）
    var selectedTerm by rememberSaveable { mutableStateOf(ALL_TERMS) }
    var onlyRetake by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(title = "成绩")
        // ── 子页切换；手动刷新已并入数据源角标（GPA 卡右下角）──
        Row(
            Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp)) {
                ScoreTab.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == t.name,
                        onClick = { tab = t.name },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = ScoreTab.entries.size),
                        colors = scnuSegmentedButtonColors(),
                    ) { Text(t.label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (tab == ScoreTab.USUAL.name) UsualScoreScreen(nav = nav) else ElectScorePage(
            vm = vm,
            selectedTerm = selectedTerm,
            onlyRetake = onlyRetake,
            onSelectTerm = { selectedTerm = it },
            onToggleRetake = { onlyRetake = it },
            nav = nav,
        )
    }
}

@Composable
private fun ElectScorePage(
    vm: ScoresViewModel,
    selectedTerm: String,
    onlyRetake: Boolean,
    onSelectTerm: (String) -> Unit,
    onToggleRetake: (Boolean) -> Unit,
    nav: NavController? = null,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (!vm.loaded) vm.load(ctx) }

    // ── 筛选：学期（默认全部）+ 只看需重修（状态在 ScoresScreen 层，切 tab 不丢）──
    var menuOpen by remember { mutableStateOf(false) }

    // 学期选项来自已上屏的记录；这些学期都有真实成绩，作为 recordTerms 传入后
    // 即使入学年份更大也绝不隐藏有记录的学期（宁可多显示，不可藏真实数据）
    val termOptions = remember(vm.rows, vm.grade) {
        val labels = vm.rows.filter { it.term.isNotBlank() }
            .associate { it.term to it.termLabel }
        ScoreParser.filterTerms(labels, vm.grade, labels.keys).toList()
            .sortedByDescending { it.first }
    }
    // 刷新后原选中学期可能已不存在：回落到全部学期
    LaunchedEffect(termOptions) {
        if (selectedTerm != ALL_TERMS && termOptions.none { it.first == selectedTerm }) {
            onSelectTerm(ALL_TERMS)
        }
    }
    val activeTerm = if (selectedTerm != ALL_TERMS && termOptions.any { it.first == selectedTerm }) {
        selectedTerm
    } else ALL_TERMS

    // 需重修列表：用户手动删除（本机隐藏）的不再出现；key 与隐藏集合一致
    val retakeRows = remember(vm.rows, vm.hiddenRetake) {
        ScoreParser.needRetakeRows(vm.rows)
            .filterNot { it.code.ifBlank { it.name } in vm.hiddenRetake }
    }
    val filtered = remember(vm.rows, activeTerm, onlyRetake, retakeRows) {
        when {
            onlyRetake -> retakeRows          // 需重修忽略学期分组，平铺
            activeTerm == ALL_TERMS -> vm.rows
            else -> vm.rows.filter { it.term == activeTerm }
        }
    }
    // 学期分组只在普通视图使用（需重修视图按课程代码去重后平铺）
    val grouped = remember(filtered, onlyRetake) {
        if (onlyRetake) emptyMap()
        else filtered.groupBy { it.term }.toSortedMap(compareByDescending { it })
    }
    // GPA 汇总卡跟随学期筛选：全部=summary(全部)，指定学期=termSummary；无行不显示
    val card = remember(filtered, activeTerm, onlyRetake) {
        if (onlyRetake || filtered.isEmpty()) {
            null
        } else {
            val s = ScoreParser.summary(filtered)
            GpaCard(
                gpa = if (activeTerm == ALL_TERMS) s.gpa else ScoreParser.termSummary(filtered),
                credits = s.credits,
                count = s.count,
                retakeCount = s.retakeCount,
                termScoped = activeTerm != ALL_TERMS,
            )
        }
    }
    // 点击成绩行 → 执行计划详情
    var detailRow by remember { mutableStateOf<ScoreRow?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(activeTerm, onlyRetake) {
        if (filtered.isNotEmpty()) listState.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        // 有数据时的刷新态：不遮挡内容
        if (vm.refreshing && vm.loaded) {
            ScnuRefreshBar("正在刷新成绩")
        }
        // 刷新失败但仍有缓存：降级提示，不把页面变成错误页
        ScnuInlineNotice(vm.notice, tone = ScnuNoticeTone.Warning)
        // ── 筛选行：与子页切换/刷新同一区域紧邻，不挤占标题 ──
        if (vm.loaded && vm.rows.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box {
                    TextButton(
                        onClick = { menuOpen = true },
                        contentPadding = PaddingValues(
                            horizontal = ScnuSpacing.s, vertical = ScnuSpacing.xs),
                    ) {
                        Text(
                            if (activeTerm == ALL_TERMS) "全部学期"
                            else (termOptions.firstOrNull { it.first == activeTerm }?.second
                                ?: "全部学期"),
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
                            onClick = { menuOpen = false; onSelectTerm(ALL_TERMS) })
                        termOptions.forEach { (code, label) ->
                            DropdownMenuItem(text = { Text(label) },
                                onClick = { menuOpen = false; onSelectTerm(code) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.hiddenRetake.isNotEmpty()) {
                        TextButton(
                            onClick = { vm.restoreHiddenRetake(ctx) },
                            contentPadding = PaddingValues(horizontal = ScnuSpacing.s),
                        ) {
                            Text(
                                "恢复已隐藏",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ScnuFilterChip(
                        selected = onlyRetake,
                        onClick = { onToggleRetake(!onlyRetake) },
                        label = "重修",
                    )
                }
            }
        }
        // ── GPA 汇总卡：全 APP 唯一记忆点 ──
        if (card != null) {
            GpaSummaryCard(card, vm.source, vm.refreshing, vm.lastRefreshOk == true) {
                vm.load(ctx, force = true)
            }
        }

        when {
            vm.issue != null -> {
                val issue = vm.issue ?: DataIssue.SERVER
                val copy = issueCopy(issue, subject = "成绩")
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
                    title = "正在加载成绩",
                    body = "首次进入需要从教务系统拉取全部学期记录。",
                    loading = true,
                )
            }
            vm.rows.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                ScnuStatePanel(
                    title = "本学期暂无成绩",
                    actionLabel = "刷新成绩",
                    onAction = { vm.load(ctx, force = true) },
                )
            }
            filtered.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (onlyRetake) {
                    ScnuStatePanel(
                        title = "没有需要重修的课程",
                        body = "所有课程都已通过，或没有未通过的记录。",
                        hint = "关闭「只看需重修」可以查看全部成绩。",
                        actionLabel = "查看全部成绩",
                        onAction = { onToggleRetake(false) },
                    )
                } else {
                    ScnuStatePanel(
                        title = "该学期没有成绩记录",
                        body = "可能是筛选条件与缓存不一致。",
                        hint = "切回「全部学期」查看完整成绩单。",
                        actionLabel = "查看全部学期",
                        onAction = { onSelectTerm(ALL_TERMS) },
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp)) {
                if (onlyRetake) {
                    // 需重修视图：按课程代码去重后平铺，每门课一行；右滑删除（本机隐藏）
                    items(retakeRows, key = { "r_${it.code}_${it.name}_${it.term}" }) { r ->
                        SwipeToHideRetake(
                            onHide = { vm.hideRetake(ctx, r.code.ifBlank { r.name }) },
                        ) {
                            // 平铺后没有学期分组标题，行内补学期名
                            ScoreItem(r, showTerm = true) { detailRow = r }
                        }
                    }
                } else {
                    grouped.entries.forEachIndexed { index, (term, list) ->
                        item(key = "h_$term") {
                            val tg = remember(list) { ScoreParser.termSummary(list) }
                            TermHeader(
                                label = list.first().termLabel,
                                count = list.size,
                                termGpa = tg,
                                dividerAbove = index > 0,
                            )
                        }
                        items(list, key = { it.term + it.code + it.name + it.scoreText }) { r ->
                            ScoreItem(r) { detailRow = r }
                        }
                    }
                }
            }
        }
        }

        detailRow?.let { r ->
            CourseDetailSheet(
                courseName = r.name,
                term = r.term,
                courseCode = r.code,
                onDismiss = { detailRow = null },
            )
        }
    }
}

/** GPA 卡展示数据：gpa=null 表示该范围没有可加权绩点（显示「—」） */
private data class GpaCard(
    val gpa: Double?,
    val credits: Double,
    val count: Int,
    val retakeCount: Int,
    val termScoped: Boolean,
)

/** 深绿渐变 + 右下角金色大圆弧；GPA 是全页唯一的大数字 */
@Composable
private fun GpaSummaryCard(
    card: GpaCard,
    source: String,
    refreshing: Boolean,
    refreshOk: Boolean,
    onRefresh: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(ScnuPalette.PINE_GRADIENT))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val d = size.minDimension * 1.7f
            drawArc(
                color = ScnuPalette.D_ACCENT.copy(alpha = 0.15f),
                startAngle = 185f,
                sweepAngle = 85f,
                useCenter = false,
                topLeft = Offset(size.width - d * 0.68f, size.height - d * 0.55f),
                size = Size(d, d),
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (card.termScoped) "学期绩点" else "加权绩点",
                    style = MaterialTheme.typography.labelMedium,
                    color = ScnuPalette.D_MUTED,
                )
                Text(
                    card.gpa?.let { "%.2f".format(it) } ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = ScnuPalette.D_ACCENT,
                )
                Text(
                    when {
                        card.termScoped -> "本学期按学分加权"
                        card.retakeCount > 0 -> "重修按最高成绩计"
                        else -> "按学分加权"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = ScnuPalette.D_MUTED,
                )
            }
            Column(
                Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    StatTile(value = "%.1f".format(card.credits), label = "已获学分")
                    Box(
                        Modifier
                            .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.xs)
                            .width(1.dp)
                            .height(38.dp)
                            .background(ScnuPalette.D_OUTLINE)
                    )
                    StatTile(value = "${card.count}", label = "共 ${card.count} 门")
                }
                if (source.isNotEmpty()) {
                    // 深色卡面变体：透明容器 + 骨白文字/图形；与「重修按最高成绩计」同一行高。
                    // 48dp 点击区与刷新符号由统一角标组件保证，刷新中只禁用点击、不塌陷。
                    ScnuRefreshableSourceBadge(
                        source = source,
                        refreshing = refreshing,
                        success = refreshOk,
                        onRefresh = onRefresh,
                        containerColor = Color.Transparent,
                        contentColor = ScnuPalette.D_MUTED,
                        borderColor = ScnuPalette.D_INK.copy(alpha = 0.12f),
                        iconTint = ScnuPalette.D_MUTED,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, style = NumMedium, color = ScnuPalette.D_INK)
        Text(label, style = MaterialTheme.typography.labelSmall, color = ScnuPalette.D_MUTED)
    }
}

/**
 * 需重修行：右滑露出删除滑块，松手即从本机隐藏。
 * 只改本地「只看需重修」视图，不改教务数据；隐藏后可用筛选行的「恢复已隐藏」找回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToHideRetake(onHide: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.StartToEnd) {
                onHide()
                true
            } else false
        },
    )
    // LazyColumn 会按 key 恢复被删除行的 dismiss 状态；恢复显示后必须回到未滑动位置
    LaunchedEffect(Unit) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) {
            state.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = ScnuSpacing.l, vertical = 3.dp)
                    .background(cs.errorContainer, MaterialTheme.shapes.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = cs.error)
                Spacer(Modifier.width(ScnuSpacing.s))
                Text(
                    "删除",
                    color = cs.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    ) { content() }
}

@Composable
private fun TermHeader(label: String, count: Int, termGpa: Double?, dividerAbove: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (dividerAbove) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = ScnuSpacing.l),
                thickness = ScnuTokens.Hairline,
                color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$count 门课程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (termGpa != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "学期绩点",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "%.2f".format(termGpa),
                        style = NumSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreItem(r: ScoreRow, showTerm: Boolean = false, onClick: () -> Unit = {}) {
    val failed = r.failed
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScnuSpacing.m, vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        // 挂科行用当前主题的 errorContainer（浅色是暖赭 #F6E3D9），不再借深色令牌 D_ERROR
        color = if (failed) cs.errorContainer else cs.surfaceVariant,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (failed) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(cs.error))
            }
            Row(
                Modifier
                    .weight(1f)
                    .padding(
                        start = if (failed) 11.dp else 12.dp,
                        end = 12.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (r.retake) {
                            Spacer(Modifier.width(ScnuSpacing.s))
                            ScnuStatusBadge("重修", tone = ScnuBadgeTone.Accent)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    ScnuMetaLine(
                        listOf(
                            // 平铺视图（需重修）没有学期分组标题，行内补学期名
                            if (showTerm) r.termLabel else "",
                            categoryShort(r.category),
                            "${fmtCredit(r.credit)} 学分",
                            r.teacher,
                        )
                    )
                }
                Spacer(Modifier.width(ScnuSpacing.m))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        r.scoreText,
                        style = NumSmall,
                        color = if (failed) cs.error else cs.onSurface,
                    )
                    r.gpa?.let {
                        Text(
                            "绩点 %.1f".format(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** 课程分类短名："自主发展课程-必修课"→"自主发展必修"，元信息行不溢出 */
private fun categoryShort(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return t
    val head = t.substringBefore('-').removeSuffix("课程")
    val tail = t.substringAfter('-', "").removeSuffix("课")
    return if (tail.isEmpty()) head else "$head$tail"
}
