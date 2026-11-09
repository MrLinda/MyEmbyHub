package com.example.tvcheck

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 电视端的极简 HTTP 配置服务。
 *
 * 电视没有软键盘，输网址很痛苦；又连不上 adb，没法远程敲命令。
 * 所以在电视上开一个小 HTTP 服务（默认 5102），电脑浏览器直接打开
 * http://<电视IP>:5102 就能改配置，改完落盘到 /sdcard/tvcheck.conf，
 * App 立即读取生效。
 *
 * 只在用户手动点「启动配置服务」后才监听，10 分钟后自动关闭。
 * 注意：同网段任何人都能改这个配置（里面可能有 Emby API Key），
 * 仅建议在家里/可信网络里临时使用。
 */
class ConfigServer(
    private val port: Int,
    private val provider: () -> Map<String, String>,
    private val onSave: (Map<String, String>) -> Unit
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var pool: java.util.concurrent.ExecutorService? = null

    /** 启动服务，返回本机局域网 IP（失败返回 null）。 */
    fun start(): String? {
        if (!running.compareAndSet(false, true)) return localIp()
        return try {
            val ss = ServerSocket(port)
            serverSocket = ss
            val p = Executors.newFixedThreadPool(4)
            pool = p
            p.execute { acceptLoop(ss) }
            Log.i(TAG, "config server started on $port")
            localIp()
        } catch (e: Throwable) {
            Log.e(TAG, "config server start failed", e)
            running.set(false)
            null
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (ignored: Throwable) {}
        try { pool?.shutdownNow() } catch (ignored: Throwable) {}
        serverSocket = null
        pool = null
    }

    fun isRunning(): Boolean = running.get()

    /** 实际绑定的端口（port 传 0 时由系统分配，测试用）。 */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val client = ss.accept()
                pool?.execute { handle(client) }
            } catch (e: Throwable) {
                if (running.get()) Log.e(TAG, "accept failed", e)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            // 注意：Android 4.3 的 java.net.Socket 尚未实现 java.io.Closeable，
            // 用 Kotlin 的 use{} 会在真机上抛 ClassCastException（桌面 JVM 上测不出来），
            // 所以这里必须手动 try/finally。
            val s = client
            s.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
            val out = s.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val rawPath = parts.getOrNull(1) ?: "/"
            val path = rawPath.substringBefore("?").trimEnd('/').ifEmpty { "/" }

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            when {
                method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", htmlPage())
                method == "GET" && path == "/api/config" ->
                    respond(out, 200, "application/json; charset=utf-8", json(provider()))
                method == "POST" && path == "/save" -> {
                    onSave(parseForm(body))
                    respond(out, 200, "text/html; charset=utf-8", htmlPage("配置已保存 ✓ 回到电视上看结果"))
                }
                else -> respond(out, 404, "text/plain; charset=utf-8", "not found")
            }
        } catch (e: Throwable) {
            // SocketTimeoutException = 客户端 keep-alive 挂着不吭声，属正常现象，不用回错误页
            if (e !is java.net.SocketTimeoutException) {
                Log.e(TAG, "handle failed", e)
                // 尽量回一个错误页，别让浏览器只看到空响应
                try {
                    val out = client.getOutputStream()
                    respond(out, 500, "text/html; charset=utf-8",
                        "<meta charset=utf-8><h3>服务器内部错误</h3><pre>${e.javaClass.simpleName}: ${e.message}</pre>")
                } catch (ignored: Throwable) {
                }
            }
        } finally {
            try { client.close() } catch (ignored: Throwable) {}
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in body.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = urlDecode(pair.substring(0, i))
            val v = urlDecode(pair.substring(i + 1))
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Throwable) {
        s
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(if (code == 200) "OK" else "Not Found").append("\r\n")
        head.append("Content-Type: ").append(type).append("\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun json(map: Map<String, String>): String = map.entries.joinToString(
        separator = ",\n", prefix = "{\n", postfix = "\n}"
    ) { (k, v) -> "  \"$k\": \"${v.replace("\"", "\\\"")}\"" }

    private fun htmlPage(msg: String? = null): String {
        val cur = provider()
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TV Check 配置</title>
<style>
 body{background:#101216;color:#dfe6ee;font-family:-apple-system,Segoe UI,Roboto,sans-serif;padding:24px;margin:0}
 h1{color:#7CFF9B;font-size:22px;margin:0 0 4px}
 .sub{color:#8892a0;font-size:13px;margin-bottom:18px}
 label{display:block;margin:14px 0 4px;font-size:13px;color:#9fb0c3}
 input{width:100%;max-width:640px;padding:9px 10px;border:1px solid #2b3440;border-radius:6px;background:#0a0c10;color:#fff;font-size:14px}
 button{margin-top:20px;padding:10px 26px;border:0;border-radius:6px;background:#00c878;color:#06231a;font-size:15px;font-weight:600;cursor:pointer}
 .ok{margin:14px 0 0;color:#7CFF9B;font-size:14px}
 .hint{margin-top:24px;color:#6d7784;font-size:12px;line-height:1.7}
 code{background:#0a0c10;padding:1px 5px;border-radius:3px;color:#8ab4f8}
</style></head><body>
<h1>TV Check 配置</h1>
<div class="sub">${esc(BuildInfo.VERSION_NAME)} · ${esc(BuildInfo.DEVICE)} · API ${BuildInfo.SDK}</div>
${if (msg != null) "<div class=\"ok\">$msg</div>" else ""}
<form method="post" action="/save">
<label>Emby 服务器地址</label>
<input name="server" value="${esc(cur["server"] ?: "")}" placeholder="https://域名:8096">
<label>Emby 用户名（服务器需要账号登录时填）</label>
<input name="username" value="${esc(cur["username"] ?: "")}" placeholder="用户名">
<label>Emby 密码</label>
<input name="password" type="password" value="${esc(cur["password"] ?: "")}" placeholder="密码">
<label>Emby API Key（可选；填了会优先用它；也可以在电视上用用户名密码换取）</label>
<input name="api_key" value="${esc(cur["api_key"] ?: "")}" placeholder="Emby 后台 → 高级 → API 密钥">
<label>媒体地址（可选，Emby 播放地址或 m3u8）</label>
<input name="media" value="${esc(cur["media"] ?: "")}" placeholder="https://.../stream.m3u8">
<label>APK 地址（在线更新用，可选）</label>
<input name="apk_url" value="${esc(cur["apk_url"] ?: "")}" placeholder="http://电脑IP:8000/tvcheck-debug.apk">
<button type="submit">保存到电视</button>
</form>
<div class="hint">
保存后直接回电视点「测试连通性」/「播上面地址」即可生效，无需重启。<br>
接口：<code>GET /api/config</code> 返回当前配置的 JSON。<br>
提示：这个服务只在本机局域网内可访问，同网段设备都能改配置，别放敏感 Key 到公网环境。
</div>
</body></html>
""".trimIndent()
    }

    companion object {
        private const val TAG = "ConfigServer"

        /** 取本机第一个站点本地 IPv4，用于告诉用户浏览器该开哪个地址。 */
        fun localIp(): String? {
            try {
                val nis = NetworkInterface.getNetworkInterfaces()
                while (nis.hasMoreElements()) {
                    val ni = nis.nextElement()
                    if (ni.isLoopback || !ni.isUp) continue
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a is InetAddress && !a.isLoopbackAddress && a.address.size == 4) {
                            val s = a.hostAddress
                            if (s != null && !s.startsWith("127.")) return s
                        }
                    }
                }
            } catch (e: Throwable) {
                // 忽略：拿不到 IP 时让用户手动填
            }
            return null
        }
    }
}

/** 页面模板用到的静态信息。 */
object BuildInfo {
    val VERSION_NAME: String = BuildConfig.VERSION_NAME
    val DEVICE: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    val SDK: Int = android.os.Build.VERSION.SDK_INT
}
