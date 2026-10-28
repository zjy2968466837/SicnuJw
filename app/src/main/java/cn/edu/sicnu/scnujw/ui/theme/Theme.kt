package cn.edu.sicnu.scnujw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 深色方案。surface 高度阶梯必须单调变亮：
 * lowest(#0C2115) < low(#143A21) < container(#164024) < surface(#1A4429) < high(#1F4E30) < highest(#245538)。
 * ModalBottomSheet 默认取 surfaceContainerLow，这里给了暖调深绿，页面就不必再手动改回 surface。
 */
private val DarkScheme = darkColorScheme(
    primary = ScnuPalette.D_ACCENT,
    onPrimary = ScnuPalette.D_ACCENT_INK,
    primaryContainer = ScnuPalette.D_SURFACE,
    onPrimaryContainer = ScnuPalette.D_INK,
    inversePrimary = ScnuPalette.L_ACCENT_TEXT,
    secondary = ScnuPalette.D_MUTED,
    onSecondary = ScnuPalette.D_BG,
    secondaryContainer = ScnuPalette.D_SURFACE_2,
    onSecondaryContainer = ScnuPalette.D_INK,
    tertiary = ScnuPalette.D_ACCENT,
    tertiaryContainer = ScnuPalette.D_ACCENT.copy(alpha = ScnuTokens.AccentIndicatorAlpha),
    onTertiaryContainer = ScnuPalette.D_BG,
    background = ScnuPalette.D_BG,
    onBackground = ScnuPalette.D_INK,
    surface = ScnuPalette.D_SURFACE,
    onSurface = ScnuPalette.D_INK,
    surfaceVariant = ScnuPalette.D_SURFACE_2,
    onSurfaceVariant = ScnuPalette.D_MUTED,
    surfaceTint = ScnuPalette.D_ACCENT,
    surfaceContainerLowest = ScnuPalette.D_SURFACE_LOWEST,
    surfaceContainerLow = ScnuPalette.D_SURFACE_LOW,
    surfaceContainer = ScnuPalette.D_SURFACE_2,
    surfaceContainerHigh = ScnuPalette.D_SURFACE_HIGH,
    surfaceContainerHighest = ScnuPalette.D_SURFACE_HIGHEST,
    surfaceBright = ScnuPalette.D_SURFACE_HIGHEST,
    surfaceDim = ScnuPalette.D_SURFACE_LOWEST,
    inverseSurface = ScnuPalette.D_INK,
    inverseOnSurface = ScnuPalette.D_BG,
    outline = ScnuPalette.D_OUTLINE,
    // 与 outline 同值：本项目只用 outline 表达描边；保留别名以免 M3 组件回落成紫色
    outlineVariant = ScnuPalette.D_OUTLINE,
    error = ScnuPalette.D_ERROR,
    onError = ScnuPalette.D_BG,
    errorContainer = ScnuPalette.D_ERROR_CONTAINER,
    onErrorContainer = ScnuPalette.D_ERROR,
    scrim = Color(0xFF000000),
)

/**
 * 浅色方案。surface 用暖白（#FEFDF9）而不是纯白，与 background #F7F6F1 同一叙事；
 * primary 取 L_ACCENT_TEXT（#8A5E0C）而不是 L_ACCENT：金色文字/图标/描边在暖白底上
 * 才有 5.26:1；L_ACCENT 只保留给 16% 胶囊底色（tertiaryContainer）。
 */
private val LightScheme = lightColorScheme(
    primary = ScnuPalette.L_ACCENT_TEXT,
    onPrimary = ScnuPalette.L_ACCENT_INK,
    primaryContainer = ScnuPalette.L_SURFACE,
    onPrimaryContainer = ScnuPalette.L_INK,
    inversePrimary = ScnuPalette.D_ACCENT,
    secondary = ScnuPalette.L_MUTED,
    onSecondary = ScnuPalette.L_SURFACE_WARM,
    secondaryContainer = ScnuPalette.L_SURFACE_2,
    onSecondaryContainer = ScnuPalette.L_INK,
    tertiary = ScnuPalette.L_ACCENT_TEXT,
    tertiaryContainer = ScnuPalette.L_ACCENT.copy(alpha = ScnuTokens.AccentIndicatorAlpha),
    onTertiaryContainer = ScnuPalette.L_INK,
    background = ScnuPalette.L_BG,
    onBackground = ScnuPalette.L_INK,
    surface = ScnuPalette.L_SURFACE_WARM,
    onSurface = ScnuPalette.L_INK,
    surfaceVariant = ScnuPalette.L_SURFACE,
    onSurfaceVariant = ScnuPalette.L_MUTED,
    surfaceTint = ScnuPalette.L_ACCENT_TEXT,
    surfaceContainerLowest = ScnuPalette.L_SURFACE_LOWEST,
    surfaceContainerLow = ScnuPalette.L_SURFACE_LOW,
    surfaceContainer = ScnuPalette.L_SURFACE_CONTAINER,
    surfaceContainerHigh = ScnuPalette.L_SURFACE,
    surfaceContainerHighest = ScnuPalette.L_SURFACE_2,
    surfaceBright = ScnuPalette.L_SURFACE_LOWEST,
    surfaceDim = ScnuPalette.L_SURFACE_2,
    inverseSurface = ScnuPalette.L_INK,
    inverseOnSurface = ScnuPalette.L_BG,
    outline = ScnuPalette.L_OUTLINE,
    outlineVariant = ScnuPalette.L_OUTLINE,
    error = ScnuPalette.L_ERROR,
    onError = ScnuPalette.L_ACCENT_INK,
    errorContainer = ScnuPalette.L_ERROR_CONTAINER,
    onErrorContainer = ScnuPalette.L_ERROR,
    scrim = Color(0xFF000000),
)

/**
 * 形状语言只留三级：8dp 控件/小件、12dp 列表卡、16dp 大卡；
 * extraSmall 6dp 专给徽章（内容宽度本来就小，6dp 才不显圆）。
 */
val ScnuShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** 重音徽章（重修/状态/数据源）统一形状；与 shapes.extraSmall 同值，语义更明确 */
val BadgeShape: Shape = RoundedCornerShape(6.dp)

/**
 * 语义色通道：M3 的 ColorScheme 没有「成功」槽位，而登录状态需要红绿直觉。
 * 语义绿不借用 primary（金 = 品牌重音，1.2 白名单）也不改 tertiary，
 * 由 [ScnuJwTheme] 按主题注入；页面用 [MaterialTheme.scnuOk] 读取，别处不得使用。
 */
private val LocalScnuOk = staticCompositionLocalOf { ScnuPalette.D_OK }

/** 语义「可用」绿：只允许用在登录 / 会话状态（圆点、状态文字、健康条）。 */
val MaterialTheme.scnuOk: Color
    @Composable @ReadOnlyComposable
    get() = LocalScnuOk.current

@Composable
fun ScnuJwTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalScnuOk provides if (darkTheme) ScnuPalette.D_OK else ScnuPalette.L_OK,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = ScnuTypography,
            shapes = ScnuShapes,
            content = content,
        )
    }
    // 系统栏图标跟随 App 主题（App 主题可独立于系统）
    SyncSystemBars(darkTheme = darkTheme)
}
