package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.LinkedHashMap
import javax.net.ssl.SSLSocket

/**
 * 自定义 HTTP(S) DataSource —— 正式客户端播放层的核心。
 *
 * ## 为什么必须自己写
 *
 * ExoPlayer 2.19.1 自带的 `DefaultHttpDataSource` 走 `HttpURLConnection`，
 * **不接受外部传入的 `SSLSocketFactory`**。而这台电视（Android 4.3 / API 18）
 * 的系统 JSSE 默认只启用 TLS 1.0，目标服务器最低要 TLS 1.1 → 直接失败。
 * 所以播放链路只能自己实现 `DataSource`。
 *
 * ## 2026-11-03 真机实测确认的三个要点
 *
 * 1. **必须跟随 302**。Emby 的取流接口 `GET /Videos/{id}/stream?static=true`
 *    返回 **302**，Location 指向真正存文件的 `https://v10.example.com/...`。
 *    ExoPlayer 不会替你跟重定向，得自己读 `Location` 头、重建连接再发一次。
 * 2. **必须能拿到总长度**。`v10` 对 Range 请求返回 `206` +
 *    `Content-Range: bytes 0-2047/294813105`，但 `Content-Length` 只是
 *    **本次 range 的长度**（2048）。若原样交给 ExoPlayer，媒体总长会算错、
 *    时长/进度条全乱。这里把 `Content-Range` 里的总长回填给 ExoPlayer。
 * 3. **Token 用 `X-Emby-Token` 头**即可，跨 `v10` 重定向后依然有效
 *    （服务端认同一个 token）。
 *
 * ## 只包 factory，不包 socket
 *
 * 包装 `SSLSocket` 而不转发 `connect()` 会导致 delegate 根本没连上，
 * 握手以 "Broken pipe" 失败（4.3 真机实测踩过）。所以只包装
 * `SSLSocketFactory`，在 `createSocket()` 里对**真 socket** 设
 * `enabledProtocols` 后原样返回。见 [TlsStack.socketFactory]。
 */
class EmbyHttpDataSource(
    private val ctx: Context,
    private val token: String,
    /**
     * 可选的共享日志。`DataSource` 会被 ExoPlayer 并发创建多个实例，
     * 每个实例各自持有自己的 socket，外部要汇总状态就靠这个共享对象。
     */
    private val sharedLog: StringBuffer? = null,
    private val userAgent: String = "MyEmbyHub/1.0 (AndroidTV)"
) : DataSource {

    companion object {
        private const val TAG = "TvCheck"
        /** 最多跟几跳重定向。 */
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 30000
        private const val MAX_HEADER_BYTES = 65536

        /**
         * 空闲连接池最多留几条。
         *
         * ExoPlayer 会同时用几个 `DataSource`（嗅探一个、正式读一个），
         * 池子只要够「一个刚用完、另一个立刻接上」即可，留多了白占内存。
         */
        private const val MAX_IDLE_SOCKETS = 3

        /**
         * 空闲连接最多放多久。
         *
         * 服务端（openresty）自己有 keepalive 超时，超时后会悄悄关掉连接。
         * 复用一条已被对端关闭的连接，写请求时就可能拿到 RST。
         * 这里主动设一个比服务端短的时限，减少撞上这种情况的概率
         * （真撞上了 [request] 里还有一次换新连接的重试兜底）。
         */
        private const val IDLE_TTL_MS = 15_000L

        /** 空闲连接池。key = `scheme://host:port`，跨 DataSource 实例共享。 */
        private val idlePool = LinkedHashMap<String, IdleSocket>()
        private val poolLock = Any()

        /**
         * 累计从网络读到的字节数（全进程）。
         *
         * ExoPlayer 会并发开好几个 DataSource（嗅探一个、正式读一个），
         * 单看某一个实例的计数会漏掉大半流量，所以用全局计数：
         * 播放页每隔 0.5 秒取两次差值，就是这一瞬间的真实下载速度。
         * 用 AtomicLong 是因为 `read()` 跑在各自的加载线程上。
         */
        private val totalBytesRead = java.util.concurrent.atomic.AtomicLong(0L)

        /** 当前累计读到的字节数，供上层算实时网速。 */
        fun bytesRead(): Long = totalBytesRead.get()

        private class IdleSocket(val socket: Socket, val idleSince: Long)

        private fun poolKey(u: UrlKit.Parts): String = "${u.scheme}://${u.host}:${u.port}"

        /** 取一条可复用的空闲连接；没有/过期了就返回 null。 */
        private fun takeIdle(key: String): Socket? {
            synchronized(poolLock) {
                val it = idlePool.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.key != key) continue
                    it.remove()
                    val idle = e.value
                    val fresh = System.currentTimeMillis() - idle.idleSince <= IDLE_TTL_MS
                    if (fresh && idle.socket.isConnected && !idle.socket.isClosed &&
                        !idle.socket.isInputShutdown && !idle.socket.isOutputShutdown
                    ) {
                        return idle.socket
                    }
                    try { idle.socket.close() } catch (ignored: Throwable) {}
                }
                return null
            }
        }

        /** 把读干净的连接放回池子；池满或连接已不可用就直接关掉。 */
        private fun releaseIdle(key: String, socket: Socket) {
            synchronized(poolLock) {
                if (idlePool.size >= MAX_IDLE_SOCKETS) {
                    val oldest = idlePool.entries.minByOrNull { it.value.idleSince }
                    if (oldest != null) {
                        idlePool.remove(oldest.key)
                        try { oldest.value.socket.close() } catch (ignored: Throwable) {}
                    }
                }
                idlePool[key] = IdleSocket(socket, System.currentTimeMillis())
            }
        }

        /** 进程退出/换服务器时清空池子。 */
        fun clearIdlePool() {
            synchronized(poolLock) {
                for (v in idlePool.values) {
                    try { v.socket.close() } catch (ignored: Throwable) {}
                }
                idlePool.clear()
            }
        }
    }

    private var socket: Socket? = null
    private var body: InputStream? = null
    private var responseHeaders: Map<String, List<String>>? = null
    private var currentUri: Uri? = null
    /** 服务器忽略了 Range（回了 200 而不是 206）时，需要自己跳过的字节数。 */
    private var skipBytes: Long = 0
    /** 本次连接实际可读的总长度。 */
    private var contentLength: Long = 0
    /** 最近一次 open() 的结果摘要，给探针写日志用。 */
    var lastOpenSummary: String = ""
        private set
    /** 这条连接的池子 key（`scheme://host:port`），null 表示不复用。 */
    private var currentPoolKey: String? = null
    /** 本次响应体还剩多少字节没读；-1 表示长度未知。读完才敢把连接还回池子。 */
    private var bytesRemaining: Long = -1

    // ==================== DataSource 接口 ====================

    override fun addTransferListener(transferListener: TransferListener) {
        // 进度上报暂不需要
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders ?: emptyMap()

    /**
     * 打开连接。
     *
     * @return 可读的总字节数；长度未知时返回 [com.google.android.exoplayer2.upstream.C.LENGTH_UNSET]
     */
    override fun open(dataSpec: DataSpec): Long {
        close()

        var url = dataSpec.uri.toString()
        var redirects = 0

        while (true) {
            val resp = request(url, dataSpec.position, dataSpec.length, dataSpec.httpRequestHeaders)
            val code = resp.code

            // 302 / 301 / 307 / 308：换地址重来
            if (code in 300..399) {
                val loc = resp.header("location")
                    ?: throw IOException("HTTP $code 但响应里没有 Location 头  ← $url")
                if (++redirects > MAX_REDIRECTS) throw IOException("重定向超过 $MAX_REDIRECTS 次：$loc")
                val next = resolve(url, loc)
                Log.i(TAG, "GET $url → $code, Location=$next")
                resp.closeQuietly()
                url = next
                continue
            }

            if (code != 200 && code != 206) {
                // 带上响应体片段和出错 URL，下次排查不用猜
                val snippet = try {
                    val buf = ByteArray(256)
                    val n = resp.body.read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1).replace('\n', ' ').trim() else ""
                } catch (ignored: Throwable) {
                    ""
                }
                resp.closeQuietly()
                throw IOException("HTTP $code ${resp.statusLine}  ← $url  ${snippet.take(160)}")
            }

            socket = resp.socket
            body = resp.body
            responseHeaders = resp.headers
            currentUri = Uri.parse(url)
            currentPoolKey = resp.poolKey
            // 本次响应体还有多少字节没读 —— 读干净了才敢把连接还回池子
            bytesRemaining = resp.header("content-length")?.toLongOrNull() ?: -1L

            // 206：从 Content-Range 取总长；Content-Length 只是本次 range 的长度
            var total = -1L
            if (code == 206) {
                val cr = resp.header("content-range")
                total = parseTotalFromContentRange(cr)
                if (total > 0) {
                    // 把 Content-Length 改成总长，ExoPlayer 才知道真实时长
                    responseHeaders = LinkedHashMap(resp.headers).apply {
                        val old = get("Content-Length")
                        remove("Content-Length")
                        put("Content-Length", listOf(total.toString()))
                        if (old != null) put("Content-Range-Response-Length", old)
                    }
                }
            } else {
                // 200：服务器忽略了 Range，若请求位置不在 0，得自己跳过
                if (dataSpec.position > 0) skipBytes = dataSpec.position
                val cl = resp.header("content-length")?.toLongOrNull()
                total = cl ?: -1L
            }

            contentLength = if (total > 0) total else -1L
            lastOpenSummary = "HTTP $code, 总长=$contentLength, Content-Range=${resp.header("content-range")}, " +
                "请求position=${dataSpec.position}/length=${dataSpec.length}, 解码=${(resp.socket as? javax.net.ssl.SSLSocket)?.session?.protocol}" +
                "/${(resp.socket as? javax.net.ssl.SSLSocket)?.session?.cipherSuite}"
            sharedLog?.append(lastOpenSummary)?.append('\n')
            Log.i(TAG, "open $url → $lastOpenSummary")
            return contentLength
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = body ?: throw IOException("连接未打开")
        if (skipBytes > 0) {
            var remain = skipBytes
            while (remain > 0) {
                val n = stream.skip(remain)
                if (n <= 0) {
                    // skip 返回 0 时手动读一点，避免死循环
                    val one = ByteArray(1)
                    if (stream.read(one, 0, 1) <= 0) throw IOException("跳过大段数据时连接中断")
                    remain -= 1
                } else {
                    remain -= n
                }
            }
            // 跳过的字节也算读掉了，不然连接永远「没读完」→ 还不回池子
            if (bytesRemaining > 0) bytesRemaining = (bytesRemaining - skipBytes).coerceAtLeast(0)
            skipBytes = 0
        }
        val n = stream.read(buffer, offset, length)
        if (n > 0 && bytesRemaining > 0) {
            bytesRemaining = (bytesRemaining - n).coerceAtLeast(0)
        }
        // 网速显示用：把真实读到的字节累加进全局计数（跳过 seek 的字节不算下载）
        if (n > 0) totalBytesRead.addAndGet(n.toLong())
        return n
    }

    /**
     * 关闭本次读取。
     *
     * 关键：**响应体已经读干净时，把 socket 还回空闲池而不是关掉**。
     * 每次 seek 都要重新 TCP + TLS 握手，在这台机器上单次握手几百毫秒，
     * 拖动进度条会明显卡顿；复用连接能把这部分开销几乎清零。
     * 没读干净的（中途 seek / 出错）只能关掉，残留字节会污染下一条响应。
     */
    override fun close() {
        val s = socket
        val key = currentPoolKey
        val clean = s != null && key != null && bytesRemaining == 0L &&
            s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown

        if (clean) {
            // 缓冲流里可能还压着数据；正常情况响应体读完后它应该是空的。
            // 交给池子前不再关 socket，只丢掉流引用。
            body = null
            releaseIdle(key!!, s!!)
        } else {
            try { body?.close() } catch (ignored: Throwable) {}
            try { s?.close() } catch (ignored: Throwable) {}
        }
        body = null
        socket = null
        responseHeaders = null
        currentUri = null
        currentPoolKey = null
        bytesRemaining = -1
        skipBytes = 0
        contentLength = 0
    }

    // ==================== 内部实现 ====================

    private class HttpResponse(
        val code: Int,
        val statusLine: String,
        val headers: Map<String, List<String>>,
        val body: InputStream,
        val socket: Socket,
        /** 这条 socket 对应的池子 key；null 表示不可复用。 */
        val poolKey: String? = null
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()

        /**
         * 丢弃这条响应。
         *
         * 只在「不打算读响应体」的场合用（重定向、非 200/206）：
         * 响应体没读干净，连接里还留着没消费的字节，复用会污染下一条响应，
         * 所以这里**一律关掉**，绝不还池。
         */
        fun closeQuietly() {
            try { body.close() } catch (ignored: Throwable) {}
            try { socket.close() } catch (ignored: Throwable) {}
        }
    }

    /**
     * 发一次请求并读回响应头。
     *
     * @param position 起始偏移（绝对位置）
     * @param length 请求的长度；[com.google.android.exoplayer2.upstream.C.LENGTH_UNSET] 表示到结尾
     */
    private fun request(
        urlStr: String,
        position: Long,
        length: Long,
        extraHeaders: Map<String, String>?
    ): HttpResponse {
        val u = UrlKit.parse(urlStr)
        // 复用的连接可能已经被对端关掉了（服务端 keepalive 超时、NAT 老化）。
        // 这种情况下第一次写/读会抛异常，换条新连接重试一次即可 —— 不值得让用户看到报错。
        var reused = false
        try {
            return requestOnce(u, position, length, extraHeaders, allowReuse = true) { reused = it }
        } catch (e: IOException) {
            if (!reused) throw e
            Log.i(TAG, "复用的连接已失效，换新连接重试：${e.message}")
            return requestOnce(u, position, length, extraHeaders, allowReuse = false) {}
        }
    }

    private fun requestOnce(
        u: UrlKit.Parts,
        position: Long,
        length: Long,
        extraHeaders: Map<String, String>?,
        allowReuse: Boolean,
        onReuse: (Boolean) -> Unit
    ): HttpResponse {
        val host = u.host
        val port = u.port
        val path = u.path
        val query = u.query
        val key = poolKey(u)

        val idle = if (allowReuse) takeIdle(key) else null
        onReuse(idle != null)
        val s: Socket = idle ?: openSocket(u, host, port)
        s.soTimeout = READ_TIMEOUT_MS

        try {
            val sb = StringBuilder()
            sb.append("GET ").append(path).append(if (query != null) "?$query" else "").append(" HTTP/1.1\r\n")
            // ⚠️ Host 头**不能带默认端口**。
            // 2026-11-03 真机实测：写成 `Host: v10.example.com:443` 时 openresty
            // 匹配不到 server_name → 直接回 404；写成 `Host: v10.example.com` 才正常。
            sb.append("Host: ").append(host)
            if (port != 443 && port != 80) sb.append(':').append(port)
            sb.append("\r\n")
            sb.append("User-Agent: ").append(userAgent).append("\r\n")
            sb.append("Accept: */*\r\n")
            sb.append("Accept-Encoding: identity\r\n")
            // 明确要 keep-alive：默认就是长连接，但写出来免得中间设备/服务端猜
            sb.append("Connection: keep-alive\r\n")
            // ⚠️ 总是发 Range（哪怕从 0 开始）：
            //    v10 对 Range 请求回 206 + `Content-Range: bytes 0-xxx/总长`，
            //    总长只能从 Content-Range 拿到；不给 Range 它会回 200 全文，
            //    ExoPlayer 就不知道媒体总长（时长/进度条会错），也无法 seek。
            val rangeEnd = if (length == -1L) "" else (position + length - 1).toString()
            sb.append("Range: bytes=").append(position).append('-').append(rangeEnd).append("\r\n")
            if (token.isNotEmpty()) sb.append("X-Emby-Token: ").append(token).append("\r\n")
            sb.append(
                "X-Emby-Authorization: MediaBrowser Client=\"MyEmbyHub\", Device=\"TV\", " +
                    "DeviceId=\"myembyhub-tv\", Version=\"1.0.0\"\r\n"
            )
            extraHeaders?.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("\r\n")

            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            return readResponse(s, key)
        } catch (e: Throwable) {
            // 失败的连接绝不能还池（残留字节会污染下一条响应），直接关掉
            try { s.close() } catch (ignored: Throwable) {}
            throw e
        }
    }

    /** 建立（TLS）连接。http 走明文，https 走系统 JSSE + 内置锚点。 */
    private fun openSocket(u: UrlKit.Parts, host: String, port: Int): Socket {
        if (u.scheme == "https") {
            val factory = TlsStack.socketFactory(ctx, false, TrustMode.BUNDLED)
                ?: throw IOException("无法构造 TLS socket factory")
            // ⚠️ 只包 factory；这里拿到的是**真 socket**，直接设协议即可
            val ssl = factory.createSocket() as SSLSocket
            // ⚠️⚠️ 必须显式设 SNI！
            //    无参 createSocket() 建出来的 socket 不带 SNI，
            //    挂在 Cloudflare 前面的服务器会**直接拒握手**。
            //    （业务接口走 HttpsURLConnection，libcore 内部会设，所以只有取流挂）
            TlsStack.applyServerName(ssl, host)
            ssl.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            ssl.startHandshake()
            return ssl
        }
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return plain
    }

    /** 读响应头（状态行 + 头字段），body 直接接着 socket 流读。 */
    private fun readResponse(s: Socket, poolKey: String?): HttpResponse {
        // ⚠️ 逐字节读到 "\r\n\r\n" 为止 —— 此时 socket 流的位置**正好在 body 开头**，
        //    所以**不需要** unread 任何东西。
        //    （2026-11-03 真机踩过：把头结束符 "\r\n\r\n" 当成 body 前 4 字节 unread 回去，
        //      ExoPlayer 读到的第一个字节是 0D 0A 而不是 'ftyp'，
        //      十五个 extractor 全部拒绝 → PARSING_CONTAINER_UNSUPPORTED。）
        val raw = BufferedInputStream(s.getInputStream(), 8192)
        val head = ByteArrayOutputStream()
        val term = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (head.size() < MAX_HEADER_BYTES) {
            val b = raw.read()
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
            try { s.close() } catch (ignored: Throwable) {}
            throw IOException("响应头不完整（收到 ${head.size()} 字节）")
        }
        // head 的内容就是完整响应头（含结尾的 \r\n\r\n）
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
        val statusLine = lines.firstOrNull() ?: ""
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

        // 保留服务器原始大小写：ExoPlayer 按 "Content-Length" 精确匹配
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            headers.getOrPut(k) { ArrayList() }.add(v)
        }

        return HttpResponse(code, statusLine, headers, raw, s, poolKey)
    }

    /** 从 `bytes 0-2047/294813105` 里解析总长。 */
    private fun parseTotalFromContentRange(contentRange: String?): Long {
        if (contentRange.isNullOrEmpty()) return -1L
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0) return -1L
        val total = contentRange.substring(slash + 1).trim()
        if (total == "*") return -1L
        return total.toLongOrNull() ?: -1L
    }

    /** 见 [UrlKit.resolve]（手工解析，避开 4.3 上 java.net.URL 的 query 重复坑）。 */
    private fun resolve(base: String, location: String): String = UrlKit.resolve(base, location)

    /** 供调试用：当前协商到的 TLS 参数。 */
    fun describeTls(): String = try {
        val s = socket as? SSLSocket ?: return "(非 TLS)"
        "${s.session.protocol} / ${s.session.cipherSuite}"
    } catch (e: Throwable) {
        "(未知)"
    }
}
