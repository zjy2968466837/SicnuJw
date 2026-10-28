package cn.edu.sicnu.scnujw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 跨页共用的「非颜色」令牌与固定 alpha。
 *
 * 规则：
 *  - 描边/分隔统一用 outline + [OutlineAlpha]（0.6）；只有「元信息竖线」用 [OutlineStrongAlpha]。
 *  - 金色底只允许两种 alpha：选中胶囊 [AccentIndicatorAlpha]（0.16）、徽章 [AccentBadgeAlpha]（0.20）。
 *  - 间距只走 [ScnuSpacing] 的 4/8/12/16/24/32 档，不再出现 5/6/7/14/18/20 的漂移值。
 */
object ScnuTokens {
    val Hairline = 0.5.dp
    val CardBorder = 1.dp
    const val OutlineAlpha = 0.6f
    const val OutlineStrongAlpha = 0.9f
    const val MutedAlpha = 0.8f
    const val AccentIndicatorAlpha = 0.16f
    const val AccentBadgeAlpha = 0.20f
}

object ScnuSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * 分段控件的统一选中语言：金 16% 底 + 金字（与底栏 indicator、筛选 chip 同一套）。
 * 用于 ThemeModeSwitcher、成绩子页切换等所有 SingleChoiceSegmentedButtonRow。
 */
@Composable
fun scnuSegmentedButtonColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary.copy(
        alpha = ScnuTokens.AccentIndicatorAlpha,
    ),
    activeContentColor = MaterialTheme.colorScheme.primary,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
