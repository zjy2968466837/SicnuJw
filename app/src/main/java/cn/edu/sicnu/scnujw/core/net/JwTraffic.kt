package cn.edu.sicnu.scnujw.core.net

import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * 教务请求优先级协调。
 *
 * 背景：教务是 ASP.NET Session，同一会话的请求串行；平时成绩的全量/单学期回发
 * 会占住会话锁十几秒到两分钟。平时成绩因此被定义为**低优先级**：
 * 每个学期请求发出前，先等普通页面请求（课表/成绩/考试/进度）清空；
 * 普通请求不等待平时成绩，避免长回发把其他模块一起拖超时。
 *
 * 计数器只统计 [cn.edu.sicnu.scnujw.core.jw.JwClient] 里走 `execute()` 的普通请求；
 * AuthGuard 的直接探活调用不计入（它本身有独立超时与「临时失败不改状态」保护）。
 */
object JwTraffic {
    private val active = AtomicInteger(0)

    fun enter() {
        active.incrementAndGet()
    }

    fun exit() {
        active.decrementAndGet()
    }

    /**
     * 等到没有普通请求在跑（最多等 [maxWaitMs]），再静默 [quietMs] 让刚返回的页面
     * 有机会发起后续请求。超时也继续，避免低优先级任务被无限饿死。
     */
    suspend fun awaitIdle(maxWaitMs: Long = 4_000, quietMs: Long = 200) {
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (active.get() > 0 && SystemClock.elapsedRealtime() < deadline) {
            delay(100)
        }
        if (active.get() == 0) delay(quietMs)
    }
}
