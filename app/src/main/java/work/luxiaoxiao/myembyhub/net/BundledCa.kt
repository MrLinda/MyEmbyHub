package work.luxiaoxiao.myembyhub.net

import java.io.InputStream
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 内置锚点证书 + 手写链校验。
 *
 * 背景：目标电视是 Android 4.3，系统 CA 库停留在 2013 年，
 * 而目标 Emby 服务器（tv.example.com）用的是 Let's Encrypt 2024 新链
 * （leaf → Let's Encrypt YR2 → ISRG Root YR → ISRG Root X1），
 * 系统根本不认识这些根 → 必然 "证书不受信任"。
 *
 * 解法：把锚点证书打进 APK 自己信任。这里不依赖 KeyStore（老系统上
 * KeyStore 类型支持参差不齐），而是手写逐级验签，逻辑简单可控。
 */
object BundledCa {

    private val FILES = arrayOf(
        "isrg_root_x1.pem",
        "isrg_root_yr.pem",
        "letsencrypt_yr2.pem"
    )

    /** 读取 assets 里的 PEM 证书。 */
    fun load(ctx: android.content.Context): List<X509Certificate> {
        val out = ArrayList<X509Certificate>()
        val cf = CertificateFactory.getInstance("X.509")
        for (name in FILES) {
            try {
                ctx.assets.open(name).use { input: InputStream ->
                    out.add(cf.generateCertificate(input) as X509Certificate)
                }
            } catch (e: Throwable) {
                // 缺一张就少一张，剩下照样能用
            }
        }
        return out
    }

    /**
     * 逐级验签：从 leaf 往上找签发者，用签发者公钥验证签名，
     * 直到命中锚点（anchors）或链断掉。
     */
    fun verifyChain(chain: Array<out Certificate>, anchors: List<X509Certificate>): Boolean {
        if (chain.isEmpty()) return false
        var current = chain[0] as X509Certificate
        val pool = ArrayList<X509Certificate>()
        for (c in chain.drop(1)) {
            try { pool.add(c as X509Certificate) } catch (ignored: Throwable) {}
        }
        var guard = 0
        while (guard++ < 10) {
            val issuer = anchors.firstOrNull { it.subjectX500Principal == current.issuerX500Principal }
                ?: pool.firstOrNull { it.subjectX500Principal == current.issuerX500Principal }
                ?: return false
            try {
                current.verify(issuer.publicKey)
            } catch (e: Throwable) {
                return false
            }
            if (anchors.any { it == issuer }) return true
            if (!pool.remove(issuer)) return false
            current = issuer
        }
        return false
    }

    /** 系统信任 + 内置锚点：系统能验就系统验，验不过再走内置锚点。 */
    fun trustManager(ctx: android.content.Context): X509TrustManager {
        val anchors = load(ctx)
        val system = try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Throwable) {
            null
        }
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) throw CertificateException("空证书链")
                if (system != null) {
                    try {
                        system.checkServerTrusted(chain, authType)
                        return
                    } catch (ignored: Throwable) {
                    }
                }
                if (!verifyChain(chain, anchors)) {
                    throw CertificateException("内置锚点(${anchors.size} 张)也无法验证该证书链")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = anchors.toTypedArray()
        }
    }
}
