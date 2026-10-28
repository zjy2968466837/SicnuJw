package cn.edu.sicnu.scnujw

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.edu.sicnu.scnujw.core.auth.AuthGuard
import cn.edu.sicnu.scnujw.core.auth.SessionProbe
import cn.edu.sicnu.scnujw.core.diag.Diag
import cn.edu.sicnu.scnujw.core.jw.UsualPrefetch
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.ui.AppScaffold
import cn.edu.sicnu.scnujw.ui.theme.ScnuJwTheme
import cn.edu.sicnu.scnujw.ui.theme.ThemeModeState
import cn.edu.sicnu.scnujw.ui.update.UpdateHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.init(this)
        AuthGuard.init(this)
        ThemeModeState.init(this)
        // 诊断日志需要应用级 Context（JwClient 等对象拿不到 Activity）
        Diag.init(this)
        installCrashRecorder()
        enableEdgeToEdge()
        setContent {
            ScnuJwTheme(darkTheme = ThemeModeState.isDark) {
                // UpdateHost 包在导航外面：更新弹窗要能盖住任意页面（含登录页）
                UpdateHost { AppScaffold() }
            }
        }
        // 首帧之后再在后台把三个下游系统的真实状态探一遍（无凭据时直接返回，
        // 需要人工验证只落状态、不弹窗、不导航）；不阻塞启动。
        SessionProbe.kick(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        ThemeModeState.onSystemDarkChanged(night)
    }

    /**
     * 崩溃事件：记一条诊断（类名 + 前几帧堆栈），**然后必须转交原 handler**。
     *
     * 两点都不能省：
     *  - 不吞崩溃：吞掉会让进程「静默消失」，用户与系统都拿不到 ANR/崩溃反馈；
     *  - 同步写：uncaught handler 返回后进程立刻被杀，走单线程池排队大概率丢记录
     *    （[Diag.recordEvent] 的 awaitWrite）。
     *
     * 记录内容的构造也包在 runCatching 里：构造若抛错（如 OOM）同样不能跳过转交。
     * [installedOnce] 保证只装一层：Activity 每次重建都会进 onCreate，无条件重装
     * 会让 handler 链越叠越长，同一次崩溃被记 N 遍、层层同步各等 2s。
     */
    private fun installCrashRecorder() {
        if (installedOnce.getAndSet(true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Diag.recordEvent(
                    "crash",
                    buildString {
                        append(error.javaClass.name).append(": ").append(error.message.orEmpty())
                        error.stackTrace.take(8).forEach { append("\n    at ").append(it) }
                    },
                    awaitWrite = true,
                )
            }
            if (previous != null) previous.uncaughtException(thread, error) else throw error
        }
    }

    private companion object {
        val installedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
