package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.util.Log
import java.io.IOException
import javax.net.ssl.SSLSocketFactory

/**
 * 带「换栈重试」的一次 HTTPS 请求。
 *
 * ## 解决的问题
 *
 * 一台 4.3 的电视上只有两条 TLS 栈可选，而**哪条能跟某个服务器谈得拢，
 * 事先是不知道的**：
 *
 * - 系统 JSSE：2013 年的 Harmony/OpenSSL，只有 CBC，没有 GCM/ChaCha20；
 * - Conscrypt：BoringSSL，GCM/ChaCha20/TLS1.3 全有，但要加载 8MB 原生库。
 *
 * 现代服务器大多只开 GCM，所以系统栈经常谈不拢；但也不是所有服务器都这样，
 * 有些老站/内网站系统栈反而更省事。于是「先试哪条」本身就是个赌。
 *
 * 这里把赌注的成本压到**每个域名只有一次**：
 *
 * ```
 * 第一次连 A 域名 → 按策略试一条 → 谈不拢 → 换另一条重试 → 成功 → 记下结论
 * 以后连 A 域名   → 直接照记录走，不再付失败握手
 * ```
 *
 * 记录落在 [TlsHostMemory]（SharedPreferences），冷启动也还在 ——
 * 这台电视的进程被系统杀掉是常态，只放内存等于没记。
 *
 * ## 为什么只在握手失败时换栈
 *
 * [TlsStack.isHandshakeFailure] 只认「谈不拢」这一类（`SSL handshake aborted`、
 * `handshake_failure`、`wrong version number` 等）。证书校验失败、DNS 错、
 * 超时都不换 —— 那些换栈也救不回来，硬换只会把真正的错因埋掉，
 * 顺手还会往记录里写一条错的结论。
 */
internal object TlsConn {

    private const val TAG = "TlsConn"

    /**
     * 跑一次需要 TLS 的操作，握手谈不拢时自动换另一条栈重试一次。
     *
     * @param host 只用于查/写记录，必须是不带端口的纯主机名
     * @param block 每次尝试都会被调用一次 —— **里面必须自己新建连接**，
     *              不能复用上一次那个（它的 socket 已经废了）
     */
    fun <T> withRetry(
        ctx: Context,
        trust: TrustMode,
        host: String,
        block: (SSLSocketFactory) -> T
    ): T {
        val first = TlsStack.chooseFor(ctx, trust, host)
            ?: throw IOException("无法构造 TLS socket factory")
        try {
            val result = block(first.factory)
            // 走到这里说明握手 + 请求都过了，这条栈对这个域名是有效的
            TlsStack.noteSuccess(ctx, host, first.impl)
            return result
        } catch (e: Throwable) {
            if (!TlsStack.isHandshakeFailure(e)) {
                // 不是握手失败 → 说明这条栈**把 TLS 谈成了**，只是后面 HTTP/业务层出问题
                // （401 没有 WWW-Authenticate 头、JSON 解析失败等）。
                // 这种时候也得记下来，否则「能连但接口报错」的域名永远学不会该用哪条栈。
                // 例外：DNS / 连不上 / 超时这三种压根没走到 TLS，记了是错的。
                if (!isNetworkLayerFailure(e)) TlsStack.noteSuccess(ctx, host, first.impl)
                throw e
            }
            Log.w(TAG, "$host 用 ${first.impl} 握手失败，换栈重试: ${e.javaClass.simpleName}: ${e.message}")
            val second = try {
                TlsStack.escalate(ctx, trust, first.impl)
            } catch (inner: Throwable) {
                null
            } ?: throw e
            return try {
                val result = block(second.factory)
                TlsStack.noteSuccess(ctx, host, second.impl)
                result
            } catch (inner: Throwable) {
                // 换栈后握手过了、但业务层报错 → 结论照样有效，得记住
                if (!TlsStack.isHandshakeFailure(inner) && !isNetworkLayerFailure(inner)) {
                    TlsStack.noteSuccess(ctx, host, second.impl)
                }
                throw inner
            }
        }
    }

    /** DNS 解析不了 / 连不上 / 超时 —— 这几类跟「哪条 TLS 栈」无关。 */
    private fun isNetworkLayerFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            when (cur) {
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketTimeoutException,
                is java.net.NoRouteToHostException -> return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }
}
