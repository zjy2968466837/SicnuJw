package cn.edu.sicnu.scnujw.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import cn.edu.sicnu.scnujw.BuildConfig
import cn.edu.sicnu.scnujw.R
import cn.edu.sicnu.scnujw.core.auth.AuthGuard
import cn.edu.sicnu.scnujw.core.auth.AuthSessionManager
import cn.edu.sicnu.scnujw.core.auth.AuthSystem
import cn.edu.sicnu.scnujw.core.auth.CasSession
import cn.edu.sicnu.scnujw.core.auth.SessionProbe
import cn.edu.sicnu.scnujw.core.auth.SessionRegistry
import cn.edu.sicnu.scnujw.core.auth.SessionState
import cn.edu.sicnu.scnujw.core.jw.JwClient
import cn.edu.sicnu.scnujw.core.jw.ScoreCache
import cn.edu.sicnu.scnujw.core.jw.StudentInfo
import cn.edu.sicnu.scnujw.core.jw.StudentInfoParser
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.core.net.networkHint
import cn.edu.sicnu.scnujw.core.update.UpdateCheck
import cn.edu.sicnu.scnujw.core.update.updateHint
import cn.edu.sicnu.scnujw.ui.common.ScnuBadgeTone
import cn.edu.sicnu.scnujw.ui.common.ScnuScreenHeader
import cn.edu.sicnu.scnujw.ui.common.ScnuStatusBadge
import cn.edu.sicnu.scnujw.ui.theme.ScnuPalette
import cn.edu.sicnu.scnujw.ui.theme.ScnuSpacing
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens
import cn.edu.sicnu.scnujw.ui.theme.scnuOk
import cn.edu.sicnu.scnujw.ui.update.UpdateCenter
import cn.edu.sicnu.scnujw.ui.zhxg.ZHXG_ENTRIES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 学生信息加载：在线（学生首页→卡片页回退）失败则解析随包样本 */
class ProfileViewModel : ViewModel() {
    var info by mutableStateOf<StudentInfo?>(null)
        private set
    var source by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)     // 已完成一次加载（切回本页不重复拉取）
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load(ctx: Context, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1) 缓存：page_student_info 是固定文件名（不按学号分文件），必须按归属校验后才能上屏
            val cached = ScoreCache.readPage(ctx, ScoreCache.Keys.STUDENT_INFO)
            var cachedShown = false
            if (cached != null) {
                val info = StudentInfoParser.parse(cached.html)
                val owner = ScoreCache.profileCode(ctx)
                // profile.json 记录当前账号；缓存里的学号与它不一致 = 上一账号的数据，忽略并走在线。
                // （owner 为空 = 还没有账号归属信息，此时缓存无法自证，也不能用来写回归属）
                if (info != null && (owner.isNullOrBlank() || owner == info.sno)) {
                    ScoreCache.onAccountKnown(ctx, info.sno, info.name)
                    apply(info, ScoreCache.dailyLabel(cached.fetchedAt))
                    cachedShown = true
                } else if (info != null) {
                    android.util.Log.w(
                        "Profile",
                        "学生信息缓存归属不符（缓存学号=${info.sno} 当前学号=$owner），忽略缓存",
                    )
                }
                // 2026-10-29 用户决定：学生信息卡一次加载后长期使用缓存，不再按天联网。
                // 换号登录会 clearAll，所以归属校验通过就足够安全；只有无缓存时才走在线。
                if (cachedShown && !force) return@launch
            }

            // 2) 在线：学生首页无信息卡时由 fetchStudentIndex 内部回退到卡片页
            var onlineFailure: Exception? = null
            val onlineDoc = try {
                JwClient.fetchStudentIndex()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onlineFailure = e
                null
            }
            val online = onlineDoc?.let { StudentInfoParser.parse(it) }
            if (online != null) {
                ScoreCache.writePage(ctx, ScoreCache.Keys.STUDENT_INFO, onlineDoc.html())
                ScoreCache.onAccountKnown(ctx, online.sno, online.name)
                apply(online, "实时")
                return@launch
            }
            // 3) 在线失败：只有「已确认属于当前账号的缓存」才保留上屏值；
            //    缓存被归属校验挡掉时明确错误态——绝不把上一账号的姓名/学号/学院留在屏幕上
            if (cachedShown) return@launch

            error = "未能获取学生信息。" +
                networkHint(onlineFailure ?: IllegalStateException("学生信息请求失败"))
            loaded = true
        }
    }

    private fun apply(i: StudentInfo, src: String) { info = i; source = src; loaded = true }
}

/** 「我的」页：学生证式信息卡 + 功能入口 + 会话管理 */
@Composable
fun ProfileScreen(nav: NavController) {
    val ctx = LocalContext.current
    val vm: ProfileViewModel = viewModel()
    LaunchedEffect(Unit) { if (!vm.loaded) vm.load(ctx) }

    Column(Modifier.fillMaxSize()) {
        ScnuScreenHeader(title = "我的")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(ScnuSpacing.l),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.l),
        ) {
            StudentCard(vm)
            AppearanceSection()
            ZhxgSection(nav)
            FeatureEntries(nav)
            RepoSection()
            QuickLinksSection()
            SessionSection(nav)
            ProfileFooter()
        }
    }
}

/* ─────────────────────────── 页脚 ─────────────────────────── */

private const val QQ_GROUP = "792678709"
private const val QQ_GROUP_SCHEME =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=group&uin=$QQ_GROUP"

/**
 * 页脚触控区高度（组件几何，见 UI_DESIGN 2.9「页脚单行」）：
 * 比 11sp 文字高一大截、可稳定点中，但整块页脚仍收在一行 40dp 内。
 */
private val FooterTouchHeight = 40.dp

/**
 * 页脚：一行次级信息。三段 = 版本（不可点）· 检查更新 · 反馈群。
 *
 * 视觉上只有 labelSmall / onSurfaceVariant 的纯文字：不加按钮、下划线、卡片或分区标题，
 * 可点段「就是那一小段文字」，点击反馈只靠水波纹；段间用「·」分隔（见 UI_DESIGN 3.9.1）。
 * 版本行本身已经表达「这是当前安装的版本」，因此不再单列「开发中…」一类状态句。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileFooter() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.labelSmall
    val cs = MaterialTheme.colorScheme

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(ScnuSpacing.xs),
    ) {
        // 三段与分隔都是同一个 40dp 高的盒子，整行高度因此恒等于触控区高度
        Box(
            Modifier.heightIn(min = FooterTouchHeight).padding(horizontal = ScnuSpacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = style,
                color = cs.onSurfaceVariant,
                maxLines = 1,
            )
        }
        FooterDot()
        FooterAction(
            text = "检查更新",
            onClickLabel = "手动检查更新",
            // 检查中：只换这一段文案，三段位置不动，整行不会跳动
            busyText = if (checking) "正在检查更新…" else null,
            enabled = !checking,
        ) {
            scope.launch {
                checking = true
                val result = UpdateCenter.checkNow(ctx)
                checking = false
                val message = when (result) {
                    is UpdateCheck.Available -> null    // 弹窗由 UpdateHost 接管
                    UpdateCheck.UpToDate -> "已是最新版本"
                    UpdateCheck.Disabled -> "当前版本未配置更新服务器"
                    is UpdateCheck.Failed -> updateHint(result.cause)
                }
                message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            }
        }
        FooterDot()
        FooterAction(
            text = "反馈群",
            onClickLabel = "复制群号并打开 QQ",
            // 群号不再占屏幕：把它放在无障碍标签里，视力用户点一次即可复制
            contentDescription = "反馈群 $QQ_GROUP",
            enabled = true,
        ) { copyQqGroupAndOpen(ctx) }
    }
}

/** 段分隔：纯装饰的「·」，不进无障碍树。 */
@Composable
private fun FooterDot() {
    Box(
        Modifier.heightIn(min = FooterTouchHeight).padding(horizontal = ScnuSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "·",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * 页脚可点段：视觉是纯文字，触控区撑到 [FooterTouchHeight]（整块页脚仍 ≤ 40dp）。
 * [busyText] 非空时显示进行中文案（同一动作全流程同名，见 5.2）。
 */
@Composable
private fun FooterAction(
    text: String,
    onClickLabel: String,
    contentDescription: String? = null,
    busyText: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val descModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Box(
        modifier = Modifier
            .heightIn(min = FooterTouchHeight)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = ScnuSpacing.xs)
            .then(descModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            busyText ?: text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun copyQqGroupAndOpen(ctx: Context) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("QQ群号", QQ_GROUP))

    val uri = Uri.parse(QQ_GROUP_SCHEME)
    val candidates = listOf("com.tencent.mobileqq", "com.tencent.tim", "com.tencent.mobileqqi")
    val opened = candidates.any { pkg ->
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.packageManager) == null) {
            false
        } else {
            runCatching { ctx.startActivity(intent) }.isSuccess
        }
    }

    Toast.makeText(
        ctx,
        if (opened) "群号已复制，正在打开 QQ" else "群号已复制，请安装 QQ 后搜索加入",
        Toast.LENGTH_LONG,
    ).show()
}

/* ─────────────────────────── 外观 ─────────────────────────── */

/** 外观设置：主题模式三态（跟随系统/浅色/深色），即时生效并持久化 */
@Composable
private fun AppearanceSection() {
    Column {
        Text("外观", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
        // 标题与卡片 8dp、卡内标签与控件 8dp：都走 ScnuSpacing，不再出现 6dp 漂移值
        Spacer(Modifier.height(ScnuSpacing.s))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            border = androidx.compose.foundation.BorderStroke(
                ScnuTokens.CardBorder,
                MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
            ),
        ) {
            Column(Modifier.padding(ScnuSpacing.m)) {
                Text("主题模式", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(ScnuSpacing.s))
                cn.edu.sicnu.scnujw.ui.theme.ThemeModeSwitcher()
            }
        }
    }
}

/* ─────────────────────────── 学生证卡 ─────────────────────────── */

/**
 * 学生证卡：瓶绿渐变底 + 右上角银杏叶暗纹（金色 alpha 0.2）。
 * 卡面固定深色，因此文字/标签直接取 D_* 色，不随浅色主题反转。
 */
@Composable
private fun StudentCard(vm: ProfileViewModel) {
    val info = vm.info
    val shape = MaterialTheme.shapes.large
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(ScnuPalette.PINE_GRADIENT))
            .border(ScnuTokens.CardBorder, ScnuPalette.D_INK.copy(alpha = 0.12f), shape),
    ) {
        // 装饰先画，避免压住姓名与信息文字
        Canvas(Modifier.matchParentSize()) {
            val gold = ScnuPalette.D_ACCENT.copy(alpha = 0.2f)
            drawGinkgoLeaf(
                Offset(size.width - 8.dp.toPx(), 48.dp.toPx()),
                46.dp.toPx(), -18f, gold,
            )
            drawGinkgoLeaf(
                Offset(size.width - 54.dp.toPx(), 10.dp.toPx()),
                30.dp.toPx(), 24f, gold,
            )
            drawGinkgoLeaf(
                Offset(size.width - 22.dp.toPx(), 86.dp.toPx()),
                18.dp.toPx(), 70f, gold,
            )
        }

        Column(Modifier.padding(ScnuSpacing.l),
            verticalArrangement = Arrangement.spacedBy(ScnuSpacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudentPhoto(info?.photoUrl, info?.sno, Modifier.size(76.dp))
                Spacer(Modifier.width(ScnuSpacing.l))
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.name ?: "未获取",
                        // 中文姓名用中文页头字号；displayMedium 是 Barlow 数字样式，中文会回退
                        style = MaterialTheme.typography.headlineSmall,
                        color = ScnuPalette.D_INK,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        info?.sno?.let { "学号 $it" } ?: "学号 --",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ScnuPalette.D_MUTED,
                    )
                }
            }

            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(ScnuPalette.D_INK.copy(alpha = 0.12f)),
            )

            if (info != null) {
                InfoGrid(info)
            } else if (vm.error != null) {
                Text("学生信息未载入", style = MaterialTheme.typography.titleSmall,
                    color = ScnuPalette.D_INK)
                Text(
                    vm.error ?: "学生信息没有取到。",
                    style = MaterialTheme.typography.labelSmall,
                    color = ScnuPalette.D_ERROR,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ScnuPalette.D_MUTED,
                    )
                    Spacer(Modifier.width(ScnuSpacing.s))
                    Text(
                        "正在读取学生信息…",
                        style = MaterialTheme.typography.labelSmall,
                        color = ScnuPalette.D_MUTED,
                    )
                }
            }
        }
    }
}

/** 信息网格：label 灰绿、value 骨白，两列排布 */
@Composable
private fun InfoGrid(info: StudentInfo) {
    val fields = listOf(
        "学院" to info.college,
        "专业" to info.major,
        "年级" to info.grade,
        "班级" to info.clazz,
        "学制" to info.eduLength,
        "性别" to info.gender,
        "类型" to info.type,
    )
    Column(verticalArrangement = Arrangement.spacedBy(ScnuSpacing.m)) {
        fields.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.l)) {
                pair.forEach { (label, value) ->
                    InfoCell(label, value, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ScnuPalette.D_MUTED)
        Spacer(Modifier.height(2.dp))
        Text(
            value.ifBlank { "--" },
            style = MaterialTheme.typography.bodyMedium,
            color = ScnuPalette.D_INK,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 证件照（jwc.sicnu.edu.cn/stdPhoto，https 直出无需会话）：76dp、12dp 圆角、1.5dp 金描边。
 *
 * 缓存口径：照片缓存与成绩缓存同生命周期（ScoreCache 的 score_cache/student_photo.img +
 * 一行属主学号，换号登录 onAccountKnown -> clearAll 一并清空），读缓存优先于联网——
 * 证件照稳定，磁盘上有且属于当前账号就直接 decode 上屏、不再发请求，无网时也能看到上次的照片；
 * 只有无缓存 / 归属不符时才下载，成功后落盘供下次离线显示。任何失败都静默降级为占位图标。
 *
 * 归属与内存失效（与学生证卡文字字段同口径）：
 *  - [owner] 是学生信息卡解析出的学号，已经过 ProfileViewModel 的归属校验；读缓存要求照片属主
 *    与它、以及 profile.json 记账学号一致，不一致时既不读缓存也不下载（换号途中 info 还是旧账号，
 *    等新 info 应用后 LaunchedEffect 会重跑）；
 *  - [ScoreCache.generation] 变化 = clearAll 换号清空，已经解码在内存里的 Bitmap 同步作废，
 *    不会出现「磁盘已删、屏幕上还是上一账号的脸」。
 */
@Composable
private fun StudentPhoto(url: String?, owner: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // 只在换号清空（代次变化）时丢弃已解码的照片；url 从 null 变为真实地址不重置，
    // 缓存照片不会先闪一下占位图标。
    val generation by ScoreCache.generation.collectAsState()
    var bmp by remember(generation) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, owner, generation) {
        // 0) 归属：与卡片文字字段同一口径。owner 与 profile.json 记账学号不一致，
        //    说明换号正在进行、页面上的 info 还是旧账号的，先不读不下载；新 info 应用后本 effect 会重跑。
        //    owner 为空 = 学生信息未载入，此时用记账学号读缓存照片（进页先显示上次的照片）。
        val account = withContext(Dispatchers.IO) {
            val recorded = ScoreCache.profileCode(ctx)
            when {
                owner.isNullOrBlank() -> recorded
                recorded.isNullOrBlank() -> owner
                recorded == owner -> owner
                else -> null
            }
        }
        if (account == null) {
            // 归属对不上 / 无从自证：不读不下载，也不把已解码的旧照片留在屏幕上
            bmp = null
            return@LaunchedEffect
        }

        // 1) 缓存优先：有属于当前账号的照片就上屏，不再联网（离线同样可见）
        val cached = withContext(Dispatchers.IO) {
            ScoreCache.readStudentPhoto(ctx, account)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        if (cached != null) {
            bmp = cached
            return@LaunchedEffect
        }
        // 2) 缓存里没有当前账号的照片：先把上一张清掉再决定是否下载，
        //    否则换号/清空后的网络等待期间，屏幕上会一直挂着旧照片。
        bmp = null
        // 只有无缓存且拿得到 https 地址时才联网；照片地址是直出链接，非 http 开头说明地址缺失
        if (url.isNullOrBlank() || !url.startsWith("http")) return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                SessionManager.client.newCall(Request.Builder().url(url).build())
                    .execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes() else null
                    }
            }.getOrNull()
        } ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } ?: return@LaunchedEffect
        bmp = decoded
        withContext(Dispatchers.IO) { ScoreCache.writeStudentPhoto(ctx, bytes, account) }
    }
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .clip(shape)
            .background(ScnuPalette.D_SURFACE_2)
            .border(1.5.dp, ScnuPalette.D_ACCENT, shape),
        contentAlignment = Alignment.Center,
    ) {
        val current = bmp
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "证件照",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = "暂无照片",
                modifier = Modifier.size(34.dp),
                tint = ScnuPalette.D_MUTED,
            )
        }
    }
}

/* ─────────────────────────── 功能入口 ─────────────────────────── */

/** 功能入口：route 为空表示跳官方 WebView（暂未做原生页） */
private data class FeatureEntry(val label: String, val sub: String, val icon: ImageVector, val route: String)

private val FEATURES = buildList {
    add(FeatureEntry("学习进度", "各类学分 已修 / 应修", Icons.Filled.School, "progress"))
    add(FeatureEntry("选课结果", "本学期选中课程明细", Icons.Filled.Checklist, "elect"))
    add(FeatureEntry("空闲教室", "按时间/地点查空教室", Icons.Filled.Schedule, "roomfree"))
    add(FeatureEntry("诊断日志", "抓取页面响应 / 导出日志", Icons.Filled.BugReport, "diag"))
    add(FeatureEntry("证明打印", "学籍 / 成绩证明（官方页）", Icons.Filled.Print, ""))
}

/**
 * 开源入口：与「功能入口」同构的单独区域（同一张卡、同 56dp 行高、左侧图标 + 右侧箭头），
 * 点击用系统浏览器打开仓库。仓库地址是公开信息。
 */
@Composable
private fun RepoSection() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column {
        Text("开源", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
        Spacer(Modifier.height(ScnuSpacing.s))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cs.surface,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { openRepo(ctx) }
                    .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_github),
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(ScnuSpacing.m))
                Column(Modifier.weight(1f)) {
                    Text("GitHub 仓库", style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                    Text(
                        "源码 · 版本发布 · 问题反馈",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = cs.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private const val REPO_URL = "https://github.com/zjy2968466837/SicnuJw"

private fun openRepo(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Toast.makeText(ctx, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun FeatureEntries(nav: NavController) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text("功能入口", style = MaterialTheme.typography.titleMedium,
            color = cs.onBackground)
        Spacer(Modifier.height(ScnuSpacing.s))
        // 不用 Card 阴影：surface + 1dp 细描边表达层级
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cs.surface,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
        ) {
            Column {
                FEATURES.forEachIndexed { i, f ->
                    val enabled = f.route.isNotEmpty()
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(enabled = enabled) { nav.navigate(f.route) }
                            .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            f.icon,
                            contentDescription = null,
                            tint = if (enabled) cs.primary else cs.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(ScnuSpacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
                            )
                            Text(f.sub, style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant)
                        }
                        if (enabled) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = null,
                                tint = cs.outline, modifier = Modifier.size(20.dp))
                        } else {
                            // 未接入的功能不做「点了弹 Toast」的假动作，直接标注状态
                            ScnuStatusBadge("暂未开放", tone = ScnuBadgeTone.Muted)
                        }
                    }
                    if (i < FEATURES.lastIndex) {
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

/* ─────────────────────────── 智慧学工 ─────────────────────────── */

/** 智慧学工入口区：与「智慧学工」页共用 [ZHXG_ENTRIES]，条目数/图标/副标题不会再漂移 */
@Composable
private fun ZhxgSection(nav: NavController) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.School, contentDescription = null, tint = cs.primary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(ScnuSpacing.s))
            Text("智慧学工", style = MaterialTheme.typography.titleMedium,
                color = cs.onBackground)
        }
        Spacer(Modifier.height(ScnuSpacing.s))
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
                            Text(e.label, style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurface)
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

/* ─────────────────────────── 快速入口 ─────────────────────────── */

/**
 * 外部快捷入口：只有 url，点击后交给系统默认浏览器。
 * 不再承载微信小程序（`#小程序://` 只有微信内能解析，客户端拼不出可用的 URL Scheme）。
 */
private data class QuickLink(
    val label: String,
    val sub: String,
    val icon: ImageVector,
    val url: String,
)

private val QUICK_LINKS = listOf(
    QuickLink("信息化服务", "校园VPN/正版软件平台/学术资源导航", Icons.Filled.Public,
        url = "https://nic.sicnu.edu.cn/p/38/"),
    QuickLink("教务处", "通知公告与教务资讯", Icons.Filled.AccountBalance,
        url = "https://jwc.sicnu.edu.cn/MoreCan.aspx"),
    QuickLink("图书馆", "馆藏查询与借阅服务", Icons.Filled.LocalLibrary,
        url = "https://lib.sicnu.edu.cn/index"),
)

@Composable
private fun QuickLinksSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Column {
        Text("快速入口", style = MaterialTheme.typography.titleMedium,
            color = cs.onBackground)
        Spacer(Modifier.height(ScnuSpacing.s))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cs.surface,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
        ) {
            Column {
                QUICK_LINKS.forEachIndexed { i, link ->
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button) {
                                openInBrowser(ctx, link.url)
                            }
                            .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            link.icon,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(ScnuSpacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(link.label, style = MaterialTheme.typography.bodyLarge,
                                color = cs.onSurface)
                            Text(link.sub, style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant)
                        }
                        // 外链标志：这些行会离开 APP，不用 ChevronRight 假装是站内跳转。
                        // 它是「会离开当前 App」的信息性图形，取 onSurfaceVariant（outline 太淡，
                        // 低于非文本对比度 3:1，看不见就等于没标）。
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "在外部打开",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (i < QUICK_LINKS.lastIndex) {
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

private fun openInBrowser(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(ctx, "没有可打开网页的应用", Toast.LENGTH_SHORT).show()
    }
}

/* ─────────────────────────── 登录状态 ─────────────────────────── */

/** 状态圆点直径（组件几何，见 UI_DESIGN 2.9「登录状态卡」；不是徽章内距 6dp 的复用）。 */
private val SessionDotSize = 6.dp

/**
 * 登录状态卡 = 会话仪表，而不是设置列表：
 *  ① 状态主语：卡名退为标签，整体健康用一句话做视觉锚点（sessionHeadline）；
 *  ② 健康条：三段，一段一个系统，颜色即状态，只做整体分布指示；
 *  ③ 系统行：圆点 + 系统名 + 状态文字 +「验证」，三行同一节奏，可扫；
 *  ④ 操作行：「刷新状态」「退出登录」都是文字动作，权重低于系统行。
 * 状态全部来自 SessionRegistry.statesFlow，判定口径与 SessionProbe 一致，不写死。
 */
@Composable
private fun SessionSection(nav: NavController) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }

    // 订阅状态快照：冷启动探活/静默换票在后台改状态时，这一页要跟着重组
    val states by SessionRegistry.statesFlow.collectAsState()
    // 「有票据」判定与 SessionProbe 同口径（sid / CASTGC / jwcxk、zhxg 域 Cookie）。
    // 完全没有凭据时 UNKNOWN 才是「未验证」；有凭据时 UNKNOWN 只表示「还没探过」，文案必须区分。
    val hasCredential = SessionProbe.hasCredential()
    val needProbe = hasCredential &&
        AuthSystem.entries.any { (states[it] ?: SessionState.UNKNOWN) == SessionState.UNKNOWN }
    // 有凭据但还没探过 → 触发一次后台探活；SessionProbe 只在冷却内去重，探完可再探（登出→重登能重新探活）
    LaunchedEffect(needProbe) { if (needProbe) SessionProbe.kick(ctx) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = cs.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(ScnuTokens.CardBorder, cs.outline),
    ) {
        Column {
            // ① 状态主语 + ② 健康条：整体健康先于明细，是这张卡的视觉锚点
            Column(
                Modifier.padding(
                    start = ScnuSpacing.l,
                    end = ScnuSpacing.l,
                    top = ScnuSpacing.m,
                    bottom = ScnuSpacing.m,
                ),
            ) {
                // 卡名只承担「这是什么」，不再是卡里最大的字
                Text(
                    "登录状态",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(ScnuSpacing.xs))
                Text(
                    sessionHeadline(states, hasCredential, refreshing),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                )
                Spacer(Modifier.height(ScnuSpacing.s))
                SessionHealthBar(states)
            }

            // 概览与系统行之间一条 hairline：概览 / 系统行 / 操作三段靠分隔线立住
            HorizontalDivider(
                thickness = ScnuTokens.Hairline,
                color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
            )

            // ② 系统行：分隔线只出现在行间（hairline），行高 48dp
            AuthSystem.entries.forEachIndexed { index, system ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = ScnuTokens.Hairline,
                        color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                    )
                }
                SessionStatusRow(
                    system = system,
                    state = states[system] ?: SessionState.UNKNOWN,
                    hasCredential = hasCredential,
                    onVerify = {
                        nav.navigate(
                            "auth?system=${system.name.lowercase()}&mode=reauth&ret=profile"
                        )
                    },
                )
            }

            if (!CasSession.isPersistentAvailable()) {
                Text(
                    "本机密钥库不可用：登录状态只保存在内存，重启后需要重新登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.error,
                    modifier = Modifier.padding(
                        start = ScnuSpacing.l,
                        end = ScnuSpacing.l,
                        bottom = ScnuSpacing.s,
                    ),
                )
            }

            // ③ 卡片级操作：hairline 隔开，权重低于系统行——两者都只是文字动作，
            //    不再用描边按钮，避免和行内「验证」抢层级。刷新进度由主句「正在重新检测」承担，
            //    按钮文案保持「刷新状态」不变（同一动作全流程同名，见 5.2）。
            HorizontalDivider(
                thickness = ScnuTokens.Hairline,
                color = cs.outline.copy(alpha = ScnuTokens.OutlineAlpha),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScnuSpacing.l, vertical = ScnuSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !refreshing,
                    onClick = {
                        refreshing = true
                        scope.launch {
                            // 刷新 = 本地复验 + 按需静默换票（结果写回 SessionRegistry）
                            AuthSystem.entries.forEach { system ->
                                runCatching { AuthGuard.ensure(system) }
                                    .onFailure {
                                        android.util.Log.w(
                                            "ProfileRefresh",
                                            "${system.name}: ${it.javaClass.simpleName}: ${it.message}"
                                        )
                                    }
                            }
                            refreshing = false
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    // 0 内距：文字与卡内 16dp 内容栅格对齐；48dp 触控区由 TextButton 自己保证
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
                ) { Text("刷新状态", style = MaterialTheme.typography.labelMedium) }

                TextButton(
                    enabled = !loggingOut,
                    onClick = {
                        loggingOut = true
                        scope.launch {
                            // 退出必须走完本地清理：切页/返回会取消 rememberCoroutineScope，
                            // 半途而废会留下「界面已退出、磁盘上仍有会话」的残留，所以整段不可取消。
                            withContext(NonCancellable) {
                                // 先作废在途的静默换票：否则正在落地的换票会在 logout 之后把 sid 写回去
                                AuthGuard.onSessionCleared()
                                AuthSessionManager.logout(ctx)
                                loggingOut = false
                                // popUpTo(0) 在 navigation 2.9 下找不到 id=0 的目标、是静默 no-op：
                                // 返回栈没清，按返回键就能回到旧账号的课表与个人信息。
                                // 清到图根（与 AuthWebViewScreen/MfaWebViewScreen 同一写法）才会销毁
                                // schedule/profile 的 NavBackStackEntry 及其 ViewModel。
                                nav.navigate("login") {
                                    popUpTo(nav.graph.id) { inclusive = false }
                                }
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                ) { Text(if (loggingOut) "退出中…" else "退出登录",
                    style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/**
 * 健康条：三段，一段一个系统（顺序与系统行一致），段色 = 状态色。
 * 只回答「整体分布」，不承担交互；同一信息由主句和系统行提供，因此不进无障碍树。
 */
@Composable
private fun SessionHealthBar(states: Map<AuthSystem, SessionState>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScnuSpacing.xs)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(ScnuSpacing.xs),
    ) {
        AuthSystem.entries.forEach { system ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        color = sessionStateColor(states[system] ?: SessionState.UNKNOWN),
                        // 胶囊收圆：进度条同一例外（RoundedCornerShape 只允许 height / 2）
                        shape = RoundedCornerShape(ScnuSpacing.xs / 2),
                    ),
            )
        }
    }
}

/**
 * 系统行：状态圆点（色）+ 系统名（主）+ 状态文字 +「验证」文字动作。
 * 圆点只是颜色的快捷通道，可读信息仍是状态文字；行高 48dp。
 * 状态文字紧贴「验证」，三行的状态列因此右对齐；系统名拿剩余宽度，字体放大时先省略自己。
 */
@Composable
private fun SessionStatusRow(
    system: AuthSystem,
    state: SessionState,
    hasCredential: Boolean,
    onVerify: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // end 只留 8dp：后面 TextButton 自带 8dp 内距，文字右缘落在 16dp 栅格上
            .padding(start = ScnuSpacing.l, end = ScnuSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(SessionDotSize)
                .clip(CircleShape)
                .background(sessionStateColor(state)),
        )
        Spacer(Modifier.width(ScnuSpacing.m))
        Text(
            systemDisplayName(system),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(ScnuSpacing.s))
        Text(
            stateLabel(state, hasCredential),
            style = MaterialTheme.typography.labelMedium,
            color = sessionStateColor(state),
            maxLines = 1,
        )
        // 文字动作（无容器）：视觉上明显低于卡片级操作，触控区仍是 48dp。
        // 无障碍标签挂在按钮内部文字上，才能被按钮节点合并成「验证教务系统」这类动作名。
        TextButton(
            onClick = onVerify,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = ScnuSpacing.s),
            colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
        ) {
            Text(
                "验证",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics {
                    contentDescription = "验证${systemDisplayName(system)}"
                },
            )
        }
    }
}

/**
 * 状态色（圆点 / 健康条分段 / 状态文字共用这一条映射，红绿直觉）：
 *  - 可用（ALIVE）= 语义绿 [scnuOk]：绿只服务登录状态，金继续承担品牌重音，两者不混用；
 *  - 已失效 / 需二次验证 / 需登录 = error；
 *  - 未探明 = 次要文字色：UNKNOWN（「检测中…」/「未验证」）与 INTERACTIVE（「验证中」，WebView 正在验证）
 *    都还没出结果，不能提前报绿；主句也把这两种计入「检测中」。
 * 颜色只是加速识别，结论仍由状态文字与卡内主句承担。
 */
@Composable
@ReadOnlyComposable
private fun sessionStateColor(state: SessionState): Color {
    val cs = MaterialTheme.colorScheme
    return when (state) {
        SessionState.ALIVE -> MaterialTheme.scnuOk
        SessionState.DEAD, SessionState.NEED_MFA, SessionState.NEED_PASSWORD -> cs.error
        SessionState.UNKNOWN, SessionState.INTERACTIVE -> cs.onSurfaceVariant
    }
}

/**
 * 状态主语：一句话说清整体健康，计数全部来自真实 states，不写死。
 * 全好 / 全未验证 / 全检测中各有一句；混合时先说需要处理的，再报可用的。
 * [refreshing] 是本卡的本地刷新态，优先于快照——否则点了「刷新状态」后要等换票回来才有反馈。
 */
private fun sessionHeadline(
    states: Map<AuthSystem, SessionState>,
    hasCredential: Boolean,
    refreshing: Boolean,
): String {
    if (refreshing) return "正在重新检测"
    val total = AuthSystem.entries.size
    var alive = 0
    var needVerify = 0
    var checking = 0
    var unverified = 0
    AuthSystem.entries.forEach { system ->
        when (states[system] ?: SessionState.UNKNOWN) {
            SessionState.ALIVE -> alive++
            SessionState.DEAD, SessionState.NEED_MFA, SessionState.NEED_PASSWORD -> needVerify++
            SessionState.INTERACTIVE -> checking++
            // 「检测中 / 未验证」与行内文案同口径：有凭据才是「检测中…」
            SessionState.UNKNOWN -> if (hasCredential) checking++ else unverified++
        }
    }
    return when {
        needVerify == 0 && checking == 0 && unverified == 0 -> "$total 个系统都可用"
        unverified == total -> "$total 个系统未验证"
        checking == total -> "$total 个系统检测中"
        else -> buildList {
            if (needVerify > 0) add("$needVerify 个需验证")
            if (checking > 0) add("$checking 个检测中")
            if (unverified > 0) add("$unverified 个未验证")
            if (alive > 0) add("$alive 个可用")
        }.joinToString("，")
    }
}

/**
 * 系统行名用用户词（教务系统/课表系统），core 里的 label（教务（正方）/课表（树维））
 * 是工程名，只用于诊断，不上界面（UI_DESIGN 词表）。
 */
private fun systemDisplayName(system: AuthSystem): String = when (system) {
    AuthSystem.ZF -> "教务系统"
    AuthSystem.JWCXK -> "课表系统"
    AuthSystem.ZHGX -> "智慧学工"
}

/**
 * 状态文案。UNKNOWN 的语义是「还没探过」，不是「验证失败」：
 * 有凭据（[SessionProbe.hasCredential]：sid / CASTGC / 下游域名 Cookie）时说明后台探活
 * 还没回来（检测中…），完全没有凭据才是「未验证」。
 */
private fun stateLabel(state: SessionState, hasCredential: Boolean): String = when (state) {
    SessionState.ALIVE -> "可用"
    SessionState.DEAD -> "已失效"
    SessionState.NEED_MFA -> "需二次验证"
    SessionState.NEED_PASSWORD -> "需登录"
    SessionState.INTERACTIVE -> "验证中"
    SessionState.UNKNOWN -> if (hasCredential) "检测中…" else "未验证"
}

/* ─────────────────────────── 银杏叶装饰 ─────────────────────────── */

/** 扇面银杏叶：底端为叶柄，顶端带浅缺口。坐标以叶柄为原点，向上生长。 */
private fun DrawScope.drawGinkgoLeaf(
    origin: Offset,
    radius: Float,
    rotation: Float,
    color: Color,
) {
    val r = radius
    val leaf = Path().apply {
        moveTo(0f, 0f)
        cubicTo(-0.18f * r, -0.30f * r, -0.70f * r, -0.62f * r, -0.92f * r, -0.98f * r)
        quadraticBezierTo(-1.02f * r, -1.14f * r, -0.72f * r, -1.16f * r)
        quadraticBezierTo(-0.34f * r, -1.18f * r, -0.10f * r, -0.92f * r)
        quadraticBezierTo(-0.03f * r, -0.84f * r, 0f, -0.80f * r)
        quadraticBezierTo(0.03f * r, -0.84f * r, 0.10f * r, -0.92f * r)
        quadraticBezierTo(0.34f * r, -1.18f * r, 0.72f * r, -1.16f * r)
        quadraticBezierTo(1.02f * r, -1.14f * r, 0.92f * r, -0.98f * r)
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
            cap = StrokeCap.Round,
        )
    }
}
