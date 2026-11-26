package work.luxiaoxiao.myembyhub.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import work.luxiaoxiao.myembyhub.BuildConfig
import work.luxiaoxiao.myembyhub.R
import work.luxiaoxiao.myembyhub.net.EmbyApi
import work.luxiaoxiao.myembyhub.net.EmbyHttpDataSource
import work.luxiaoxiao.myembyhub.net.ErrorLog
import work.luxiaoxiao.myembyhub.net.ImageLoader
import work.luxiaoxiao.myembyhub.net.MediaItem
import work.luxiaoxiao.myembyhub.net.PhoneControlServer
import work.luxiaoxiao.myembyhub.net.QrUtil
import work.luxiaoxiao.myembyhub.net.Session
import work.luxiaoxiao.myembyhub.net.SortOption
import work.luxiaoxiao.myembyhub.net.TlsHostMemory
import work.luxiaoxiao.myembyhub.net.TlsStack
import work.luxiaoxiao.myembyhub.net.friendlyError
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 主界面（电视端）：**顶部 tab + 三个页面**。
 *
 * ## 三个页面共用一套视觉
 *
 * - **首页**：继续观看 + 每个媒体库最新若干条，纵向多条横滑行。
 * - **媒体库**：顶部一行库 chip + 下方海报墙（网格，翻页加载）。
 * - **搜索**：顶部一个搜索入口 + 结果海报墙。
 *
 * 三处的卡片都是同一份 `item_home_poster` + 同一个 [PosterAdapter]，
 * 差别只在 `LayoutManager`（横滑 vs 网格）—— 这样「换页面」不会换掉视觉语言。
 *
 * ## 焦点即切换（电视化的关键取舍）
 *
 * 顶栏三个 tab **焦点走到哪一项，内容就切到哪一栏**，不用再按一次确认。
 * 遥控器上「左左左 + 确认」是四次按键，「左左左」是三次，而且中间那两次
 * 用户本来也不关心 —— 所以焦点即切换在电视上是省按键的方向。
 *
 * 但直接切有两个坑，这里各有一道对策：
 *
 * 1. **按住方向键会连续经过中间 tab**，一次经过触发一次加载 → 4.3 上直接卡住。
 *    → 焦点停下 [TAB_SWITCH_DELAY_MS] 毫秒才真切（防抖），路过不算。
 * 2. **切过去时新页面数据还没到**，整屏空白像崩了。
 *    → 旧页面先留着不隐藏，等新页面出第一屏内容才互换；且每个页面的数据取过就缓存，
 *      切回来不重取。
 *
 * ## 为什么纵向用 `ScrollView` 而不是 `RecyclerView`
 *
 * 电视上没有「无限滚」的需求，纵向就是一个 `ScrollView`，每条横滑行才是一个横向
 * `RecyclerView`。滚动和焦点跟随都是系统行为，不用自己维护。
 * 海报墙那两处反过来：条目上千，必须是 `RecyclerView` 网格（带翻页）。
 */
class HomeActivity : Activity() {

    // ==================== 视图 ====================

    private lateinit var navBar: LinearLayout
    private lateinit var pageHome: TopInsetScrollView
    private lateinit var sectionContainer: LinearLayout
    private lateinit var pageLibrary: View
    private lateinit var pageSearch: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var boxLoading: View
    private lateinit var boxLibLoading: View
    private lateinit var tvHint: TextView

    /**
     * 每个页面自己的提示语（空态 / 出错 / 搜索引导），索引跟 tab 一致。
     *
     * ⚠️ 三页共用一个 `tv_home_hint`，只靠 `visibility` 记状态是不够的：
     * 搜索页那句引导语会跟着用户飘到首页去（实测「进过设置页面之后，
     * 这个按确认键输入片名……的提示就一直在了」）。所以按页存文案，切页时整页替换。
     */
    private val pageHints = arrayOfNulls<String>(3)
    private val pageLoading = BooleanArray(3)

    /** 顶栏三个 tab（顺序和 `home_nav_titles` 一致）。 */
    private val navItems = ArrayList<View>()

    /** 顶栏最右侧的「设置」按钮：不参与 tab 切换，按确认弹菜单。 */
    private lateinit var btnSettings: View

    /**
     * 媒体库左栏最上面那一行「排序 · 最近添加」。
     *
     * 为什么不在顶栏：顶栏从左往右走会「焦点即切换」，要够到最右边的按钮必须先经过
     * 「搜索」tab，一经过就切页了 —— 排序按钮在媒体库页**永远走不到**
     * （实测：从「媒体库」按 → 焦点直接落到「设置」，排序被跳过）。
     * 放在左栏头上，↓ 就是「选哪个库」，动作链是一条直线。
     */
    private var libSortRow: TextView? = null

    /**
     * 媒体库当前的排序方式。
     *
     * 默认「开播时间」降序（见 [SortOption.DEFAULT]）：库里「加入时间」取决于什么时候
     * 拷进去的，一次补齐整季会让同一天的片子整批霸占前面；开播时间才是内容本身的先后。
     *
     * ⚠️ 会存盘（[uiPrefs]）：用户挑过「按片名」，下次开 App 还得是按片名。
     *    只放内存里的话，杀进程重进就变回默认，等于白设。
     */
    private var libSort: SortOption.Opt = SortOption.DEFAULT

    /**
     * 用户选的排序方向（true = 降序）。
     *
     * 跟 [SortOption.Opt.descending] 分开存：字段自带的方向只是**换字段时的默认值**
     * （开播时间、年份、时长、加入时间默认降序，片名默认升序），
     * 而用户完全可以要「年份升序」看老片。换字段时方向重置成新字段的默认，
     * 同一字段内用户改过的方向保持不变。
     */
    private var libSortDesc: Boolean = libSort.descending

    private val uiPrefs by lazy { getSharedPreferences("myembyhub_ui", MODE_PRIVATE) }

    /** 把当前排序写回左栏那一行（带方向箭头，省得用户还得点开菜单才知道是升还是降）。 */
    private fun refreshSortLabel() {
        val arrow = if (libSortDesc) " ↓" else " ↑"
        libSortRow?.text = getString(R.string.home_sort_fmt, libSort.label + arrow)
    }

    /** 读回上次用的排序；存的值不认识（比如手改过）就退回默认。 */
    private fun loadSort() {
        libSort = SortOption.of(uiPrefs.getString(KEY_LIB_SORT, "") ?: "")
        libSortDesc = uiPrefs.getBoolean(KEY_LIB_SORT_DESC, libSort.descending)
    }

    /** 改排序：更新标签、存盘、重取当前库。 */
    private fun applySort(opt: SortOption.Opt, desc: Boolean) {
        libSort = opt
        libSortDesc = desc
        uiPrefs.edit()
            .putString(KEY_LIB_SORT, opt.key)
            .putBoolean(KEY_LIB_SORT_DESC, desc)
            .apply()
        refreshSortLabel()
        // 只重取当前库，不重置左栏分类（用户刚挑好这一页，别把他也挪走）
        if (tabIndex == 1) selectLibrary(libIndex, focusGrid = false)
    }

    /** 「降序」对当前这个字段到底是什么意思（菜单里那行小字）。 */
    private fun descHint(opt: SortOption.Opt): String = when (opt.key) {
        SortOption.SORT_NAME -> "Z → A"
        SortOption.SORT_RUNTIME -> "长的在前"
        else -> "新的在前"
    }

    private fun ascHint(opt: SortOption.Opt): String = when (opt.key) {
        SortOption.SORT_NAME -> "A → Z"
        SortOption.SORT_RUNTIME -> "短的在前"
        else -> "旧的在前"
    }

    /** 当前页面编号：0 首页 / 1 媒体库 / 2 搜索。 */
    private var tabIndex = 0

    /** 焦点停下后才生效的那次切换（防抖用的）。 */
    private var pendingTab: Runnable? = null

    /**
     * 临时关掉「焦点即切换」。
     *
     * 弹窗/菜单一关，Activity 重新拿焦点时系统会把焦点**塞给第一个可聚焦视图**
     * （正好是第一个 tab），于是「焦点即切换」立刻把页面拽回首页 —— 用户只是关了个输入框，
     * 后面的搜索结果全白搜了（实测踩过）。
     * → 弹窗期间置 true，关掉的同时把焦点扶回当前页的内容上。
     */
    private var suppressTabSwitch = false

    /** 已经建好数据、不用再等的页面。 */
    private val pageReady = booleanArrayOf(false, false, false)

    // ==================== 首页状态 ====================

    private val homeRows = ArrayList<RecyclerView>()
    private val homeRowOrder = ArrayList<Int>()
    /** 首页多排卡片共用同一个视图布局，共享回收池减少 ViewHolder 实例。 */
    private val homePosterPool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 18) }

    /**
     * 从详情页返回时要恢复焦点的条目 id。
     *
     * 用户点开第 7 张卡、看完返回，焦点如果跳回第一张卡，等于让他重新找路 ——
     * 所以进详情页前把 id 记下来，首页数据重刷完再按 id 找回去。
     */
    private var pendingFocusItemId: String? = null

    /**
     * 每条横滑行右侧的「更多 ›」，索引跟 [homeRows] 对齐（没有就是 null）。
     *
     * 「更多」在区块标题那一行最右端（header 里），不在卡片行里 —— 它是 RecyclerView 的
     * **兄弟视图**，RecyclerView 自己找邻居永远找不到它，所以两边只能靠这里存引用：
     * 行末卡按 → 跳到它身上，它按 ← 跳回行末卡（对称，一个不多一个不少）。
     */
    private val homeMoreChips = ArrayList<View?>()

    /**
     * 下一次 [appendSection] 是否要先把老内容整体换掉。
     *
     * 刷新时不能立刻清空（会空白好几秒），也不能不清（会重复堆叠）——
     * 折中成「第一块新数据到了才清」，一个标志搞定。
     */
    private var homePendingReset = true

    // ==================== 媒体库状态 ====================

    private lateinit var libTabColumn: LinearLayout
    private lateinit var libTabScroll: ScrollView
    private lateinit var libGrid: RecyclerView

    /**
     * 左栏一个库 tab 的三个零件。
     *
     * 单独存根视图 + 竖条 + 标题，是因为「选中」要同时改三处：
     * 行底（`bg_lib_tab` 的 `state_selected`）、左侧暖色竖条、标题颜色 ——
     * 只把 root 存进列表的话，另外两处每次都得 `findViewById` 摸一遍。
     */
    private class LibTab(val root: View, val indicator: View, val title: TextView)

    private val libTabs = ArrayList<LibTab>()

    private var libAdapter: PosterAdapter? = null

    /** 当前选中的库（在 [libraries] 里的下标）。 */
    private var libIndex = 0

    /** 当前库已经取到第几条（翻页用）。 */
    private var libOffset = 0
    private var libAtEnd = false
    private var libLoading = false

    /** 正在显示哪个库（翻页的请求回来时用来判断用户是否已经切走）。 */
    private var libShownId = ""

    /** 库 chip 焦点停下才切库，理由同顶栏。 */
    private var pendingLib: Runnable? = null

    // ==================== 搜索状态 ====================

    private lateinit var searchBox: TextView
    private lateinit var searchGrid: RecyclerView
    private var searchAdapter: PosterAdapter? = null
    private var searchQuery = ""

    /** 搜索页的「手机输入」按钮（弹窗关掉后焦点要回到这里）。 */
    private var btnPhone: View? = null

    /** 手机扫码输入的本地服务（只在搜索页按「手机输入」时起）。 */
    private var phoneServer: PhoneControlServer? = null
    private var qrDialog: Dialog? = null

    // ==================== 数据 ====================

    private lateinit var api: EmbyApi
    private lateinit var session: Session

    private fun newBackgroundPool(threads: Int, name: String): ThreadPoolExecutor =
        ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, name).apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }

    private val worker = newBackgroundPool(1, "home-worker")
    private val main = Handler(Looper.getMainLooper())

    /**
     * 首页各行的取数线程池，**两路**。
     *
     * 为什么要单独开一个：`worker` 是单线程的，上面还排着登录态、媒体库清单、
     * 图片元数据这些活儿；首页五个库要是塞进同一个队列里，那就是纯串行。
     * 这里只服务「按库取 20 条」这一件事，最多同时两路 ——
     * MStar 双核 1GHz，再多开也只是互相抢，而且这台电视挂不住三四个响应体。
     */
    private val rowPool = newBackgroundPool(2, "home-rows")

    /**
     * 首页的「代」。每次 [loadHome] 自增，回调里对不上就整块丢弃。
     *
     * 播完片回首页会再调一次 loadHome，两次的响应可能交叉回来 ——
     * 没有这个守卫的话，老的一代会把新的那批行又追加一遍，
     * 屏幕上会出现两排同一个库。
     */
    @Volatile private var homeGeneration = 0
    private var homeLoading = false
    private var autoFocusHome = false

    /** 媒体库清单（首页和媒体库页共用一份，避免取两遍）。 */
    private var libraries: List<MediaItem> = emptyList()
    private val libraryCallbacks = ArrayList<(Result<List<MediaItem>>) -> Unit>()

    /** 一条横滑行 = 一个区块。 */
    private class Section(val title: String, val libraryId: String, val items: List<MediaItem>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        hideSystemBars()

        navBar = findViewById(R.id.nav_bar)
        pageHome = findViewById(R.id.scroll_sections)
        sectionContainer = findViewById(R.id.section_container)
        pageLibrary = findViewById(R.id.page_library)
        pageSearch = findViewById(R.id.page_search)
        pbLoading = findViewById(R.id.pb_home_loading)
        boxLoading = findViewById(R.id.box_home_loading)
        boxLibLoading = findViewById(R.id.box_lib_loading)
        tvHint = findViewById(R.id.tv_home_hint)

        session = Session.get(this)
        api = EmbyApi(this, session.server, session.token, session.userId)

        // 装全局异常钩子：电视上没法看 logcat，崩过一次就再也查不到原因。
        // 装在这里而不是 Application：只有一个入口需要它，且幂等。
        ErrorLog.install()

        if (!session.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 排序方式要赶在 buildLibraryPage 之前读回来，否则第一屏先按默认排一遍再重排
        loadSort()
        buildNav()
        buildLibraryPage()
        buildSearchPage()

        // 首屏：首页先取数，焦点落在第一张卡上
        showPage(0)
        navItems.firstOrNull()?.requestFocus()
        loadHome(focusFirst = true)
    }

    // ==================== 顶栏 ====================

    /**
     * 建顶栏：三个 tab + 靠右的「设置」。
     *
     * 设置**不进 tab 序列**：它是弹出菜单，不是一栏内容。混进去的话
     * 焦点一路往右会「切到一个不存在的页面」，说不通。
     */
    private fun buildNav() {
        val titles = resources.getStringArray(R.array.home_nav_titles)
        navItems.clear()
        navBar.removeAllViews()

        for (i in titles.indices) {
            val item = LayoutInflater.from(this).inflate(R.layout.item_home_nav, navBar, false)
            item.findViewById<TextView>(R.id.tv_nav_title).text = titles[i]
            val underline = item.findViewById<View>(R.id.nav_underline)

            item.isSelected = i == tabIndex
            underline.visibility = if (i == tabIndex) View.VISIBLE else View.INVISIBLE

            item.setOnFocusChangeListener { _, hasFocus ->
                // 焦点走到哪一项，下划线就跟到哪一项（当前页的选中态另算，两者可以同时出现）
                refreshNavUnderlines()
                // 焦点即切换：停顿一下再切，路过中间那几项不算
                if (hasFocus) scheduleTabSwitch(i) else cancelTabSwitch()
            }
            // 确认键不跳转（焦点已经切过去了），只把焦点送进内容区 —— 少按一次
            item.setOnClickListener { onTabConfirmed(i) }
            item.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusContentOf(tabIndex)
                        true
                    }
                    // 顶栏是一整排，左右必须逐项走。
                    // ⚠️ 不能交给框架的 focus search：实测在「媒体库」页加载完之后，
                    //    从 tab 按右键会被判定成「右下方的卡片更近」，直接跳进海报墙，
                    //    用户再也走不到「搜索」和「设置」。
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (i > 0) navItems[i - 1].requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (i < navItems.size - 1) navItems[i + 1].requestFocus()
                        else btnSettings?.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            navBar.addView(item)
            navItems.add(item)
        }

        // 把设置推到最右
        navBar.addView(Space(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })

        // 设置放在最右，但用**和 tab 一样的药丸**（同一个 item_home_nav 布局）：
        // 它是顶栏上的第五个位置，只是被 Space 推到右边去。
        val settings = LayoutInflater.from(this)
            .inflate(R.layout.item_home_nav, navBar, false)
        settings.findViewById<TextView>(R.id.tv_nav_title).text = getString(R.string.home_settings)
        val settingsUnderline = settings.findViewById<View>(R.id.nav_underline)
        settings.setOnFocusChangeListener { _, hasFocus ->
            // 「设置」不是页面（是弹菜单），所以它拿到焦点时下划线亮一下、
            // **但不参与「焦点即切换」** —— 也就不会把当前页切走。
            settingsUnderline.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) cancelTabSwitch()
        }
        settings.setOnClickListener { showSettingsMenu() }
        // 右缘跟内容区对齐（内容区右边距 28dp，顶栏自己已留 14dp，这里再补 14dp）
        (settings.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.rightMargin = dp(14)
            settings.layoutParams = it
        }
        settings.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                // 顶栏只有一行：左回最后一个 tab，下进当前页内容区
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    navItems.lastOrNull()?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusContentOf(tabIndex)
                    true
                }
                else -> false
            }
        }
        navBar.addView(settings)
        btnSettings = settings

        // 顶栏第一项默认拿焦点的话，用户一进来看不到内容 —— 交给 loadHome 里的 focusFirst
    }

    /** 焦点停稳之后才真的换页。 */
    private fun scheduleTabSwitch(index: Int) {
        cancelTabSwitch()
        if (suppressTabSwitch) return
        if (index == tabIndex) return
        val r = Runnable { switchTab(index) }
        pendingTab = r
        main.postDelayed(r, TAB_SWITCH_DELAY_MS)
    }

    /**
     * 重算三个 tab 的下划线。
     *
     * ⚠️ 光靠 `setOnFocusChangeListener` 里改是不够的：焦点从「媒体库」直接跳到「搜索」时，
     *    媒体库那一刻**还是选中态**，下划线留着；等 260ms 后 [switchTab] 把选中态改掉，
     *    已经没有第二次焦点变化来触发它了 → **多个 tab 底下都挂着一条线**（实测踩过）。
     *    → 凡是改选中态的地方，都顺手整体重算一遍。
     */
    private fun refreshNavUnderlines() {
        for (i in navItems.indices) {
            val item = navItems[i]
            val underline = item.findViewById<View>(R.id.nav_underline)
            underline.visibility =
                if (item.hasFocus() || item.isSelected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun cancelTabSwitch() {
        pendingTab?.let { main.removeCallbacks(it) }
        pendingTab = null
    }

    /**
     * 切页。
     *
     * 旧页面不立刻隐藏：等新页面自己把内容准备好（[revealPage]）再换，
     * 否则中间那段「取数中」用户看到的是整屏空白。
     *
     * @param libraryTarget 切到媒体库页时要选中的库序号；-1 = 用当前的 [libIndex]。
     *        「更多 ›」用它一次把「切页 + 选中指定库」做完 —— 早期做法是
     *        `switchTab(1)` 之后再补一次 `selectLibrary`，两次都拉一遍同一个库的
     *        第一页，后回来的那次 `submit()` 会把已经拿到焦点的那张卡 detach 掉，
     *        焦点被框架甩到「排序」那颗 chip 上（实测）。
     */
    private fun switchTab(index: Int, libraryTarget: Int = -1) {
        if (index !in 0..2) return
        if (index != 0) autoFocusHome = false
        tabIndex = index
        for (i in navItems.indices) navItems[i].isSelected = i == index
        refreshNavUnderlines()
        // 先按目标页切显隐，取完数再交给 revealPage 收尾
        showPage(index)
        when (index) {
            0 -> if (!pageReady[0] && !homeLoading) loadHome(focusFirst = false)
            1 -> if (libraryTarget >= 0) {
                // 指定了库：跳过 loadLibraryPageIfNeeded 的「选当前库」，
                // 免得同一个库被拉两遍（见 KDoc）。左栏 tab 还没建就先建出来。
                if (libTabs.isEmpty()) buildLibTabs()
                selectLibrary(libraryTarget, focusGrid = true)
            } else {
                loadLibraryPageIfNeeded()
            }
            2 -> revealPage(2)
        }
    }

    private fun onTabConfirmed(index: Int) {
        switchTab(index)
        main.postDelayed({ focusContentOf(index) }, 60)
    }

    /** 焦点从顶栏进到内容区（用户主动按的，所以要 force）。 */
    private fun focusContentOf(index: Int) {
        when (index) {
            0 -> focusHomeFirstCard(force = true)
            1 -> {
                val tab = libTabs.getOrNull(libIndex)
                if (tab != null) tab.root.requestFocus() else focusGrid(libGrid)
            }
            2 -> searchBox.requestFocus()
        }
    }

    private fun showPage(index: Int) {
        pageHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageLibrary.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageSearch.visibility = if (index == 2) View.VISIBLE else View.GONE
        // 搜索页刚进来是空的，一句话告诉用户这一页怎么用 —— 否则整屏全黑，看着像加载失败
        // ⚠️ 只在「还没搜过」时插手：搜过之后空态文案归 runSearch 管（「没有找到相关内容」），
        //    这里无条件 hide 会把那句话抹掉
        if (index == 2 && searchQuery.isEmpty()) pageHints[2] = getString(R.string.search_guide)
        updateLoadingUi()
    }

    /**
     * 页面数据就绪了，可以显示。
     *
     * ⚠️ **只有当前就是这一页时才真的显示**：取数是异步的，用户可能在「媒体库」还没加载完
     * 就切到「搜索」了，这时媒体库的数据回来把页面显示出来，会出现
     * **顶栏写着「搜索」、内容却是媒体库**的错位（实测踩过）。
     * 数据照样标记成就绪（下次切回来不用重取），但页面的显隐只由 [switchTab] 决定。
     */
    private fun revealPage(index: Int) {
        pageReady[index] = true
        if (index == tabIndex) showPage(index)
    }

    // ==================== 首页 ====================

    /** 两条首页请求并行，回调都在主线程更新页面。 */
    private fun loadHome(focusFirst: Boolean) {
        if (homeLoading) return
        homeLoading = true
        homePendingReset = true
        autoFocusHome = focusFirst
        if (tabIndex == 0) updateLoadingUi()
        val gen = ++homeGeneration
        var pending = 2
        var firstFailure: Throwable? = null

        fun finished(error: Throwable? = null) {
            if (firstFailure == null) firstFailure = error
            if (--pending != 0) return
            homeLoading = false
            if (homePendingReset && firstFailure == null) clearHomeSections()
            val failure = firstFailure
            if (homeRows.isEmpty()) {
                val hint = if (failure == null) getString(R.string.home_empty)
                else getString(R.string.home_failed, friendlyError(failure))
                showHint(hint, 0)
            }
            pageReady[0] = homeRows.isNotEmpty() || failure == null
            if (tabIndex == 0) {
                updateLoadingUi()
                if (pendingFocusItemId != null) restoreHomeFocus()
            }
        }

        requestLibraries { result ->
            if (gen != homeGeneration) return@requestLibraries
            val rows = result.getOrNull().orEmpty().take(MAX_LIBRARY_ROWS)
            pending += rows.size
            rows.forEachIndexed { index, lib ->
                rowPool.execute {
                    if (gen != homeGeneration) return@execute
                    val items = api.items(
                        lib.id,
                        recursive = true,
                        limit = ROW_LIMIT,
                        sortBy = SortOption.SORT_DATE_CREATED,
                        descending = true,
                        includeItemTypes = POSTER_TYPES
                    )
                    main.post {
                        if (gen != homeGeneration) return@post
                        val data = items.getOrNull().orEmpty()
                        if (data.isNotEmpty()) addHomeSection(index + 1, Section(lib.name, lib.id, data))
                        finished(items.exceptionOrNull())
                    }
                }
            }
            finished(result.exceptionOrNull())
        }

        rowPool.execute {
            val result = api.resume(ROW_LIMIT)
            main.post {
                if (gen != homeGeneration) return@post
                val items = result.getOrNull().orEmpty()
                if (items.isNotEmpty()) addHomeSection(0, Section(getString(R.string.home_resume), "", items))
                finished(result.exceptionOrNull())
            }
        }
    }

    /** 首页和媒体库共享进行中的库列表请求。 */
    private fun requestLibraries(onResult: (Result<List<MediaItem>>) -> Unit) {
        libraryCallbacks.add(onResult)
        if (libraryCallbacks.size > 1) return
        rowPool.execute {
            val result = api.libraries()
            main.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { libraries = it }
                val callbacks = libraryCallbacks.toList()
                libraryCallbacks.clear()
                callbacks.forEach { it(result) }
            }
        }
    }

    private fun clearHomeSections() {
        sectionContainer.removeAllViews()
        homeRows.clear()
        homeMoreChips.clear()
        homeRowOrder.clear()
    }

    /** 哪一行先返回就先展示。插入位置仍按媒体库顺序，「继续观看」固定在最前。 */
    private fun addHomeSection(order: Int, sec: Section) {
        if (homePendingReset) {
            clearHomeSections()
            homePendingReset = false
        }
        val index = homeRowOrder.indexOfFirst { it > order }.let { if (it < 0) homeRows.size else it }
        val row = createRow(sec)
        homeRows.add(index, row)
        homeRowOrder.add(index, order)
        sectionContainer.addView(createHeader(sec.title, sec.libraryId, row, index), index * 2)
        sectionContainer.addView(row, index * 2 + 1)
        // 当晚到的区块插到第一个时，原来的首行标题改用普通区块的内边距。
        if (index == 0 && homeRows.size > 1) {
            sectionContainer.getChildAt(2).setPadding(dp(24), dp(16), dp(24), 0)
        }
        hideHint(0)
        if (tabIndex == 0) {
            updateLoadingUi()
            main.post {
                if (tabIndex != 0 || PopupGate.anyOpen()) return@post
                val focus = currentFocus
                if (autoFocusHome && (focus == null || focus === pageHome || focus === navItems.firstOrNull())) {
                    autoFocusHome = false
                    focusHomeFirstCard(force = true)
                } else if (focus == null || focus === pageHome) {
                    focusHomeFirstCard()
                }
            }
        }
    }

    private fun createHeader(title: String, libraryId: String, cards: RecyclerView, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP)
            )
            // ⚠️ 只在非首节加 topMargin：加了以后 chip 的底边会压到下一排卡片上
            setPadding(dp(24), if (index > 0) dp(16) else 0, dp(24), 0)
        }

        val tv = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.content_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isSingleLine = true
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })

        if (libraryId.isNotEmpty()) {
            val more = TextView(this).apply {
                text = getString(R.string.home_more)
                setTextColor(ContextCompat.getColorStateList(this@HomeActivity, R.color.chip_text))
                textSize = 15f
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
            }
            more.setOnClickListener { openLibraryTab(libraryId) }
            more.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusCardIn(cards, 0)
                        true
                    }
                    // ← 回本排最后一张卡（焦点左移一格）；→ 是屏幕边缘，吃掉合理。
                    //
                    // ⚠️ 原来 ← 直接送回顶栏 —— 2026-11-18 用户反馈：「更多键按左键
                    //    会回到顶栏，按理说应该是焦点左移一个吧」。方向键就该是「移动一格」，
                    //    「更多」在行末右侧，它左边正好是本排最后一张卡。
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val last = (cards.adapter?.itemCount ?: 0) - 1
                        if (last >= 0) {
                            focusCardIn(cards, last)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(4)
            row.addView(more, lp)
            homeMoreChips.add(index, more)
        } else {
            homeMoreChips.add(index, null)
        }
        return row
    }

    private fun createRow(sec: Section): RecyclerView {
        val rv = RecyclerView(this)
        rv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
        )
        rv.setPadding(dp(18), 0, dp(18), 0)
        rv.clipToPadding = false
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.setHasFixedSize(true)
        rv.setRecycledViewPool(homePosterPool)
        rv.setItemViewCacheSize(4)
        // 默认动画在切换数据时会闪一下
        rv.itemAnimator = null

        rv.adapter = PosterAdapter(
            items = sec.items.toMutableList(),
            onClick = { onCardClick(it) },
            // ⚠️ 每一排都要自己接住 ↑，不能只接第一排。
            //    页面滚下去之后，上面那一排是**看不见**的，框架的几何 focus search
            //    找不到它，就会把焦点丢给顶栏 —— 实测：焦点在第二排按 ↑，
            //    直接跳到了「媒体库」tab（日志 h5 y=114 → h6 顶栏），
            //    第一排那两张卡用户再也回不去。
            firstRowCount = sec.items.size,
            onUpFromFirstRow = { pos -> focusRowAbove(homeRows.indexOf(rv), pos) },
            firstColumnCount = 1,
            onLeftFromFirstColumn = {
                // 行首按 ←：不动，吃掉这次按键（2026-11-18 用户明确要求）。
                //
                // 历史两版都不对：
                //   ① `chip?.requestFocus(); true` —— chip 为 null（「继续观看」这类区块
                //      不指向媒体库）时 requestFocus 空转、按键却被吃掉，观感是「左键坏了」；
                //   ② 改成「找不到 chip 就回顶栏」—— 焦点从行首直接甩到顶栏，
                //      用户按 ← 想要的是「再往左挪一点」，不是「跳回导航条」。
                // 现在的语义：行首就是这一排的最左边，← 到这里为止，什么都不发生。
                // 要回顶栏请用 ↑（`onUpFromFirstRow` 接的就是这个）。
                //
                // ⚠️ 这次能真正生效的前提是 PosterAdapter 把「行首」判对了：
                //    以前 `position % firstColumnCount == 0` 在 firstColumnCount = 1 时恒真，
                //    每张卡按 ← 都会走到这里 —— 这才是「非最左边也跳去更多键」的真凶。
                true
            },
            // 行末按 →：去这一排的「更多 ›」（区块标题那一行的右端）。
            // 没有「更多」的区块返回 false，让框架照常把焦点带到下一排。
            onRightFromLastCard = { _ ->
                val chip = homeMoreChips.getOrNull(homeRows.indexOf(rv))
                if (chip != null && chip.isShown && chip.requestFocus()) true else false
            },
            onCardFocused = {
                // 焦点落回第一排 → 整页滚回最顶，把区块标题也露出来。
                //
                // ⚠️ 光靠 `TopInsetScrollView` 不够：它只保证「焦点卡不被顶栏压住」，
                //    滚停的位置是「卡片顶边贴着顶栏下沿」—— 区块标题（「继续观看」）
                //    反而被顶栏吃掉，整页看着跟刚进来时不是一个姿势（实测：初始卡片
                //    y=207，上来一趟之后变 y=114）。用户从第三排一路 ↑ 回来，
                //    期望的是「回到最上面」，不是「卡住在这里」。
                //
                // ⚠️ 必须 `post`：焦点变化回调跑在 `View.requestFocus()` 内部，
                //    紧接着父容器才调 `requestChildRectangleOnScreen` 把卡片滚进可视区，
                //    在这里同步 `scrollTo(0,0)` 会被那次滚动直接盖掉（实测 t3 仍是 114）。
                if (homeRows.firstOrNull() === rv) {
                    pageHome.post { if (pageHome.scrollY != 0) pageHome.scrollTo(0, 0) }
                }
            }
        )
        return rv
    }

    /**
     * 某一排按 ↑：交给上一排的同序号卡片；第一排才回顶栏。
     *
     * 为什么要自己算而不是让框架 focus search 去找：上面那一排**可能已经滚出屏幕**，
     * 几何搜索找不到它，就会把焦点丢给顶栏（实测：第二排按 ↑ 直接跳到「媒体库」tab）。
     * 自己算的话，不管上一排现在看不看得见，焦点都稳定地往上走一排在同样的位置 ——
     * 用户按几下 ↑ 就退回几下，路径可预测。
     */
    private fun focusRowAbove(rowIndex: Int, position: Int): Boolean {
        val above = homeRows.getOrNull(rowIndex - 1)
        if (above == null) {
            return navItems.getOrNull(tabIndex)?.requestFocus() != null
        }
        // 上一排短（条目少）时贴到最后一张，不要因为越界就吃掉按键
        val count = above.adapter?.itemCount ?: 0
        val target = position.coerceAtMost((count - 1).coerceAtLeast(0))
        focusCardIn(above, target)
        // 焦点还在当前排时把整页滚回顶：第一排要露出区块标题，
        // 其它排只要保证上一排完整可见（交给 ScrollView 的焦点滚动）
        pageHome.post { if (pageHome.scrollY != 0 && rowIndex - 1 == 0) pageHome.scrollTo(0, 0) }
        return true
    }

    /**
     * 把焦点放到首页第一张卡上。
     *
     * @param force `true` 表示「用户主动按方向键要求进内容区」，此时不管焦点现在在哪
     *        都要抢过来；`false`（默认）是数据加载完的自动兜底，只在焦点悬空、
     *        或者本来就停在首页内容区里时才动手 —— 否则会把用户已经挪走的焦点抢回来。
     *
     * ⚠️ 要重试：数据是一行一行画出来的，第一行刚 addView 时 RecyclerView 还没测量子项，
     *    `findViewByPosition(0)` 返回 null，`requestFocus()` 打空 —— 冷启动就变成
     *    **整屏没有任何高亮**，用户不知道往哪按（实测「刚启动 dump 到的是 ScrollView」）。
     *    每 200ms 重试一次，最多 8 次（1.6 秒），期间只要拿到焦点就停。
     */
    private fun focusHomeFirstCard(retries: Int = 0, force: Boolean = false) {
        // 每次重试前都重新确认「用户还在首页」。
        //
        // ⚠️ 光判 `tabIndex == 0` 不够：顶栏是「焦点停 260ms 才换页」，
        //    用户已经把焦点挪到「媒体库」tab、页面还没切的那一瞬间，这个函数
        //    照样会把焦点抢进首页第一张卡 —— 于是 260ms 后页面切走，焦点留在一个
        //    `GONE` 的页面里，整屏没有高亮、方向键全不响应（真机日志实测：
        //    `firstCard retries=0 tab=0 cur=LinearLayout{135,0-279,114}`）。
        //    所以非 force 时还要看「焦点现在是不是就在首页里」。
        if (tabIndex != 0 || pageHome.visibility != View.VISIBLE) return
        if (!force) {
            val cf = currentFocus
            if (cf != null && !isInHome(cf)) return
        }
        val first = homeRows.firstOrNull()
        if (first == null) {
            if (retries < FOCUS_RETRY_MAX) {
                main.postDelayed({ focusHomeFirstCard(retries + 1, force) }, 200)
            }
            return
        }
        val lm = first.layoutManager as? LinearLayoutManager
        val card = lm?.findViewByPosition(0)
        if (card != null) {
            card.requestFocus()
            if (card.isFocused || retries >= FOCUS_RETRY_MAX) return
        }
        if (retries < FOCUS_RETRY_MAX) {
            main.postDelayed({ focusHomeFirstCard(retries + 1, force) }, 200)
        }
    }

    /**
     * 从详情页 / 播放器回来时，把焦点放回**刚才那条内容**。
     *
     * 找不到（那条内容被删了、或者首页数据变了）就老实退回第一张卡 ——
     * 至少不能整屏没高亮。
     */
    private fun restoreHomeFocus(retries: Int = 0) {
        if (tabIndex != 0 || pageHome.visibility != View.VISIBLE) return
        val want = pendingFocusItemId
        if (want == null) {
            focusHomeFirstCard()
            return
        }
        for (rv in homeRows) {
            val ad = rv.adapter as? PosterAdapter ?: continue
            val pos = ad.indexOf(want)
            if (pos < 0) continue
            val lm = rv.layoutManager as? LinearLayoutManager ?: continue
            val card = lm.findViewByPosition(pos)
            if (card != null) {
                card.requestFocus()
                if (card.isFocused) {
                    pendingFocusItemId = null
                    return
                }
            } else {
                // 那一排还没画出来（数据是一行一行画的），等下一轮
                if (retries < FOCUS_RETRY_MAX) {
                    main.postDelayed({ restoreHomeFocus(retries + 1) }, 200)
                }
                return
            }
        }
        // 翻遍了首页都没这一条 —— 说明首页数据换过了，退回第一张卡
        pendingFocusItemId = null
        focusHomeFirstCard()
    }

    /** [v] 是不是首页内容区里的东西（或者就是内容区本身）。 */
    private fun isInHome(v: View?): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === pageHome) return true
            val p = cur.parent
            cur = if (p is View) p else null
        }
        return false
    }

    /** 把焦点塞进某个卡片（不可见就先滚过去）。 */
    private fun focusCardIn(rv: RecyclerView, position: Int) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val visible = lm.findViewByPosition(position)
        if (visible != null) {
            visible.requestFocus()
            return
        }
        // 外部塞焦点时 RecyclerView 不会自己滚
        lm.scrollToPositionWithOffset(position, 0)
        rv.post {
            lm.findViewByPosition(position)?.requestFocus()
        }
    }

    // ==================== 媒体库页 ====================

    private fun buildLibraryPage() {
        val root = pageLibrary as ViewGroup
        libTabScroll = root.findViewById(R.id.scroll_lib_tabs)
        libTabColumn = root.findViewById(R.id.lib_tab_column)
        libGrid = root.findViewById(R.id.rv_library_grid)

        // 排序行固定在左栏顶部（不在 lib_tab_column 里，所以不会被 buildLibTabs 清掉）
        val sortRow = root.findViewById<TextView>(R.id.lib_sort_row)
        libSortRow = sortRow
        refreshSortLabel()
        sortRow.setOnClickListener { showSortMenu() }
        sortRow.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                // 它是左栏第一行：往上回顶栏，往下去第一个库，往右进海报墙
                KeyEvent.KEYCODE_DPAD_UP -> {
                    navItems.getOrNull(tabIndex)?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    libTabs.firstOrNull()?.root?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    focusGrid(libGrid)
                    true
                }
                else -> false
            }
        }

        libGrid.layoutManager = GridLayoutManager(this, libSpan)
        libGrid.setHasFixedSize(true)
        libGrid.itemAnimator = null
        libAdapter = PosterAdapter(
            items = ArrayList(),
            onClick = { onCardClick(it) },
            firstRowCount = libSpan,
            // 第一排按 ↑ 回顶栏（左栏在左边，不是上边，往上就是顶栏）
            onUpFromFirstRow = { _ -> navItems.getOrNull(tabIndex)?.requestFocus() != null },
            firstColumnCount = libSpan,
            // 行首按 ← 回左栏当前选中的库 —— 不接的话框架会往左上找，
            // 结果是跳到顶栏（或者干脆不动），用户就被困在墙里出不去
            onLeftFromFirstColumn = { libTabs.getOrNull(libIndex)?.root?.requestFocus() != null }
        )
        libGrid.adapter = libAdapter

        // 滚到接近底部就取下一页
        libGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                val total = libAdapter?.size() ?: 0
                if (lm.findLastVisibleItemPosition() >= total - libSpan) loadMoreLibrary()
            }
        })
    }

    /** 第一次进媒体库页才建 chip。 */
    private fun loadLibraryPageIfNeeded() {
        if (pageReady[1]) {
            showPage(1)
            return
        }
        if (libraries.isNotEmpty()) {
            buildLibTabs()
            selectLibrary(libIndex, focusGrid = false)
            return
        }
        if (pageLoading[1]) return
        showLoading(true, page = 1)
        requestLibraries { result ->
            showLoading(false, page = 1)
            result.onSuccess { libs ->
                if (libs.isEmpty()) {
                    showHint(getString(R.string.library_empty), 1)
                    return@onSuccess
                }
                buildLibTabs()
                selectLibrary(0, focusGrid = false)
            }.onFailure { showHint(getString(R.string.home_failed, friendlyError(it)), 1) }
        }
    }

    private fun buildLibTabs() {
        libTabColumn.removeAllViews()
        libTabs.clear()
        for (i in libraries.indices) {
            val view = layoutInflater.inflate(R.layout.item_lib_tab, libTabColumn, false)
            val indicator = view.findViewById<View>(R.id.lib_tab_indicator)
            val title = view.findViewById<TextView>(R.id.tv_lib_tab_title)
            title.text = libraries[i].name
            view.isSelected = i == libIndex
            indicator.visibility = if (i == libIndex) View.VISIBLE else View.INVISIBLE

            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    scheduleLibSwitch(i)
                    revealLibTab(view)
                } else {
                    cancelLibSwitch()
                }
            }
            view.setOnClickListener { onLibConfirmed(i) }
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    // 左栏在左边，往右才是海报墙
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        focusGrid(libGrid)
                        true
                    }
                    // 第一项往上顶到排序行（它上面才是顶栏），最后一项往下掉进墙里 —— 两头都不留死角
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (i == 0) {
                            if (libSortRow?.requestFocus() != true) navItems.getOrNull(tabIndex)?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (i == libraries.lastIndex) {
                            focusGrid(libGrid)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }

            // ⚠️ 不能自己 new LayoutParams 传进去：那会把 XML 里的 56dp 行高直接换成
            //    WRAP_CONTENT（实测行高塌成 24dp，一列分类挤成一堆）。
            //    用 inflate(parent, false) 已经生成好的 layoutParams 原样加入就行。
            libTabColumn.addView(view)
            libTabs.add(LibTab(view, indicator, title))
        }
    }

    /**
     * 让左栏的这一行完整露出来。
     *
     * ⚠️ 不能只靠 ScrollView 自己跟着焦点滚：这个库有 14 个分类，一屏放不下，
     *    实测按到底最后一行只露出 12px（`[21,996][342,1008]`）——
     *    ScrollView 在 4.3 上算「目标矩形」时用的是被裁剪后的可见部分，
     *    贴边的时候它就以为已经看见了。所以这里 `post` 到布局完成之后，
     *    用这一行真实的宽高再要求一次。
     */
    private fun revealLibTab(tab: View) {
        libTabScroll.post {
            if (tab.width <= 0 || tab.height <= 0) return@post
            libTabScroll.requestChildRectangleOnScreen(
                tab,
                android.graphics.Rect(0, 0, tab.width, tab.height),
                false
            )

        }
    }

    /** 把第 [index] 个库标成「选中」（行底、竖条、标题色三处一起动）。 */
    private fun markLibTabSelected(index: Int) {
        for (i in libTabs.indices) {
            val on = i == index
            val tab = libTabs[i]
            tab.root.isSelected = on
            tab.indicator.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun scheduleLibSwitch(index: Int) {
        cancelLibSwitch()
        if (index == libIndex && pageReady[1]) return
        val r = Runnable { selectLibrary(index, focusGrid = false) }
        pendingLib = r
        main.postDelayed(r, TAB_SWITCH_DELAY_MS)
    }

    private fun cancelLibSwitch() {
        pendingLib?.let { main.removeCallbacks(it) }
        pendingLib = null
    }

    private fun onLibConfirmed(index: Int) {
        cancelLibSwitch()
        selectLibrary(index, focusGrid = true)
    }

    /**
     * 切到某个库：重建海报墙。
     *
     * 左栏是 `ScrollView`，塞焦点进去时它不会自己滚 ——
     * 用 `requestChildRectangleOnScreen` 让选中的那一行露出来
     * （11 个库在 720dp 高的屏上刚好装不下，不滚就有一半够不到）。
     */
    private fun selectLibrary(index: Int, focusGrid: Boolean) {
        val lib = libraries.getOrNull(index) ?: return
        libIndex = index
        markLibTabSelected(index)
        libTabs.getOrNull(index)?.let { tab ->
            libTabScroll.requestChildRectangleOnScreen(
                tab.root,
                android.graphics.Rect(0, 0, tab.root.width, tab.root.height),
                false
            )
        }

        libShownId = lib.id
        libOffset = 0
        libAtEnd = false
        libLoading = true
        libAdapter?.submit(emptyList())
        // ⚠️ 左栏有库 tab 时**不摆全屏转圈**：屏幕上已经有一列分类在那儿响应方向键，
        //    中间再盖一个转圈 + 「加载中…」，看着像界面卡住了。
        //    改成只在海报墙那块转（`box_lib_loading`）—— 网格会被清空，
        //    那两秒纯黑跟「这个库是空的」分不出来，必须有东西说明在忙。
        val firstEnter = libTabs.isEmpty()
        showLoading(firstEnter, page = 1)
        showLibLoading(!firstEnter)

        worker.execute {
            val r = api.items(
                lib.id,
                recursive = true,
                limit = PAGE_LIMIT,
                sortBy = libSort.key,
                descending = libSortDesc,
                includeItemTypes = POSTER_TYPES,
                startIndex = 0
            )
            main.post {
                if (libShownId != lib.id) return@post
                libLoading = false
                showLibLoading(false)
                showLoading(false, page = 1)
                r.onSuccess { list ->
                    libOffset = list.size
                    libAtEnd = list.size < PAGE_LIMIT
                    libAdapter?.submit(list)
                    revealPage(1)
                    if (list.isEmpty()) {
                        showHint(getString(R.string.library_empty), 1)
                    } else {
                        hideHint(1)
                        // 从详情页回来时优先把焦点放回刚才那张卡；没有记录才进第一张
                        if (pendingFocusItemId != null) restoreLibraryFocus()
                        else if (focusGrid && tabIndex == 1) focusGrid(libGrid)
                    }
                }.onFailure {
                    revealPage(1)
                    showHint(getString(R.string.home_failed, friendlyError(it)), 1)
                }
            }
        }
    }

    private fun loadMoreLibrary() {
        if (libLoading || libAtEnd) return
        val lib = libraries.getOrNull(libIndex) ?: return
        libLoading = true
        val from = libOffset
        worker.execute {
            val r = api.items(
                lib.id,
                recursive = true,
                limit = PAGE_LIMIT,
                sortBy = libSort.key,
                descending = libSortDesc,
                includeItemTypes = POSTER_TYPES,
                startIndex = from
            )
            main.post {
                if (libShownId != lib.id) return@post
                libLoading = false
                r.onSuccess { list ->
                    libOffset = from + list.size
                    libAtEnd = list.size < PAGE_LIMIT
                    libAdapter?.append(list)
                }.onFailure { libAtEnd = true }
            }
        }
    }

    // ==================== 搜索页 ====================

    private fun buildSearchPage() {
        val root = pageSearch as ViewGroup
        searchBox = root.findViewById(R.id.btn_search_box)
        searchGrid = root.findViewById(R.id.rv_search_grid)
        val phoneBtn = root.findViewById<View>(R.id.btn_phone_search)
        btnPhone = phoneBtn

        searchGrid.layoutManager = GridLayoutManager(this, gridSpan)
        searchGrid.setHasFixedSize(true)
        searchGrid.itemAnimator = null
        searchAdapter = PosterAdapter(
            items = ArrayList(),
            onClick = { onCardClick(it) },
            firstRowCount = gridSpan,
            onUpFromFirstRow = { _ -> searchBox.requestFocus() },
            firstColumnCount = gridSpan,
            onLeftFromFirstColumn = { false }
        )
        searchGrid.adapter = searchAdapter

        searchBox.setOnClickListener { showSearchInput() }
        searchBox.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusGrid(searchGrid)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    navItems.getOrNull(tabIndex)?.requestFocus()
                    true
                }
                else -> false
            }
        }

        phoneBtn.setOnClickListener { startPhoneSearch() }
        phoneBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusGrid(searchGrid)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    navItems.getOrNull(tabIndex)?.requestFocus()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 搜索框：走 [TvDialog] 的输入形态。
     *
     * 电视上用遥控器逐字输入体验很差，所以搜索页另有「手机扫码输入」那条路
     * （见 [showQrDialog]）。
     */
    private fun showSearchInput() {
        val input = EditText(this).apply {
            setBackgroundResource(R.drawable.bg_dialog_input)
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.content_primary))
            setHintTextColor(ContextCompat.getColor(this@HomeActivity, R.color.content_secondary))
            textSize = 18f
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(searchQuery)
            setSelection(text.length)
        }
        suppressTabSwitch = true
        TvDialog.show(
            this,
            getString(R.string.search_box_hint),
            input,
            listOf(
                TvDialog.Btn("取消") { restoreFocusAfterDialog() },
                TvDialog.Btn("搜索", primary = true) {
                    val q = input.text.toString().trim()
                    if (q.isNotEmpty()) runSearch(q)
                    restoreFocusAfterDialog()
                }
            ),
            initialFocus = input,
            onDismiss = { restoreFocusAfterDialog() }
        )
    }

    /**
     * 弹窗关掉之后收尾：放开「焦点即切换」，并把焦点扶回搜索框。
     *
     * 不扶的话焦点会落在框架自己挑的那个视图上（通常是第一个 tab），
     * 一旦放开防抖就立刻把页面切走。
     *
     * ⚠️ **弹窗可以套娃**（设置菜单 → 体验优化计划 → 确认框），内层关掉时外层还开着。
     *    这时若放开闸门并把焦点拽回内容区，用户刚打开的那个对话框会立刻丢焦点。
     *    所以先问 [PopupGate]：还有窗口开着就什么都不做，让最外层关的时候统一收尾。
     */
    private fun restoreFocusAfterDialog(target: View? = null) {
        main.postDelayed({
            // 这 80ms 里页面可能已经走了（退出登录那条路：confirm → finish）
            if (isFinishing || isDestroyed) return@postDelayed
            if (PopupGate.anyOpen()) return@postDelayed
            suppressTabSwitch = false
            if (target != null && target.isShown) target.requestFocus()
            else if (tabIndex == 2) searchBox.requestFocus()
            else focusContentOf(tabIndex)
        }, 80)
    }

    private fun runSearch(query: String) {
        searchQuery = query
        searchBox.text = query
        showLoading(true, page = 2)
        worker.execute {
            val r = api.search(query, SEARCH_LIMIT)
            main.post {
                showLoading(false, page = 2)
                r.onSuccess { list ->
                    searchAdapter?.submit(list)
                    if (list.isEmpty()) showHint(getString(R.string.search_empty), 2)
                    else hideHint(2)
                }.onFailure { showHint(getString(R.string.search_failed, friendlyError(it)), 2) }
            }
        }
    }

    // ==================== 搜索页：手机扫码输入 ====================

    /**
     * 起本地小服务，让手机扫二维码在浏览器里打字，搜到之后回填到这一页。
     *
     * 电视上没有软键盘，遥控器逐字选字母的按键数跟片名长度成正比，
     * 这是电视端唯一能在十秒内输入「星际穿越」的办法。
     */
    private fun startPhoneSearch() {
        // isRunning 而不是 != null：服务会在 10 分钟上限自己关掉，
        // 那时引用还在但端口早没了，直接复用会弹出一张扫不通的码
        if (phoneServer?.isRunning != true) {
            phoneServer = PhoneControlServer(
                this,
                mode = PhoneControlServer.MODE_SEARCH,
                onLogin = { _, _, _, _ -> },
                onSearch = { term ->
                    main.post {
                        qrDialog?.dismiss()
                        qrDialog = null
                        phoneServer?.stop()
                        phoneServer = null
                        runSearch(term)
                        searchBox.requestFocus()
                    }
                },
                onFail = { msg -> main.post { toast(msg) } }
            ).also { it.start() }
        }
        showQrDialog()
    }

    private fun showQrDialog() {
        qrDialog?.dismiss()
        suppressTabSwitch = true
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qr, null)
        val img = view.findViewById<ImageView>(R.id.img_qr)
        val tv = view.findViewById<TextView>(R.id.tv_qr_url)
        val dlg = TvDialog.show(
            this,
            getString(R.string.search_phone),
            view,
            buttons = listOf(
                TvDialog.Btn("关闭", primary = true) {
                    phoneServer?.stop()
                    phoneServer = null
                }
            )
        )
        qrDialog = dlg
        // ⚠️ 这一行**覆盖**了 TvDialog 内部设的 setOnDismissListener（同一把锁只能挂一个监听）。
        //    这里没传 onDismiss 给 TvDialog，所以覆盖不丢东西；但以后要给这个对话框补
        //    onDismiss 时，得写进下面这个 lambda，而不是再传参。
        dlg.setOnDismissListener {
            qrDialog = null
            phoneServer?.stop()
            phoneServer = null
            // 返回键直接关掉也要走这一步：否则 suppressTabSwitch 一直是 true，
            // 「焦点即切换」从此彻底失效（现象：切页全靠按确认键）
            restoreFocusAfterDialog(btnPhone)
        }

        worker.execute {
            val url = QrUtil.selfUrl(this, PhoneControlServer.PORT, "/?mode=" + PhoneControlServer.MODE_SEARCH)
            val bmp = if (url.isEmpty()) null else QrUtil.encode(url, 640)
            main.post {
                tv.text = if (url.isEmpty()) "拿不到电视的局域网 IP" else url
                if (bmp != null) img.setImageBitmap(bmp) else img.setImageDrawable(null)
            }
        }
    }

    // ==================== 公共：卡片点击 / 播放 ====================

    private fun focusGrid(rv: RecyclerView) {
        if (rv.adapter?.itemCount == 0) return
        val lm = rv.layoutManager as? GridLayoutManager ?: return
        val v = lm.findViewByPosition(0)
        if (v != null && v.requestFocus()) return
        lm.scrollToPositionWithOffset(0, 0)
        focusGridRetry(rv, 6)
    }

    /**
     * 等第 0 张卡真正布局出来再交焦点（最多重试 [tries] 帧）。
     *
     * ⚠️ 不能用 `post {}`：`scrollToPositionWithOffset` 只是请求一次遍历，
     *    `post` 常常排在那次遍历之前跑，此时第 0 张卡还没 attach，
     *    `findViewByPosition(0)` 拿到 null —— 焦点原地不动。
     *    这正是「点更多切到媒体库后，焦点掉在『排序』那颗 chip 上」的成因。
     */
    private fun focusGridRetry(rv: RecyclerView, tries: Int) {
        rv.postOnAnimation {
            if (isFinishing || isDestroyed) return@postOnAnimation
            val lm = rv.layoutManager as? GridLayoutManager ?: return@postOnAnimation
            val v = lm.findViewByPosition(0)
            if (v != null && v.requestFocus()) return@postOnAnimation
            if (tries > 0) focusGridRetry(rv, tries - 1)
        }
    }

    /**
     * 从详情页回到媒体库网格时，把焦点放回**刚才点的那张卡**。
     *
     * 跟首页 [restoreHomeFocus] 是同一个诉求：用户点开第 12 张、看完返回，
     * 焦点落回左栏第一个库，等于让他重新在一屏 40 张里找刚才那张。
     * 找不到（翻页把那张挤掉了、或者换了库）就退回第一张。
     */
    private fun restoreLibraryFocus(retries: Int = 0) {
        if (tabIndex != 1) return
        val want = pendingFocusItemId ?: return
        val ad = libAdapter
        val pos = ad?.indexOf(want) ?: -1
        if (ad == null || pos < 0) {
            pendingFocusItemId = null
            focusGrid(libGrid)
            return
        }
        val lm = libGrid.layoutManager as? GridLayoutManager
        val card = lm?.findViewByPosition(pos)
        if (card != null) {
            card.requestFocus()
            if (card.isFocused) pendingFocusItemId = null
            return
        }
        if (retries < FOCUS_RETRY_MAX) {
            main.postDelayed({ restoreLibraryFocus(retries + 1) }, 200)
        } else {
            // 那一页还没画出来（或者被翻页挤掉了），退回第一张
            pendingFocusItemId = null
            focusGrid(libGrid)
        }
    }

    private fun onCardClick(item: MediaItem) {
        // 电影 / 剧集：进详情页（看简介、挑集数）。
        // 单集 / 视频：直接播 —— 从「继续观看」点进来的那张卡本来就是「接着上次看」，
        // 再让用户过一遍详情页只是多按一次确认键。
        if (item.type == "Movie" || item.type == "Series") {
            openDetail(item)
            return
        }
        if (item.isPlayable) {
            play(item)
        } else {
            // 走到这里说明是一张「文件夹型」的卡片（`isPlayable` 要求 !isFolder）。
            // 首页和媒体库都按 `POSTER_TYPES = Movie,Series,Video` 取数，
            // 正常情况下不会出现这种卡；万一服务端把某个视频标成了文件夹，
            // 就说明白，别让用户点了没反应。
            toast("「${item.displayTitle()}」是个文件夹，展开请到媒体库")
        }
    }

    private fun openDetail(item: MediaItem) {
        // 记下点的是哪一条，回来时把焦点放回这张卡（见 pendingFocusItemId）
        pendingFocusItemId = item.id
        startActivityForResult(DetailActivity.intent(this, item), REQ_DETAIL)
    }

    /**
     * 打开某个库的完整海报墙 —— 区块标题右侧那个「更多 ›」。
     *
     * 以前这里跳的是 `LibraryActivity`（老的左栏分层列表）。那套界面已经被
     * 首页 + 媒体库页完全覆盖（同样的库、同样的海报墙、同样能翻页），
     * 留着只是多一个长得不一样的入口，所以删掉，改成**切到媒体库页并选中这个库**。
     *
     * ⚠️ 用 `post` 而不是直接切：`switchTab(1)` 会先把页面显出来、再触发取数，
     *    紧接着 `selectLibrary` 才能拿到正确的 `libGrid` 布局状态。
     *    两步之间没有延迟的话，`focusGrid` 会落在一个还没 layout 的 RecyclerView 上，
     *    焦点丢失（真机表现是「点了更多，页面切过去了但遥控器没反应」）。
     */
    private fun openLibraryTab(libraryId: String) {
        val index = libraries.indexOfFirst { it.id == libraryId }
        if (index < 0) {
            // 首页区块用的库 id 一定来自 libraries；对不上说明列表还没加载完
            toast("这个库还没加载出来，稍后再试")
            return
        }
        // ⚠️⚠️ 必须先关掉「焦点即切换」的闸门（[suppressTabSwitch]）。
        //    `switchTab(1)` 里 `showPage(1)` 会把整个首页设成 `GONE`，而此刻焦点正停在
        //    刚按过的那颗「更多 ›」上 —— 视图一消失，系统立刻把焦点甩给顶栏最近的可聚焦物
        //    （就是「首页」那颗 tab），它的焦点监听排一个 260ms 的 [scheduleTabSwitch] →
        //    **页面刚切过去又被切回首页**。实测现象：logcat 里那句日志打了、
        //    页面却纹丝不动，顶栏仍是「首页」选中。
        //    → 切之前关门，等媒体库页真正拿到焦点后再放开。不能一直关着，
        //      否则「焦点即切换」永久失效（那是之前修过的另一个 bug）。
        suppressTabSwitch = true
        // 一次做完「切页 + 选中这个库」，不再先 switchTab(1) 再补一次 selectLibrary ——
        // 那样同一个库会被拉两遍，后回来的那次 submit() 把已经拿到焦点的卡 detach 掉，
        // 焦点被框架甩到「排序」那颗 chip 上（实测）。
        switchTab(1, libraryTarget = index)
        // 这个库的海报是第一页并发取的，切过来时 libGrid 常常还是空的 ——
        // `focusGrid` 遇到空 adapter 直接 return。补几次重试，数据一到就把焦点放进网格。
        listOf(200L, 500L, 1_000L, 1_800L).forEach { delayMs ->
            main.postDelayed({ focusLibraryGridIfNeeded() }, delayMs)
        }
        // 闸门最后放开：期间焦点可能被「首页 tab 拿到焦点」抢走一次，
        // 放开前再确认一遍焦点确实在内容区。
        main.postDelayed({
            if (!isFinishing && !isDestroyed && tabIndex == 1) {
                focusLibraryGridIfNeeded()
                suppressTabSwitch = false
            }
        }, 2_200L)
    }

    /** 焦点还不在媒体库网格里就把它挪进去（网格没数据时 `focusGrid` 自己会空转）。 */
    private fun focusLibraryGridIfNeeded() {
        if (isFinishing || isDestroyed) return
        if (tabIndex != 1 || PopupGate.anyOpen()) return
        var v: View? = currentFocus
        while (v != null) {
            if (v === libGrid) return
            v = v.parent as? View
        }
        focusGrid(libGrid)
    }

    private fun play(item: MediaItem, startMs: Long = item.resumeMs) {
        if (item.mediaSourceId.isNotEmpty()) {
            startPlayer(item, item.mediaSourceId, startMs, item.seriesId)
            return
        }
        val page = tabIndex
        showLoading(true, page)
        worker.execute {
            val r = api.itemDetail(item.id)
            main.post {
                showLoading(false, page)
                val detail = r.getOrNull()
                val msId = detail?.mediaSourceId.orEmpty()
                if (msId.isEmpty()) {
                    toast(friendlyError(r.exceptionOrNull() ?: IllegalStateException("没有可播放的媒体源")))
                } else {
                    startPlayer(item, msId, startMs, detail?.seriesId.orEmpty().ifEmpty { item.seriesId })
                }
            }
        }
    }

    private fun startPlayer(
        item: MediaItem,
        mediaSourceId: String,
        startMs: Long,
        seriesId: String = item.seriesId
    ) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra(PlayerActivity.EXTRA_URL, api.streamUrl(item, mediaSourceId))
        i.putExtra(PlayerActivity.EXTRA_TITLE, item.displayTitle())
        i.putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
        if (seriesId.isNotEmpty()) i.putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
        i.putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE_ID, mediaSourceId)
        i.putExtra(PlayerActivity.EXTRA_START_MS, startMs)
        startActivityForResult(i, REQ_PLAY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            // 播完回来：刷新继续观看的进度
            REQ_PLAY -> if (pageReady[0] && tabIndex == 0) loadHome(focusFirst = false)
            // 从详情页回来：只有在详情页真播过片时才重刷进度和「继续观看」
            REQ_DETAIL -> {
                val played = data?.getBooleanExtra(DetailActivity.EXTRA_PLAYED, false) == true
                if (played) {
                    if (pageReady[0]) loadHome(focusFirst = false)
                    if (pageReady[1] && tabIndex == 1) selectLibrary(libIndex, focusGrid = false)
                } else {
                    pendingFocusItemId = null
                }
            }
        }
    }

    // ==================== 设置 ====================

    private fun showSettingsMenu() {
        // 菜单期间同样要挡住「焦点即切换」：菜单一关，系统会把焦点塞给第一个 tab
        suppressTabSwitch = true
        TvMenu.show(
            this,
            getString(R.string.home_settings),
            listOf(
                TvMenu.Item("服务器信息") { showServerInfo() },
                TvMenu.Item("TLS 实现", tlsImplLabel()) { showTlsMenu() },
                TvMenu.Item("检查更新", "从 GitHub Release 取新版本") {
                    UpdateUi.check(this, worker, main, ::toast)
                },
                TvMenu.Item("反馈", "把设备信息发给开发者") { showFeedback() },
                TvMenu.Item("体验优化计划", playStatsLabel()) { showStatsMenu() },
                TvMenu.Item("播完自动下一集", autoNextLabel()) { toggleAutoNext() },
                TvMenu.Item("刷新首页", "重新取一遍数据") {
                    pageReady.fill(false)
                    if (tabIndex == 0) loadHome(focusFirst = false) else switchTab(0)
                },
                TvMenu.Item("退出登录", "清掉本地登录态") { confirmLogout() }
            ),
            onDismiss = { restoreFocusAfterDialog() }
        )
    }

    /**
     * 反馈入口。
     *
     * 菜单的 `onDismiss` 已经把闸门放开、焦点扶回来了，但反馈框是**新开的窗口** ——
     * 它一关，Activity 重新拿焦点，系统会把焦点塞给第一个 tab，
     * 于是「焦点即切换」立刻把页面拽回首页。所以这里再关一次闸门，
     * 由 [FeedbackUi] 在所有出口（取消 / 返回 / 成功 / 失败）回调 `restoreFocusAfterDialog`。
     */
    private fun showFeedback() {
        suppressTabSwitch = true
        FeedbackUi.show(this, worker, main, ::toast) { restoreFocusAfterDialog() }
    }

    // ==================== 播完自动下一集 ====================

    /**
     * 设置菜单里那行副标题。
     *
     * 写清楚「最后一集会停」而不是只说「开 / 关」：用户最怕的是
     * 关电视前手一滑、它自己一集接一集播到天亮。
     */
    private fun autoNextLabel(): String =
        if (session.autoNextEpisode) "开：播完自动接下一集（最后一集停）"
        else "关：播完停在结尾"

    /**
     * 一键切换，不走二次确认。
     *
     * 和「体验优化计划」不同：那个一旦打开就开始往外发数据，值得一次确认；
     * 这个纯属本地播放行为，用户随时能再点回来，多一个确认框只是多两次方向键。
     */
    private fun toggleAutoNext() {
        val on = !session.autoNextEpisode
        session.autoNextEpisode = on
        toast(if (on) "播完自动接下一集：已开启" else "播完自动接下一集：已关闭")
    }

    // ==================== 体验优化计划 ====================

    /** 设置菜单里那行副标题 —— 直接把「发不发 / 不发什么」写出来。 */
    private fun playStatsLabel(): String =
        if (session.playStatsEnabled) "开：每次播放只发性能数字"
        else "关：不上报任何播放记录"

    /**
     * 「体验优化计划」开关。
     *
     * ## 为什么要这个开关，以及为什么默认关
     *
     * [PlayStats] 这条链路和「反馈」不是一回事：反馈是用户点一下、填一句话、
     * 自己看着进度条发出去的；打点是**每次退出播放器**自动发一条，
     * 不弹框、不确认、失败静默。默认开等于「用户从没同意过但一直在发」——
     * 哪怕内容只有首帧毫秒数，这个形态也不该存在。
     *
     * 所以：默认关，只有用户自己在这里打开才发（[Session.playStatsEnabled]）。
     * 打开时给一次确认，把「具体发哪几个字段」念一遍 —— 遥控器上的二次确认
     * 值这个成本，总比事后解释「你到底传了我什么」便宜。
     *
     * ## 为什么不做首启弹窗
     *
     * 首启那一刻用户还在输服务器地址 / 扫码，App 连自己能不能用都没证明，
     * 这时候问「要不要参与改进」几乎没有说服力，答「不」是默认反应；
     * 而且它会额外多占两三次方向键。放在设置里，想开的人自然会找到。
     */
    private fun showStatsMenu() {
        suppressTabSwitch = true
        val on = session.playStatsEnabled
        TvMenu.show(
            this,
            "体验优化计划",
            listOf(
                TvMenu.Item(
                    "关闭",
                    if (!on) "当前" else "不再自动上报任何播放数据"
                ) { applyPlayStats(false) },
                TvMenu.Item(
                    "开启",
                    if (on) "当前" else "播放结束后发一条性能数字"
                ) { applyPlayStats(true) }
            ),
            onDismiss = { restoreFocusAfterDialog() }
        )
    }

    /**
     * 关是立刻生效的；开要走一次确认，因为开完就会开始外发数据。
     *
     * ⚠️ 确认框是**新开的窗口**，和 [showFeedback] 同一个坑：
     * 它一关，Activity 重新拿到焦点，系统会把焦点塞给第一个 tab，
     * 于是「焦点即切换」立刻把页面拽回首页。所以 onDismiss 里再扶一次焦点。
     */
    private fun applyPlayStats(on: Boolean) {
        if (!on) {
            session.playStatsEnabled = false
            toast("已退出体验优化计划，不再上报播放数据")
            return
        }
        TvDialog.confirm(
            this,
            "开启体验优化计划",
            "每次播放结束会自动上报一条性能数据：\n\n" +
                "首帧耗时、播放时长、掉帧数、重缓冲次数、码率、\n" +
                "编解码格式、解码器名称、片名与条目 id。\n\n" +
                "不含账号、密码、播放进度、片库清单、Wi-Fi 名称和局域网 IP。\n" +
                "只发到你自己配置的那个反馈服务器。",
            confirmText = "开启",
            cancelText = "算了",
            onDismiss = { restoreFocusAfterDialog() }
        ) {
            session.playStatsEnabled = true
            toast("已加入体验优化计划")
        }
    }

    /**
     * 媒体库的排序菜单：**字段 + 方向**两层。
     *
     * 用 [TvMenu.showPersistent]（点选不关闭）而不是 [TvMenu.show]：
     * 「先挑字段、再翻方向、不满意再换一个字段」是连着按的，
     * 每点一下就关掉、还得重新打开菜单，电视上按起来烦。
     * 菜单开着的时候网格就在后面重排，关掉就是最终结果。
     *
     * 方向是**跟着字段走**的：换字段时方向重置成该字段的默认值
     * （开播时间 / 年份 / 时长 / 加入时间 → 新的在前；片名 → A 在前），
     * 同一字段内用户改过的方向保持不变。所以「年份升序」这种是能按出来的。
     */
    private fun showSortMenu() {
        suppressTabSwitch = true
        TvMenu.showPersistent(
            this,
            getString(R.string.home_sort_title),
            provider = {
                val list = ArrayList<TvMenu.Item>()
                // 分组标题：禁用项 = 灰色、不可聚焦，方向键直接跳过
                list.add(TvMenu.Item(getString(R.string.home_sort_field), enabled = false) {})
                SortOption.ALL.forEach { opt ->
                    list.add(
                        TvMenu.Item(
                            opt.label,
                            if (opt.key == libSort.key) getString(R.string.home_sort_current) else ""
                        ) {
                            if (opt.key != libSort.key) applySort(opt, opt.descending)
                        }
                    )
                }
                list.add(TvMenu.Item(getString(R.string.home_sort_dir), enabled = false) {})
                list.add(
                    TvMenu.Item(
                        getString(R.string.home_sort_desc),
                        prefixCurrent(libSortDesc) + descHint(libSort)
                    ) { if (!libSortDesc) applySort(libSort, true) }
                )
                list.add(
                    TvMenu.Item(
                        getString(R.string.home_sort_asc),
                        prefixCurrent(!libSortDesc) + ascHint(libSort)
                    ) { if (libSortDesc) applySort(libSort, false) }
                )
                list
            },
            onDismiss = { restoreFocusAfterDialog(libSortRow) }
        )
    }

    /** 菜单副标题里「当前」那个前缀；不是当前项就是空串。 */
    private fun prefixCurrent(isCurrent: Boolean): String =
        if (isCurrent) getString(R.string.home_sort_current) + " · " else ""

    private fun showServerInfo() {
        val msg = buildString {
            append("服务器：").append(session.server).append('\n')
            append("用户：").append(session.username).append('\n')
            append("客户端：").append(BuildConfig.VERSION_NAME).append('\n')
            append("TLS：").append(tlsImplLabel()).append('\n')
            append("更新来源：").append(session.updateManifest)
        }
        TvDialog.message(this, "服务器信息", msg)
    }

    // ==================== TLS 实现 ====================

    /** 菜单副标题 / 服务器信息里显示的那行字。 */
    private fun tlsImplLabel(): String = when (TlsStack.pref()) {
        TlsStack.TlsImpl.AUTO ->
            if (TlsStack.conscryptAvailable() == false) "自动（Conscrypt 不可用，已退回系统库）"
            else "自动（优先 Conscrypt）"
        TlsStack.TlsImpl.CONSCRYPT -> "强制 Conscrypt"
        TlsStack.TlsImpl.SYSTEM -> "系统 JSSE（只有 CBC）"
    }

    /**
     * TLS 实现选择。
     *
     * ## 为什么要有这个开关
     *
     * 4.3 的系统 JSSE 只有 CBC 套件，只开 GCM 的服务端根本连不上（见 [TlsStack] 注释）。
     * 默认改成 Conscrypt 之后，绝大多数情况不用管这一页。留这个入口是因为：
     *
     * - 某些 ROM 上 Conscrypt 的原生库可能加载不了或行为异常，
     *   此时用户可以自己锁回系统栈，而不是「这版彻底连不上」；
     * - 排查问题时需要一次性对比两条栈的握手结果。
     *
     * 改完必须重连：SSLContext 与 socket 池里全是旧栈建出来的连接，
     * 所以这里提示「回到登录页重新登录一次」。
     */
    private fun showTlsMenu() {
        suppressTabSwitch = true
        val cur = TlsStack.pref()
        TvMenu.show(
            this,
            "TLS 实现",
            listOf(
                TvMenu.Item(
                    "自动",
                    if (cur == TlsStack.TlsImpl.AUTO) "当前" else "优先 Conscrypt，失败退回系统库"
                ) { applyTlsImpl(TlsStack.TlsImpl.AUTO) },
                TvMenu.Item(
                    "Conscrypt",
                    if (cur == TlsStack.TlsImpl.CONSCRYPT) "当前" else "强制使用，支持 GCM / TLS 1.3"
                ) { applyTlsImpl(TlsStack.TlsImpl.CONSCRYPT) },
                TvMenu.Item(
                    "系统 JSSE",
                    if (cur == TlsStack.TlsImpl.SYSTEM) "当前" else "只有 CBC 套件，新式站点连不上"
                ) { applyTlsImpl(TlsStack.TlsImpl.SYSTEM) },
                // 「新域名先试哪条」是**另一件事**，跟上面三选一正交：
                // 上面定「主动用什么」，这里定「没有记录时先赌哪条」。
                // 默认开：系统栈能用就用，省掉 Conscrypt 那 8MB 原生库。
                TvMenu.Item(
                    "新域名先试系统库",
                    if (TlsStack.probeSystemFirst(this)) "开 · 系统库能连就用它"
                    else "关 · Conscrypt 优先，零失败握手"
                ) { toggleProbeSystemFirst() },
                TvMenu.Item(
                    "已记录的域名",
                    rememberedHostsLabel()
                ) { forgetTlsHosts() }
            ),
            onDismiss = { restoreFocusAfterDialog() }
        )
    }

    /**
     * 切换「新域名先试系统库」。
     *
     * 打开时会把已有记录清空 —— 那些记录是按旧策略试出来的结论，
     * 留着会让新策略形同虚设。
     */
    private fun toggleProbeSystemFirst() {
        val on = !TlsStack.probeSystemFirst(this)
        TlsStack.setProbeSystemFirst(this, on)
        toast(
            if (on) "新域名将先试系统库；谈不拢会自动换回 Conscrypt"
            else "已改回 Conscrypt 优先；域名记录已清空"
        )
    }

    private fun rememberedHostsLabel(): String {
        val list = TlsHostMemory.snapshot(this)
        if (list.isEmpty()) return "还没记录，点此无操作"
        val names = list.joinToString("、") { it.first }
        return "清空 $names"
    }

    private fun forgetTlsHosts() {
        val n = TlsHostMemory.snapshot(this).size
        if (n == 0) {
            toast("还没有域名记录")
            return
        }
        TlsHostMemory.clear(this)
        toast("已清空 $n 个域名的 TLS 记录")
    }

    private fun applyTlsImpl(impl: TlsStack.TlsImpl) {
        TlsStack.setPref(this, impl)
        // 手动锁了实现，按域名试出来的旧结论就不再适用（记录优先级低于手动锁定，
        // 但留着会在下次切回「自动」时把旧结论又翻出来，容易看花眼）
        TlsHostMemory.clear(this)
        toast("TLS 实现已切换到「${tlsImplLabel()}」，建议重新登录一次")
    }

    private fun confirmLogout() {
        TvDialog.confirm(this, "退出登录", "确定要退出当前账号吗？", "退出", "取消") {
            EmbyHttpDataSource.clearIdlePool()
            ImageLoader.get(this).clearMemCache()
            session.clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    // ==================== 提示 / 杂项 ====================

    private fun showLoading(on: Boolean, page: Int) {
        pageLoading[page] = on
        if (page == tabIndex) updateLoadingUi()
    }

    private fun updateLoadingUi() {
        val loading = isPageLoading(tabIndex)
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        // 首页只在没有任何区块时显示居中的转圈，已有内容时保留顶栏进度条。
        val showSpinner = loading && (tabIndex != 0 || homeRows.isEmpty())
        boxLoading.visibility = if (showSpinner) View.VISIBLE else View.GONE
        if (loading) tvHint.visibility = View.GONE else applyPageHint(tabIndex)
    }

    private fun isPageLoading(page: Int): Boolean = pageLoading[page] || (page == 0 && homeLoading)

    /**
     * 媒体库**切库**时的转圈：只盖在海报墙那一块上。
     *
     * 跟 [showLoading] 的分工：全屏那个是「整页还没有结果」（冷启动、第一次进媒体库），
     * 这个是「左栏分类已经在了、右边这片还没到」—— 所以不盖左栏，也不盖顶栏：
     * 用户正在用 ↑/↓ 挑分类，把整屏糊住等于告诉他「现在什么都别按」。
     *
     * 为什么必须给个东西：切库会把网格清空（留着上一个库的海报是错的），
     * 而取一页要一两秒 —— 那两秒里右半边是纯黑，跟「这个库是空的」分不出来（实测截图）。
     */
    private fun showLibLoading(on: Boolean) {
        boxLibLoading.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun showHint(text: String, page: Int = tabIndex) {
        // 提示语是三个页面共用的一个 TextView —— 必须按页记账。
        // 只改可见性的话，搜索页那句「按确认键输入片名…」会跟着用户一路飘到首页上
        // （实测：进一趟搜索页再回首页，那行字就赖在那儿不走了）。
        pageHints[page] = text
        if (page != tabIndex || isPageLoading(page)) return
        tvHint.text = text
        tvHint.visibility = View.VISIBLE
    }

    private fun hideHint(page: Int = tabIndex) {
        pageHints[page] = null
        if (page != tabIndex) return
        tvHint.visibility = View.GONE
    }

    /** 切页时把提示语换成这一页自己的那条（没有就藏起来）。 */
    private fun applyPageHint(index: Int) {
        val t = pageHints.getOrNull(index)
        if (t == null) {
            tvHint.visibility = View.GONE
        } else {
            tvHint.text = t
            tvHint.visibility = View.VISIBLE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                showSettingsMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // 不在首页时，返回先回首页 —— 电视上「退回上一层」比直接退出更符合直觉
                if (tabIndex != 0) {
                    cancelTabSwitch()
                    cancelLibSwitch()
                    switchTab(0)
                    navItems.getOrNull(0)?.requestFocus()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统栏会在焦点变化后偷偷回来，这里再收一次
        if (!hasFocus) return
        hideSystemBars()
        // ⚠️ 闸门兜底。`suppressTabSwitch` 的正规复位在各弹窗的 onDismiss 里，
        //    但那要求**每个调用方都记得传 onDismiss** —— 只要漏一个
        //    （`showServerInfo` 就漏了：只弹一个「好」，谁想得起来），
        //    闸门就永久关着，现象是「顶栏焦点不跟随切页，得再按一次确认」。
        //    弹窗全部关掉时 Activity 一定会重新拿到窗口焦点，这里是唯一可靠的收口点。
        main.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (PopupGate.anyOpen()) return@postDelayed
            if (!suppressTabSwitch) return@postDelayed
            suppressTabSwitch = false
            // 这期间焦点多半被系统塞给了第一个 tab，扶回当前页内容区
            if (tabIndex == 2) searchBox.requestFocus() else focusContentOf(tabIndex)
        }, 120)
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
        homeGeneration++
        super.onDestroy()
        cancelTabSwitch()
        cancelLibSwitch()
        // 手机扫码服务的定时线程必须显式收，否则 Activity 没了它还在跑
        phoneServer?.stop()
        phoneServer = null
        worker.shutdownNow()
        // 首页各行的取数池也必须收：Activity 没了线程还在发请求，
        // 回来的结果会 post 到一个已经 detach 的界面上（虽然守卫拦得住，但白耗 CPU 和流量）
        rowPool.shutdownNow()
        libraryCallbacks.clear()
    }

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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 网格每行几个：按屏幕宽度算，别写死（1280dp 宽能放 7 个 150dp 的卡）。 */
    private val gridSpan: Int
        get() {
            val dm = resources.displayMetrics
            val widthDp = dm.widthPixels / dm.density
            return ((widthDp - 36f) / (CARD_WIDTH_DP + CARD_GAP_DP)).toInt().coerceAtLeast(3)
        }

    /**
     * 媒体库页的列数：要扣掉左边那列库分类（240dp + 左右内边距）。
     *
     * 1280dp 宽算下来是 6 列（7 列是整屏宽时的数字）。
     * 万一哪天左栏加宽，这里跟着算，不用去改布局里的卡尺寸。
     */
    private val libSpan: Int
        get() {
            val dm = resources.displayMetrics
            val widthDp = dm.widthPixels / dm.density
            return ((widthDp - LIB_TAB_WIDTH_DP - 28f) / (CARD_WIDTH_DP + CARD_GAP_DP))
                .toInt().coerceAtLeast(2)
        }

    companion object {
        /** 每条横滑行取多少条。20 条够横滑两屏，再多就是白下载。 */
        private const val ROW_LIMIT = 20

        /** 首页最多渲染几个媒体库的区块（「继续观看」另算一行）。 */
        private const val MAX_LIBRARY_ROWS = 5

        /** 行高：卡片 222dp + 上下各 6dp 的 margin。 */
        private const val ROW_HEIGHT_DP = 234

        /** 区块标题行高度。 */
        private const val HEADER_HEIGHT_DP = 56

        /** 卡片宽 / 卡片间距，网格算列数用（跟 item_home_poster 的尺寸对齐）。 */
        private const val CARD_WIDTH_DP = 150
        private const val CARD_GAP_DP = 12

        /** 媒体库页左栏（库分类）的宽度，跟 activity_home.xml 里的 scroll_lib_tabs 对齐。 */
        private const val LIB_TAB_WIDTH_DP = 228

        /** 海报墙一页取多少条。60 条大约 9 行（7 列），够翻两屏。 */
        private const val PAGE_LIMIT = 60

        private const val SEARCH_LIMIT = 60

        /** 焦点停多久才真的换页 / 换库（毫秒）。太快会把「路过」也当成「要切」。 */
        private const val TAB_SWITCH_DELAY_MS = 260L

        /** 冷启动把焦点送进第一张卡时最多重试几次（每次间隔 200ms）。 */
        private const val FOCUS_RETRY_MAX = 8

        /**
         * 海报墙 / 首页横滑行只收这几种类型。
         *
         * ⚠️ 递归列库**必须**带类型过滤：这个 Emby 库里每个影片外面还套了一层同名
         * 文件夹（「恐怖X档案 (2022)」folder 里才是 Movie「恐怖X档案」），
         * 不带过滤会拿回一半「文件夹」，海报墙上出现两套同名卡片。
         */
        private const val POSTER_TYPES = "Movie,Series,Video"

        private const val REQ_PLAY = 2001
        private const val REQ_DETAIL = 2003

        /** 媒体库排序存盘的 key（SharedPreferences 文件名见 [uiPrefs]）。 */
        private const val KEY_LIB_SORT = "lib_sort_key"

        /** 排序方向存盘的 key：true = 降序。跟字段分开存，见 [libSortDesc]。 */
        private const val KEY_LIB_SORT_DESC = "lib_sort_desc"
    }
}
