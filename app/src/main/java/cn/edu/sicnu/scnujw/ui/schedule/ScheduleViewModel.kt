package cn.edu.sicnu.scnujw.ui.schedule

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.sicnu.scnujw.core.auth.AuthFeatureFlags
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import cn.edu.sicnu.scnujw.core.jwcxk.CourseTableApi
import cn.edu.sicnu.scnujw.core.jwcxk.JwcxkClient
import cn.edu.sicnu.scnujw.core.jwcxk.ParsedSchedule
import cn.edu.sicnu.scnujw.core.jwcxk.ScheduleParser
import cn.edu.sicnu.scnujw.core.net.ScheduleCache
import cn.edu.sicnu.scnujw.core.net.networkHint
import cn.edu.sicnu.scnujw.ui.common.DataIssue
import cn.edu.sicnu.scnujw.ui.common.dataIssueOf
import cn.edu.sicnu.scnujw.ui.common.dataIssueShort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class ScheduleViewModel : ViewModel() {
    var schedule by mutableStateOf<ParsedSchedule?>(null)
        private set
    var source by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 正在联网刷新：角标显示「更新中…」；命中当天快照直接返回时不会置位 */
    var refreshing by mutableStateOf(false)
        private set

    /** 最近一次联网刷新的结果：供角标「已更新」判定（null=本次未联网） */
    var lastRefreshOk by mutableStateOf<Boolean?>(null)
        private set

    /** 无课表数据时的致命错误分类（登录失效/无网/校园网/服务端） */
    var issue by mutableStateOf<DataIssue?>(null)
        private set

    /** 有缓存时的降级提示 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 已完成一次加载（命中磁盘快照也算）。界面据此「首次进入只拉一次」，切 tab 不再联网 */
    var loaded by mutableStateOf(false)
        private set

    var selectedWeek by mutableIntStateOf(1)
    var currentWeek by mutableIntStateOf(1)
        private set

    private var loadJob: Job? = null

    /**
     * 拉取课表。顺序：磁盘快照先上屏 → 跨 7 天才后台刷新 → 原生接口 → 渲染页兜底。
     *
     * 刷新策略（2026-10-27 起）：课表按**周**缓存，7 天内直接使用快照，
     * 不再每次重进/切 tab 都联网；手动刷新（force=true）不受限制。
     *
     * 并发保护：新一次加载取消旧的；失败时不清掉已经上屏的课表
     * （把「刷新失败」变成「清空页面」是最伤的一类回归）。
     */
    fun load(ctx: Context, force: Boolean = false) {
        loadJob?.cancel()
        error = null
        issue = null
        notice = null
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // 0) 磁盘快照先上屏：跨进程重启首帧就有课表（角标如实标「缓存 MM-dd HH:mm」）
            val cached = ScheduleCache.read(ctx)
            // 读文件期间可能已被新一次加载取消：取消后不再上屏/写 loaded，交给新任务
            if (!isActive) return@launch
            // 快照里的教学周：termStart 缺失时它是唯一可信的周次来源
            val cachedWeek = cached?.weekIndex?.takeIf { it in 1..30 }
            if (cached != null && schedule == null) {
                apply(
                    ParsedSchedule(cached.semesterLabel, cached.termStart, cached.sessions),
                    ScoreCache.dailyLabel(cached.fetchedAt),
                    cached.weekIndex,
                )
            }
            // 7 天内的快照且不是手动刷新：不再联网（冷启动/重进页面/同窗口切 tab 都走这里）
            if (!force && cached != null && !ScoreCache.needWeeklyRefresh(cached.fetchedAt)) {
                loaded = true
                return@launch
            }

            refreshing = true
            lastRefreshOk = null
            try {
                // 1) 原生 print-data：带精确周次，不依赖 WebView 渲染
                var failure: Exception? = null
                if (AuthFeatureFlags.NATIVE_SCHEDULE) {
                    val native = try {
                        CourseTableApi.fetch()
                    } catch (e: CancellationException) {
                        // 被新一次加载/离页取消：原样抛出，不能当成「原生课表失败」去走兜底或写状态
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("Schedule", "原生课表失败: ${e.javaClass.simpleName}: ${e.message}")
                        failure = e
                        null
                    }
                    if (native != null) {
                        // 取消可能发生在阻塞请求返回的瞬间：上屏/落盘前再确认一次
                        if (!isActive) return@launch
                        apply(
                            ParsedSchedule(native.semesterLabel, native.termStart, native.sessions),
                            "实时",
                            weekHint(native.termStart, native.weekIndex, cachedWeek),
                        )
                        ScheduleCache.write(ctx, native.semesterLabel, native.termStart, currentWeek, native.sessions)
                        error = null
                        issue = null
                        notice = null
                        loaded = true
                        lastRefreshOk = true
                        return@launch
                    }
                }
                if (!isActive) return@launch

                // 2) 兜底：渲染页 HTML（旧路径）
                try {
                    val html = JwcxkClient.fetchRenderedPage(JwcxkClient.ROUTE_COURSE_TABLE).html()
                    val parsed = ScheduleParser.parse(html)
                    if (!isActive) return@launch
                    apply(parsed, "实时", weekHint(parsed.termStart, null, cachedWeek))
                    ScheduleCache.write(ctx, parsed.semesterLabel, parsed.termStart, currentWeek, parsed.sessions)
                    error = null
                    issue = null
                    notice = null
                    loaded = true
                    lastRefreshOk = true
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("Schedule", "渲染页课表失败: ${e.javaClass.simpleName}: ${e.message}")
                    failure = e
                }

                // 取消后不要再写 error/loaded：否则离页或新一次加载会被上一次的失败结果覆盖
                if (!isActive) return@launch
                // 课表是个人日程，不做样本兜底——离线/会话失效时明确提示，
                // 避免把开发期抓取的课表当成使用者的数据展示；
                // 已有快照/旧数据上屏时只保留（source 仍是缓存时间），不把页面清空。
                if (schedule == null) {
                    val kind = dataIssueOf(failure)
                    issue = kind
                    source = ""
                    error = "课表服务暂时不可用。" + networkHint(failure ?: IllegalStateException("课表请求失败"))
                } else {
                    val kind = dataIssueOf(failure)
                    notice = "刷新失败：${dataIssueShort(kind)}，当前显示上次缓存"
                }
                lastRefreshOk = false
                loaded = true
            } finally {
                // 被新一次加载取消时不要复位：新任务会继续持有「更新中…」
                if (isActive) refreshing = false
            }
        }
    }

    /**
     * 网络返回的周次提示。termStart 缺失时网络周次不可靠：
     * 原生接口在「当前教学周」请求失败时会兜底成 1，直接用会把快照里正确的
     * 第 N 周退回第 1 周——此时优先沿用快照里的 weekIndex。
     */
    private fun weekHint(termStart: String?, netHint: Int?, cachedWeek: Int?): Int? =
        if (termStart.isNullOrBlank()) (cachedWeek ?: netHint) else netHint

    /**
     * 上屏。[weekHint] 是服务端返回的教学周；termStart 可用时优先按学期起始日现算
     * （快照可能是几天前抓的，缓存的 weekIndex 会过时）。
     */
    private fun apply(parsed: ParsedSchedule, src: String, weekHint: Int?) {
        val firstApply = schedule == null
        schedule = parsed
        source = src
        val week = weekFrom(parsed.termStart) ?: weekHint?.takeIf { it in 1..30 }
        if (week != null) currentWeek = week
        // 只在首次上屏时对齐本周；后续刷新保留用户翻到的周次
        if (firstApply) selectedWeek = currentWeek
    }

    private fun weekFrom(termStart: String?): Int? {
        if (termStart.isNullOrBlank()) return null
        return runCatching {
            val start = LocalDate.parse(termStart)
            (ChronoUnit.WEEKS.between(start, LocalDate.now()).toInt() + 1).takeIf { it in 1..30 }
        }.getOrNull()
    }

    fun prevWeek() { if (selectedWeek > 1) selectedWeek-- }
    fun nextWeek() { if (selectedWeek < 30) selectedWeek++ }
    fun thisWeek() { selectedWeek = currentWeek }
}
