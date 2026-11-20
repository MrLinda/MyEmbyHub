package work.luxiaoxiao.myembyhub.ui

import android.app.Dialog
import java.util.WeakHashMap

/**
 * 「现在还有没有弹窗开着」。
 *
 * ## 为什么需要它
 *
 * `HomeActivity.suppressTabSwitch` 是顶栏「焦点即切换」的闸门：弹窗开着时不能让焦点
 * 落到 tab 上，否则一关窗系统把焦点塞给第一个 tab，页面就被拽走了。
 *
 * 闸门的放开动作挂在弹窗的关闭回调里（[work.luxiaoxiao.myembyhub.ui.HomeActivity.restoreFocusAfterDialog]），
 * 而弹窗是**可以套娃的**：设置菜单 →「体验优化计划」→ 子菜单 → 确认框。
 * 内层关掉时外层可能还开着 —— 这时若直接放开闸门 + 把焦点拽回内容区，
 * 用户刚打开的那个对话框会立刻失去焦点，看着像「按了没反应」。
 *
 * ## 为什么用弱引用而不是计数器
 *
 * 计数器一旦漏减（Activity 被回收、dialog 没走 dismiss、异常打断）就**永久卡在 >0**，
 * 表现为「焦点即切换」再也不工作 —— 那正是这个类要修的病。
 * 弱引用表每次 [anyOpen] 都顺手清掉已经不在显示的窗口，自己会好。
 */
internal object PopupGate {

    /** 只用来回答「还开着吗」，值是占位。 */
    private val live = WeakHashMap<Dialog, Boolean>()

    /** 弹窗 [show] 之后登记。 */
    fun track(dialog: Dialog) {
        live[dialog] = true
    }

    /**
     * 还有没有正在显示的弹窗。
     *
     * 顺带清理已关闭的条目，所以调用次数越多表越干净。
     */
    fun anyOpen(): Boolean {
        var any = false
        val it = live.keys.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (d.isShowing) any = true else it.remove()
        }
        return any
    }
}
