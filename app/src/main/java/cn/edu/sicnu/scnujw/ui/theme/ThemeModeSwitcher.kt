package cn.edu.sicnu.scnujw.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 主题模式三态切换（跟随系统/浅色/深色），即时生效并持久化。
 * 文案来自 [ThemeMode.label]，不在本文件重复硬编码。
 * 选中语言与底栏 indicator、成绩子页切换共用 [scnuSegmentedButtonColors]。
 */
@Composable
fun ThemeModeSwitcher(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val modes = listOf(
        ThemeMode.SYSTEM to Icons.Filled.SettingsBrightness,
        ThemeMode.LIGHT to Icons.Filled.LightMode,
        ThemeMode.DARK to Icons.Filled.DarkMode,
    )
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (mode, icon) ->
                val sel = ThemeModeState.mode == mode
                SegmentedButton(
                    selected = sel,
                    onClick = { ThemeModeState.set(mode, ctx) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    colors = scnuSegmentedButtonColors(),
                    // 可见文案已经说明选项，图标不再重复播报
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(mode.label) },
                )
            }
        }
    }
}
