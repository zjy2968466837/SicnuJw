package cn.edu.sicnu.scnujw.core.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务端下发的更新清单（`GET /latest.json`）。
 *
 * 清单只负责「有没有新版本、去哪下、改了啥」；**是否可信由 APK 签名决定**——
 * 下载完成后会核对「包名 + versionCode + 签名与已安装应用一致」，
 * 因此清单被篡改最多让用户看到假文案，装不上别人的包。
 *
 * 字段（除 versionCode/url 外均可省）：
 * ```json
 * {
 *   "versionCode": 3,
 *   "versionName": "1.1",
 *   "url": "https://host/SicnuJw-1.1.apk",
 *   "sha256": "…64 hex…",
 *   "size": 14474272,
 *   "notes": ["修复…", "新增…"],
 *   "force": false,
 *   "minSupportedVersionCode": 0
 * }
 * ```
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String?,
    val sizeBytes: Long,
    val notes: List<String>,
    val force: Boolean,
    val minSupportedVersionCode: Long,
) {
    companion object {
        /** 清单体上限：正常不到 1KB，超过这个尺寸按异常处理（不把大块文本读进内存） */
        const val MAX_BYTES = 64 * 1024

        fun parse(json: String): UpdateInfo {
            val o = JSONObject(json)
            val code = o.optLong("versionCode", 0L)
            require(code > 0L) { "清单缺少 versionCode" }
            val url = o.optString("url").trim()
            require(url.startsWith("https://") || url.startsWith("http://")) {
                "清单里的下载地址无效"
            }
            return UpdateInfo(
                versionCode = code,
                versionName = o.optString("versionName").trim().ifBlank { code.toString() },
                url = url,
                sha256 = o.optString("sha256").trim().lowercase().takeIf { it.isNotEmpty() },
                sizeBytes = o.optLong("size", 0L),
                notes = notesOf(o),
                force = o.optBoolean("force", false),
                minSupportedVersionCode = o.optLong("minSupportedVersionCode", 0L),
            )
        }

        /** notes 允许是数组，也允许是整段多行文本；去掉常见项目符号，最多 12 条 */
        private fun notesOf(o: JSONObject): List<String> {
            val raw = o.opt("notes") ?: return emptyList()
            val items = if (raw is JSONArray) {
                List(raw.length()) { raw.optString(it) }
            } else {
                raw.toString().split('\n')
            }
            return items
                .map { it.trim().trimStart('-', '*', '·', '•').trim() }
                .filter { it.isNotEmpty() }
                .take(12)
        }
    }
}
