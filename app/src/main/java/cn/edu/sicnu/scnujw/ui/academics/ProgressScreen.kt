package cn.edu.sicnu.scnujw.ui.academics

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.jw.CreditCategory
import cn.edu.sicnu.scnujw.core.jw.JwClient
import cn.edu.sicnu.scnujw.core.jw.LearnCourse
import cn.edu.sicnu.scnujw.core.jw.LearnProgress
import cn.edu.sicnu.scnujw.core.jw.ProgressParser
import cn.edu.sicnu.scnujw.core.net.networkHint
import cn.edu.sicnu.scnujw.ui.common.DataIssue
import cn.edu.sicnu.scnujw.ui.common.ScnuInlineNotice
import cn.edu.sicnu.scnujw.ui.common.ScnuNoticeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshBar
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshableSourceBadge
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.common.dataIssueOf
import cn.edu.sicnu.scnujw.ui.common.dataIssueShort
import cn.edu.sicnu.scnujw.ui.common.fmtCredit
import cn.edu.sicnu.scnujw.ui.common.issueCopy
import cn.edu.sicnu.scnujw.ui.theme.NumLarge
import cn.edu.sicnu.scnujw.ui.theme.NumMedium
import cn.edu.sicnu.scnujw.ui.theme.NumSmall
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ProgressViewModel : ViewModel() {
    var progress by mutableStateOf<LearnProgress?>(null)
        private set
    var source by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)   // 已完成一次加载
        private set
    var refreshing by mutableStateOf(false) // 正在联网刷新（已有数据时用提示条展示）
        private set

    /** 最近一次联网刷新的结果：供角标「已更新」判定（null=本次未联网） */
    var lastRefreshOk by mutableStateOf<Boolean?>(null)
        private set

    /** 无缓存时的致命错误分类（登录失效/无网/校园网/服务端） */
    var issue by mutableStateOf<DataIssue?>(null)
        private set

    /** 有缓存时的降级提示 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 缓存优先：当天缓存直接用；跨天自动刷新；失败保留旧缓存，无缓存时给可操作状态 */
    fun load(ctx: Context, force: Boolean = false) {
        issue = null
        notice = null
        viewModelScope.launch(Dispatchers.IO) {
            var cached: cn.edu.sicnu.scnujw.core.jw.ScoreCache.CachedPage? = null
            cached = cn.edu.sicnu.scnujw.core.jw.ScoreCache.readPage(
                ctx, cn.edu.sicnu.scnujw.core.jw.ScoreCache.Keys.PROGRESS
            )
            if (cached != null) {
                val p = ProgressParser.parse(cached.html)
                if (p.categories.isNotEmpty()) {
                    applyResult(p, cn.edu.sicnu.scnujw.core.jw.ScoreCache.dailyLabel(cached.fetchedAt))
                }
                if (!force && !cn.edu.sicnu.scnujw.core.jw.ScoreCache.needDailyRefresh(cached.fetchedAt)) {
                    return@launch
                }
            }
            refreshing = true
            lastRefreshOk = null
            try {
                val html = JwClient.fetchLearnProgress()
                val online = ProgressParser.parse(html)
                // 结构完整但没有任何模块 = 新生/培养方案未下发，按正常空态处理；
                // 连页面骨架都没有才是结构异常。
                if (online.categories.isEmpty() && !ProgressParser.hasProgressPage(html)) {
                    // 诊断事件：只写页面路径与结论，HTML 由 JwClient 的 http 记录负责
                    cn.edu.sicnu.scnujw.core.diag.Diag.recordEvent(
                        "parse",
                        "学习进度页结构异常：找不到学分模块（${JwClient.LEARN_PROGRESS}）",
                    )
                    throw IllegalStateException("学习进度页结构异常（找不到学分模块）")
                }
                cn.edu.sicnu.scnujw.core.jw.ScoreCache.writePage(
                    ctx, cn.edu.sicnu.scnujw.core.jw.ScoreCache.Keys.PROGRESS, html
                )
                applyResult(online, "实时")
                lastRefreshOk = true
            } catch (e: Exception) {
                android.util.Log.w("Progress", "在线加载失败: ${e.javaClass.simpleName}: ${e.message}")
                val kind = dataIssueOf(e)
                // 有缓存降级显示缓存并给非阻塞提示；无缓存才落错误面板
                if (cached == null) {
                    issue = kind
                    error = "学习进度暂时读不到。" + networkHint(e)
                    loaded = true
                } else {
                    notice = "刷新失败：${dataIssueShort(kind)}，当前显示上次缓存"
                }
                lastRefreshOk = false
            } finally {
                refreshing = false
            }
        }
    }

    private fun applyResult(p: LearnProgress, src: String) {
        progress = p
        source = src
        loaded = true
        error = null
    }
}

@Composable
fun ProgressScreen(
    vm: ProgressViewModel = viewModel(),
    nav: NavController? = null,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (!vm.loaded && vm.error == null) vm.load(ctx) }
    val p = vm.progress
    val cs = MaterialTheme.colorScheme

    // 已展开的分类名集合；rememberSaveable 保证旋转/重建后展开状态还在
    var expanded by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet<String>()) }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(
            title = "学习进度",
            onBack = nav?.let { n -> { n.navigateUp() } },
            actions = {
                ScnuRefreshableSourceBadge(
                    source = vm.source,
                    refreshing = vm.refreshing,
                    success = vm.lastRefreshOk == true,
                    onRefresh = { vm.load(ctx, force = true) },
                )
            },
        )

        if (vm.refreshing && vm.loaded) {
            ScnuRefreshBar("正在刷新学习进度")
        }
        ScnuInlineNotice(vm.notice, tone = ScnuNoticeTone.Warning)

        when {
            vm.issue != null -> {
                val issue = vm.issue ?: DataIssue.SERVER
                val copy = issueCopy(issue, subject = "学习进度")
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ScnuStatePanel(
                        title = copy.title,
                        body = copy.body,
                        isError = copy.isError,
                        actionLabel = copy.action,
                        onAction = {
                            if (issue == DataIssue.AUTH_EXPIRED && nav != null) {
                                nav.navigate("auth?system=zf&mode=reauth&ret=progress")
                            } else {
                                vm.load(ctx, force = true)
                            }
                        },
                    )
                }
            }
            !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScnuStatePanel(
                    title = "正在读取学习进度",
                    loading = true,
                )
            }
            p == null || p.categories.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScnuStatePanel(
                    title = "本学期暂无学习进度",
                    actionLabel = "重新加载",
                    onAction = { vm.load(ctx, force = true) },
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                item { TotalProgressCard(p) }
                item {
                    Text(
                        "分类学分明细",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        modifier = Modifier.padding(
                            horizontal = ScnuSpacing.l,
                            vertical = ScnuSpacing.s,
                        ),
                    )
                }
                itemsIndexed(p.categories, key = { i, c -> "$i-${c.name}" }) { _, c ->
                    CategoryCard(
                        c = c,
                        expanded = c.name in expanded,
                        onToggle = {
                            expanded = if (c.name in expanded) expanded - c.name
                            else expanded + c.name
                        },
                    )
                }
            }
        }
    }
}

/* ─────────────────────────── 总进度大卡 ─────────────────────────── */

/** 总进度大卡：完成率走 Barlow 大数字（银杏金），进度条 10dp 圆角 */
@Composable
private fun TotalProgressCard(p: LearnProgress) {
    val cs = MaterialTheme.colorScheme
    val ratio = p.totalRatio.coerceIn(0f, 1f)
    val percent = (ratio * 100).roundToInt()
    val remain = (p.totalRequired - p.totalEarned).coerceAtLeast(0.0)

    Surface(
        color = cs.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScnuSpacing.m, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
    ) {
        Column(Modifier.padding(ScnuSpacing.l)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).padding(bottom = 5.dp)) {
                    Text("总学分进度", style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已修 ${fmtCredit(p.totalEarned)} / 应修 ${fmtCredit(p.totalRequired)} 学分",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                    )
                }
                Text("$percent", style = NumLarge, color = cs.primary)
                Text(
                    "%",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.primary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(ScnuSpacing.m))
            ThinProgress(
                progress = ratio,
                height = 10.dp,
                color = cs.primary,
                trackColor = cs.surfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (remain > 0) "还差 ${fmtCredit(remain)} 学分达到最低要求"
                else "已满足最低学分要求",
                style = MaterialTheme.typography.labelLarge,
                // 金色只表示「已达标」；未达标用中性色，避免同页两种相反语义共用金色
                color = if (remain > 0) cs.onSurfaceVariant else cs.primary,
            )
        }
    }
}

/* ─────────────────────────── 分类卡 ─────────────────────────── */

/**
 * 单个学分模块：默认收起，只显示 已修/应修 + 完成率 + 备注；
 * 点击卡片头部展开该类课程清单（已获成绩在前、未获成绩在后；
 * 行内容为课名/学分/成绩/学期）。
 */
@Composable
private fun CategoryCard(c: CreditCategory, expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScnuSpacing.m, vertical = 3.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
    ) {
        Column(Modifier.padding(ScnuSpacing.m).animateContentSize()) {
            // ── 可点击头部：展开 / 收起 ──
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已修 ${fmtCredit(c.earned)} / 应修 ${fmtCredit(c.required)} 学分",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                        if (c.courses.isNotEmpty()) {
                            MetaBar()
                            Text("${c.courses.size} 门",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurfaceVariant)
                        }
                    }
                    // 收起态补一行「已获 N/共 M 门」；展开后由两组组头分别给出数量
                    if (!expanded && c.courses.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("已获 ", style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant)
                            Text("${c.courses.count { it.score != "×" }}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            Text(" / 共 ", style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant)
                            Text("${c.courses.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            Text(" 门", style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant)
                        }
                    }
                }
                val percent = c.ratio?.let { "${(it * 100).roundToInt()}%" }
                if (percent != null) {
                    Text(
                        percent,
                        style = NumMedium,
                        color = if (c.finished) cs.primary else cs.onSurfaceVariant,
                    )
                } else {
                    Text("—", style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起课程清单" else "展开课程清单",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            c.ratio?.let { r ->
                Spacer(Modifier.height(10.dp))
                ThinProgress(
                    progress = r,
                    height = 6.dp,
                    // 金色只留给「已达标」，其余用灰绿，避免整页金色泛滥
                    color = if (c.finished) cs.primary else cs.onSurfaceVariant,
                    trackColor = cs.surface,
                )
            }
            if (c.note.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    c.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(ScnuSpacing.s))
                HorizontalDivider(
                    color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    thickness = ScnuTokens.Hairline,
                )
                Spacer(Modifier.height(6.dp))
                CourseList(c)
            }
        }
    }
}

/**
 * 展开后的课程明细：按成绩分两组——已获成绩（成绩非 ×）在前，
 * 未获成绩（× 行，透明度 0.5）在后。行数多时限制高度并在卡片内部滚动。
 */
@Composable
private fun CourseList(c: CreditCategory) {
    if (c.courses.isEmpty()) {
        Text("暂无课程明细", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // Parser 约定：成绩 "×" 表示未修/未出成绩，其余（数字 / 优）视为已获成绩
    val graded = c.courses.filter { it.score != "×" }
    val ungraded = c.courses.filter { it.score == "×" }
    // heightIn + verticalScroll：课程多时固定最大高度，卡片内部滚动，不撑爆外层列表
    Box(
        Modifier.fillMaxWidth()
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.fillMaxWidth()) {
            CourseGroup(label = "已获成绩", courses = graded)
            CourseGroup(label = "未获成绩", courses = ungraded, muted = true)
        }
    }
}

/**
 * 一组课程 + 组头；组内无课程时整组（含组头）不渲染。
 * 组头门数走 Barlow NumSmall；[muted] 用令牌色降级，不用整块 alpha（alpha 会连描边一起虚化）。
 */
@Composable
private fun CourseGroup(
    label: String,
    courses: List<LearnCourse>,
    muted: Boolean = false,
) {
    if (courses.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    if (muted) Spacer(Modifier.height(ScnuSpacing.m))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (muted) cs.onSurfaceVariant else cs.onSurface,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        Spacer(Modifier.width(ScnuSpacing.s))
        Text(
            "${courses.size}",
            style = NumSmall,
            color = if (muted) cs.onSurfaceVariant else cs.onSurface,
        )
        Text(
            "门",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 5.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "小计 ${fmtCredit(courses.sumOf { it.credit ?: 0.0 })} 学分",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 5.dp),
        )
    }
    Spacer(Modifier.height(2.dp))

    Column(Modifier.fillMaxWidth()) {
        courses.forEachIndexed { i, course ->
            if (i > 0) {
                HorizontalDivider(
                    color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    thickness = ScnuTokens.Hairline,
                )
            }
            CourseRow(course, muted)
        }
    }
}

/** 单门课程行：课名 + 学分 + 成绩；有学期/属性时补一行小字（细竖线分隔） */
@Composable
private fun CourseRow(course: LearnCourse, muted: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val textColor = if (muted) cs.onSurfaceVariant else cs.onSurface
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                course.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(ScnuSpacing.s))
            Text(
                course.credit?.let { "${fmtCredit(it)} 学分" } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                course.score ?: "—",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (muted) cs.onSurfaceVariant else scoreColor(course.score),
            )
        }
        // 学期为页面原样标记（如"第二学年一"）；属性取自课程名"（属性：X）"
        val meta = listOfNotNull(
            course.term?.let { "学期 $it" },
            course.category?.let { "属性 $it" },
        )
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                meta.forEachIndexed { i, part ->
                    if (i > 0) MetaBar()
                    Text(
                        part,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 1dp 细竖线：替代中点连接符，分隔两段同级信息 */
@Composable
private fun MetaBar() {
    Box(
        Modifier
            .padding(horizontal = 7.dp)
            .width(1.dp)
            .height(10.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/** 成绩颜色：未修/未出成绩灰；不及格取主题 error；其余正常 */
@Composable
private fun scoreColor(score: String?): Color {
    val scheme = MaterialTheme.colorScheme
    if (score == null || score == "×") return scheme.onSurfaceVariant
    val n = score.toDoubleOrNull()
    return if (n != null && n < 60) scheme.error else scheme.onSurface
}

/** 圆角进度条：金色由调用方决定；末端不画停靠点，保持一条干净的量尺 */
@Composable
private fun ThinProgress(
    progress: Float,
    height: Dp,
    color: Color,
    trackColor: Color,
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2)),
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
