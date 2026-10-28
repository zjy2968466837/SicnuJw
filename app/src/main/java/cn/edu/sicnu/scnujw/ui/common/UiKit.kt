package cn.edu.sicnu.scnujw.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.sicnu.scnujw.ui.theme.BadgeShape
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.delay

/* ─────────────────────────── 页头 ─────────────────────────── */

/**
 * 全 App 唯一页头骨架：可选返回箭头 + titleMedium 标题（可带副标题）+ 右侧动作槽，
 * 底部 0.5dp outline 分隔线，底色与页面一致（不再用 primaryContainer 满幅色带）。
 * 顶栏 tab 页不传 onBack；功能子页传 onBack。
 */
@Composable
fun ScnuScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = if (onBack != null) 4.dp else ScnuSpacing.l, end = ScnuSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = cs.onSurface,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s),
                content = actions,
            )
        }
        HorizontalDivider(
            color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
            thickness = ScnuTokens.Hairline,
        )
    }
}

/* ─────────────────────────── 加载 / 空 / 错误 ─────────────────────────── */

/**
 * 页面内刷新提示条（已有数据时的刷新态）。
 * 设计规范：加载态说明「正在做什么 + 大概多久」，且不能遮挡页面内容。
 */
@Composable
fun ScnuRefreshBar(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = cs.primary,
        )
        Spacer(Modifier.width(ScnuSpacing.s))
        Text(text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

enum class ScnuNoticeTone { Info, Warning, Error }

/**
 * 行内提示：异常降级到上次缓存 / 部分学期加载失败时使用。
 * 淡入 + 展开，消失时淡出 + 收起；与刷新条同一套字号和行高，避免页面跳动。
 */
@Composable
fun ScnuInlineNotice(
    text: String?,
    modifier: Modifier = Modifier,
    tone: ScnuNoticeTone = ScnuNoticeTone.Warning,
) {
    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val (bg, fg) = when (tone) {
            ScnuNoticeTone.Error -> cs.errorContainer to cs.onErrorContainer
            ScnuNoticeTone.Warning ->
                cs.primary.copy(alpha = ScnuTokens.AccentIndicatorAlpha) to cs.onSurface
            ScnuNoticeTone.Info -> cs.surfaceVariant to cs.onSurfaceVariant
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.xs)
                .clip(MaterialTheme.shapes.small)
                .background(bg)
                .padding(horizontal = ScnuSpacing.m, vertical = ScnuSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(ScnuSpacing.s))
            Text(
                text ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
        }
    }
}

/**
 * 统一状态面板（加载 / 空 / 错误）。
 * 文案模板固定三段：title = 发生了什么；body = 为什么；hint = 下一步。
 * 错误标题取 error 色；主按钮是「金描边 + onSurface 字」（浅色墨绿 / 深色骨白）。
 */
@Composable
fun ScnuStatePanel(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    hint: String? = null,
    loading: Boolean = false,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().padding(horizontal = ScnuSpacing.xxl, vertical = ScnuSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = cs.primary,
                )
                Spacer(Modifier.height(ScnuSpacing.m))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) cs.error else cs.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(ScnuSpacing.s))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(ScnuSpacing.xs))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant.copy(alpha = ScnuTokens.MutedAlpha),
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(ScnuSpacing.l))
                PanelButton(actionLabel, isPrimary = true, onClick = onAction)
            }
            if (secondaryLabel != null && onSecondaryAction != null) {
                Spacer(Modifier.height(ScnuSpacing.s))
                PanelButton(secondaryLabel, isPrimary = false, onClick = onSecondaryAction)
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, isPrimary: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            ScnuTokens.CardBorder,
            if (isPrimary) cs.primary else cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (isPrimary) cs.onSurface else cs.onSurfaceVariant,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/* ─────────────────────────── 角标 / 元信息 / 徽章 ─────────────────────────── */

/**
 * 数据来源角标：BadgeShape + 1dp outline，全 App 只保留这一种外观。
 * 传入 [onClick] 时角标本身就是手动刷新入口（右侧 12dp 刷新符号提示可点），
 * 不再另设右上角刷新按钮。
 *
 * 深色品牌面上（GPA 卡）通过 [containerColor]/[contentColor]/[borderColor]/[iconTint]
 * 换成骨白/品牌深色，外观与可点语义保持一致；[enabled] 只禁用点击，不改变尺寸，
 * 避免刷新中角标从 48dp 塌成一行文字、把页头顶得上下跳。
 */
@Composable
fun ScnuSourceBadge(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (text.isBlank()) return
    if (onClick == null) {
        Box(modifier) {
            SourceBadgeContent(text, false, containerColor, contentColor, borderColor, iconTint)
        }
    } else {
        // 视觉仍保持小角标；点击区做满 48dp，避免小目标点不中
        Box(
            modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(vertical = ScnuSpacing.s),
            contentAlignment = Alignment.Center,
        ) { SourceBadgeContent(text, true, containerColor, contentColor, borderColor, iconTint) }
    }
}

@Composable
private fun SourceBadgeContent(
    text: String,
    showRefresh: Boolean,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    iconTint: Color,
) {
    Surface(
        color = containerColor,
        shape = BadgeShape,
        border = BorderStroke(ScnuTokens.CardBorder, borderColor),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
            if (showRefresh) {
                Spacer(Modifier.width(ScnuSpacing.xs))
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "更新数据",
                    tint = iconTint,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** 角标刷新的本地状态：只有「用户主动点角标」的那次刷新成功，才闪一次「已更新」。 */
class SourceRefreshState {
    var requested by mutableStateOf(false)
    var wasRefreshing by mutableStateOf(false)
    var justUpdated by mutableStateOf(false)
}

@Composable
fun rememberSourceRefreshState(): SourceRefreshState = remember { SourceRefreshState() }

/**
 * 角标文案：刷新中「更新中…」→ 成功后短暂「已更新」→ 回到页面给的 source。
 *
 * [success] 必须是 ViewModel 对这次联网结果的明确判定（网络成功 true、失败 false）；
 * 不要用 source 文本反推——上一次成功留下的「实时」会让失败误判成「已更新」。
 * 自动加载（进入页面/跨天刷新）不会闪「已更新」：只有 [state] 里记录了用户点按才闪。
 * 「已更新」只是本次运行的瞬时反馈，不落盘：下次进入仍按各页默认缓存策略显示。
 */
@Composable
fun sourceLabel(
    source: String,
    refreshing: Boolean,
    success: Boolean,
    state: SourceRefreshState,
): String {
    LaunchedEffect(refreshing, success) {
        if (state.requested && state.wasRefreshing && !refreshing) {
            if (success) {
                state.justUpdated = true
                // 被下一次刷新/切换取消时也要复位，否则「已更新」会永久卡在角标上
                try {
                    delay(1_800)
                } finally {
                    state.justUpdated = false
                }
            }
            state.requested = false
        }
        state.wasRefreshing = refreshing
    }
    return when {
        refreshing -> "更新中…"
        state.justUpdated -> "已更新"
        else -> source
    }
}

/** 可点角标 + 刷新中/已更新文案：点它 = 强制刷新当前页 */
@Composable
fun ScnuRefreshableSourceBadge(
    source: String,
    refreshing: Boolean,
    success: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // 首屏还没有任何来源（整页加载面板在展示）时不渲染角标，避免两处重复「更新中…」
    if (source.isBlank()) return
    val state = rememberSourceRefreshState()
    // 双击保护：VM 的 refreshing 要到协程里才置位，中间一两帧仍可点；本地先锁 2 秒
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) { if (!refreshing) pending = false }
    LaunchedEffect(pending) {
        if (pending) {
            delay(2_000)
            pending = false
        }
    }
    ScnuSourceBadge(
        text = sourceLabel(source, refreshing, success, state),
        modifier = modifier,
        onClick = {
            if (!pending) {
                pending = true
                state.requested = true
                state.justUpdated = false
                onRefresh()
            }
        },
        // 刷新中仍保留同一个 48dp 盒子与刷新图形，只禁用点击
        enabled = !refreshing && !pending,
        containerColor = containerColor,
        contentColor = contentColor,
        borderColor = borderColor,
        iconTint = iconTint,
    )
}

enum class ScnuBadgeTone { Accent, Error, Muted, Neutral }

/** 状态徽章：accent = 金 20% 底 + 金字；error = errorContainer；muted/neutral = 描边 */
@Composable
fun ScnuStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: ScnuBadgeTone = ScnuBadgeTone.Neutral,
) {
    val cs = MaterialTheme.colorScheme
    val background = when (tone) {
        ScnuBadgeTone.Accent -> cs.primary.copy(alpha = ScnuTokens.AccentBadgeAlpha)
        ScnuBadgeTone.Error -> cs.errorContainer
        ScnuBadgeTone.Muted -> Color.Transparent
        ScnuBadgeTone.Neutral -> cs.surface
    }
    val content = when (tone) {
        ScnuBadgeTone.Accent -> cs.primary
        ScnuBadgeTone.Error -> cs.error
        ScnuBadgeTone.Muted, ScnuBadgeTone.Neutral -> cs.onSurfaceVariant
    }
    val border = when (tone) {
        ScnuBadgeTone.Accent -> cs.primary.copy(alpha = 0.5f)
        ScnuBadgeTone.Error -> cs.error.copy(alpha = 0.5f)
        ScnuBadgeTone.Muted, ScnuBadgeTone.Neutral ->
            cs.outline.copy(alpha = ScnuTokens.OutlineAlpha)
    }
    Surface(
        modifier = modifier,
        color = background,
        shape = BadgeShape,
        border = BorderStroke(ScnuTokens.CardBorder, border),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 元信息一行：段与段之间用 1dp 竖线（不用中点连接符「A · B」——那是模板腔）。
 * style 默认 labelSmall；考试页可传 labelMedium。
 */
@Composable
fun ScnuMetaLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
) {
    val visible = parts.filter { it.isNotBlank() }
    if (visible.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        visible.forEachIndexed { index, text ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 7.dp)
                        .width(1.dp)
                        .height(9.dp)
                        .background(cs.outline.copy(alpha = ScnuTokens.OutlineStrongAlpha)),
                )
            }
            Text(
                text,
                style = style,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 筛选 chip：选中 = 金 16% 底 + 金字 + 金描边，与底栏/分段控件同一套语言 */
@Composable
fun ScnuFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = cs.onSurfaceVariant,
            selectedContainerColor = cs.primary.copy(alpha = ScnuTokens.AccentIndicatorAlpha),
            selectedLabelColor = cs.primary,
        ),
        border = BorderStroke(
            ScnuTokens.CardBorder,
            if (selected) cs.primary.copy(alpha = 0.5f)
            else cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
        ),
    )
}

/* ─────────────────────────── 数字格式 ─────────────────────────── */

/** 学分统一写法：39.5 → "39.5"，41.0 → "41"，缺省 "—"；全 App 只此一份 */
fun fmtCredit(v: Double?): String = v?.let {
    if (it % 1.0 == 0.0) "%.0f".format(it) else "%.1f".format(it)
} ?: "—"
