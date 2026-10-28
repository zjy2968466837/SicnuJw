package cn.edu.sicnu.scnujw.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局主题模式：跟随系统 / 浅色 / 深色，三态持久化。
 * 默认跟随系统；改动即时生效（[cn.edu.sicnu.scnujw.MainActivity] 的 ScnuJwTheme 读取 [isDark]）。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");
}

object ThemeModeState {
    private const val PREFS = "scnu_prefs"
    private const val KEY = "theme_mode"

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** 深色是否生效（考虑系统暗色） */
    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> _systemDark
        }

    private var _systemDark = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        _systemDark = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        prefs.edit().putString(KEY, mode.name).apply() // 回写规范化值
    }

    /** 系统暗色变化回调（MainActivity onConfigurationChanged 里调） */
    fun onSystemDarkChanged(dark: Boolean) { _systemDark = dark }

    fun set(m: ThemeMode, context: Context) {
        mode = m
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, m.name).apply()
    }
}
