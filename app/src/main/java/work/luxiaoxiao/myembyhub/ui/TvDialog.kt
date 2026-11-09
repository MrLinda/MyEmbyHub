package work.luxiaoxiao.myembyhub.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import work.luxiaoxiao.myembyhub.R

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

    /** 纯提示对话框：标题 + 消息 + 一个「好」。 */
    fun message(
        ctx: Context,
        title: String,
        message: String,
        confirmText: String = "好",
        onConfirm: (() -> Unit)? = null
    ): Dialog {
        val tv = TextView(ctx).apply {
            text = message
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        return show(ctx, title, tv, listOf(Btn(confirmText, primary = true) {
            onConfirm?.invoke()
        }))
    }

    /** 二次确认对话框：取消 + 主按钮。 */
    fun confirm(
        ctx: Context,
        title: String,
        message: String,
        confirmText: String = "确定",
        cancelText: String = "取消",
        onConfirm: () -> Unit
    ): Dialog {
        val tv = TextView(ctx).apply {
            text = message
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        return show(ctx, title, tv, listOf(
            Btn(cancelText),
            Btn(confirmText, primary = true) { onConfirm() }
        ))
    }

    /**
     * 自定义内容对话框。
     *
     * @param content 内容视图（放进面板的内容区）
     * @param buttons 按钮（空 = 没有按钮行）
     * @param initialFocus 打开后先拿到焦点的视图（比如输入框）；不给就落在主按钮上
     * @param cancelable 是否允许「返回」关闭（下载进度框要 false）
     * @param onDismiss 关闭时的回调（返回键关闭也会走）
     */
    fun show(
        ctx: Context,
        title: String,
        content: View?,
        buttons: List<Btn>,
        initialFocus: View? = null,
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(ctx, R.style.Theme_MyEmbyHub_Dialog)
        // 自己画标题，系统那个不要
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LayoutInflater.from(ctx).inflate(R.layout.tv_dialog, null, false)
        dialog.setContentView(root)
        root.findViewById<TextView>(R.id.tv_dialog_title).text = title

        val box = root.findViewById<FrameLayout>(R.id.dlg_content)
        if (content != null) box.addView(content)

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
        dialog.setOnCancelListener { onDismiss?.invoke() }

        // 窗口背景透明：只留自绘面板，系统那个九宫格边框不要
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 宽度固定（要在 show() 之后设置，否则不生效）
        val width = (560 * dialog.context.resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        // 焦点：优先给指定视图，否则给主按钮，再否则给第一个按钮
        val target = initialFocus
            ?: buttonViews.firstOrNull { buttons[buttonViews.indexOf(it)].primary }
            ?: buttonViews.firstOrNull()
        target?.requestFocus()
        return dialog
    }
}
