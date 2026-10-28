package cn.edu.sicnu.scnujw.ui.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.sicnu.scnujw.core.jwcxk.CourseSession
import cn.edu.sicnu.scnujw.ui.common.CourseDetailSheet
import cn.edu.sicnu.scnujw.ui.common.DataIssue
import cn.edu.sicnu.scnujw.ui.common.ScnuInlineNotice
import cn.edu.sicnu.scnujw.ui.common.ScnuNoticeTone
import cn.edu.sicnu.scnujw.ui.common.issueCopy
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshableSourceBadge
import cn.edu.sicnu.scnujw.ui.theme.BadgeShape
import cn.edu.sicnu.scnujw.ui.theme.NumMedium
import cn.edu.sicnu.scnujw.ui.theme.NumMicro
import cn.edu.sicnu.scnujw.ui.theme.ScnuPalette
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import java.time.LocalDate

private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

/** 课表几何：一格 56dp，一天 86dp，节次轴 30dp（与旧版一致，避免信息密度回退） */
private val CELL_HEIGHT = 56.dp
private val DAY_WIDTH = 86.dp
private val AXIS_WIDTH = 30.dp
private val DAY_HEADER_HEIGHT = 42.dp

@Composable
fun ScheduleScreen(vm: ScheduleViewModel = viewModel(), nav: androidx.navigation.NavController? = null) {
    val ctx = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // 首次进入拉取。ViewModel 挂在 schedule 的 NavBackStackEntry 上，切 tab 复出的组合重建时
    // loaded 已为 true，不再重复联网；从 webauth（课表 SSO）返回会重建 entry（新 VM）自然重拉。
    LaunchedEffect(Unit) { if (!vm.loaded) vm.load(ctx) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            // 只有「还没有任何课表且上次失败」时才在回前台重试，避免每次 resume 都联网
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                vm.schedule == null && vm.error != null
            ) {
                vm.load(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val schedule = vm.schedule
    val todayDow = LocalDate.now().dayOfWeek.value
    // 点课程块 → 执行计划详情
    var detailSession by remember { mutableStateOf<CourseSession?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
        ScheduleHeader(
            semester = schedule?.semesterLabel?.takeIf { it.isNotBlank() } ?: "课程表",
            source = vm.source,
            refreshing = vm.refreshing,
            refreshOk = vm.lastRefreshOk == true,
            week = vm.selectedWeek,
            currentWeek = vm.currentWeek,
            onPrev = vm::prevWeek,
            onNext = vm::nextWeek,
            onThisWeek = vm::thisWeek,
            onRefresh = { vm.load(ctx, force = true) },
        )

        ScnuInlineNotice(vm.notice, tone = ScnuNoticeTone.Warning)

        when {
            schedule == null && vm.error != null -> {
                val issue = vm.issue ?: DataIssue.SERVER
                val copy = issueCopy(issue, subject = "课表")
                ScnuStatePanel(
                    title = copy.title,
                    body = copy.body,
                    isError = copy.isError,
                    actionLabel = if (issue == DataIssue.AUTH_EXPIRED) "去验证" else copy.action,
                    onAction = {
                        if (issue == DataIssue.AUTH_EXPIRED && nav != null) {
                            nav.navigate("auth?system=jwcxk&mode=reauth&ret=schedule")
                        } else {
                            vm.load(ctx, force = true)
                        }
                    },
                    secondaryLabel = if (issue == DataIssue.AUTH_EXPIRED) "重新加载" else "补一次课表登录",
                    onSecondaryAction = {
                        if (issue == DataIssue.AUTH_EXPIRED) {
                            vm.load(ctx, force = true)
                        } else {
                            nav?.navigate(
                                "webauth?url=" + android.net.Uri.encode(
                                    "https://jwcxk.sicnu.edu.cn/student/sso/login"
                                )
                            )
                        }
                    },
                )
            }

            schedule == null -> ScnuStatePanel(
                title = "正在读取课表",
                body = "正在从教务系统获取本学期课程。",
                loading = true,
            )

            schedule.sessions.isEmpty() -> ScnuStatePanel(
                title = "本学期暂无课表",
                actionLabel = "重新加载",
                onAction = { vm.load(ctx, force = true) },
            )

            else -> WeekGrid(
                sessions = schedule.sessions,
                week = vm.selectedWeek,
                currentWeek = vm.currentWeek,
                todayDow = todayDow,
                onCourseClick = { detailSession = it },
            )
        }
        }

        detailSession?.let { s ->
            CourseDetailSheet(
                courseName = s.name,
                term = null,        // 课表对应选课结果页默认（当前）学期
                courseCode = "",
                onDismiss = { detailSession = null },
            )
        }
    }
}

// ── 顶部：学期 / 数据源角标 / 周次导航 ─────────────────────────────

@Composable
private fun ScheduleHeader(
    semester: String,
    source: String,
    refreshing: Boolean,
    refreshOk: Boolean,
    week: Int,
    currentWeek: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onThisWeek: () -> Unit,
    onRefresh: () -> Unit,
) {
    val isCurrent = week == currentWeek
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = ScnuSpacing.l, end = ScnuSpacing.m, top = ScnuSpacing.m, bottom = ScnuSpacing.s)
    ) {
        // 学期 + 数据源角标（统一角标外观：BadgeShape + outline，不再额外挂「数据源」标签）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                semester,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(ScnuSpacing.s))
            ScnuRefreshableSourceBadge(
                source = source,
                refreshing = refreshing,
                success = refreshOk,
                onRefresh = onRefresh,
            )
        }

        Spacer(Modifier.height(ScnuSpacing.s))

        // 周次导航：第 N 周用 BarlowCondensed（NumMedium），本周按钮金色描边
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeekArrowButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                label = "上一周",
                onClick = onPrev,
            )
            Row(Modifier.padding(horizontal = 4.dp)) {
                Text(
                    "第",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline().padding(end = 2.dp),
                )
                Text(
                    "$week",
                    style = NumMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    "周",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline().padding(start = 2.dp),
                )
            }
            WeekArrowButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                label = "下一周",
                onClick = onNext,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onThisWeek,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(
                    // 金色只留给描边/选中底，文字用墨色保证小字号可读
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    containerColor = if (isCurrent) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        Color.Transparent
                    },
                ),
                contentPadding = PaddingValues(horizontal = ScnuSpacing.m, vertical = 0.dp),
            ) {
                Text("本周", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun WeekArrowButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 课表网格 ─────────────────────────────────────────────────────

@Composable
private fun WeekGrid(
    sessions: List<CourseSession>,
    week: Int,
    currentWeek: Int,
    todayDow: Int,
    onCourseClick: (CourseSession) -> Unit,
) {
    val maxPeriod = (sessions.maxOf { it.startPeriod + it.spanPeriods }).coerceAtMost(14)
    val weekSessions = sessions.filter { it.activeOn(week) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        // 固定表头：今天列用金色圆底强调（仅在查看本周时出现）
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(AXIS_WIDTH).height(DAY_HEADER_HEIGHT))
            Row(Modifier.horizontalScroll(hScroll)) {
                for (day in 1..7) {
                    DayHeader(
                        day = day,
                        isToday = week == currentWeek && day == todayDow,
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
            thickness = ScnuTokens.Hairline,
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                // 时间轴：节次号是数字，走 Barlow（NumMicro），与课程块钟点同一字体
                Column(Modifier.width(AXIS_WIDTH)) {
                    for (period in 1..maxPeriod) {
                        Box(
                            Modifier.fillMaxWidth().height(CELL_HEIGHT),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$period",
                                style = NumMicro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(Modifier.horizontalScroll(hScroll)) {
                    for (day in 1..7) {
                        DayBody(
                            maxPeriod = maxPeriod,
                            sessions = weekSessions.filter { it.dayOfWeek == day },
                            onCourseClick = onCourseClick,
                        )
                    }
                }
            }

            if (weekSessions.isEmpty()) {
                EmptyWeekHint(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun DayHeader(day: Int, isToday: Boolean) {
    Box(
        Modifier.width(DAY_WIDTH).height(DAY_HEADER_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        val label = "周${DAY_NAMES[day - 1]}"
        if (isToday) {
            // 主题 primary/onPrimary 在深色下即 D_ACCENT 银杏金 / D_ACCENT_INK 墨绿。
            // 强调只押在金色圆底上，字号与其余日期保持一致（不再用更小的 labelSmall）
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
        } else {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayBody(maxPeriod: Int, sessions: List<CourseSession>,
                    onCourseClick: (CourseSession) -> Unit) {
    // 网格线取满不透明 outline：surfaceVariant 在浅色下只有 1.11:1，几乎看不见
    val gridColor = MaterialTheme.colorScheme.outline

    Box(Modifier.width(DAY_WIDTH).height(CELL_HEIGHT * maxPeriod)) {
        // 极淡空格子网格：outline 细描边，不用阴影
        for (period in 1..maxPeriod) {
            Box(
                Modifier
                    .offset(y = CELL_HEIGHT * (period - 1))
                    .fillMaxWidth()
                    .height(CELL_HEIGHT)
                    .border(ScnuTokens.Hairline, gridColor)
            )
        }
        sessions.forEach { session -> CourseBlock(session) { onCourseClick(session) } }
    }
}

@Composable
private fun CourseBlock(session: CourseSession, onClick: () -> Unit = {}) {
    val color = ScnuPalette.COURSE[
        (session.lessonId.hashCode().ushr(1)) % ScnuPalette.COURSE.size
    ]
    val shape = MaterialTheme.shapes.medium // 12dp
    val nameStyle = MaterialTheme.typography.labelMedium.copy(
        color = ScnuPalette.COURSE_INK,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        lineHeight = 14.sp,
    )
    val metaStyle = MaterialTheme.typography.labelSmall.copy(
        color = ScnuPalette.COURSE_INK_SECONDARY,
        letterSpacing = 0.sp,
        lineHeight = 12.sp,
    )
    // 钟点是数字，走 Barlow（NumMicro）——课表里最密的数字阵列
    val clockStyle = NumMicro.copy(
        color = ScnuPalette.COURSE_INK_SECONDARY,
        fontSize = 12.sp,
        lineHeight = 13.sp,
    )
    // 教室在前、校区在后：窄块里先保证“去哪间教室”可读
    val place = listOf(session.room, session.campus)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    Column(
        Modifier
            .offset(
                x = 1.dp,
                y = CELL_HEIGHT * (session.startPeriod - 1) + 1.dp,
            )
            .width(DAY_WIDTH - 2.dp)
            .height(CELL_HEIGHT * session.spanPeriods - 2.dp)
            .clip(shape)
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = ScnuSpacing.xs, vertical = ScnuSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            session.name,
            style = nameStyle,
            maxLines = if (session.spanPeriods > 1) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (place.isNotEmpty()) {
            Text(
                place,
                style = metaStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            slotText(session),
            style = clockStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (session.spanPeriods > 1) {
            Text(
                session.weeksLabel,
                style = metaStyle,
                maxLines = 1,
            )
        }
    }
}

private fun periodText(session: CourseSession): String =
    if (session.spanPeriods > 1) {
        "第${session.startPeriod}-${session.startPeriod + session.spanPeriods - 1}节"
    } else {
        "第${session.startPeriod}节"
    }

/** 上课时间段：优先钟点（08:10-09:45），解析缺失时回退节次 */
private fun slotText(session: CourseSession): String {
    val s = session.startTime.trim()
    val e = session.endTime.trim()
    return if (s.isNotEmpty() && e.isNotEmpty()) "${padClock(s)}-${padClock(e)}"
    else periodText(session)
}

private fun padClock(t: String): String {
    val p = t.split(":")
    if (p.size != 2) return t
    val h = p[0].toIntOrNull() ?: return t
    val m = p[1].toIntOrNull() ?: return t
    return "%02d:%02d".format(h, m)
}

@Composable
private fun EmptyWeekHint(modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(ScnuSpacing.xl)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                ScnuTokens.CardBorder,
                MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                MaterialTheme.shapes.medium,
            )
            .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "这一周没有课",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(ScnuSpacing.xs))
        Text(
            "切换到其他周看看，或点「本周」回到当前周",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

