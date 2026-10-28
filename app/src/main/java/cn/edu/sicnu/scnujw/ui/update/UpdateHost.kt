package cn.edu.sicnu.scnujw.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.sicnu.scnujw.core.update.UpdateManager
import cn.edu.sicnu.scnujw.core.update.updateHint
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 更新网关：包在 `AppScaffold` 外面，弹窗因此能盖住任何页面（包括登录页）。
 *
 * 启动后等 1.5s 再检查，把首屏、会话探活、课表首拉的带宽先让出来；
 * 检查失败静默（只有「我的 → 检查更新」手动触发时才提示原因）。
 */
@Composable
fun UpdateHost(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        delay(1500)
        UpdateCenter.checkNow(ctx, manual = false)
    }
    content()
    UpdateCenter.state?.let { UpdateDialog(it) }
}

@Composable
private fun UpdateDialog(st: UpdateUiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    // 从「允许安装未知应用」设置页返回：授权成功就接着装，失败就保持当前阶段
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = UpdateCenter.state?.pendingFile
        if (file != null && UpdateManager.canInstallPackages(ctx)) {
            install(ctx, file, st.force)
        }
    }

    fun startDownload() {
        val info = st.info
        UpdateCenter.update {
            it.copy(phase = UpdatePhase.Downloading, progress = 0f, message = null)
        }
        scope.launch {
            try {
                val file = UpdateManager.download(ctx, info) { p ->
                    UpdateCenter.update { it.copy(progress = p) }
                }
                UpdateCenter.update { it.copy(pendingFile = file) }
                if (UpdateManager.canInstallPackages(ctx)) {
                    install(ctx, file, st.force)
                } else {
                    UpdateCenter.update { it.copy(phase = UpdatePhase.NeedPermission) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateCenter.update {
                    it.copy(phase = UpdatePhase.Failed, message = updateHint(e))
                }
            }
        }
    }

    val dismissible = !st.force
    val title = when (st.phase) {
        UpdatePhase.Offer ->
            if (st.force) "需要更新到 ${st.info.versionName}" else "发现新版本 ${st.info.versionName}"
        UpdatePhase.Downloading -> "正在下载更新"
        UpdatePhase.NeedPermission -> "需要允许安装应用"
        UpdatePhase.Installing -> "已打开系统安装界面"
        UpdatePhase.Failed -> "更新未完成"
    }

    Dialog(
        onDismissRequest = { if (dismissible) UpdateCenter.dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = ScnuSpacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                Modifier.fillMaxWidth().widthIn(max = 420.dp),
                shape = MaterialTheme.shapes.large,
                color = cs.surface,
                // 与全 App 一致：surface + 1dp 细描边表达层级，不用 tonalElevation / 阴影
                border = BorderStroke(
                    1.dp,
                    cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                ),
            ) {
                Column(
                    Modifier.padding(ScnuSpacing.xl).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ScnuSpacing.m),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                    )

                    when (st.phase) {
                        UpdatePhase.Offer -> OfferBody(st)
                        UpdatePhase.Downloading -> DownloadingBody(st)
                        UpdatePhase.NeedPermission -> Text(
                            "系统要求先允许本应用安装未知来源的应用。打开开关后返回本页，会自动继续安装。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        UpdatePhase.Installing -> Text(
                            "如果没有看到安装提示，点「重新安装」再试一次。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        UpdatePhase.Failed -> {
                            Text(
                                st.message ?: "请稍后重试。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.error,
                            )
                            Text(
                                if (st.pendingFile != null) {
                                    "安装包已下载并通过校验，重试会直接再次调起系统安装。"
                                } else {
                                    "点「重试」会重新下载安装包。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }

                    Actions(
                        st = st,
                        dismissible = dismissible,
                        onPrimary = {
                            when (st.phase) {
                                UpdatePhase.Offer, UpdatePhase.Failed -> startDownload()
                                UpdatePhase.NeedPermission -> runCatching {
                                    settingsLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:${ctx.packageName}"),
                                        ),
                                    )
                                }.onFailure { UpdateManager.openInstallPermissionSettings(ctx) }
                                UpdatePhase.Installing -> st.pendingFile?.let { f ->
                                    install(ctx, f, st.force)
                                }
                                UpdatePhase.Downloading -> Unit
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfferBody(st: UpdateUiState) {
    val cs = MaterialTheme.colorScheme
    val size = sizeText(st.info.sizeBytes)
    Text(
        buildString {
            append("当前 ${UpdateManager.localVersionName()}（${UpdateManager.localVersionCode()}）")
            append(" · 新版本 ${st.info.versionName}（${st.info.versionCode}）")
            if (size != null) append(" · $size")
        },
        style = MaterialTheme.typography.labelMedium,
        color = cs.onSurfaceVariant,
    )
    if (st.force) {
        Text(
            "该版本已不再受支持，请更新后继续使用。",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
        )
    }
    if (st.info.notes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(ScnuSpacing.xs)) {
            st.info.notes.forEach { line ->
                Row {
                    Text("·", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(ScnuSpacing.xs))
                    Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                }
            }
        }
    }
    Text(
        "下载完成后会打开系统安装界面，需要你手动确认安装。",
        style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant,
    )
}

@Composable
private fun DownloadingBody(st: UpdateUiState) {
    val cs = MaterialTheme.colorScheme
    LinearProgressIndicator(
        progress = { st.progress },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "正在下载 ${(st.progress * 100).toInt()}%",
        style = MaterialTheme.typography.labelMedium,
        color = cs.onSurfaceVariant,
    )
}

@Composable
private fun Actions(
    st: UpdateUiState,
    dismissible: Boolean,
    onPrimary: () -> Unit,
) {
    val primaryText = when (st.phase) {
        UpdatePhase.Offer -> "立即更新"
        UpdatePhase.Downloading -> null
        UpdatePhase.NeedPermission -> "去设置"
        UpdatePhase.Installing -> "重新安装"
        UpdatePhase.Failed -> "重试"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (st.phase == UpdatePhase.Downloading) {
            TextButton(
                onClick = { UpdateCenter.dismiss() },
                shape = MaterialTheme.shapes.small,
            ) { Text("取消", style = MaterialTheme.typography.labelMedium) }
        } else {
            if (dismissible) {
                TextButton(
                    onClick = { UpdateCenter.dismiss() },
                    shape = MaterialTheme.shapes.small,
                ) { Text("稍后", style = MaterialTheme.typography.labelMedium) }
            }
            if (primaryText != null) {
                Button(
                    onClick = onPrimary,
                    shape = MaterialTheme.shapes.small,
                ) { Text(primaryText, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

private fun install(ctx: android.content.Context, file: File, force: Boolean) {
    runCatching { UpdateManager.install(ctx, file) }
        .onSuccess {
            if (force) {
                // 强制更新：保留弹窗，用户取消系统安装后还能再点「重新安装」
                UpdateCenter.update { it.copy(phase = UpdatePhase.Installing) }
            } else {
                UpdateCenter.dismiss()
            }
        }
        .onFailure {
            UpdateCenter.update {
                it.copy(phase = UpdatePhase.Failed, message = "无法打开系统安装界面")
            }
        }
}

private fun sizeText(bytes: Long): String? = when {
    bytes <= 0L -> null
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    else -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
}
