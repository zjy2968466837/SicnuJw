package cn.edu.sicnu.scnujw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.edu.sicnu.scnujw.core.auth.CasSession
import cn.edu.sicnu.scnujw.core.net.SessionManager
import cn.edu.sicnu.scnujw.ui.dev.DevBrowseScreen
import cn.edu.sicnu.scnujw.ui.exams.ExamsScreen
import cn.edu.sicnu.scnujw.ui.login.AuthWebViewScreen
import cn.edu.sicnu.scnujw.ui.login.LoginScreen
import cn.edu.sicnu.scnujw.ui.login.MfaWebViewScreen
import cn.edu.sicnu.scnujw.ui.profile.ProfileScreen
import cn.edu.sicnu.scnujw.ui.roomfree.RoomFreeScreen
import cn.edu.sicnu.scnujw.ui.schedule.ScheduleScreen
import cn.edu.sicnu.scnujw.ui.scores.ScoresScreen
import cn.edu.sicnu.scnujw.ui.theme.ScnuTokens

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("schedule", "课表", Icons.Filled.CalendarMonth),
    Tab("scores", "成绩", Icons.Filled.Grade),
    Tab("exams", "考试", Icons.Filled.Schedule),
    Tab("profile", "我的", Icons.Filled.Person),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(startDestination: String = "login") {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    // 功能子页仍显示底栏，但高亮「入口 tab」——否则子页上四个 tab 全灭，导航定位消失。
    // 登录/认证类页面不进 barRoutes，底栏隐藏。
    val subTabParent = mapOf(
        "elect" to "profile",
        "progress" to "profile",
        "devbrowse" to "profile",
        "roomfree" to "profile",
        "zhxg" to "profile",
        "zhxg_holiday" to "profile",
        "diag" to "profile",
    )
    val barRoutes = TABS.map { it.route } + subTabParent.keys
    // scores 带查询参数（scores?tab={tab}），前缀匹配才算顶层目的地
    fun isTopLevel(route: String?) = route != null &&
        barRoutes.any { route == it || route.startsWith("$it?") }
    val showBars = isTopLevel(current)
    Scaffold(
        bottomBar = {
            if (showBars) {
                // 必须包一层 Column：Scaffold 会把 bottomBar 的多个 placeable 叠在同一条底边，
                // 直接并列 Divider + NavigationBar 会让分隔线被导航栏盖住。
                Column {
                    // 顶缘细分隔线压住内容渗色（暗/浅色各自 outline）
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = ScnuTokens.OutlineAlpha),
                        thickness = ScnuTokens.Hairline,
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                    TABS.forEach { tab ->
                        val selected = current == tab.route ||
                            current?.startsWith("${tab.route}?") == true ||
                            (current != null && subTabParent[current] == tab.route)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // tab 语义：始终回到该 tab 的根页。
                                // 不用 saveState/restoreState：它会把「被弹出的整段」存到 tab 名下，
                                // 恢复时段顶若是子页（如 我的→考试安排），点「我的」就会落回考试页，
                                // 表现为底栏点击失效（navigation-compose 2.9 restoreStateInternal 语义）。
                                val top = nav.currentBackStack.value.lastOrNull()
                                val atTabRoot = top?.destination?.route == tab.route ||
                                    (tab.route == "scores" &&
                                        top?.destination?.route == "scores?tab={tab}" &&
                                        top.arguments?.getString("tab").isNullOrEmpty())
                                if (!atTabRoot) {
                                    nav.navigate(tab.route) {
                                        popUpTo("schedule") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            // 选中态只用胶囊 + 颜色编码，图标不再放大（避免点击时跳动）
                            icon = { Icon(tab.icon, tab.label, modifier = Modifier.size(24.dp)) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                // 选中 pill：金色低透明容器（深底银杏金/浅底稳金），拒绝默认紫
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(
                                    alpha = ScnuTokens.AccentIndicatorAlpha,
                                ),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            // 持久化会话直接进主界面；有 CAS TGT 也算已登录（下游按需换票）
            AppNavHost(nav, if (SessionManager.sid != null || CasSession.hasTgt()) "schedule" else "login")
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, startDestination: String) {
    NavHost(navController = nav, startDestination = startDestination) {
        composable("login") { LoginScreen(nav) }
        composable("mfa?url={url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
            MfaWebViewScreen(nav, entry.arguments?.getString("url") ?: "")
        }
        // jwcxk（课表系统）SSO 中转：完成落地 /student/home 后按 ret 返回发起页
        // （ret=roomfree 时空闲教室页会带新会话重新初始化）
        composable("webauth?url={url}&ret={ret}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("ret") { type = NavType.StringType; defaultValue = "schedule" },
            )) { entry ->
            MfaWebViewScreen(nav, entry.arguments?.getString("url") ?: "",
                donePattern = "student/home",
                returnRoute = entry.arguments?.getString("ret") ?: "schedule")
        }
        // 统一验证入口：system=zf|jwcxk|zhxg, mode=full|reauth, ret=目标路由
        composable("auth?system={system}&mode={mode}&ret={ret}",
            arguments = listOf(
                navArgument("system") { type = NavType.StringType; defaultValue = "zf" },
                navArgument("mode") { type = NavType.StringType; defaultValue = "full" },
                navArgument("ret") { type = NavType.StringType; defaultValue = "schedule" },
            )) { entry ->
            AuthWebViewScreen(
                nav,
                entry.arguments?.getString("system") ?: "zf",
                entry.arguments?.getString("mode") ?: "full",
                entry.arguments?.getString("ret") ?: "schedule",
            )
        }
        composable("schedule") { ScheduleScreen(nav = nav) }
        composable("scores?tab={tab}",
            arguments = listOf(navArgument("tab") {
                type = NavType.StringType; defaultValue = ""
            })) { entry ->
                ScoresScreen(entry.arguments?.getString("tab") ?: "", nav = nav)
            }
        composable("exams") { ExamsScreen(nav = nav) }
        composable("devbrowse?url={url}",
            arguments = listOf(navArgument("url") {
                type = NavType.StringType; defaultValue = ""
            })) { entry -> DevBrowseScreen(entry.arguments?.getString("url") ?: "", nav) }
        composable("profile") { ProfileScreen(nav) }
        composable("elect") { cn.edu.sicnu.scnujw.ui.enroll.ElectResultScreen(nav = nav) }
        composable("progress") { cn.edu.sicnu.scnujw.ui.academics.ProgressScreen(nav = nav) }
        composable("roomfree") { RoomFreeScreen(nav = nav) }
        composable("zhxg") { cn.edu.sicnu.scnujw.ui.zhxg.ZhxgHomeScreen(nav) }
        composable("zhxg_holiday") { cn.edu.sicnu.scnujw.ui.zhxg.HolidayRegisterScreen(nav) }
        composable("diag") { cn.edu.sicnu.scnujw.ui.diag.DiagScreen(nav) }
    }
}
