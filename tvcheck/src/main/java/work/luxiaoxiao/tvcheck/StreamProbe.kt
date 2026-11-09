package work.luxiaoxiao.tvcheck

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 播放链路可行性探针。
 *
 * 为什么不能用 HttpURLConnection：
 * ExoPlayer 2.19.1 的 `DefaultHttpDataSource` 走 `HttpURLConnection`，
 * 而它不接受外部传入的 `SSLSocketFactory` → HTTPS 源必然失败。
 * 正式客户端只能自己实现 `DataSource`。
 *
 * 这个探针就是「自己实现 DataSource」的第一步验证：
 * 用裸 `SSLSocket` 手写 HTTP 请求，看能不能
 *   ① 建立带鉴权的 TLS 连接
 *   ② 带上 Range 头取到媒体流
 *   ③ 正确解析状态行与响应头
 *
 * 跑通了，剩下的就是把这段逻辑封成 ExoPlayer 的 `DataSource`。
 */
object StreamProbe {

    private const val TAG = "TvCheck"
    const val RESULT_FILE = "/sdcard/tvcheck-stream.txt"

    private val TRUST_ALL = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    /**
     * @param mediaUrl 直接可取的媒体地址（可空，空则先从 API 找一个视频项）
     */
    fun run(ctx: Context, mediaUrl: String, out: (String) -> Unit) {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append('\n')
            out(s)
        }

        line("===== 播放链路探针（裸 SSLSocket + 手写 HTTP）=====")
        line("设备: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        line("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        line("")

        val token = readApiKey()
        line("api_key: ${if (token.isEmpty()) "(缺失)" else "已读取，长度 ${token.length}"}")
        line("")

        // 1) 选定要拉的地址
        var url = mediaUrl.trim()
        if (url.isEmpty()) {
            line("--- 1) 从 API 找一个视频项 ---")
            url = findVideoUrl(ctx, token) { line(it) }
            line("")
        } else {
            line("--- 1) 使用配置里的 media 地址 ---")
            line("  $url")
            line("")
        }

        if (url.isEmpty()) {
            line("没有可测的媒体地址，探针结束。")
            write(sb.toString())
            return
        }

        // 2) 裸 SSLSocket 手写 HTTP GET（带 Range）
        line("--- 2) 裸 SSLSocket 发 HTTPS GET（带 Range + Token）---")
        line(rawHttpsGet(ctx, url, token) { line(it) })
        line("")

        // 3) 同一地址用 HttpURLConnection 对照（预期失败：无法注入 socket factory）
        line("--- 3) HttpURLConnection 对照（预期失败，说明播放必须自定义 DataSource）---")
        line(urlConnectionGet(url, token) { line(it) })
        line("")

        line("===== 探针结束 =====")
        write(sb.toString())
    }

    private fun write(text: String) {
        try {
            File(RESULT_FILE).writeText(text)
            Log.i(TAG, "播放链路探针结果已写入 $RESULT_FILE")
        } catch (e: Throwable) {
            Log.e(TAG, "写探针结果失败", e)
        }
    }

    /**
     * 裸 SSLSocket + 手写 HTTP。
     *
     * 这是「自己实现 DataSource」的原型：连接、握手、拼请求行、读响应头、读一点 body。
     */
    private fun rawHttpsGet(ctx: Context, urlStr: String, token: String, line: (String) -> Unit): String {
        var socket: SSLSocket? = null
        try {
            val u = java.net.URL(urlStr)
            val host = u.host
            val port = if (u.port > 0) u.port else 443
            val path = u.file.ifEmpty { "/" }
            val query = u.query

            // TLS：系统 JSSE + 内置锚点 + 抬协议
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(
                null,
                arrayOf<TrustManager>(BundledCa.trustManager(ctx)),
                SecureRandom()
            )
            val factory: SSLSocketFactory = sslCtx.socketFactory as SSLSocketFactory
            socket = factory.createSocket() as SSLSocket
            socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")
            socket.connect(InetSocketAddress(host, port), 10000)
            socket.soTimeout = 20000
            socket.startHandshake()

            val s = socket.session
            line("  ✓ TLS 握手成功：${s.protocol} / ${s.cipherSuite}")

            // 手写 HTTP 请求
            val req = StringBuilder()
            req.append("GET ").append(path).append(if (query != null) "?$query" else "").append(" HTTP/1.1\r\n")
            req.append("Host: ").append(host).append(":").append(port).append("\r\n")
            req.append("User-Agent: tvcheck-stream/1.0\r\n")
            req.append("Accept: */*\r\n")
            req.append("Range: bytes=0-2047\r\n")
            if (token.isNotEmpty()) req.append("X-Emby-Token: ").append(token).append("\r\n")
            req.append("X-Emby-Authorization: MediaBrowser Client=\"TV Check\", Device=\"TV\", DeviceId=\"tvcheck\", Version=\"1.0.0\"\r\n")
            req.append("Connection: close\r\n")
            req.append("\r\n")

            socket.getOutputStream().use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }

            // 读响应头
            val raw = ByteArray(8192)
            var total = 0
            var headerEnd = -1
            val buf = StringBuilder()
            while (total < raw.size) {
                val n = socket.getInputStream().read(raw, total, raw.size - total)
                if (n <= 0) break
                total += n
                buf.append(String(raw, 0, n, Charsets.ISO_8859_1))
                headerEnd = buf.indexOf("\r\n\r\n")
                if (headerEnd >= 0) break
            }
            if (headerEnd < 0) {
                return "  ✗ 没读到完整的响应头（收到 ${total} 字节）\n  ${buf.toString().take(200)}"
            }

            val headers = buf.substring(0, headerEnd).split("\r\n")
            val status = headers.firstOrNull() ?: "(空)"
            line("  ✓ HTTP 响应: $status")
            headers.drop(1).forEach { h ->
                val k = h.substringBefore(":").trim()
                if (k.lowercase(Locale.US) in setOf(
                        "content-type", "content-length", "content-range", "accept-ranges", "transfer-encoding")) {
                    line("    $h")
                }
            }
            val bodyLen = buf.length - headerEnd - 4
            line("  同一包内已读到 body ${bodyLen} 字节")
            line("  ⇒ **裸 SSLSocket 手写 HTTP 可行**，播放可用自定义 DataSource 实现")
            return ""
        } catch (e: Throwable) {
            val head = (e.message ?: "").substringBefore('\n')
            return "  ✗ ${e.javaClass.simpleName}: $head"
        } finally {
            try { socket?.close() } catch (ignored: Throwable) {}
        }
    }

    /** HttpURLConnection 对照组：预期失败（无法注入带 TLS1.2 的 socket factory）。 */
    private fun urlConnectionGet(urlStr: String, token: String, line: (String) -> Unit): String {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            if (token.isNotEmpty()) conn.setRequestProperty("X-Emby-Token", token)
            val code = conn.responseCode
            "  ✓ HTTP $code（这台机器上 HttpsURLConnection 若能带 token 也许能通）"
        } catch (e: Throwable) {
            val head = (e.message ?: "").substringBefore('\n')
            "  ✗ ${e.javaClass.simpleName}: $head"
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    /** 从 API 找一个视频项，拼出可直接取流的地址。 */
    private fun findVideoUrl(ctx: Context, token: String, line: (String) -> Unit): String {
        if (token.isEmpty()) {
            line("  没有 token，跳过")
            return ""
        }
        val base = readServer()
        if (base.isEmpty()) {
            line("  conf 里没有 server，跳过")
            return ""
        }
        return try {
            val factory = TlsStack.forceTls12(
                TlsStack.socketFactory(ctx, false, TlsMatrix.TrustMode.BUNDLED)!!
            )
            val conn = java.net.URL("$base/Items?IncludeMedia=Video&Recursive=true&Limit=1&Fields=Id,Name,MediaType")
                .openConnection() as java.net.HttpURLConnection
            if (conn is javax.net.ssl.HttpsURLConnection) conn.sslSocketFactory = factory
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("X-Emby-Token", token)
            conn.setRequestProperty(
                "X-Emby-Authorization",
                "MediaBrowser Client=\"TV Check\", Device=\"TV\", DeviceId=\"tvcheck\", Version=\"1.0.0\""
            )
            val code = conn.responseCode
            line("  GET /Items → HTTP $code")
            if (code != 200) {
                conn.disconnect()
                return ""
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val items = org.json.JSONObject(body).optJSONArray("Items")
            if (items == null || items.length() == 0) {
                line("  没有视频项")
                return ""
            }
            val id = items.getJSONObject(0).optString("Id")
            val name = items.getJSONObject(0).optString("Name")
            line("  找到视频: $name (id=$id)")
            "$base/Videos/$id/stream?static=true&api_key=$token"
        } catch (e: Throwable) {
            line("  ✗ ${e.javaClass.simpleName}: ${(e.message ?: "").substringBefore('\n')}")
            ""
        }
    }

    private fun readApiKey(): String {
        return try {
            val f = File("/sdcard/tvcheck.conf")
            if (!f.exists()) return ""
            var key = ""
            f.forEachLine { line ->
                val s = line.trim()
                if (s.startsWith("api_key=")) key = s.substringAfter("api_key=").trim()
            }
            key
        } catch (e: Throwable) {
            ""
        }
    }

    private fun readServer(): String {
        return try {
            val f = File("/sdcard/tvcheck.conf")
            if (!f.exists()) return ""
            var s = ""
            f.forEachLine { line ->
                val t = line.trim()
                if (t.startsWith("server=")) s = t.substringAfter("server=").trim()
            }
            s.trimEnd('/')
        } catch (e: Throwable) {
            ""
        }
    }
}
