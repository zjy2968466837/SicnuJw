package cn.edu.sicnu.scnujw.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 清单解析：`ticketUrl` 是 2026-10-29 新增的可选字段（取票端点）。
 * 缺失时必须退回直接下载（兼容旧服务器/GitHub 人工镜像）；
 * 非 http(s) 的值必须按缺失处理，绝不能把奇怪协议带进下载器。
 */
class UpdateInfoTest {

    @Test
    fun `ticketUrl 正常解析`() {
        val json = """
            {
              "versionCode": 10,
              "versionName": "1.8",
              "url": "https://example.com/SicnuJw-1.8.apk",
              "sha256": "abc",
              "size": 12345,
              "ticketUrl": "https://example.com/v1/update-ticket"
            }
        """.trimIndent()
        val info = UpdateInfo.parse(json)
        assertEquals("https://example.com/v1/update-ticket", info.ticketUrl)
        assertEquals(10L, info.versionCode)
    }

    @Test
    fun `没有 ticketUrl 时为 null，退回直接下载`() {
        val json = """
            {
              "versionCode": 10,
              "versionName": "1.8",
              "url": "https://example.com/SicnuJw-1.8.apk"
            }
        """.trimIndent()
        assertNull(UpdateInfo.parse(json).ticketUrl)
    }

    @Test
    fun `非 http 的 ticketUrl 按缺失处理`() {
        val json = """
            {
              "versionCode": 10,
              "versionName": "1.8",
              "url": "https://example.com/SicnuJw-1.8.apk",
              "ticketUrl": "file:///etc/passwd"
            }
        """.trimIndent()
        assertNull(UpdateInfo.parse(json).ticketUrl)
    }
}
