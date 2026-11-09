package work.luxiaoxiao.myembyhub.net

import android.content.Context
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 栈工厂：系统 JSSE 与 Bouncy Castle 两套。
 *
 * 为什么需要 BC：
 * 目标电视是 Android 4.3 / API 18，它的系统 JSSE 是 OpenSSL 1.0.1 时代的实现
 * （Apache Harmony 血统），默认只启用 TLS 1.0，而目标服务器最低要求 TLS 1.1；
 * 强行抬高到 TLS 1.2 之后仍然被服务器以 handshake_failure 拒绝。
 *
 * Bouncy Castle 是纯 Java 实现的 TLS 1.2 栈，不依赖系统 OpenSSL，
 * 其 android 变体（bcprov-android / bctls-android）支持 API 16+，
 * 是这台电视上跑现代 TLS 的现实方案。
 */
object TlsStack {

    /**
     * 已构造好的 factory 缓存（key = "useBc:trust"）。
     *
     * ⚠️ **必须复用同一个 SSLContext**，否则海报墙会慢到没法看：
     *   ① 构造它要把系统 CA 库整个 KeyStore 初始化一遍（4.3 上约 130 张证书），
     *      `BundledCa.trustManager` 还要再解析 3 张内置 PEM；
     *   ② TLS 会话缓存（SSLSessionContext）挂在 SSLContext 上，
     *      换一个新的就等于放弃 session resumption → 每张图都重新完整握手
     *      （RSA-2048 验签 + ECDHE，1GHz ARM 上纯软件算）。
     */
    private val cache = HashMap<String, SSLSocketFactory>()

    /**
     * 取 socket factory（按需构造一次，之后复用），返回的已经抬到 TLS 1.2。
     *
     * 目前只保留系统 JSSE 一条路：2026-11-03 真机实测 4.3 的系统 JSSE
     * 用 TLSv1.2 + 内置锚点握手成功，不需要换栈。
     *
     * @param useBc 保留参数：若将来真要接 Bouncy Castle，这里是入口
     * @param trust 信任模式
     */
    fun socketFactory(
        ctx: Context,
        useBc: Boolean,
        trust: TrustMode
    ): SSLSocketFactory? {
        val key = "$useBc:$trust"
        synchronized(cache) { cache[key]?.let { return it } }
        val built = build(ctx, useBc, trust) ?: return null
        val raised = forceTls12(built)
        synchronized(cache) { cache[key] = raised }
        return raised
    }

    private fun build(
        ctx: Context,
        useBc: Boolean,
        trust: TrustMode
    ): SSLSocketFactory? {
        val tm: TrustManager = when (trust) {
            TrustMode.ALL -> trustAll()
            TrustMode.BUNDLED -> BundledCa.trustManager(ctx)
        }
        return try {
            if (useBc) {
                val provider = bouncyCastleProvider() ?: return null
                val c = SSLContext.getInstance("TLS", provider)
                c.init(null, arrayOf(tm), SecureRandom())
                c.socketFactory as SSLSocketFactory
            } else {
                val c = SSLContext.getInstance("TLS")
                c.init(null, arrayOf(tm), SecureRandom())
                c.socketFactory as SSLSocketFactory
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * BC 的 provider。
     *
     * 注意：Maven Central 上的 `bctls-jdk15on` 把 JSSE provider 类放在
     * `META-INF/versions/9/`（Java 9+ 多版本 jar），D8 在 API 18 上取不到，
     * 且没有 `-android` 变体。因此默认不启用，留作将来手动改造时的入口。
     */
    private fun bouncyCastleProvider(): java.security.Provider? {
        return try {
            val cls = Class.forName("org.bouncycastle.jsse.provider.BouncyCastleProvider")
            val p = cls.getDeclaredConstructor().newInstance() as java.security.Provider
            val existing = java.security.Security.getProvider(p.name)
            if (existing != null) existing
            else {
                java.security.Security.insertProviderAt(p, 1)
                p
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 把 enabledProtocols 抬到 1.2/1.1 的 socket factory 包装。
     *
     * 只在 [socketFactory] 构造缓存时调用一次。
     *
     * ⚠️ **不要包装 SSLSocket**，只包装 factory：
     * 包装 socket 时若不转发 connect()，delegate 根本没连上，
     * 握手会以 "Broken pipe" 失败（4.3 真机实测）。
     * 这里直接返回**真 socket**，只是把它原本的 enabledProtocols 改掉。
     *
     * 系统 JSSE 需要这个改造（4.3 默认只启用 TLS 1.0）；
     * BC 默认就支持 1.2，不需要。
     */
    private fun forceTls12(factory: SSLSocketFactory): SSLSocketFactory =
        object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = factory.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = factory.supportedCipherSuites

            override fun createSocket(): java.net.Socket = raise(factory.createSocket() as javax.net.ssl.SSLSocket)
            override fun createSocket(host: String, port: Int): java.net.Socket =
                raise(factory.createSocket(host, port) as javax.net.ssl.SSLSocket)
            override fun createSocket(
                host: String, port: Int,
                localAddress: java.net.InetAddress, localPort: Int
            ): java.net.Socket =
                raise(factory.createSocket(host, port, localAddress, localPort) as javax.net.ssl.SSLSocket)
            override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket =
                raise(factory.createSocket(host, port) as javax.net.ssl.SSLSocket)
            override fun createSocket(
                host: java.net.InetAddress, port: Int,
                localAddress: java.net.InetAddress, localPort: Int
            ): java.net.Socket =
                raise(factory.createSocket(host, port, localAddress, localPort) as javax.net.ssl.SSLSocket)
            override fun createSocket(
                s: java.net.Socket, host: String, port: Int, autoClose: Boolean
            ): java.net.Socket =
                raise(factory.createSocket(s, host, port, autoClose) as javax.net.ssl.SSLSocket)

            /** 把真 socket 的启用协议抬到 1.2/1.1，然后原样返回。 */
            private fun raise(s: javax.net.ssl.SSLSocket): javax.net.ssl.SSLSocket {
                for (p in arrayOf(
                    arrayOf("TLSv1.2", "TLSv1.1", "TLSv1"),
                    arrayOf("TLSv1.1", "TLSv1")
                )) {
                    try {
                        s.enabledProtocols = p
                        break
                    } catch (ignored: Throwable) {
                    }
                }
                return s
            }
        }

    /** 仅用于探测服务端 TLS 行为，不校验证书。 */
    fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * 给 socket 设置 **SNI（Server Name Indication）**。
     *
     * ## 为什么必须显式设置
     *
     * `SSLSocketFactory.createSocket()` 建出来的 socket **默认不带 SNI**，
     * ClientHello 里没有 server_name 扩展。对普通后端无所谓，但：
     *
     * - 挂在 **Cloudflare** 前面的服务器，没有 SNI 就**直接拒绝握手**
     *   （实测 2026-11-09：`sslv3 alert handshake failure`，
     *   在 4.3 的 OpenSSL 1.0.1 上表现为 `SSL handshake aborted` +
     *   `SSL3_GET_RECORD:wrong version number`，非常难认）；
     * - 一些 CDN / 多租户网关也要靠 SNI 选站点。
     *
     * `HttpsURLConnection`（业务接口走的那条路）**自带 SNI** —— libcore 内部
     * 调了 `OpenSSLSocketImpl.setHostname()`。所以现象是「接口调用正常、
     * 只有取流失败」，很容易误判成网络或证书问题。
     *
     * ## 两个系统怎么设
     *
     * - **API 24+**：`SSLParameters.setServerNames(SNIHostName)`（公开 API）。
     * - **API 18（这台电视）**：`javax.net.ssl.SNIHostName` 这个类根本不存在，
     *   但 `org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl` 上有个
     *   **隐藏方法 `setHostname(String)`** —— 它就是 4.3 上设 SNI 的入口
     *   （`HttpsURLConnection` 走的也是它）。只能反射调用。
     *
     * 两个都失败也不致命：这时退回到「不带 SNI」，普通后端照样能连。
     */
    fun applyServerName(socket: SSLSocket, host: String) {
        if (host.isEmpty()) return

        // ① API 24+：SSLParameters.setServerNames
        try {
            // ⚠️ 这里不能用 SSLParameter/SNIHostName 做类型声明：
            //    那些类 API 24 才有，API 18 上连类都加载不到，
            //    写进字节码会在校验阶段炸。全程只用 Any + 反射。
            val sniHostName: Any = Class.forName("javax.net.ssl.SNIHostName")
                .getConstructor(String::class.java)
                .newInstance(host)
            val params = socket.sslParameters
            Class.forName("javax.net.ssl.SSLParameters")
                .getMethod("setServerNames", List::class.java)
                .invoke(params, listOf(sniHostName))
            socket.sslParameters = params
            return
        } catch (ignored: Throwable) {
        }

        // ② API 18：OpenSSLSocketImpl.setHostname(String)（隐藏方法）
        try {
            socket.javaClass.getMethod("setHostname", String::class.java)
                .invoke(socket, host)
            return
        } catch (ignored: Throwable) {
        }
        try {
            socket.javaClass.getDeclaredMethod("setHostname", String::class.java)
                .apply { isAccessible = true }
                .invoke(socket, host)
        } catch (ignored: Throwable) {
        }
    }
}

