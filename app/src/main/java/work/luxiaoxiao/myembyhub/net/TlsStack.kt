package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 栈工厂：**Conscrypt（首选）与系统 JSSE（兜底）两条路**。
 *
 * ## 为什么必须换栈（2026-11-18 实测）
 *
 * 目标电视是 Android 4.3 / API 18，系统 JSSE 由 OpenSSL 1.0.1 驱动
 * （Apache Harmony 血统），它**只有 CBC 套件，完全没有 GCM / AEAD / ChaCha20 的实现**。
 *
 * 现代服务端（nginx 默认配置、Cloudflare 托管、各类 CDN 线路）往往只开 GCM：
 *
 * ```
 * 电视 ClientHello  →  只带 ECDHE-ECDSA-AES128-SHA 这类 CBC 套件
 * 服务端 ServerHello →  我一个都不支持 → TLS alert 40 handshake_failure
 * ```
 *
 * 实测对照（同一时刻、同一台机器）：
 *
 * | 套件 | cf.wedg.top:1234 | tt.wedg.top:443 |
 * |---|---|---|
 * | ECDHE-ECDSA-AES128-SHA (CBC) | 失败 alert 40 | 成功 |
 * | ECDHE-ECDSA-AES256-SHA (CBC) | 失败 alert 40 | 成功 |
 * | ECDHE-ECDSA-AES128-GCM-SHA256 | 成功 | 成功 |
 *
 * 也就是说：**不是证书问题、不是 TLS 版本问题，是套件实现缺失**。
 * [forceTls12] 只能抬 `enabledProtocols`，凭空变不出 GCM 实现 ——
 * 那个年代的系统 JSSE 里根本没有这段代码。
 *
 * ## Conscrypt 怎么解决
 *
 * Conscrypt 是 Google 把 BoringSSL 包成 JSSE Provider 的库，AAR 里**自带原生库**
 * （`libconscrypt_jni.so`，armeabi-v7a 1.28MB），官方声明支持 API 9+。
 * 它不接管系统全局 Provider（那会波及整个进程），而是自己 new 一个 Provider：
 *
 * ```kotlin
 * val provider = Conscrypt.newProvider()
 * val c = SSLContext.getInstance("TLS", provider)
 * ```
 *
 * 4.3 上它走 `PreKitKatPlatformOpenSSLSocketImplAdapter`（继承 4.3 的
 * `org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl`），
 * 这样 `HttpsURLConnection` 和 ExoPlayer 这些按老接口拿 socket 的地方都不用改。
 *
 * 换栈之后 GCM / ChaCha20 / TLS 1.3 全都有，上面那个 cf 线路直接能连。
 *
 * ## 代价与退路
 *
 * APK +约 1.5MB（classes 0.47MB + armeabi-v7a so 1.28MB）。
 * 万一某个 ROM 上 Conscrypt 原生库加载失败（`UnsatisfiedLinkError`）或者行为异常，
 * 会**自动退回系统 JSSE**；用户也可以在「设置 → TLS 实现」里手动锁回系统栈
 * （[TlsImpl.SYSTEM]）—— 老代码路径一条都没删。
 */
object TlsStack {

    private const val TAG = "TlsStack"

    /** 用户可选的 TLS 实现。 */
    enum class TlsImpl {
        /** 优先 Conscrypt，构造失败自动退回系统 JSSE（默认）。 */
        AUTO,

        /** 强制 Conscrypt；失败就报错，不静默降级（排查用）。 */
        CONSCRYPT,

        /** 强制系统 JSSE：只有 CBC 套件，新式站点会握手失败。 */
        SYSTEM,
    }

    /**
     * 已构造好的 factory 缓存（key = "impl:trust"）。
     *
     * ⚠️ **必须复用同一个 SSLContext**，否则海报墙会慢到没法看：
     *   ① 构造它要把系统 CA 库整个 KeyStore 初始化一遍（4.3 上约 130 张证书），
     *      `BundledCa.trustManager` 还要再解析 3 张内置 PEM，
     *      Conscrypt 还要加载一遍原生库；
     *   ② TLS 会话缓存（SSLSessionContext）挂在 SSLContext 上，
     *      换一个新的就等于放弃 session resumption → 每张图都重新完整握手
     *      （RSA-2048 验签 + ECDHE，1GHz ARM 上纯软件算）。
     */
    private val cache = HashMap<String, SSLSocketFactory>()

    /** 运行时是否真的成功构造过 Conscrypt factory（给设置页显示状态用）。 */
    @Volatile
    private var conscryptOk: Boolean? = null

    /** 当前进程里 Conscrypt 可不可用（null = 还没试过）。 */
    fun conscryptAvailable(): Boolean? = conscryptOk

    // ==================== 实现选择（持久化） ====================

    private const val PREFS = "myembyhub_tls"
    private const val KEY_IMPL = "impl"

    @Volatile
    private var pref: TlsImpl = TlsImpl.AUTO

    /** 是否已经从 SharedPreferences 读回过（懒加载，第一次取 factory 时读）。 */
    @Volatile
    private var prefLoaded = false

    /**
     * 从 SharedPreferences 读回用户选择。
     *
     * 会在 [socketFactory] 里**懒调用**一次，所以不需要在 Application / Activity
     * 里显式初始化；重复调无害（设置页改完想立刻生效可以直接调）。
     */
    fun loadPref(ctx: Context) {
        val raw = try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_IMPL, null)
        } catch (e: Throwable) {
            null
        }
        pref = when (raw) {
            "conscrypt" -> TlsImpl.CONSCRYPT
            "system" -> TlsImpl.SYSTEM
            else -> TlsImpl.AUTO
        }
        prefLoaded = true
    }

    fun pref(): TlsImpl = pref

    /** 改实现并清缓存（缓存里存的是旧栈造出来的 factory，必须作废）。 */
    fun setPref(ctx: Context, impl: TlsImpl) {
        pref = impl
        try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(
                    KEY_IMPL,
                    when (impl) {
                        TlsImpl.CONSCRYPT -> "conscrypt"
                        TlsImpl.SYSTEM -> "system"
                        TlsImpl.AUTO -> "auto"
                    }
                ).commit()
        } catch (ignored: Throwable) {
        }
        synchronized(cache) { cache.clear() }
    }

    /**
     * 取 socket factory（按需构造一次，之后复用）。
     *
     * @param trust 信任模式
     * @param impl 实现覆盖；默认用 [pref] 里存的那个
     */
    fun socketFactory(
        ctx: Context,
        trust: TrustMode,
        impl: TlsImpl? = null
    ): SSLSocketFactory? {
        if (!prefLoaded) loadPref(ctx)
        val use = impl ?: pref
        val key = "$use:$trust"
        synchronized(cache) { cache[key]?.let { return it } }
        val built = build(ctx, trust, use) ?: return null
        // Conscrypt 自带 1.2/1.3，不需要抬协议；系统栈要抬（4.3 默认只开 TLS 1.0）
        val ready = if (use == TlsImpl.CONSCRYPT) built else forceTls12(built)
        synchronized(cache) { cache[key] = ready }
        return ready
    }

    private fun build(
        ctx: Context,
        trust: TrustMode,
        impl: TlsImpl
    ): SSLSocketFactory? {
        val tm: TrustManager = when (trust) {
            TrustMode.ALL -> trustAll()
            TrustMode.BUNDLED -> BundledCa.trustManager(ctx)
        }
        if (impl != TlsImpl.SYSTEM) {
            conscrypt(tm)?.let {
                // 成功也记一行：这台电视没法截图，TLS 到底走的哪条路
                // 只能靠 logcat 认（`adb logcat -s TlsStack`）。
                if (conscryptOk != true) Log.i(TAG, "TLS 使用 Conscrypt（GCM/ChaCha20 可用）")
                conscryptOk = true
                return it
            }
            conscryptOk = false
            if (impl == TlsImpl.CONSCRYPT) {
                Log.w(TAG, "强制 Conscrypt 但构造失败")
                return null
            }
            Log.i(TAG, "Conscrypt 不可用，退回系统 JSSE（只有 CBC 套件）")
        }
        return try {
            val c = SSLContext.getInstance("TLS")
            c.init(null, arrayOf(tm), SecureRandom())
            c.socketFactory as SSLSocketFactory
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 用 Conscrypt 的 Provider 单独构造一个 SSLContext。
     *
     * ⚠️ **不要把它 install 成全局 Provider**：那会替换掉整个进程的 JSSE
     * （包括系统安装器、WebView 之类的既有实现），在老 ROM 上风险太大。
     * 这里只让本 App 自己 new 出来的 SSLContext 用它，作用域干净。
     *
     * 全部走反射的原因：万一某个 ABI 上原生库缺失，
     * `UnsatisfiedLinkError` 会在类初始化/调用时抛出，反射能完整兜住，
     * 而不是在类校验阶段就炸掉整个 TlsStack。
     */
    private fun conscrypt(tm: TrustManager): SSLSocketFactory? {
        return try {
            val cls = Class.forName("org.conscrypt.Conscrypt")
            val provider = cls.getMethod("newProvider").invoke(null) as java.security.Provider
            val c = SSLContext.getInstance("TLS", provider)
            c.init(null, arrayOf(tm), SecureRandom())
            c.socketFactory as SSLSocketFactory
        } catch (e: Throwable) {
            Log.w(TAG, "Conscrypt 构造失败: ${e.javaClass.simpleName}: ${e.message}")
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

        // ⓪ Conscrypt 自己建出来的 socket —— 它有专门的入口，
        //    走它才能把名字同时用于 SNI 和**证书主机名校验**
        //    （换栈之后 leaf 证书的 CN/SAN 校验也归 Conscrypt 管，不能漏）。
        try {
            val cls = Class.forName("org.conscrypt.Conscrypt")
            if (cls.getMethod("isConscrypt", javax.net.ssl.SSLSocket::class.java)
                    .invoke(null, socket) == true
            ) {
                cls.getMethod("setHostname", javax.net.ssl.SSLSocket::class.java, String::class.java)
                    .invoke(null, socket, host)
                return
            }
        } catch (ignored: Throwable) {
        }

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

