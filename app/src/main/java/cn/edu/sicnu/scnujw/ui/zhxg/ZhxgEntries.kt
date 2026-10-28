package cn.edu.sicnu.scnujw.ui.zhxg

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 智慧学工功能入口的唯一数据源：
 * 「智慧学工」页与「我的」页的学工区共用这一份，避免条目数/图标/副标题各写一遍后漂移。
 * 2026-10-27 起只保留已接入的「假期去向登记」；其余学工业务不列入，避免一排「暂未开放」。
 */
data class ZhxgEntry(
    val label: String,
    val sub: String,
    val icon: ImageVector,
    val route: String,
)

val ZHXG_ENTRIES: List<ZhxgEntry> = listOf(
    ZhxgEntry("假期去向登记", "离校 / 留校 / 返校状态与历史", Icons.Filled.FlightTakeoff, "zhxg_holiday"),
)
