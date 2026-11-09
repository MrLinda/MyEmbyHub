package com.example.tvcheck

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocket

/**
 * HTTP 请求变体矩阵探针。
 *
 * ## 为什么需要它
 *
 * 播放链路的手写 HTTP 层已经踩了三个坑：
 *   ① `Host` 头带默认端口 → openresty 匹配不到 server_name → 404
 *   ② `java.net.URL(URL, String)` 在 4.3 上重复拼 query → 404
 *   ③ 路径丢前导斜杠 → 400 Bad Request
 *
 * 每次都得让用户重装 APK 才能验证一轮，迭代太慢。
 * 这个探针**在一个安装里把各种写法全试一遍**，把「服务器到底接受什么」
 * 一次性测清楚，避免再靠反复试错。
 *
 * 变体覆盖：
 *   - Host 头带 / 不带默认端口
 *   - 带 / 不带 Range
 *   - 路径带 / 不带前导斜杠
 *   - 直连重定向目标域名（v10.example.com）
 */
object HttpProbe {

    private const val TAG = "TvCheck"
    const val RESULT_FILE = "/sdcard/tvcheck-http.txt"

    fun run(
        ctx: Context,
        server: String,
        token: String,
        onLine: (String) -> Unit,
        onFinished: (String) -> Unit
    ) {
        val sb = StringBuilder()
        val emit: (String) -> Unit = { s ->
            sb.append(s).append('\n')
            onLine(s)
            Log.i(TAG, s)
        }

        emit("===== HTTP 请求变体矩阵 =====")
        emit("设备: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        emit("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        emit("")

        val item = NetPlayProbe.findVideoCandidates(ctx, server, token) { emit(it) }.firstOrNull()
        if (item == null) {
            emit("✗ 没找到可测的视频项")
            finish(sb.toString(), onFinished)
            return
        }
        val url = "$server/Videos/${item.id}/stream?static=true&mediaSourceId=${item.mediaSourceId}"
        emit("测试对象: ${item.name}  ${item.container}/${item.codec} ${item.width}x${item.height}")
        emit("URL: $url")
        emit("")

        // ---- 先拿到重定向目标 ----
        emit("--- 0) 先看 302 去哪 ---")
        val r0 = tryRequest(ctx, url, token, hostWithPort = false, withRange = true, pathLeadingSlash = true)
        emit("  状态行: ${r0.statusLine}")
        r0.headers.forEach { (k, v) -> if (k.equals("location", true)) emit("  Location: $v") }
        // ⚠️ `String.firstOrNull()` 返回 Char?，这里要的是整串 Location
        val location = r0.headers.entries
            .firstOrNull { it.key.equals("location", true) }
            ?.value?.takeIf { it.isNotEmpty() }
        emit("")

        if (location == null) {
            emit("  没有 Location，后续变体跳过")
        } else {
            // 变体 1：直连重定向目标，Host 不带端口（正确写法）
            emit("--- 1) 直连 v10，Host 不带端口（预期 206）---")
            emitVariants(ctx, location, token, emit)

            // 变体 2：直连 v10，Host 带 :443
            emit("--- 2) 直连 v10，Host 带 :443（对照）---")
            emitVariants(ctx, location, token, emit)
        }

        // 变体 3：原始域名上的各种写法
        emit("--- 3) 原始域名上的写法对照 ---")
        emitVariants(ctx, url, token, emit)

        emit("--- 结论 ---")
        emit("  看上面哪个变体是 206 → 那就是服务器接受的写法")
        finish(sb.toString(), onFinished)
    }

    /** 对同一个 URL 跑 4 种写法：Host 端口、Range、路径斜杠。 */
    private fun emitVariants(ctx: Context, url: String, token: String, emit: (String) -> Unit) {
        val cases = listOf(
            Triple("Host 无端口 + Range + 路径带斜杠", false, true),
            Triple("Host 带端口 + Range + 路径带斜杠", true, true),
            Triple("Host 无端口 + 无 Range + 路径带斜杠", false, false),
            Triple("Host 无端口 + Range + 路径无斜杠", false, true)
        )
        for ((label, withPort, withRange) in cases) {
            val leadingSlash = !label.contains("路径无斜杠")
            val r = tryRequest(
                ctx, url, token,
                hostWithPort = withPort,
                withRange = withRange,
                pathLeadingSlash = leadingSlash
            )
            emit("  [$label] → ${r.statusLine}")
            r.headers.forEach { (k, v) ->
                if (k.equals("content-range", true) || k.equals("content-type", true) ||
                    k.equals("content-length", true) || k.equals("accept-ranges", true)
                ) emit("      $k: $v")
            }
            if (r.bodySnippet.isNotEmpty()) {
                emit("      body: ${r.bodySnippet.take(120)}")
            }
        }
    }

    private class Result(
        val statusLine: String,
        val headers: Map<String, String>,
        val bodySnippet: String
    )

    /**
     * 裸 SSLSocket 发一次请求，**不经过 [EmbyHttpDataSource]**，
     * 这样即使 DataSource 有 bug 也能独立测出服务器的接受形式。
     */
    private fun tryRequest(
        ctx: Context,
        urlStr: String,
        token: String,
        hostWithPort: Boolean,
        withRange: Boolean,
        pathLeadingSlash: Boolean
    ): Result {
        var socket: Socket? = null
        try {
            val scheme = urlStr.substringBefore("://", missingDelimiterValue = "https").lowercase()
            val rest = urlStr.substringAfter("://", missingDelimiterValue = urlStr)
            val hostPort = rest.substringBefore('/')
            val host = hostPort.substringBefore(':')
            val port = hostPort.substringAfter(':', missingDelimiterValue = "").toIntOrNull()
                ?: if (scheme == "http") 80 else 443
            var pathQuery = if (rest.contains('/')) rest.substringAfter('/') else ""
            if (!pathLeadingSlash && pathQuery.startsWith("/")) pathQuery = pathQuery.substring(1)
            val qIdx = pathQuery.indexOf('?')
            var path = if (qIdx >= 0) pathQuery.substring(0, qIdx) else pathQuery
            if (pathLeadingSlash && !path.startsWith("/")) path = "/" + path
            val query = if (qIdx >= 0) pathQuery.substring(qIdx + 1) else null
            if (path.isEmpty()) path = "/"

            val factory = TlsStack.forceTls12(
                TlsStack.socketFactory(ctx, false, TlsMatrix.TrustMode.BUNDLED)
                    ?: throw java.io.IOException("无法构造 TLS factory")
            )
            val ssl = factory.createSocket() as SSLSocket
            socket = ssl
            ssl.connect(InetSocketAddress(host, port), 10000)
            ssl.soTimeout = 20000
            ssl.startHandshake()

            val sb = StringBuilder()
            sb.append("GET ").append(path).append(if (query != null) "?$query" else "").append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host)
            if (hostWithPort) sb.append(':').append(port)
            sb.append("\r\n")
            sb.append("User-Agent: tvcheck-http/1.0\r\n")
            sb.append("Accept: */*\r\n")
            sb.append("Accept-Encoding: identity\r\n")
            if (withRange) sb.append("Range: bytes=0-\r\n")
            if (token.isNotEmpty()) sb.append("X-Emby-Token: ").append(token).append("\r\n")
            sb.append("\r\n")

            val out = ssl.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val pis = java.io.BufferedInputStream(ssl.getInputStream(), 8192)
            val head = java.io.ByteArrayOutputStream()
            val term = byteArrayOf(13, 10, 13, 10)
            var matched = 0
            while (head.size() < 65536) {
                val b = pis.read()
                if (b < 0) break
                head.write(b)
                matched = when {
                    (b and 0xFF) == (term[matched].toInt() and 0xFF) -> matched + 1
                    (b and 0xFF) == (term[0].toInt() and 0xFF) -> 1
                    else -> 0
                }
                if (matched == 4) break
            }
            if (matched != 4) {
                return Result("(响应头不完整)", emptyMap(), "")
            }
            // ⚠️ 不要 unread：逐字节读到 \r\n\r\n 时 socket 流的位置正好在 body 开头
            val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
            val statusLine = lines.firstOrNull() ?: ""
            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            val bodyBuf = ByteArray(160)
            val n = pis.read(bodyBuf)
            val snippet = if (n > 0) String(bodyBuf, 0, n, Charsets.ISO_8859_1).replace('\n', ' ').trim() else ""
            return Result(statusLine, headers, snippet)
        } catch (e: Throwable) {
            return Result("✗ ${e.javaClass.simpleName}: ${(e.message ?: "").substringBefore('\n')}", emptyMap(), "")
        } finally {
            try { socket?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun finish(text: String, onFinished: (String) -> Unit) {
        try {
            File(RESULT_FILE).writeText(text)
            Log.i(TAG, "HTTP 变体矩阵已写入 $RESULT_FILE")
        } catch (e: Throwable) {
            Log.e(TAG, "写 HTTP 变体矩阵失败", e)
        }
        onFinished(text)
    }
}
