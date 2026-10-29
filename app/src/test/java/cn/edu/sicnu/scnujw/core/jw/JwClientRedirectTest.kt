package cn.edu.sicnu.scnujw.core.jw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正方把失效会话 302 到 /error.aspx（"一台计算机同一时间只能一个用户登录"）。
 * 登录链路用的 client 关闭了自动跟随，所以这里能拿到 3xx；必须按「登录失效」处理，
 * 交给 AuthGuard 静默换票重试，而不是当成普通 HTTP 错误。
 */
class JwClientRedirectTest {

    @Test
    fun `3xx 视为会话失效`() {
        listOf(301, 302, 303, 307, 308).forEach { code ->
            assertTrue("HTTP $code 应视为失效重定向", JwClient.isAuthRedirect(code))
        }
    }

    @Test
    fun `正常响应与客户端错误不算`() {
        listOf(200, 204, 400, 401, 403, 404, 500).forEach { code ->
            assertFalse("HTTP $code 不应视为失效重定向", JwClient.isAuthRedirect(code))
        }
    }
}
