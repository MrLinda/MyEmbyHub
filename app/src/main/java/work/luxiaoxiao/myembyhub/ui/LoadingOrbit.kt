package work.luxiaoxiao.myembyhub.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import work.luxiaoxiao.myembyhub.R

/**
 * 加载动画：一圈细轨道 + 一段暖色圆弧绕圈跑。
 *
 * 为什么不用系统的 `ProgressBar`：
 * ① 4.3 的 `progressBarStyleLarge` 是 Holo 青色转圈，跟这套暖色 + 深底的语言完全两套；
 * ② 系统 spinner 在低端机上是 12 张帧图轮播，这里一笔弧线按角度重绘，开销小得多
 *    （S50 Air 只有 96MB 堆、1GHz，能省就省）；
 * ③ 颜色要跟令牌走 —— 换皮肤时它自动跟着 `indicator_bar` / `stroke_subtle` 变。
 *
 * ⚠️ 动画生命周期必须自己管：`ValueAnimator` 不会因为 View 变 `GONE` 就停，
 *    一直跑就是一直 `invalidate()`，电视上白烧 CPU。所以 `onVisibilityChanged`
 *    和 `onDetachedFromWindow` 里都要停。
 */
class LoadingOrbit @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 轨道：一整圈暗线，让「转」有个参照物，否则只有一段弧在飞，看着像在闪。 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.stroke_subtle)
    }

    /** 跑动的那段弧。 */
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.indicator_bar)
    }

    private val oval = RectF()

    /** 当前旋转角度。 */
    private var angle = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 3f * density
        val half = stroke / 2f
        // 留出半个笔宽，否则描边会被 View 边界裁掉一半，看着是「扁的环」
        oval.set(half, half, width - half, height - half)
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)
        canvas.drawArc(oval, angle, SWEEP_DEGREES, false, arcPaint)
    }

    private fun startIfVisible() {
        if (visibility == VISIBLE && !animator.isStarted) animator.start()
    }

    private fun stop() {
        if (animator.isStarted) animator.cancel()
        angle = 0f
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startIfVisible() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startIfVisible()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 弧长：太长像进度条（会让人以为有百分比），太短像卡顿，100° 刚好。 */
        const val SWEEP_DEGREES = 100f
    }
}
