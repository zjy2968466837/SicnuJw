package cn.edu.sicnu.scnujw.ui.common

import cn.edu.sicnu.scnujw.core.auth.AuthGuard
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 页面异常/空态的四种用户可区分情况。
 *
 * - [AUTH_EXPIRED]：登录态失效，需要重新验证；不是网络问题。
 * - [NO_NETWORK]：设备没有网络/DNS 失败。
 * - [NO_CAMPUS]：设备有网络，但访问不到校园网/教务服务。
 * - [SERVER]：能连上，但页面结构异常或服务端返回错误。
 * - [EMPTY]：请求成功、登录有效，教务就是没有记录（大一上学期常见）。
 */
enum class DataIssue { AUTH_EXPIRED, NO_NETWORK, NO_CAMPUS, SERVER, EMPTY }

fun dataIssueOf(e: Throwable?): DataIssue = when (e) {
    is AuthGuard.AuthExpiredException,
    is AuthGuard.InteractiveAuthRequired -> DataIssue.AUTH_EXPIRED
    // 换票/落地超时属于链路暂时不可达，不是账号失效
    is AuthGuard.TransientAuthException -> DataIssue.NO_CAMPUS
    is UnknownHostException, is NoRouteToHostException -> DataIssue.NO_NETWORK
    is SocketTimeoutException, is ConnectException -> DataIssue.NO_CAMPUS
    null -> DataIssue.SERVER
    else -> DataIssue.SERVER
}

/** 一句话原因：用于「异常时降级到上次缓存」的非阻塞提示 */
fun dataIssueShort(issue: DataIssue): String = when (issue) {
    DataIssue.AUTH_EXPIRED -> "登录已失效"
    DataIssue.NO_NETWORK -> "设备没有可用网络"
    DataIssue.NO_CAMPUS -> "无法连接校园网"
    DataIssue.SERVER -> "教务系统没有正常响应"
    DataIssue.EMPTY -> "暂时没有数据"
}

data class IssueCopy(
    val title: String,
    val body: String,
    val action: String,
    val isError: Boolean = true,
)

/**
 * 错误面板文案：统一「<页面>读取失败，<原因>。」标题 + 一句正文 + 动作，不再有「下一步」行。
 * [subject] 由调用方传入：成绩 / 平时成绩 / 学习进度 / 考试安排 / 选课结果 / 课表。
 */
fun issueCopy(
    issue: DataIssue,
    subject: String,
    retryLabel: String = "重新加载",
): IssueCopy = when (issue) {
    DataIssue.AUTH_EXPIRED -> IssueCopy(
        title = "${subject}读取失败，登录已失效。",
        body = "教务会话已过期，需要重新验证后才能继续读取。",
        action = "去验证",
    )
    DataIssue.NO_NETWORK -> IssueCopy(
        title = "${subject}读取失败，设备没有可用网络。",
        body = "当前无法解析教务地址，请先连接网络。",
        action = retryLabel,
    )
    DataIssue.NO_CAMPUS -> IssueCopy(
        title = "${subject}读取失败，无法连接校园网。",
        body = "请连接校园网或校园 VPN 后重试。",
        action = retryLabel,
    )
    DataIssue.SERVER -> IssueCopy(
        title = "${subject}读取失败，教务系统没有正常响应。",
        body = "请求已发出，但页面结构异常或服务端返回错误。",
        action = retryLabel,
    )
    DataIssue.EMPTY -> IssueCopy(
        title = "${subject}暂无数据",
        body = "教务系统正常返回，但当前没有记录。",
        action = retryLabel,
        isError = false,
    )
}
