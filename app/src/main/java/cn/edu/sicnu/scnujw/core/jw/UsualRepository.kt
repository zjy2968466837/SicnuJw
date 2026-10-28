package cn.edu.sicnu.scnujw.core.jw

import android.content.Context
import android.os.SystemClock
import android.util.Log
import cn.edu.sicnu.scnujw.core.diag.Diag
import cn.edu.sicnu.scnujw.core.net.JwTraffic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document

/**
 * 一次平时成绩同步的进度快照。[page] 是「当前已加载学期」的合并视图，可能不完整。
 */
data class UsualProgress(
    val page: UsualScorePage,
    val latestFetchedAt: Long,
    val loadedTerms: Set<String>,
    val targetTerms: Set<String>,
    val failedTerms: Set<String>,
    val fromNetwork: Boolean,
    val error: Exception? = null,
) {
    val pendingTerms: Int get() = (targetTerms - loadedTerms).size
    val complete: Boolean get() = targetTerms.isNotEmpty() && targetTerms.all { it in loadedTerms }
}

/**
 * 平时成绩同步入口（2026-10-27 起：按学期增量，不再一次 `-1` 全量）。
 *
 * 为什么不再一次拉全部学期：全量 `-1` 回发实测 112s（89 门课），贴在 ASP.NET
 * executionTimeout（默认 110s）与客户端 180s readTimeout 边缘；失败即丢掉整次结果，
 * 且全程占住 ASP.NET 会话锁。改为只拉「有活动」的 7 个学期，每个约 20s：
 *  - 首屏 GET 默认学期，几秒内可见；
 *  - 其余学期按学期码倒序逐个回发（复用上一页 VIEWSTATE），每完成一个就上屏并落盘；
 *  - 每个学期独立成败；失败只缺该学期，下次手动刷新继续补；
 *    （进入页面不再自动联网：有缓存即缓存态，见 [readCache]）
 *  - 离页取消只丢当前学期，已完成学期保留（分学期缓存续传）；
 *  - 全部目标学期齐全后才写 `usual_all.json` 聚合快照，部分结果绝不伪装成全量。
 *
 * 低优先级：每次请求前等普通页面请求清空（[JwTraffic]），尽量不让长回发拖住
 * 成绩/考试/进度；普通请求不等平时成绩。
 */
object UsualRepository {
    private const val TAG = "UsualRepo"
    private const val FAIL_COOLDOWN_MS = 60_000L
    private const val TERM_GAP_MS = 250L

    private val mutex = Mutex()

    @Volatile private var lastFailureAt = 0L

    suspend fun sync(
        ctx: Context,
        force: Boolean = false,
        beforeWrite: () -> Boolean = { true },
        onProgress: (UsualProgress) -> Unit = {},
    ): UsualProgress? = mutex.withLock {
        val grade = ScoreCache.enrollmentYear(ctx)
        var cached = ScoreCache.readAllUsualTerms(ctx)
        val aggregate = ScoreCache.readUsualAll(ctx)
        // 升级迁移：旧的全量快照拆成分学期文件，避免首次进入重拉 7 个学期
        if (cached.isEmpty() && aggregate != null) {
            seedFromAggregate(ctx, aggregate)
            cached = ScoreCache.readAllUsualTerms(ctx)
        }

        val scoreTerms = ScoreCache.readElectScores(ctx)?.data
            ?.mapNotNull { row -> row.term.takeIf { it.isNotBlank() } }
            ?.toSet().orEmpty()
        // 学期下拉必须以「完整 map」为准：优先分学期文件（每次回发都带完整下拉），
        // 再补聚合文件。聚合文件的 map 曾经只保留已加载学期，不能单独作为学期全集。
        var termMap: Map<String, String> = buildMap {
            cached.values.forEach { putAll(it.data.termMap) }
            aggregate?.data?.termMap?.forEach { (k, v) -> putIfAbsent(k, v) }
        }
        var currentTerm: String? = null
        var doc: Document? = null
        var fromNetwork = false
        var lastError: Exception? = null

        // 没有任何学期信息：先 GET 默认学期页（几秒），顺便拿学期下拉
        if (termMap.isEmpty()) {
            if (!beforeWrite()) return@withLock null
            JwTraffic.awaitIdle()
            try {
                doc = JwClient.getUsualPage()
                val page = UsualScoreParser.parse(doc.html())
                if (!UsualScoreParser.hasUsualPage(doc.html())) {
                    // 解析事件：页面路径 + 结论，不记 HTML（HTML 由 JwClient 的 http 记录负责）
                    Diag.recordEvent(
                        "parse",
                        "${JwClient.USUAL_SCORE} 首个学期页结构异常：找不到学期下拉与课程块",
                    )
                    throw IllegalStateException("平时成绩页结构异常（找不到学期下拉与课程块）")
                }
                termMap = page.termMap
                currentTerm = page.selectedTerm.takeIf { it.isNotBlank() && it != "-1" }
                if (currentTerm != null) {
                    if (!beforeWrite()) return@withLock null
                    ScoreCache.writeUsualTerm(ctx, currentTerm, page.copy(selectedTerm = currentTerm))
                    cached = ScoreCache.readAllUsualTerms(ctx)
                    fromNetwork = true
                    Log.i(TAG, "学期 $currentTerm（首屏）完成：${page.courses.size} 门")
                    val targets = targetsOf(termMap, grade, scoreTerms, cached.keys, currentTerm)
                    onProgress(progressOf(cached, targets, emptySet(), true, null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailureAt = SystemClock.elapsedRealtime()
                lastError = e
                Log.w(TAG, "首屏 GET 失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (termMap.isEmpty()) {
            val fallback = merge(cached, emptySet(), emptyMap(), currentTerm)
            // 这里绝不能把 lastError 换成 null 返回：没有缓存且首屏 GET 失败时，null 会让
            // ViewModel 只能硬编码 DataIssue.SERVER —— AuthExpiredException 被降级成 SERVER，
            // 「去验证」入口永远不可达（2026-10-28 复核确认）。
            // 只有「连错误都没有」（页面有课程块却没有学期下拉，页面结构异常但未抛异常）时
            // 才保持旧的 null 口径，避免把结构异常误显示成「本学期暂无平时成绩」。
            if (fallback == null && lastError == null) return@withLock null
            return@withLock UsualProgress(
                page = fallback?.page ?: UsualScorePage(emptyList(), emptyMap(), "-1"),
                latestFetchedAt = fallback?.latest ?: System.currentTimeMillis(),
                loadedTerms = cached.keys,
                targetTerms = emptySet(),
                failedTerms = emptySet(),
                fromNetwork = false,
                error = lastError,
            )
        }

        val targets = targetsOf(termMap, grade, scoreTerms, cached.keys, currentTerm)
        if (targets.isEmpty()) {
            val fallback = merge(cached, targets, termMap, currentTerm)
                ?: return@withLock null
            return@withLock UsualProgress(
                page = fallback.page,
                latestFetchedAt = fallback.latest,
                loadedTerms = cached.keys,
                targetTerms = emptySet(),
                failedTerms = emptySet(),
                fromNetwork = fromNetwork,
                error = lastError,
            )
        }

        // 失败冷却：刚失败且已有缓存时，非手动刷新不立刻重打（手动 force 不受限）
        val now = SystemClock.elapsedRealtime()
        if (!force && cached.isNotEmpty() && lastFailureAt != 0L &&
            now - lastFailureAt in 0 until FAIL_COOLDOWN_MS
        ) {
            val merged = merge(cached, targets, termMap, currentTerm) ?: return@withLock null
            return@withLock UsualProgress(
                page = merged.page,
                latestFetchedAt = merged.latest,
                loadedTerms = cached.keys,
                targetTerms = targets,
                failedTerms = emptySet(),
                fromNetwork = false,
            )
        }

        // 缺失 / 跨天 / 手动强制 → 需要拉取；按学期码倒序，最新学期优先
        val toFetch = targets.filter { term ->
            val c = cached[term]
            c == null || force || !ScoreCache.isToday(c.fetchedAt)
        }.sortedDescending()

        val failed = LinkedHashSet<String>()
        var docForNext = doc
        for (term in toFetch) {
            if (!beforeWrite()) return@withLock null
            JwTraffic.awaitIdle()
            try {
                if (docForNext == null) docForNext = JwClient.getUsualPage()
                val next = JwClient.postUsualTerm(docForNext, term)
                val page = UsualScoreParser.parse(next.html())
                if (page.selectedTerm != term) {
                    Diag.recordEvent(
                        "parse",
                        "${JwClient.USUAL_SCORE} 回发学期不符：返回=" +
                            "${page.selectedTerm.ifBlank { "无" }} 期望=$term",
                    )
                    throw IllegalStateException(
                        "返回页学期=${page.selectedTerm.ifBlank { "无" }}，期望=$term"
                    )
                }
                if (!UsualScoreParser.hasUsualPage(next.html())) {
                    Diag.recordEvent("parse", "${JwClient.USUAL_SCORE} 学期 $term 页面结构异常")
                    throw IllegalStateException("学期 $term 页面结构异常")
                }
                if (!beforeWrite()) return@withLock null
                ScoreCache.writeUsualTerm(ctx, term, page.copy(selectedTerm = term))
                docForNext = next
                cached = ScoreCache.readAllUsualTerms(ctx)
                fromNetwork = true
                Log.i(TAG, "学期 $term 完成：${page.courses.size} 门（${cached.size}/${targets.size}）")
                onProgress(progressOf(cached, targets, failed, true, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += term
                lastError = e
                Log.w(TAG, "学期 $term 拉取失败: ${e.javaClass.simpleName}: ${e.message}")
                // 会话/页面状态未知：下一个学期重新 GET，避免拿旧 VIEWSTATE 继续回发
                docForNext = null
                onProgress(progressOf(cached, targets, failed, fromNetwork, e))
            }
            delay(TERM_GAP_MS)
        }

        // 同上：所有目标学期都失败且没有任何旧缓存时，merge 会返回 null；这里也不能用 null
        // 把 lastError 的分类吞掉（否则同样只能落到硬编码 SERVER）
        val merged = merge(cached, targets, termMap, currentTerm)
        if (merged == null) {
            return@withLock UsualProgress(
                page = UsualScorePage(emptyList(), emptyMap(), "-1"),
                latestFetchedAt = System.currentTimeMillis(),
                loadedTerms = cached.keys,
                targetTerms = targets,
                failedTerms = failed,
                fromNetwork = fromNetwork,
                error = lastError,
            )
        }
        val allLoaded = targets.all { it in cached.keys }
        if (allLoaded && beforeWrite()) {
            // 全部学期齐全才写聚合；部分结果只留在分学期文件里
            ScoreCache.writeUsualAll(ctx, merged.page.copy(selectedTerm = "-1"), merged.latest)
        }
        lastFailureAt = if (failed.isEmpty()) 0L else SystemClock.elapsedRealtime()
        val result = UsualProgress(
            page = merged.page,
            latestFetchedAt = merged.latest,
            loadedTerms = cached.keys,
            targetTerms = targets,
            failedTerms = failed,
            fromNetwork = fromNetwork,
            error = lastError.takeIf { failed.isNotEmpty() },
        )
        onProgress(result)
        result
    }

    /**
     * 纯磁盘缓存读取：页面进入时用它把「缓存态」立刻上屏，不联网。
     *
     * ★ 不可回退的规则（2026-10-28 约定）：
     *   有缓存 = 缓存态；进入页面不自动联网，只有「完全无缓存」的初次加载或用户点数据源
     *   角标的手动刷新（force=true）才允许调用 [sync]。别无脑在这里或 ViewModel 里补上
     *   自动 sync：设备没有校园网时那会让整页先卡在「正在加载平时成绩」很久。
     *
     * 约束：不 suspend、不拿 [mutex]、不碰 [JwTraffic] / JwClient / postUsualTerm；只读
     * ScoreCache 的本地文件（阻塞 IO，调用方必须在 IO 线程）。读取口径与 [sync] 一致，
     * 保证缓存上屏的页面结构与联网结果同构。
     *
     * 唯一的写操作是旧全量快照的一次性迁移（[seedFromAggregate]，只写本地文件），
     * 与 [sync] 的迁移口径相同——否则只有聚合快照的升级用户会被误判成「无缓存」而联网。
     *
     * 返回的 [UsualProgress] 恒为 `fromNetwork = false`、`failedTerms` 为空：本次没有任何请求，
     * 不存在「失败学期」。targetTerms 仍按 [targetsOf] 算，界面可在手动刷新时显示补齐进度。
     *
     * @return 完全没有缓存（分学期文件与聚合快照都没有）时返回 null
     */
    fun readCache(ctx: Context): UsualProgress? {
        val grade = ScoreCache.enrollmentYear(ctx)
        var cached = ScoreCache.readAllUsualTerms(ctx)
        val aggregate = ScoreCache.readUsualAll(ctx)
        // 与 sync 同一迁移口径：只有聚合快照时先拆成分学期文件
        if (cached.isEmpty() && aggregate != null) {
            seedFromAggregate(ctx, aggregate)
            cached = ScoreCache.readAllUsualTerms(ctx)
        }

        val scoreTerms = ScoreCache.readElectScores(ctx)?.data
            ?.mapNotNull { row -> row.term.takeIf { it.isNotBlank() } }
            ?.toSet().orEmpty()
        // 学期下拉以「完整 map」为准：先合并所有分学期文件，再用聚合补缺（同 sync）
        val termMap: Map<String, String> = buildMap {
            cached.values.forEach { putAll(it.data.termMap) }
            aggregate?.data?.termMap?.forEach { (k, v) -> putIfAbsent(k, v) }
        }

        val targets = targetsOf(termMap, grade, scoreTerms, cached.keys, null)
        val merged = merge(cached, targets, termMap, null)
        if (merged != null) {
            return UsualProgress(
                page = merged.page,
                latestFetchedAt = merged.latest,
                loadedTerms = cached.keys,
                targetTerms = targets,
                failedTerms = emptySet(),
                fromNetwork = false,
            )
        }

        // merged 为空但聚合文件还在（例如聚合里课程为空、课程 term 全为空白）：
        // 用聚合页兜底，仍按缓存态返回 —— 不能把「有缓存」误判成「无缓存」去联网。
        val agg = aggregate ?: return null
        return UsualProgress(
            page = agg.data.copy(selectedTerm = "-1"),
            latestFetchedAt = agg.fetchedAt,
            loadedTerms = cached.keys,
            targetTerms = targets,
            failedTerms = emptySet(),
            fromNetwork = false,
        )
    }

    /** 旧全量快照拆成分学期文件（只写有课程的学期；空学期下次按需补） */
    private fun seedFromAggregate(ctx: Context, agg: Cached<UsualScorePage>) {
        agg.data.courses.groupBy { it.term }.forEach { (term, courses) ->
            if (term.isBlank()) return@forEach
            val page = UsualScorePage(courses, agg.data.termMap, term)
            ScoreCache.writeUsualTerm(ctx, term, page, agg.fetchedAt)
        }
    }

    /**
     * 目标学期：入学年份裁剪后的完整学期列表（含当前学期）。
     *
     * 不能只用「成绩记录里的学期」：当前学期（如 26271）往往还没有期末成绩，
     * 但平时成绩已经录入；只看成绩记录会漏掉它。用 `filterTerms` 按入学年份裁剪，
     * 并保留成绩记录/缓存/当前学期（recordTerms）；年份未知时最多取最近 8 个，
     * 避免打 30+ 个空学期。
     */
    private fun targetsOf(
        termMap: Map<String, String>,
        grade: Int?,
        scoreTerms: Set<String>,
        cachedTerms: Set<String>,
        currentTerm: String?,
    ): Set<String> {
        val recordTerms = scoreTerms + cachedTerms + listOfNotNull(currentTerm)
        val filtered = ScoreParser.filterTerms(termMap, grade, recordTerms).keys
        val merged = (filtered + recordTerms).filter { it.isNotBlank() }.toSet()
        return if (grade == null) {
            merged.sortedDescending().take(8).toSet()
        } else {
            merged
        }
    }

    private data class Merged(
        val page: UsualScorePage,
        val latest: Long,
    )

    private fun merge(
        cached: Map<String, Cached<UsualScorePage>>,
        targets: Set<String>,
        termMap: Map<String, String>,
        currentTerm: String?,
    ): Merged? {
        if (cached.isEmpty()) return null
        val terms = targets + cached.keys
        val courses = cached.entries.filter { it.key in terms }.flatMap { it.value.data.courses }
        // 保留完整学期下拉：聚合文件下一次可能被当作学期全集来源，不能只存已加载学期
        val map = LinkedHashMap<String, String>(termMap)
        cached.values.forEach { c ->
            c.data.termMap.forEach { (k, v) -> map.putIfAbsent(k, v) }
        }
        currentTerm?.takeIf { !map.containsKey(it) }?.let { map[it] = it }
        val latest = cached.values.maxOfOrNull { it.fetchedAt } ?: System.currentTimeMillis()
        return Merged(UsualScorePage(courses, map, "-1"), latest)
    }

    private fun progressOf(
        cached: Map<String, Cached<UsualScorePage>>,
        targets: Set<String>,
        failed: Set<String>,
        fromNetwork: Boolean,
        error: Exception?,
    ): UsualProgress {
        val merged = merge(cached, targets, emptyMap(), null)
        return UsualProgress(
            page = merged?.page ?: UsualScorePage(emptyList(), emptyMap(), "-1"),
            latestFetchedAt = merged?.latest ?: System.currentTimeMillis(),
            loadedTerms = cached.keys,
            targetTerms = targets,
            failedTerms = failed,
            fromNetwork = fromNetwork,
            error = error,
        )
    }
}
