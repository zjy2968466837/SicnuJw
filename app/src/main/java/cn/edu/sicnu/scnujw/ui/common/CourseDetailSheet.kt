package cn.edu.sicnu.scnujw.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.sicnu.scnujw.core.jw.JwClient
import cn.edu.sicnu.scnujw.core.jw.PlanDetail
import cn.edu.sicnu.scnujw.core.jw.PlanDetailParser
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import org.jsoup.Jsoup
import cn.edu.sicnu.scnujw.ui.common.ScnuStatePanel
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens

/** 弹层里字段标签列的固定宽度（与选课结果展开态保持一致） */
private val SheetLabelWidth = 88.dp

/**
 * 课程详情底部弹层：按 课程名/课程代码 在选课结果里定位执行计划（Zxjhdm），
 * 再原生抓「执行计划详情」页解析展示（键值对 + 教材表）。
 *
 * @param term 成绩行用其学期码；课表用 null（选课结果页默认=当前学期）
 * @param courseCode 课表块没有代码，传空串
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    courseName: String,
    term: String?,
    courseCode: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<PlanDetail?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        error = null
        // 缓存键与 Zxjhdm 无关：即使映射刷新失败/课程被撤，也能用上一次的详情兜底。
        val courseKey = courseCode.ifBlank { courseName }
        // 1) 先上屏缓存（有的话），用户不必等网络
        val cached = runCatching { ScoreCache.readPlanDetail(ctx, term, courseKey) }.getOrNull()
        if (cached != null) {
            val p = runCatching { PlanDetailParser.parse(Jsoup.parse(cached.html)) }.getOrNull()
            if (p != null && (p.info.isNotEmpty() || p.textbooks.isNotEmpty())) {
                detail = p
                loading = false
            }
        }
        try {
            val zx = ScoreCache.findZxjhdm(ctx, term, courseCode, courseName)
                ?: throw IllegalStateException(
                    "选课结果里没有找到这门课的执行计划。可能该课未走选课（如重修/补修），或不在本学期选课结果页。"
                )
            val doc = JwClient.fetchPlanDetail(zx)
            val p = PlanDetailParser.parse(doc)
            if (p.info.isEmpty() && p.textbooks.isEmpty()) {
                // 更新拿到了空页面：不覆盖缓存，有缓存就继续用
                throw IllegalStateException("执行计划页暂无可展示内容")
            }
            ScoreCache.writePlanDetail(ctx, term, courseKey, doc.html())
            detail = p
            error = null
        } catch (e: Exception) {
            // 面向用户的文案不直接透出异常原文；技术细节只进日志
            android.util.Log.w("CourseDetail", "执行计划加载失败: ${e.javaClass.simpleName}: ${e.message}")
            // 有缓存就静默降级（保留缓存内容），无缓存才显示错误面板
            if (detail == null) {
                error = if (e is IllegalStateException && !e.message.isNullOrBlank()) {
                    e.message
                } else {
                    "执行计划页暂时读不到，可能是网络或登录状态的问题。"
                }
            }
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScnuSpacing.l)
                .padding(bottom = ScnuSpacing.xl),
        ) {
            Text(
                courseName,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(ScnuSpacing.l))

            when {
                loading -> ScnuStatePanel(
                    title = "正在读取执行计划",
                    body = "正在从教务系统取这门课的执行计划。",
                    loading = true,
                )

                error != null -> ScnuStatePanel(
                    title = "执行计划没打开",
                    body = error,
                    isError = true,
                    actionLabel = "重新加载",
                    onAction = { attempt++ },
                )

                detail != null -> {
                    val d = detail!!
                    d.info.entries.filter { it.value.isNotBlank() }.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                k,
                                modifier = Modifier.width(SheetLabelWidth),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                v,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (d.textbooks.isNotEmpty()) {
                        Spacer(Modifier.height(ScnuSpacing.m))
                        HorizontalDivider(
                            thickness = ScnuTokens.Hairline,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                        )
                        Spacer(Modifier.height(ScnuSpacing.m))
                        Text(
                            "教材",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        d.textbooks.forEach { b ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(top = ScnuSpacing.s),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(Modifier.padding(horizontal = ScnuSpacing.m, vertical = ScnuSpacing.s)) {
                                    Text(
                                        b.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    val meta = listOf(b.author, b.publisher, b.date, b.price)
                                        .filter { it.isNotBlank() }
                                    if (meta.isNotEmpty()) {
                                        Spacer(Modifier.height(ScnuSpacing.xs))
                                        Text(
                                            meta.joinToString(" / "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (b.isbn.isNotBlank()) {
                                        Text(
                                            "ISBN ${b.isbn}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
