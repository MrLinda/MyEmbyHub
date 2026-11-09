
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 纯 Java TLS 探针（跑在电视的 dalvikvm 上，不需要安装 App）。
 *
 * 目标：定性 Android 4.3 的 JSSE 为什么被服务器拒绝握手。
 * 手段：
 *   A. 逐个 TLS 版本裸握手
 *   B. 逐个密码套件握手，找出服务器到底接受哪一类
 *   C. 走本地 TCP 代理，让电脑抓下 JSSE 真实发出的 ClientHello 字节
 *   D. 打印本机 JSSE 支持的协议/套件/provider
 *
 * 所有输出打印到 stdout，adb shell 直接能看。
 */
public class Tp {

    private static final String HOST = "203.0.113.10";
    private static final int PORT = 443;

    private static final TrustManager[] TRUST_ALL = new TrustManager[] {
        new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }
    };

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : PORT;

        p("===== D) 本机 JSSE 能力 =====");
        dumpCapabilities();

        p("===== A) 逐个 TLS 版本握手（信任所有证书） =====");
        String[] protos = { "TLSv1", "TLSv1.1", "TLSv1.2" };
        for (String pr : protos) {
            handshake(host, port, new String[] { pr }, null);
        }

        p("===== A2) 多版本同时启用（TLSv1.2,1.1,1.0） =====");
        handshake(host, port, new String[] { "TLSv1.2", "TLSv1.1", "TLSv1" }, null);

        p("===== B) 逐个密码套件 × TLSv1.2 =====");
        List<String> suites = supportedSuites();
        for (String s : suites) {
            handshake(host, port, new String[] { "TLSv1.2" }, new String[] { s });
        }

        p("===== B2) 逐个密码套件 × TLSv1（默认协议） =====");
        for (String s : suites) {
            handshake(host, port, new String[] { "TLSv1" }, new String[] { s });
        }

        p("===== 汇总结束 =====");
    }

    // ------------------------------------------------------------------

    private static void dumpCapabilities() {
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            p("  provider class : " + f.getClass().getName());
            // getSupportedProtocols()/getDefaultProtocols() 在 JDK 里不存在（Android 特有），只能反射
            p("  supported protos: " + reflectArray(f, "getSupportedProtocols"));
            p("  default protos  : " + reflectArray(f, "getDefaultProtocols"));
            p("  supported suites: " + f.getSupportedCipherSuites().length + " 个");
        } catch (Throwable t) {
            p("  读取能力失败: " + t);
        }
        String[] providers = java.security.Security.getProviders("SSL").length > 0
            ? names(java.security.Security.getProviders("SSL"))
            : names(java.security.Security.getProviders());
        p("  security providers: " + providers);
        try {
            p("  SSLContext TLSv1.2 : " + SSLContext.getInstance("TLSv1.2"));
            p("  SSLContext TLS     : " + SSLContext.getInstance("TLS"));
            p("  SSLContext SSLv3   : " + SSLContext.getInstance("SSLv3"));
        } catch (Throwable t) {
            p("  SSLContext 探测异常: " + t);
        }
    }

    private static String[] names(java.security.Provider[] ps) {
        String[] n = new String[ps.length];
        for (int i = 0; i < ps.length; i++) n[i] = ps[i].getName();
        return n;
    }

    /** Android 特有方法在 JDK 里没有，用反射调用；电视上跑才是准的。 */
    private static String reflectArray(Object target, String method) {
        try {
            Object v = target.getClass().getMethod(method).invoke(target);
            if (v instanceof String[]) return Arrays.toString((String[]) v);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "(不可用: " + t.getClass().getSimpleName() + ")";
        }
    }

    private static List<String> supportedSuites() {
        List<String> out = new ArrayList<String>();
        try {
            String[] all = ((SSLSocketFactory) SSLSocketFactory.getDefault()).getSupportedCipherSuites();
            // 全量太慢，只测有代表性的：ECDHE / DHE / RSA / GCM / CBC 各挑几个
            String[] keep = {
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
                "TLS_RSA_WITH_RC4_128_SHA",
                "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
            };
            for (String k : keep) {
                for (String suite : all) if (suite.equals(k)) out.add(suite);
            }
            // 若一个都没匹配上（命名不同），退化为只测前 10 个
            if (out.isEmpty()) {
                for (int i = 0; i < Math.min(10, all.length); i++) out.add(all[i]);
            }
        } catch (Throwable t) {
            p("  取套件列表失败: " + t);
        }
        return out;
    }

    private static void handshake(String host, int port, String[] protos, String[] suites) {
        SSLSocket socket = null;
        String label = "protos=" + Arrays.toString(protos)
            + (suites == null ? "" : " suites=" + Arrays.toString(suites));
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, TRUST_ALL, null);
            socket = (SSLSocket) ctx.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setSoTimeout(8000);
            try {
                socket.setEnabledProtocols(protos);
            } catch (Throwable t) {
                p("  [" + label + "] setEnabledProtocols 失败: " + t);
            }
            if (suites != null) {
                try {
                    socket.setEnabledCipherSuites(suites);
                } catch (Throwable t) {
                    p("  [" + label + "] setEnabledCipherSuites 失败: " + t);
                }
            }
            socket.startHandshake();
            p("  [OK] " + label);
            p("       protocol=" + proto(socket) + " cipher=" + cipher(socket));
            try {
                SSLSession s = socket.getSession();
                p("       session=" + s.getProtocol() + "/" + s.getCipherSuite());
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            p("  [FAIL] " + label);
            p("       " + t.getClass().getName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            while (cause != null) {
                p("       caused by " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String proto(SSLSocket s) {
        try { return String.valueOf(s.getSession().getProtocol()); } catch (Throwable t) { return "?"; }
    }

    private static String cipher(SSLSocket s) {
        try { return String.valueOf(s.getSession().getCipherSuite()); } catch (Throwable t) { return "?"; }
    }

    private static void p(String s) {
        System.out.println(s);
        System.out.flush();
    }
}

