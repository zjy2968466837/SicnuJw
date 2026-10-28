package cn.edu.sicnu.scnujw.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复事件聚合口径：首条照常落盘、窗口内只计数、窗口结束补汇总、不同 key 互不结账。
 *
 * 背景：日志里 11 条一模一样的 SocketException 会淹没其它记录，但次数本身是
 * 「服务端抖一下」还是「每次都失败」的判断依据，所以只做「首条 + 汇总」而不是完全去重。
 * 这些用例锁住三件容易改坏的事：
 *  1) 窗口内不落新行，N = 首条之后被抑制的次数（首条已单独落行）；
 *  2) 最后一段突发也要有汇总——导出前 flushAll / 定时器 flushExpired 都能结账；
 *  3) 两个端点交替失败时各自在自己的 60s 窗口里计数（换 key 不结账）。
 */
class RepeatAggregatorTest {

    private val window = 60_000L

    private fun agg(maxKeys: Int = 32, maxDetailChars: Int = 2_000) =
        RepeatAggregator(window, maxKeys, maxDetailChars)

    @Test
    fun `首条照常落盘，窗口内重复只计数`() {
        val a = agg()
        val first = a.resolve("net", "Socket closed", "k", 0L)
        assertEquals(1, first.size)
        assertEquals("Socket closed", first[0].detail)
        // 窗口内（含最后一毫秒）全是重复：不落新行
        assertTrue(a.resolve("net", "Socket closed", "k", 30_000L).isEmpty())
        assertTrue(a.resolve("net", "Socket closed", "k", window - 1).isEmpty())
    }

    @Test
    fun `最后一段突发也能在导出前结账，N 是首条后被抑制的次数`() {
        val a = agg()
        a.resolve("net", "Socket closed", "k", 0L)
        // 11 次相同失败 = 首条 + 10 次被抑制
        for (i in 1..10) assertTrue(a.resolve("net", "Socket closed", "k", i * 1_000L).isEmpty())
        val lines = a.flushAll()
        assertEquals(1, lines.size)
        assertEquals("Socket closed（60s 内重复 10 次）", lines[0].detail)
        // 结账后窗口清空：再导出不会重复补
        assertTrue(a.flushAll().isEmpty())
        assertNull(a.nextDueAt())
    }

    @Test
    fun `窗口到点后的下一次同 key 事件先补汇总再落新首条`() {
        val a = agg()
        a.resolve("net", "Socket closed", "k", 0L)
        a.resolve("net", "Socket closed", "k", 1_000L)
        val lines = a.resolve("net", "Socket closed", "k", window)
        assertEquals(2, lines.size)
        // 顺序不能反：先解释前面那段，再落本次
        assertEquals("Socket closed（60s 内重复 1 次）", lines[0].detail)
        assertEquals("Socket closed", lines[1].detail)
    }

    @Test
    fun `单个事件不产生汇总`() {
        val a = agg()
        a.resolve("net", "timeout", "k", 0L)
        assertTrue(a.flushAll().isEmpty())
        // 定时器路径同样不补
        assertTrue(a.flushExpired(window * 2).isEmpty())
    }

    @Test
    fun `不同 key 交错失败时各自独立计窗，不互相结账`() {
        val a = agg()
        val k1 = "net:host/a:SocketException"
        val k2 = "net:host/b:SocketTimeoutException"
        assertEquals(1, a.resolve("net", "A", k1, 0L).size)
        assertEquals(1, a.resolve("net", "B", k2, 1_000L).size)
        // 回到 k1：仍在 k1 自己的窗口内 → 只计数（修复前这里会先把 k1 结账再重新落首条）
        assertTrue(a.resolve("net", "A", k1, 2_000L).isEmpty())
        assertTrue(a.resolve("net", "B", k2, 3_000L).isEmpty())
        val lines = a.flushAll().associateBy { it.detail.substringBefore("（") }
        assertEquals(2, lines.size)
        assertEquals("A（60s 内重复 1 次）", lines["A"]?.detail)
        assertEquals("B（60s 内重复 1 次）", lines["B"]?.detail)
    }

    @Test
    fun `定时结账只结已过窗的窗口，未过窗的继续等`() {
        val a = agg()
        a.resolve("net", "A", "k1", 0L)
        a.resolve("net", "A", "k1", 1_000L)
        a.resolve("net", "B", "k2", 30_000L)   // k2 的窗口到 90s 才结束
        val due = a.flushExpired(window)
        assertEquals(1, due.size)
        assertEquals("A（60s 内重复 1 次）", due[0].detail)
        assertEquals(window + 30_000L, a.nextDueAt())
        // k2 只有一条：到点后也补不出汇总
        assertTrue(a.flushExpired(window + 30_000L).isEmpty())
    }

    @Test
    fun `nextDueAt 取最早结束的窗口`() {
        val a = agg()
        assertNull(a.nextDueAt())
        a.resolve("net", "A", "k1", 5_000L)
        a.resolve("net", "B", "k2", 1_000L)
        assertEquals(1_000L + window, a.nextDueAt())
    }

    @Test
    fun `表满时先结账最旧再淘汰，计数不白丢`() {
        val a = agg(maxKeys = 2)
        a.resolve("net", "A", "k1", 0L)
        a.resolve("net", "A", "k1", 1_000L)   // k1 计数 2
        a.resolve("net", "B", "k2", 2_000L)
        val lines = a.resolve("net", "C", "k3", 3_000L)
        // 淘汰 k1 前把它结账了，k3 首条照常落
        assertEquals(2, lines.size)
        assertEquals("A（60s 内重复 1 次）", lines[0].detail)
        assertEquals("C", lines[1].detail)
        // 剩下 k2（count=1，无汇总）与 k3（count=1，无汇总）：都没有可补的汇总
        assertTrue(a.flushAll().isEmpty())
    }

    @Test
    fun `clear 丢弃计数，之后不再补汇总`() {
        val a = agg()
        a.resolve("net", "A", "k", 0L)
        a.resolve("net", "A", "k", 1_000L)
        a.clear()
        assertTrue(a.flushAll().isEmpty())
        assertNull(a.nextDueAt())
    }

    @Test
    fun `汇总行总长不超过上限，后缀完整保留`() {
        val a = agg(maxDetailChars = 20)
        a.resolve("net", "12345678901234567890", "k", 0L)
        a.resolve("net", "12345678901234567890", "k", 1_000L)
        val line = a.flushAll().single()
        assertTrue(line.detail.length <= 20)
        assertTrue(line.detail.endsWith("（60s 内重复 1 次）"))
    }
}
