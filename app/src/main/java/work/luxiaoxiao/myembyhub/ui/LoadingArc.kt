package work.luxiaoxiao.myembyhub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import work.luxiaoxiao.myembyhub.R

/**
 * 「正在检查更新」用的转圈弧。
 *
 * 为什么自己画、不用 `GradientDrawable`（`<shape android:shape="ring">` + `type="sweep"`）：
 * 4.3 的 GradientDrawable 对 **ring + sweep 渐变**渲染是坏的 —— 真机上那圈不是平滑拖尾，
 * 而是**四段方形的色块跟着转**（sweep 渐变的 shader 在非矩形的环形 path 上采样错位）。
 * 这是系统绘制的问题，不是资源写错，换参数也救不回来。
 *
 * 用 Canvas.drawArc 画一段带缺口的弧：缺口让它「转得看得见」（整圆旋转在视觉上等于不动），
 * 弧线和圆头端点是 Canvas 的基础能力，4.3 到最新系统表现一致。
 *
 * 底下一圈极淡的完整圆环：没有它的话，弧转到某些角度时看起来像「一根棍子」，
 * 有了它才看得出这是个转圈指示器。
 *
 * 旋转不在这个类里做：由调用方用 ValueAnimator 改 `rotation`（View 属性动画，API 11 起）。
 * 这样转不转、转多快都是那一处在管，跟画法解耦。
 */
class LoadingArc @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_primary)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = ContextCompat.getColor(context, R.color.accent_container)
    }

    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = arcPaint.strokeWidth / 2f
        oval.set(inset, inset, width - inset, height - inset)

        // 底环：完整一圈，很淡
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)
        // 主弧：留 80° 缺口，从 12 点方向开始
        canvas.drawArc(oval, -90f, 280f, false, arcPaint)
    }
}
