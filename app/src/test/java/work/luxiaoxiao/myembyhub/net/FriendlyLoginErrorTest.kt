package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [friendlyLoginError] 的回归测试。
 *
 * 为什么单测盯这个：登录页把 401 说成「登录已过期，请重新登录」不会有编译错误，
 * 只会在真机上表现为「密码打错了，用户以为是自己网络坏了，反复重试」。
 */
class FriendlyLoginErrorTest {

    /** 密码打错：服务端回 401 + 中文错误体，必须说「账号或密码不对」。 */
    @Test
    fun wrongPassword_saysCredentialsAreWrong() {
        val e = IllegalStateException("登录失败 HTTP 401 无效用户名或密码。请重试。")
        assertEquals("账号或密码不对（服务端拒绝了这次登录）", friendlyLoginError(e))
    }

    /** 4.3 上服务端不给 WWW-Authenticate 头时抛的那条，也归到「密码不对」。 */
    @Test
    fun noAuthChallenges_saysCredentialsAreWrong() {
        val e = IOException("No authentication challenges found")
        assertEquals("账号或密码不对（服务端拒绝了这次登录）", friendlyLoginError(e))
    }

    /** 网络类错误照旧走 friendlyError，别被登录分支吞掉。 */
    @Test
    fun timeout_stillSaysCheckNetwork() {
        val e = IOException("connect timed out")
        assertEquals("连接服务器超时，检查网络", friendlyLoginError(e))
    }

    /** 网关 5xx 的响应体常常是一大坨 HTML —— 不能把 HTML 漏到界面上。 */
    @Test
    fun gatewayError_doesNotLeakHtml() {
        val e = IllegalStateException(
            "登录失败 HTTP 502 <html>\n<head><title>502 Bad Gateway</title></head>\n</html>"
        )
        val msg = friendlyLoginError(e)
        assertEquals("服务器暂时不可用，稍后再试", msg)
        assertTrue("不能把 HTML 漏出去", !msg.contains("<"))
    }

    /** 空 message 也得给个说法，不能是空白。 */
    @Test
    fun emptyMessage_fallsBackToClassName() {
        val e = IllegalStateException()
        assertEquals("IllegalStateException", friendlyLoginError(e))
    }
}
