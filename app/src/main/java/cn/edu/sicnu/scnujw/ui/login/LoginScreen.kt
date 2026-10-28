package cn.edu.sicnu.scnujw.ui.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.ui.theme.HeroSubtitle
import cn.edu.sicnu.scnujw.ui.theme.HeroTitle
import cn.edu.sicnu.scnujw.ui.theme.ScnuPalette
import cn.edu.sicnu.scnujw.ui.theme.SyncSystemBars
import cn.edu.sicnu.scnujw.ui.theme.ThemeModeState

/**
 * 登录唯一通道：应用内官方页（CAS + MFA 全程在 WebView 内完成）。
 * 登录成功后由 AuthWebViewScreen 统一回灌 CAS 票据与各域 Cookie，
 * 之后 zhxg / jwcxk 可静默换票，无需二次 WebView。
 */
@Composable
fun LoginScreen(nav: NavController) {
    // 顶部是固定深色 hero：状态栏图标始终用浅色，不跟随 App 主题
    SyncSystemBars(darkTheme = ThemeModeState.isDark, forceLightStatusIcons = true)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        BrandHero()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = { nav.navigate("auth?system=zf&mode=full&ret=schedule") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.large,
                    // 走主题令牌：深色主题 = 银杏金 + 墨绿字，浅色主题 = 深金 + 暖白字
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                ) {
                    Text(
                        text = "统一身份认证登录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "在官方页面完成密码与双因子验证后自动返回",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 页脚：免责声明
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "数据来自教务系统",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 8.dp)
                            .width(1.dp)
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Text(
                        text = "请勿用于正式依据",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun BrandHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clipToBounds()
            .background(Brush.linearGradient(ScnuPalette.PINE_GRADIENT))
    ) {
        // 银杏叶剪影：金色 alpha 0.6，只作点缀
        Canvas(Modifier.matchParentSize()) {
            val gold = ScnuPalette.D_ACCENT.copy(alpha = 0.6f)
            drawGinkgoLeaf(Offset(size.width * 0.80f, size.height * 0.64f), 40.dp.toPx(), 18f, gold)
            drawGinkgoLeaf(Offset(size.width * 0.88f, size.height * 0.88f), 26.dp.toPx(), 52f, gold)
            drawGinkgoLeaf(Offset(size.width * 0.66f, size.height * 0.34f), 19.dp.toPx(), -20f, gold)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        ) {
            Text(
                text = "狮山教务",
                color = ScnuPalette.D_INK,
                style = HeroTitle,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "四川师范大学 非官方客户端",
                color = ScnuPalette.D_MUTED,
                style = HeroSubtitle,
            )
        }
    }
}

/** 扇面银杏叶：底端为叶柄，顶端带浅缺口。坐标以叶柄为原点，向上生长。 */
private fun DrawScope.drawGinkgoLeaf(
    origin: Offset,
    radius: Float,
    rotation: Float,
    color: Color
) {
    val r = radius
    val leaf = Path().apply {
        moveTo(0f, 0f)
        cubicTo(-0.18f * r, -0.30f * r, -0.70f * r, -0.62f * r, -0.92f * r, -0.98f * r)
        quadraticTo(-1.02f * r, -1.14f * r, -0.72f * r, -1.16f * r)
        quadraticTo(-0.34f * r, -1.18f * r, -0.10f * r, -0.92f * r)
        quadraticTo(-0.03f * r, -0.84f * r, 0f, -0.80f * r)
        quadraticTo(0.03f * r, -0.84f * r, 0.10f * r, -0.92f * r)
        quadraticTo(0.34f * r, -1.18f * r, 0.72f * r, -1.16f * r)
        quadraticTo(1.02f * r, -1.14f * r, 0.92f * r, -0.98f * r)
        cubicTo(0.70f * r, -0.62f * r, 0.18f * r, -0.30f * r, 0f, 0f)
        close()
    }

    withTransform({
        translate(origin.x, origin.y)
        rotate(rotation, pivot = Offset.Zero)
    }) {
        drawPath(leaf, color)
        drawLine(
            color = color,
            start = Offset.Zero,
            end = Offset(0f, r * 0.36f),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
