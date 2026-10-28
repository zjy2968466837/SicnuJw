package cn.edu.sicnu.scnujw.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import cn.edu.sicnu.scnujw.BuildConfig
import cn.edu.sicnu.scnujw.core.net.NetFailure
import cn.edu.sicnu.scnujw.core.net.classifyNetworkFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 一次检查的结果。自动检查只关心 [Available]，手动检查要把三种「没有新版本」分开说 */
sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo, val force: Boolean) : UpdateCheck
    data object UpToDate : UpdateCheck

    /** 构建时没配 `updateManifestUrl`（未部署更新服务器，功能整体关闭） */
    data object Disabled : UpdateCheck
    data class Failed(val cause: Throwable) : UpdateCheck
}

/** 更新链路的可展示错误（文案直接写进弹窗正文，技术细节只进 logcat） */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 应用内更新：拉清单 → 比对版本 → 下载 → 校验 → 交给系统安装器。
 *
 * 安全边界（为什么敢从第三方主机下 APK）：
 *  - 下载后校验「包名 + versionCode + 签名证书与已安装应用完全一致」，
 *    签名不一致直接拒绝——清单或传输被篡改也装不上别人的包；
 *  - 从不静默安装：始终调用系统安装界面，用户自己点「安装」；
 *  - 使用**独立 OkHttpClient（无 CookieJar）**：教务 Cookie / CASTGC 绝不会随请求发到更新主机；
 *  - 清单请求 `no-cache`，不落 HTTP 缓存，避免读到过期版本号。
 *
 * 刷新节流：成功后 24h 内不再自动检查，失败后 1h 再试。节流只作用于**自动检查**；
 * 手动检查（`forceCheck=true`）既不读也不写这个时间戳——用户主动点「检查更新」
 * 不该影响下次启动的自动提示。
 */
object UpdateManager {
    private const val TAG = "Update"
    /**
     * 自动检查的下次允许时间。只有自动检查会写它，手动检查（forceCheck）绝不写。
     *
     * 值从 `next_check_at` 改名为 `auto_check_at`：旧版手动检查成功也会写这个时间戳，
     * 真机 1.1 就是被一次手动检查压住了启动后的自动检查（24h 内不再弹更新）。
     * 换名字让已经中招的设备升级后立即恢复自动提示，不必等旧的 24h 过期。
     */
    private const val DIR = "updates"
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    /** 断点续传的重试次数与间隔：国际线路慢且易被 RST，保留已下载字节继续 */
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 1_500L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun localVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    fun localVersionName(): String = BuildConfig.VERSION_NAME

    /** App 是否被允许安装未知来源应用（Android 8+ 每个应用单独授权） */
    fun canInstallPackages(ctx: Context): Boolean = runCatching {
        ctx.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    fun openInstallPermissionSettings(ctx: Context) {
        val app = ctx.applicationContext
        val ok = runCatching {
            app.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${app.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * 清单地址列表（逗号分隔，按顺序尝试）。
     *
     * - 公开构建只配 GitHub（`releases/latest/download/latest.json`），不含任何自建地址；
     * - 自用构建可以配「自建服务器, GitHub」两个：服务器快，GitHub 兜底。
     * 注意：客户端能连上的地址必然在包里可被找到，这里只做可用性回退，不做地址隐藏。
     */
    internal fun manifestUrls(): List<String> =
        BuildConfig.UPDATE_MANIFEST_URL.split(',')
            .map { it.trim() }
            .filter { it.startsWith("https://") || it.startsWith("http://") }

    /**
     * 检查更新：**每次调用都真正请求一次清单**，没有跨启动的节流。
     *
     * 为什么不再做 24h 节流（2026-10-29 用户明确要求）：自动检查在
     * [cn.edu.sicnu.scnujw.ui.update.UpdateHost] 的 `LaunchedEffect(Unit)` 里，
     * 每次进程启动只跑一次，本身已经是「启动后检查一次」；再加一层持久化节流会让
     * 用户当天第二次打开 App 看不到新版本，反而像是「更新不生效」。清单只有 1KB
     * 量级、走 HTTPS，一次请求的成本远低于漏掉更新的代价。
     * 失败也不退避：下次启动会再试，失败只记日志、不打扰用户（手动检查才提示原因）。
     */
    suspend fun check(ctx: Context): UpdateCheck {
        val urls = manifestUrls()
        if (urls.isEmpty()) return UpdateCheck.Disabled
        return try {
            val info = withContext(Dispatchers.IO) { fetchFirst(urls) }
            if (info.versionCode > localVersionCode()) {
                val force = info.force ||
                    info.minSupportedVersionCode in 1..localVersionCode()
                UpdateCheck.Available(info, force)
            } else {
                UpdateCheck.UpToDate
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败: ${e.javaClass.simpleName}: ${e.message}")
            UpdateCheck.Failed(e)
        }
    }

    /**
     * 取所有可用清单里 versionCode 最大的那个，而不是“谁先响应谁生效”：
     * 服务器镜像万一手工更新落后一版，用户也不会被锁在旧版本上。
     */
    private fun fetchFirst(urls: List<String>): UpdateInfo {
        var best: UpdateInfo? = null
        var last: Exception? = null
        for (u in urls) {
            try {
                val info = fetchManifest(u)
                if (best == null || info.versionCode > best.versionCode) best = info
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "清单不可用（${u.take(48)}…）: ${e.javaClass.simpleName}")
            }
        }
        return best ?: throw (last ?: UpdateException("没有可用的更新清单"))
    }

    private fun fetchManifest(url: String): UpdateInfo {
        val req = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw UpdateException("清单请求失败（HTTP ${resp.code}）")
            val body = resp.body ?: throw UpdateException("清单响应为空")
            val length = body.contentLength()
            if (length > UpdateInfo.MAX_BYTES) throw UpdateException("清单异常")
            val bytes = body.bytes()
            if (bytes.size > UpdateInfo.MAX_BYTES) throw UpdateException("清单异常")
            return try {
                UpdateInfo.parse(bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw UpdateException("清单格式无法识别", e)
            }
        }
    }

    /**
     * 下载并校验安装包；返回的 File 已通过包名 / versionCode / 签名三重校验。
     *
     * 支持断点续传：中断后（国际线路跑十几 MB 常常被 RST）保留 `.part`，
     * 下一次用 `Range: bytes=N-` 从断点接着下，最多重试 [MAX_ATTEMPTS] 次。
     * 这就把「38 KB/s + 6 分钟单连接」从「一次抖动全废」变成可累积完成。
     */
    suspend fun download(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, "SicnuJw-${info.versionCode}.apk")
        if (target.exists() && verifyApk(app, target, info) == null) {
            onProgress(1f)
            return@withContext target
        }

        // 只保留本次要装的包，旧版本残留顺手清掉
        dir.listFiles()?.forEach { if (it != target) it.delete() }

        val tmp = File(dir, "SicnuJw-${info.versionCode}.part")
        var total = info.sizeBytes
        var attempt = 0
        while (true) {
            attempt++
            try {
                total = fetchInto(tmp, info.url, total, onProgress)
                if (total > 0L && tmp.length() != total) {
                    throw IOException("连接中断（${tmp.length()}/$total）")
                }
                if (tmp.length() == 0L) throw IOException("下载内容为空")
                val expected = info.sha256
                if (!expected.isNullOrBlank() && expected != sha256Of(tmp)) {
                    tmp.delete()
                    throw UpdateException("安装包校验失败（摘要不一致）")
                }
                val problem = verifyApk(app, tmp, info)
                if (problem != null) {
                    tmp.delete()
                    throw UpdateException(problem)
                }
                if (!tmp.renameTo(target)) throw UpdateException("安装包保存失败")
                onProgress(1f)
                return@withContext target
            } catch (e: UpdateException) {
                throw e                       // 协议/校验失败，重试解决不了
            } catch (e: IOException) {
                Log.w(TAG, "下载中断（第 $attempt/$MAX_ATTEMPTS 次）: ${e.javaClass.simpleName}: ${e.message}")
                if (attempt >= MAX_ATTEMPTS) throw UpdateException(updateHint(e), e)
                delay(RETRY_DELAY_MS)
            }
        }
        // while(true) 里每条路径都 return 或 throw，这行只为满足类型检查
        throw UpdateException("下载失败")
    }

    /** 单次请求：按已有字节数决定是否带 Range；返回文件总长度（未知时返回 0） */
    private fun fetchInto(
        tmp: File,
        url: String,
        knownTotal: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        val have = if (tmp.isFile) tmp.length() else 0L
        val builder = Request.Builder().url(url)
        if (have > 0L && (knownTotal <= 0L || have < knownTotal)) {
            builder.header("Range", "bytes=$have-")
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 416) return knownTotal      // 已经下完（长度未知的兜底）
            if (!resp.isSuccessful) throw UpdateException("下载失败（HTTP ${resp.code}）")
            val partial = resp.code == 206
            val body = resp.body ?: throw UpdateException("下载响应为空")
            // 服务器忽略 Range 时会返回 200 + 整个文件：丢掉旧片段重来
            if (!partial && have > 0L) tmp.delete()
            val start = if (partial) have else 0L
            val total = when {
                partial -> totalFromContentRange(resp.header("Content-Range")) ?: knownTotal
                else -> body.contentLength().takeIf { it > 0L } ?: knownTotal
            }
            if (total > MAX_APK_BYTES) throw UpdateException("安装包体积异常")
            var read = start
            FileOutputStream(tmp, partial && start > 0L).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read > MAX_APK_BYTES) throw UpdateException("安装包体积异常")
                        if (total > 0L) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            return if (total > 0L) total else read
        }
    }

    /** `Content-Range: bytes 100-999/1000` → 1000；解析不了返回 null */
    private fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    /** 调系统安装界面。必须走 FileProvider：cache 目录直接给 file:// 会被系统拒绝 */
    fun install(ctx: Context, file: File) {
        val app = ctx.applicationContext
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 返回 null 表示校验通过；否则是给用户看的失败原因 */
    private fun verifyApk(ctx: Context, file: File, info: UpdateInfo): String? {
        if (!file.isFile || file.length() == 0L) return "安装包不存在"
        if (info.sizeBytes > 0 && file.length() != info.sizeBytes) return "安装包大小与清单不一致"
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "安装包无法解析"
        if (archive.packageName != ctx.packageName) return "安装包不是本应用"
        if (versionCodeOf(archive) != info.versionCode) return "安装包版本与清单不一致"
        val archiveCerts = signerDigests(archive)
        val installedCerts = installedSignerDigests(pm, ctx.packageName)
        if (installedCerts.isEmpty() || archiveCerts != installedCerts) {
            return "安装包签名与本机应用不一致"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun installedSignerDigests(pm: PackageManager, pkg: String): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching { pm.getPackageInfo(pkg, flags) }.getOrNull() ?: return emptySet()
        return signerDigests(info)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(pi: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.apkContentsSigners
        } else {
            pi.signatures
        }
        return sigs.orEmpty().mapTo(HashSet()) { it.toByteArray().sha256() }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else pi.versionCode.toLong()

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}

/** 更新链路的错误文案：与校园网无关，别复用「请连接校园网」那套 */
fun updateHint(e: Throwable): String {
    (e as? UpdateException)?.message?.let { return it }
    return when (classifyNetworkFailure(e)) {
        NetFailure.NO_NETWORK -> "设备当前没有可用网络。"
        NetFailure.NO_CAMPUS -> "网络连接失败，请稍后重试。"
        NetFailure.UNKNOWN -> "网络请求失败，请稍后重试。"
    }
}
