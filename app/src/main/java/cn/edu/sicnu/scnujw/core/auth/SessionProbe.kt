package cn.edu.sicnu.scnujw.core.auth

import android.content.Context
import android.util.Log
import cn.edu.sicnu.scnujw.core.net.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台探活（冷启动 + 「我的」页发现未探过的系统时）。
 *
 * 目的：只解决「进程刚起来时三个系统全是 UNKNOWN、界面上却把 UNKNOWN 显示成未验证」这个
 * 观感问题——后台把真实状态探一遍写进 [SessionRegistry]，让「我的 → 登录状态」如实显示。
 *
 * 约束：
 *  - 没有可用凭据时直接返回（登录页/首次启动零网络）；
 *  - 同一时刻只跑一轮（[running] 去重），但**不再是一次性开关**：一轮结束后可以再探，
 *    否则退出登录 → 重新登录同一进程时，UNKNOWN 永远停在「检测中…」而实际没有探测在跑；
 *  - [KICK_COOLDOWN_MS] 冷却只用来吸收界面重组/连续 kick 的重复排队；
 *    探活因会话清空而中止时立即解除冷却，重登后能马上再探；
 *  - 只在后台 [Dispatchers.IO] 跑，不阻塞首帧；
 *  - 只调 [AuthGuard.ensure]：需要人工验证（MFA/账密）时把状态落地后就此打住，
 *    绝不弹窗、绝不导航——交互式验证仍然只由用户点按钮发起。
 */
object SessionProbe {
    private const val TAG = "SessionProbe"

    /** 相邻两次 kick 的最小间隔（仅防重复排队；不是「只探一次」） */
    private const val KICK_COOLDOWN_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一轮探活是否在跑：只挡并发，不挡下一轮 */
    private val running = AtomicBoolean(false)

    /** 最近一次 kick 的时间（0 = 无冷却，可立即探） */
    private val lastKickAt = AtomicLong(0L)

    /** 下游系统的 Cookie 域名：webauth-only 登录不放 sid/CASTGC，只落这些域的会话 */
    private val DOWNSTREAM_DOMAINS = setOf("jwcxk.sicnu.edu.cn", "zhxg.sicnu.edu.cn")

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasCredential()) {
            // 无凭据不占用冷却：退出登录后重新登录要能立刻再探
            lastKickAt.set(0L)
            return
        }
        if (running.get()) return
        val now = System.currentTimeMillis()
        val last = lastKickAt.get()
        if (last != 0L && now - last < KICK_COOLDOWN_MS) return
        // CAS 原子推进时间戳：并发的两次 kick 只有一次能排进一轮
        if (!lastKickAt.compareAndSet(last, now)) return
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                // 顺序与 AuthSystem 声明一致（ZF → JWCXK → ZHGX）：先探启动前台的课表链路所需系统，
                // 让进入课表页时的 ensure 尽量命中 ALIVE 窗口，不再重复换票。
                AuthSystem.entries.forEach { system ->
                    // 探活途中退出登录：凭据已清空，剩下的系统不必再探（也不能写回状态）
                    if (!hasCredential()) {
                        Log.i(TAG, "会话已清空（退出登录），停止探活")
                        // 这一轮是被登出打断的：解除冷却，重登同一进程后能立刻重新探活
                        lastKickAt.set(0L)
                        return@launch
                    }
                    probeOne(system)
                    Log.i(TAG, "${system.name} → ${SessionRegistry.state(system)}")
                }
                Log.i(TAG, "后台探活结束（app=${app.packageName}）")
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * 是否有可探的凭据：sid、CASTGC，或 CookieStore 里 jwcxk/zhxg 域的会话 Cookie。
     * webauth（只建立下游会话）登录不会写 sid/CASTGC，只看前两者会把它当成「完全没有凭据」，
     * 界面上的 UNKNOWN 就会一直显示「检测中…」却没人探测。
     * 「我的」页的状态文案也用这个口径（[hasCredential]），保证「检测中…」只在真的会探测时出现。
     */
    fun hasCredential(): Boolean {
        if (SessionManager.sid != null) return true
        if (!CasSession.castgc().isNullOrBlank()) return true
        val now = System.currentTimeMillis()
        return SessionManager.cookieStore.snapshot().any { r ->
            r.expiresAt > now && r.value.isNotBlank() &&
                r.domain.removePrefix(".").lowercase() in DOWNSTREAM_DOMAINS
        }
    }

    private suspend fun probeOne(system: AuthSystem) {
        try {
            // ensure 内部已经负责 probe → 换票 → 落地 → markAlive/markDead
            AuthGuard.ensure(system)
        } catch (e: AuthGuard.InteractiveAuthRequired) {
            // 需要人工验证：只落状态，不弹窗（弹窗由用户点「验证」触发）
            when (e.reason) {
                AuthGuard.Reason.MFA -> markNeedMfa(system)
                AuthGuard.Reason.PASSWORD -> markNeedPassword(system)
                // 已有 WebView 正在验证：不要覆盖它的 INTERACTIVE 状态
                AuthGuard.Reason.IN_PROGRESS -> Unit
            }
            Log.i(TAG, "${system.name} 需要交互式验证: ${e.reason}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthGuard.TransientAuthException) {
            // 网络/超时只代表「这次没探到」，不是会话失效：保留上次确认的状态。
            SessionRegistry.markTransientFailure(system)
            Log.w(TAG, "${system.name} 探活暂时失败（保留原状态）: ${e.message}")
        } catch (e: Exception) {
            // 探测异常同样不写 DEAD：真正的失效会由 probe=false + 票据结果落到 NEED_PASSWORD。
            SessionRegistry.markTransientFailure(system)
            Log.w(TAG, "${system.name} 探活异常（保留原状态）: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // 后台探活也不得覆盖正在进行的 WebView 验证（与 AuthGuard 内 NeedMfa/NeedPassword 同口径）
    private fun markNeedMfa(system: AuthSystem) {
        if (SessionRegistry.interactive != system) SessionRegistry.markNeedMfa(system)
    }

    private fun markNeedPassword(system: AuthSystem) {
        if (SessionRegistry.interactive != system) SessionRegistry.markNeedPassword(system)
    }
}
