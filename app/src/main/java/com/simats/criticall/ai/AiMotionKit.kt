package com.simats.criticall.ai

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.facebook.shimmer.ShimmerFrameLayout
import java.util.WeakHashMap
import kotlin.math.min

object AiMotionKit {
    private val streamAnimators = WeakHashMap<TextView, ValueAnimator>()

    @JvmStatic
    fun startListening(view: View?) {
        val wave = findFirst(view, VoiceWaveView::class.java)
        wave?.visibility = View.VISIBLE
        try {
            wave?.start()
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun startThinking(view: View?) {
        val dots = findFirst(view, TypingDotsView::class.java)
        dots?.visibility = View.VISIBLE
        try {
            dots?.start()
        } catch (_: Throwable) {
        }
        val shimmer = findFirst(view, ShimmerFrameLayout::class.java)
        shimmer?.visibility = View.VISIBLE
        try {
            shimmer?.startShimmer()
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun startStreamingText(textView: TextView?, fullText: String?) {
        if (textView == null) return
        val text = fullText ?: ""
        streamAnimators.remove(textView)?.cancel()
        if (text.isEmpty()) {
            textView.text = ""
            return
        }
        val animator = ValueAnimator.ofInt(0, text.length)
        animator.duration = min(1400L, 24L * text.length + 220L)
        animator.addUpdateListener {
            val end = (it.animatedValue as Int).coerceAtMost(text.length)
            textView.text = text.substring(0, end)
        }
        try {
            animator.start()
        } catch (_: Throwable) {
            textView.text = text
        }
        streamAnimators[textView] = animator
    }

    @JvmStatic
    fun animateChips(container: ViewGroup?) {
        if (container == null) return
        val density = container.resources.displayMetrics.density
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.translationY = 10f * density
            child.alpha = 0f
            child.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(i * 100L)
                .setDuration(240L)
                .start()
        }
    }

    @JvmStatic
    fun stopAllIn(root: View?) {
        if (root == null) return
        val wave = findFirst(root, VoiceWaveView::class.java)
        try {
            wave?.stop()
        } catch (_: Throwable) {
        }
        val dots = findFirst(root, TypingDotsView::class.java)
        try {
            dots?.stop()
        } catch (_: Throwable) {
        }
        val shimmer = findFirst(root, ShimmerFrameLayout::class.java)
        try {
            shimmer?.stopShimmer()
        } catch (_: Throwable) {
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                stopAllIn(root.getChildAt(i))
            }
        }
        val iterator = streamAnimators.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isDescendant(entry.key, root)) {
                entry.value.cancel()
                iterator.remove()
            }
        }
    }

    private fun <T : View> findFirst(root: View?, type: Class<T>): T? {
        if (root == null) return null
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirst(root.getChildAt(i), type)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isDescendant(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return false
    }
}
