package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.EmbyApi
import work.luxiaoxiao.myembyhub.net.EmbyHttpDataSource
import work.luxiaoxiao.myembyhub.net.EpisodeRef
import work.luxiaoxiao.myembyhub.net.ErrorLog
import work.luxiaoxiao.myembyhub.net.IsoDataSourceFactory
import work.luxiaoxiao.myembyhub.net.MediaItem
import work.luxiaoxiao.myembyhub.net.PlaybackSnapshot
import work.luxiaoxiao.myembyhub.net.PlayStats
import work.luxiaoxiao.myembyhub.net.Session
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem as ExoMediaItem
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
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

    /** 按左右键拖动进度时的位置提示（「▶ 08:42 / 45:10」），1.2 秒后自动收起。 */
    private lateinit var tvSeek: TextView

    /** 暂停指示（「已暂停」）。控制栏关掉后，没有它就完全看不出按键生效了没。 */
    private lateinit var tvPause: TextView

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

    /**
     * 「迟迟没有第一帧」检查跑了几轮。
     *
     * 第一轮（起播后 15 秒）判「解码器都没建起来」这种情况；
     * 第二轮（再过 20 秒）判「解码器建起来了、音频也在走，就是一帧都不出」——
     * 后者是**硬解能力超限但被 ExoPlayer 静默跳过**的典型表现
     * （2.19.1 只在 logcat 里打一句 `Format exceeds selected codec's capabilities`，
     * 界面上什么都不说，用户看到的就是「有声音没画面」）。
     */
    private var stallRound = 0

    /** 解码能力预检只做一次（视频轨格式确定后立刻判）。 */
    private var capabilityChecked = false

    /**
     * 已经判定「这条视频轨超出硬解能力」并接管了界面。
     *
     * 一旦为 true：网速浮层、卡顿提示都不再掺和，避免两套文案互相覆盖。
     */
    private var capabilityBlocked = false

    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    // ---------- 切集 ----------

    /**
     * 同剧的剧集列表（按播出顺序）。
     *
     * 两个来源，按优先级：
     * ① 调用方通过 [EXTRA_EPISODES] 传进来的（详情页手里本来就有，零网络请求）；
     * ② 传进来是空的时候，[loadEpisodeListAsync] 在后台自己拉一次
     *    —— 首页「继续观看」和媒体库直接起播的入口没有列表。
     *
     * 空表 = 「这不是剧集，或者列表没拉到」，此时上下键给提示而不是静默吞掉。
     */
    private var episodes: List<EpisodeRef> = emptyList()

    /** 当前播的是 [episodes] 里的第几个；-1 = 不在列表里（对不上就当第一集处理）。 */
    private var episodeIndex = -1

    /** 后台正在拉剧集列表 —— 用户此时按上下键要看到「正在取…」而不是「没有上一集」。 */
    private var episodesLoading = false

    /**
     * 播放器自己的 TrackSelector，字幕开关要改它的参数（`setTrackTypeDisabled`）。
     *
     * ⚠️ 必须是字段：`onCreate` 里是局部变量，菜单里切字幕时拿不到。
     */
    private var trackSelector: DefaultTrackSelector? = null

    /**
     * 上一次被我们吞掉的键码（-1 = 没有）。
     *
     * 只吞 `ACTION_DOWN` 不吞 `ACTION_UP`，系统会打
     * `InputEventConsistencyVerifier: ACTION_UP but key was not down`，
     * 而且抬起事件会漏给视图树。靠这个字段把成对的抬起也拦下来。
     */
    private var consumedKey = -1

    /** 加载类后台任务（补取 mediaSourceId / 拉剧集列表），和上报线程分开。 */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "play-load") }

    /** 「迟迟没有第一帧」的兜底检查。做成字段才能 removeCallbacks（切集时旧集的必须撤掉）。 */
    private val stallCheck = object : Runnable {
        override fun run() = checkVideoStalled()
    }

    /**
     * 播放 ↔ 暂停。
     *
     * ⚠️ 暂停时**必须**把「已暂停」显示出来。控制栏关掉之后，暂停在屏幕上
     * 什么都不会变（画面本来就是静止的），用户没法区分「暂停了」和「卡住了」，
     * 也判断不出按键到底有没有生效。
     */
    private fun togglePlayPause() {
        val p = player ?: return
        // 播到结尾时按确定 = 从头重播，而不是「暂停一个已经停住的画面」
        if (p.playbackState == com.google.android.exoplayer2.Player.STATE_ENDED) {
            p.seekTo(0)
            p.playWhenReady = true
            return
        }
        p.playWhenReady = !p.playWhenReady
        updatePauseBadge()
    }

    /** 按当前是否在播，切换「已暂停」提示和顶部标题的显隐。 */
    private fun updatePauseBadge() {
        val p = player ?: return
        val paused = p.playWhenReady.not() &&
            p.playbackState != com.google.android.exoplayer2.Player.STATE_ENDED
        tvPause.visibility = if (paused) View.VISIBLE else View.GONE
        if (paused) {
            // 暂停时标题一起露出来（用户这时候正想确认自己看的是哪一集）
            main.removeCallbacks(hideInfo)
            tvInfo.visibility = View.VISIBLE
        } else {
            showInfoBriefly()
        }
    }

    /**
     * 标题露一下再收起来。
     *
     * 起播、切集、恢复播放时调。以前这是靠监听 ExoPlayer 控制栏的可见性做的，
     * 现在控制栏关了，只能自己计时。
     */
    private fun showInfoBriefly() {
        tvInfo.visibility = View.VISIBLE
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, INFO_VISIBLE_MS)
    }


    /** 收起 seek 提示。 */
    private val hideSeekHint = object : Runnable {
        override fun run() {
            tvSeek.visibility = View.GONE
        }
    }

    /**
     * 收起顶部标题。
     *
     * 以前标题是跟 ExoPlayer 控制栏一起显示/隐藏的（监听控制器可见性），
     * 现在控制栏整个关了（见 activity_player.xml 里 `use_controller=false` 的注释），
     * 只能自己计时：起播 / 切集 / 暂停时露 3.5 秒，然后收起来别挡正片。
     */
    private val hideInfo = object : Runnable {
        override fun run() {
            // 暂停时标题一直留着 —— 那是用户按了确定键的结果，不能自己消失
            if (player?.playWhenReady != false) tvInfo.visibility = View.GONE
        }
    }

    /** 是否自然播到结尾（打点时用来区分「看完退出」和「中途退出」）。 */
    private var endedNaturally = false

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
            // capabilityBlocked：已经判定「硬解吃不下」，界面归错误文案独占 ——
            // 这颗定时器自己会把速度/「正在缓冲…」刷回去，光靠 showSpeed() 里的
            // 守卫挡不住（那是另一条路径），所以在这里直接停。
            if (!speedVisible || capabilityBlocked) return
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
    private var playSessionId = UUID.randomUUID().toString().replace("-", "")

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
        hideSystemBars()

        playerView = findViewById(R.id.player_view)
        tvError = findViewById(R.id.tv_error)
        tvInfo = findViewById(R.id.tv_info)
        tvDiag = findViewById(R.id.tv_diag)
        boxSpeed = findViewById(R.id.box_speed)
        tvSpeed = findViewById(R.id.tv_speed)
        tvSpeedHint = findViewById(R.id.tv_speed_hint)
        tvSeek = findViewById(R.id.tv_seek)
        tvPause = findViewById(R.id.tv_pause)

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

        // 每播一个新条目先把快照清干净 —— 否则「上一部片播放失败」的报错
        // 会被当成「这一部片」的现场一起报上去，反馈就白填了。
        PlaybackSnapshot.reset(title, itemId)
        // 性能打点（首帧耗时 / 掉帧 / 重缓冲）从这一刻开始计时。
        // 和 PlaybackSnapshot 分开：前者是「用户点反馈时要带走的现场」，
        // 后者是「每次播放都自动上一条的统计」，两者生命周期不同。
        PlayStats.begin(this, title, itemId)

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

        // 剧集列表：详情页会直接传进来；首页「继续观看」和媒体库直接起播的
        // 入口手里没有，走后台补拉（见 loadEpisodeListAsync）。
        episodes = EpisodeRef.decode(intent.getStringExtra(EXTRA_EPISODES))
        episodeIndex = episodes.indexOfFirst { it.id == itemId }
        if (episodes.isEmpty() && itemId.isNotEmpty()) loadEpisodeListAsync(itemId)

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
        //
        // ⚠️ `setSelectUndeterminedTextLanguage(true)` 不能省：
        // DefaultTrackSelector 默认**不选没有语言标记、也没有 default/forced 标志的字幕轨**。
        // 而绝大多数内嵌字幕（MKV 里的 ASS/SRT）恰恰既没写 language、也没打 default，
        // 结果就是「片子里明明有字幕，播放器一个字都不显示」——
        // 看起来像不支持字幕，其实只是没被选中。
        //
        // ⚠️ 字幕**默认关**（`setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)`）：
        // 很多资源是压好的硬字幕（字直接烧在画面里），这时再把内嵌字幕轨渲染上来，
        // 屏幕上就是两行字、内容还常常不一样。用户在播放页菜单里打开即可。
        // 注意「默认关」和上面的 `setSelectUndeterminedTextLanguage` 不冲突：
        // 后者决定「开了之后选哪条」，前者决定「开不开」。
        val ts = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setSelectUndeterminedTextLanguage(true)
                    .setTrackTypeDisabled(
                        com.google.android.exoplayer2.C.TRACK_TYPE_TEXT,
                        !Session.get(this@PlayerActivity).subtitleEnabled
                    )
            )
        }
        trackSelector = ts

        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(ts)
            // 耳机拔掉自动暂停（电视上少见，但盒子/回音壁接线时会遇到）
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        playerView.player = p

        // 字幕样式：**强制透明底 + 白字黑描边**。
        //
        // 电视上实测过的坑：字幕每行文字后面都垫着一条不透明的黑条。
        // 真凶**不是**字幕文件（别去查 ASS 的 `BackColour`，ExoPlayer 的 SsaDecoder
        // 压根不读颜色，只读位置/对齐）——是**系统的无障碍字幕设置**：
        // `PlayerView` 在构造时调 `SubtitleView.setUserDefaultStyle()`，
        // 读 `CaptioningManager.getUserStyle()`；用户在电视上开过「字幕样式」，
        // 底色是黑的，所有走 SubtitleView 的字幕就全带黑底。
        //
        // 所以修法是**显式 `setStyle`**：给了样式就不再跟系统走。
        // 底色给 TRANSPARENT 才是修黑底的那一句。
        //
        // 描边（EDGE_TYPE_OUTLINE）不能省：透明底之后，白字压在白墙/雪景上会糊成一片，
        // 黑描边是唯一能同时兼顾亮暗画面的办法 —— 这也是电视台字幕的标准做法。
        //
        // `setApplyEmbeddedStyles(false)` 管的是另一件事（字幕文件自带的字号/斜体/颜色），
        // 与黑底无关；关掉它只是让显示更统一。
        //
        // ⚠️ 这几个是只写属性（只有 setter 没有 getter），Kotlin 里**不能**写成
        //    `sv.applyEmbeddedStyles = false`，编译期会报 Unresolved reference。
        //    只能老老实实调方法名。
        // ⚠️ 必须放在 `playerView.player = p` 之后：PlayerView 的构造函数里
        //    已经把系统样式打进去了，先设会被覆盖。
        playerView.subtitleView?.let { sv ->
            sv.setApplyEmbeddedStyles(false)
            sv.setApplyEmbeddedFontSizes(false)
            sv.setStyle(
                com.google.android.exoplayer2.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE,          // 字色
                    android.graphics.Color.TRANSPARENT,    // 底色 ← 修黑底靠这一句
                    android.graphics.Color.TRANSPARENT,    // 窗口色（4.3 上不用）
                    com.google.android.exoplayer2.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK,          // 描边色
                    null                                    // 字体：系统默认
                )
            )
        }

        // 控制栏关掉之后（见 activity_player.xml 的 use_controller=false），
        // 标题、暂停指示都得自己管 —— 原来是挂在控制栏可见性上的。
        p.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                main.post { updatePauseBadge() }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                main.post { updatePauseBadge() }
            }
        })

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
                var audioCodec = ""
                var bitrate = 0
                for (g in tracks.groups) {
                    if (g.type == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO) {
                        v++
                        if (codec.isEmpty() && g.length > 0) {
                            val f = g.getTrackFormat(0)
                            codec = f.sampleMimeType ?: ""
                            size = "${f.width}x${f.height}"
                            bitrate = if (f.bitrate == com.google.android.exoplayer2.Format.NO_VALUE) 0
                            else f.bitrate / 1000
                        }
                    }
                    if (g.type == com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO) {
                        a++
                        if (audioCodec.isEmpty() && g.length > 0) {
                            audioCodec = g.getTrackFormat(0).sampleMimeType ?: ""
                        }
                    }
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

                // 反馈上报要用的现场快照（内存态，进程内一直留着「最近一次」）
                PlaybackSnapshot.videoCodec = codec
                PlaybackSnapshot.audioCodec = audioCodec
                PlaybackSnapshot.bitrateKbps = bitrate
                PlaybackSnapshot.width = tracks.groups.firstOrNull {
                    it.type == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO && it.length > 0
                }?.let { it.getTrackFormat(0).width } ?: 0
                PlaybackSnapshot.height = tracks.groups.firstOrNull {
                    it.type == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO && it.length > 0
                }?.let { it.getTrackFormat(0).height } ?: 0
                // 性能打点也要同一份规格：统计「哪类片子在老机器上起播慢」靠它
                PlayStats.tracks(
                    codec = codec,
                    audio = audioCodec,
                    w = PlaybackSnapshot.width,
                    h = PlaybackSnapshot.height,
                    kbps = bitrate,
                    containerHint = ""
                )
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
                // 这一轨能不能硬解，趁格式刚确定就问清楚 —— 别等用户对着白屏干等 35 秒。
                checkHardwareCapability(format)
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                diag("decoder=$decoderName init=${initializationDurationMs}ms")
                decoderInitialized = true
                PlaybackSnapshot.decoderName = decoderName
                PlayStats.decoder(decoderName)
            }

            // 掉帧是「卡不卡」最硬的证据：硬解扛不住 / 码率超带宽时，
            // ExoPlayer 会悄悄丢帧继续播，用户只看到画面一顿一顿的。
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                PlayStats.droppedFrames(droppedFrames)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                firstFrameRendered = true
                PlaybackSnapshot.firstFrame = true
                // 首帧耗时：从 begin() 到现在。这是整份统计里最值钱的数字 ——
                // 「点了播放要等多久」比任何主观描述都准。
                PlayStats.firstFrame()
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
                // 重缓冲只在「已经出过画之后」才算（见 PlayStats.buffering 的注释）
                PlayStats.buffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY && !stopped) {
                    // 只在第一次 READY 时上报开始，并启动进度心跳
                    reportStart()
                    main.removeCallbacks(progressTick)
                    main.postDelayed(progressTick, PROGRESS_INTERVAL_MS)
                }
                if (state == Player.STATE_ENDED) {
                    endedNaturally = true
                    reportStop()
                    // 播完自动接下一集。放 post 里是为了先让 ENDED 这一帧的
                    // 状态回调全部跑完，再拆播放源 —— 在回调栈里直接
                    // setMediaSource 会让 ExoPlayer 自己重入。
                    main.post { autoAdvance() }
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
                    // ⚠️ `error.message` 只有外层那一句（"Source error" / "Renderer error."），
                    // 真正能诊断的那句在 cause 链里（UnrecognizedInputFormatException 的
                    // "None of the available extractors (...)" / MediaCodec 的 "format_supported=…"）。
                    // 原来只拿 message 去 contains("404")，等于永远匹配不到 —— 2026-11-18 实测踩到。
                    val chain = generateSequence(error as Throwable?) { it.cause }
                        .mapNotNull { it.message }
                        .joinToString(" | ")
                    // HTTP 404 基本只有一个原因：取流 URL 少了 mediaSourceId，
                    // v10 纯文件后端直接拒绝（见 EmbyApi.streamUrl 注释）。
                    // 直接把这个提示打出来，省得下次又误判成「服务器挂了」。
                    val hint = when {
                        chain.contains("404") ->
                            "\n\n取流地址可能缺少 mediaSourceId —— 服务端 v10 对缺这个参数的请求一律 404。"
                        // 硬解能力不够：MStar 的 HEVC 解码器上限是 1080p，
                        // 4K HEVC（Level 5.1 及以上）会在 logcat 里报
                        //   NoSupport [codec.profileLevel, hvc1.1.6.L153.90, video/hevc]
                        //   Format exceeds selected codec's capabilities
                        //   ES_OMX_HEVCDEC Error Code: 1000003
                        // 有时走这条路（抛错），有时静默跳过视频轨只留音频。
                        // 两条路都要给同一句人话，所以 checkVideoStalled() 里也有一份。
                        chain.contains("exceeds selected codec", ignoreCase = true) ||
                        chain.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) ||
                        chain.contains("ERROR(0x80001001)", ignoreCase = true) ->
                            "\n\n这一集的视频轨超出了这台电视的硬解能力（多为 4K / 高位深 HEVC）。\n" +
                            "音频能正常播放，但画面出不来。换 1080p 的片源，或用支持 4K 的设备播放。"
                        // PARSING_CONTAINER_UNSUPPORTED 只有一种常见来路，值得说人话：
                        // 服务端把**光盘镜像**（.iso / BDMV）当媒体文件挂进库里。
                        //
                        // 客户端现在**已内置 ISO 直放**（net/Udf.kt + net/IsoPlayback.kt）：
                        // 会自己进 BDMV/STREAM 找正片、剥掉 m2ts 的 4 字节 ATS 头还原成
                        // 标准 188 字节 TS 再交给 TsExtractor。所以走到这个分支，说明
                        // 不是「客户端不会解」，而是那次解析没成功（盘结构不典型 / 读取中断 /
                        // 加密盘 / 传输层抽风）。让用户先重试一次，再不行才提转码。
                        //
                        // 2026-11-18 实测：emby.example.com《玉簟秋》第 9 集，
                        // 文件 `The.Lament.of.Autumn.2026.S01E09.iso`，chaichai 与本项目报的错一字不差。
                        chain.contains("None of the available extractors", ignoreCase = true) ||
                        chain.contains("UnrecognizedInputFormat", ignoreCase = true) ->
                            "\n\n这个流不是能直接播放的视频容器。多半是服务器上这一集的文件" +
                            "是光盘镜像（.iso / BDMV）—— 客户端会尝试自动解析镜像内部的正片，\n" +
                            "但这一次没成功（结构不典型、读取中断，或盘是加密的）。\n" +
                            "先按「重试播放」再来一次；仍然不行的话，把取流地址用浏览器打开、\n" +
                            "看保存下来的文件名是不是 .iso，是的话只能让服主把这一集转成 mkv/mp4。"
                        else -> ""
                    }
                    tvError.text = "播放失败：${error.errorCodeName}\n$raw$hint"
                    diag("ERROR ${error.errorCodeName}")
                    // 反馈上报要带走的现场：错误码 + 原始消息（含 4.3 那些误导性文案）
                    PlaybackSnapshot.errorCode = error.errorCodeName
                    PlaybackSnapshot.errorMessage = raw.take(600)
                    // 打点里也记一份：服务端按 errorCode 聚合才看得出
                    // 「哪个版本在哪种片源上崩得最多」
                    PlayStats.error(error.errorCodeName, raw.take(400))
                    ErrorLog.record("player:${error.errorCodeName}", raw.take(400))
                    // 已经失败了就别再显示网速，否则会一直跳数字让人误以为还在加载
                    hideSpeed()
                    showDiag()
                }
            }
        })

        // 起播（以及之后每一次切集都走同一条路）。
        prepareEpisode(url, startMs)
    }

    // ── 起播 / 切集 ──────────────────────────────────────────────────────────

    /**
     * 给播放器换一条流并开始播。
     *
     * **首次起播和之后每次切集都走这里**，而不是在 onCreate 里摊平写一遍 ——
     * 切集时要复位的东西太多（见 [resetPlaybackState]），漏一项就是
     * 「切到下一集直接显示上一集的『硬解吃不下』」这种难查的 bug。
     *
     * @param startMs 起播位置；0 = 从头
     */
    private fun prepareEpisode(url: String, startMs: Long) {
        val p = player ?: return
        resetPlaybackState()

        // ⚠️ 每次 createDataSource() 必须返回**新实例**：
        // ExoPlayer 会并发使用多个 DataSource（嗅探 + 正式读取），
        // 而我们的 DataSource 是有状态的（各自持有 socket）。
        //
        // 换成 IsoDataSourceFactory：它会先用一次 8 字节的 Range 判断「这条流装的是不是
        // 光盘镜像（.iso / BDMV）」。不是就原样透传给 EmbyHttpDataSource，行为和以前
        // 一模一样；是的话就在客户端里把 BDMV/STREAM 的正片 m2ts 解出来、
        // 剥掉 192→188 的包壳，当成普通 TS 流喂给 ExoPlayer（见 net/IsoPlayback.kt）。
        //
        // ⚠️ 切集时这个 factory **必须重建**：url 是它的构造参数，
        //    复用会把上一集的镜像信息带过来。
        val isoSteps: (String) -> Unit = { step ->
            main.post { if (!firstFrameRendered) tvSpeedHint.text = "$step…" }
        }
        val dsFactory = IsoDataSourceFactory(this, Session.get(this).token, Uri.parse(url), isoSteps)

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
        // 标题露一下再收 —— 控制栏关了，没人替我们干这事了
        showInfoBriefly()

        // 兜底：15 秒还没出第一帧，才认定「这台机器真的放不出来」。
        // 之前是 onTracksChanged 里一看 isTypeSupported=false 就弹提示，
        // 结果电视上出现「一边说解不了、一边画面正常播」的自相矛盾 ——
        // 老系统的 MediaCodec 不上报 HDR/10bit 能力，判定会假阴性。
        // 现在改成「以有没有第一帧为准」，出画就永不提示。
        //
        // ⚠️ 每集都要重新排：旧集的定时器不撤，会在新集起播 15 秒后
        //    拿新集的现场误判一次（resetPlaybackState 里已 removeCallbacks）。
        main.postDelayed(stallCheck, 15_000L)
    }

    /**
     * 切集 / 重播之前把「上一集留下的判定」全部清掉。
     *
     * 这些字段每一个都是「只判一次」的守卫，不清就会出现：
     * 从 4K HEVC 那集切到 1080p 的集 → `capabilityBlocked` 还是 true →
     * 新集直接显示「这台电视硬解吃不下」并且把视频层摘了，画面永远不出来。
     */
    private fun resetPlaybackState() {
        firstFrameRendered = false
        decoderInitialized = false
        capabilityBlocked = false
        capabilityChecked = false
        videoSupported = true
        videoTrackCount = 0
        videoCodecInfo = ""
        stallRound = 0
        diagShown = false
        synchronized(diagLines) { diagLines.clear() }
        endedNaturally = false
        stopped = false

        main.removeCallbacks(stallCheck)
        main.removeCallbacks(hideSeekHint)
        main.removeCallbacks(hideInfo)
        main.removeCallbacks(progressTick)
        tvSeek.visibility = View.GONE
        tvPause.visibility = View.GONE
        tvDiag.visibility = View.GONE
        tvError.visibility = View.GONE
        hideSpeed()
    }

    /**
     * 上一集 / 下一集。
     *
     * @param delta -1 = 上一集，+1 = 下一集
     *
     * ⚠️ 边界必须**给提示**，不能静默吞键。AGENTS.md 里那条教训：
     *    方向键的 fallback 一定要有去处，吃掉比乱跳更像 bug ——
     *    用户按了没反应，只会以为遥控器坏了或者这个功能根本没做。
     */
    private fun switchEpisode(delta: Int) {
        if (episodes.isEmpty()) {
            toast(
                if (episodesLoading) "正在取剧集列表…"
                else "这不是剧集，没有上一集 / 下一集"
            )
            return
        }
        // ⚠️ 索引没定位到时**绝不能**拿 -1 去算 next：`-1 + (-1) = -2` 越界，
        //    报出来的却是「已经是第一集了」—— 2026-11-19 真机就是这么误导人的：
        //    用户从「最近观看」进来（当前集不在传进来的列表里、或后台还没拉到），
        //    按一下 ↑ 看到「已经是第一集了」，以为「跳回第一集了」。
        //    真正的原因是**压根不知道现在播的是第几集**，两件事必须分开说。
        val idx = resolveEpisodeIndex()
        if (idx < 0) {
            android.util.Log.i(TAG, "切集失败：在 ${episodes.size} 集里定位不到当前集（itemId=${reportItem?.id}）")
            toast(if (episodesLoading) "正在定位当前剧集…" else "定位不到当前是第几集，暂时不能切集")
            return
        }
        val next = idx + delta
        if (next !in episodes.indices) {
            android.util.Log.i(TAG, "切集到头：当前第 ${idx + 1} 集，delta=$delta")
            toast(if (delta < 0) "已经是第一集了" else "已经是最后一集了")
            return
        }
        playEpisodeAt(next, reason = "switch")
    }

    /**
     * 找出「现在播的是列表里第几集」。
     *
     * 三重兜底，因为三个入口给的线索不一样：
     *  1. 详情页 / 媒体库直接传了完整列表 → `onCreate` 里已按 id 对上；
     *  2. 首页「继续观看」只有一个 itemId，后台拉回列表后按 id 对上；
     *  3. 都对不上时（服务端换过 itemId、或拉回来的是另一季），按
     *     「季号 + 集号」再试一次 —— 这是唯一还能用的线索。
     *
     * 返回 -1 表示确实不知道，调用方要给用户明确的提示，别瞎跳。
     */
    private fun resolveEpisodeIndex(): Int {
        if (episodeIndex in episodes.indices) return episodeIndex
        val want = reportItem ?: return -1
        val byId = episodes.indexOfFirst { it.id == want.id }
        if (byId >= 0) {
            episodeIndex = byId
            return byId
        }
        // ⚠️ 季号/集号兜底必须带 `want.indexNumber > 0` 这道闸。
        //    `MediaItem.indexNumber` / `parentIndexNumber` 的默认值都是 **0**，
        //    而接口没给集号时列表里每一集的 indexNumber 也是 0 ——
        //    不加闸的话 `indexOfFirst { 0 == 0 }` 会稳稳命中**第一集**，
        //    于是「按上」看起来就像「跳回第一集」，比定位不到还误导人。
        //
        // ⚠️ 而且**必须唯一命中**才算数。多季剧里每季都从第 1 集开始编号，
        //    `parentIndexNumber` 缺失（=0）时「第 1 集」会有好几条候选，
        //    随便挑一条就是跳到别的季 —— 宁可老实说「找不到」，也不能瞎跳。
        if (want.indexNumber > 0) {
            val candidates = episodes.withIndex().filter {
                it.value.indexNumber == want.indexNumber &&
                    it.value.parentIndexNumber == want.parentIndexNumber
            }
            if (candidates.size == 1) {
                val at = candidates.first().index
                android.util.Log.i(TAG, "按季号/集号定位到第 ${at + 1} 集（id 对不上）")
                episodeIndex = at
                return at
            }
            if (candidates.size > 1) {
                android.util.Log.i(
                    TAG,
                    "按季号/集号定位到 ${candidates.size} 条候选（第 ${want.indexNumber} 集），不猜"
                )
            }
        }
        return -1
    }

    /**
     * 播到结尾时自动接下一集（受 [Session.autoNextEpisode] 控制，默认开）。
     *
     * 只在**自然播完**时被调用；最后一集不动，让用户正常退出。
     *
     * 三个提前退出的分支都留了日志：用户报「没自动下一集」时，
     * 一看 logcat 就能分清是开关关了、还是压根没拿到剧集列表、还是已经到最后一集 ——
     * 否则只能猜。
     */
    private fun autoAdvance() {
        if (!Session.get(this).autoNextEpisode) {
            android.util.Log.i(TAG, "播完收尾：自动下一集是关的，停在这里")
            return
        }
        if (episodes.isEmpty()) {
            android.util.Log.i(TAG, "播完收尾：没有剧集列表（不是剧集，或还在拉），不续播")
            return
        }
        val idx = resolveEpisodeIndex()
        if (idx < 0) {
            android.util.Log.i(TAG, "播完收尾：定位不到当前是第几集，不续播")
            return
        }
        val next = idx + 1
        if (next !in episodes.indices) {
            android.util.Log.i(TAG, "已经是最后一集，不自动续播")
            return
        }
        android.util.Log.i(TAG, "自动续播第 ${next + 1} 集")
        playEpisodeAt(next, reason = "ended")
    }

    /**
     * 播第 [idx] 集。
     *
     * 收尾顺序是有讲究的：**先把旧的一集结掉，再换 [reportItem]** ——
     * `reportStop()` 读的是当前的 `reportItem` / `playSessionId`，
     * 换早了就把旧集的进度记到新集头上。
     *
     * @param reason 打点里的收尾原因（`switch` = 手动切，`ended` = 播完自动接）
     */
    private fun playEpisodeAt(idx: Int, reason: String) {
        val ep = episodes.getOrNull(idx) ?: return
        val a = api ?: return

        // 旧集的进度要写回 Emby，否则「继续观看」永远停在切走前 10 秒那个位置
        reportStop()
        main.removeCallbacks(progressTick)
        // 打点：把这一集当成一次完整播放收尾（首帧 / 掉帧 / 重缓冲都按本集算）
        PlayStats.finish(
            this,
            Session.get(this).feedbackEndpoint,
            player?.currentPosition ?: 0L,
            reason
        )

        episodeIndex = idx
        val title = ep.label()
        tvInfo.text = title

        if (ep.mediaSourceId.isNotEmpty()) {
            startEpisodeStream(ep, ep.mediaSourceId)
            return
        }
        // 列表接口偶尔缺 MediaSources（见 HomeActivity.play 的同款兜底）
        tvSpeedHint.text = "正在获取播放地址…"
        boxSpeed.visibility = View.VISIBLE
        worker.execute {
            val msId = a.itemDetail(ep.id).getOrNull()?.mediaSourceId.orEmpty()
            main.post {
                if (isFinishing || isDestroyed) return@post
                boxSpeed.visibility = View.GONE
                if (msId.isEmpty()) toast("这一集拿不到播放源（mediaSourceId 为空）")
                else startEpisodeStream(ep, msId)
            }
        }
    }

    /** 真正把某一集的流喂给播放器（`mediaSourceId` 已确保非空）。 */
    private fun startEpisodeStream(ep: EpisodeRef, mediaSourceId: String) {
        val a = api ?: return
        val title = ep.label()
        val item = MediaItem(
            id = ep.id,
            name = title,
            type = "Episode",
            isFolder = false,
            mediaSourceId = mediaSourceId
        )
        reportItem = item
        // 每一集都是新的 Emby 播放会话，沿用旧的会让服务端把两集算成一次
        playSessionId = UUID.randomUUID().toString().replace("-", "")
        // 现场快照与打点都按「新的一集」重来
        PlaybackSnapshot.reset(title, ep.id)
        PlayStats.begin(this, title, ep.id)
        android.util.Log.i(TAG, "起播：$title（${ep.id}）从 ${ep.resumeMs}ms")
        prepareEpisode(a.streamUrl(item, mediaSourceId), ep.resumeMs)
    }

    /**
     * 后台把同剧的剧集列表拉回来（只有「没有传列表」的入口会走到）。
     *
     * 为什么要「条目详情 → seriesId → 剧集列表」两步：播放器手里只有 itemId，
     * 而 `MediaItem.seriesId` 只有在列表/详情接口带 `SeriesId` 字段时才有。
     * 两步大约 1~2 秒，正好藏在起播缓冲里，用户察觉不到。
     *
     * 失败一律静默：拉不到就只是「没有切集功能」，正片照播。
     */
    private fun loadEpisodeListAsync(itemId: String) {
        val a = api ?: return
        episodesLoading = true
        worker.execute {
            val sid = a.itemDetail(itemId).getOrNull()?.seriesId.orEmpty()
            val list = if (sid.isEmpty()) emptyList() else a.episodes(sid).getOrNull().orEmpty()
            main.post {
                if (isFinishing || isDestroyed) return@post
                episodesLoading = false
                if (list.isEmpty()) {
                    android.util.Log.i(TAG, "没有剧集列表（不是剧集，或接口没给）")
                    return@post
                }
                episodes = list.map { EpisodeRef.of(it) }
                episodeIndex = episodes.indexOfFirst { it.id == itemId }
                android.util.Log.i(
                    TAG,
                    "剧集列表就绪：${episodes.size} 集，当前第 ${episodeIndex + 1} 集"
                )
            }
        }
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
        // 已经判定「硬解吃不下」时，界面归那条错误文案独占 —— 别再冒出「正在缓冲…」
        if (capabilityBlocked) return
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
        // capabilityBlocked：已经判过「硬解吃不下」并接管了界面，这里不能再刷另一套文案。
        if (firstFrameRendered || capabilityBlocked) return
        stallRound++

        // ---- 第二轮：解码器建起来了，但 20 秒过去仍然一帧都没出 ----
        //
        // 真机上 4K 起播慢也就十几秒；到这会儿还不出帧，基本就是
        // 「解码器能初始化、但每一帧都被硬解拒绝」——ExoPlayer 2.19.1 在这种情况下
        // **不会**走 onPlayerError，视频轨被静默跳过，音频照播 → 黑屏有声。
        // 2026-11-18 实测：4K HEVC L153 的 ISO 就是这样，logcat 只有
        //   W/MediaCodecRenderer: Format exceeds selected codec's capabilities
        //   E/MS_OMX_HEVCDEC: Error Code: 1000003
        if (decoderInitialized) {
            if (stallRound == 1) {
                main.postDelayed(stallCheck, 20_000L)
                return
            }
            showCodecLimit(videoCodecInfo)
            showDiag()
            return
        }

        // ---- 第一轮：解码器根本没建起来 ----
        // 能力不匹配时 MediaCodec 会直接拒绝，这时不用再等。
        if (videoTrackCount > 0) {
            tvError.visibility = View.VISIBLE
            tvError.text = "这台设备放不出这个视频画面\n\n" +
                "编码：$videoCodecInfo\n" +
                "音频能正常播放，但没有画面。\n" +
                "换一个 H.264 版本的片源，或者用支持该编码的设备播放。"
        }
        showDiag()
    }

    /**
     * 解码能力预检：这台机器的硬解有没有可能吃下这条视频轨。
     *
     * 为什么必须提前判：MStar 的 HEVC 解码器**能创建、能 configure**，失败的只是逐帧解码。
     * 这种情况下 ExoPlayer 2.19.1 只在 logcat 打一句
     * `W/MediaCodecRenderer: Format exceeds selected codec's capabilities`，然后照播不误 ——
     * 界面上声音正常、进度条正常走、**画面是白的**（那块是硬件视频层，不是 SurfaceView
     * 的黑色背景，解码器出不来有效帧时它就不受 Surface 控制地白着/灰着）。
     * 2026-11-18 真机：ISO 里的 4K HEVC（`hvc1.1.6.L153.90`，3840×2160）就是这样。
     *
     * 判断用的是 ExoPlayer 自己的 [MediaCodecInfo.isFormatSupported] —— 就是打出那句
     * `NoSupport [codec.profileLevel, ...]` 的同一个函数，这里把它的结论当结果用。
     *
     * ⚠️ 只在**明确判死**时才动手：查询抛异常（老系统上 `DecoderQueryException` 很常见）、
     *    或者一条解码器都查不到（那种情况 ExoPlayer 自己会报错），一律放行。
     *    宁可试一下也不要误伤 —— 这台机器对 HEVC Main10 HDR 是能解的，而 4.3 的
     *    MediaCodec 不上报 10bit 能力，误判方向只会是「假阴性」。
     */
    private fun checkHardwareCapability(format: com.google.android.exoplayer2.Format) {
        if (capabilityChecked || capabilityBlocked || firstFrameRendered) return
        val mime = format.sampleMimeType ?: return
        if (!mime.startsWith("video/")) return
        val infos = try {
            com.google.android.exoplayer2.mediacodec.MediaCodecUtil
                .getDecoderInfos(mime, false, false)
        } catch (e: Exception) {
            diag("cap? ${e.javaClass.simpleName}")
            return
        }
        if (infos.isEmpty()) return
        val supported = infos.any { info ->
            try {
                info.isFormatSupported(format)
            } catch (e: Exception) {
                true
            }
        }
        capabilityChecked = true
        if (supported) {
            diag("cap ok ${infos.first().name}")
            return
        }
        val spec = listOfNotNull(
            format.codecs,
            if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null
        ).joinToString(" / ")
        diag("cap NO ${infos.joinToString { it.name }} $spec")
        android.util.Log.i(TAG, "硬解吃不下这条视频轨：$mime $spec（设备只有 ${infos.joinToString { it.name }}）")
        main.post { showCodecLimit(spec) }
    }

    /**
     * 「这条视频轨这台机器解不了」的最终表现。
     *
     * 关键动作是 [com.google.android.exoplayer2.ExoPlayer.VideoComponent.clearVideoSurface]：
     * 不摘掉视频层的话，屏幕上那块白色/灰色会一直盖着错误文案（硬件 plane 优先于 View 合成，
     * 也不是「黑屏」——它在没有有效帧时既不受 Surface 控制、也不跟窗口背景走）。
     * 摘掉之后窗口背景（`surface_base` 深色）才露出来，文案才看得见。
     */
    private fun showCodecLimit(spec: String) {
        if (firstFrameRendered || capabilityBlocked) return
        capabilityBlocked = true
        videoSupported = false
        hideSpeed()
        try {
            player?.videoComponent?.clearVideoSurface()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clearVideoSurface 失败：${e.javaClass.simpleName}")
        }
        tvError.text = getString(
            R.string.player_video_unsupported,
            if (spec.isEmpty()) videoCodecInfo else spec
        )
        tvError.visibility = View.VISIBLE
    }

    /** 把诊断面板顶到屏幕上。 */
    private fun showDiag() {
        if (diagShown) return
        diagShown = true
        val lines = synchronized(diagLines) { diagLines.toList() }
        tvDiag.text = if (lines.isEmpty()) "（没有任何事件 —— 连解封装都没开始）" else lines.joinToString("\n")
        tvDiag.visibility = View.VISIBLE
    }

    // ---------- 播放页菜单（菜单键） ----------

    /**
     * 播放页菜单：选集 / 字幕 / 诊断。
     *
     * 为什么这三项放在一起：遥控器上能随手按到、又不占屏幕的入口只有菜单键，
     * 而播放时真正想改的就是「换一集」和「字幕开不开」。
     * 诊断排最后 —— 它是给排障用的，正常用户不会碰。
     */
    private fun showPlayerMenu() {
        val items = ArrayList<TvMenu.Item>()

        if (episodes.isEmpty()) {
            items.add(
                TvMenu.Item(
                    "选集",
                    if (episodesLoading) "正在取剧集列表…" else "这不是剧集",
                    enabled = false
                ) {}
            )
        } else {
            val idx = resolveEpisodeIndex()
            val sub = if (idx >= 0) "第 ${idx + 1} 集 / 共 ${episodes.size} 集"
            else "共 ${episodes.size} 集（当前集没对上）"
            items.add(TvMenu.Item("选集", sub) { showEpisodePicker() })
        }

        items.add(TvMenu.Item("字幕", subtitleLabel()) { toggleSubtitle() })

        items.add(
            TvMenu.Item(
                "诊断信息",
                if (tvDiag.visibility == View.VISIBLE) "开：屏幕下方显示状态" else "关：不显示"
            ) { toggleDiag() }
        )

        TvMenu.show(this, "播放", items)
    }

    /** 选集面板：整部剧的集列表，标出正在播的那一集。 */
    private fun showEpisodePicker() {
        if (episodes.isEmpty()) return
        val cur = resolveEpisodeIndex()
        val items = episodes.mapIndexed { i, ep ->
            TvMenu.Item(
                ep.label(),
                when {
                    i == cur -> "正在播"
                    ep.resumeMs > 0 -> "上次看到 ${fmtTime(ep.resumeMs)}"
                    else -> ""
                },
                // 当前这一集点了也没意义（会从头重播），直接禁用，焦点会跳过去
                enabled = i != cur
            ) { playEpisodeAt(i, reason = "menu") }
        }
        // ⚠️ 必须限高：39 集的剧不限高会把面板顶到屏幕外，按钮都看不见。
        TvMenu.show(this, "选集（共 ${episodes.size} 集）", items, maxHeightDp = 420)
    }

    /** 字幕开关的副标题：让人一眼看出「现在开没开」和「这部片子有没有字幕轨」。 */
    private fun subtitleLabel(): String {
        if (!Session.get(this).subtitleEnabled) return "关"
        val n = textTrackCount()
        return if (n == 0) "开（这部片子没有内嵌字幕轨）" else "开（$n 条内嵌字幕轨）"
    }

    /** 当前媒体里一共几条文字轨。 */
    private fun textTrackCount(): Int {
        val p = player ?: return 0
        var n = 0
        for (g in p.currentTracks.groups) {
            if (g.type == com.google.android.exoplayer2.C.TRACK_TYPE_TEXT) n += g.length
        }
        return n
    }

    private fun toggleSubtitle() {
        val on = !Session.get(this).subtitleEnabled
        Session.get(this).subtitleEnabled = on
        val ts = trackSelector
        if (ts != null) {
            // 只改「文字轨是否禁用」这一项，其余参数（含 setSelectUndeterminedTextLanguage）
            // 用 buildUponParameters 继承，别整个重建 —— 重建会把没显式设置的项退回默认值。
            ts.setParameters(
                ts.buildUponParameters().setTrackTypeDisabled(
                    com.google.android.exoplayer2.C.TRACK_TYPE_TEXT, !on)
            )
        }
        android.util.Log.i(TAG, "字幕 ${if (on) "开" else "关"}（内嵌字幕轨 ${textTrackCount()} 条）")
        toast(if (on) "字幕已开启" else "字幕已关闭")
    }

    private fun toggleDiag() {
        if (tvDiag.visibility == View.VISIBLE) {
            tvDiag.visibility = View.GONE
        } else {
            // diagShown 是「已经渲染过」的标记；置回 false 才会用最新的事件重画一遍
            diagShown = false
            showDiag()
        }
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

    /**
     * 遥控器按键先过这里，再交给视图树。
     *
     * ⚠️ 这是本页最关键的一处改动，原因值得写清楚：
     * `onKeyDown` 是**兜底**——只有视图树没人吃这个键，它才会被调到。
     * 而全屏播放时焦点在 `PlayerView` 上，它的控制器会先吃掉方向键
     * （上下左右都被当成「显示控制器 / 移动焦点 / 快进快退」），
     * 于是写在 `onKeyDown` 里的切集逻辑**一次都不会执行**：
     * 按上下毫无反应，日志里连一行都没有 —— 看起来像「功能没做」。
     *
     * 只有 `KEYCODE_MENU` 没被控制器吃，所以「连按三次出诊断」看着是好的，
     * 掩盖了其他键全失效这件事。
     *
     * 修法：在 `dispatchKeyEvent` 里截，它跑在 `super`（窗口 → 视图树）**之前**，
     * 谁先谁后由这里说了算。要用的键全在这里截走，只剩返回键放行
     * （返回退出播放页是系统的活儿，不归我们）。
     *
     * 注：控制栏现在已经整个关掉了（`use_controller=false`），
     * 所以「确定键」也必须由这里接管 —— 否则播放/暂停彻底按不动。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ⚠️ 这一行是给「遥控器上某个键按了没反应」留的**唯一**排查入口。
        //
        // 真机（乐视 S50 Air）和模拟器不是同一套遥控器：国产电视的「菜单」键
        // 未必发 KEYCODE_MENU(82)，有的发 SETTINGS(176) / INFO(165) / 书签(174)，
        // 有的干脆被 Launcher 或 TV Service 吃掉、根本到不了 Activity。
        // 光看「按了没反应」永远分不清是「键没到」「键码不认识」还是「逻辑没跑」——
        // 而这一行日志能把三种情况一次分开：
        //   完全没有 key 日志  → 键被系统截走了，客户端再怎么改也没用；
        //   有日志但 keyCode 陌生 → 加到 DIAG_KEYS 里；
        //   有日志且 keyCode 是 82 → 逻辑问题。
        //
        // 播放页按键频率很低（一次操作几下），常驻不影响性能。
        if (event.action == KeyEvent.ACTION_DOWN) {
            android.util.Log.i(TAG, "key ${event.keyCode}(${keyName(event.keyCode)}) repeat=${event.repeatCount}")
            // 遥控器上按到一个我们完全不认识的键时，**在屏幕上**报一句键码。
            //
            // 为什么非要在屏幕上说：这台电视没有 uiautomator、`dumpsys` 也没权限，
            // 用户看不到 logcat —— 「按了没反应」这句话传回来，我这边什么也判断不了。
            // 有了这行 toast，用户按一下遥控器上那个「菜单」键，屏幕上直接显示
            // 「未识别按键 176」，照着加进 DIAG_KEYS 就行。
            //
            // 只对**不认识**的键提示，方向键/确定键这些常用键不打扰。
            if (event.repeatCount == 0 && event.keyCode !in KNOWN_KEYS) {
                toast("未识别按键 ${event.keyCode}")
            }
        }
        val mine = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            event.keyCode in DIAG_KEYS
        if (!mine || player == null) return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (handlePlayerKey(event)) {
                    consumedKey = event.keyCode
                    return true
                }
            }
            // 抬起也要吞掉：只吞按下会让系统报
            // `InputEventConsistencyVerifier: ACTION_UP but key was not down`，
            // 而且那个键的抬起会漏给视图树，可能触发一次多余的点击。
            KeyEvent.ACTION_UP -> {
                if (consumedKey == event.keyCode) {
                    consumedKey = -1
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 键码 → 名字，只给日志用。
     *
     * ⚠️ 不能用 `KeyEvent.keyCodeToString()`：那是 **API 19** 才加的，
     *    这台电视是 4.3（API 18），一调就是 `NoSuchMethodError`。
     *    也没法 `when` 穷举（`KEYCODE_*` 常量在 4.3 的 stub 里有一堆是后来才加的，
     *    引用到不存在的常量同样会崩），所以只列这台机器上真会遇到的十几个。
     */
    private fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        KeyEvent.KEYCODE_DPAD_CENTER -> "CENTER"
        KeyEvent.KEYCODE_ENTER -> "ENTER"
        KeyEvent.KEYCODE_MENU -> "MENU"
        KeyEvent.KEYCODE_BACK -> "BACK"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY_PAUSE"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
        KeyEvent.KEYCODE_MEDIA_STOP -> "STOP"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "FFWD"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "REW"
        KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSET"
        KeyEvent.KEYCODE_VOLUME_UP -> "VOL_UP"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "VOL_DOWN"
        KeyEvent.KEYCODE_SETTINGS -> "SETTINGS"
        KeyEvent.KEYCODE_INFO -> "INFO"
        KeyEvent.KEYCODE_BOOKMARK -> "BOOKMARK"
        KeyEvent.KEYCODE_PROG_RED -> "RED"
        KeyEvent.KEYCODE_PROG_GREEN -> "GREEN"
        KeyEvent.KEYCODE_PROG_YELLOW -> "YELLOW"
        KeyEvent.KEYCODE_PROG_BLUE -> "BLUE"
        KeyEvent.KEYCODE_TV -> "TV"
        KeyEvent.KEYCODE_GUIDE -> "GUIDE"
        else -> "?"
    }

    /** 返回 true 表示这个键归我们管，不再往下传。 */
    private fun handlePlayerKey(event: KeyEvent): Boolean {
        val p = player ?: return false
        when (event.keyCode) {
            // 确定键 / 播放暂停键 = 播放 ↔ 暂停。
            //
            // 以前这是 PlayerView 自带控制栏的活儿。控制栏关掉之后
            // （见 activity_player.xml 的 use_controller=false），
            // 没有这一条就彻底按不动了 —— 而且**屏幕上不会有任何异常**，
            // 用户只会觉得「按了没反应」。所以暂停时必须有 tvPause 那个「已暂停」提示。
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event.repeatCount > 0) return true
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // 长按连发时别一次跳十秒一跳十秒地堆上去 —— 按住不放就按 5 秒小步走
                val step = if (event.repeatCount > 0) SEEK_STEP_SMALL_MS else SEEK_STEP_MS
                p.seekTo((p.currentPosition - step).coerceAtLeast(0))
                showSeekHint(-step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val step = if (event.repeatCount > 0) SEEK_STEP_SMALL_MS else SEEK_STEP_MS
                val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                p.seekTo((p.currentPosition + step).coerceAtMost(dur))
                showSeekHint(step)
                return true
            }
            // 上/下 = 上一集 / 下一集。
            // 为什么不用「长按」或组合键：遥控器上真正一按就到、又没人用的键只剩上下，
            // 而它在全屏播放里本来就没有别的语义。
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.repeatCount == 0) switchEpisode(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0) switchEpisode(1)
                return true
            }
            // 菜单键 = 播放页菜单（选集 / 字幕 / 诊断）。
            //
            // 早期版本是「按一下弹出诊断面板」，后来改成「连按三次」，
            // 现在按用户要求恢复成**按一下就出菜单** —— 播放页里真正需要
            // 随手够到的操作是「换集」和「开字幕」，诊断只是排在最后的一项。
            //
            // ⚠️ 这里吃的是 [DIAG_KEYS] 一整组键，不只是 KEYCODE_MENU：
            //    国产电视遥控器上那个「菜单」键的键码五花八门（见 DIAG_KEYS 注释），
            //    乐视这台发的是哪一组就在那几条里。
            in DIAG_KEYS -> {
                // 长按菜单键系统会一直发 repeat，别把一次长按算成连开好几次菜单
                if (event.repeatCount > 0) return true
                showPlayerMenu()
                return true
            }
        }
        return false
    }

    /**
     * 按左右键拖动进度时给一行位置提示。
     *
     * 为什么需要：ExoPlayer 自带的控制栏只在按键后短暂出现、进度条又细又小，
     * 用户按了十次右键根本不知道自己跳到哪了 —— 只能靠猜。
     * 这里直接给「▶ 08:42 / 45:10」，一眼就知道当前位置和总长。
     *
     * @param deltaMs 本次跳的毫秒数（只用来决定箭头方向）
     */
    private fun showSeekHint(deltaMs: Long) {
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = if (p.duration > 0) p.duration else 0L
        val arrow = if (deltaMs < 0) "◀" else "▶"
        tvSeek.text = if (dur > 0) "$arrow  ${fmtTime(pos)} / ${fmtTime(dur)}" else "$arrow  ${fmtTime(pos)}"
        tvSeek.visibility = View.VISIBLE
        main.removeCallbacks(hideSeekHint)
        main.postDelayed(hideSeekHint, SEEK_HINT_MS)
    }

    /** 毫秒 → `mm:ss` / `h:mm:ss`。手算不用 `String.format`（4.3 上省一个 Formatter）。 */
    private fun fmtTime(ms: Long): String {
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        val mm = if (m < 10 && h > 0) "0$m" else "$m"
        val ss = if (s < 10) "0$s" else "$s"
        return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
    }

    /** 一行短提示。电视上没有 Toast 的替代品，只能用系统的（会自动消失）。 */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
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
        // 性能打点：退出这一刻才知道总共看了多久、掉了几帧。
        // 失败/网络断都在 PlayStats 内部静默处理，不会影响退出流程。
        PlayStats.finish(
            this,
            Session.get(this).feedbackEndpoint,
            player?.currentPosition ?: 0L,
            when {
                PlaybackSnapshot.errorCode.isNotEmpty() -> "error"
                endedNaturally -> "ended"
                else -> "exit"
            }
        )
        reportStop()
        main.removeCallbacks(progressTick)
        main.removeCallbacks(speedTick)
        main.removeCallbacks(stallCheck)
        main.removeCallbacks(hideSeekHint)
        main.removeCallbacks(hideInfo)
        reporter.shutdown()
        // 正在补取播放地址 / 拉剧集列表的后台线程：直接 shutdownNow，
        // 它们的回调里都有 isFinishing 守卫，打断也不会崩。
        try { worker.shutdownNow() } catch (ignored: Throwable) {}
        try { player?.release() } catch (ignored: Throwable) {}
        player = null
        setResult(RESULT_OK)
        super.onDestroy()
    }

    /**
     * 全屏。
     *
     * ⚠️ 4.3 上 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 不可靠（它是 4.4 的），
     * 只能靠这三面旗子 + 每次窗口重新拿到焦点时再收一次 —— 系统栏会偷偷回来。
     */
    private fun hideSystemBars() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LOW_PROFILE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    companion object {
        /** logcat 过滤用：`adb logcat -s PlayAct:*`。电视没法截图，只能靠日志认路。 */
        private const val TAG = "PlayAct"

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_MEDIA_SOURCE_ID = "media_source_id"
        const val EXTRA_START_MS = "start_ms"

        /**
         * 同剧的剧集列表（[work.luxiaoxiao.myembyhub.net.EpisodeRef.encode] 出的 JSON）。
         *
         * 详情页手里本来就有这个列表，直接传进来能省掉两次网络请求；
         * 不传也不影响播放 —— 播放器会自己后台补拉（见 loadEpisodeListAsync）。
         */
        const val EXTRA_EPISODES = "episodes"

        /** 进度上报间隔。太密会在 4.3 上抢带宽/CPU，太疏则退出时丢进度。 */
        private const val PROGRESS_INTERVAL_MS = 10_000L

        /** 网速采样间隔：0.5 秒。够跟手，也不会让老机器一直重绘。 */
        private const val SPEED_INTERVAL_MS = 500L

        /** 按一下左右键跳 10 秒。 */
        private const val SEEK_STEP_MS = 10_000L

        /** 按住不放时改跳 5 秒 —— 连发速度是 10 次/秒，按 10 秒跳会瞬间跑完整部片。 */
        private const val SEEK_STEP_SMALL_MS = 5_000L

        /** seek 位置提示停留时长。 */
        private const val SEEK_HINT_MS = 1_200L

        /**
         * 顶部标题露多久。
         *
         * 控制栏关掉之后标题不再跟着控制栏走，得自己计时。
         * 3.5 秒：够看清是哪一集，又不至于一直压在画面上。
         */
        private const val INFO_VISIBLE_MS = 3_500L

        /**
         * 菜单键（播放页菜单）。
         *
         * 只认 `KEYCODE_MENU` 是不够的 —— 2026-11-19 真机上「按遥控器菜单键没反应」，
         * 而模拟器上同一个键好好的。国产电视（乐视 S50 Air 这类）遥控器的「菜单」
         * 键码并不统一，常见还有 SETTINGS / INFO / 书签 / 红绿黄蓝这几个彩键。
         * 这些键在播放页本来就没有别的用途，全收进来没有副作用。
         *
         * ⚠️ 加键之前先看 logcat 里的 `key NNN(NAME)` 日志（`dispatchKeyEvent` 打的）：
         *    完全没有这条日志 → 键被系统/Launcher 截走了，加多少键码都没用；
         *    有日志 → 照着 NNN 加进来。
         */
        private val DIAG_KEYS = setOf(
            KeyEvent.KEYCODE_MENU,        // 82
            KeyEvent.KEYCODE_SETTINGS,    // 176
            KeyEvent.KEYCODE_INFO,        // 165
            KeyEvent.KEYCODE_BOOKMARK,    // 174
            KeyEvent.KEYCODE_PROG_RED,    // 183
            KeyEvent.KEYCODE_PROG_GREEN,  // 184
            KeyEvent.KEYCODE_PROG_YELLOW, // 185
            KeyEvent.KEYCODE_PROG_BLUE,   // 186
            KeyEvent.KEYCODE_TV,          // 170
            KeyEvent.KEYCODE_GUIDE        // 172
        )

        /**
         * 播放页已经接管、不需要「未识别按键」提示的键。
         *
         * 只要不在这个集合里、又按在播放页上，就会在屏幕上弹出键码 ——
         * 这是给遥控器认键用的，别把常用的几个塞进来，否则正常看片会一直弹。
         */
        private val KNOWN_KEYS = DIAG_KEYS + setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER
        )
    }
}
