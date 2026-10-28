package cn.edu.sicnu.scnujw.ui.exams

import cn.edu.sicnu.scnujw.ui.common.DataIssue
import cn.edu.sicnu.scnujw.ui.common.dataIssueShort

/**
 * 学期代码 → 下拉里的中文名；null/空白/"-1"（教务无学期视图）不当作学期名使用。
 * 未知代码退回原始代码：宁可让用户看到一个陌生编号，也别把「想看哪个学期」说丢了。
 *
 * 抽成顶层纯函数（不碰 ViewModel/Context）是为了让文案分支能被 JVM 单测锁定，
 * 见 ExamTermNoticeTest。
 */
internal fun examTermLabel(code: String?, terms: Map<String, String>): String? =
    code?.takeIf { it.isNotBlank() && it != "-1" }?.let { terms[it] ?: it }

/**
 * 切学期/刷新失败的行内提示文案——必须如实说明「屏幕上的列表到底属于哪个学期」。
 *
 * 三种情况：
 * 1. [cachedShown] = true：要看的那个学期的缓存已经上屏，本次只是联网刷新失败，
 *    说「当前显示该学期的上次缓存」才不误导；
 * 2. [cachedShown] = false 且能拿到 [wanted] 的名称：该学期从未加载成功，屏幕上仍是别的学期，
 *    必须同时点名「想看的」和「正在显示的」（[shown]），否则用户会以为看到的是新学期的安排；
 * 3. 连 [wanted] 都没有（默认视图刷新失败）：只说「显示上次缓存」。
 *
 * [terms] = 学期代码 → 下拉里的中文名；[kind] 决定一句原因（登录失效/无网/无法连校园网/服务端）。
 */
internal fun examTermFailureNotice(
    wanted: String?,
    cachedShown: Boolean,
    shown: String?,
    terms: Map<String, String>,
    kind: DataIssue,
): String {
    val reason = dataIssueShort(kind)
    val want = examTermLabel(wanted, terms)
    if (cachedShown) {
        // 该学期自己的缓存就在屏幕上：说「该学期的上次缓存」才不误导
        return if (want != null) {
            "「$want」刷新失败：$reason，当前显示该学期的上次缓存"
        } else {
            "刷新失败：$reason，当前显示上次缓存"
        }
    }
    if (want == null) return "刷新失败：$reason，当前显示上次缓存"
    val showing = examTermLabel(shown, terms)
    return if (showing != null) {
        "「$want」暂时没有读取成功：$reason，仍显示「$showing」"
    } else {
        // 缓存 html 里拿不到学期名（极少见）：只说清「不是你要的那个学期」
        "「$want」暂时没有读取成功：$reason，仍显示原有考试安排"
    }
}
