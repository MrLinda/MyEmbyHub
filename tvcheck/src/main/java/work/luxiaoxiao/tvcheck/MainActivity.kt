package work.luxiaoxiao.tvcheck

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.multidex.MultiDex
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.video.VideoSize
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 电视端「可行性验证」工具。
 *
 * 目标：在目标电视（乐视 S50 Air / Android 4.3 / API 18）上，回答三个决定技术路线的问题：
 *   1. 能不能连上 Emby 服务器（HTTP / HTTPS / TLS 1.0 / 1.1 / 1.2 分别协商结果如何）；
 *   2. ExoPlayer 2.x 能不能在这台机器上解码（硬解还是软解）；
 *   3. 播放时掉帧、分辨率、错误码如何。
 *
 * 约束：这台机器没有 WebView、没有 GMS、可用堆只有 ~96MB，
 * 所以本工具只用原生 View + HttpURLConnection + ExoPlayer 2.x，不引入 Compose / media3。
 */
class MainActivity : Activity() {

    private lateinit var etServer: EditText
    private lateinit var etMedia: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etApkUrl: EditText
    private lateinit var tvNet: TextView
    private lateinit var tvPlay: TextView
    private lateinit var tvCfg: TextView
    private lateinit var tvUpdate: TextView
    private lateinit var playerView: PlayerView

    private var player: ExoPlayer? = null
    private var cfgServer: ConfigServer? = null
    private var cfgStopAt = 0L

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** 播放日志与状态分开拼，保证进度刷新不会冲掉历史记录。 */
    private val log = StringBuilder()
    private var progress = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // minSdk 18 没有原生 multidex 支持
        MultiDex.install(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.etServer)
        etMedia = findViewById(R.id.etMedia)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etApkUrl = findViewById(R.id.etApkUrl)
        tvNet = findViewById(R.id.tvNet)
        tvPlay = findViewById(R.id.tvPlay)
        tvCfg = findViewById(R.id.tvCfg)
        tvUpdate = findViewById(R.id.tvUpdate)
        playerView = findViewById(R.id.playerView)

        findViewById<Button>(R.id.btnNet).setOnClickListener { runNetTest() }
        findViewById<Button>(R.id.btnLogin).setOnClickListener { runLogin() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { tvNet.text = "等待测试…" }
        findViewById<Button>(R.id.btnDiag).setOnClickListener {
            runTlsMatrix(defaultServerHost().substringBefore(":"), defaultServerPort())
        }
        findViewById<Button>(R.id.btnCfg).setOnClickListener { startConfigServer() }
        findViewById<Button>(R.id.btnCfgStop).setOnClickListener { stopConfigServer() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            downloadAndInstall(etApkUrl.text.toString().trim())
        }
        findViewById<Button>(R.id.btnPlayUrl).setOnClickListener {
            play(etMedia.text.toString().trim(), "URL")
        }
        findViewById<Button>(R.id.btnPlayH264).setOnClickListener {
            play("asset:///test_h264.mp4", "本地 H.264 样片")
        }
        findViewById<Button>(R.id.btnPlayH265).setOnClickListener {
            play("asset:///test_h265.mp4", "本地 HEVC 样片")
        }

        // 自动诊断：可以用 adb 直接触发，不需要在电视上按按钮
        //   adb shell am start -n work.luxiaoxiao.tvcheck/.MainActivity \
        //     --es cmd diag --es host tv.example.com --ei port 443
        // 结果写到 /sdcard/tvcheck-tls.txt，用 adb pull 拉回电脑。
        handleCommand(intent)

        log.append("设备: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        log.append("版本: ${BuildConfig.VERSION_NAME}\n")
        log.append("注意: 本机无 WebView / 无 GMS / 仅 TLS1.0 需显式开启\n\n")

        // 服务器地址三级优先：SD 卡配置文件 > 打包时烤进来的默认值 > 空
        val conf = readConf()
        val serverDefault = conf["server"].orEmpty().ifEmpty { BuildConfig.DEFAULT_SERVER_URL }
        if (serverDefault.isNotEmpty()) etServer.setText(serverDefault)
        conf["media"]?.let { if (etMedia.text.isNullOrEmpty()) etMedia.setText(it) }
        conf["username"]?.let { etUser.setText(it) }
        conf["password"]?.let { etPass.setText(it) }
        conf["apk_url"]?.let { etApkUrl.setText(it) }
        if (conf.containsKey("api_key")) {
            log.append("已从 /sdcard/tvcheck.conf 读取 api_key（长度 ${conf["api_key"]!!.length}）\n")
        }
        if (serverDefault.isEmpty()) {
            log.append("⚠ 未配置服务器地址：请在电视上手动输入，或用 -PtvServerUrl 重新打包\n\n")
        }
        renderPlay()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        cfgServer?.stop()
        cfgServer = null
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * 处理来自 intent 的自动化命令。
     *
     * ⚠️ 必须在 `onCreate` **和** `onNewIntent` 里都调用：
     * App 已经在前台时，`am start` 只会走 `onNewIntent`（不重建 Activity），
     * 只写在 onCreate 里会导致命令完全失效——这是真机踩过的坑。
     */
    private fun handleCommand(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd")
        Log.i("TvCheck", "handleCommand cmd=$cmd (activity=${hashCode()})")
        when (cmd) {
            "diag" -> {
                val h = intent?.getStringExtra("host") ?: defaultServerHost()
                val p = intent?.getIntExtra("port", 443) ?: 443
                main.postDelayed({ runTlsMatrix(h, p) }, 500)
            }
            "login" -> {
                // 自动登录：用 /sdcard/tvcheck.conf 里的凭据，结果写 /sdcard/tvcheck-login.txt
                main.postDelayed({ runAutoLogin() }, 500)
            }
            "stream" -> {
                // 播放链路探针：裸 SSLSocket 手写 HTTPS 取流，结果写 /sdcard/tvcheck-stream.txt
                main.postDelayed({ runStreamProbe() }, 500)
            }
            "decode" -> {
                // 解码器探测：自动播 H264/HEVC 样片，结果写 /sdcard/tvcheck-decode.txt
                main.postDelayed({ runDecodeProbe() }, 500)
            }
            "netplay" -> {
                // 网络播放探针：自写 DataSource 取流 + ExoPlayer 实播网络视频
                // 结果写 /sdcard/tvcheck-netplay.txt
                main.postDelayed({ runNetPlayProbe() }, 500)
            }
            "httpprobe" -> {
                // HTTP 请求变体矩阵：一次把服务器接受的写法全部测清
                // 结果写 /sdcard/tvcheck-http.txt
                main.postDelayed({ runHttpProbe() }, 500)
            }
            else -> Log.i("TvCheck", "无命令，忽略")
        }
    }

    /** App 已在前台时，`am start` 走这里而不是 onCreate。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCommand(intent)
    }

    // ==================== 播放链路探针（adb 可触发）====================

    /** 用裸 SSLSocket 手写 HTTPS 取流，验证播放链路能否走通。 */
    private fun runStreamProbe() {
        tvNet.text = "播放链路探针中…"
        worker.execute {
            val media = readConf()["media"].orEmpty()
            StreamProbe.run(this, media) { Log.i("TvCheck", it) }
            val text = try {
                java.io.File(StreamProbe.RESULT_FILE).readText()
            } catch (e: Throwable) {
                "(读取结果文件失败: ${e.message})"
            }
            postNet(StringBuilder(text))
        }
    }

    // ==================== 网络播放探针（adb 可触发）====================

    /**
     * HTTP 请求变体矩阵：把「服务器到底接受什么写法」一次测清，
     * 免得每次都要改代码 + 让用户重装 APK 才能验证一轮。
     */
    private fun runHttpProbe() {
        tvNet.text = "HTTP 变体矩阵探针中…"
        val conf = readConf()
        val server = conf["server"].orEmpty().trimEnd('/')
        val token = conf["api_key"].orEmpty()
        worker.execute {
            HttpProbe.run(
                this,
                server,
                token,
                onLine = { Log.i("TvCheck", it) },
                onFinished = { text -> main.post { postNet(StringBuilder(text)) } }
            )
        }
    }

    /**
     * 用自写的 [EmbyHttpDataSource] + ExoPlayer 真播一段网络视频。
     *
     * 这是端到端验证：API 查库 → 取流 URL → 自定义 DataSource（跟随 302）→
     * ExoPlayer 解码上屏。屏幕上看不出解码器名/丢帧，所以结果落盘。
     */
    private fun runNetPlayProbe() {
        tvNet.text = "网络播放探针中…"
        val conf = readConf()
        val server = conf["server"].orEmpty().trimEnd('/')
        val token = conf["api_key"].orEmpty()
        worker.execute {
            NetPlayProbe.run(
                this,
                server,
                token,
                onLine = { Log.i("TvCheck", it) },
                onPlayerReady = { p ->
                    // 这里已经在主线程（探针内部切过线程）
                    playerView.player = p
                },
                onFinished = { text ->
                    main.post { postNet(StringBuilder(text)) }
                }
            )
        }
    }

    // ==================== 解码器探测（adb 可触发）====================
    /**
     * 自动播 H264 / HEVC 样片，把解码器名写文件。
     *
     * 想知道这台电视是硬解（OMX.MSTAR.*）还是软解（OMX.google.*），
     * 但界面上看不到、也截不了图 → 只能让 App 自己播、自己记。
     */
    private fun runDecodeProbe() {
        val results = StringBuilder()
        results.append("===== 解码器探测 =====\n")
        results.append("设备: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        results.append("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n")

        val pending = ArrayDeque<String>()
        val tags = LinkedHashMap<String, String>()

        fun next() {
            val asset = pending.removeFirstOrNull() ?: run {
                try {
                    java.io.File("/sdcard/tvcheck-decode.txt").writeText(results.toString())
                    Log.i("TvCheck", "解码器探测结果已写入 /sdcard/tvcheck-decode.txt")
                } catch (e: Throwable) {
                    Log.e("TvCheck", "写解码器结果失败", e)
                }
                postNet(StringBuilder(results))
                return
            }
            val label = if (asset.contains("h264")) "H.264" else "HEVC"
            results.append("── $label ($asset)\n")
            playForDecode(asset, label, tags) { detail ->
                results.append(detail).append('\n')
                main.postDelayed({ next() }, 600)
            }
        }

        pending.addLast("asset:///test_h264.mp4")
        pending.addLast("asset:///test_h265.mp4")
        next()
    }

    /**
     * 播一段样片，等解码器就绪后回调。
     *
     * ExoPlayer 的解码器初始化是异步的，只能等 `onVideoDecoderInitialized` 回调。
     */
    private fun playForDecode(
        asset: String,
        label: String,
        tags: MutableMap<String, String>,
        done: (String) -> Unit
    ) {
        var finished = false
        var finishedP: ExoPlayer? = null

        val p = ExoPlayer.Builder(this).build()
        playerView.player = p
        finishedP = p
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializationTimeMs: Long
            ) {
                if (finished) return
                finished = true
                val kind = when {
                    decoderName.contains("MSTAR", true) -> "MStar 硬解"
                    decoderName.contains("google", true) -> "软解（CPU 解码，会吃力）"
                    decoderName.contains("OMX.", true) -> "厂商 OMX 解码器"
                    else -> "未知类型"
                }
                tags[label] = decoderName
                done("  解码器: $decoderName  [$kind]")
                try { finishedP?.release() } catch (ignored: Throwable) {}
            }

            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                if (!finished) {
                    tags["${label}.分辨率"] = "${videoSize.width}x${videoSize.height}"
                }
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (finished) return
                // 播完了也算拿到信息
                if (state == ExoPlayer.STATE_ENDED) {
                    finished = true
                    val dec = tags[label] ?: "(未触发解码器初始化回调)"
                    done("  播放结束，解码器: $dec")
                    try { finishedP?.release() } catch (ignored: Throwable) {}
                }
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                if (finished) return
                finished = true
                done("  ✗ 播放错误 [${error.errorCodeName}] ${error.message}")
                try { finishedP?.release() } catch (ignored: Throwable) {}
            }
        })

        try {
            p.setMediaItem(MediaItem.fromUri(asset))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Throwable) {
            finished = true
            done("  ✗ 启动失败: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 兜底：20 秒还没回调就放弃，避免卡死
        main.postDelayed({
            if (!finished) {
                finished = true
                done("  ⏱ 20 秒内没有解码器回调（可能不支持该编码）")
                try { finishedP?.release() } catch (ignored: Throwable) {}
            }
        }, 20000)
    }

    // ==================== 自动登录（adb 可触发）====================

    /**
     * 用 /sdcard/tvcheck.conf 里的凭据自动登录，结果写 /sdcard/tvcheck-login.txt。
     *
     * 为什么要自动版：验证「能不能换到 AccessToken」是整个项目最关键的一步，
     * 但它在电视上要人点按钮、看屏幕、拍照回传。改成文件驱动后，
     * adb 一条命令就能验证，不用人参与。
     */
    private fun runAutoLogin() {
        refreshFromConf()
        val base = etServer.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        if (base.isEmpty() || user.isEmpty()) {
            writeLoginResult("配置缺失：server 或 username 为空\n")
            return
        }
        worker.execute {
            val sb = StringBuilder()
            sb.append("服务器: $base\n")
            sb.append("用户: $user\n")
            sb.append("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n")
            val body = JSONObject().put("Username", user).put("Pw", pass).toString()
            var token: String? = null

            for (path in listOf("/Users/AuthenticateByName", "/emby/Users/AuthenticateByName")) {
                for (force in listOf(true, false)) {
                    val label = if (force) "强制TLS1.2" else "默认TLS"
                    try {
                        val (code, text) = postJsonSafe(base, path, body, force, false)
                        sb.append("[$label] $path → HTTP $code\n")
                        val t = extractToken(text)
                        if (t != null) {
                            token = t
                            sb.append("  ✓ 拿到 AccessToken（长度 ${t.length}）\n")
                            sb.append("  token 前 8 位: ${t.take(8)}…\n")
                        } else {
                            // 不打印完整响应体，可能含敏感信息
                            sb.append("  响应摘要: ${text.take(160).replace(Regex("(?i)token\"?\\s*:\\s*\"[^\"]*\""), "token\":\"***\"")}\n")
                        }
                    } catch (e: Throwable) {
                        val msg = e.message ?: ""
                        val head = msg.substringBefore('\n')
                        // Android 的 HttpURLConnection 遇到 401 但服务端没给 WWW-Authenticate 时会抛这个，
                        // 其实说明请求已经到达服务器、只是凭据不对或路径不对。
                        if (head.contains("No authentication challenges")) {
                            sb.append("[$label] $path → 到达服务器但返回 401（缺 WWW-Authenticate 头导致 Android 抛异常）\n")
                        } else {
                            sb.append("[$label] $path → 失败 ${e.javaClass.simpleName}: $head\n")
                        }
                    }
                    if (token != null) break
                }
                if (token != null) break
            }

            if (token != null) {
                val conf = HashMap(readConf())
                conf["api_key"] = token
                writeConf(conf)
                sb.append("\n✓ Token 已写入 /sdcard/tvcheck.conf 的 api_key\n")
            } else {
                sb.append("\n✗ 未取到 Token\n")
            }
            writeLoginResult(sb.toString())
            postNet(StringBuilder(sb))
        }
    }

    private fun writeLoginResult(text: String) {
        try {
            java.io.File("/sdcard/tvcheck-login.txt").writeText(text)
            Log.i("TvCheck", "登录结果已写入 /sdcard/tvcheck-login.txt")
        } catch (e: Throwable) {
            Log.e("TvCheck", "写登录结果失败", e)
        }
    }

    // ==================== TLS 诊断矩阵（自动，可由 adb intent 触发）====================

    /**
     * 跑完整 TLS 矩阵并把结果写到 /sdcard/tvcheck-tls.txt。
     *
     * 之所以做成「自动 + 写文件」：这台电视的 screencap 输出 0 字节、shell 没有 DUMP 权限，
     * 没法自动取界面截图；靠人拍照回传效率太低。写文件后 adb pull 一下就有完整日志。
     */
    private fun runTlsMatrix(host: String, port: Int) {
        tvNet.text = "TLS 诊断中…"
        worker.execute {
            try {
                TlsMatrix.run(this, host, port) { line ->
                    Log.i("TvCheck", line)
                }
                val text = try {
                    java.io.File(TlsMatrix.RESULT_FILE).readText()
                } catch (e: Throwable) {
                    "(读取结果文件失败: ${e.message})"
                }
                postNet(StringBuilder(text))
            } catch (e: Throwable) {
                Log.e("TvCheck", "诊断异常", e)
                postNet(StringBuilder("诊断异常: ${e.javaClass.simpleName}: ${e.message}"))
            }
        }
    }

    /** 从配置里取服务器 host（去掉 scheme 和路径）。 */
    private fun defaultServerHost(): String {
        val raw = etServer.text.toString().trim().ifEmpty {
            readConf()["server"].orEmpty().ifEmpty { BuildConfig.DEFAULT_SERVER_URL }
        }
        return try {
            URL(normalize(raw)).host
        } catch (e: Throwable) {
            raw.substringAfter("://").substringBefore('/').substringBefore(':')
        }
    }

    /** 从配置里取服务器端口，默认 443。 */
    private fun defaultServerPort(): Int {
        val raw = etServer.text.toString().trim().ifEmpty {
            readConf()["server"].orEmpty().ifEmpty { BuildConfig.DEFAULT_SERVER_URL }
        }
        return try {
            val u = URL(normalize(raw))
            if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
        } catch (e: Throwable) {
            443
        }
    }

    // ==================== 在线更新 ====================

    /**
     * 从 HTTP 地址下载新 APK 并唤起系统安装器。
     * 这样电脑端只要开个静态服务，电视上点一下就能升级，不用再插 U 盘。
     */
    private fun downloadAndInstall(url: String) {
        if (url.isEmpty()) {
            tvUpdate.text = "请先填写 APK 地址（可在 5102 配置页里填）"
            return
        }
        tvUpdate.text = "开始下载：$url\n"
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
            req.setTitle("TV Check 更新")
            req.setDescription("正在下载新版本")
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE)
            req.allowScanningByMediaScanner()
            val id = dm.enqueue(req)
            tvUpdate.append("下载任务已提交 id=$id，等待完成…\n")
            pollDownload(dm, id, url)
        } catch (e: Throwable) {
            tvUpdate.append("启动下载失败: ${e.javaClass.simpleName}: ${e.message}\n")
        }
    }

    /** 轮询下载状态（有些国产 ROM 不发 DOWNLOAD_COMPLETE 广播）。 */
    private fun pollDownload(dm: DownloadManager, id: Long, url: String) {
        main.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val q = dm.query(DownloadManager.Query().setFilterById(id))
                    if (q.moveToFirst()) {
                        val status = q.getInt(q.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        val bytes = q.getLong(q.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = q.getLong(q.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                tvUpdate.append("下载完成 ($bytes 字节)，正在唤起系统安装器…\n")
                                launchInstaller()
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                tvUpdate.append("下载失败\n")
                                return
                            }
                            else -> {
                                tvUpdate.text = "下载中：$bytes / $total 字节\n"
                            }
                        }
                    }
                } catch (e: Throwable) {
                    tvUpdate.append("状态查询异常: ${e.message}\n")
                    return
                }
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun launchInstaller() {
        try {
            val f = File(Environment.getExternalStorageDirectory(), "Download/$UPDATE_FILE")
            if (!f.exists()) {
                tvUpdate.append("找不到安装包: ${f.absolutePath}\n")
                return
            }
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            tvUpdate.append("已唤起安装界面，请在电视上按「安装」确认。\n")
        } catch (e: Throwable) {
            tvUpdate.append("唤起安装器失败: ${e.javaClass.simpleName}: ${e.message}\n")
        }
    }

    // ==================== 账号密码登录（换取 AccessToken）====================

    private fun runLogin() {
        refreshFromConf()
        val base = etServer.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        if (base.isEmpty() || user.isEmpty()) {
            tvNet.text = "需要服务器地址和用户名（可在 5102 配置页里填好后回到电视）"
            return
        }
        tvNet.text = "登录中…"
        worker.execute {
            val sb = StringBuilder()
            sb.append("服务器: $base\n用户: $user\n\n")
            val body = JSONObject().put("Username", user).put("Pw", pass).toString()
            var token: String? = null

            // 栈 × 路径 组合逐个试：
            //   默认 TLS = 系统默认（4.3 只有 TLS1.0，服务器会拒）
            //   强制TLS1.2 = 自定义 socket factory 把 enabledProtocols 抬到 1.2/1.1
            for (stack in listOf("强制TLS1.2", "默认TLS")) {
                if (token != null) break
                for (path in listOf("/Users/AuthenticateByName", "/emby/Users/AuthenticateByName")) {
                    if (token != null) break
                    for (force in listOf(true, false)) {
                        val label = "[$stack] $path"
                        try {
                            val (code, text) = postJsonSafe(base, path, body, force, false)
                            sb.append("  $label → HTTP $code\n")
                            if (code == 200 || code == 401) {
                                sb.append("    ${text.take(300)}\n")
                                val t = extractToken(text)
                                if (t != null) {
                                    token = t
                                    sb.append("    ✓ 登录成功（$stack），AccessToken 已保存\n")
                                } else if (code == 401) {
                                    sb.append("    ✗ 用户名或密码错误\n")
                                }
                            } else {
                                sb.append("    ${text.take(200)}\n")
                            }
                        } catch (e: Throwable) {
                            sb.append("  $label → 失败 ${e.javaClass.simpleName}: ${e.message}\n")
                        }
                        if (token != null) break
                    }
                }
            }

            if (token != null) {
                // 把 token 写回配置，后续所有请求自动带上
                val conf = HashMap(readConf())
                conf["api_key"] = token
                writeConf(conf)
                sb.append("\nToken 已写入 /sdcard/tvcheck.conf 的 api_key，")
                sb.append("之后的测试与播放都会自动携带它。\n")
            } else {
                sb.append("\n没拿到 Token。\n")
                sb.append("若全部报 SSL 握手失败 ⇒ 检查 socket factory 是否把 enabledProtocols ")
                sb.append("抬到了 TLSv1.2（4.3 默认只发 TLS1.0，服务器最低要 1.1）。\n")
            }
            postNet(sb)
        }
    }

    private fun extractToken(text: String): String? = try {
        val o = JSONObject(text)
        if (o.has("AccessToken")) o.getString("AccessToken") else null
    } catch (e: Throwable) {
        null
    }

    private fun postJson(
        base: String,
        path: String,
        json: String,
        forceTls12: Boolean,
        useBc: Boolean
    ): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            // 4.3 默认只启用 TLS 1.0，服务器最低要 1.1 → 必须抬高 enabledProtocols。
            // 信任走内置锚点：4.3 的系统 CA 库是 2013 年的，不认 Let's Encrypt 新根。
            val mode = TlsMatrix.TrustMode.BUNDLED
            val factory = TlsStack.socketFactory(this, useBc, mode)
            if (factory != null) {
                conn.sslSocketFactory = if (forceTls12) {
                    TlsStack.forceTls12(factory)
                } else {
                    factory
                }
            }
        }
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty(
            "X-Emby-Authorization",
            "MediaBrowser Client=\"TV Check\", Device=\"TV\", DeviceId=\"tvcheck\", Version=\"1.0.0\""
        )
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    /**
     * 同上，但把 Android 的 401 处理异常还原成正常的 HTTP 401。
     *
     * Android 的 HttpURLConnection 遇到 401 且响应里没有 `WWW-Authenticate` 头时，
     * 会在 `getResponseCode()` 阶段抛
     * `IOException: No authentication challenges found`，
     * 根本读不到响应体。Emby 不发这个头，所以必须手工兜住，
     * 否则「凭据不对」会被误报成「网络/TLS 失败」。
     */
    private fun postJsonSafe(
        base: String,
        path: String,
        json: String,
        forceTls12: Boolean,
        useBc: Boolean
    ): Pair<Int, String> {
        return try {
            postJson(base, path, json, forceTls12, useBc)
        } catch (e: Throwable) {
            val head = (e.message ?: "").substringBefore('\n')
            if (head.contains("No authentication challenges")) {
                401 to "(服务端返回 401 但未提供 WWW-Authenticate 头)"
            } else {
                throw e
            }
        }
    }

    // ==================== 远程配置服务 ====================

    private fun startConfigServer() {
        if (cfgServer?.isRunning() == true) {
            showCfgStatus()
            return
        }
        val server = ConfigServer(
            port = 5102,
            provider = { readConf() },
            onSave = { map -> applyConf(map) }
        )
        val ip = server.start()
        if (ip == null) {
            tvCfg.text = "启动失败：端口 5102 可能被占用，或系统不允许监听。\n点「停止」后再试。"
            return
        }
        cfgServer = server
        cfgStopAt = System.currentTimeMillis() + 10 * 60 * 1000L
        showCfgStatus()
        // 每秒刷新一次倒计时（之前只在启动时算一次，界面一直显示 9分59秒）
        main.postDelayed(object : Runnable {
            override fun run() {
                if (cfgServer === server && server.isRunning()) {
                    showCfgStatus()
                    main.postDelayed(this, 1000)
                } else if (cfgServer === server) {
                    stopConfigServer()
                    tvCfg.append("\n[已到 10 分钟自动关闭]")
                }
            }
        }, 1000)
    }

    private fun stopConfigServer() {
        cfgServer?.stop()
        cfgServer = null
        tvCfg.text = "配置服务已停止。"
    }

    private fun showCfgStatus() {
        val ip = ConfigServer.localIp() ?: "<本机IP>"
        val left = ((cfgStopAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
        tvCfg.text = """
            配置服务运行中（端口 5102）
            在电脑浏览器打开：  http://$ip:5102
            剩余自动关闭时间： ${left / 60} 分 ${left % 60} 秒
            （同网段其他设备也能访问，别放敏感 Key 到公网环境）
        """.trimIndent()
    }

    /** 远程保存后：落盘 + 立刻刷新输入框，下一次测试即刻生效。 */
    private fun applyConf(map: Map<String, String>) {
        // 注意：ConfigServer 的回调跑在后台线程，碰 View 必须切回主线程，
        // 否则会抛 CalledFromWrongThreadException，响应也发不出去（浏览器看到空白页）。
        writeConf(map)
        main.post {
            try {
                map["server"]?.let { if (it.isNotEmpty()) etServer.setText(it) }
                map["media"]?.let { if (it.isNotEmpty()) etMedia.setText(it) }
                map["username"]?.let { if (it.isNotEmpty()) etUser.setText(it) }
                map["password"]?.let { if (it.isNotEmpty()) etPass.setText(it) }
                map["apk_url"]?.let { if (it.isNotEmpty()) etApkUrl.setText(it) }
                log.append("📝 配置已从远程更新（${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}）\n\n")
                renderPlay()
            } catch (e: Throwable) {
                Log.e("TvCheck", "刷新界面失败", e)
            }
        }
    }

    /** 把配置写到 /sdcard/tvcheck.conf（电视可读，U 盘也能拷）。 */
    private fun writeConf(map: Map<String, String>) {
        try {
            File("/sdcard/tvcheck.conf").writeText(
                map.entries.joinToString("\n") { "${it.key}=${it.value}" }
            )
        } catch (e: Throwable) {
            Log.e("TvCheck", "写入 conf 失败", e)
        }
    }

    // ==================== 网络 / TLS 测试 ====================

    private fun runNetTest() {
        // 远程配置可能刚被改过，每次测试前重新读一次配置文件
        refreshFromConf()

        val raw = etServer.text.toString().trim()
        if (raw.isEmpty()) {
            tvNet.text = "请先填写服务器地址，例如 http://192.168.1.10:8096"
            return
        }
        tvNet.text = "测试中…"
        worker.execute {
            val sb = StringBuilder()
            sb.append("目标: $raw\n\n")

            val url = try {
                URL(normalize(raw))
            } catch (e: Throwable) {
                sb.append("地址格式错误: ${e.javaClass.simpleName}: ${e.message}\n")
                postNet(sb)
                return@execute
            }
            val host = url.host
            val port = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80

            // 1) TCP 可达性
            val t0 = System.currentTimeMillis()
            try {
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 8000) }
                sb.append("[1] TCP $host:$port 连通 ✓  (${System.currentTimeMillis() - t0} ms)\n")
            } catch (e: Throwable) {
                sb.append("[1] TCP $host:$port 失败 ✗  ${e.javaClass.simpleName}: ${e.message}\n")
                sb.append("    → 服务器没开? 端口不对? 电视和服务器不在同一网段?\n\n")
                postNet(sb)
                return@execute
            }
            sb.append('\n')

            // 2) HTTP GET：Emby 公开接口
            sb.append("[2] HTTP 明文请求\n")
            probeHttp(sb, "http://$host:$port/System/Info/Public")
            probeHttp(sb, "http://$host:$port/emby/System/Info/Public")
            sb.append('\n')

            // 3) HTTPS 默认栈（系统默认信任 + 默认协议）
            sb.append("[3] HTTPS（HttpsURLConnection 默认配置）\n")
            probeHttps(sb, host, port, "/System/Info/Public", false, TrustMode.DEFAULT)
            sb.append('\n')

            // 3b) HTTPS 强制 TLS1.2（Android 4.3 默认不启用 1.1/1.2，公网服务器几乎只给 1.2）
            sb.append("[3b] HTTPS（强制 TLSv1.1/TLSv1.2，socket factory 改造）\n")
            probeHttps(sb, host, port, "/System/Info/Public", true, TrustMode.DEFAULT)
            sb.append('\n')

            // 3c) HTTPS 强制 TLS1.2 + 信任所有证书：用来区分「协议不通」和「证书链不被 4.3 信任」
            sb.append("[3c] HTTPS（强制 TLS1.2 + 不校验证书）\n")
            probeHttps(sb, host, port, "/System/Info/Public", true, TrustMode.ALL)
            sb.append("    ↑ 若 3b 失败但 3c 成功 ⇒ 服务器没问题，是这台机器 2013 年的系统 CA 库\n")
            sb.append("      不认识现代根证书，正式客户端必须自带 CA 或改用 Conscrypt\n\n")

            // 3d) HTTPS 强制 TLS1.2 + 内置 Let's Encrypt 锚点：验证「自带 CA」这条解法是否可行
            sb.append("[3d] HTTPS（强制 TLS1.2 + 内置 Let's Encrypt 锚点）\n")
            probeHttps(sb, host, port, "/System/Info/Public", true, TrustMode.BUNDLED)
            sb.append("    ↑ 若 3d 成功 ⇒ 正式客户端自带 CA 即可解决，不必引入 Conscrypt\n\n")

            // 4) 逐个 TLS 版本裸握手
            sb.append("[4] TLS 版本逐个握手（不看证书是否被信任，只看服务端是否接受）\n")
            listOf("TLSv1", "TLSv1.1", "TLSv1.2").forEach { p ->
                tlsProbe(sb, host, port, p)
            }
            sb.append('\n')

            // 5) 设备自身能力
            sb.append("[5] 本机 TLS 能力\n")
            try {
                val f = SSLSocketFactory.getDefault()
                sb.append("    支持: ${invokeArray(f, "getSupportedProtocols")?.joinToString(", ")}\n")
                sb.append("    默认套件: ${invokeArray(f, "getDefaultCipherSuites")?.take(3)?.joinToString(", ")} …\n")
            } catch (e: Throwable) {
                sb.append("    读取失败: ${e.message}\n")
            }
            sb.append("\n结论: 上面哪个 TLS 版本握手成功，就用那个；\n")
            sb.append("      若只有 TLSv1 成功，正式客户端必须显式开启 TLSv1.1/1.2 或改服务器配置。\n")

            postNet(sb)
        }
    }

    private fun postNet(sb: StringBuilder) {
        main.post { tvNet.text = sb.toString() }
    }

    private fun probeHttp(sb: StringBuilder, urlStr: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "tvcheck/1.0 (Android)")
            conn.setRequestProperty("Accept", "application/json")
            apiKey()?.let { conn.setRequestProperty("X-Emby-Token", it) }
            val code = conn.responseCode
            val body = try {
                (conn.inputStream ?: conn.errorStream)?.use { readAll(it) } ?: ""
            } catch (e: Throwable) {
                "(读 body 失败: ${e.message})"
            }
            sb.append("    $urlStr\n")
            sb.append("      → HTTP $code, ${conn.contentType}, ${conn.contentLength} bytes\n")
            if (body.isNotEmpty()) sb.append("      → ${body.take(240)}\n")
        } catch (e: Throwable) {
            sb.append("    $urlStr\n      → 失败 ${e.javaClass.simpleName}: ${e.message}\n")
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    private fun probeHttps(
        sb: StringBuilder,
        host: String,
        port: Int,
        path: String,
        forceTls12: Boolean,
        trustMode: TrustMode
    ) {
        var conn: HttpsURLConnection? = null
        try {
            conn = URL("https://$host:$port$path").openConnection() as HttpsURLConnection
            if (forceTls12) {
                val mode = when (trustMode) {
                    TrustMode.ALL -> TlsMatrix.TrustMode.ALL
                    TrustMode.BUNDLED -> TlsMatrix.TrustMode.BUNDLED
                    TrustMode.DEFAULT -> TlsMatrix.TrustMode.ALL
                }
                val factory = TlsStack.socketFactory(this, false, mode)
                if (factory != null) {
                    conn.sslSocketFactory = TlsStack.forceTls12(factory)
                }
            }
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "tvcheck/1.0 (Android)")
            apiKey()?.let { conn.setRequestProperty("X-Emby-Token", it) }
            val code = conn.responseCode
            sb.append("    https://$host:$port$path → HTTP $code\n")
            sb.append("      密码套件: ${conn.cipherSuite}\n")
            if (forceTls12) {
                sb.append("      ✓ 说明：显式开启 1.1/1.2 后能通，正式客户端需要同样的 socket factory 改造\n")
            }
        } catch (e: Throwable) {
            sb.append("    https://$host:$port$path → 失败 ${e.javaClass.simpleName}: ${e.message}\n")
            if (e is javax.net.ssl.SSLException) {
                sb.append("      → 证书/协议问题。Android 4.3 默认只认 TLSv1，且只信系统 CA；\n")
                sb.append("        自签名证书必须让客户端信任，或改用 http://\n")
            }
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    private fun tlsProbe(sb: StringBuilder, host: String, port: Int, protocol: String) {
        var socket: SSLSocket? = null
        try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(TRUST_ALL), null)
            socket = ctx.socketFactory.createSocket(host, port) as SSLSocket
            socket.soTimeout = 8000
            socket.enabledProtocols = arrayOf(protocol)
            socket.startHandshake()
            sb.append("    $protocol → 握手成功 ✓  协议=${invokeString(socket, "getProtocol")}  套件=${invokeString(socket, "getCipherSuite")}\n")
        } catch (e: Throwable) {
            sb.append("    $protocol → 失败 ✗  ${e.javaClass.simpleName}: ${e.message}\n")
        } finally {
            try { socket?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun readAll(input: java.io.InputStream): String =
        input.bufferedReader().use { it.readText() }

    // ==================== 配置文件 ====================

    /**
     * 读取 U 盘/SD 卡上的 /sdcard/tvcheck.conf，格式为 key=value 每行一条：
     *   server=http://192.168.1.10:8096
     *   api_key=xxxx
     *   media=https://xxx/stream.m3u8
     * 这样换服务器地址不需要重新打包，插上 U 盘即可。
     */
    private fun readConf(): Map<String, String> {
        val out = HashMap<String, String>()
        val candidates = arrayOf(
            File("/sdcard/tvcheck.conf"),
            File(Environment.getExternalStorageDirectory(), "tvcheck.conf")
        )
        for (f in candidates) {
            try {
                if (!f.exists()) continue
                f.forEachLine { line ->
                    val s = line.trim()
                    if (s.isEmpty() || s.startsWith("#")) return@forEachLine
                    val i = s.indexOf('=')
                    if (i > 0) out[s.substring(0, i).trim()] = s.substring(i + 1).trim()
                }
                break
            } catch (ignored: Throwable) {
            }
        }
        return out
    }

    private fun apiKey(): String? {
        val fromConf = readConf()["api_key"]
        if (!fromConf.isNullOrEmpty()) return fromConf
        return null
    }

    /** 把配置文件里的最新值刷到输入框（远程改完不用重启 App）。 */
    private fun refreshFromConf() {
        val conf = readConf()
        conf["server"]?.let { if (it.isNotEmpty()) etServer.setText(it) }
        conf["media"]?.let { if (it.isNotEmpty()) etMedia.setText(it) }
        conf["username"]?.let { if (it.isNotEmpty()) etUser.setText(it) }
        conf["password"]?.let { if (it.isNotEmpty()) etPass.setText(it) }
        conf["apk_url"]?.let { if (it.isNotEmpty()) etApkUrl.setText(it) }
    }

    /**
     * SSLSocket.getProtocol() / getCipherSuite() / SSLSocketFactory.getSupportedProtocols()
     * 在公开 SDK 里被 @hide，编译期引用不到，只能反射调用（AOSP 里是存在的）。
     */
    private fun invokeString(target: Any, method: String): String? = try {
        target.javaClass.getMethod(method).invoke(target) as? String
    } catch (e: Throwable) {
        null
    }

    private fun invokeArray(target: Any, method: String): Array<String>? = try {
        @Suppress("UNCHECKED_CAST")
        target.javaClass.getMethod(method).invoke(target) as? Array<String>
    } catch (e: Throwable) {
        null
    }

    private fun normalize(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        while (s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    // ==================== 播放测试 ====================

    private fun play(uri: String, label: String) {
        if (uri.isEmpty()) {
            log.append("媒体地址为空——请填地址，或直接点 H264/HEVC 样片按钮\n\n")
            renderPlay()
            return
        }
        log.append("── 播放: $label\n   $uri\n")
        progress = ""
        renderPlay()

        val p = player ?: ExoPlayer.Builder(this).build().also {
            playerView.player = it
            player = it
            attachAnalytics(it)
        }
        try {
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Throwable) {
            log.append("   启动失败: ${e.javaClass.simpleName}: ${e.message}\n\n")
            renderPlay()
        }
        startProgressTicker(p)
    }

    private fun attachAnalytics(p: ExoPlayer) {
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializationTimeMs: Long
            ) {
                val tag = when {
                    decoderName.contains("google", true) -> "（软解，CPU 解码，会吃力）"
                    decoderName.contains("MSTAR", true) -> "（MStar 硬解）"
                    decoderName.contains("OMX.", true) -> "（厂商/OMX 解码器）"
                    else -> ""
                }
                log.append("   解码器: $decoderName $tag\n")
                log.append("   初始化耗时: ${initializationTimeMs} ms\n\n")
                renderPlay()
            }

            override fun onVideoSizeChanged(
                eventTime: AnalyticsListener.EventTime,
                videoSize: VideoSize
            ) {
                log.append("   分辨率: ${videoSize.width}x${videoSize.height}\n")
                renderPlay()
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                if (droppedFrames > 0) {
                    log.append("   丢帧: +$droppedFrames (${elapsedMs} ms 内)\n")
                    renderPlay()
                }
            }

            override fun onPlaybackStateChanged(
                eventTime: AnalyticsListener.EventTime,
                state: Int
            ) {
                val name = when (state) {
                    ExoPlayer.STATE_IDLE -> "IDLE"
                    ExoPlayer.STATE_BUFFERING -> "BUFFERING"
                    ExoPlayer.STATE_READY -> "READY"
                    ExoPlayer.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                log.append("   状态: $name\n")
                renderPlay()
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException
            ) {
                log.append("   ✗ 播放错误 [${error.errorCodeName}] ${error.message}\n")
                error.cause?.let { log.append("     原因: ${it.javaClass.simpleName}: ${it.message}\n") }
                log.append('\n')
                renderPlay()
            }
        })
    }

    private fun startProgressTicker(p: ExoPlayer) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (player !== p) return
                try {
                    val pos = p.currentPosition / 1000
                    val dur = p.duration / 1000
                    val buffered = p.bufferedPercentage
                    progress = "   进度: ${pos}s / ${dur}s   缓冲 $buffered%\n"
                    renderPlay()
                } catch (ignored: Throwable) {
                }
                main.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun renderPlay() {
        tvPlay.text = log.toString() + progress
    }

    companion object {
        private const val UPDATE_FILE = "tvcheck-update.apk"

        /** 证书信任模式 */
        enum class TrustMode { DEFAULT, ALL, BUNDLED }

        /** 仅用于「探测服务端 TLS 能力」，不校验证书，不代表安全用法。 */
        private val TRUST_ALL = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
}


