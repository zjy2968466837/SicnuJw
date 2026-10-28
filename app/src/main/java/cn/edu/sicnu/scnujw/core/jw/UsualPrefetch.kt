package cn.edu.sicnu.scnujw.core.jw

/**
 * 后台预取已于 2026-10-27 移除：全量 `-1` 回发占住 ASP.NET 会话锁，会拖垮其他模块。
 *
 * 平时成绩现在只在页面打开时按学期增量拉取（[UsualRepository.sync]）；
 * 这里保留 no-op 仅为登出/换号调用点兼容，避免它们引用不存在的对象。
 */
object UsualPrefetch {
    fun invalidate() = Unit
}
