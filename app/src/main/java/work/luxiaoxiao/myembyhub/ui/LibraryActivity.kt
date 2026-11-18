package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.EmbyApi
import work.luxiaoxiao.myembyhub.net.ImageLoader
import work.luxiaoxiao.myembyhub.net.MediaItem
import work.luxiaoxiao.myembyhub.net.PhoneControlServer
import work.luxiaoxiao.myembyhub.net.QrUtil
import work.luxiaoxiao.myembyhub.net.Session
import work.luxiaoxiao.myembyhub.net.SortOption
import work.luxiaoxiao.myembyhub.net.UpdateManager
import java.util.concurrent.Executors

/**
 * 主界面：左侧媒体库 + 右侧内容。
 *
 * ## 遥控器导航（没有 androidx.tv，只能自实现）
 *
 * - 上下：同列内移动（RecyclerView 默认行为）
 * - 左右：**跨列切换**（左列 ⇄ 右列）
 * - OK：下钻（剧 → 季 → 集）或直接播放
 * - 返回：回上一层
 * - 菜单键 / 右上角「菜单」按钮：呼出菜单
 *
 * 跨列切换靠 `setNextFocusRightId` / `setNextFocusLeftId`
 * 在 item 绑定时动态设置（XML 里写不了 RecyclerView 内部的焦点链）。
 */
class LibraryActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var api: EmbyApi
    private lateinit var rvLeft: RecyclerView
    private lateinit var rvRight: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var pbLoading: ProgressBar

    /** 左侧：媒体库。 */
    private val libraries = ArrayList<MediaItem>()
    /** 右侧：当前层的条目。 */
    private val current = ArrayList<MediaItem>()

    /** 下钻栈：记录每一层的标题与 parentId，「返回」时弹出。 */
    private val path = ArrayList<Layer>()

    /** 当前排序。默认按首播/上映时间倒序（新片在前）。 */
    private var sortBy = SortOption.SORT_PREMIERE_DATE
    private var sortDesc = true

    /** 搜索模式下隐藏左侧。 */
    private var searchMode = false

    /** 首页传来的深链 / 搜索意图只处理一次（Activity 重建时 intent 里的 extra 还在）。 */
    private var deepLinkConsumed = false

    private var phoneServer: PhoneControlServer? = null
    private var qrDialog: Dialog? = null

    private class Layer(val title: String, val parentId: String, val type: String = "")

    /** 左侧列表的第一项：虚拟的「最近观看」入口，不是真正的媒体库。 */
    private val resumeEntry = MediaItem(
        id = RESUME_ID,
        name = "最近观看",
        type = "Resume",
        isFolder = true
    )

    private val leftAdapter = SimpleAdapter(SIDE_LEFT) { item ->
        if (searchMode) return@SimpleAdapter
        if (item.id == RESUME_ID) openResume() else openLibrary(item)
    }

    private val rightAdapter = SimpleAdapter(SIDE_RIGHT) { item ->
        when {
            // 剧和电影先看详情（标题 / 简介 / 播放按钮），别一上来就播
            item.type == "Series" || item.type == "Movie" -> showDetail(item)
            item.isPlayable -> play(item)
            else -> drillInto(item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        // 首页是全屏的，这里不收系统栏的话，从首页切过来会「跳」一下：
        // 顶部状态栏和底部导航条突然冒出来，内容整体下移 25dp。
        hideSystemBars()

        rvLeft = findViewById(R.id.rv_left)
        rvRight = findViewById(R.id.rv_right)
        tvTitle = findViewById(R.id.tv_title)
        tvHint = findViewById(R.id.tv_hint)
        pbLoading = findViewById(R.id.pb_loading)

        val session = Session.get(this)
        api = EmbyApi(this, session.server, session.token, session.userId)

        rvLeft.layoutManager = LinearLayoutManager(this)
        rvRight.layoutManager = LinearLayoutManager(this)
        rvLeft.adapter = leftAdapter
        rvRight.adapter = rightAdapter

        // ---------- RecyclerView 性能设置 ----------
        // 条目高度固定（item_media.xml 里海报 78dp + 固定 padding）→
        // 告诉 RecyclerView 不用每次重新测量，省掉一轮 layout。
        rvLeft.setHasFixedSize(true)
        rvRight.setHasFixedSize(true)
        // 多缓存两屏：电视的遥控器滚动是「整屏跳」，缓存不足会频繁 onCreateViewHolder
        // （每次 inflate 一个 item_media 在 1GHz 上是实打实的开销）。
        rvLeft.setItemViewCacheSize(12)
        rvRight.setItemViewCacheSize(12)
        // 左栏切换选中项时，**不**走 notifyItemChanged（见 SimpleAdapter.setSelected），
        // 所以也不会触发 ItemAnimator 的「淡入淡出」——在电视上就是一闪。
        // 顺带关掉动画，左栏行数少、变化轻，关了最省事也最稳。
        rvLeft.itemAnimator = null
        // 两列共用一个 ViewHolder 池，回收更充分。
        // ⚠️ **但必须靠 viewType 把两列分开**（见 SimpleAdapter.getItemViewType）：
        //    池是按 viewType 分桶的，两列都塞进 0 号桶的话，
        //    右栏的 item_media（带海报）holder 会被左栏回收走，
        //    左栏就渲染出带封面的卡片，还白白发一堆图片请求。
        rvRight.setRecycledViewPool(rvLeft.recycledViewPool)

        // ---------- 左栏滚动时，焦点跟着滚动走 ----------
        //
        // ⚠️ **没有这一段，左栏会被「拽回」之前点的那个 tab。**
        // RecyclerView 的布局器（LinearLayoutManager.updateAnchorFromChildren）
        // 在每一轮 layout 时会拿「当前有焦点的那个 child」当锚点，
        // 把列表重新对齐到它身上（`getFocusedChild()` → `assignFromViewAndKeepVisibleRect`）。
        // 于是：点某个 tab（焦点在它身上）→ 加载期间把左栏滚走 →
        // 焦点还留在那个已经被滚出屏幕的 tab 上 →
        // 下一轮 layout（切库、刷新、改选中态……）就把左栏拉回那个 tab。
        //
        // 修法：手指/触摸板滚动时（DRAGGING），如果焦点那一项已经滚出可视区，
        // 就把焦点交给当前最上面那一项 —— 焦点永远落在看得见的地方，
        // 锚点也就永远在屏幕内，不会再把列表拽回去。
        //
        // 只在 DRAGGING（触摸滚动）时做：遥控器方向键滚动本来就会带着焦点走，
        // 这时候再抢焦点会跟框架打架。
        var railDragging = false
        rvLeft.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                railDragging = newState == RecyclerView.SCROLL_STATE_DRAGGING
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!railDragging || dy == 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val focused = rv.findFocus() ?: return
                // 焦点那一项还在可视区 → 不用管（遥控器滚动走的就是这条路）
                if (focused.top < rv.height && focused.bottom > 0) return
                val pos = lm.findFirstVisibleItemPosition()
                if (pos == RecyclerView.NO_POSITION) return
                lm.findViewByPosition(pos)?.requestFocus()
            }
        })

        // 标题栏：搜索 + 菜单
        findViewById<Button>(R.id.btn_search).setOnClickListener { showSearchDialog() }
        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMainMenu() }

        // 左右跨列的焦点切换（focus 链之外的兜底）
        rvLeft.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                focusFirst(rvRight); true
            } else false
        }
        rvRight.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                focusFirst(rvLeft); true
            } else false
        }

        loadLibraries()
    }

    /** 标题旁的转圈：只在真正等网络时出现。 */
    private fun setLoading(on: Boolean) {
        if (!::pbLoading.isInitialized) return
        pbLoading.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** 内容换新后的淡入：避免整屏「啪」一下替换，切 tab 时体感更连贯。 */
    private fun fadeIn(rv: RecyclerView) {
        rv.animate().cancel()
        rv.alpha = 0f
        rv.animate().alpha(1f).setDuration(160).start()
    }

    private fun loadLibraries() {
        tvTitle.text = getString(R.string.loading)
        setLoading(true)
        worker.execute {
            val result = api.libraries()
            main.post {
                setLoading(false)
                result.onSuccess { libs ->
                    libraries.clear()
                    libraries.addAll(libs)
                    // 第一项固定是「最近观看」（Emby 的 Resume 列表）
                    val leftItems = ArrayList<MediaItem>(libs.size + 1)
                    leftItems.add(resumeEntry)
                    leftItems.addAll(libs)
                    leftAdapter.submit(leftItems)
                    // 首页深链 / 搜索优先：直接从首页点进来的条目不该先闪一下「最近观看」
                    if (applyDeepLink()) {
                        // 已经在 applyDeepLink 里处理完了
                    } else if (libs.isEmpty()) {
                        tvTitle.text = getString(R.string.empty)
                    } else {
                        openResume()
                    }
                }.onFailure { e ->
                    tvTitle.text = "加载失败：${work.luxiaoxiao.myembyhub.net.friendlyError(e)}"
                }
            }
        }
    }

    /**
     * 处理首页传来的深链 / 搜索意图。
     *
     * @return true 表示已经接管了首屏（调用方不要再自动打开「最近观看」）
     */
    private fun applyDeepLink(): Boolean {
        if (deepLinkConsumed) return false
        deepLinkConsumed = true

        val parentId = intent.getStringExtra(EXTRA_PARENT_ID).orEmpty()
        if (parentId.isNotEmpty()) {
            val name = intent.getStringExtra(EXTRA_PARENT_NAME).orEmpty()
            path.clear()
            path.add(Layer(name, parentId, ""))
            loadLevel(parentId, name)
            return true
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false)) {
            showSearchDialog()
            return true
        }
        return false
    }

    /** 加载某一级的内容。 */
    private fun loadLevel(parentId: String, title: String) {
        // 「季」这一层的子项是「集」，只能按集号升序 —— 菜单里的排序对集没意义，
        // 用首播时间倒序会变成 30、26、25 这种乱序。
        val episodes = path.lastOrNull()?.type == "Season"
        val sortKey = if (episodes) SortOption.SORT_INDEX_NUMBER else sortBy
        val desc = if (episodes) false else sortDesc
        // 标题立刻切成目标层，进度靠转圈表示，不要退回「加载中…」让标题闪一下
        tvTitle.text = title
        tvHint.text = ""
        setLoading(true)
        worker.execute {
            // 媒体库这一层直接给海报墙：库里每个影片外面套了同名文件夹，
            // 非递归列出来只有「电影」「1080p电影」几个文件夹，得让用户再点一层才看到片，
            // 电视上这一步纯属白按。再往下的层（季/集/子目录）仍然非递归。
            val atLibraryRoot = path.size == 1
            val result = if (atLibraryRoot) {
                val flat = api.items(
                    parentId,
                    recursive = true,
                    sortBy = sortKey,
                    descending = desc,
                    includeItemTypes = LIBRARY_ITEM_TYPES
                )
                // 播放列表 / 未分类这类虚拟库递归可能一个都没有 → 退回分层列
                if (flat.getOrNull().isNullOrEmpty()) {
                    api.items(parentId, recursive = false, sortBy = sortKey, descending = desc)
                } else {
                    flat
                }
            } else {
                api.items(parentId, recursive = false, sortBy = sortKey, descending = desc)
            }
            main.post {
                setLoading(false)
                result.onSuccess { items ->
                    current.clear()
                    current.addAll(items)
                    rightAdapter.submit(items)
                    fadeIn(rvRight)
                    tvTitle.text = title
                    tvHint.text = if (items.isEmpty()) getString(R.string.empty) else ""
                    if (items.isNotEmpty()) focusFirst(rvRight)
                }.onFailure { e ->
                    tvTitle.text = "加载失败：${work.luxiaoxiao.myembyhub.net.friendlyError(e)}"
                }
            }
        }
    }

    /** 重新加载当前层（排序改变后调用）。 */
    private fun reloadCurrent() {
        if (searchMode) {
            // 搜索结果重新搜一次
            return
        }
        if (path.isEmpty()) {
            loadLibraries()
        } else {
            val layer = path[path.size - 1]
            if (layer.parentId == RESUME_ID) loadResume() else loadLevel(layer.parentId, layer.title)
        }
    }

    /** 「最近观看」：Emby 的 Resume 列表，条目自带续播位置。 */
    private fun loadResume() {
        tvTitle.text = "最近观看"
        tvHint.text = ""
        setLoading(true)
        worker.execute {
            val result = api.resume()
            main.post {
                setLoading(false)
                result.onSuccess { items ->
                    current.clear()
                    current.addAll(items)
                    rightAdapter.submit(items)
                    fadeIn(rvRight)
                    tvTitle.text = "最近观看"
                    tvHint.text = if (items.isEmpty()) "没有未看完的" else ""
                    focusFirst(if (items.isNotEmpty()) rvRight else rvLeft)
                }.onFailure { e ->
                    tvTitle.text = "加载失败：${work.luxiaoxiao.myembyhub.net.friendlyError(e)}"
                }
            }
        }
    }

    /**
     * 开始播放。
     *
     * ⚠️ **`mediaSourceId` 不能空**：v10 取流后端对没有这个参数的请求直接回 404
     * （2026-11-05 实测，见 [EmbyApi.streamUrl] 注释）。
     * 列表接口理论上都带 `MediaSources`，但个别条目（尤其某些聚合视图）会缺，
     * 所以这里兜一层：空了就单独查一次条目详情补上，实在拿不到才硬着头皮播
     * ——那样至少报错信息还是「HTTP 404」，能一眼看出是缺参数。
     */
    private fun play(item: MediaItem, startMs: Long = item.resumeMs) {
        if (item.mediaSourceId.isNotEmpty()) {
            startPlayer(item, item.mediaSourceId, startMs)
            return
        }
        tvHint.text = "正在获取播放地址…"
        worker.execute {
            val resolved = api.itemDetail(item.id).getOrNull()?.mediaSourceId.orEmpty()
            main.post {
                tvHint.text = ""
                if (resolved.isEmpty()) {
                    toast("这个条目拿不到播放源（mediaSourceId 为空）")
                    return@post
                }
                startPlayer(item, resolved, startMs)
            }
        }
    }

    private fun startPlayer(item: MediaItem, mediaSourceId: String, startMs: Long) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, api.streamUrl(item, mediaSourceId))
            putExtra(PlayerActivity.EXTRA_TITLE, item.displayTitle())
            putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
            putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE_ID, mediaSourceId)
            putExtra(PlayerActivity.EXTRA_START_MS, startMs)
        }
        startActivityForResult(intent, REQ_PLAY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 播完回来刷新一次：进度、「已看」标记、「继续观看」列表都会变
        if (requestCode == REQ_PLAY) reloadCurrent()
        // 详情页里也可能直接播了一集，回来同样要刷
        if (requestCode == REQ_DETAIL) reloadCurrent()
    }

    /** 进入下一层（剧 → 季 → 集）。 */
    private fun drillInto(item: MediaItem) {
        path.add(Layer(item.name, item.id, item.type))
        tvTitle.text = item.name
        loadLevel(item.id, item.name)
    }

    /** 打开某个媒体库（左侧点击）。 */
    private fun openLibrary(item: MediaItem) {
        path.clear()
        path.add(Layer(item.name, item.id, item.type))
        tvTitle.text = item.name
        // 左栏高亮跟着内容走：当前打开的是哪一个库
        leftAdapter.setSelected(item.id)
        loadLevel(item.id, item.name)
    }

    /** 打开「最近观看」。启动时默认走这里。 */
    private fun openResume() {
        path.clear()
        path.add(Layer("最近观看", RESUME_ID, "Resume"))
        tvTitle.text = "最近观看"
        leftAdapter.setSelected(RESUME_ID)
        loadResume()
    }

    /** 把焦点放到某个列表的第一项。 */
    private fun focusFirst(rv: RecyclerView) {
        // 列表刚 submit 完时可能还没布局（childCount 为 0，或拿到的是马上要被移除的旧 child），
        // 这时 requestFocus 会失败 / 落到 RecyclerView 自己身上（看不见焦点框）→ 重试几次
        var tries = 0
        val attempt = object : Runnable {
            override fun run() {
                val child = if (rv.childCount > 0) rv.getChildAt(0) else null
                val ok = child != null && child.requestFocus()
                if (!ok && tries++ < 8) {
                    rv.postDelayed(this, 100)
                } else if (!ok) {
                    rv.requestFocus()
                }
            }
        }
        rv.post(attempt)
    }

    // ==================== 菜单 ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMainMenu()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchMode) {
                exitSearch()
                return true
            }
            if (path.size > 1) {
                // 回上一层
                path.removeAt(path.size - 1)
                val layer = path[path.size - 1]
                tvTitle.text = layer.title
                loadLevel(layer.parentId, layer.title)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showMainMenu() {
        // 「季」这一层列的是集，固定按集号升序，排序菜单在这里没有意义 → 置灰
        val episodesLayer = path.lastOrNull()?.type == "Season"
        val items = ArrayList<TvMenu.Item>()
        items.add(
            TvMenu.Item(
                "排序方式",
                if (episodesLayer) "集固定按集号升序"
                else SortOption.labelOf(sortBy) + (if (sortDesc) " ↓" else " ↑"),
                enabled = !episodesLayer
            ) { showSortDialog() }
        )
        items.add(TvMenu.Item("在线更新", "检查新版本") { checkUpdate() })
        items.add(TvMenu.Item("服务器信息", "") { showServerInfo() })
        items.add(TvMenu.Item("刷新", "") { reloadCurrent() })
        items.add(TvMenu.Item("退出登录", "") { confirmLogout() })
        TvMenu.show(this, "菜单", items)
    }

    private fun showSortDialog() {
        // 用「选中不关闭」的菜单：排序字段和升降序往往要连改两次，
        // 点一下就关掉只能反复开菜单。这里改完按「返回」关。
        TvMenu.showPersistent(this, "排序方式", provider = {
            val items = ArrayList<TvMenu.Item>()
            for (opt in SortOption.ALL) {
                val mark = if (opt.key == sortBy) "✓ " else "   "
                items.add(TvMenu.Item("$mark${opt.label}") {
                    sortBy = opt.key
                    reloadCurrent()
                })
            }
            items.add(TvMenu.Item(if (sortDesc) "✓ 降序（新的在前）" else "   降序（新的在前）") {
                sortDesc = true
                reloadCurrent()
            })
            items.add(TvMenu.Item(if (!sortDesc) "✓ 升序（旧的在前）" else "   升序（旧的在前）") {
                sortDesc = false
                reloadCurrent()
            })
            items
        })
    }

    /**
     * 详情弹窗：标题 / 简介 / 播放按钮。
     *
     * 播放按钮按「看没看过」自动变：
     * - 电影：没看过 →「播放」，看过一半 →「继续播放 12:34」
     * - 剧集：问服务器 `NextUp` 拿「该看的那一集」——没看过就是第 1 集，
     *   看过一半就是那一集（Emby 自己会算，不用客户端记状态）
     */
    private fun showDetail(item: MediaItem) {
        // 详情页现在是独立的一整页（DetailActivity），不再是弹窗：
        // 弹窗里塞不下简介 + 选集，电视上按起来像走迷宫。
        // 这个函数保留原来的名字，是因为下面几处（左右栏点击）都还叫它。
        startActivityForResult(DetailActivity.intent(this, item), REQ_DETAIL)
    }

    private fun showServerInfo() {
        worker.execute {
            val info = api.systemInfo()
            main.post {
                val token = Session.get(this).token
                val msg = StringBuilder()
                msg.append("服务器：").append(api.server).append('\n')
                msg.append(info.getOrNull() ?: ("连接失败：" + (info.exceptionOrNull()?.message ?: "")))
                    .append('\n')
                msg.append("Token：").append(if (token.isEmpty()) "无" else token.take(6) + "…（长度 ${token.length}）")
                    .append('\n')
                msg.append("客户端版本：").append(UpdateManager(this).currentVersionName())
                    .append('\n')
                // 更新来源也放进来：镜像站时通时不通，出问题时能一眼看出它要去哪
                msg.append("更新来源：").append(Session.get(this).updateManifest)
                TvDialog.message(this, "服务器信息", msg.toString())
            }
        }
    }

    private fun confirmLogout() {
        TvDialog.confirm(
            this,
            "退出登录",
            "会清除本机保存的账号和令牌，下次需要重新登录。",
            confirmText = "退出"
        ) {
            Session.get(this).clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // ==================== 在线更新 ====================

    private fun checkUpdate() {
        // 检查 → 确认 → 下载三段都收进 UpdateUi 了：首页那边也要用同一套
        UpdateUi.check(this, worker, main, ::toast)
    }

    // ==================== 搜索 ====================

    private fun showSearchDialog() {
        val items = listOf(
            TvMenu.Item("手机搜索（扫码输入关键词）", "推荐：电视没有软键盘") { startPhoneSearch() },
            TvMenu.Item("在电视上输入", "需要电视有输入法") { showTvSearchInput() }
        )
        TvMenu.show(this, "搜索", items)
    }

    /** 电视端直接输入（如果有 IME）。 */
    private fun showTvSearchInput() {
        val et = EditText(this).apply {
            hint = "输入片名或关键词"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            // 系统那条青蓝色下划线在暖色界面里太跳，换成自己的暖色细线
            setBackgroundResource(R.drawable.bg_dialog_input)
        }
        val pad = LinearLayout(this).apply {
            setPadding(40, 20, 40, 0)
            addView(et, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dlg = TvDialog.show(
            this,
            "搜索",
            pad,
            buttons = listOf(
                TvDialog.Btn("取消"),
                TvDialog.Btn("搜索", primary = true) {
                    val term = et.text.toString().trim()
                    if (term.isNotEmpty()) runSearch(term)
                }
            ),
            initialFocus = et
        )
        dlg.setOnShowListener { et.requestFocus() }
    }

    /** 手机扫码搜索。 */
    private fun startPhoneSearch() {
        // 同上：服务可能已经在 10 分钟上限自己关掉了，那时 `!= null` 判断会骗人，
        // 弹出来的二维码指向一个没人监听的端口
        if (phoneServer?.isRunning != true) {
            phoneServer = PhoneControlServer(
                this,
                mode = PhoneControlServer.MODE_SEARCH,
                onLogin = { _, _, _, _ -> },
                onSearch = { term ->
                    main.post {
                        runSearch(term)
                        toast("已收到搜索：$term")
                    }
                },
                onFail = { msg -> main.post { toast(msg) } }
            )
            phoneServer?.start()
        }
        showQrDialog(PhoneControlServer.MODE_SEARCH)
    }

    private fun showQrDialog(mode: String) {
        qrDialog?.dismiss()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qr, null)
        val img = view.findViewById<ImageView>(R.id.img_qr)
        val tv = view.findViewById<TextView>(R.id.tv_qr_url)
        val dlg = TvDialog.show(
            this,
            if (mode == PhoneControlServer.MODE_SEARCH) "手机搜索" else "手机登录",
            view,
            buttons = listOf(
                TvDialog.Btn("关闭", primary = true) {
                    phoneServer?.stop()
                    phoneServer = null
                }
            )
        )
        qrDialog = dlg

        worker.execute {
            val url = QrUtil.selfUrl(this, PhoneControlServer.PORT, "/?mode=$mode")
            val bmp = if (url.isEmpty()) null else QrUtil.encode(url, 640)
            main.post {
                tv.text = if (url.isEmpty()) "拿不到电视的局域网 IP" else url
                if (bmp != null) img.setImageBitmap(bmp) else img.setImageDrawable(null)
            }
        }
    }

    private fun runSearch(term: String) {
        searchMode = true
        path.clear()
        tvTitle.text = "搜索：$term"
        tvHint.text = ""
        setLoading(true)
        worker.execute {
            val result = api.search(term)
            main.post {
                setLoading(false)
                result.onSuccess { items ->
                    current.clear()
                    current.addAll(items)
                    rightAdapter.submit(items)
                    fadeIn(rvRight)
                    tvHint.text = if (items.isEmpty()) "没有找到相关内容" else "${items.size} 个结果"
                    focusFirst(rvRight)
                }.onFailure { e ->
                    tvTitle.text = "搜索失败：${e.message}"
                }
            }
        }
    }

    private fun exitSearch() {
        searchMode = false
        if (path.isEmpty()) {
            loadLibraries()
        } else {
            val layer = path[path.size - 1]
            tvTitle.text = layer.title
            loadLevel(layer.parentId, layer.title)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 弹过对话框、从播放器回来之后，系统栏会偷偷回来，这里再收一次
        if (hasFocus) hideSystemBars()
    }

    /**
     * 全屏（隐藏状态栏 + 底部导航条）。
     *
     * 不能用 IMMERSIVE_STICKY —— 那是 API 19 才有的常量，这台电视是 4.3。
     * 只能用这套老标志位，并且要在 onWindowFocusChanged 里反复收，
     * 因为系统在任何一次「窗口焦点变化」后都会把栏还回来。
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

    override fun onDestroy() {
        phoneServer?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * 极简 RecyclerView 适配器：左列显示名称，右列显示名称 + 副标题。
     */
    inner class SimpleAdapter(
        private val side: Int,
        private val onClick: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<SimpleAdapter.Holder>() {

        private var items: List<MediaItem> = emptyList()

        /**
         * 左栏「当前选中」的条目 id（只有左栏用）。
         *
         * 焦点 ≠ 选中：焦点框只说明遥控器走到哪儿，
         * 选中才说明「右边显示的内容来自哪一项」——两者要能同时存在。
         */
        private var selectedId: String? = null

        /**
         * 切换左栏选中项。
         *
         * ⚠️ **不要用 `notifyItemChanged` 改选中态！**
         * 那会让左栏重新 layout，而 `LinearLayoutManager` 在 layout 时
         * 会拿「当前焦点项」当锚点重算滚动位置（`findReferenceChild` → `getFocusedChild`）——
         * 用户正在滚动左栏时，这一轮 layout 就会把列表拽回焦点所在的那一行，
         * 观感上就是「点完 tab 加载中，滚动 tab 列会被拉回之前点的那个 tab」。
         *
         * 改成直接改**可见行**的样式：离屏的行等它滚进来时 `onBindViewHolder` 自然会刷。
         * 整个过程不触发 layout，左栏滚动位置一寸不动。
         */
        fun setSelected(id: String?) {
            if (selectedId == id) return
            val old = selectedId
            selectedId = id
            if (side != SIDE_LEFT) return
            refreshLeftVisual(old)
            refreshLeftVisual(id)
        }

        /** 直接刷新左栏某一项的选中样式（不走 adapter 通知，不触发 layout）。 */
        private fun refreshLeftVisual(id: String?) {
            if (id == null) return
            val pos = items.indexOfFirst { it.id == id }
            if (pos < 0) return
            val holder = rvLeft.findViewHolderForAdapterPosition(pos) as? Holder ?: return
            applyLeftVisual(holder, items[pos])
        }

        /** 把「当前选中」的视觉状态套到某个 holder 上。 */
        private fun applyLeftVisual(holder: Holder, item: MediaItem) {
            val sel = item.id == selectedId
            holder.itemView.isSelected = sel
            holder.indicator?.visibility = if (sel) View.VISIBLE else View.GONE
            holder.title.setTextColor(
                if (sel) ContextCompat.getColor(this@LibraryActivity, R.color.content_accent)
                else ContextCompat.getColor(this@LibraryActivity, R.color.content_primary)
            )
        }

        init {
            // 稳定 id：条目 id 唯一且不变，RecyclerView 才能正确复用
            // （否则每次 submit 都整屏重绑，海报全部重新请求一遍）。
            setHasStableIds(true)
        }

        /**
         * 换数据。
         *
         * 走 DiffUtil 而不是 `notifyDataSetChanged()`：
         * 后者会让 RecyclerView 把所有可见条目全部重新绑定 + 重新测量，
         * 剧集列表 200 条时在电视上能明显看到一顿。
         * DiffUtil 只重绑真正变化的那几条。
         */
        fun submit(list: List<MediaItem>) {
            val old = items
            if (old === list) return
            items = list
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize(): Int = old.size
                    override fun getNewListSize(): Int = list.size
                    override fun areItemsTheSame(o: Int, n: Int): Boolean =
                        old[o].id == list[n].id
                    override fun areContentsTheSame(o: Int, n: Int): Boolean =
                        old[o] == list[n]
                }
            )
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemId(position: Int): Long =
            items[position].id.hashCode().toLong()

        /**
         * viewType 按「列」区分（0=左栏，1=右栏）。
         *
         * ⚠️ **这是必须的，不是可选优化。**
         * 左右两列的布局根本不同：左栏（类别 tab）是 `item_rail` 纯文字，
         * 右栏是 `item_media` 带海报。而 RecyclerView 默认所有 item 的 viewType 都是 0，
         * `RecycledViewPool` 又是**按 viewType 分桶**的 ——
         * 两列共用一个池（`rvRight.setRecycledViewPool(rvLeft.recycledViewPool)`）
         * 且都塞进 0 号桶，右栏的 `item_media` holder 就会被左栏回收走：
         * 左栏于是渲染出**带封面的卡片**，还白白发一堆图片请求
         * （4.3 电视上这些图纯属浪费带宽和内存）。
         *
         * 用 `side` 当 viewType，两个桶天然分开，池还能继续共享。
         */
        override fun getItemViewType(position: Int): Int = side

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            // 左栏是导航（纯文字），右栏才是带海报的内容行
            val layout = if (side == SIDE_LEFT) R.layout.item_rail else R.layout.item_media
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items.getOrNull(position) ?: return
            // 先登记这一格现在装的是谁，下面取图回调要靠它判断自己是不是过期结果
            holder.itemId = item.id
            holder.title.text = item.displayTitle()
            // ⚠️ 只算一次：subtitle() 里要拼字符串 + 走 String.format，
            //    之前在判断 visibility 时又调了一遍，滚动时白烧一倍 CPU。
            val sub = item.subtitle()
            holder.subtitle.text = sub
            holder.subtitle.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE

            // 海报：剧/季/电影用条目自己的 Primary。
            // **单集没有自己的海报**（取图会 404），用所属剧的 —— 所以要看 SeriesId。
            // 先取消这个 holder 上一次的请求：holder 是复用的，
            // 旧请求晚到的回调会把上一条目的海报贴到这一条上（错位 bug）。
            val poster = holder.poster
            if (side == SIDE_LEFT) {
                // 左栏 = 类别 tab：**纯文字，不读封面**。
                // 这里再挡一道保险：万一 holder 是从共用池里捡来的，
                // 也绝不能让它去拉图（海报会占掉 72dp，行高和排版全乱）。
                holder.imageRequest?.cancel()
                holder.imageRequest = null
                poster?.let {
                    it.setImageDrawable(null)
                    it.visibility = View.GONE
                }

                // 「当前选中」：左侧暖色竖条 + 标题变暖色 + 浅底色。
                // 焦点框是另一个维度（遥控器走到哪儿），可以同时出现。
                applyLeftVisual(holder, item)
            } else if (poster != null) {
                holder.imageRequest?.cancel()
                holder.imageRequest = null
                poster.setImageDrawable(null)
                poster.visibility = View.INVISIBLE
                // 单集没有自己的海报 → 用所属剧的；
                // 其余条目（Movie / Folder / Series / Season…）一律用自身 id。
                // ⚠️ 别只按 "Movie" 判断：有的服务器把电影组织成 Folder，
                //    类型是 "Folder" 而不是 "Movie"，按类型白名单会把它们漏掉。
                val posterId = if (item.type == "Episode") item.seriesId else item.id
                if (posterId.isNotEmpty()) {
                    holder.imageRequest =
                        ImageLoader.get(this@LibraryActivity).load(posterId, "Primary", POSTER_WIDTH) { bmp ->
                            // 回调里再确认一次：等它回来时 holder 可能已经绑了别的条目
                            if (holder.itemId == item.id && bmp != null) {
                                poster.setImageBitmap(bmp)
                                poster.visibility = View.VISIBLE
                            }
                        }
                }
            }

            // 看过一半的条目在海报下面显示进度条（「继续观看」列表里最直观）
            holder.progress?.let { pb ->
                if (item.positionTicks > 0 && item.runtimeTicks > 0) {
                    pb.progress = ((item.positionTicks * 100) / item.runtimeTicks).toInt().coerceIn(0, 100)
                    pb.visibility = View.VISIBLE
                } else {
                    pb.visibility = View.GONE
                }
            }

            // 跨列焦点：左右切换到另一列
            if (side == SIDE_LEFT) {
                holder.itemView.setNextFocusRightId(R.id.rv_right)
                holder.itemView.setNextFocusLeftId(View.NO_ID)
            } else {
                holder.itemView.setNextFocusLeftId(R.id.rv_left)
                holder.itemView.setNextFocusRightId(View.NO_ID)
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun onViewRecycled(holder: Holder) {
            super.onViewRecycled(holder)
            // 滚出屏幕就取消，别让看不见的海报继续占用网络和线程池
            holder.imageRequest?.cancel()
            holder.imageRequest = null
            holder.itemId = null
            // ⚠️ 清掉「当前选中」的样式再放回池子：
            //    否则这个 holder 会被别的条目复用，带着上一个条目的暖色竖条/标题色，
            //    左栏就会同时出现好几个高亮 tab。
            if (side == SIDE_LEFT) {
                holder.itemView.isSelected = false
                holder.indicator?.visibility = View.GONE
                holder.title.setTextColor(
                    ContextCompat.getColor(this@LibraryActivity, R.color.content_primary))
            }
        }

        /**
         * holder 挂回屏幕时重刷左栏视觉。
         *
         * ⚠️ **这一步不能省。**
         * RecyclerView 有「视图缓存」（`setItemViewCacheSize`）：
         * 条目滚出屏幕后 holder 不销毁，只是进缓存；再滚回来时，
         * **只要 stable id 相同就直接复用，连 `onBindViewHolder` 都不再调用**
         * —— 于是 holder 上残留的「选中」样式（`isSelected` + 暖色标题）被原样带回来，
         * 左栏就出现「好几个 tab 同时高亮」。
         *
         * `onViewAttachedToWindow` 无论 holder 是新建、从池子里捞的、还是从缓存复用的，
         * **一定会被调用**，所以在这里按当前状态重刷一遍最保险。
         */
        override fun onViewAttachedToWindow(holder: Holder) {
            super.onViewAttachedToWindow(holder)
            if (side != SIDE_LEFT) return
            val pos = holder.bindingAdapterPosition
            val byPos = if (pos != RecyclerView.NO_POSITION) items.getOrNull(pos) else null
            val item = byPos ?: items.firstOrNull { it.id == holder.itemId } ?: return
            applyLeftVisual(holder, item)
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tv_item_title)
            val subtitle: TextView = view.findViewById(R.id.tv_item_subtitle)

            /** 左栏（item_rail）没有海报和进度条，这两个会是 null。 */
            val poster: ImageView? = view.findViewById(R.id.img_poster)
            val progress: ProgressBar? = view.findViewById(R.id.pb_item_progress)

            /** 左栏的「当前选中」竖条。 */
            val indicator: View? = view.findViewById(R.id.rail_indicator)

            /** 当前绑定的是哪一条（回调里用来判断 holder 是否已被复用）。 */
            var itemId: String? = null

            /** 这个 holder 正在进行的取图请求。 */
            var imageRequest: work.luxiaoxiao.myembyhub.net.ImageLoader.Request? = null
        }
    }

    companion object {
        private const val SIDE_LEFT = 0
        private const val SIDE_RIGHT = 1

        /** 左侧「最近观看」的虚拟 id，不和 Emby 的真实 id 冲突。 */
        private const val RESUME_ID = "__resume__"

        private const val REQ_PLAY = 1001
        private const val REQ_DETAIL = 1002

        /**
         * 从首页深链进来的目标（媒体库 id / 剧 id / 文件夹 id）。
         *
         * 首页卡片点「剧」这类需要再下钻的条目时，直接跳到这里并定位到对应层，
         * 不用让用户在老界面里再点一遍。
         */
        const val EXTRA_PARENT_ID = "home_parent_id"
        const val EXTRA_PARENT_NAME = "home_parent_name"

        /** 首页顶栏「搜索」进来时直接弹搜索框。 */
        const val EXTRA_OPEN_SEARCH = "home_open_search"

        /**
         * 海报请求宽度（像素）。
         *
         * item_media.xml 里海报是 72dp × 108dp，density 1.5 → 屏幕上就是 108 × 162 px。
         * 取 160 保留约 1.5 倍余量（应对不同密度），再大就是纯浪费：
         * 位图内存按宽度平方涨，320 宽是 160 宽的 4 倍。
         */
        private const val POSTER_WIDTH = 160

        /** 媒体库根层递归列条目时只收这几种（Folder 包装项会被服务端先滤掉）。 */
        private const val LIBRARY_ITEM_TYPES = "Movie,Series,Video"
    }
}
