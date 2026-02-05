package com.simats.criticall.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.simats.criticall.R
import kotlin.math.min
import kotlin.math.sin

class TypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.gray_light)
        style = Paint.Style.FILL
    }
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            try {
                start()
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(w / 10f, h / 2.8f)
        val centerY = h / 2f
        val gap = radius * 2.2f
        val startX = (w - gap * 2f) / 2f
        for (i in 0..2) {
            val offset = i * 0.22f
            val wave = (sin((phase + offset) * Math.PI * 2.0) + 1.0) / 2.0
            paint.alpha = (80 + 175 * wave).toInt().coerceIn(40, 255)
            canvas.drawCircle(startX + gap * i, centerY, radius, paint)
        }
    }
}
