package work.luxiaoxiao.myembyhub.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import work.luxiaoxiao.myembyhub.R
import kotlin.math.min

/**
 * 自绘对话框。
 *
 * ⚠️ **不要用系统 `AlertDialog`**：
 * 系统对话框在 4.3 上是 Holo 蓝框 + 青色标题分隔线（API 21+ 上是 Material 蓝），
 * 跟本 App 的暖色语言完全两套，看着像「塞进来的系统弹窗」。
 * 现在统一成 **透明窗口 + 自绘面板**（`tv_dialog.xml`）：
 * 深色圆角卡片 + 暖色竖条标题 + 描边/实心按钮，跟菜单、左栏同一套视觉。
 *
 * 三种形态：
 * - [message] 纯提示（一个「好」）
 * - [confirm] 二次确认（取消 + 主按钮）
 * - [show] 自定义内容（进度条、二维码、输入框、详情页…）
 */
object TvDialog {

    /**
     * 对话框按钮。
     *
     * @param primary true → 暖色实心（焦点时）；false → 次要样式
     */
    data class Btn(
        val text: String,
        val primary: Boolean = false,
        val onClick: () -> Unit = {}
    )

    /**
     * 纯提示对话框：标题 + 消息 + 一个「好」。
     *
     * ⚠️ [onConfirm] 必须排在**最后**：Kotlin 的尾随 lambda 绑的是最后一个参数，
     * 全工程有 3 处 `confirm(...) { ... }` 写法，把 onDismiss 放最后会让
     * 「退出登录 / 下载更新」的确认逻辑永远不执行。传 onDismiss 一律写命名参数。
     */
    fun message(
        ctx: Context,
        title: String,
        message: String,
        confirmText: String = "好",
        scrollable: Boolean = false,
        onDismiss: (() -> Unit)? = null,
        onConfirm: (() -> Unit)? = null
    ): Dialog {
        val tv = TextView(ctx).apply {
            text = message
            setTextColor(ContextCompat.getColor(ctx, R.color.content_primary))
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        return show(ctx, title, tv, listOf(Btn(confirmText, primary = true) {
            onConfirm?.invoke()
        }), scrollable = scrollable, onDismiss = onDismiss)
    }

    /** 二次确认对话框：取消 + 主按钮（参数顺序同 [message]，理由见那里）。 */
    fun confirm(
        ctx: Context,
        title: String,
        message: String,
        confirmText: String = "确定",
        cancelText: String = "取消",
        scrollable: Boolean = false,
        onDismiss: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ): Dialog {
        val tv = TextView(ctx).apply {
            text = message
            setTextColor(ContextCompat.getColor(ctx, R.color.content_primary))
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        return show(ctx, title, tv, listOf(
            Btn(cancelText),
            Btn(confirmText, primary = true) { onConfirm() }
        ), scrollable = scrollable, onDismiss = onDismiss)
    }

    /**
     * 自定义内容对话框。
     *
     * @param content 内容视图（放进面板的内容区）
     * @param buttons 按钮（空 = 没有按钮行）
     * @param initialFocus 打开后先拿到焦点的视图（比如输入框）；不给就落在主按钮上
     * @param cancelable 是否允许「返回」关闭（下载进度框要 false）
     * @param scrollable 内容是否可滚动（长文本，如更新说明）。见 [scrollableContent]
     * @param maxContentHeightDp 可滚时内容区的最大高度（超出才滚）
     * @param onDismiss 关闭时的回调（返回键关闭也会走）
     */
    fun show(
        ctx: Context,
        title: String,
        content: View?,
        buttons: List<Btn>,
        initialFocus: View? = null,
        cancelable: Boolean = true,
        scrollable: Boolean = false,
        maxContentHeightDp: Int = 300,
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(ctx, R.style.Theme_MyEmbyHub_Dialog)
        // 自己画标题，系统那个不要
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LayoutInflater.from(ctx).inflate(R.layout.tv_dialog, null, false)
        dialog.setContentView(root)
        root.findViewById<TextView>(R.id.tv_dialog_title).text = title

        val box = root.findViewById<FrameLayout>(R.id.dlg_content)
        if (content != null) {
            if (scrollable) box.addView(scrollableContent(ctx, content, maxContentHeightDp))
            else box.addView(content)
        }

        val row = root.findViewById<LinearLayout>(R.id.dlg_buttons)
        row.visibility = if (buttons.isEmpty()) View.GONE else View.VISIBLE
        val buttonViews = ArrayList<TextView>()
        for (b in buttons) {
            val btn = LayoutInflater.from(ctx)
                .inflate(R.layout.tv_dialog_button, row, false) as TextView
            btn.text = b.text
            btn.setBackgroundResource(
                if (b.primary) R.drawable.bg_dialog_btn else R.drawable.bg_dialog_btn_secondary)
            btn.setTextColor(ContextCompat.getColorStateList(
                ctx,
                if (b.primary) R.color.dialog_btn_text else R.color.dialog_btn_text_secondary
            ))
            btn.setOnClickListener {
                dialog.dismiss()
                b.onClick()
            }
            row.addView(btn)
            buttonViews.add(btn)
        }

        dialog.setCancelable(cancelable)
        // ⚠️ 用 setOnDismissListener，**不是** setOnCancelListener：
        //    onCancel 只在「按返回 / 点外面」时触发，点按钮走的是 dialog.dismiss()，一次都不回调。
        //    调用方的 onDismiss 是「关掉之后收尾」的意思（放开焦点闸门、把焦点扶回去），
        //    挂在 onCancel 上等于「凡是点按钮关的窗口，收尾都不执行」——
        //    真机表现就是「设置菜单里点过东西之后，顶栏焦点不再跟随切页，得再按一次确认」。
        dialog.setOnDismissListener { onDismiss?.invoke() }

        // 窗口背景透明：只留自绘面板，系统那个九宫格边框不要
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // ⚠️ 对话框是独立窗口，弹出时 Activity 失焦、系统栏会**重新冒出来**
        //    （底部那排返回/主页/任务）。Activity 那边的 onWindowFocusChanged 管不到它，
        //    只能在对话框自己的窗口上再收一次。
        dialog.window?.let { w ->
            w.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LOW_PROFILE
        }
        // 宽度固定（要在 show() 之后设置，否则不生效）
        val width = (560 * dialog.context.resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        // 登记到「还有窗口开着吗」里，供嵌套弹窗的收尾判断（见 PopupGate）
        PopupGate.track(dialog)

        // 焦点：优先给指定视图，否则给主按钮，再否则给第一个按钮
        val target = initialFocus
            ?: buttonViews.firstOrNull { buttons[buttonViews.indexOf(it)].primary }
            ?: buttonViews.firstOrNull()

        // 可滚内容与按钮行之间的「上下互通」：
        // 内容区滚到底再按 ↓ → 落到按钮行；按钮行按 ↑ → 回到内容区顶部。
        // 不做这一步的话，焦点进了可滚区就出不来（4.3 上 ScrollView 的焦点搜索
        // 不会自动跨到兄弟视图），用户只能按返回把整个对话框关掉。
        if (scrollable && content != null) {
            val sv = box.getChildAt(0) as? ScrollView
            if (sv != null) {
                sv.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !sv.canScrollVertically(1)) {
                        buttonViews.firstOrNull()?.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                val firstBtn = buttonViews.firstOrNull()
                if (firstBtn != null) {
                    firstBtn.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            sv.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }

        target?.requestFocus()
        return dialog
    }

    /**
     * 把内容包进一个**限高可滚**的 ScrollView。
     *
     * 为什么需要：在线更新的说明、以后可能加的更新日志会越来越长，
     * 而对话框宽度固定 560dp、内容区还各留 24dp 内边距，长文本会把面板顶出屏幕
     * （4.3 电视 720dp 高，标题 + 按钮就吃掉 120dp 左右）。
     * 系统安装器的做法就是「内容自己滚、按钮钉在下面」，这里照抄。
     *
     * ⚠️ 高度必须**先量再定**：`ScrollView` 没有 `maxHeight` 这种属性，
     *    只能自己 measure 一次内容、取 `min(实测高度, 上限)` 当高度。
     *    量的时候宽度要给 `EXACTLY`（面板 560dp - 左右各 24dp = 512dp），
     *    否则 `TextView` 会按「一行放得下」去量，高度算出来只有一行。
     *
     * ⚠️ ScrollView 要 `isFocusable = true`：默认它自己不吃焦点，
     *    焦点会直接跳过内容区落到按钮上 —— 那内容再长也滚不动。
     */
    private fun scrollableContent(ctx: Context, content: View, maxHeightDp: Int): View {
        val density = ctx.resources.displayMetrics.density
        val widthPx = (512 * density).toInt()
        val maxPx = (maxHeightDp * density).toInt()

        content.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val heightPx = min(content.measuredHeight, maxPx)

        val sv = ScrollView(ctx).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // 内容比上限矮时不显示滚动条（否则短文本旁边挂一条灰杠，看着像没加载完）
            isVerticalScrollBarEnabled = content.measuredHeight > maxPx
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, heightPx)
        return sv
    }
}
