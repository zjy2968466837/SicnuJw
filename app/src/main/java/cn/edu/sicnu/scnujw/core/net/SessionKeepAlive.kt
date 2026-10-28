package cn.edu.sicnu.scnujw.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * 会话保活：教务会话空闲约 30 分钟即失效（2026-10-25 实测：两次会话分别
 * 31/30 分钟死亡，死亡特征 = Index.aspx 302 → error.aspx）。
 * 策略：前台存活期间每 20 分钟静默探测一次 Index.aspx，
 * 既续时又探测；探测发现失效时置 state=Dead，由 UI 层提示重新登录。
 *
 * 注意：探测必须与登录会话同源（本 APP OkHttp 与 WebView 共享 cookie，
 * 出口一致视为同一"计算机"，实测不互踢——教务按计算机踢会话）。
 */
object SessionKeepAlive {
    private const val INTERVAL_MS = 20 * 60_000L // 20 分钟 < 30 分钟寿命

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Alive=会话可用；Dead=探测发现失效；Idle=未登录/未启动 */
    enum class State { Idle, Alive, Dead }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state

    /** 最近一次探测是否通过（null=从未探测） */
    private val _lastProbeAlive = MutableStateFlow<Boolean?>(null)
    val lastProbeAlive: StateFlow<Boolean?> = _lastProbeAlive

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                if (!SessionManager.sid.isNullOrEmpty()) {
                    val alive = probeOnce()
                    _lastProbeAlive.value = alive
                    _state.value = if (alive) State.Alive else State.Dead
                }
                delay(INTERVAL_MS)
            }
        }
    }

    /** 单次探测：Index.aspx 200=活；302/其他=死。suspend 供 UI 即时刷新用。 */
    suspend fun probeOnce(): Boolean {
        val sid = SessionManager.sid ?: return false
        return try {
            SessionManager.client
                .newCall(Request.Builder()
                    .url("http://202.115.194.60/Index.aspx?sid=$sid")
                    .build())
                .execute().use { it.code == 200 }
        } catch (_: Exception) {
            false // 网络异常按死处理，避免误报 Alive
        }
    }

    /** 重新登录成功后复位状态 */
    fun onRelogin() {
        _state.value = State.Alive
        _lastProbeAlive.value = true
    }
}
