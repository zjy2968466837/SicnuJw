package cn.edu.sicnu.scnujw.core.net

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 网络失败分类：设备没网 vs 有网但连不上校园网 */
enum class NetFailure {
    /** 设备本身没有可用网络，或 DNS 解析失败 */
    NO_NETWORK,

    /** 设备有网络，但访问不到校园网/教务（超时、拒绝连接、无路由到目标） */
    NO_CAMPUS,

    UNKNOWN,
}

fun classifyNetworkFailure(e: Throwable): NetFailure {
    var t: Throwable? = e
    while (t != null) {
        when (t) {
            is UnknownHostException -> return NetFailure.NO_NETWORK
            is NoRouteToHostException -> return NetFailure.NO_NETWORK
            is SocketTimeoutException -> return NetFailure.NO_CAMPUS
            is ConnectException -> return NetFailure.NO_CAMPUS
        }
        t = t.cause
    }
    return NetFailure.UNKNOWN
}

/** 面向用户的提示：明确告诉用户是「没网」还是「连不上校园网」 */
fun networkHint(e: Throwable): String = when (classifyNetworkFailure(e)) {
    NetFailure.NO_NETWORK -> "设备当前没有可用网络（或 DNS 解析失败），请先连接网络。"
    NetFailure.NO_CAMPUS -> "设备有网络，但访问不到校园网。请连接校园网或校园 VPN 后重试。"
    NetFailure.UNKNOWN -> "网络请求失败，请确认已连接校园网并在「我的」重新登录后再试。"
}
