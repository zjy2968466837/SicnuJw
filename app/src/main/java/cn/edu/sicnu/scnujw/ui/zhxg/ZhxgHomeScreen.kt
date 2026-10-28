package cn.edu.sicnu.scnujw.ui.zhxg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.theme.HeroSubtitle
import cn.edu.sicnu.scnujw.ui.theme.HeroTitle
import cn.edu.sicnu.scnujw.ui.theme.ScnuPalette
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens

/** 智慧学工模块主页：功能入口列表，入口数据见 [ZHXG_ENTRIES]。 */
@Composable
fun ZhxgHomeScreen(nav: NavController) {
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(title = "智慧学工", onBack = { nav.navigateUp() })

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(ScnuSpacing.l),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.l),
        ) {
            // ── hero：瓶绿渐变 + 骨白标题（与登录页同一套 hero 文字令牌） ──
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(ScnuPalette.PINE_GRADIENT),
                        MaterialTheme.shapes.large),
            ) {
                Column(Modifier.padding(ScnuSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(ScnuSpacing.xs)) {
                    Text(
                        "学生事务",
                        color = ScnuPalette.D_INK,
                        style = HeroTitle,
                    )
                    Text(
                        "假期去向登记",
                        color = ScnuPalette.D_MUTED,
                        style = HeroSubtitle,
                    )
                }
            }

            // ── 功能入口（细描边卡，无阴影） ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = cs.surface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
            ) {
                Column {
                    ZHXG_ENTRIES.forEachIndexed { i, e ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { nav.navigate(e.route) }
                                .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                e.icon,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(ScnuSpacing.m))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = cs.onSurface,
                                )
                                Text(e.sub, style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null,
                                tint = cs.outline, modifier = Modifier.size(20.dp))
                        }
                        if (i < ZHXG_ENTRIES.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                                thickness = ScnuTokens.Hairline,
                            )
                        }
                    }
                }
            }
        }
    }
}
