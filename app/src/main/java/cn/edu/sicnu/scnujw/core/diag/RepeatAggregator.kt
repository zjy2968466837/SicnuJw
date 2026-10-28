package cn.edu.sicnu.scnujw.core.diag

/**
 * 重复事件聚合：同一 key 在窗口内的重复只计数，窗口结束后补一条「…（Ns 内重复 M 次）」。
 *
 * 纯内存状态机——不碰 Context、不碰文件、不碰线程，时间由调用方传入。
 * 独立成类的原因：开窗/结账/交错/淘汰全是时间相关分支，靠读代码推演太容易漏；
 * 时间外部化后才能用 JVM 单测把每种时间轴钉住（见 RepeatAggregatorTest）。
 *
 * 规则：
 * - 首见直接返回一条（单次失败不会因为聚合而丢失）；
 * - 窗口内重复只 `count++`，返回空；
 * - 窗口已过：先补上一段的汇总（M = count-1，首条自己已落过一行），再按首条落本次；
 * - 不同 key **各自独立计窗**，不因为换了 key 就结掉别人——两个端点交替失败时
 *   各自仍要在自己的窗口里计数，否则每次交错都会把对方结掉，噪声一点没少；
 * - 没有后续事件时由调用方主动结账（[flushExpired] 定时器 / [flushAll] 导出前），
 *   否则「失败一阵后不再失败」的最后一段突发永远等不到汇总。
 */
internal class RepeatAggregator(
    private val windowMs: Long,
    private val maxKeys: Int,
    private val maxDetailChars: Int,
) {
    /** 要落盘的一行；汇总行的 detail 已带「重复 M 次」后缀 */
    class Line(val type: String, val detail: String)

    /** 一个 key 的当前窗口。[count] 含首条，而首条自己已经落过一行。 */
    private class Bucket(
        val type: String,
        val detail: String,
        val firstAt: Long,
        var count: Int,
    )

    private val lock = Any()

    /** key → 窗口；插入序 = 首见序，超容量时先结账再删最旧 */
    private val buckets = LinkedHashMap<String, Bucket>()

    /**
     * 记一次事件，返回本次要落盘的行（0~2 行）；空列表 = 窗口内重复，只计数。
     * 调用方拿返回结果去排队写盘，本函数不持锁做 IO。
     */
    fun resolve(type: String, detail: String, key: String, now: Long): List<Line> =
        synchronized(lock) {
            val out = ArrayList<Line>(2)
            val cur = buckets[key]
            when {
                // 首见：照常落本次
                cur == null -> {
                    // 满了先结账再删最旧：计数不白丢，正常情况下窗口数远小于上限
                    while (buckets.size >= maxKeys) {
                        val oldest = buckets.keys.firstOrNull() ?: break
                        flush(oldest, out)
                        buckets.remove(oldest)
                    }
                    buckets[key] = Bucket(type, detail, now, 1)
                    out += Line(type, detail)
                }
                // 窗口内重复：不落新行
                now - cur.firstAt < windowMs -> cur.count++
                // 窗口已过：给上一段补汇总，本次开新窗口并照常落行
                else -> {
                    flush(key, out)
                    buckets[key] = Bucket(type, detail, now, 1)
                    out += Line(type, detail)
                }
            }
            out
        }

    /**
     * 结掉已过窗的窗口，返回要补的汇总行；未过窗的继续等（定时器兜底用：
     * 没有新事件时只有它能让最后一段突发补上汇总）。
     */
    fun flushExpired(now: Long): List<Line> = synchronized(lock) {
        val out = ArrayList<Line>(1)
        // 先收集 key 再删：直接遍历 map 时 flush 会 remove
        buckets.entries.filter { now - it.value.firstAt >= windowMs }
            .map { it.key }
            .forEach { flush(it, out) }
        out
    }

    /**
     * 全部结账（导出/清空前调用）：用户此刻看到的就是最后一轮突发的现场，
     * 不能因为「窗口还没到点」就没有汇总。count < 2 的窗口直接丢弃——首条自己就是全部信息。
     */
    fun flushAll(): List<Line> = synchronized(lock) {
        val out = ArrayList<Line>(buckets.size)
        buckets.keys.toList().forEach { flush(it, out) }
        out
    }

    /** 最早一个窗口的结束时刻；null = 没有待结账的窗口 */
    fun nextDueAt(): Long? = synchronized(lock) {
        buckets.values.minOfOrNull { it.firstAt + windowMs }
    }

    /** 清空（日志文件已删时调用：不能再补一条指向已删首条的汇总） */
    fun clear() {
        synchronized(lock) { buckets.clear() }
    }

    /** 结账并移除：计数 ≥2 才补汇总 */
    private fun flush(key: String, out: MutableList<Line>) {
        val b = buckets.remove(key) ?: return
        if (b.count < 2) return
        val suffix = "（${windowMs / 1000}s 内重复 ${b.count - 1} 次）"
        out += Line(
            b.type,
            b.detail.take((maxDetailChars - suffix.length).coerceAtLeast(0)) + suffix,
        )
    }
}
