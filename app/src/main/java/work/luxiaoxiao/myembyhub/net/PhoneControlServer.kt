package work.luxiaoxiao.myembyhub.net

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 电视端内置的小 HTTP 服务，让**手机**替电视干「需要打字」的事。
 *
 * ## 为什么需要
 *
 * 这台电视没有软键盘，用遥控器输入账号密码或搜索关键词都很痛苦。
 * 于是：电视显示二维码 → 手机扫码打开网页 → 在手机上输入 → 提交给电视。
 *
 * 两种模式：
 * - [MODE_LOGIN]：手机输入账号密码 → 电视换取 AccessToken
 * - [MODE_SEARCH]：手机输入搜索词 → 电视执行搜索并显示结果
 *
 * ## 安全边界
 *
 * - 只在用户主动点击时开启，登录成功或超时（10 分钟）自动关闭。
 * - 只提供「登录」和「搜索」两个动作，不暴露媒体库数据。
 * - 局域网内任何人都能访问这个端口 → 属于「可信家庭网络」假设。
 */
class PhoneControlServer(
    private val ctx: Context,
    private val mode: String,
    private val onLogin: (server: String, token: String, username: String, userId: String) -> Unit,
    private val onSearch: (String) -> Unit,
    private val onFail: (String) -> Unit
) {

    companion object {
        private const val TAG = "PhoneCtrl"
        const val PORT = 5103
        const val MODE_LOGIN = "login"
        const val MODE_SEARCH = "search"
        /** 10 分钟后自动关闭，避免端口长期暴露。 */
        private const val MAX_LIFE_MS = 10 * 60 * 1000L

        /**
         * 手机端网页的配色。
         *
         * 为什么不用 Android 的 `@color` 资源：这个页面是在**手机浏览器**里渲染的，
         * 拿不到 Android 资源。但角色名和电视端一一对应（bg/fg/muted/action…），
         * 想统一改一套配色时，改这里 + `res/values/colors.xml` 两处即可。
         *
         * 规则：**本文件里除这个对象外不再出现 `#` 色值。**
         */
        private object WebPalette {
            const val BG = "#0E1116"
            const val FG = "#F2F5FA"
            const val MUTED = "#98A2B3"
            const val FIELD = "#181C24"
            const val BORDER = "#262C38"
            const val ACTION = "#3D7EFF"
            const val ON_ACTION = "#FFFFFF"
            const val ACTION_DISABLED = "#2A3A5C"
            const val ON_ACTION_DISABLED = "#7A8AA8"
            const val OK_BG = "#12351F"
            const val OK_FG = "#7EE2A8"
            const val OK_BORDER = "#1F6B3A"
            const val ERR_BG = "#3A1A1A"
            const val ERR_FG = "#FF9C9C"
            const val ERR_BORDER = "#7A2B2B"
            const val HINT = "#5D6A80"
        }

        /** 手机端页面（内嵌 HTML，无外部依赖）。 */
        private val PAGE_TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>MyEmbyHub</title>
<style>
  :root { color-scheme: dark;
          --bg:{{bg}}; --fg:{{fg}}; --muted:{{muted}};
          --field:{{field}}; --border:{{border}};
          --action:{{action}}; --on-action:{{on_action}};
          --action-disabled:{{action_disabled}}; --on-action-disabled:{{on_action_disabled}};
          --ok-bg:{{ok_bg}}; --ok-fg:{{ok_fg}}; --ok-border:{{ok_border}};
          --err-bg:{{err_bg}}; --err-fg:{{err_fg}}; --err-border:{{err_border}};
          --hint:{{hint}}; }
  * { box-sizing: border-box; }
  body { margin:0; padding:24px; background:var(--bg); color:var(--fg);
         font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif; }
  h1 { font-size:22px; margin:8px 0 4px; }
  .sub { color:var(--muted); font-size:13px; margin-bottom:24px; }
  label { display:block; font-size:13px; color:var(--muted); margin:14px 0 6px; }
  input { width:100%; height:48px; padding:0 14px; font-size:16px;
          background:var(--field); border:1px solid var(--border); border-radius:10px; color:var(--fg); }
  input:focus { outline:none; border-color:var(--action); }
  button { width:100%; height:50px; margin-top:24px; font-size:17px; font-weight:600;
           background:var(--action); color:var(--on-action); border:0; border-radius:10px; }
  button:disabled { background:var(--action-disabled); color:var(--on-action-disabled); }
  #msg { margin-top:16px; padding:12px 14px; border-radius:10px; display:none; font-size:14px; }
  .ok { display:block; background:var(--ok-bg); color:var(--ok-fg); border:1px solid var(--ok-border); }
  .err { display:block; background:var(--err-bg); color:var(--err-fg); border:1px solid var(--err-border); }
  .hint { margin-top:22px; font-size:12px; color:var(--hint); line-height:1.7; }
</style>
</head>
<body>
  <h1 id="h1">MyEmbyHub 电视登录</h1>
  <div class="sub" id="sub">在手机上输入账号密码，登录后电视会自动进入媒体库</div>

  <div id="loginBox">
    <label>服务器地址（留空则用电视上的配置）</label>
    <input id="server" type="text" placeholder="https://tv.example.com" autocomplete="off">
    <label>用户名</label>
    <input id="username" type="text" autocomplete="username">
    <label>密码</label>
    <input id="password" type="password" autocomplete="current-password">
    <button id="go" onclick="doLogin()">登录到电视</button>
  </div>

  <div id="searchBox" style="display:none">
    <label>搜索关键词</label>
    <input id="term" type="text" placeholder="片名 / 演员 / 关键词">
    <button id="gosecond" onclick="doSearch()">在电视上搜索</button>
  </div>

  <div id="msg"></div>

  <div class="hint">
    这个页面由电视本机提供，数据只在你的局域网内传输。<br>
    操作完成后请回到电视，屏幕会自动刷新。
  </div>

<script>
var MODE = new URLSearchParams(location.search).get('mode') || 'login';
if (MODE === 'search') {
  document.getElementById('h1').textContent = 'MyEmbyHub 电视搜索';
  document.getElementById('sub').textContent = '在手机上输入关键词，电视会立刻显示搜索结果';
  document.getElementById('loginBox').style.display = 'none';
  document.getElementById('searchBox').style.display = 'block';
}
function show(cls, text) {
  var m = document.getElementById('msg');
  m.className = cls; m.textContent = text; m.style.display = 'block';
}
function doLogin() {
  var btn = document.getElementById('go');
  btn.disabled = true; show('', '');
  var body = JSON.stringify({
    server: document.getElementById('server').value.trim(),
    username: document.getElementById('username').value.trim(),
    password: document.getElementById('password').value
  });
  fetch('/api/login', { method:'POST', headers:{'Content-Type':'application/json'}, body: body })
    .then(function(r){ return r.json(); })
    .then(function(j){
      if (j.ok) {
        show('ok', j.message);
        document.getElementById('password').value = '';
      } else {
        show('err', j.message || '登录失败');
        btn.disabled = false;
      }
    })
    .catch(function(e){ show('err', '网络错误：' + e); btn.disabled = false; });
}
function doSearch() {
  var btn = document.getElementById('gosecond');
  var term = document.getElementById('term').value.trim();
  if (!term) { show('err', '请输入搜索关键词'); return; }
  btn.disabled = true; show('', '');
  fetch('/api/search', { method:'POST', headers:{'Content-Type':'application/json'},
                         body: JSON.stringify({ term: term }) })
    .then(function(r){ return r.json(); })
    .then(function(j){
      if (j.ok) { show('ok', '已发送到电视：' + term); }
      else { show('err', j.message || '发送失败'); btn.disabled = false; }
    })
    .catch(function(e){ show('err', '网络错误：' + e); btn.disabled = false; });
}
</script>
</body>
</html>
""".trimIndent()

        /** 占位符填色后的页面；只拼一次。 */
        private val PAGE_HTML: String by lazy {
            PAGE_TEMPLATE
                .replace("{{bg}}", WebPalette.BG)
                .replace("{{fg}}", WebPalette.FG)
                .replace("{{muted}}", WebPalette.MUTED)
                .replace("{{field}}", WebPalette.FIELD)
                .replace("{{border}}", WebPalette.BORDER)
                .replace("{{action_disabled}}", WebPalette.ACTION_DISABLED)
                .replace("{{on_action_disabled}}", WebPalette.ON_ACTION_DISABLED)
                .replace("{{action}}", WebPalette.ACTION)
                .replace("{{on_action}}", WebPalette.ON_ACTION)
                .replace("{{ok_bg}}", WebPalette.OK_BG)
                .replace("{{ok_fg}}", WebPalette.OK_FG)
                .replace("{{ok_border}}", WebPalette.OK_BORDER)
                .replace("{{err_bg}}", WebPalette.ERR_BG)
                .replace("{{err_fg}}", WebPalette.ERR_FG)
                .replace("{{err_border}}", WebPalette.ERR_BORDER)
                .replace("{{hint}}", WebPalette.HINT)
        }
    }

    private var socket: ServerSocket? = null
    private val worker = Executors.newCachedThreadPool()
    private val startedAt = System.currentTimeMillis()
    private val busy = AtomicReference(false)

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val srv: ServerSocket
        try {
            srv = ServerSocket(PORT)
        } catch (e: Throwable) {
            Log.e(TAG, "启动服务失败（端口 $PORT 可能被占用）", e)
            onFail("无法启动服务：${e.message ?: e.javaClass.simpleName}")
            return
        }
        socket = srv
        running = true
        Log.i(TAG, "服务已启动，端口 $PORT，模式 $mode")
        worker.execute {
            while (running) {
                try {
                    val client = srv.accept()
                    worker.execute { handle(client) }
                } catch (e: Throwable) {
                    if (running) Log.w(TAG, "accept 失败", e)
                }
            }
        }
        worker.execute {
            try {
                while (running && System.currentTimeMillis() - startedAt < MAX_LIFE_MS) {
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                // ⚠️ **必须接住**：`stop()` 里的 `worker.shutdownNow()` 会打断这个 sleep，
                //    而 InterruptedException 从 Runnable 里逃出去会被
                //    ThreadPoolExecutor 包成 java.lang.Error 抛到线程外 →
                //    **整个进程崩掉**（真机表现：扫码弹窗一按「关闭」App 就闪退）。
                //    这里安静退出即可。
                Log.i(TAG, "服务已关闭（定时线程被中断）")
                Thread.currentThread().interrupt()
                return@execute
            }
            stop()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { socket?.close() } catch (ignored: Throwable) {}
        socket = null
        worker.shutdownNow()
        Log.i(TAG, "服务已关闭")
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            // 请求行形如： POST /api/login HTTP/1.1
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val out = client.getOutputStream()
            when {
                method == "GET" && (path == "/" || path.startsWith("/?")) -> {
                    respond(out, 200, "text/html; charset=utf-8", PAGE_HTML)
                }
                method == "GET" && path == "/api/state" -> {
                    val state = if (busy.get()) "working" else "waiting"
                    respond(out, 200, "application/json; charset=utf-8", "{\"state\":\"$state\",\"mode\":\"$mode\"}")
                }
                method == "POST" && path == "/api/login" -> {
                    handleLogin(body, out)
                }
                method == "POST" && path == "/api/search" -> {
                    handleSearch(body, out)
                }
                else -> {
                    respond(out, 404, "text/plain; charset=utf-8", "not found")
                }
            }
            out.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "处理请求失败", e)
        } finally {
            try { client.close() } catch (ignored: Throwable) {}
        }
    }

    private fun handleLogin(body: String, out: OutputStream) {
        busy.set(true)
        try {
            val json = org.json.JSONObject(body)
            val user = json.optString("username").trim()
            val pass = json.optString("password")
            if (user.isEmpty()) {
                respond(out, 400, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"请填写用户名\"}")
                return
            }

            // 服务器地址：手机可以覆盖，留空则用电视端已配置的
            val server = json.optString("server").trim().ifEmpty { Session.get(ctx).server }
            val api = EmbyApi(ctx, server)
            val result = api.login(user, pass)
            result.onSuccess { token ->
                onLogin(api.server, token, user, api.userId)
                respond(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"message\":\"登录成功，电视端已接收\"}")
            }.onFailure { e ->
                val msg = (e.message ?: e.javaClass.simpleName).replace("\"", "'")
                onFail(msg)
                respond(out, 401, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"$msg\"}")
            }
        } catch (e: Throwable) {
            val msg = "解析请求失败：${e.message ?: e.javaClass.simpleName}"
            respond(out, 400, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"$msg\"}")
        } finally {
            busy.set(false)
        }
    }

    private fun handleSearch(body: String, out: OutputStream) {
        try {
            val term = org.json.JSONObject(body).optString("term").trim()
            if (term.isEmpty()) {
                respond(out, 400, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"请输入关键词\"}")
                return
            }
            onSearch(term)
            respond(out, 200, "application/json; charset=utf-8", "{\"ok\":true,\"message\":\"已发送到电视\"}")
        } catch (e: Throwable) {
            val msg = "解析请求失败：${e.message ?: e.javaClass.simpleName}"
            respond(out, 400, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"$msg\"}")
        }
    }

    private fun respond(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reasonOf(code)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(bytes.size).append("\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
    }

    private fun reasonOf(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "OK"
    }
}
