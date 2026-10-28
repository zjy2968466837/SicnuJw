package cn.edu.sicnu.scnujw.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.edu.sicnu.scnujw.R

/**
 * 字体分工（两条轨道，各自只做一件事）：
 *  - 中文/正文：系统字体（Roboto + 中文回退），承载可读性与本地感；
 *  - 数字：Barlow Condensed，只给 GPA/分数/倒计时/周次/日期等数字场景。
 *
 * 中文标签不要用 display* / Num*（Barlow 无 CJK 字形，中文会回退成系统字体，
 * 令牌语义与实际渲染就脱节了）。中文页头用 headlineSmall / titleLarge / titleMedium。
 */
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
)

/**
 * 中文排版标尺。label 系列字距归零：M3 基线给 labelSmall 的 0.5sp 是给英文全大写标签的，
 * 套在中文上会松散。
 */
val ScnuTypography = Typography(
    // 数字：Hero（全 App 只有 GPA 一处在用）
    displayLarge = TextStyle(
        fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp, letterSpacing = (-0.5).sp, lineHeight = 58.sp,
    ),
    // 数字：Metric（与 NumLarge 同档，保留给需要 typography 直取的场景）
    displayMedium = TextStyle(
        fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
        fontSize = 42.sp, letterSpacing = (-0.5).sp, lineHeight = 44.sp,
    ),
    // 中文页头 / 学生姓名：大字但不需要 Barlow
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp,
    ),
    // 页面标题
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
    ),
    // 区块标题
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    // 卡片/小节标题
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp,
    ),
)

/** hero 区中文主标题（登录页、学工页）；不是数字样式，不要换成 display* */
val HeroTitle = TextStyle(
    fontSize = 40.sp, lineHeight = 46.sp,
    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
)

/** hero 区副标题 */
val HeroSubtitle = TextStyle(
    fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
)

/**
 * 数字标尺（四级，语义固定；不要按「行数」随手取）：
 *  - Hero 56   displayLarge：GPA / 唯一主角大数
 *  - Metric 42 NumLarge：总进度百分比、考试日期块的大日号
 *  - Value 28  NumMedium：区块计数、短编号房名、倒计时
 *  - Inline 22 NumSmall：列表行内分数、学期绩点
 *  - Dense 14  NumMicro：节次轴、钟点、标签里的数字（密集场景）
 * 每级都带 lineHeight，Barlow Condensed 行高偏紧，不给行高会贴边。
 */
val NumLarge = TextStyle(
    fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold,
    fontSize = 42.sp, letterSpacing = (-0.5).sp, lineHeight = 44.sp,
)
val NumMedium = TextStyle(
    fontFamily = BarlowCondensed, fontWeight = FontWeight.Medium,
    fontSize = 28.sp, letterSpacing = 0.sp, lineHeight = 30.sp,
)
val NumSmall = TextStyle(
    fontFamily = BarlowCondensed, fontWeight = FontWeight.Medium,
    fontSize = 22.sp, letterSpacing = 0.sp, lineHeight = 24.sp,
)
val NumMicro = TextStyle(
    fontFamily = BarlowCondensed, fontWeight = FontWeight.Medium,
    fontSize = 14.sp, letterSpacing = 0.sp, lineHeight = 16.sp,
)
