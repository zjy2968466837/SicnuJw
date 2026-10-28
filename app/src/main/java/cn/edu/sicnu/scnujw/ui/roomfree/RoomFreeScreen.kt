package cn.edu.sicnu.scnujw.ui.roomfree

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.sicnu.scnujw.core.jwcxk.*
import cn.edu.sicnu.scnujw.ui.common.ScnuBadgeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuFilterChip
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.common.ScnuSourceBadge
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.common.ScnuStatusBadge
import cn.edu.sicnu.scnujw.ui.theme.BadgeShape
import cn.edu.sicnu.scnujw.ui.theme.NumMedium
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ── ViewModel ─────────────────────────────────────────────────────

class RoomFreeViewModel : ViewModel() {
    // 下拉数据
    var campuses by mutableStateOf<List<Campus>>(emptyList())
    var buildings by mutableStateOf<List<Building>>(emptyList())
    var rooms by mutableStateOf<List<RoomSimple>>(emptyList())
    var roomTypes by mutableStateOf<List<RoomType>>(emptyList())
    var units by mutableStateOf<List<TimeUnit>>(emptyList())

    // 选中值
    var selectedCampusId by mutableStateOf("")
    var selectedBuildingId by mutableStateOf("")
    var selectedRoomId by mutableStateOf("")
    var selectedRoomType by mutableStateOf<Int?>(null)
    var seatsGte by mutableStateOf("")

    // 时间模式
    var byWeek by mutableStateOf(false)
    var byTime by mutableStateOf(false)

    // 按教学周
    var selectedWeeks by mutableStateOf<List<String>>(emptyList())
    // 星期几默认选中今天（避免进页面就因缺星期几查询失败）
    var selectedWeekdays by mutableStateOf(listOf(LocalDate.now().dayOfWeek.value.toString()))

    // 按日期（默认今天）
    var startDate by mutableStateOf(LocalDate.now().toString())
    var endDate by mutableStateOf(LocalDate.now().toString())

    // 按节次
    var selectedUnits by mutableStateOf<List<String>>(emptyList())

    // 按时间
    var startTime by mutableStateOf("")
    var endTime by mutableStateOf("")

    // 结果
    var freeRooms by mutableStateOf<List<FreeRoom>>(emptyList())
    var searched by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var initialLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set
    /** 表单校验提示（与网络/会话错误分开，不要画成「加载失败」） */
    var formError by mutableStateOf<String?>(null)

    // 学期信息
    var semesterStart by mutableStateOf<String?>(null)
    var totalWeeks by mutableIntStateOf(20)

    fun loadInitial() {
        if (initialLoading) return
        initialLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("RoomFree", "loadInitial: start")
                // 尝试从 room-free 页面提取校区列表和教室类型（内嵌变量）
                val html = try {
                    JwcxkClient.fetchRenderedPage(JwcxkClient.ROUTE_ROOM_FREE).html()
                } catch (e: Exception) {
                    android.util.Log.w("RoomFree", "fetchRenderedPage failed, using fallback campuses", e)
                    ""
                }
                android.util.Log.d("RoomFree", "loadInitial: got html, size=${html.length}")

                campuses = extractCampuses(html)
                roomTypes = extractRoomTypes(html)

                // 如果无法从页面提取（会话过期或页面结构变化），使用硬编码后备
                if (campuses.isEmpty()) {
                    android.util.Log.w("RoomFree", "No campuses extracted, using fallback")
                    campuses = listOf(
                        Campus("1", "狮子山校区"),
                        Campus("3", "成龙校区"),
                        Campus("9", "遂宁校区"),
                    )
                }
                if (roomTypes.isEmpty()) {
                    roomTypes = listOf(
                        RoomType(2, "普通教室"),
                        RoomType(3, "多媒体教室"),
                        RoomType(4, "语音实验室"),
                        RoomType(6, "普通实验室"),
                    )
                }

                android.util.Log.d("RoomFree", "loadInitial: campuses=${campuses.size}, roomTypes=${roomTypes.size}")
                // 校区优先取登录信息里的校区（课表页出现的校区名），取不到再退回第一个
                val detected = detectCampusFromLogin(campuses)
                selectedCampusId = (detected ?: campuses.first()).id
                android.util.Log.d("RoomFree", "loadInitial: campus=${selectedCampusId} (detected=${detected?.nameZh})")
                onCampusSelected()
            } catch (e: Exception) {
                android.util.Log.e("RoomFree", "loadInitial failed", e)
                error = "校区与教室类型没有取到。请确认已连接校园网，完成一次课表登录后再试。"
            } finally {
                initialLoading = false
            }
        }
    }

    /** 从登录后的课表页（Ams 缓存）里统计校区名出现次数，出现最多的即本人所在校区。
     *  课表只含本人课程，因此校区分布天然收敛到 1 个（实测：成龙校区 40 次、其余 0 次）。 */
    private fun detectCampusFromLogin(campuses: List<Campus>): Campus? {
        val html = cn.edu.sicnu.scnujw.core.net.SessionManager
            .getJwcxkPage(JwcxkClient.ROUTE_COURSE_TABLE) ?: return null
        return campuses
            .map { it to html.windowed(it.nameZh.length).count { w -> w == it.nameZh } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun onCampusSelected() {
        if (selectedCampusId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("RoomFree", "onCampusSelected: campusId=$selectedCampusId")
                buildings = JwcxkClient.fetchBuildings(selectedCampusId)
                units = JwcxkClient.fetchUnitCampus(selectedCampusId)
                semesterStart = JwcxkClient.fetchTableLayoutCampus(selectedCampusId)
                android.util.Log.d("RoomFree", "onCampusSelected: buildings=${buildings.size}, units=${units.size}")
                // 重置下游选择
                selectedBuildingId = ""
                rooms = emptyList()
                selectedRoomId = ""
                // 节次默认全选（全天），进来即可直接查询
                selectedUnits = units.map { it.value }
                error = null
            } catch (e: JwcxkClient.JwcxkSessionExpiredException) {
                android.util.Log.w("RoomFree", "jwcxk session expired", e)
                buildings = emptyList()
                units = emptyList()
                error = "登录状态已失效，请重新完成一次课表登录后重试。"
            } catch (e: Exception) {
                android.util.Log.e("RoomFree", "onCampusSelected failed", e)
                buildings = emptyList()
                units = emptyList()
                error = "校区数据加载失败，请重试。"
            }
        }
    }

    /**
     * 选教学楼后拉教室列表。失败时清空旧教室并给出提示——
     * 否则「新教学楼 + 上一栋楼的教室」会拼出一次不匹配的查询。
     */
    fun onBuildingSelected() {
        val building = selectedBuildingId
        if (building.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = JwcxkClient.fetchRooms(building)
                if (selectedBuildingId == building) {
                    rooms = list
                    selectedRoomId = ""
                }
            } catch (e: Exception) {
                android.util.Log.w("RoomFree", "fetchRooms failed: ${e.javaClass.simpleName}")
                if (selectedBuildingId == building) {
                    rooms = emptyList()
                    selectedRoomId = ""
                    error = "这栋教学楼的教室列表没有取到，请重新选择教学楼或稍后重试。"
                }
            }
        }
    }

    /** 快捷选日：起止日期同一天，星期几同步为该天的星期 */
    fun applyDay(day: LocalDate) {
        startDate = day.format(DATE_FMT)
        endDate = startDate
        selectedWeekdays = listOf(day.dayOfWeek.value.toString())
    }

    fun query() {
        // 校验提示走 formError，不占用 error（error 只表示请求失败）
        formError = null
        if (selectedCampusId.isEmpty()) { formError = "先选择校区再查询"; return }
        if (!byWeek && startDate.isEmpty()) { formError = "先填写起始日期再查询"; return }
        if (byWeek && selectedWeeks.isEmpty()) { formError = "先选择周次再查询"; return }
        if (byWeek && selectedWeekdays.isEmpty()) { formError = "先选择星期几再查询"; return }
        if (!byTime && selectedUnits.isEmpty()) { formError = "先选择节次再查询"; return }
        if (byTime && (startTime.isEmpty() || endTime.isEmpty())) { formError = "先填写起止时间再查询"; return }

        loading = true
        error = null
        searched = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val param = FreeListParam(
                    campusId = selectedCampusId,
                    buildingId = selectedBuildingId,
                    roomId = selectedRoomId,
                    roomType = selectedRoomType,
                    seatsForLessonGte = seatsGte.toIntOrNull(),
                    byWeek = byWeek,
                    byTime = byTime,
                    weeks = selectedWeeks,
                    startDateTime = if (byWeek) "" else startDate,
                    endDateTime = if (byWeek) "" else endDate,
                    units = selectedUnits,
                    startTime = startTime,
                    endTime = endTime,
                    weekdays = selectedWeekdays,
                )
                freeRooms = JwcxkClient.fetchRoomFreeList(param)
                searched = true
            } catch (e: JwcxkClient.JwcxkSessionExpiredException) {
                error = "登录状态已失效，请重新完成一次课表登录后再查询。"
            } catch (e: Exception) {
                android.util.Log.w("RoomFree", "query failed: ${e.javaClass.simpleName}")
                error = "查询没有完成，请稍后再试。"
            } finally {
                loading = false
            }
        }
    }

    fun clearError() { error = null }

    // ── 辅助 ──

    private fun extractCampuses(html: String): List<Campus> {
        // 解析 _campusList = [{'text':'狮子山校区','value':1},...]
        // 注意：页面 JS 里中文是 \uXXXX 转义形式，且页面上有多组同形数组
        // （_roomTypeList/_units 等），必须按变量名分段，否则会混入其他列表
        val m = Regex("""_campusList\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        val itemRegex = """\{'text':'([^']+)','value':(\d+)\}""".toRegex()
        return itemRegex.findAll(m.groupValues[1]).map {
            Campus(it.groupValues[2], unicodeUnescape(it.groupValues[1]))
        }.toList()
    }

    private fun extractRoomTypes(html: String): List<RoomType> {
        // 解析 _roomTypeList = [{'text':'普通教室','value':2},...]（同样需解码 \uXXXX）
        val m = Regex("""_roomTypeList\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        val itemRegex = """\{'text':'([^']+)','value':(\d+)\}""".toRegex()
        return itemRegex.findAll(m.groupValues[1]).map {
            RoomType(it.groupValues[2].toInt(), unicodeUnescape(it.groupValues[1]))
        }.toList()
    }

    /** 解码页面内嵌 JS 里的 \uXXXX 转义（服务端输出中文为转义形式） */
    private fun unicodeUnescape(s: String): String {
        if (!s.contains("\\u")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 5 < s.length && s[i + 1] == 'u') {
                val hex = s.substring(i + 2, i + 6)
                val code = hex.toIntOrNull(16)
                if (code != null) { sb.append(code.toChar()); i += 6; continue }
            }
            sb.append(s[i]); i++
        }
        return sb.toString()
    }
}

// ── Screen ────────────────────────────────────────────────────────

/** 星期写法全 App 统一用「周一…周日」（不用「星期一」） */
private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun RoomFreeScreen(vm: RoomFreeViewModel = viewModel(), nav: androidx.navigation.NavController? = null) {
    LaunchedEffect(Unit) { vm.loadInitial() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScnuScreenHeader(
            title = "空闲教室",
            onBack = nav?.let { n -> { n.navigateUp() } },
            actions = {
                if (vm.searched) ScnuSourceBadge("${vm.freeRooms.size} 间")
            },
        )

        // ── 筛选区（可滚动） ──
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.m),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.m),
        ) {
            // 初始化：先给加载态，避免正常网络下先闪一下「你还没登录」
            if (vm.initialLoading) {
                ScnuStatePanel(
                    modifier = Modifier.fillMaxWidth(),
                    title = "正在读取校区与教室类型",
                    body = "首次进入需要从课表系统取一次基础数据。",
                    loading = true,
                )
                return@Column
            }
            if (vm.campuses.isEmpty() && vm.error == null) {
                InitPanel(
                    onRetry = vm::loadInitial,
                    onWebAuth = nav?.let { n ->
                        {
                            n.navigate("webauth?url=" + android.net.Uri.encode(
                                "https://jwcxk.sicnu.edu.cn/student/sso/login") + "&ret=roomfree")
                        }
                    }
                )
                return@Column
            }

            // 校区
            FilterDropdown(
                label = "校区",
                value = vm.campuses.find { it.id == vm.selectedCampusId }?.nameZh ?: "请选择",
                options = vm.campuses.map { it.id to it.nameZh },
                onSelected = { vm.selectedCampusId = it; vm.onCampusSelected() },
            )

            // 教学楼
            FilterDropdown(
                label = "教学楼",
                value = vm.buildings.find { it.id == vm.selectedBuildingId }?.nameZh ?: "全部",
                options = vm.buildings.map { it.id to it.nameZh },
                onSelected = { vm.selectedBuildingId = it; vm.onBuildingSelected() },
                enabled = vm.buildings.isNotEmpty(),
            )

            // 教室
            FilterDropdown(
                label = "教室",
                value = vm.rooms.find { it.id == vm.selectedRoomId }?.nameZh ?: "全部",
                options = vm.rooms.map { it.id to it.nameZh },
                onSelected = { vm.selectedRoomId = it },
                enabled = vm.rooms.isNotEmpty(),
            )

            // 容量 + 类型：两列共用「标签在框外 + 56dp 高」的同一套控件骨架
            Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.m)) {
                Column(Modifier.weight(1f)) {
                    Text("容量 ≥", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(ScnuSpacing.xs))
                    OutlinedTextField(
                        value = vm.seatsGte,
                        onValueChange = { vm.seatsGte = it.filter { c -> c.isDigit() } },
                        placeholder = { Text("不限") },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.small,
                        singleLine = true,
                    )
                }
                FilterDropdown(
                    label = "教室类型",
                    value = vm.roomTypes.find { it.id == vm.selectedRoomType }?.nameZh ?: "全部",
                    options = vm.roomTypes.map { it.id.toString() to it.nameZh },
                    onSelected = { vm.selectedRoomType = it.toIntOrNull() },
                    modifier = Modifier.weight(1f),
                )
            }

            // 时间模式切换
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间模式", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(ScnuSpacing.m))
                ScnuFilterChip(
                    selected = vm.byWeek,
                    onClick = { vm.byWeek = !vm.byWeek },
                    label = "按教学周",
                )
                Spacer(Modifier.width(ScnuSpacing.s))
                ScnuFilterChip(
                    selected = !vm.byWeek,
                    onClick = { vm.byWeek = false },
                    label = "按日期",
                )
            }

            // 周次/日期
            if (vm.byWeek) {
                WeekSelector(vm)
            } else {
                DateSelector(vm)
            }

            // 星期几
            WeekdaySelector(vm)

            // 时间粒度
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间粒度", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(ScnuSpacing.m))
                ScnuFilterChip(
                    selected = vm.byTime,
                    onClick = { vm.byTime = !vm.byTime },
                    label = "按时间",
                )
                Spacer(Modifier.width(ScnuSpacing.s))
                ScnuFilterChip(
                    selected = !vm.byTime,
                    onClick = { vm.byTime = false },
                    label = "按节次",
                )
            }

            // 节次/时间
            if (vm.byTime) {
                TimeRangeSelector(vm)
            } else {
                UnitSelector(vm)
            }

            // 查询按钮：CTA 说清会发生什么
            Button(
                onClick = vm::query,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                enabled = !vm.loading,
            ) {
                if (vm.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(ScnuSpacing.s))
                }
                Text("查询空闲教室", style = MaterialTheme.typography.labelMedium)
            }

            // 表单校验提示：跟在按钮下方，不画成整页「加载失败」
            vm.formError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ── 结果区 ──
            when {
                vm.error != null -> ScnuStatePanel(
                    modifier = Modifier.fillMaxWidth(),
                    title = "查询没有完成",
                    body = vm.error,
                    isError = true,
                    actionLabel = "重新查询",
                    onAction = vm::query,
                    secondaryLabel = nav?.let { "去完成课表登录" },
                    onSecondaryAction = nav?.let { n ->
                        {
                            n.navigate("webauth?url=" + android.net.Uri.encode(
                                "https://jwcxk.sicnu.edu.cn/student/sso/login") + "&ret=roomfree")
                        }
                    },
                )
                vm.loading -> ScnuStatePanel(
                    modifier = Modifier.fillMaxWidth(),
                    title = "正在查询空闲教室",
                    body = "正在比对课表占用情况。",
                    loading = true,
                )
                vm.searched && vm.freeRooms.isEmpty() -> ScnuStatePanel(
                    modifier = Modifier.fillMaxWidth(),
                    title = "这段时间没有空教室",
                    body = "默认只查今天。可以换一天，或把节次放宽到全天。",
                    actionLabel = "按今天全天再查",
                    onAction = {
                        vm.applyDay(LocalDate.now())
                        vm.selectedUnits = vm.units.map { it.value }
                        vm.query()
                    },
                )
                vm.searched -> ResultList(vm.freeRooms)
            }
        }
    }
}

// ── 筛选组件 ─────────────────────────────────────────────────────

@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(ScnuSpacing.xs))
        Box {
            // 与同排的容量输入框同高（56dp）、同圆角（8dp）、标签同在框外
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = ScnuSpacing.m),
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { open = false; onSelected(id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekSelector(vm: RoomFreeViewModel) {
    val weeks = remember { (1..vm.totalWeeks).map { it.toString() } }
    Column {
        Text("选择周次", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(ScnuSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
            QuickAction("单周") { vm.selectedWeeks = weeks.filter { it.toInt() % 2 == 1 } }
            QuickAction("双周") { vm.selectedWeeks = weeks.filter { it.toInt() % 2 == 0 } }
            QuickAction("全部") { vm.selectedWeeks = weeks }
            QuickAction("清空") { vm.selectedWeeks = emptyList() }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
        ) {
            weeks.forEach { w ->
                val selected = w in vm.selectedWeeks
                ScnuFilterChip(
                    selected = selected,
                    onClick = {
                        vm.selectedWeeks = if (selected) vm.selectedWeeks - w
                        else vm.selectedWeeks + w
                    },
                    label = "第${w}周",
                )
            }
        }
    }
}

@Composable
private fun DateSelector(vm: RoomFreeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.m)) {
            OutlinedTextField(
                value = vm.startDate,
                onValueChange = { vm.startDate = it },
                label = { Text("起始日期") },
                placeholder = { Text("yyyy-MM-dd") },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                singleLine = true,
            )
            OutlinedTextField(
                value = vm.endDate,
                onValueChange = { vm.endDate = it },
                label = { Text("结束日期") },
                placeholder = { Text("yyyy-MM-dd") },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
            // 快捷日期同时对齐星期几，避免用户只改日期后星期几还停在旧值导致查空
            QuickAction("今天") { vm.applyDay(LocalDate.now()) }
            QuickAction("明天") { vm.applyDay(LocalDate.now().plusDays(1)) }
        }
    }
}

@Composable
private fun WeekdaySelector(vm: RoomFreeViewModel) {
    Column {
        Text("星期几", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(ScnuSpacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
        ) {
            WEEKDAYS.forEachIndexed { idx, name ->
                val value = (idx + 1).toString()
                val selected = value in vm.selectedWeekdays
                ScnuFilterChip(
                    selected = selected,
                    onClick = {
                        vm.selectedWeekdays = if (selected) vm.selectedWeekdays - value
                        else vm.selectedWeekdays + value
                    },
                    label = name,
                )
            }
        }
    }
}

@Composable
private fun UnitSelector(vm: RoomFreeViewModel) {
    Column {
        Text("选择节次", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(ScnuSpacing.xs))
        // 快捷按钮
        val dayParts = vm.units.groupBy { it.dayPart }
        Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
            dayParts.forEach { (part, list) ->
                QuickAction(partName(part)) { vm.selectedUnits = list.map { it.value } }
            }
            QuickAction("全天") { vm.selectedUnits = vm.units.map { it.value } }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
        ) {
            vm.units.forEach { u ->
                val selected = u.value in vm.selectedUnits
                ScnuFilterChip(
                    selected = selected,
                    onClick = {
                        vm.selectedUnits = if (selected) vm.selectedUnits - u.value
                        else vm.selectedUnits + u.value
                    },
                    label = "${u.name} ${u.time}",
                )
            }
        }
    }
}

/** 快捷选择按钮：8dp 小圆角，与全 App 的按钮轮廓一致 */
@Composable
private fun QuickAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = ScnuSpacing.m, vertical = ScnuSpacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TimeRangeSelector(vm: RoomFreeViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.m)) {
        OutlinedTextField(
            value = vm.startTime,
            onValueChange = { vm.startTime = it },
            label = { Text("开始时间") },
            placeholder = { Text("HH:mm") },
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
            singleLine = true,
        )
        OutlinedTextField(
            value = vm.endTime,
            onValueChange = { vm.endTime = it },
            label = { Text("结束时间") },
            placeholder = { Text("HH:mm") },
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
            singleLine = true,
        )
    }
}

private fun partName(dayPart: String): String = when (dayPart) {
    "MORNING" -> "上午"
    "NOON" -> "中午"
    "AFTERNOON" -> "下午"
    "EVENING" -> "晚上"
    else -> dayPart
}

// ── 结果/状态面板 ────────────────────────────────────────────────

@Composable
private fun ResultList(rooms: List<FreeRoom>) {
    Column(verticalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
        rooms.forEach { room ->
            RoomCard(room)
        }
    }
}

@Composable
private fun RoomCard(room: FreeRoom) {
    val cs = MaterialTheme.colorScheme
    // 短编号（05101 / 09405）用大号数字做视觉锚点；
    // 长名称（综合实验楼303 / 微7507(微格)）用小一号字体 + 两行上限，
    // 否则固定宽度列会把名称挤成三行、卡片高度失控
    val compact = room.nameZh.length <= 6
    Surface(
        color = cs.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = ScnuSpacing.m, vertical = ScnuSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                room.nameZh,
                style = if (compact) NumMedium else MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(if (compact) 72.dp else 96.dp),
            )

            Spacer(Modifier.width(ScnuSpacing.m))

            Column(Modifier.weight(1f)) {
                Text(
                    "${room.buildingName} / ${room.campusName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(ScnuSpacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
                    MetaBadge("楼层 ${room.floor}")
                    MetaBadge("座位 ${room.seatsForLesson}")
                    if (room.roomTypeName.isNotEmpty()) MetaBadge(room.roomTypeName)
                }
                if (room.remark.isNotEmpty()) {
                    Spacer(Modifier.height(ScnuSpacing.xs))
                    Text(
                        room.remark,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaBadge(text: String) {
    ScnuStatusBadge(text, tone = ScnuBadgeTone.Neutral)
}

@Composable
private fun InitPanel(onRetry: () -> Unit, onWebAuth: (() -> Unit)? = null) {
    ScnuStatePanel(
        modifier = Modifier.fillMaxWidth(),
        title = "需要先登录课表系统",
        body = "空闲教室查询要用课表系统的数据。完成一次课表登录后，这里会自动加载校区与教室类型。",
        actionLabel = if (onWebAuth != null) "去完成课表登录" else null,
        onAction = onWebAuth,
        secondaryLabel = "重新加载",
        onSecondaryAction = onRetry,
    )
}

// ── FlowRow 使用 Compose 内置流式布局 ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
