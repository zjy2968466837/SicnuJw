package cn.edu.sicnu.scnujw.core.auth

import android.content.Context
import android.util.Log
import cn.edu.sicnu.scnujw.core.diag.Diag
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.core.zhxg.HolidayParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class SessionState { UNKNOWN, ALIVE, DEAD, NEED_MFA, NEED_PASSWORD, INTERACTIVE }

/**
 * 各下游系统的状态与最近验证时间；只保存本地事实，不发网络请求
 * （探活由 [SessionProbe] / [AuthGuard.ensure] 负责，结果回写到这里）。
 */
object SessionRegistry {
    private val states = ConcurrentHashMap<AuthSystem, SessionState>()
    private val verifiedAt = ConcurrentHashMap<AuthSystem, Long>()

    /**
     * 状态快照。ConcurrentHashMap 本身不是 Compose 的可观察状态：
     * 后台探活改状态时，直接读 map 的界面不会重组，所以每次变更发布一份快照供 collectAsState。
     */
    private val _statesFlow = MutableStateFlow<Map<AuthSystem, SessionState>>(emptyMap())
    val statesFlow: StateFlow<Map<AuthSystem, SessionState>> = _statesFlow.asStateFlow()

    @Volatile var interactive: AuthSystem? = null
        private set

    fun state(system: AuthSystem): SessionState = states[system] ?: SessionState.UNKNOWN

    fun verifiedAt(system: AuthSystem): Long = verifiedAt[system] ?: 0L

    fun markAlive(system: AuthSystem) {
        states[system] = SessionState.ALIVE
        verifiedAt[system] = System.currentTimeMillis()
        publish()
    }

    fun markDead(system: AuthSystem) {
        states[system] = SessionState.DEAD
        publish()
    }

    /**
     * 暂时性失败（网络中断、超时、服务端排队）不是会话失效。
     * 不改写已确认的状态：ALIVE 继续显示可用，UNKNOWN 继续显示检测中；
     * 只有明确的登录页/票据失效才允许走 [markDead] / [markNeedPassword]。
     */
    fun markTransientFailure(system: AuthSystem) {
        android.util.Log.w(
            "SessionRegistry",
            "${system.name} 暂时不可达（网络/超时），保留原状态 ${state(system)}",
        )
    }

    fun markNeedMfa(system: AuthSystem) {
        states[system] = SessionState.NEED_MFA
        publish()
    }

    fun markNeedPassword(system: AuthSystem) {
        states[system] = SessionState.NEED_PASSWORD
        publish()
    }

    /** 前台恢复：只把本地状态标脏，不探测、不铸票 */
    fun markStale() {
        states.keys.forEach { states[it] = SessionState.DEAD }
        publish()
    }

    fun beginInteractive(system: AuthSystem) {
        interactive = system
        states[system] = SessionState.INTERACTIVE
        publish()
    }

    fun endInteractive(system: AuthSystem) {
        if (interactive == system) interactive = null
        // 交互结束不猜结果：状态由 verifyAfterLogin / ensure 回写
        publish()
    }

    fun reset() {
        states.clear()
        verifiedAt.clear()
        interactive = null
        publish()
    }

    private fun publish() {
        _statesFlow.value = states.toMap()
    }
}

/**
 * 统一鉴权入口（`LOGIN_SSO_PLAN.md` Step 7）。
 *
 * 调用方只关心 `ensure` / `require`：
 *  - 有会话 → 放行；
 *  - 没会话 → 用 CAS 票据静默换票并落地复验；
 *  - CAS 要求 MFA/账密 → 抛 InteractiveAuthRequired，让 UI 弹一次 WebView；
 *  - 网络错误 / 超时 → 抛 TransientAuthException（不弹验证，下次再试）。
 */
object AuthGuard {
    private const val CAS = "https://authserver.sicnu.edu.cn"
    private const val ALIVE_WINDOW_MS = 120_000L
    private const val TICKET_TIMEOUT_MS = 20_000L

    /** 落地（≤12 跳）+ 复验（3 次探活）的总预算：全程持有该系统的 Mutex，必须有界 */
    private const val LANDING_TIMEOUT_MS = 45_000L

    enum class Reason { MFA, PASSWORD, IN_PROGRESS }

    class InteractiveAuthRequired(
        val system: AuthSystem,
        val reason: Reason,
        val url: String?,
        message: String,
    ) : Exception(message)

    class TransientAuthException(val system: AuthSystem, message: String) : Exception(message)

    /** 业务请求发现 302 error.aspx / 401 / 未登录信封时抛这个 */
    class AuthExpiredException(val system: AuthSystem, message: String) : Exception(message)

    /** 落地总预算用尽（单跳阻塞调用可能略微超时，故在循环内自检截止时间） */
    private class LandingTimeoutException(message: String) : Exception(message)

    private val mutexes = ConcurrentHashMap<AuthSystem, Mutex>()
    private lateinit var appContext: Context

    /**
     * 会话代数：本地会话被清空（退出登录）时递增。
     * 用来丢掉「退出前发起、退出后才落地」的静默换票结果——否则 establish 会在 logout 之后
     * 把 sid 写回去，进程里明明已登出却重新「变成已登录」，下次冷启动还会直接进课表。
     */
    @Volatile private var exchangeEpoch = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 退出登录前调用：让在途的静默换票落地作废（登出入口必须调一次） */
    fun onSessionCleared() {
        exchangeEpoch++
    }

    suspend fun ensure(system: AuthSystem) = withContext(Dispatchers.IO) {
        ensureInternal(system)
    }

    private suspend fun ensureInternal(system: AuthSystem) {
        if (!AuthFeatureFlags.SILENT_TICKET) {
            throw InteractiveAuthRequired(system, Reason.PASSWORD, CasTicketClient.loginUrl(system),
                "${system.label}需要重新验证")
        }
        if (SessionRegistry.interactive == system) {
            throw InteractiveAuthRequired(system, Reason.IN_PROGRESS, null, "验证进行中")
        }
        val age = System.currentTimeMillis() - SessionRegistry.verifiedAt(system)
        if (SessionRegistry.state(system) == SessionState.ALIVE && age in 0 until ALIVE_WINDOW_MS) return

        val mutex = mutexes.getOrPut(system) { Mutex() }
        mutex.withLock {
            // 等锁期间可能已经退出登录：这一轮作废，不要探测也不要写状态
            val epoch = exchangeEpoch
            val recheckAge = System.currentTimeMillis() - SessionRegistry.verifiedAt(system)
            if (SessionRegistry.state(system) == SessionState.ALIVE && recheckAge in 0 until ALIVE_WINDOW_MS) return

            if (probe(system)) {
                if (epoch != exchangeEpoch) return
                SessionRegistry.markAlive(system)
                refreshIdentity(system)
                return
            }

            val result = try {
                withTimeout(TICKET_TIMEOUT_MS) { CasTicketClient.requestTicket(system) }
            } catch (e: TimeoutCancellationException) {
                SessionRegistry.markTransientFailure(system)
                throw TransientAuthException(system, "换取 CAS 票据超时（${TICKET_TIMEOUT_MS / 1000}s）")
            }
            CasTicketClient.logTicket(system, result)
            when (result) {
                is TicketResult.Ticket -> {
                    val ok = try {
                        withTimeout(LANDING_TIMEOUT_MS) {
                            val deadline = System.currentTimeMillis() + LANDING_TIMEOUT_MS
                            establish(system, result.location, deadline, epoch)
                            verifyWithRetry(system, deadline)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w("AuthGuard", "${system.name} 落地超时（${LANDING_TIMEOUT_MS / 1000}s）")
                        SessionRegistry.markTransientFailure(system)
                        throw TransientAuthException(system, "${system.label}落地超时，请稍后重试")
                    } catch (e: CancellationException) {
                        // 调用方取消（切页销毁 scope、新一次加载取消旧任务）不是会话失效：
                        // 必须原样抛出，也不能在取消后再写 DEAD / 弹登录
                        throw e
                    } catch (e: IOException) {
                        // 网络中断：按「暂时失败」处理，不弹验证，下次再试
                        Log.w("AuthGuard", "${system.name} 落地网络中断: ${e.javaClass.simpleName}: ${e.message}")
                        SessionRegistry.markTransientFailure(system)
                        throw TransientAuthException(system, "${system.label}网络中断，请稍后重试")
                    } catch (e: LandingTimeoutException) {
                        Log.w("AuthGuard", "${system.name} 落地超出预算: ${e.message}")
                        SessionRegistry.markTransientFailure(system)
                        throw TransientAuthException(system, "${system.label}落地超时，请稍后重试")
                    } catch (e: Exception) {
                        Log.w("AuthGuard", "${system.name} 落地失败: ${e.javaClass.simpleName}: ${e.message}")
                        false
                    }
                    // 落地期间用户退出了登录：结果整体作废（establish 已跳过回写 sid，这里也不再改状态）
                    if (epoch != exchangeEpoch) {
                        Log.i("AuthGuard", "${system.name} 换票期间会话已清空，丢弃换票结果")
                        return
                    }
                    if (ok) {
                        SessionRegistry.markAlive(system)
                        refreshIdentity(system)
                        // 诊断事件：只记系统名与结果，不写账号/票据（脱敏在 Diag 内兜底）
                        Diag.recordEvent("auth", "${system.label} 静默换票成功")
                    } else {
                        // 换票后仍验不到不一定是会话失效（也可能是并发请求占住会话锁/网络抖动）：
                        // 不再直接把状态打成 DEAD 或弹账密，交给下一次探活重试。
                        SessionRegistry.markTransientFailure(system)
                        throw TransientAuthException(
                            system, "${system.label}换票后仍无法确认会话，请稍后重试"
                        )
                    }
                }

                is TicketResult.NeedMfa -> {
                    // 已有 WebView 在验证时不要覆盖它的 INTERACTIVE 状态
                    if (SessionRegistry.interactive != system) SessionRegistry.markNeedMfa(system)
                    throw InteractiveAuthRequired(system, Reason.MFA, result.url, "需要完成一次双因子验证")
                }

                is TicketResult.NeedPassword -> {
                    // 同上：不能让后台换票把「验证进行中」改写成「需登录」
                    if (SessionRegistry.interactive != system) SessionRegistry.markNeedPassword(system)
                    throw InteractiveAuthRequired(system, Reason.PASSWORD, result.url, "需要重新登录")
                }

                is TicketResult.Transient -> {
                    SessionRegistry.markTransientFailure(system)
                    throw TransientAuthException(system, result.message)
                }
            }
        }
    }

    /** 业务方法体重跑一次：第一次 AuthExpired 才换票，第二次仍失败就交给 UI */
    suspend fun <T> require(system: AuthSystem, block: suspend () -> T): T {
        ensure(system)
        return try {
            block()
        } catch (e: AuthExpiredException) {
            if (e.system != system) throw e
            SessionRegistry.markDead(system)
            ensure(system)
            try {
                block()
            } catch (e2: AuthExpiredException) {
                SessionRegistry.markDead(system)
                throw InteractiveAuthRequired(
                    system, Reason.PASSWORD, CasTicketClient.loginUrl(system), "会话失效且无法自动恢复"
                )
            }
        }
    }

    /** 业务请求失败时由客户端调用，标记该域需要重新建会话 */
    fun markDead(system: AuthSystem) = SessionRegistry.markDead(system)

    suspend fun probe(system: AuthSystem): Boolean = withContext(Dispatchers.IO) {
        when (system) {
            AuthSystem.ZF -> probeZf()
            AuthSystem.JWCXK -> probeJwcxk()
            AuthSystem.ZHGX -> probeZhxg()
        }
    }

    /** WebView 落地后调用：导出已完成，这里只做复验与身份回填 */
    suspend fun verifyAfterLogin(system: AuthSystem): Boolean = withContext(Dispatchers.IO) {
        val ok = verifyWithRetry(system)
        // 诊断事件：登录结果只有「成功/失败 + 系统名」，绝不写账号、密码、票据值
        Diag.recordEvent(
            "auth",
            "${system.label} 登录" + if (ok) "成功" else "失败（落地后复验未通过）",
        )
        if (ok) {
            SessionRegistry.markAlive(system)
            refreshIdentity(system)
        } else {
            SessionRegistry.markDead(system)
        }
        ok
    }

    fun onInteractiveFinished(system: AuthSystem) = SessionRegistry.endInteractive(system)
    fun onInteractiveStarted(system: AuthSystem) = SessionRegistry.beginInteractive(system)

    private suspend fun verifyWithRetry(
        system: AuthSystem,
        deadline: Long = System.currentTimeMillis() + LANDING_TIMEOUT_MS,
    ): Boolean {
        repeat(3) { attempt ->
            if (System.currentTimeMillis() > deadline) return false
            if (probe(system)) return true
            if (attempt < 2) delay(600)
        }
        return false
    }

    private fun probeZf(): Boolean {
        val sid = SessionManager.sid ?: return false
        return try {
            SessionManager.client.newCall(
                Request.Builder().url("http://202.115.194.60/Index.aspx?sid=$sid").build()
            ).execute().use { r ->
                if (r.code != 200) return false
                val body = r.body?.string().orEmpty()
                body.length > 5_000 && body.contains("MenuList")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeJwcxk(): Boolean = try {
        SessionManager.client.newCall(
            Request.Builder().url("https://jwcxk.sicnu.edu.cn/student/home")
                .header("User-Agent", CasTicketClient.UA).build()
        ).execute().use { it.code == 200 }
    } catch (_: Exception) {
        false
    }

    private fun probeZhxg(): Boolean = try {
        SessionManager.client.newCall(
            Request.Builder().url("https://zhxg.sicnu.edu.cn/edu/api/v1/user/getinfo")
                .header("User-Agent", CasTicketClient.UA).build()
        ).execute().use { r ->
            if (r.code != 200) return false
            HolidayParser.isLoggedIn(r.body?.string().orEmpty(), r.code)
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 跟随票据落地。手工跟 302（client 不自动跟随），以便：
     *  - 抓到正方 `/pjtj.ashx?...&xid=` 回填身份；
     *  - 在每个 hop 都由 CookieStore 收下 Set-Cookie。
     */
    private suspend fun establish(
        system: AuthSystem,
        ticketUrl: String,
        deadline: Long = System.currentTimeMillis() + LANDING_TIMEOUT_MS,
        epoch: Long = exchangeEpoch,
    ) {
        ChattyLog.i("AuthGuard", "${system.name} 换票落地 ${CasTicketClient.mask(ticketUrl)}")
        var url: String = ticketUrl
        var sid: String? = null
        var xid: String? = null
        for (hop in 0 until 12) {
            if (System.currentTimeMillis() > deadline) {
                throw LandingTimeoutException("${system.name} 第 ${hop + 1} 跳前已超出落地预算")
            }
            val resp = try {
                SessionManager.client.newCall(
                    Request.Builder().url(url).header("User-Agent", CasTicketClient.UA).build()
                ).execute()
            } catch (e: Exception) {
                Log.w("AuthGuard", "${system.name} 第 ${hop + 1} 跳失败: ${e.message}")
                throw e
            }
            val current = resp.request.url.toString()
            Log.i("AuthGuard", "${system.name} hop ${hop + 1}: ${resp.code} ${resp.request.url.host}${resp.request.url.encodedPath}")
            SID_PAT.find(current)?.let { sid = it.groupValues[1] }
            XID_PAT.find(current)?.let { xid = it.groupValues[1] }
            val location = resp.header("Location")
            resp.close()
            if (location == null) break
            url = runCatching { current.toHttpUrl().resolve(location)?.toString() }.getOrNull()
                ?: break
        }
        if (sid != null) {
            // 期间已退出登录：不要把 sid 写回去（否则 logout 之后进程会重新「变成已登录」）
            if (epoch != exchangeEpoch) {
                Log.i("AuthGuard", "${system.name} 落地完成时会话已清空，跳过 sid 回写")
                return
            }
            SessionManager.onLoginSuccess(sid!!)
            val code = xid
            if (!code.isNullOrBlank() && ::appContext.isInitialized) {
                AccountIdentityStore.save(
                    appContext,
                    AccountIdentity(code, "", "zf", System.currentTimeMillis())
                )
                // 缓存配置按学号归属：同学号复用，换号才清空
                cn.edu.sicnu.scnujw.core.jw.ScoreCache.onAccountKnown(appContext, code)
            }
        }
    }

    /** 换票后回填身份；只在能零成本拿到时做，不额外发请求 */
    private suspend fun refreshIdentity(system: AuthSystem) {
        if (!::appContext.isInitialized) return
        when (system) {
            AuthSystem.ZHGX -> {
                runCatching {
                    val (code, body) = withContext(Dispatchers.IO) {
                        SessionManager.client.newCall(
                            Request.Builder().url(
                                "https://zhxg.sicnu.edu.cn/edu/api/v1/user/getinfo"
                            ).header("User-Agent", CasTicketClient.UA).build()
                        ).execute().use { it.code to it.body?.string().orEmpty() }
                    }
                    if (code == 200) {
                        val (studentCode, name) = HolidayParser.parseUserInfo(body)
                        AccountIdentityStore.save(
                            appContext,
                            AccountIdentity(studentCode, name, "zhxg", System.currentTimeMillis())
                        )
                        cn.edu.sicnu.scnujw.core.jw.ScoreCache.onAccountKnown(appContext, studentCode, name)
                    }
                }
            }

            else -> Unit
        }
    }

    private val SID_PAT = Regex("[Ii]ndex\\.aspx\\?sid=([0-9A-Fa-f]+)")
    private val XID_PAT = Regex("[?&]xid=(\\d+)")

    private object ChattyLog {
        // establish 由每个系统各自的 Mutex 保护，不同系统可并发落地；去重集合必须线程安全
        private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun i(tag: String, msg: String) {
            if (seen.add(msg)) Log.i(tag, msg)
        }
    }
}
