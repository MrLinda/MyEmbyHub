package work.luxiaoxiao.tvcheck

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 诊断矩阵。
 *
 * 目标：在真机上一次性回答「这台电视到底能不能和服务器完成 TLS 握手、卡在哪一步」，
 * 并且结果能写到 /sdcard 文件里，用 adb 拉回电脑（不用在电视上拍照）。
 *
 * 为什么需要它：
 * 目标电视是 Android 4.3 / API 18，JSSE 是老版 Apache Harmony + OpenSSL 1.0.1 时代的东西，
 * 默认只启用 TLS 1.0，而目标服务器最低要 TLS 1.1。手工在电视上点按钮 + 拍照回传
 * 效率极低，所以改成「adb 触发 → 自动跑矩阵 → 写文件 → adb 拉取」。
 *
 * 每条测试都单独记录：
 *   - 目标：host:port
 *   - 用的栈：系统 JSSE / Bouncy Castle
 *   - 协议：TLSv1 / TLSv1.1 / TLSv1.2
 *   - 信任：ALL（不校验）/ BUNDLED（内置锚点）
 *   - 结果：成功（含协商到的协议与套件）或失败（含异常链）
 */
object TlsMatrix {

    private const val TAG = "TvCheck"
    const val RESULT_FILE = "/sdcard/tvcheck-tls.txt"

    /** 仅用于探测服务端 TLS 行为，不校验证书。 */
    private val TRUST_ALL = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun run(ctx: Context, host: String, port: Int, out: (String) -> Unit) {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append('\n')
            out(s)
        }

        line("===== TLS 诊断矩阵 =====")
        line("设备: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        line("目标: $host:$port")
        line("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        line("")

        line("--- A) 本机 TLS 能力（系统 JSSE） ---")
        line(dumpSystemCapability())
        line("")

        line("--- B) 系统 JSSE × 协议 × 信任模式 ---")
        for (proto in PROTOCOLS) {
            for (mode in TRUST_MODES) {
                line(handshake(ctx, "JSSE", host, port, proto, mode, null))
            }
        }
        line("")

        line("--- C) 系统 JSSE × 逐个密码套件（TLSv1.2） ---")
        for (suite in candidateSuites()) {
            line(handshake(ctx, "JSSE", host, port, "TLSv1.2", TrustMode.ALL, suite))
        }
        line("")

        line("--- C2) 关键对照：socket 包装 vs 不包装，直接设 enabledProtocols ---")
        line("  这一节用来定位「为什么 App 里登录握手失败，但裸握手成功」")
        line("  （HttpsURLConnection 走的是 createSocket() 无参重载）")
        line(compareWrappedVsPlain(ctx, host, port))
        line("")

        line("--- C3) HttpsURLConnection 真实路径（App 实际用的就是它）---")
        line(httpsUrlConnectionMatrix(ctx, host, port))
        line("")

        line("--- D) 真实登录路径（POST /Users/AuthenticateByName）---")
        line("  这一节直接验证 App 登录用的那条路：HttpsURLConnection + 自定义 socket factory")
        line(loginMatrix(ctx, host, port))
        line("")

        line("--- E) 带 token 的真实 API 调用（端到端）---")
        line(authenticatedApiProbe(ctx, host, port))
        line("")

        line("--- E) 结论提示 ---")
        line(conclusion(ctx))
        line("")
        line("===== 诊断结束 =====")

        try {
            File(RESULT_FILE).writeText(sb.toString())
            Log.i(TAG, "诊断结果已写入 $RESULT_FILE")
        } catch (e: Throwable) {
            Log.e(TAG, "写诊断结果文件失败", e)
        }
    }

    // ------------------------------------------------------------------

    private val PROTOCOLS = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2")
    private val TRUST_MODES = arrayOf(TrustMode.ALL, TrustMode.BUNDLED)

    enum class TrustMode { ALL, BUNDLED }

    private fun dumpSystemCapability(): String {
        val sb = StringBuilder()
        try {
            val f = SSLSocketFactory.getDefault() as SSLSocketFactory
            sb.append("  factory: ").append(f.javaClass.name).append('\n')
            sb.append("  supported protocols: ").append(reflectArray(f, "getSupportedProtocols")).append('\n')
            sb.append("  default protocols: ").append(reflectArray(f, "getDefaultProtocols")).append('\n')
            val suites = f.supportedCipherSuites
            sb.append("  supported suites: ").append(suites.size).append(" 个\n")
            sb.append("    ").append(suites.joinToString(", ")).append('\n')
        } catch (e: Throwable) {
            sb.append("  读取能力失败: ").append(e).append('\n')
        }
        // 有哪些安全 provider
        try {
            val names = java.security.Security.getProviders().map { it.name }
            sb.append("  providers: ").append(names.joinToString(", ")).append('\n')
        } catch (ignored: Throwable) {
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * 挑代表性套件逐个试：
     * 服务器若只认某一大类（比如只认 GCM），逐个试就能定位到具体哪一类被接受。
     */
    private fun candidateSuites(): List<String> {
        val want = arrayOf(
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_3DES_EDE_CBC_SHA"
        )
        val have = try {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).supportedCipherSuites.toSet()
        } catch (e: Throwable) {
            emptySet()
        }
        val picked = want.filter { it in have }
        return if (picked.isEmpty()) {
            // 命名对不上就取前 12 个
            have.take(12).toList()
        } else {
            picked
        }
    }

    /**
     * 对照实验：定位「为什么 App 里登录握手失败，但裸握手成功」。
     *
     * 已知现象（4.3 真机实测）：
     *   - 裸握手：createSocket() + connect() + enabledProtocols=[TLSv1.2] → ✓ 成功
     *   - App 登录：HttpsURLConnection + 自定义 SSLSocketFactory → ✗ 握手失败
     *
     * 怀疑点：HttpsURLConnection 内部拿到 socket 后会再调 setEnabledProtocols，
     * 把我们抬高到 1.2 的协议又覆盖回默认的 TLSv1 → 实际发出的是 TLS1.0
     * → 服务器回 tlsv1 alert protocol version（诊断 B 段 TLSv1 那行正是这个错）。
     *
     * 这一节用三种方式验证：
     *   方式1 裸握手，直接设 enabledProtocols
     *   方式2 经 forceTls12 包装后再设 enabledProtocols
     *   方式3 模拟 HttpsURLConnection 的调用顺序（先 connect 再 setEnabledProtocols）
     */
    private fun compareWrappedVsPlain(ctx: Context, host: String, port: Int): String {
        val sb = StringBuilder()

        sb.append("  方式1 裸握手，直接设 enabledProtocols（不包装）:\n")
        for (proto in arrayOf("TLSv1.2", "TLSv1.1")) {
            sb.append("    ").append(plainHandshake(ctx, host, port, proto, false)).append('\n')
        }

        sb.append("  方式2 经 forceTls12 包装，再设 enabledProtocols:\n")
        for (proto in arrayOf("TLSv1.2", "TLSv1.1")) {
            sb.append("    ").append(plainHandshake(ctx, host, port, proto, true)).append('\n')
        }

        sb.append("  方式3 模拟 HttpsURLConnection 顺序（connect 之后再 setEnabledProtocols）:\n")
        for (proto in arrayOf("TLSv1.2", "TLSv1.1")) {
            sb.append("    ").append(plainHandshake(ctx, host, port, proto, false, setAfterConnect = true)).append('\n')
        }

        sb.append("  方式4 经包装 + connect 之后再 setEnabledProtocols（最接近 App 实际路径）:\n")
        for (proto in arrayOf("TLSv1.2", "TLSv1.1")) {
            sb.append("    ").append(plainHandshake(ctx, host, port, proto, true, setAfterConnect = true)).append('\n')
        }

        return sb.toString().trimEnd('\n')
    }

    private fun plainHandshake(
        ctx: Context,
        host: String,
        port: Int,
        proto: String,
        wrapped: Boolean,
        setAfterConnect: Boolean = false
    ): String {
        var socket: SSLSocket? = null
        return try {
            val base = TlsStack.socketFactory(ctx, false, TrustMode.ALL)!!
            val factory = if (wrapped) TlsStack.forceTls12(base) else base
            socket = factory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 8000
            if (!setAfterConnect) {
                socket.enabledProtocols = arrayOf(proto)
            } else {
                // HttpsURLConnection 的典型顺序：连上之后才设协议
                socket.enabledProtocols = arrayOf(proto)
            }
            socket.startHandshake()
            val s = socket.session
            val eff = s.protocol
            "$proto → ✓ 成功  协商协议=$eff  套件=${s.cipherSuite}" +
                (if (eff == proto) "" else "  ⚠ 实际协商的不是 $proto！")
        } catch (e: Throwable) {
            "$proto → ✗ 失败  ${firstLine(e)}"
        } finally {
            try { socket?.close() } catch (ignored: Throwable) {}
        }
    }

    /**
     * 真实登录路径验证。
     *
     * 走 App 实际用的代码：HttpsURLConnection + POST /Users/AuthenticateByName。
     * 三种 factory 各试一次，看哪种能换回 HTTP 200（401 也算 TLS 通了）。
     */
    private fun loginMatrix(ctx: Context, host: String, port: Int): String {
        val sb = StringBuilder()
        val body = "{\"Username\":\"__probe__\",\"Pw\":\"__probe__\"}"

        sb.append("  默认 factory:\n")
        sb.append("    ").append(postProbe(host, port, null, body)).append('\n')

        sb.append("  自定义 factory（BUNDLED 锚点），不包装:\n")
        val plain = TlsStack.socketFactory(ctx, false, TrustMode.BUNDLED)
        sb.append("    ").append(postProbe(host, port, plain, body)).append('\n')

        sb.append("  forceTls12 包装后的 factory（真 socket + 抬协议）:\n")
        val wrapped = plain?.let { TlsStack.forceTls12(it) }
        sb.append("    ").append(postProbe(host, port, wrapped, body)).append('\n')

        return sb.toString().trimEnd('\n')
    }

    private fun postProbe(
        host: String,
        port: Int,
        factory: javax.net.ssl.SSLSocketFactory?,
        body: String
    ): String {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL("https://$host:$port/Users/AuthenticateByName")
                .openConnection() as java.net.HttpURLConnection
            if (factory != null && conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = factory
            }
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty(
                "X-Emby-Authorization",
                "MediaBrowser Client=\"TV Check\", Device=\"TV\", DeviceId=\"tvcheck\", Version=\"1.0.0\""
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val cipher = (conn as? javax.net.ssl.HttpsURLConnection)?.cipherSuite
            "→ ✓ HTTP $code  套件=$cipher"
        } catch (e: Throwable) {
            "→ ✗ ${firstLine(e)}"
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    /**
     * 端到端验证：带 AccessToken 调真实 API。
     *
     * 依次试几个 Emby 公开/需鉴权的接口，能拿到 JSON 就说明
     * 「TLS + 认证 + 业务 API」整条链路在这台电视上通了。
     */
    private fun authenticatedApiProbe(ctx: Context, host: String, port: Int): String {
        val sb = StringBuilder()
        val token = readApiKey()
        if (token.isEmpty()) {
            sb.append("  conf 里没有 api_key，先跑一次登录（cmd=login）再来看这一节\n")
            return sb.toString().trimEnd('\n')
        }
        sb.append("  已读取 api_key（长度 ${token.length}）\n")

        val factory = TlsStack.forceTls12(
            TlsStack.socketFactory(ctx, false, TrustMode.BUNDLED)!!
        )

        val paths = listOf(
            "/System/Info/Public" to "公开信息",
            "/Users/Me" to "当前用户",
            "/Items?Limit=1" to "媒体库首条"
        )
        for ((path, label) in paths) {
            sb.append("  $label  GET $path → ")
            sb.append(apiGet(host, port, path, token, factory)).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    private fun apiGet(
        host: String,
        port: Int,
        path: String,
        token: String,
        factory: javax.net.ssl.SSLSocketFactory
    ): String {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL("https://$host:$port$path")
                .openConnection() as java.net.HttpURLConnection
            if (conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = factory
            }
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Emby-Token", token)
            conn.setRequestProperty(
                "X-Emby-Authorization",
                "MediaBrowser Client=\"TV Check\", Device=\"TV\", DeviceId=\"tvcheck\", Version=\"1.0.0\""
            )
            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Throwable) {
                "(读 body 失败)"
            }
            "✓ HTTP $code  ${body.take(160).replace(Regex("(?i)\"AccessToken\"\\s*:\\s*\"[^\"]*\""), "\"AccessToken\":\"***\"")}"
        } catch (e: Throwable) {
            "✗ ${firstLine(e)}"
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
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

    /** 只取异常链里第一行有信息的内容，避免刷屏。 */
    private fun firstLine(e: Throwable): String {
        val msg = e.message ?: ""
        val idx = msg.indexOf('\n')
        val head = if (idx > 0) msg.substring(0, idx) else msg
        return "${e.javaClass.simpleName}: $head"
    }

    /**
     * HttpsURLConnection 真实路径对照。
     *
     * 这一节直接复现 App 登录时的调用方式，只是把 URL 换成 GET /System/Info/Public：
     *   1) 完全不设 sslSocketFactory（系统默认）
     *   2) 设自定义 factory 但不包装
     *   3) 设 forceTls12 包装后的 factory
     * 看哪一种能拿到 HTTP 200。
     */
    private fun httpsUrlConnectionMatrix(ctx: Context, host: String, port: Int): String {
        val sb = StringBuilder()

        // 1) 系统默认
        sb.append("  默认 factory（不设 sslSocketFactory）:\n")
        sb.append("    ").append(httpsGet(host, port, null)).append('\n')

        // 2) 自定义 factory，不包装
        sb.append("  自定义 factory（BUNDLED 锚点），不包装:\n")
        val plain = TlsStack.socketFactory(ctx, false, TrustMode.BUNDLED)
        sb.append("    ").append(httpsGet(host, port, plain)).append('\n')

        // 3) forceTls12 包装
        sb.append("  forceTls12 包装后的 factory:\n")
        val wrapped = plain?.let { TlsStack.forceTls12(it) }
        sb.append("    ").append(httpsGet(host, port, wrapped)).append('\n')

        return sb.toString().trimEnd('\n')
    }

    private fun httpsGet(host: String, port: Int, factory: javax.net.ssl.SSLSocketFactory?): String {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL("https://$host:$port/System/Info/Public")
                .openConnection() as java.net.HttpURLConnection
            if (factory != null && conn is javax.net.ssl.HttpsURLConnection) {
                conn.sslSocketFactory = factory
            }
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "tvcheck-diag/1.0")
            val code = conn.responseCode
            "→ ✓ HTTP $code  套件=${(conn as? javax.net.ssl.HttpsURLConnection)?.cipherSuite}"
        } catch (e: Throwable) {
            "→ ✗ ${firstLine(e)}"
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    /**
     * 一次裸握手测试。返回一行摘要 + 失败时的异常链。
     *
     * stack = "JSSE" 走系统栈，"BC" 走 Bouncy Castle。
     */
    private fun handshake(
        ctx: Context,
        stack: String,
        host: String,
        port: Int,
        proto: String,
        mode: TrustMode,
        suite: String?
    ): String {
        val label = buildString {
            append("  [").append(stack).append("] ")
            append(proto)
            if (suite != null) append(" / ").append(suite)
            append(" / trust=").append(mode)
        }
        var socket: SSLSocket? = null
        return try {
            val useBc = stack == "BC"
            val factory = TlsStack.socketFactory(ctx, useBc, mode)
                ?: return "$label → ✗ 失败  无法创建 $stack 的 SSLSocketFactory"
            socket = factory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 8000
            socket.enabledProtocols = arrayOf(proto)
            if (suite != null) socket.enabledCipherSuites = arrayOf(suite)
            socket.startHandshake()
            val s = socket.session
            "$label → ✓ 成功  协议=${s.protocol}  套件=${s.cipherSuite}"
        } catch (e: Throwable) {
            val chain = StringBuilder()
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 5) {
                if (depth > 0) chain.append(" <- ")
                chain.append(t.javaClass.simpleName).append(": ").append(t.message)
                t = t.cause
                depth++
            }
            "$label → ✗ 失败  $chain"
        } finally {
            try { socket?.close() } catch (ignored: Throwable) {}
        }
    }

    /**
     * Bouncy Castle 探测。
     *
     * 如果 BC 依赖没打进 APK，这里会返回「未集成」，不影响其它测试。
     * BC 是纯 Java 实现的 TLS 1.2 栈，不依赖系统 OpenSSL，
     * 理论上在 API 16+ 都能跑（它的 android 变体 bcprov-android / bctls-android）。
     */
    /** 根据上面的结果给一句人话结论。 */
    private fun conclusion(ctx: Context): String {
        return try {
            val text = File(RESULT_FILE).let { if (it.exists()) it.readText() else "" }
            if (text.isEmpty()) "  (结果尚未落盘，无法自动归纳)"
            else "  见上方各分节；成功的行即为可用组合。"
        } catch (e: Throwable) {
            "  (归纳失败: ${e.message})"
        }
    }

    /** Android 上这些方法在公开 stub 里是 @hide，只能反射。 */
    private fun reflectArray(target: Any, method: String): String {
        return try {
            val v = target.javaClass.getMethod(method).invoke(target)
            if (v is Array<*>) v.joinToString(", ") else v.toString()
        } catch (e: Throwable) {
            "(不可用: ${e.javaClass.simpleName})"
        }
    }
}

