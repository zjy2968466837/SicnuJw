package cn.edu.sicnu.scnujw.ui.diag

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.core.diag.Diag
import cn.edu.sicnu.scnujw.core.diag.DiagReport
import cn.edu.sicnu.scnujw.core.stats.StatsReporter
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志页：开关页面抓取、查看已捕获体积、导出成文本。
 *
 * 两个开关的默认值与理由：
 *  - 页面响应抓取 **默认开**（只写本机私有目录、写入/导出双重脱敏、不联网，用户可随时关）；
 *  - 使用统计 **默认开**（只上传学号前 4 位与匿名设备标识，见 [StatsReporter]）。
 */
@Composable
fun DiagScreen(nav: NavController) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var capture by remember { mutableStateOf(Diag.isEnabled(ctx)) }
    var statsOn by remember { mutableStateOf(StatsReporter.isEnabled(ctx)) }
    // stats() 要读文件数行数：不能在组合期算（主线程 IO），进页面时在 IO 线程取一次
    var stats by remember { mutableStateOf(Diag.Stats(0, 0, Diag.isEnabled(ctx), 0)) }
    LaunchedEffect(Unit) { stats = withContext(Dispatchers.IO) { Diag.stats(ctx) } }
    var confirmCapture by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { Diag.export(ctx, it) }
                }.isSuccess
            }
            working = false
            Toast.makeText(
                ctx,
                if (ok) "已导出（内容已脱敏）" else "导出失败，请换个位置再试",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(title = "诊断日志", onBack = { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(ScnuSpacing.l),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.l),
        ) {
            // 卡 1：两个开关。内距交给行自己（横向 16 / 纵向 12），卡片不设内距——
            // 分隔线两侧各 12dp 由两行提供，卡边与文字之间不再多垫一层 16dp。
            SectionCard(contentPadding = PaddingValues(0.dp)) {
                SwitchRow(
                    title = "记录页面响应",
                    sub = "默认开启：记录教务接口与 WebView 返回的原始页面（只存本机，导出时脱敏），" +
                        "用于排查解析问题；不需要可以关掉",
                    checked = capture,
                    onCheckedChange = { on ->
                        // 默认就是开的，所以「首次进入」不需要确认；只有用户从关切回开时再问一次
                        if (on && !capture) confirmCapture = true
                        else {
                            capture = on
                            Diag.setEnabled(ctx, on)
                        }
                    },
                )
                HorizontalDivider(
                    color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    thickness = ScnuTokens.Hairline,
                )
                // 公开构建没有配置统计服务（APK 内不含自建地址），这一项直接不显示，
                // 避免出现「开关打开但什么也不发」的假象
                if (StatsReporter.isConfigured()) {
                    SwitchRow(
                        title = "参与使用统计",
                        sub = "只上传学号前 4 位、匿名设备标识与版本信息；不含姓名/完整学号/成绩",
                        checked = statsOn,
                        onCheckedChange = { on ->
                            statsOn = on
                            StatsReporter.setEnabled(ctx, on)
                        },
                    )
                }
            }

            SectionCard {
                Text("已记录", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(ScnuSpacing.xs))
                Text(
                    "${stats.entries} 条 · ${stats.bytes / 1024} KB",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                )
                if (stats.lastAt > 0) {
                    Spacer(Modifier.height(ScnuSpacing.xs))
                    Text(
                        "最近一条：" + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(stats.lastAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(ScnuSpacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.s)) {
                    Button(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                            exportLauncher.launch("scnujw-diag-$stamp.txt")
                        },
                        enabled = !working && stats.entries > 0,
                        shape = MaterialTheme.shapes.small,
                    ) { Text(if (working) "导出中…" else "导出日志", style = MaterialTheme.typography.labelMedium) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { Diag.clear(ctx) }
                                stats = withContext(Dispatchers.IO) { Diag.stats(ctx) }
                            }
                        },
                        enabled = stats.entries > 0,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("清空", style = MaterialTheme.typography.labelMedium) }
                }
                if (DiagReport.isConfigured()) {
                    Spacer(Modifier.height(ScnuSpacing.s))
                    OutlinedButton(
                        onClick = { reportOpen = true },
                        enabled = !sending && stats.entries > 0,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (sending) "发送中…" else "发送诊断（附文字说明）",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // 说明只留最关键的事实；脱敏细则以 PRIVACY.md 与 Diag 的脱敏实现为准，
            // 不再在页面底部铺一段 4 行清单（UI_DESIGN 3.9.2）。
            Text(
                "记录默认开启，只写本机应用私有目录、不联网；不需要时可用上方开关关闭。" +
                    "导出文本已自动脱敏（学号、姓名、登录凭据）；页面里仍可能有课程名等校内信息，分享前请自行确认。",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }

    if (confirmCapture) {
        // 只在你主动从「关」切回「开」时出现；默认开启状态下进页面不会打扰
        AlertDialog(
            onDismissRequest = { confirmCapture = false },
            title = { Text("重新开始记录页面内容？") },
            text = {
                Text(
                    "开启后会把你访问的教务页面原文写入应用私有目录（本机，不上传，导出时自动脱敏），" +
                        "用于定位解析问题。反馈完可以在这里清空，或再次关闭。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCapture = false
                    capture = true
                    Diag.setEnabled(ctx, true)
                }) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCapture = false }) { Text("取消") }
            },
        )
    }

    if (reportOpen) {
        AlertDialog(
            onDismissRequest = { if (!sending) reportOpen = false },
            title = { Text("发送诊断信息", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        "将上传最近约 500 KB 的诊断记录（与导出内容相同，学号/姓名/票据/密码等已替换为占位符），" +
                            "以及下面的文字说明。仅在点击「发送」时上传，不含账号密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(ScnuSpacing.s))
                    OutlinedTextField(
                        value = reportText,
                        onValueChange = { reportText = it.take(2000) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending,
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("问题描述（可选）") },
                        placeholder = { Text("例如：点开成绩页提示读取失败，已试过重新登录") },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (sending) return@TextButton
                        sending = true
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { DiagReport.send(ctx, reportText) }
                            sending = false
                            if (err == null) {
                                reportOpen = false
                                reportText = ""
                            }
                            Toast.makeText(ctx, err ?: "已发送，感谢反馈", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !sending,
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("发送")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { reportOpen = false }, enabled = !sending) { Text("取消") }
            },
        )
    }
}

/**
 * 分组卡：`surface` + 1dp outline + large 圆角，与「我的」页其它分组卡同一套语言。
 * 内距由调用方决定：之前这里强制 `spacedBy(12)`，各卡再自己加 Spacer，间距会叠成两倍
 * （统计卡「N 条 → 按钮」被撑到 36dp）；现在卡内只保留一套间距。
 */
@Composable
private fun SectionCard(
    contentPadding: PaddingValues = PaddingValues(ScnuSpacing.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = cs.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }

}

/**
 * 开关行：左文案（bodyLarge）+ 副标题（bodySmall），右侧 Switch。
 * 行内 16/12 内距让两条开关行之间的分隔线真正起分层作用；
 * 整行高度随字体放大自然增长，Switch 自带 48dp 触控区。
 */
@Composable
private fun SwitchRow(
    title: String,
    sub: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
