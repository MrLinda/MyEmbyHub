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
    private val cache = HashMap<String, Choice>()

    /** 运行时是否真的成功构造过 Conscrypt factory（给设置页显示状态用）。 */
    @Volatile
    private var conscryptOk: Boolean? = null

    /** 当前进程里 Conscrypt 可不可用（null = 还没试过）。 */
    fun conscryptAvailable(): Boolean? = conscryptOk

    // ==================== 实现选择（持久化） ====================

    private const val PREFS = "myembyhub_tls"
    private const val KEY_IMPL = "impl"
    private const val KEY_PROBE_SYSTEM = "probe_system_first"

    @Volatile
    private var pref: TlsImpl = TlsImpl.AUTO

    /**
     * 新域名（没有记录）先试系统 JSSE，握手失败再自动换 Conscrypt。
     *
     * **默认开**。理由：系统栈能用就该用它 —— Conscrypt 要映射 8MB 原生库、
     * 首次调用还要加载 + JIT 预热，在这台 1.5GB / 96MB 堆的电视上是实打实的开销，
     * 而且它是「平台自己在用的那条路」，出问题概率最低。
     *
     * 代价是每个**没见过的域名**第一次连接可能多付一次失败握手（1~3 秒）。
     * 靠 [TlsHostMemory] 记住结论后，同一个域名只付一次 —— 实际就那几个域名
     * （Emby 服务器 + 取流 / 海报所在主机），付完就没了。
     *
     * ⚠️ 别因为「Conscrypt 更现代」就反过来默认它：现代不等于这台机器上更省。
     * 实测 `eb.atrct.cn` 两条栈都能连（系统栈走 TLSv1.2 + CBC），
     * 这种情况用系统栈完全够，根本不需要把 Conscrypt 拉起来。
     */
    @Volatile
    private var probeSystemFirst: Boolean = true

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
        val p = try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (e: Throwable) {
            null
        }
        val raw = try {
            p?.getString(KEY_IMPL, null)
        } catch (e: Throwable) {
            null
        }
        pref = when (raw) {
            "conscrypt" -> TlsImpl.CONSCRYPT
            "system" -> TlsImpl.SYSTEM
            else -> TlsImpl.AUTO
        }
        // 默认值 true：新装用户直接走「系统栈优先」。
        // 已经手动存过 false 的用户保持 false —— 那是他自己选的，不该被默认值覆盖。
        probeSystemFirst = try {
            p?.getBoolean(KEY_PROBE_SYSTEM, true) ?: true
        } catch (e: Throwable) {
            true
        }
        prefLoaded = true
    }

    fun pref(): TlsImpl = pref

    /** 新域名是否先试系统 JSSE（见 [probeSystemFirst]）。 */
    fun probeSystemFirst(ctx: Context): Boolean {
        if (!prefLoaded) loadPref(ctx)
        return probeSystemFirst
    }

    fun setProbeSystemFirst(ctx: Context, on: Boolean) {
        probeSystemFirst = on
        try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PROBE_SYSTEM, on).commit()
        } catch (ignored: Throwable) {
        }
        // 记录里存的是「按当时策略试出来的结论」，策略变了就该重新试
        TlsHostMemory.clear(ctx)
    }

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
     * 一次选择的结果：真正用上的 factory，以及它属于哪条栈。
     *
     * [impl] 是**实际生效**的实现，不是用户选的那个 —— 选 [TlsImpl.AUTO] 时
     * 它只会是 CONSCRYPT 或 SYSTEM，这样上层要记「哪个域名用哪条栈」时有据可依。
     */
    class Choice(val factory: SSLSocketFactory, val impl: TlsImpl) {
        override fun toString(): String = "Choice($impl)"
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
    ): SSLSocketFactory? = choose(ctx, trust, impl)?.factory

    /** 同 [socketFactory]，但把「实际用了哪条栈」一并交出去。 */
    fun choose(
        ctx: Context,
        trust: TrustMode,
        impl: TlsImpl? = null
    ): Choice? {
        if (!prefLoaded) loadPref(ctx)
        val use = impl ?: pref
        val key = "$use:$trust"
        synchronized(cache) { cache[key]?.let { return it } }
        val built = build(ctx, trust, use) ?: return null
        val (f, actual) = built
        // Conscrypt 自带 1.2/1.3，不需要抬协议；系统栈要抬（4.3 默认只开 TLS 1.0）。
        // ⚠️ 判据必须是**实际**实现：AUTO 解析成 Conscrypt 时若还去 forceTls12，
        //    会把 Conscrypt 的 TLS 1.3 一起关掉。
        val ready = if (actual == TlsImpl.CONSCRYPT) f else forceTls12(f)
        val choice = Choice(ready, actual)
        synchronized(cache) { cache[key] = choice }
        return choice
    }

    /**
     * 按域名取 factory：**有记录按记录，没记录按策略**。
     *
     * 记录来自 [TlsHostMemory] —— 上一个连过这个域名的进程写下的结论。
     * 这样「哪条栈跟它谈得拢」就是**已知**而不是每次重新猜。
     */
    fun chooseFor(ctx: Context, trust: TrustMode, host: String): Choice? {
        if (!prefLoaded) loadPref(ctx)
        val remembered = TlsHostMemory.recall(ctx, host)
        if (remembered != null) {
            val hit = choose(ctx, trust, remembered)
            if (hit != null) return hit
            // 记录过期（比如换了 ROM、Conscrypt 的 .so 没了）→ 丢掉重来
            Log.w(TAG, "$host 的记录 $remembered 已不可用，重新判定")
            TlsHostMemory.forget(ctx, host)
        }
        // 没记录：按策略决定第一次试哪条。
        // probeSystemFirst 打开时先试系统栈（省 Conscrypt 原生库），
        // 谈不拢由调用方 escalate 换栈 —— 代价只落在每个域名的第一次。
        if (pref == TlsImpl.AUTO && probeSystemFirst) {
            choose(ctx, trust, TlsImpl.SYSTEM)?.let { return it }
        }
        return choose(ctx, trust, pref)
    }

    /** 换到「另一条」栈。当前是 Conscrypt 就给系统 JSSE，反之亦然。 */
    fun escalate(ctx: Context, trust: TrustMode, failed: TlsImpl): Choice? {
        val other = if (failed == TlsImpl.CONSCRYPT) TlsImpl.SYSTEM else TlsImpl.CONSCRYPT
        Log.i(TAG, "$failed 握手失败，换 $other 重试")
        return choose(ctx, trust, other)
    }

    /** 记下「这个域名用这条栈能通」。失败路径不记，避免把偶发问题写死。 */
    fun noteSuccess(ctx: Context, host: String, impl: TlsImpl) {
        TlsHostMemory.remember(ctx, host, impl)
    }

    /**
     * 这个异常是不是「TLS 谈不拢」。
     *
     * 换栈重试只对**握手层**的失败有意义：套件谈不拢、协议版本被拒、SNI 缺失。
     * 证书校验失败、DNS 错、超时都不该换栈 —— 换了也是白试，还会掩盖真因。
     *
     * 4.3 上的实际文案很杂，逐个认：
     * - `SSL handshake aborted` / `SSL handshake timeout`（Harmony 血统）
     * - `javax.net.ssl.SSLHandshakeException` / `SSLException`（Conscrypt）
     * - `Connection closed by peer`（对端 alert 40 之后直接断）
     * - `Received fatal alert: handshake_failure`（BoringSSL）
     */
    fun isHandshakeFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            val name = cur.javaClass.name
            val msg = cur.message ?: ""
            if (name.contains("SSLHandshakeException")) return true
            if (name.contains("CertificateException")) return false
            val low = msg.lowercase()
            if (low.contains("handshake") && !low.contains("certificate")) return true
            if (low.contains("wrong version number")) return true
            if (low.contains("connection closed by peer")) return true
            if (low.contains("ssl3_get_record")) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun build(
        ctx: Context,
        trust: TrustMode,
        impl: TlsImpl
    ): Pair<SSLSocketFactory, TlsImpl>? {
        // ⚠️ 信任管理器要单独兜住：`BundledCa.trustManager` 解析内置 PEM 失败，
        //    和「Conscrypt 能不能用」是**两件事**。以前两者的异常都落到
        //    `conscryptOk = false` 上，于是证书库的问题会被误报成「Conscrypt 不可用」，
        //    把排查方向直接带偏。
        val tm: TrustManager = try {
            when (trust) {
                TrustMode.ALL -> trustAll()
                TrustMode.BUNDLED -> BundledCa.trustManager(ctx)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "信任管理器构造失败（$trust）: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (impl != TlsImpl.SYSTEM) {
            conscrypt(tm)?.let {
                // 成功也记一行：这台电视没法截图，TLS 到底走的哪条路
                // 只能靠 logcat 认（`adb logcat -s TlsStack`）。
                if (conscryptOk != true) Log.i(TAG, "TLS 使用 Conscrypt（GCM/ChaCha20 可用）")
                conscryptOk = true
                return it to TlsImpl.CONSCRYPT
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
            (c.socketFactory as SSLSocketFactory) to TlsImpl.SYSTEM
        } catch (e: Throwable) {
            Log.w(TAG, "系统 JSSE 构造失败: ${e.javaClass.simpleName}: ${e.message}")
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

