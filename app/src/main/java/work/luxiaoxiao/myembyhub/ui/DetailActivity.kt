package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.EmbyApi
import work.luxiaoxiao.myembyhub.net.ImageLoader
import work.luxiaoxiao.myembyhub.net.MediaItem
import work.luxiaoxiao.myembyhub.net.Session
import work.luxiaoxiao.myembyhub.net.friendlyError
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 详情页：海报 + 标题 + 硬信息 + 简介 + 播放按钮 + （剧集才有）选集。
 *
 * 为什么单独开一个 Activity，而不是像以前那样弹个对话框：
 * 电视上从海报墙点进一部剧，用户要判断的是「这剧讲什么、我该从第几集看」——
 * 这需要整屏宽度放简介和一整排选集。塞进 560dp 宽的弹窗里，简介只能露三行，
 * 选集还得在弹窗里再套一层滚动，遥控器按起来像在走迷宫。
 *
 * 数据分三次到，按「先能看、后补全」的顺序：
 *   1. 标题 / 海报 / 类型 —— 调用方已经把 `MediaItem` 传进来了，立刻显示；
 *   2. 简介 / 分级 / 评分 —— 一次 `itemDetail`（列表接口不带这些字段）；
 *   3. 选集 —— 剧集才发，`/Shows/{id}/Episodes` 一次拿全。
 * 三步互不阻塞：第 2 步慢的时候，用户已经能按「播放」了。
 */
class DetailActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "detail-worker").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private lateinit var session: Session
    private lateinit var api: EmbyApi

    private lateinit var imgBackdrop: ImageView
    private lateinit var imgPoster: ImageView
    private lateinit var tvPosterPh: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvGenres: TextView
    private lateinit var tvOverview: TextView
    private lateinit var tvTech: TextView
    private lateinit var tvLoading: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnBack: TextView
    private lateinit var pageEpisodes: LinearLayout
    private lateinit var epRow: RecyclerView

    private var itemId = ""
    private var itemName = ""
    private var itemType = ""
    private var seriesId = ""

    /** 点「播放」时要播的那一条。剧集是「该看的那一集」，电影就是自己。 */
    private var playTarget: MediaItem? = null
    private var playStartMs = 0L

    /** 详情本体。留着是为了在选集回来之后重算一遍信息行（「共 N 集」要等它）。 */
    private var detail: MediaItem? = null

    private val episodes = ArrayList<MediaItem>()
    private val epAdapter = EpisodeAdapter()
    private var redirectingToLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        hideSystemBars()

        itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        itemName = intent.getStringExtra(EXTRA_ITEM_NAME).orEmpty()
        itemType = intent.getStringExtra(EXTRA_ITEM_TYPE).orEmpty()
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        if (itemId.isEmpty()) {
            finish()
            return
        }

        imgBackdrop = findViewById(R.id.img_backdrop)
        imgPoster = findViewById(R.id.img_poster)
        tvPosterPh = findViewById(R.id.tv_poster_ph)
        tvTitle = findViewById(R.id.tv_title)
        tvMeta = findViewById(R.id.tv_meta)
        tvGenres = findViewById(R.id.tv_genres)
        tvOverview = findViewById(R.id.tv_overview)
        tvTech = findViewById(R.id.tv_tech)
        tvLoading = findViewById(R.id.tv_loading)
        btnPlay = findViewById(R.id.btn_play)
        btnBack = findViewById(R.id.btn_back)
        pageEpisodes = findViewById(R.id.page_episodes)
        epRow = findViewById(R.id.row_episodes)

        session = Session.get(this)
        if (!session.isLoggedIn) {
            finish()
            return
        }
        api = EmbyApi(this, session.server, session.token, session.userId)

        // 先拿调用方给的信息把骨架填上 —— 用户点进来的一瞬间就该看到东西，
        // 而不是先盯 1~2 秒的黑屏等 itemDetail 回来。
        tvTitle.text = itemName
        tvPosterPh.text = itemName.take(1)
        tvOverview.text = getString(R.string.detail_loading)
        bindButtons()
        loadPoster()
        loadBackdrop()

        // 一进来焦点就落在「播放」上：电视用户点开一部片，十有八九是要接着看，
        // 不该让人先按一下方向键才找得到按钮。
        btnPlay.post { if (!btnPlay.isFocused) btnPlay.requestFocus() }

        worker.execute { loadDetail() }
    }

    // ── 数据 ────────────────────────────────────────────────────────────────

    private fun loadDetail() {
        val r = api.itemDetail(itemId)
        val d = r.getOrNull()
        if (d == null) {
            main.post {
                val err = r.exceptionOrNull()
                if (handleAuthExpiredIfNeeded(err)) return@post
                tvOverview.text = getString(
                    R.string.detail_failed,
                    friendlyError(err ?: IllegalStateException("空响应"))
                )
            }
            return
        }
        main.post { bindDetail(d) }
        // 剧集才拉选集；电影/单集不用发这个请求。
        if (d.type == "Series") {
            val er = api.episodes(d.id)
            val list = er.getOrNull()
            // 「该看的那一集」单独问一次 —— Emby 的 NextUp 已经把
            // 「没看完的优先 → 下一集 → 第一集」这套逻辑算好了；拿不到直接用列表第一集，省掉一次请求。
            val next = api.nextUp(d.id).getOrNull() ?: list?.firstOrNull()
            main.post { bindEpisodes(list.orEmpty(), next) }
        }
    }

    private fun bindDetail(d: MediaItem) {
        detail = d
        tvTitle.text = if (d.name.isNotEmpty()) d.name else itemName
        tvPosterPh.text = tvTitle.text.take(1)
        tvMeta.text = metaText(d)

        val extra = ArrayList<String>()
        if (d.genres.isNotEmpty()) extra.add(d.genres)
        if (d.communityRating > 0) extra.add(String.format("%.1f 分", d.communityRating))
        if (d.officialRating.isNotEmpty()) extra.add(d.officialRating)
        tvGenres.text = extra.joinToString("  ·  ")
        tvGenres.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE

        // ⚠️ Android 4.3 上 `ellipsize="end"` 配 `maxLines="6"` **不会真的加省略号**
        // （多行省略是老系统没做全的功能，只有单行 reliable），
        // 简介会被硬切在第 6 行半个字上，看着像排版坏了。自己截断更稳。
        val ov = if (d.overview.isNotEmpty()) d.overview else getString(R.string.detail_no_overview)
        tvOverview.text = if (ov.length > OVERVIEW_MAX) ov.take(OVERVIEW_MAX) + "…" else ov

        val tech = ArrayList<String>()
        if (d.container.isNotEmpty()) tech.add(d.container.uppercase())
        if (d.codec.isNotEmpty()) tech.add(d.codec.uppercase())
        if (d.width > 0 && d.height > 0) tech.add("${d.width}×${d.height}")
        if (d.size > 0) tech.add(MediaItem.formatSize(d.size))
        tvTech.text = tech.joinToString("  ·  ")
        tvTech.visibility = if (tech.isEmpty()) View.GONE else View.VISIBLE

        // 电影：按钮直接标成「播放 / 继续播放 12:34」。
        if (d.type != "Series") {
            playTarget = d
            playStartMs = d.resumeMs
            btnPlay.text = if (playStartMs > 0) {
                getString(R.string.detail_resume, MediaItem.formatDuration(playStartMs))
            } else {
                getString(R.string.detail_play)
            }
            btnPlay.isEnabled = true
        }
    }

    private fun bindEpisodes(list: List<MediaItem>, next: MediaItem?) {
        episodes.clear()
        episodes.addAll(list)
        epAdapter.notifyDataSetChanged()
        // 信息行里的「共 N 集」要等选集回来才算得准，重算一次。
        detail?.let { tvMeta.text = metaText(it) }

        if (episodes.isEmpty()) {
            pageEpisodes.visibility = View.GONE
            btnPlay.text = getString(R.string.detail_no_episode)
            btnPlay.isEnabled = false
            return
        }
        pageEpisodes.visibility = View.VISIBLE

        val target = next ?: episodes.first()
        playTarget = target
        playStartMs = target.resumeMs
        val n = target.indexNumber
        btnPlay.text = when {
            playStartMs > 0 && n > 0 -> getString(R.string.detail_resume_episode, n)
            n > 0 -> getString(R.string.detail_play_episode, n)
            playStartMs > 0 -> getString(R.string.detail_resume, MediaItem.formatDuration(playStartMs))
            else -> getString(R.string.detail_play)
        }
        btnPlay.isEnabled = true

        // 把「该看的那一集」标成选中态（浅底），并把这一集滚进视野 ——
        // 一部剧可能有 40 集，默认停在最左边会让用户以为「从第一集开始」。
        val idx = episodes.indexOfFirst { it.id == target.id }
        if (idx > 0) {
            epRow.post {
                val lm = epRow.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(idx, 0)
            }
        }
    }

    /**
     * 硬信息行：「2026 · 剧集 · 45 分钟 · 已看 12:34」。
     *
     * 时长用 `formatDuration` 而不是「45 min」：这台电视的用户群看的是中文界面，
     * 混着英文单位会显得像没翻译完。
     */
    private fun metaText(item: MediaItem): String {
        val parts = ArrayList<String>()
        if (item.productionYear > 0) parts.add(item.productionYear.toString())
        parts.add(
            when (item.type) {
                "Series" -> "剧集"
                "Episode" -> "单集"
                "Movie" -> "电影"
                else -> item.type
            }
        )
        if (item.type == "Series" && episodes.isNotEmpty()) parts.add("共 ${episodes.size} 集")
        if (item.runtimeTicks > 0) parts.add(MediaItem.formatDuration(item.runtimeTicks / 10_000))
        if (item.played) {
            parts.add("已看完")
        } else {
            val p = item.progressText()
            if (p.isNotEmpty()) parts.add(p)
        }
        return parts.joinToString(" · ")
    }

    // ── 图片 ────────────────────────────────────────────────────────────────

    private fun loadPoster() {
        val pid = if (itemType == "Episode" && seriesId.isNotEmpty()) seriesId else itemId
        ImageLoader.get(this).load(pid, "Primary", POSTER_WIDTH) { bmp ->
            main.post { showImage(imgPoster, tvPosterPh, bmp) }
        }
    }

    /**
     * 背景：优先用横版剧照（Backdrop）。
     *
     * ⚠️ 这个库里**很多条目没有 Backdrop**（实测「史努比特辑」就没有，只有 Primary），
     * 直接跳过的话整页背景是纯黑的，看着像没加载完。所以拿不到剧照时
     * 退回用海报放大铺满 —— 同一张图，至少颜色和构图能透出来。
     */
    private fun loadBackdrop() {
        val pid = if (itemType == "Episode" && seriesId.isNotEmpty()) seriesId else itemId
        ImageLoader.get(this).loadBackdrop(pid, BACKDROP_WIDTH) { bmp ->
            main.post {
                if (bmp != null) {
                    imgBackdrop.setImageBitmap(bmp)
                    imgBackdrop.visibility = View.VISIBLE
                } else {
                    loadFallbackBackdrop(pid)
                }
            }
        }
    }

    /** 没有剧照就复用 400px 海报顶替（alpha 调低，既命中缓存又省掉 4.9MB 内存）。 */
    private fun loadFallbackBackdrop(posterId: String) {
        ImageLoader.get(this).load(posterId, "Primary", POSTER_WIDTH) { bmp ->
            main.post {
                if (bmp == null) return@post
                imgBackdrop.setImageBitmap(bmp)
                imgBackdrop.alpha = 0.28f
                imgBackdrop.visibility = View.VISIBLE
            }
        }
    }

    /** 拿到图就藏占位字；拿不到就留着占位字（总比一块空白强）。 */
    private fun showImage(img: ImageView, placeholder: TextView, bmp: Bitmap?) {
        if (bmp == null) return
        img.setImageBitmap(bmp)
        img.visibility = View.VISIBLE
        placeholder.visibility = View.GONE
    }

    // ── 按钮与焦点 ──────────────────────────────────────────────────────────

    private fun bindButtons() {
        btnPlay.setOnClickListener { startPlayback() }
        btnBack.setOnClickListener { finish() }

        // 主按钮往下 → 选集第一集；没有选集就交给框架（会落到「返回」）
        btnPlay.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (episodes.isNotEmpty()) {
                    focusEpisode(0)
                    true
                } else false
            } else false
        }

        btnBack.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (episodes.isNotEmpty()) {
                    focusEpisode(0)
                    true
                } else false
            } else false
        }

        epRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        epRow.adapter = epAdapter
        // ⚠️ 别加 setHasFixedSize(true)：`row_episodes` 是 height=wrap_content，
        //    高度取决于有几集 → 内容变了尺寸就可能变，那个标志是在骗框架。
        //    （lint 的 InvalidSetHasFixedSize 会直接让 assembleRelease 失败。）
        epRow.itemAnimator = null
    }

    /**
     * 把焦点塞进第 [position] 集。
     *
     * RecyclerView 在「焦点从外面塞进来」时不会自己滚 —— 目标项在屏幕外时
     * 必须先把这一项滚到起点再 `requestFocus`，否则焦点会落到
     * 一个不可见的子项上，用户看不到高亮（跟首页卡片行同一个坑）。
     */
    private fun focusEpisode(position: Int) {
        if (episodes.isEmpty()) return
        val lm = epRow.layoutManager as? LinearLayoutManager ?: return
        val visible = lm.findViewByPosition(position)
        if (visible != null) {
            visible.requestFocus()
            return
        }
        lm.scrollToPositionWithOffset(position, 0)
        epRow.post {
            lm.findViewByPosition(position)?.requestFocus()
        }
    }

    private fun startPlayback() {
        val target = playTarget ?: return
        if (target.mediaSourceId.isNotEmpty()) {
            startPlayer(target, target.mediaSourceId, playStartMs)
            return
        }
        // 列表接口偶尔缺 MediaSources，取流前补一次详情。
        tvLoading.visibility = View.VISIBLE
        worker.execute {
            val r = api.itemDetail(target.id)
            main.post {
                tvLoading.visibility = View.GONE
                val msId = r.getOrNull()?.mediaSourceId.orEmpty()
                if (msId.isEmpty()) {
                    if (handleAuthExpiredIfNeeded(r.exceptionOrNull())) return@post
                    toast(friendlyError(r.exceptionOrNull() ?: IllegalStateException("没有可播放的媒体源")))
                } else {
                    startPlayer(target, msId, playStartMs)
                }
            }
        }
    }

    private fun startPlayer(item: MediaItem, mediaSourceId: String, startMs: Long) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra(PlayerActivity.EXTRA_URL, api.streamUrl(item, mediaSourceId))
        i.putExtra(PlayerActivity.EXTRA_TITLE, item.displayTitle())
        i.putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
        val sid = if (item.seriesId.isNotEmpty()) item.seriesId else seriesId
        if (sid.isNotEmpty()) i.putExtra(PlayerActivity.EXTRA_SERIES_ID, sid)
        i.putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE_ID, mediaSourceId)
        i.putExtra(PlayerActivity.EXTRA_START_MS, startMs)
        // 详情页手里已经有整季的剧集列表，直接传给播放器 ——
        // 不传的话播放器还要「详情 → seriesId → 剧集列表」多跑两次请求，
        // 而这两次正好落在用户第一次按上下键的时候，会看到「正在取剧集列表…」。
        //
        // 只在条目本身是「集」的时候传：点开一部电影也把空列表传过去没意义，
        // 而传了空字符串和没传是一样的（播放器会当成「没有列表」）。
        if (episodes.isNotEmpty()) {
            i.putExtra(
                PlayerActivity.EXTRA_EPISODES,
                work.luxiaoxiao.myembyhub.net.EpisodeRef.encode(
                    episodes.map { work.luxiaoxiao.myembyhub.net.EpisodeRef.of(it) }
                )
            )
        }
        startActivityForResult(i, REQ_PLAY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 播完回来：进度变了，「该看的那一集」和「继续播放 12:34」都得重算，并通知首页刷新。
        if (requestCode == REQ_PLAY) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PLAYED, true))
            worker.execute { loadDetail() }
        }
    }

    // ── 选集列表 ────────────────────────────────────────────────────────────

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        override fun getItemCount(): Int = episodes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
            // 根就是个 TextView（item_episode_chip.xml），直接强转省掉一层包装
            val v = layoutInflater.inflate(R.layout.item_episode_chip, parent, false) as TextView
            return EpisodeHolder(v)
        }

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val ep = episodes[position]
            holder.tv.text = if (ep.indexNumber > 0) getString(R.string.detail_episode_label, ep.indexNumber)
            else ep.name
            // 「该看的那一集」用 selected 标出来；已看完的不做区分 ——
            // 遥控器界面里再加第三种视觉状态，用户反而分不清焦点在哪。
            holder.tv.isSelected = playTarget != null && ep.id == playTarget!!.id
            holder.tv.setOnClickListener { playEpisode(ep) }
            holder.tv.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    btnPlay.requestFocus()
                    true
                } else false
            }
        }
    }

    private inner class EpisodeHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private fun playEpisode(ep: MediaItem) {
        playTarget = ep
        playStartMs = ep.resumeMs
        startPlayback()
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────

    private fun hideSystemBars() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LOW_PROFILE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun handleAuthExpiredIfNeeded(e: Throwable?): Boolean {
        if (!work.luxiaoxiao.myembyhub.net.isAuthExpired(e)) return false
        if (redirectingToLogin || isFinishing || isDestroyed) return true
        redirectingToLogin = true
        work.luxiaoxiao.myembyhub.net.EmbyHttpDataSource.clearIdlePool()
        ImageLoader.get(this).clearMemCache()
        session.clear()
        toast("登录已过期，请重新登录")
        startActivity(HomeActivity.loginIntent(this, "登录已过期，请重新登录"))
        finish()
        return true
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ImageLoader.get(this).trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ImageLoader.get(this).clearMemCache()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM_ID = "detail_item_id"
        const val EXTRA_ITEM_NAME = "detail_item_name"
        const val EXTRA_ITEM_TYPE = "detail_item_type"
        const val EXTRA_SERIES_ID = "detail_series_id"
        const val EXTRA_PLAYED = "detail_played"

        private const val REQ_PLAY = 3001
        private const val POSTER_WIDTH = 400
        private const val BACKDROP_WIDTH = 800

        /** 简介最多留多少个字（多行 ellipsize 在老系统上不可靠，见 bindDetail）。 */
        private const val OVERVIEW_MAX = 220

        /** 从别处打开详情页（省得每个调用方都自己拼 Intent）。 */
        fun intent(activity: Activity, item: MediaItem): Intent {
            return Intent(activity, DetailActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, item.id)
                putExtra(EXTRA_ITEM_NAME, item.displayTitle())
                putExtra(EXTRA_ITEM_TYPE, item.type)
                putExtra(EXTRA_SERIES_ID, item.seriesId)
            }
        }
    }
}
