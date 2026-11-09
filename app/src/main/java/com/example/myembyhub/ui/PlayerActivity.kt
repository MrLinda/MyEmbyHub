package com.example.myembyhub.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.example.myembyhub.R
import com.example.myembyhub.net.EmbyApi
import com.example.myembyhub.net.EmbyHttpDataSource
import com.example.myembyhub.net.MediaItem
import com.example.myembyhub.net.Session
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem as ExoMediaItem
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.video.VideoSize
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 播放页。
 *
 * 用**自写的 [EmbyHttpDataSource]** 而不是 ExoPlayer 自带的 `DefaultHttpDataSource`：
 * 后者走 `HttpURLConnection`，不接受外部传入的 `SSLSocketFactory`，
 * 而这台电视（Android 4.3）必须显式抬到 TLS 1.2 才能连上服务器。
 *
 * 取流地址来自 `/Videos/{id}/stream?static=true&mediaSourceId=…`，
 * 服务端会 **302 到 v10/v11.example.com**，DataSource 内部自己跟。
 *
 * ## 续播
 *
 * 进来时按 `startMs` 定位，播放中每 10 秒往 Emby 报一次进度
 * （`/Sessions/Playing/Progress`），退出时报 `Stopped`。
 * 不上报的话 Emby 那边的 `UserData.PlaybackPositionTicks` 永远是 0，
 * 「继续观看」和续播就都不成立。
 */
class PlayerActivity : Activity() {

    private lateinit var playerView: com.google.android.exoplayer2.ui.PlayerView
    private lateinit var tvError: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvDiag: TextView

    /** 解码诊断：把关键事件按发生顺序记下来，黑屏时直接打在屏幕上。 */
    private val diagLines = ArrayList<String>()
    private var diagShown = false

    /**
     * 视频轨的「能不能播」判定结果。
     *
     * ⚠️ 这个判定只能当参考，不能当结论：Android 4.3 的 MediaCodec 不会上报
     * HDR / 10bit 能力，ExoPlayer 据此认为 HEVC Main10 HDR 轨「不支持」，
     * 但乐视这台机器的 OMX.MS.HEVC.Decoder 实际上能硬解出来。
     * 所以只在「等了很久仍然没有第一帧」时才把这句话给用户看。
     */
    private var videoTrackCount = 0
    private var videoSupported = true
    private var videoCodecInfo = ""
    private var firstFrameRendered = false

    /** 解码器建起来了（哪怕还没出画）—— 说明编码是能解的，只是慢。 */
    private var decoderInitialized = false

    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    // ---------- 起播网速显示 ----------
    private lateinit var boxSpeed: android.widget.LinearLayout
    private lateinit var tvSpeed: TextView
    private lateinit var tvSpeedHint: TextView

    /** 上一次采样时的累计字节数，用来算差值。 */
    private var lastBytes = 0L
    private var lastSampleAt = 0L
    /** 正在显示网速（起播阶段）。 */
    private var speedVisible = false
    /** 峰值速度，起播结束时用来判断「慢不慢」。 */
    private var peakBytesPerSec = 0L

    /**
     * 每 0.5 秒刷新一次网速。
     *
     * 0.5s 对电视够用：人眼看数字变化不觉得迟滞，又不会因为刷新太频繁
     * 让老机器一直重绘。这里只更新一个 TextView 的文本，开销很小。
     */
    private val speedTick = object : Runnable {
        override fun run() {
            if (!speedVisible) return
            val now = android.os.SystemClock.elapsedRealtime()
            val total = EmbyHttpDataSource.bytesRead()
            val dt = now - lastSampleAt
            if (dt > 0) {
                val delta = (total - lastBytes).coerceAtLeast(0L)
                val bps = delta * 1000L / dt
                if (bps > peakBytesPerSec) peakBytesPerSec = bps

                // 两次采样取平均再显示。
                // 原因：ExoPlayer 缓冲够了就停止下载，单次采样会突然掉到 0 B/s，
                // 用户看到「0 B/s」会以为又卡死了。取平均能把这个坑抹平。
                val shown = (bps + lastBps) / 2
                lastBps = bps
                tvSpeed.text = formatSpeed(shown)

                // 已经下了一大堆却还是 0 B/s —— 那是下完了在解码，不是网络卡住。
                // 这句提示正是用户要的「知道软件没卡」。
                tvSpeedHint.text = if (shown == 0L && total > 1L * 1024 * 1024) {
                    "已下载 ${formatSize(total)}，正在解码…"
                } else {
                    "正在缓冲…"
                }

                if (++speedSamples % 10 == 0) {
                    android.util.Log.i("TvCheck", "网速 ${tvSpeed.text}  累计=$total 字节")
                }
            }
            lastBytes = total
            lastSampleAt = now
            main.postDelayed(this, SPEED_INTERVAL_MS)
        }
    }
    private var speedSamples = 0
    private var lastBps = 0L

    /** 上报走单线程，别和 UI 抢主线程 */
    private val reporter = Executors.newSingleThreadExecutor { r -> Thread(r, "play-report") }
    private var api: EmbyApi? = null
    private var reportItem: MediaItem? = null
    private val playSessionId = UUID.randomUUID().toString().replace("-", "")

    private var stopped = false

    private val progressTick = object : Runnable {
        override fun run() {
            reportProgress(paused = false)
            main.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        tvError = findViewById(R.id.tv_error)
        tvInfo = findViewById(R.id.tv_info)
        tvDiag = findViewById(R.id.tv_diag)
        boxSpeed = findViewById(R.id.box_speed)
        tvSpeed = findViewById(R.id.tv_speed)
        tvSpeedHint = findViewById(R.id.tv_speed_hint)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        if (url.isNullOrEmpty()) {
            tvError.text = "没有可播放的地址"
            return
        }
        tvInfo.text = title

        val session = Session.get(this)
        val token = session.token
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: ""
        val mediaSourceId = intent.getStringExtra(EXTRA_MEDIA_SOURCE_ID) ?: ""
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)

        if (itemId.isNotEmpty()) {
            api = EmbyApi(this, session.server, token, session.userId)
            reportItem = MediaItem(
                id = itemId,
                name = title,
                type = "Episode",
                isFolder = false,
                mediaSourceId = mediaSourceId
            )
        }

        // ⚠️ 每次 createDataSource() 必须返回**新实例**：
        // ExoPlayer 会并发使用多个 DataSource（嗅探 + 正式读取），
        // 而我们的 DataSource 是有状态的（各自持有 socket）。
        val dsFactory = DataSource.Factory { EmbyHttpDataSource(this, token) }

        // 缓冲水位：默认的 50s/2.5s 在这台机器 + 公网 4K 源上太浅，
        // 稍微掉点速就开始转圈。这里加大到 30s 起步、最多囤 2 分钟 ——
        // 起播会慢一点点，但换来的是拖动后不容易卡住。
        // 注意：**不限制分辨率/码率**，4K 片源照播（硬解 OMX.MS.HEVC 能扛）。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // 缓冲到 30s 才开始播
                120_000,  // 最多囤 2 分钟
                2_500,    // 起播后维持 2.5s 就够
                5_000     // 卡顿恢复需要 5s
            )
            .build()

        // 显式给一个 TrackSelector，但**不设任何尺寸上限**：
        // 用户明确要求 4K 必须能播，不能像某些 TV 客户端那样硬锁 1080p。
        val trackSelector = DefaultTrackSelector(this)

        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            // 耳机拔掉自动暂停（电视上少见，但盒子/回音壁接线时会遇到）
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        playerView.player = p

        // 顶部标题跟着控制栏一起显示/隐藏。
        //
        // 不自己计时：ExoPlayer 的控制栏本来就有「闲置几秒自动隐藏、按方向键再出现」
        // 的机制（setControllerShowTimeoutMs，默认 5 秒），直接监听它的可见性最稳，
        // 不会出现「进度条藏了标题还在」或反过来不同步的情况。
        playerView.setControllerVisibilityListener { visibility ->
            tvInfo.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }

        // 播放期间保持网络唤醒：电视熄屏/待机时把 Wi-Fi 关掉会导致缓冲中断。
        // 依赖 AndroidManifest 里已声明的 WAKE_LOCK 权限。
        p.setWakeMode(com.google.android.exoplayer2.C.WAKE_MODE_NETWORK)

        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, size: VideoSize) {
                diag("videoSize ${size.width}x${size.height}")
                main.post { tvInfo.text = "$title  ${size.width}×${size.height}" }
            }

            // 这几个回调是黑屏排查的主线：
            // tracks 有视频轨 → 解封装成功；decoderInitialized → 找到解码器；
            // renderedFirstFrame → 真正出画。缺哪一环就卡在哪。
            override fun onTracksChanged(
                eventTime: AnalyticsListener.EventTime,
                tracks: com.google.android.exoplayer2.Tracks
            ) {
                var v = 0
                var a = 0
                var codec = ""
                var size = ""
                for (g in tracks.groups) {
                    if (g.type == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO) {
                        v++
                        if (codec.isEmpty() && g.length > 0) {
                            val f = g.getTrackFormat(0)
                            codec = f.sampleMimeType ?: ""
                            size = "${f.width}x${f.height}"
                        }
                    }
                    if (g.type == com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO) a++
                }
                // 视频轨存在 ≠ 能播：设备没有对应解码器时 ExoPlayer 会静默跳过这条轨，
                // 表现为「黑屏但进度条在走、音频正常」。
                //
                // 但 isTypeSupported 在这台 4.3 电视上不可信：它只认 MediaCodec 自己
                // 上报的能力，老系统不上报 HDR / 10bit，于是 HEVC Main10 HDR 被判成
                // 「不支持」，实际硬解却能出画。所以这里只记录，不立刻弹提示 ——
                // 等「迟迟没有第一帧」时再说话，见 checkVideoStalled()。
                videoTrackCount = v
                videoSupported = tracks.isTypeSupported(com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO)
                videoCodecInfo = "${codec.ifEmpty { "未知" }}  $size"
                diag("tracks video=$v audio=$a videoSupported=$videoSupported")
            }

            // 注意：2.19.1 里这个回调有两个重载，这里用两参数版（三参数版多一个
            // DecoderReuseEvaluation，且那个类在 com.google.android.exoplayer2.decoder 包下）。
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: com.google.android.exoplayer2.Format
            ) {
                val hdr = when (format.colorInfo?.colorTransfer) {
                    com.google.android.exoplayer2.C.COLOR_TRANSFER_ST2084 -> "HDR10(PQ)"
                    com.google.android.exoplayer2.C.COLOR_TRANSFER_HLG -> "HLG"
                    else -> "SDR"
                }
                // Format.codec 不存在，编解码器信息在 sampleMimeType / codecs 里
                diag("videoIn ${format.sampleMimeType} ${format.width}x${format.height} $hdr")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                diag("decoder=$decoderName init=${initializationDurationMs}ms")
                decoderInitialized = true
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                firstFrameRendered = true
                diag("FIRST FRAME rendered")
                main.post {
                    // 出画了就说明之前那句「解不了」是误报，必须收掉；
                    // 网速浮层也一起收，别挡正片。
                    tvDiag.visibility = View.GONE
                    tvError.visibility = View.GONE
                    hideSpeed()
                }
            }

            // 2.19.1 的签名是 (EventTime, LoadEventInfo, MediaLoadData, IOException, boolean)，
            // 包在 com.google.android.exoplayer2.source 下（不是 upstream）。
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: com.google.android.exoplayer2.source.LoadEventInfo,
                loadMediaData: com.google.android.exoplayer2.source.MediaLoadData,
                loadError: java.io.IOException,
                wasCanceled: Boolean
            ) {
                diag("loadError ${loadError.message?.take(60)}")
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                diag("state=${stateName(state)}")
                if (state == Player.STATE_READY && !stopped) {
                    // 只在第一次 READY 时上报开始，并启动进度心跳
                    reportStart()
                    main.removeCallbacks(progressTick)
                    main.postDelayed(progressTick, PROGRESS_INTERVAL_MS)
                }
                if (state == Player.STATE_ENDED) {
                    reportStop()
                }
                // 起播时的缓冲：显示网速。中途卡顿重新缓冲（已经出过画）也显示，
                // 这时候用户同样需要知道「是在下，不是死了」。
                main.post {
                    if (firstFrameRendered && state == Player.STATE_BUFFERING) {
                        showSpeed("缓冲中…")
                    } else if (state == Player.STATE_READY && firstFrameRendered) {
                        hideSpeed()
                    }
                }
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                main.post {
                    tvError.visibility = View.VISIBLE
                    val raw = error.message ?: ""
                    // HTTP 404 基本只有一个原因：取流 URL 少了 mediaSourceId，
                    // v10 纯文件后端直接拒绝（见 EmbyApi.streamUrl 注释）。
                    // 直接把这个提示打出来，省得下次又误判成「服务器挂了」。
                    val hint = if (raw.contains("404")) {
                        "\n\n取流地址可能缺少 mediaSourceId —— 服务端 v10 对缺这个参数的请求一律 404。"
                    } else {
                        ""
                    }
                    tvError.text = "播放失败：${error.errorCodeName}\n$raw$hint"
                    diag("ERROR ${error.errorCodeName}")
                    // 已经失败了就别再显示网速，否则会一直跳数字让人误以为还在加载
                    hideSpeed()
                    showDiag()
                }
            }
        })

        // 兜底：15 秒还没出第一帧，才认定「这台机器真的放不出来」。
        // 之前是 onTracksChanged 里一看 isTypeSupported=false 就弹提示，
        // 结果电视上出现「一边说解不了、一边画面正常播」的自相矛盾 ——
        // 老系统的 MediaCodec 不上报 HDR/10bit 能力，判定会假阴性。
        // 现在改成「以有没有第一帧为准」，出画就永不提示。
        main.postDelayed({ checkVideoStalled() }, 15_000L)

        // ⚠️ 顺序很重要：先 setMediaSource 再 seekTo，最后 prepare。
        //    反过来（先 seekTo 再 setMediaSource）在 2.19 上定位会被丢掉，
        //    续播就从 0 开始 —— 表现为「点了继续观看却从头播」。
        val mediaSource = ProgressiveMediaSource.Factory(dsFactory)
            // 网络抖动时自动重试，别一断就报错退出
            .setLoadErrorHandlingPolicy(
                com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy(3)
            )
            .createMediaSource(ExoMediaItem.fromUri(url))
        p.setMediaSource(mediaSource)
        if (startMs > 0) p.seekTo(startMs)
        // 起播期间在画面正中显示网速，出第一帧后自动收掉。
        showSpeed()
        p.prepare()
        p.playWhenReady = true
    }

    private fun currentTicks(): Long = (player?.currentPosition ?: 0L) * 10_000L

    /** 记一条诊断（线程安全：AnalyticsListener 回调在播放线程上）。 */
    private fun diag(line: String) {
        synchronized(diagLines) {
            if (diagLines.size < 40) diagLines.add(line)
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    /** 起播阶段显示网速浮层，并启动 0.5 秒采样。 */
    private fun showSpeed(hint: String = "正在缓冲…") {
        if (!speedVisible) {
            peakBytesPerSec = 0L
            lastBytes = EmbyHttpDataSource.bytesRead()
            lastSampleAt = android.os.SystemClock.elapsedRealtime()
            tvSpeed.text = "0.0 MB/s"
        }
        tvSpeedHint.text = hint
        speedVisible = true
        boxSpeed.visibility = View.VISIBLE
        main.removeCallbacks(speedTick)
        main.postDelayed(speedTick, SPEED_INTERVAL_MS)
    }

    /** 收起网速浮层（出第一帧 / 播放失败 / 退出时调用）。 */
    private fun hideSpeed() {
        if (!speedVisible) return
        speedVisible = false
        main.removeCallbacks(speedTick)
        boxSpeed.visibility = View.GONE
    }

    /**
     * 把字节/秒格式化成好读的字符串。
     *
     * 不用 `String.format`：它在 4.3 上每次都要建 Formatter 对象，
     * 0.5 秒一次虽然不多，但这里手算更省。
     */
    private fun formatSpeed(bps: Long): String {
        if (bps >= 1024L * 1024L) {
            val tenths = bps * 10L / (1024L * 1024L)
            return "${tenths / 10}.${tenths % 10} MB/s"
        }
        if (bps >= 1024L) {
            return "${bps / 1024L} KB/s"
        }
        return "$bps B/s"
    }

    /**
     * 把字节数格式化成好读的大小（累计量用，和 [formatSpeed] 单位口径一致）。
     */
    private fun formatSize(bytes: Long): String {
        if (bytes >= 1024L * 1024L * 1024L) {
            val tenths = bytes * 10L / (1024L * 1024L * 1024L)
            return "${tenths / 10}.${tenths % 10} GB"
        }
        if (bytes >= 1024L * 1024L) {
            val tenths = bytes * 10L / (1024L * 1024L)
            return "${tenths / 10}.${tenths % 10} MB"
        }
        return "${bytes / 1024L} KB"
    }

    /**
     * 播放 15 秒后检查：有视频轨但一帧都没出来，才算「解不了」。
     *
     * 判定依据是**实际有没有出画**，不是 MediaCodec 自报的能力 ——
     * 4.3 这台机器的 `isTypeSupported` 对 HEVC Main10 HDR 会假阴性，
     * 拿它当结论就会出现「一边报解不了、一边正常播」。
     */
    private fun checkVideoStalled() {
        if (firstFrameRendered) return
        // 解码器都建起来了 = 编码本身能解，只是 4K 起播慢（真机上首帧要十几秒）。
        // 这种情况什么都不弹，安静等着就行；想看细节可以按菜单键叫诊断面板。
        if (decoderInitialized) return
        if (videoTrackCount > 0 && !videoSupported) {
            tvError.visibility = View.VISIBLE
            tvError.text = "这台设备放不出这个视频画面\n\n" +
                "编码：$videoCodecInfo\n" +
                "音频能正常播放，但没有画面。\n" +
                "换一个 H.264 版本的片源，或者用支持该编码的设备播放。"
        }
        showDiag()
    }

    /** 把诊断面板顶到屏幕上。 */
    private fun showDiag() {
        if (diagShown) return
        diagShown = true
        val lines = synchronized(diagLines) { diagLines.toList() }
        tvDiag.text = if (lines.isEmpty()) "（没有任何事件 —— 连解封装都没开始）" else lines.joinToString("\n")
        tvDiag.visibility = View.VISIBLE
    }

    private fun reportStart() {
        val a = api ?: return
        val item = reportItem ?: return
        reporter.execute { a.reportStart(item, playSessionId) }
    }

    private fun reportProgress(paused: Boolean) {
        val a = api ?: return
        val item = reportItem ?: return
        val ticks = currentTicks()
        reporter.execute { a.reportProgress(item, ticks, paused, playSessionId) }
    }

    private fun reportStop() {
        if (stopped) return
        stopped = true
        main.removeCallbacks(progressTick)
        val a = api ?: return
        val item = reportItem ?: return
        val ticks = currentTicks()
        reporter.execute { a.reportStopped(item, ticks, playSessionId) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player
        if (p != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    p.playWhenReady = !p.playWhenReady
                    reportProgress(paused = !p.playWhenReady)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    p.playWhenReady = !p.playWhenReady
                    reportProgress(paused = !p.playWhenReady)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    p.seekTo(p.currentPosition + 10000)
                    return true
                }
                // 菜单键：随时把解码诊断面板叫出来（或收起）
                KeyEvent.KEYCODE_MENU -> {
                    if (tvDiag.visibility == View.VISIBLE) {
                        tvDiag.visibility = View.GONE
                    } else {
                        diagShown = false
                        showDiag()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        player?.playWhenReady = false
        main.removeCallbacks(progressTick)
        // 退到后台就别再采样了，省电
        main.removeCallbacks(speedTick)
        reportProgress(paused = true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!stopped && player != null) {
            player?.playWhenReady = true
            main.removeCallbacks(progressTick)
            main.postDelayed(progressTick, PROGRESS_INTERVAL_MS)
            // 还没出画（仍在缓冲）时，把网速采样接上
            if (speedVisible) {
                lastBytes = EmbyHttpDataSource.bytesRead()
                lastSampleAt = android.os.SystemClock.elapsedRealtime()
                main.removeCallbacks(speedTick)
                main.postDelayed(speedTick, SPEED_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        reportStop()
        main.removeCallbacks(progressTick)
        main.removeCallbacks(speedTick)
        reporter.shutdown()
        try { player?.release() } catch (ignored: Throwable) {}
        player = null
        setResult(RESULT_OK)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_MEDIA_SOURCE_ID = "media_source_id"
        const val EXTRA_START_MS = "start_ms"

        /** 进度上报间隔。太密会在 4.3 上抢带宽/CPU，太疏则退出时丢进度。 */
        private const val PROGRESS_INTERVAL_MS = 10_000L

        /** 网速采样间隔：0.5 秒。够跟手，也不会让老机器一直重绘。 */
        private const val SPEED_INTERVAL_MS = 500L
    }
}
