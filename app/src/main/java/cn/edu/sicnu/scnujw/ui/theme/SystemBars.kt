package cn.edu.sicnu.scnujw.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 系统栏图标明暗跟随 App 主题。
 *
 * 背景：MainActivity 只调用一次 enableEdgeToEdge()，系统栏外观由系统明暗决定；
 * 而 App 内主题可以独立于系统（ThemeMode.DARK + 系统浅色），不同步就会出现
 * 「深色图标压深绿底」。这里在主题组合完成后同步一次，任何主题切换都会重跑。
 *
 * @param darkTheme App 当前是否深色
 * @param forceLightStatusIcons 顶部是固定深色 hero 时（登录页）强制浅色图标
 */
@Composable
fun SyncSystemBars(darkTheme: Boolean, forceLightStatusIcons: Boolean = false) {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !forceLightStatusIcons && !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
