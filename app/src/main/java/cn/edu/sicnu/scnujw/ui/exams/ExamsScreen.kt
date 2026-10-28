package cn.edu.sicnu.scnujw.ui.exams

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.jw.ExamParser
import cn.edu.sicnu.scnujw.core.jw.ExamRow
import cn.edu.sicnu.scnujw.core.jw.JwClient
import cn.edu.sicnu.scnujw.core.jw.RoomParts
import cn.edu.sicnu.scnujw.core.jw.ScoreParser
import cn.edu.sicnu.scnujw.core.net.networkHint
import cn.edu.sicnu.scnujw.ui.common.DataIssue
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
import cn.edu.sicnu.scnujw.ui.common.issueCopy
import cn.edu.sicnu.scnujw.ui.theme.BadgeShape
import cn.edu.sicnu.scnujw.ui.theme.NumLarge
import cn.edu.sicnu.scnujw.ui.theme.NumMedium
import cn.edu.sicnu.scnujw.ui.theme.NumSmall
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ExamsViewModel : ViewModel() {
    var exams by mutableStateOf<List<ExamRow>>(emptyList())
        private set
    var terms by mutableStateOf<Map<String, String>>(emptyMap())
    var currentTerm by mutableStateOf<String?>(null)
    var source by mutableStateOf("")
    var loaded by mutableStateOf(false)     // 已完成一次上屏（缓存 / 网络）
        private set
    var refreshing by mutableStateOf(false) // 正在联网刷新（已有数据时用提示条展示）
        private set

    /** 最近一次联网刷新的结果：供角标「已更新」判定（null=本次未联网） */
    var lastRefreshOk by mutableStateOf<Boolean?>(null)
        private set

    /** 无数据时的致命错误分类（登录失效/无网/校园网/服务端） */
    var issue by mutableStateOf<DataIssue?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 已有数据时的非阻塞失败提示（切换学期失败等）：列表保持原样，不整页替换成错误面板 */
    var notice by mutableStateOf<String?>(null)
        private set
    /** 最近一次请求的学期（含失败的新学期），「重新加载」按用户选中的学期重试 */
    var pendingTerm by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null
    /** 请求代号：只有最新一次请求能写状态，避免旧请求后完成覆盖新学期的数据 */
    private var loadGen = 0

    /** 是否成功上屏过数据（含空列表）：据此区分「首屏失败」与「切学期失败」 */
    private var hasData by mutableStateOf(false)

    /** 入学年份：只读本地缓存，用于裁剪学期下拉（与成绩/平时成绩同一口径） */
    private var grade: Int? = null

    /** 默认学期（缓存 meta1 或服务端默认视图的 selectedTerm），角标刷新据此决定写不写缓存 */
    private var defaultTerm: String? = null

    fun load(ctx: Context, term: String? = null, force: Boolean = false) {
        // 切换学期/重新加载：取消上一个请求，结果只认最后一次
        loadJob?.cancel()
        val gen = ++loadGen
        pendingTerm = term
        notice = null
        lastRefreshOk = null
        issue = null
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            grade = cn.edu.sicnu.scnujw.core.jw.ScoreCache.enrollmentYear(ctx)
            // 默认视图读 page_exams.json；筛选视图读 page_exams_term_<学期>.json。
            // 两者都先上屏；!force 时直接返回，筛选视图不会因切学期而每次联网刷新。
            val cached: cn.edu.sicnu.scnujw.core.jw.ScoreCache.CachedPage? =
                if (term == null) {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.readPage(
                        ctx, cn.edu.sicnu.scnujw.core.jw.ScoreCache.Keys.EXAMS
                    )
                } else {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.readExamTerm(ctx, term)
                }
            if (cached != null) {
                if (gen != loadGen) return@launch
                applyHtml(
                    cached.html, cached.meta1, cached.meta2,
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.dailyLabel(cached.fetchedAt),
                    recordDefault = term == null,
                )
                // 有缓存 = 缓存态：进入页面/切到该学期都不自动联网，缓存跨天也不刷；
                // 只有完全无缓存的初次加载或角标手动 force=true 才请求。
                if (!force) return@launch
            }
            if (gen != loadGen) return@launch
            refreshing = true
            try {
                val (html, sel, label) = JwClient.queryExams(term)
                if (term == null) {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.writePage(
                        ctx, cn.edu.sicnu.scnujw.core.jw.ScoreCache.Keys.EXAMS, html, sel, label
                    )
                } else {
                    cn.edu.sicnu.scnujw.core.jw.ScoreCache.writeExamTerm(
                        ctx, term, html, label.ifBlank { terms[term] ?: term }
                    )
                }
                if (gen != loadGen) return@launch // 已被更新的请求取代：丢弃过期结果
                applyHtml(html, sel, label, "实时", recordDefault = term == null)
                lastRefreshOk = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != loadGen) return@launch
                lastRefreshOk = false
                val kind = dataIssueOf(e)
                // 空/失败不再回落示例数据
                if (hasData) {
                    // 旧数据继续留在屏幕上，只用行内提示说明这次没换成
                    notice = if (term != null) {
                        "「${terms[term] ?: term}」读取失败：${dataIssueShort(kind)}，当前显示上次缓存"
                    } else {
                        "刷新失败：${dataIssueShort(kind)}，当前显示上次缓存"
                    }
                } else {
                    issue = kind
                    error = "考试安排暂时读不到。" + networkHint(e)
                    loaded = true
                }
            } finally {
                if (gen == loadGen) refreshing = false
            }
        }
    }

    /**
     * 角标手动刷新：当前就在默认学期视图时按默认视图拉取（会写回 page_exams.json）；
     * 用户切到其它学期时只刷新该学期，不覆盖默认视图缓存。
     */
    fun refresh(ctx: Context) {
        val term = currentTerm
        load(ctx, if (term == defaultTerm) null else term, force = true)
    }

    private fun applyHtml(
        html: String,
        sel: String?,
        label: String?,
        src: String,
        recordDefault: Boolean = false,
    ) {
        val allTerms = ExamParser.termMap(html)
        val s = sel?.takeIf { it.isNotBlank() } ?: allTerms.keys.firstOrNull() ?: "-1"
        val l = label?.takeIf { it.isNotBlank() } ?: allTerms[s].orEmpty()
        val parsed = ExamParser.parse(html, s, l)
        // 与成绩页同一裁剪口径：按入学年份隐藏未入学学期；确实有记录/当前视图的学期一律保留
        // （宁可多显示，不可藏真实数据）。grade 为空时 filterTerms 原样返回。
        val recordTerms = parsed.mapNotNull { row -> row.term.takeIf { it.isNotBlank() } }.toSet() +
            listOfNotNull(s.takeIf { it.isNotBlank() && it != "-1" })
        terms = ScoreParser.filterTerms(allTerms, grade, recordTerms)
        currentTerm = s.ifEmpty { null }
        exams = parsed
        if (recordDefault && s != "-1") defaultTerm = s
        source = src
        loaded = true
        hasData = true
        error = null
    }
}

private val ExamTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 倒计时展示数据：数字与单位分开，数字走 NumMedium */
private data class CountdownUi(val value: String, val unit: String, val urgent: Boolean)

/**
 * 倒计时：
 *  - 已过结束时刻 → null（显示「已结束」）
 *  - 已开考未结束 → 「进行中」（赭红）
 *  - 未开考 → 天 / 小时 / 分钟
 */
private fun countdownUi(e: ExamRow, now: LocalDateTime): CountdownUi? {
    if (!e.endAt.isAfter(now)) return null
    if (!e.startAt.isAfter(now)) return CountdownUi("进行中", "", true)
    val remaining = e.countdownDuration(now)
    if (remaining.isNegative || remaining.isZero) return CountdownUi("进行中", "", true)
    val totalHours = remaining.toHours()
    val totalMinutes = remaining.toMinutes()
    return when {
        totalHours >= 24 -> CountdownUi((totalHours / 24).toString(), "天", false)
        totalHours >= 1 -> CountdownUi(totalHours.toString(), "小时", true)
        totalMinutes >= 1 -> CountdownUi(totalMinutes.toString(), "分钟", true)
        else -> CountdownUi("即将", "开考", true)
    }
}

@Composable
fun ExamsScreen(
    vm: ExamsViewModel = viewModel(),
    nav: NavController? = null,
) {
    val ctx = LocalContext.current
    // 其余四页同款守卫：切回 tab 时不重置用户已选学期，也不重发整页请求
    LaunchedEffect(Unit) { if (!vm.loaded) vm.load(ctx) }
    // 每 30 秒推进一次「当前时间」：倒计时数字与「待考/已结束」分组跨过开考/结束时刻会自动翻转
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val now = remember(tick) { LocalDateTime.now() }
    var menuOpen by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(
            title = "考试安排",
            actions = {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(
                            vm.terms[vm.currentTerm] ?: "选择学期",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "切换学期",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        vm.terms.forEach { (code, label) ->
                            DropdownMenuItem(text = { Text(label) },
                                onClick = { menuOpen = false; vm.load(ctx, code) })
                        }
                    }
                }
                ScnuRefreshableSourceBadge(
                    source = vm.source,
                    refreshing = vm.refreshing,
                    success = vm.lastRefreshOk == true,
                    onRefresh = { vm.refresh(ctx) },
                )
            },
        )

        if (vm.refreshing && vm.loaded) {
            ScnuRefreshBar("正在刷新考试安排")
        }

        // 刷新/切学期失败：旧列表保持可见，行内提示说明这次没换成（与学习进度/选课成绩同款）
        ScnuInlineNotice(vm.notice, tone = ScnuNoticeTone.Warning)

        when {
            !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScnuStatePanel(
                    title = "正在读取考试安排",
                    body = "首次进入需要从教务系统拉取本学期考试。",
                    loading = true,
                )
            }
            vm.issue != null -> {
                val issue = vm.issue ?: DataIssue.SERVER
                val copy = issueCopy(issue, subject = "考试安排")
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ScnuStatePanel(
                        title = copy.title,
                        body = copy.body,
                        isError = copy.isError,
                        actionLabel = copy.action,
                        onAction = {
                            if (issue == DataIssue.AUTH_EXPIRED && nav != null) {
                                nav.navigate("auth?system=zf&mode=reauth&ret=exams")
                            } else {
                                // 重试用户最近一次选中的学期
                                vm.load(ctx, vm.pendingTerm ?: vm.currentTerm)
                            }
                        },
                    )
                }
            }
            vm.exams.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScnuStatePanel(title = "本学期暂无考试安排")
            }
            else -> {
                // 分组与「已结束」判定都以结束时刻为准：正在进行的考试属于待考
                val upcoming = vm.exams.filter { it.endAt.isAfter(now) }
                val past = vm.exams.filter { !it.endAt.isAfter(now) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (upcoming.isNotEmpty()) {
                        item(key = "upcoming") { SectionHeader("待考", upcoming.size) }
                        items(
                            upcoming,
                            key = { "u-${it.date}-${it.timeStart}-${it.course}-${it.room}-${it.seat}" },
                        ) {
                            ExamItem(it, now)
                        }
                    }
                    if (past.isNotEmpty()) {
                        item(key = "past") { SectionHeader("已结束", past.size) }
                        items(
                            past,
                            key = { "p-${it.date}-${it.timeStart}-${it.course}-${it.room}-${it.seat}" },
                        ) {
                            ExamItem(it, now)
                        }
                    }
                }
            }
        }
    }
}

/** 分组头：标题 + 场次数；金色只留给「今天/选中」，计数用中性色 */
@Composable
private fun SectionHeader(title: String, count: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(
            start = ScnuSpacing.l,
            end = ScnuSpacing.l,
            top = ScnuSpacing.s,
            bottom = 2.dp,
        ),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
        )
        Spacer(Modifier.width(ScnuSpacing.s))
        Text(count.toString(), style = NumSmall, color = cs.onSurfaceVariant)
        Text(
            "场",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        )
    }
}

/** 打开系统日历的新建日程页；设备无日历应用时 Toast 提示 */
private fun addToCalendar(ctx: Context, e: ExamRow) {
    val zone = ZoneId.systemDefault()
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, e.course)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
            e.startAt.atZone(zone).toInstant().toEpochMilli())
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
            e.endAt.atZone(zone).toInstant().toEpochMilli())
    }
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(ctx, "未找到日历应用，无法加入日程", Toast.LENGTH_SHORT).show()
    }
}

/** 一场考试：左 52dp 竖排日期块 + 右侧课程/时间/考场 + 右端倒计时 */
@Composable
private fun ExamItem(e: ExamRow, now: LocalDateTime) {
    val ctx = LocalContext.current
    val room = e.roomParts
    val countdown = countdownUi(e, now)
    val cs = MaterialTheme.colorScheme

    Surface(
        color = cs.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ScnuSpacing.m, vertical = 3.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = ScnuSpacing.m)) {
            // ── 左：52dp 竖排日期（月份/日号都用数字字体，但不再铺金色） ──
            Column(
                Modifier.width(52.dp).fillMaxHeight().padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(e.date.monthValue.toString(), style = NumMedium, color = cs.onSurfaceVariant)
                    Text("月",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 1.dp, bottom = 4.dp))
                }
                Text(e.date.dayOfMonth.toString(), style = NumLarge, color = cs.onSurface)
            }

            // 竖线分隔日期块与正文（替代中点连接符）
            Box(
                Modifier.padding(vertical = 2.dp)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(cs.outline.copy(alpha = ScnuTokens.OutlineStrongAlpha)),
            )

            // ── 中：课程名 + 时间/座位 + 考场三段 ──
            Column(Modifier.weight(1f).padding(start = ScnuSpacing.m, end = ScnuSpacing.s)) {
                Text(e.course,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                ScnuMetaLine(
                    listOf(
                        "${e.timeStart.format(ExamTimeFmt)}-${e.timeEnd.format(ExamTimeFmt)}",
                        if (e.seat.isNotEmpty()) "座位 ${e.seat}" else "",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                RoomBadges(room)
            }

            // ── 右：倒计时（临近/进行中赭红，其余正文色）+ 加入日历 ──
            Column(
                Modifier.fillMaxHeight().padding(end = ScnuSpacing.s),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                if (countdown != null) {
                    val tone = if (countdown.urgent) cs.error else cs.onSurface
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            countdown.value,
                            style = if (countdown.unit.isEmpty()) {
                                MaterialTheme.typography.labelMedium
                            } else NumMedium,
                            color = tone,
                        )
                        if (countdown.unit.isNotEmpty()) {
                            Text(countdown.unit,
                                style = MaterialTheme.typography.labelSmall,
                                color = tone,
                                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
                        }
                    }
                } else {
                    Text("已结束",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant)
                }
                IconButton(
                    onClick = { addToCalendar(ctx, e) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Outlined.Event, contentDescription = "加入日历",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 考场三段式：校区徽章（BadgeShape）+ 楼栋 + 房间号加粗 */
@Composable
private fun RoomBadges(room: RoomParts) {
    val cs = MaterialTheme.colorScheme
    if (room.campus.isEmpty() && room.building.isEmpty() && room.room.isEmpty()) {
        Text("考场待定",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (room.campus.isNotEmpty()) {
            ScnuStatusBadge(room.campus)
        }
        if (room.building.isNotEmpty()) {
            Text(room.building,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (room.room.isNotEmpty()) {
            Text(room.room,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                maxLines = 1)
        }
    }
}
