/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.google.android.material.loadingindicator.LoadingIndicator
import kotlin.math.abs
import kotlin.math.min

/**
 * Pulling the content down while it is at the top reloads it. The Material 3 Expressive loading
 * indicator grows in the gap that opens above the content and keeps spinning until the work is
 * done.
 *
 * The first child is the content, it has to be a scrolling view.
 */
class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    /**
     * Called when the user pulled far enough. The indicator keeps spinning until
     * [finishRefresh] is called, or for a few seconds at most.
     */
    var onRefresh: (() -> Unit)? = null

    /**
     * The new content arrived. The indicator stays for a moment at least, so that it doesn't just
     * flash.
     */
    fun finishRefresh() {
        if (!isRefreshing) {
            return
        }

        val shown = SystemClock.uptimeMillis() - refreshStartedAt
        handler.removeCallbacks(stopRefreshing)
        handler.postDelayed(stopRefreshing, (MIN_REFRESH_MS - shown).coerceAtLeast(0L))
    }

    private var refreshStartedAt = 0L
    private val stopRefreshing = Runnable { isRefreshing = false }

    var isRefreshing = false
        set(value) {
            if (field == value) {
                return
            }

            field = value
            when (value) {
                true -> {
                    animateTo(holdOffset)
                    indicator.visibility = VISIBLE
                }

                false -> animateTo(0f) { indicator.visibility = INVISIBLE }
            }
        }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val indicatorSize = 48 * density
    private val triggerOffset = 84 * density
    private val holdOffset = 72 * density
    private val maxOffset = 150 * density

    private val indicator = LoadingIndicator(context).apply {
        layoutParams = LayoutParams(indicatorSize.toInt(), indicatorSize.toInt()).also {
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        visibility = INVISIBLE
    }

    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var tracking = false
    private var reachedTrigger = false
    private var offset = 0f
    private var animator: ValueAnimator? = null

    private val content get() = getChildAt(0)

    override fun onFinishInflate() {
        super.onFinishInflate()

        addView(indicator)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                pulling = false
                tracking = !isRefreshing && content?.canScrollVertically(-1) == false
                animator?.cancel()
            }

            MotionEvent.ACTION_MOVE -> if (tracking && !pulling) {
                val dy = ev.rawY - downY
                if (dy > touchSlop && dy > abs(ev.rawX - downX) * 1.2f) {
                    pulling = true
                    downY = ev.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                pulling = false
            }
        }

        return pulling
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return tracking

            MotionEvent.ACTION_MOVE -> {
                if (!pulling) {
                    return tracking
                }

                // Pulling gets harder the further it goes
                val travel = (ev.rawY - downY).coerceAtLeast(0f)
                val pull = min(maxOffset, travel * 0.5f)
                applyOffset(pull)

                val reached = pull >= triggerOffset
                if (reached && !reachedTrigger) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                reachedTrigger = reached
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    tracking = false

                    when {
                        ev.actionMasked == MotionEvent.ACTION_UP && offset >= triggerOffset -> {
                            isRefreshing = true
                            refreshStartedAt = SystemClock.uptimeMillis()
                            handler.removeCallbacks(stopRefreshing)
                            handler.postDelayed(stopRefreshing, MAX_REFRESH_MS)
                            onRefresh?.invoke()
                        }

                        else -> animateTo(0f) { indicator.visibility = INVISIBLE }
                    }
                }

                reachedTrigger = false
            }
        }

        return pulling || tracking
    }

    private fun applyOffset(newOffset: Float) {
        offset = newOffset

        content?.translationY = newOffset

        // The indicator lives in the gap above the content
        val progress = (newOffset / triggerOffset).coerceIn(0f, 1f)
        if (indicator.visibility != VISIBLE && newOffset > 0f) {
            indicator.visibility = VISIBLE
        }
        indicator.translationY = (newOffset - indicatorSize) / 2f
        indicator.alpha = progress
        indicator.scaleX = 0.4f + 0.6f * progress
        indicator.scaleY = 0.4f + 0.6f * progress
    }

    private fun animateTo(target: Float, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = SETTLE_MS
            interpolator = when (target > offset) {
                true -> OvershootInterpolator(1.2f)
                false -> DecelerateInterpolator(1.6f)
            }
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (offset <= 0f) {
                        onEnd?.invoke()
                    }
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        handler.removeCallbacks(stopRefreshing)

        super.onDetachedFromWindow()
    }

    companion object {
        private const val SETTLE_MS = 320L
        private const val MIN_REFRESH_MS = 900L
        private const val MAX_REFRESH_MS = 8000L
    }
}
