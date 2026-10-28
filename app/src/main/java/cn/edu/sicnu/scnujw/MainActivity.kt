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
}
