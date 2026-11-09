package work.luxiaoxiao.tvcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置服务的自测：在电脑上直接跑，验证「浏览器打开 → 填表提交 → 落盘」这条链路。
 * 不依赖电视，也不需要 adb。
 */
class ConfigServerTest {

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        val code = c.responseCode
        // 404 时 HttpURLConnection 的 inputStream 会抛异常，要读 errorStream
        val body = BufferedReader(InputStreamReader(c.errorStream ?: c.inputStream, "UTF-8")).use { it.readText() }
        c.disconnect()
        return code to body
    }

    private fun post(url: String, form: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val body = BufferedReader(InputStreamReader(c.inputStream, "UTF-8")).use { it.readText() }
        c.disconnect()
        return code to body
    }

    @Test
    fun 配置页可访问且能保存远程修改() {
        val store = HashMap<String, String>()
        store["server"] = "http://old:8096"

        val server = ConfigServer(
            port = 0,
            provider = { HashMap(store) },
            onSave = { map -> store.putAll(map) }
        )
        server.start()
        try {
            val base = "http://127.0.0.1:${server.boundPort}"
            assertTrue("端口没起来", server.boundPort > 0)

            // 1) 首页能打开，且回显当前 server
            val (code, html) = get("$base/")
            assertEquals(200, code)
            assertTrue(html.contains("TV Check"))
            assertTrue(html.contains("http://old:8096"))

            // 2) POST 保存 → 落到 store
            val (code2, body2) = post(
                "$base/save",
                "server=https%3A%2F%2Fexample.com%3A8096&api_key=abc123&media=http%3A%2F%2Fm%2Fa.m3u8"
            )
            assertEquals(200, code2)
            assertTrue(body2.contains("配置已保存"))

            assertEquals("https://example.com:8096", store["server"])
            assertEquals("abc123", store["api_key"])
            assertEquals("http://m/a.m3u8", store["media"])

            // 3) JSON 接口能读回
            val (code3, json) = get("$base/api/config")
            assertEquals(200, code3)
            assertTrue(json.contains("example.com"))
            assertTrue(json.contains("abc123"))

            // 4) 未知路径 404
            val (code4, _) = get("$base/nope")
            assertEquals(404, code4)
        } finally {
            server.stop()
        }
    }
}
