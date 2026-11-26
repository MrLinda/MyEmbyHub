package work.luxiaoxiao.myembyhub.net

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * 播放性能打点：**每次起播都记一条**，播完（或播失败）悄悄发给反馈服务器。
 *
 * ## 为什么要它
 *
 * 反馈是用户主动点的，一年也点不了几次；而「卡不卡」是要看**长期曲线**的 ——
 * 首帧多少毫秒、一集里掉多少帧、重缓冲几次。这些只有每次都记才看得出来，
 * 所以这条路和反馈分开：不填表、不弹框、失败静默。
 *
 * ## 只发数字，不发内容
 *
 * 带片名（方便对上「哪一类片子慢」）和编码参数，不带进度、不带账号、
 * 不带片库清单。一次几十字节到几百字节，走同一个自建服务器。
 *
 * ## 为什么单独一个线程
 *
 * 打点是顺带的，绝不能影响播放：单线程 + 最低优先级 + daemon。
 * 进程退出时还没发出去就算了 —— 宁可丢一条数据，也别在 onDestroy 里等网络。
 *
 * ## 开关
 *
 * 受 **设置 → 体验优化计划** 控制（[Session.playStatsEnabled]，默认关）。
 * 关着的时候 [begin] 直接不记、[finish] 直接不发，网络上一个字节都不会出去。
 */
object PlayStats {

    /** 上报接口路径（拼在反馈服务器地址后面）。 */
    const val PATH_PLAY = "/api/v1/play"

    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 8_000

    /**
     * 严重低于这个时长的「播放」不算一次有效样本。
     *
     * 用户点进去又马上退出来（换片、点错了）会被记成「首帧 300ms、看了 0 秒」，
     * 混进统计里会把首帧均值拉得很好看 —— 宁可少一条。
     */
    private const val MIN_VALID_WATCH_MS = 3_000L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "play-stats").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val lock = Any()

    /** 本次播放的开始时间（elapsedRealtime）；0 = 没有正在记录的一次。 */
    private var startedAt = 0L
    private var title = ""
    private var itemId = ""
    private var firstFrameMs = -1L
    private var droppedFrames = 0
    private var rebufferCount = 0
    private var rebufferMs = 0L
    private var bufferingSince = 0L
    private var errorCode = ""
    private var errorMessage = ""
    private var container = ""
    private var videoCodec = ""
    private var audioCodec = ""
    private var width = 0
    private var height = 0
    private var bitrateKbps = 0
    private var decoderName = ""

    /**
     * 起播：清空上一次的现场。在 `player.prepare()` 之前调用。
     *
     * @param ctx 用来读「体验优化计划」开关（[Session.playStatsEnabled]）。
     *        开关关着时只把 [startedAt] 归零、什么都不记 —— 见 [Session.playStatsEnabled]
     *        注释里「为什么两处都要拦」。
     */
    fun begin(ctx: Context, itemTitle: String, id: String) {
        if (!Session.get(ctx).playStatsEnabled) {
            synchronized(lock) { startedAt = 0L }
            return
        }
        synchronized(lock) {
            startedAt = android.os.SystemClock.elapsedRealtime()
            title = itemTitle.take(120)
            itemId = id
            firstFrameMs = -1L
            droppedFrames = 0
            rebufferCount = 0
            rebufferMs = 0L
            bufferingSince = 0L
            errorCode = ""
            errorMessage = ""
            container = ""
            videoCodec = ""
            audioCodec = ""
            width = 0
            height = 0
            bitrateKbps = 0
            decoderName = ""
        }
    }

    /** 视频轨参数确定（`onTracksChanged`）。 */
    fun tracks(codec: String, audio: String, w: Int, h: Int, kbps: Int, containerHint: String) {
        synchronized(lock) {
            // 只在第一次有值时写，后面的组切换不该覆盖首帧那次现场
            if (videoCodec.isEmpty()) videoCodec = codec
            if (audioCodec.isEmpty()) audioCodec = audio
            if (width == 0) width = w
            if (height == 0) height = h
            if (bitrateKbps == 0) bitrateKbps = kbps
            if (container.isEmpty()) container = containerHint
        }
    }

    fun decoder(name: String) {
        synchronized(lock) { if (decoderName.isEmpty()) decoderName = name }
    }

    /** 第一帧真正上屏 —— 首帧耗时就是此刻减开始时刻。 */
    fun firstFrame() {
        synchronized(lock) {
            if (startedAt == 0L || firstFrameMs >= 0) return
            firstFrameMs = android.os.SystemClock.elapsedRealtime() - startedAt
        }
    }

    /** ExoPlayer 每次回调报告自上次通知以来的掉帧数。 */
    fun droppedFrames(count: Int) {
        synchronized(lock) { if (startedAt != 0L) droppedFrames += count }
    }

    /**
     * 缓冲状态变化。只在「已经出过画」之后计数 —— 起播那一段属于首帧耗时，
     * 重复计进重缓冲会让两个指标互相污染。
     */
    fun buffering(nowBuffering: Boolean) {
        synchronized(lock) {
            if (firstFrameMs < 0) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (nowBuffering) {
                if (bufferingSince == 0L) bufferingSince = now
            } else if (bufferingSince > 0L) {
                rebufferCount++
                rebufferMs += now - bufferingSince
                bufferingSince = 0L
            }
        }
    }

    fun error(code: String, message: String) {
        synchronized(lock) {
            errorCode = code.take(80)
            errorMessage = message.take(400)
        }
    }

    /**
     * 结束这一次播放并上报。
     *
     * @param positionMs 退出时的播放位置（判断「真看了还是扫了一眼」比墙钟准）
     * @param reason `exit` / `ended` / `error`
     */
    fun finish(ctx: Context, endpoint: String, positionMs: Long, reason: String) {
        if (endpoint.isEmpty()) return
        // 开关是「上报这一刻」说了算（不是 begin 那一刻）：
        // 用户中途去设置里关掉了，这一条就不该再发。
        if (!Session.get(ctx).playStatsEnabled) {
            synchronized(lock) { startedAt = 0L }
            return
        }

        val payload: JSONObject
        synchronized(lock) {
            if (startedAt == 0L) return
            val wallMs = android.os.SystemClock.elapsedRealtime() - startedAt
            // 收尾：还在缓冲里退出的话，把最后那段也算上
            if (bufferingSince > 0L) {
                rebufferCount++
                rebufferMs += android.os.SystemClock.elapsedRealtime() - bufferingSince
                bufferingSince = 0L
            }
            val meaningful = positionMs >= MIN_VALID_WATCH_MS || errorCode.isNotEmpty() || firstFrameMs >= 0
            if (!meaningful) {
                startedAt = 0L
                return
            }
            val um = UpdateManager(ctx)
            payload = JSONObject().apply {
                put("clientVersion", um.currentVersionName())
                put("versionCode", um.currentVersionCode().toInt())
                put("model", android.os.Build.MODEL ?: "")
                put("release", android.os.Build.VERSION.RELEASE ?: "")
                put("sdkInt", android.os.Build.VERSION.SDK_INT)
                put("abi", android.os.Build.CPU_ABI ?: "")
                put("itemTitle", title)
                put("itemId", itemId)
                put("container", container)
                put("videoCodec", videoCodec)
                put("audioCodec", audioCodec)
                put("width", width)
                put("height", height)
                put("bitrateKbps", bitrateKbps)
                put("decoder", decoderName)
                put("firstFrameMs", firstFrameMs)
                put("playedMs", positionMs.coerceAtLeast(0))
                put("wallMs", wallMs)
                put("droppedFrames", droppedFrames)
                put("rebufferCount", rebufferCount)
                put("rebufferMs", rebufferMs)
                put("errorCode", errorCode)
                put("errorMessage", errorMessage)
                put("reason", reason)
            }
            // 立刻清空：同一个 Activity 里重播下一集会再 begin()，
            // 万一 begin 没走到（异常退出），也不该把这份数据重复发一遍。
            startedAt = 0L
        }

        val appCtx = ctx.applicationContext
        try {
            exec.execute { post(appCtx, endpoint, payload) }
        } catch (ignored: Throwable) {
            // 线程池已关闭（进程在退出）——丢掉即可，打点不重要
        }
    }

    private fun post(ctx: Context, endpoint: String, payload: JSONObject) {
        val url = endpoint.trimEnd('/') + PATH_PLAY
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val host = try { UrlKit.parse(url).host } catch (e: Throwable) { "" }
        try {
            // 和反馈同一条链路：用户自建的服务，证书可能是自签的；
            // 也可能是只开 GCM 的 nginx。走 TlsConn 才会自动换栈重试。
            if (url.startsWith("https", ignoreCase = true) && host.isNotEmpty()) {
                TlsConn.withRetry(ctx, TrustMode.ALL, host) { factory -> attempt(ctx, url, body, factory, payload) }
            } else {
                attempt(ctx, url, body, null, payload)
            }
        } catch (e: Throwable) {
            android.util.Log.i("PlayStats", "打点失败（忽略）：${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun attempt(
        ctx: Context,
        url: String,
        body: ByteArray,
        factory: javax.net.ssl.SSLSocketFactory?,
        payload: JSONObject
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            // 和反馈同一条链路：用户自建的服务，证书可能是自签的。
            // 里面只有播放数字，放宽校验不涉及别人的数据。
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = factory
                    ?: TlsStack.socketFactory(ctx, TrustMode.ALL)
                    ?: throw IOException("无法构造 TLS socket factory")
            }
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("User-Agent", "MyEmbyHub/" + UpdateManager(ctx).currentVersionName())
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            // 不读响应体也得取一次状态码，否则请求可能根本没发出去
            val code = conn.responseCode
            android.util.Log.i("PlayStats", "打点已发（HTTP $code）：首帧 ${payload.optLong("firstFrameMs")}ms")
        } catch (e: Throwable) {
            // ⚠️ 握手失败要抛给 [TlsConn.withRetry]，否则换栈重试是死代码。
            //    打点的其他失败照旧静默吞掉（见下）。
            if (TlsStack.isHandshakeFailure(e)) throw e
            // 静默：打点失败不能变成用户可见的错误，也不能写进 ErrorLog
            // （那会污染反馈里的「最近几条错误」，让人误以为是播放问题）
            android.util.Log.i("PlayStats", "打点失败（忽略）：${e.javaClass.simpleName} ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }
}
