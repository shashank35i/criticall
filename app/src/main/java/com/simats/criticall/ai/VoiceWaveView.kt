package com.simats.criticall.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.simats.criticall.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_500)
        style = Paint.Style.FILL
    }
    private val bar = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private val offsets = floatArrayOf(0f, 0.12f, 0.24f, 0.36f, 0.48f)

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 780L
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
        val barCount = offsets.size
        val barWidth = w / (barCount * 2f)
        val gap = barWidth
        val maxBar = h * 0.9f
        val minBar = h * 0.25f
        for (i in 0 until barCount) {
            val wave = abs(sin((phase + offsets[i]) * Math.PI * 2.0)).toFloat()
            val height = minBar + (maxBar - minBar) * wave
            val left = i * (barWidth + gap)
            val top = (h - height) / 2f
            bar.set(left, top, left + barWidth, top + height)
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint)
        }
    }
}
