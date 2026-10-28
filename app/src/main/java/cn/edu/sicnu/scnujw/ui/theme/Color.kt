package cn.edu.sicnu.scnujw.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「狮山·银杏」设计色板（依据 taste-skill library "Bottle green, gold" two-tone 校准）。
 * 深瓶绿承载学府沉稳，银杏金只做重音——一处大胆，其余克制。
 *
 * 阅读约定：
 *  - D_* / L_* 是「深色主题 / 浅色主题」两套背景下的取色表；
 *  - D_* 同时也是「始终深色的品牌面」（PINE_GRADIENT hero、学生证卡、GPA 卡）的取色来源，
 *    这些面不随主题反转，因此不要再把它们理解成「只有暗色主题才用」。
 */
object ScnuPalette {
    // —— 深色（默认，品牌记忆点）——
    val D_BG = Color(0xFF10291B)        // 瓶绿底（比 133621 沉一档）
    val D_SURFACE = Color(0xFF1A4429)   // 卡面
    val D_SURFACE_2 = Color(0xFF164024) // 次级面
    val D_INK = Color(0xFFF2EFE6)       // 骨白正文
    val D_MUTED = Color(0xFFB3C1A8)     // 次要文字（灰绿，提到 4.5:1 级保证深底可读）
    val D_ACCENT = Color(0xFFF4D96C)    // 银杏金（重音：GPA/本周/选中）
    val D_ACCENT_INK = Color(0xFF133621)
    val D_ERROR = Color(0xFFEC8E6C)     // 挂科警示（赭红）：在 surface #1A4429 上 4.55:1，不刺眼
    val D_ERROR_CONTAINER = Color(0xFF5C2A18)
    val D_OUTLINE = Color(0xFF2E5A3E)   // 描边/分隔

    /**
     * 语义「可用」绿：只给登录 / 会话状态（UI_DESIGN 2.10），不参与其它页面。
     * 实测（6.1 不透明口径）：on D_SURFACE #1A4429 = 5.52:1，on D_SURFACE_2 = 5.85:1，on D_BG = 7.74:1。
     */
    val D_OK = Color(0xFF74C98F)

    // 深色面高度阶梯（由暗到亮，禁止倒置：lowest < bg < surface < high < highest 的观感顺序）
    val D_SURFACE_LOWEST = Color(0xFF0C2115)
    val D_SURFACE_LOW = Color(0xFF143A21)
    val D_SURFACE_HIGH = Color(0xFF1F4E30)
    val D_SURFACE_HIGHEST = Color(0xFF245538)

    // —— 浅色（日间）——
    val L_BG = Color(0xFFF7F6F1)        // 暖白
    val L_SURFACE = Color(0xFFECEBE2)   // 次级面 / surfaceVariant
    val L_SURFACE_2 = Color(0xFFE2E1D6) // 最深一级卡面
    val L_SURFACE_WARM = Color(0xFFFEFDF9) // 主卡面：暖白而非冷白，与 L_BG 同一叙事
    val L_SURFACE_CONTAINER = Color(0xFFF1F0E8)
    val L_SURFACE_LOW = Color(0xFFF5F4EE)
    val L_SURFACE_LOWEST = Color(0xFFFFFEFB)
    val L_INK = Color(0xFF0D2A1C)       // 瓶绿墨
    val L_MUTED = Color(0xFF5F6B62)
    val L_ACCENT = Color(0xFFC08A1E)    // 浅色主题的亮金：只做 16% 透明胶囊/装饰，不承载文字
    /** 浅色主题的金色文字与图标（on #F7F6F1 = 5.26:1，on #FEFDF9 = 5.60:1，达 AA） */
    val L_ACCENT_TEXT = Color(0xFF8A5E0C)
    val L_ACCENT_INK = Color(0xFFFFFBF0)
    val L_ERROR = Color(0xFFB5471F)
    val L_ERROR_CONTAINER = Color(0xFFF6E3D9)
    val L_OUTLINE = Color(0xFFC9CBBB)

    /**
     * 语义「可用」绿（浅色）：与 D_OK 成对，只给登录 / 会话状态。
     * 实测（6.1 不透明口径）：on L_SURFACE_WARM #FEFDF9 = 6.03:1，on L_BG = 5.67:1，on L_SURFACE = 5.13:1。
     */
    val L_OK = Color(0xFF1B7038)

    /** 课程块 8 色（莫兰迪校园系，白字可读；顺序即 lessonId 哈希落点） */
    val COURSE = listOf(
        Color(0xFF3E6B57), // 松绿
        Color(0xFF756A55), // 暖石灰（替换紫灰：全色板保持暖调叙事）
        Color(0xFF7A5C48), // 赭石
        Color(0xFF4E6E8E), // 雾蓝
        Color(0xFF8E6E4E), // 驼
        Color(0xFF5E7A4E), // 橄榄
        Color(0xFF7A4E5E), // 豆沙
        Color(0xFF55706B), // 青灰
    )

    /** 课程块上的字色（课程 8 色是固定深色，字色因此不跟随主题） */
    val COURSE_INK = Color(0xFFFFFFFF)
    val COURSE_INK_SECONDARY = Color(0xFFFFFFFF).copy(alpha = 0.85f)

    /** 银杏金渐变（GPA 记忆点卡用） */
    val GOLD_GRADIENT = listOf(Color(0xFFF4D96C), Color(0xFFD9A441))
    /** 瓶绿渐变（hero 区用） */
    val PINE_GRADIENT = listOf(Color(0xFF1A4429), Color(0xFF10291B))
}
