package work.luxiaoxiao.myembyhub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL 解析的回归测试。
 *
 * 这几个用例都对应 2026-11-03 在乐视 S50 Air 真机上踩过的坑，
 * 写在这里防止以后重构时又踩一遍。
 */
class UrlKitTest {

    @Test
    fun `解析 https url 的各部分`() {
        val p = UrlKit.parse("https://tv.example.com/Videos/765503/stream?static=true&mediaSourceId=abc")
        assertEquals("https", p.scheme)
        assertEquals("tv.example.com", p.host)
        assertEquals(443, p.port)
        assertEquals("/Videos/765503/stream", p.path)
        assertEquals("static=true&mediaSourceId=abc", p.query)
    }

    @Test
    fun `路径必须带前导斜杠`() {
        // 少了斜杠 → 请求行变成 `GET Videos/...` → openresty 回 400 Bad Request
        val p = UrlKit.parse("https://v10.example.com/Videos/1/stream?static=true")
        assertEquals("/Videos/1/stream", p.path)
        assertEquals(true, p.path.startsWith("/"))
    }

    @Test
    fun `没有 query 时 query 为 null`() {
        val p = UrlKit.parse("https://tv.example.com/emby/System/Info/Public")
        assertNull(p.query)
        assertEquals("/emby/System/Info/Public", p.path)
    }

    @Test
    fun `显式端口要解析出来`() {
        val p = UrlKit.parse("http://192.168.1.50:8765/myembyhub-debug.apk")
        assertEquals("http", p.scheme)
        assertEquals("192.168.1.50", p.host)
        assertEquals(8765, p.port)
        assertEquals("/myembyhub-debug.apk", p.path)
    }

    @Test
    fun `重定向到绝对 url 时不能重复拼 query`() {
        val base = "https://tv.example.com/Videos/666982/stream?static=true&mediaSourceId=xyz"
        val loc = "https://v11.example.com/Videos/666982/stream?static=true&mediaSourceId=xyz"
        val resolved = UrlKit.resolve(base, loc)
        assertEquals(loc, resolved)
        // 整条 URL 里 query 只能出现一次
        assertEquals(1, resolved.split('?').size - 1)
        assertEquals(false, resolved.contains("?static=true&mediaSourceId=xyz?"))
    }

    @Test
    fun `重定向到根相对路径时拼 origin`() {
        val resolved = UrlKit.resolve(
            "https://tv.example.com/Videos/1/stream?static=true",
            "/Videos/2/stream?static=true"
        )
        assertEquals("https://tv.example.com/Videos/2/stream?static=true", resolved)
    }

    @Test
    fun `重定向到相对路径时拼在 base 的目录后面`() {
        val resolved = UrlKit.resolve(
            "https://tv.example.com/Videos/1/stream?static=true",
            "other.mp4"
        )
        assertEquals("https://tv.example.com/Videos/1/other.mp4", resolved)
    }
}
