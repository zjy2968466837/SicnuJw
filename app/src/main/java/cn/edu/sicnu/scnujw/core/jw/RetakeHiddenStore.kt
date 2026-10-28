package cn.edu.sicnu.scnujw.core.jw

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 「需重修」列表里被用户手动删除（隐藏）的课程。
 *
 * 只影响本机「只看需重修」视图，不改教务数据、不影响全部成绩与 GPA；
 * 文件放在 filesDir/score_cache/ 下，换号时 ScoreCache.clearAll 会随目录一起清空。
 */
object RetakeHiddenStore {
    private const val DIR = "score_cache"
    private const val FILE = "retake_hidden.json"

    private fun file(ctx: Context) = File(File(ctx.filesDir, DIR), FILE)

    fun read(ctx: Context): Set<String> = try {
        val f = file(ctx)
        if (!f.exists()) emptySet()
        else {
            val arr = JSONArray(f.readText())
            buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
    } catch (_: Exception) {
        file(ctx).delete()
        emptySet()
    }

    fun hide(ctx: Context, key: String) {
        if (key.isBlank()) return
        write(ctx, read(ctx) + key)
    }

    fun restoreAll(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    private fun write(ctx: Context, keys: Set<String>) {
        runCatching {
            File(ctx.filesDir, DIR).mkdirs()
            file(ctx).writeText(JSONArray(keys.sorted()).toString())
        }
    }
}
