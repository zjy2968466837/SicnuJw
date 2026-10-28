package cn.edu.sicnu.scnujw.ui.zhxg

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.zhxg.*
import cn.edu.sicnu.scnujw.ui.common.ScnuBadgeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuRefreshBar
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.common.ScnuStatusBadge
import cn.edu.sicnu.scnujw.ui.theme.NumMicro
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 页面状态 */
enum class LoadState { Loading, Ready, Error, NeedLogin }

class HolidayViewModel : ViewModel() {
    var state by mutableStateOf(LoadState.Loading); private set
    var errorMsg by mutableStateOf<String?>(null); private set

    var batch by mutableStateOf<HolidayBatch?>(null); private set
    var names by mutableStateOf<List<HolidayBatch>>(emptyList()); private set
    var selectedBatchId by mutableStateOf<Long?>(null); private set
    var myHoliday by mutableStateOf<MyHoliday?>(null); private set
    var studentCode by mutableStateOf(""); private set

    /** 后台静默刷新中：已有数据继续显示，页面顶部用 ScnuRefreshBar 提示 */
    var refreshing by mutableStateOf(false); private set
    /** 后台刷新失败提示（失败时保留旧数据，不切空态） */
    var refreshError by mutableStateOf<String?>(null); private set
    /** 后台刷新因身份失效失败：提示里给出「去验证」入口 */
    var refreshNeedLogin by mutableStateOf(false); private set

    /** 上次成功从 zhxg 拉到数据的时间戳；0 表示本次 ViewModel 生命周期内还没成功过 */
    var fetchedAt by mutableStateOf(0L); private set

    /** 选中批次的登记（无记录时为 null） */
    val currentRegisters: List<HolidayRegister> get() = myHoliday?.registers.orEmpty()
    val currentReturns: List<HolidayReturn> get() = myHoliday?.returnRegisters.orEmpty()

    /** 一次全量读取的结果；先全部拉完再落地，避免刷新中途把页面改成半新半旧 */
    private data class Snapshot(
        val studentCode: String,
        val batch: HolidayBatch?,
        val names: List<HolidayBatch>,
        val targetBatchId: Long?,
        val myHoliday: MyHoliday?,
    )

    private var loadJob: Job? = null
    private var batchJob: Job? = null

    /**
     * 进入页面时调用：保证看到的是当前真实状态。
     *  - 还没有数据（首次进入 / 上次失败）→ 走加载态；
     *  - 已有数据但超过 [FRESH_WINDOW_MS] → 后台静默刷新（旧数据留在屏幕上）；
     *  - 60 秒内 → 不打网络，直接复用。
     * 已有请求在飞时不重复发起（例如旋转屏幕重新触发 LaunchedEffect）。
     */
    fun onEnter() {
        if (loadJob?.isActive == true || batchJob?.isActive == true) return
        when {
            state != LoadState.Ready -> load()
            System.currentTimeMillis() - fetchedAt > FRESH_WINDOW_MS -> refresh()
        }
    }

    /** 首次加载：显示加载态；失败按原逻辑切到错误 / 需要验证 */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            state = LoadState.Loading
            errorMsg = null
            refreshError = null
            refreshNeedLogin = false
            refreshing = false
            try {
                applySnapshot(fetchSnapshot(preferredBatchId = null))
                fetchedAt = System.currentTimeMillis()
                state = LoadState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = when (e) {
                    // AuthGuard 判定需要一次 MFA / 账密验证
                    is cn.edu.sicnu.scnujw.core.auth.AuthGuard.InteractiveAuthRequired -> LoadState.NeedLogin
                    is ZhxgClient.NotLoggedInException -> LoadState.NeedLogin
                    else -> LoadState.Error
                }
                errorMsg = e.message
            }
        }
    }

    /**
     * 后台静默刷新：不动 state（保持 Ready），旧数据继续显示，只置 [refreshing]。
     * 失败时保留旧数据并给出 [refreshError]；身份失效额外置 [refreshNeedLogin]。
     */
    fun refresh() {
        if (loadJob?.isActive == true || batchJob?.isActive == true) return
        if (state != LoadState.Ready) { load(); return }
        loadJob = viewModelScope.launch {
            refreshing = true
            refreshError = null
            refreshNeedLogin = false
            val selectedAtStart = selectedBatchId
            try {
                val snap = fetchSnapshot(preferredBatchId = selectedAtStart)
                // 刷新期间用户切了批次：批次列表照常更新，登记内容交给 selectBatch 落地，避免串批次
                batch = snap.batch
                names = snap.names
                studentCode = snap.studentCode
                if (selectedBatchId == selectedAtStart) {
                    selectedBatchId = snap.targetBatchId
                    myHoliday = snap.myHoliday
                }
                // 全量数据已是最新，之前「某个历史批次读取失败」的提示不再成立
                errorMsg = null
                fetchedAt = System.currentTimeMillis()
                state = LoadState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (e) {
                    is cn.edu.sicnu.scnujw.core.auth.AuthGuard.InteractiveAuthRequired,
                    is ZhxgClient.NotLoggedInException -> {
                        refreshNeedLogin = true
                        refreshError = "身份验证已过期，下面显示的是上次读取的登记状态。"
                    }
                    else -> refreshError = "刷新失败，下面显示的是上次读取的登记状态。"
                }
            } finally {
                refreshing = false
            }
        }
    }

    private fun applySnapshot(snap: Snapshot) {
        studentCode = snap.studentCode
        batch = snap.batch
        names = snap.names
        selectedBatchId = snap.targetBatchId
        myHoliday = snap.myHoliday
    }

    /**
     * 全量读取：学号 → 当前批次 + 历史批次 → 我的登记。
     * [preferredBatchId] 用于刷新时保留用户正在看的历史批次（已不在列表则回落到默认批次）。
     */
    private suspend fun fetchSnapshot(preferredBatchId: Long?): Snapshot {
        // 1) 拿学号（同时触发 zhxg 换票建会话）
        val (code, _) = ZhxgClient.userInfo()
            ?: throw ZhxgClient.NotLoggedInException()
        // 2) 当前批次 + 历史批次列表
        val b = ZhxgClient.defaultBatch()
        val n = ZhxgClient.holidayNames()
        val keep = preferredBatchId?.takeIf { id -> b?.id == id || n.any { it.id == id } }
        val target = keep ?: b?.id
        // 3) 我的登记
        val mine = target?.let { ZhxgClient.myHoliday(it, code) }
        return Snapshot(code, b, n, target, mine)
    }

    /**
     * 切换历史批次，重新拉该批次的登记。
     * 并发保护：新请求取消旧请求；失败回滚选中项并给出提示，
     * 避免「选中新批次、内容还是旧批次」的错配。
     */
    fun selectBatch(id: Long) {
        if (id == selectedBatchId) return
        val previous = selectedBatchId
        batchJob?.cancel()
        selectedBatchId = id
        errorMsg = null
        // 本次是新的尝试：上一次的刷新失败提示与「去验证」入口不再适用
        refreshError = null
        refreshNeedLogin = false
        batchJob = viewModelScope.launch {
            try {
                myHoliday = ZhxgClient.myHoliday(id, studentCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                selectedBatchId = previous
                when (e) {
                    // 会话/票据失效：给「去验证」入口，否则用户只能对着同一条提示反复重试
                    is cn.edu.sicnu.scnujw.core.auth.AuthGuard.InteractiveAuthRequired,
                    is ZhxgClient.NotLoggedInException -> {
                        refreshNeedLogin = true
                        refreshError = "身份验证已过期，这个历史批次没有读取成功。"
                    }
                    else -> errorMsg = "这个历史批次没有读取成功，请重试。"
                }
            }
        }
    }

    fun clearError() { errorMsg = null }

    companion object {
        /** 数据保鲜期：进入页面时超过这个时长就静默重拉（保证看到的是当前真实状态） */
        const val FRESH_WINDOW_MS = 60_000L
    }
}

/**
 * 批次登记窗口是否开放：离校/留校各自有独立的开始与结束时间，
 * 只有落在**某个窗口内**（已开始且未结束）才算开放——未开始的批次不得显示「开放登记」。
 * 结束时间为空的窗口视为无效；开始时间为空时按「不限开始」处理，避免误报已截止。
 */
private fun registerOpen(b: HolidayBatch, now: LocalDateTime): Boolean {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val windows = listOf(
        b.leaveRegisterStartTime to b.leaveRegisterEndTime,
        b.stayRegisterStartTime to b.stayRegisterEndTime,
    ).filter { (_, end) -> end.isNotBlank() }
        .mapNotNull { (start, end) ->
            val endAt = runCatching { LocalDateTime.parse(end, fmt) }.getOrNull()
                ?: return@mapNotNull null
            val startAt = runCatching { LocalDateTime.parse(start, fmt) }.getOrNull()
            startAt to endAt
        }
    return windows.any { (startAt, endAt) ->
        (startAt == null || !now.isBefore(startAt)) && now.isBefore(endAt)
    }
}

/** 当前批次假期是否已结束（以返校登记结束时间为界） */
private fun holidayClosed(b: HolidayBatch, now: LocalDateTime): Boolean {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val end = runCatching { LocalDateTime.parse(b.returnRegisterEndTime.ifBlank { b.endDate + " 23:59" }, fmt) }
        .getOrNull() ?: return false
    return now.isAfter(end)
}

@Composable
fun HolidayRegisterScreen(nav: NavController, vm: HolidayViewModel = viewModel()) {
    // 每次进入页面都按「是否超过 60 秒」决定是否静默刷新：
    // ViewModel 存活（旋转 / 从验证页返回）时也不会把上次的旧状态当成当前状态。
    LaunchedEffect(Unit) { vm.onEnter() }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(title = "假期去向登记", onBack = { nav.navigateUp() })

        // 已有内容时的后台刷新提示：旧数据仍在下方，不闪空
        if (vm.refreshing) ScnuRefreshBar("正在刷新假期登记")

        when (vm.state) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ScnuStatePanel(
                    title = "正在读取假期登记",
                    body = "需要先建立智慧学工会话。",
                    loading = true,
                )
            }
            LoadState.NeedLogin -> ScnuStatePanel(
                modifier = Modifier.fillMaxSize(),
                title = "需要先验证身份",
                body = "智慧学工和教务是两套业务会话，但共用同一份统一认证票据。完成一次验证后会自动建立会话。",
                actionLabel = "去验证",
                onAction = { nav.navigate("auth?system=zhxg&mode=reauth&ret=zhxg_holiday") },
            )
            LoadState.Error -> ScnuStatePanel(
                modifier = Modifier.fillMaxSize(),
                title = "假期登记加载失败",
                body = "智慧学工暂时没有返回数据。",
                hint = "确认已连接校园网并完成身份验证后再试。",
                isError = true,
                actionLabel = "重新加载",
                onAction = { vm.load() },
            )
            LoadState.Ready -> HolidayContent(
                vm,
                onReauth = { nav.navigate("auth?system=zhxg&mode=reauth&ret=zhxg_holiday") },
            )
        }
    }
}

@Composable
private fun HolidayContent(vm: HolidayViewModel, onReauth: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // 页面停留期间每 30 秒推进一次「当前时间」：跨过登记开始/截止时刻时徽章与文案会自动翻转
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    // 每 30 秒一次（tick），以及每次成功拉到数据（fetchedAt 变化）时重算「当前时间」
    val now = remember(vm.fetchedAt, tick) { LocalDateTime.now() }
    // 当前批次来自 defaultBatch，带完整登记窗口时间；历史批次列表接口（queryHolidayName）
    // 不含任何窗口时间字段，所以只有「选中的不是当前批次」时才回落到列表条目。
    val current = vm.batch
    val batch = if (current != null && vm.selectedBatchId == current.id) {
        current
    } else {
        vm.names.firstOrNull { it.id == vm.selectedBatchId } ?: current
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(ScnuSpacing.l),
        verticalArrangement = Arrangement.spacedBy(ScnuSpacing.l),
    ) {
        // 历史批次切换失败的内联提示
        vm.errorMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = cs.error,
            )
        }
        // 后台刷新失败的提示：保留旧数据，只说明「下面是上次读取的结果」
        vm.refreshError?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.error,
                    modifier = Modifier.weight(1f),
                )
                if (vm.refreshNeedLogin) {
                    TextButton(onClick = onReauth) {
                        Text("去验证", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(onClick = { vm.refresh() }) {
                        Text("重试", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        // ── 假期批次卡 ──
        if (batch == null) {
            Surface(color = cs.surface, shape = MaterialTheme.shapes.large,
                border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
                modifier = Modifier.fillMaxWidth()) {
                Text("当前学年度暂无假期登记批次",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(ScnuSpacing.xl))
            }
        } else {
            // 历史批次卡必须展示「选中批次」自己的窗口，而不是当前批次的
            val open = registerOpen(batch, now)
            val closed = holidayClosed(batch, now)
            Surface(color = cs.surface, shape = MaterialTheme.shapes.large,
                border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(ScnuSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(batch.holidayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface, modifier = Modifier.weight(1f))
                        StatusBadge(
                            when {
                                closed -> "已结束"
                                open -> "开放登记"
                                else -> "已截止"
                            },
                            accent = open && !closed,
                            error = closed,
                        )
                    }
                    InfoLine("假期时间", "${batch.startDate} ~ ${batch.endDate}", numeric = true)
                    if (batch.leaveRegisterStartTime.isNotBlank()) {
                        InfoLine("离校登记",
                            "${batch.leaveRegisterStartTime} ~ ${batch.leaveRegisterEndTime}",
                            numeric = true)
                    }
                    if (batch.stayRegisterStartTime.isNotBlank() &&
                        batch.stayRegisterStartTime != batch.leaveRegisterStartTime) {
                        InfoLine("留校登记",
                            "${batch.stayRegisterStartTime} ~ ${batch.stayRegisterEndTime}",
                            numeric = true)
                    }
                    if (batch.returnRegisterStartTime.isNotBlank()) {
                        InfoLine("返校登记",
                            "${batch.returnRegisterStartTime} ~ ${batch.returnRegisterEndTime}",
                            numeric = true)
                    }
                    if (batch.description.isNotBlank()) {
                        Text(batch.description, style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant)
                    }
                }
            }

            // ── 历史批次切换（仅当有多批次） ──
            if (vm.names.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
                    Text("切换批次", style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant)
                    vm.names.take(4).forEach { n ->
                        val sel = n.id == vm.selectedBatchId
                        Surface(
                            color = if (sel) cs.primary.copy(alpha = ScnuTokens.AccentIndicatorAlpha)
                            else cs.surface,
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(
                                ScnuTokens.CardBorder,
                                if (sel) cs.primary.copy(alpha = 0.5f)
                                else cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                            ),
                            modifier = Modifier.clickable { vm.selectBatch(n.id) },
                        ) {
                            Text(n.holidayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) cs.primary else cs.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = ScnuSpacing.s,
                                    vertical = ScnuSpacing.xs,
                                ))
                        }
                    }
                }
            }

            // ── 我的登记状态 ──
            MyStatusCard(vm, batch, open && !closed, now)
        }
    }
}

/** 我的登记状态卡：未登记 / 已登记（离校|留校）/ 已返校 */
@Composable
private fun MyStatusCard(vm: HolidayViewModel, batch: HolidayBatch, registerOpen: Boolean, now: LocalDateTime) {
    val cs = MaterialTheme.colorScheme
    val registers = vm.currentRegisters
    val returns = vm.currentReturns
    val mh = vm.myHoliday

    Surface(color = cs.surface, shape = MaterialTheme.shapes.large,
        border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ScnuSpacing.l),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的登记", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                    modifier = Modifier.weight(1f))
                when {
                    registers.isEmpty() -> StatusBadge("未登记", muted = true)
                    else -> StatusBadge(registers.first().scheduleTypeDesc, accent = true)
                }
            }

            if (registers.isEmpty()) {
                Text("本批次暂未提交去向登记",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                // 不做「点了只弹 Toast」的假动作：提交能力未接入时明确说明
                Text(
                    if (registerOpen) "在线提交入口将在下个版本开放"
                    else if (holidayClosed(batch, now)) "登记已截止" else "当前不在登记时间内",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                // 去向登记明细
                registers.forEach { r ->
                    RegisterDetail(r)
                }
                // 返校登记
                returns.forEach { r ->
                    ReturnDetail(r)
                }
            }

            // 学生信息（来自 myHoliday）
            mh?.let { info ->
                HorizontalDivider(
                    color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    thickness = ScnuTokens.Hairline,
                )
                InfoLine("姓名", info.studentName)
                if (info.orgName.isNotBlank()) InfoLine("学院", info.orgName)
                if (info.majorName.isNotBlank()) InfoLine("专业", info.majorName)
                if (info.classFName.isNotBlank()) InfoLine("班级", info.classFName)
                if (info.campusName.isNotBlank()) InfoLine("校区", info.campusName)
                if (info.dormInfo.isNotBlank()) InfoLine("宿舍", info.dormInfo)
                if (info.phone.isNotBlank()) InfoLine("联系电话", info.phone, numeric = true)
            }
        }
    }
}

/** 单条去向登记详情 */
@Composable
private fun RegisterDetail(r: HolidayRegister) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceVariant, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ScnuSpacing.m),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.scheduleTypeDesc, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                    modifier = Modifier.weight(1f))
                if (r.auditStatus.isNotBlank()) {
                    StatusBadge(r.auditStatus,
                        accent = r.auditStatus == "已通过",
                        error = r.auditStatus == "已退回" || r.auditStatus == "未通过")
                }
            }
            if (r.reason.isNotBlank()) InfoLine(r.scheduleTypeDesc.let { if (it == "留校") "留校原因" else "去向事由" }, r.reason)
            if (r.startDate.isNotBlank()) InfoLine("开始日期", r.startDate, numeric = true)
            if (r.endDate.isNotBlank()) InfoLine("结束日期", r.endDate, numeric = true)
            if (r.destination.isNotBlank()) {
                InfoLine("目的地", r.destination.replace(",", " / "))
            }
            if (r.travelType.isNotBlank()) InfoLine("出行方式", r.travelType)
            if (r.activityDefName.isNotBlank() && r.activityDefName != "结束") {
                InfoLine("流程节点", r.activityDefName)
            }
        }
    }
}

/** 单条返校登记详情 */
@Composable
private fun ReturnDetail(r: HolidayReturn) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceVariant, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ScnuSpacing.m),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("返校登记", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                    modifier = Modifier.weight(1f))
                if (r.returnStatusDesc.isNotBlank()) {
                    StatusBadge(r.returnStatusDesc, accent = r.returnStatusDesc == "已返校")
                }
            }
            if (r.returnTypeDesc.isNotBlank()) InfoLine("返校类型", r.returnTypeDesc)
            if (r.realReturnDate.isNotBlank()) InfoLine("实际返校", "${r.realReturnDate} ${r.realReturnTime}".trim(), numeric = true)
            if (r.confirmStatusDesc.isNotBlank()) InfoLine("确认状态", r.confirmStatusDesc)
        }
    }
}

/** 状态徽章：统一走共享组件（accent=金 20% 底，error=errorContainer，muted/neutral=描边） */
@Composable
private fun StatusBadge(text: String, accent: Boolean = false, error: Boolean = false, muted: Boolean = false) {
    val tone = when {
        accent -> ScnuBadgeTone.Accent
        error -> ScnuBadgeTone.Error
        muted -> ScnuBadgeTone.Muted
        else -> ScnuBadgeTone.Neutral
    }
    ScnuStatusBadge(text, tone = tone)
}

/** label/value 行：label 灰绿窄列，value 正文（中间竖线分隔，不用中点）；[numeric] 用数字字体 */
@Composable
private fun InfoLine(label: String, value: String, numeric: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            modifier = Modifier.width(72.dp))
        Box(
            Modifier.padding(top = 4.dp, end = ScnuSpacing.s)
                .width(1.dp).height(10.dp)
                .background(cs.outline.copy(alpha = ScnuTokens.OutlineStrongAlpha)),
        )
        Text(
            value,
            style = if (numeric) NumMicro else MaterialTheme.typography.bodySmall,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
