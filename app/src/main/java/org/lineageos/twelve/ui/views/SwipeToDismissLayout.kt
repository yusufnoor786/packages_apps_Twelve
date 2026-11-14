/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * A layout that follows a downward drag and, when released far enough or fast enough, slides
 * away and calls [onDismissed]. Used to minimize the player to the mini player.
 *
 * The content moves with the finger, shrinks a little and fades. On release it settles on a
 * critically damped spring: it leaves briskly and then spends its time easing into place.
 */
class SwipeToDismissLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    /**
     * Called once the layout slid out of the screen.
     */
    var onDismissed: (() -> Unit)? = null

    /**
     * Whether a drag that starts with this touch may dismiss the layout. Lets scrollable
     * content keep its own vertical gestures.
     */
    var canStartDrag: (MotionEvent) -> Boolean = { true }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    private var downX = 0f
    private var downY = 0f
    private var dragStartY = 0f
    private var tracking = false
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null
    private var animation: SpringAnimation? = null
    private var dismissing = false

    private val offsetProperty = object : FloatPropertyCompat<SwipeToDismissLayout>("offset") {
        override fun getValue(layout: SwipeToDismissLayout) = layout.translationY

        override fun setValue(layout: SwipeToDismissLayout, value: Float) {
            layout.applyOffset(value)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dismissing) {
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animation?.cancel()
                downX = ev.x
                downY = ev.y
                dragging = false
                tracking = canStartDrag(ev)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                maybeStartDrag(ev)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    reset()
                }
            }
        }

        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dismissing) {
            return true
        }

        when (ev.actionMasked) {
            // Nobody below wanted the touch: it is ours
            MotionEvent.ACTION_DOWN -> return tracking

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                maybeStartDrag(ev)

                if (dragging) {
                    applyOffset((ev.y - dragStartY).coerceAtLeast(0f))
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // The finger may have moved since the last move event
                    applyOffset((ev.y - dragStartY).coerceAtLeast(0f))
                    velocityTracker?.addMovement(ev)
                    velocityTracker?.computeCurrentVelocity(1000)
                    release(velocityTracker?.yVelocity ?: 0f)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    release(0f)
                }
            }
        }

        return dragging
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept)

        // A child took the gesture, e.g. the seek bar
        if (disallowIntercept && !dragging) {
            tracking = false
        }
    }

    private fun maybeStartDrag(ev: MotionEvent) {
        if (!tracking || dragging) {
            return
        }

        val dy = ev.y - downY
        val dx = ev.x - downX

        if (dy > touchSlop && dy > abs(dx) * HORIZONTAL_TOLERANCE) {
            dragging = true
            dragStartY = ev.y
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private fun applyOffset(offset: Float) {
        val progress = (offset / height.coerceAtLeast(1)).coerceIn(0f, 1f)

        translationY = offset
        scaleX = 1f - progress * SHRINK
        scaleY = 1f - progress * SHRINK
        alpha = 1f - progress * FADE
    }

    private fun release(velocity: Float) {
        val dismiss = translationY > height * DISMISS_FRACTION
                || (velocity > FLING_VELOCITY && velocity > minimumFlingVelocity)

        val target = when (dismiss) {
            true -> height.toFloat()
            false -> 0f
        }

        dismissing = dismiss
        dragging = false
        tracking = false
        velocityTracker?.recycle()
        velocityTracker = null

        animation = SpringAnimation(this, offsetProperty, target).apply {
            spring = SpringForce(target).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = STIFFNESS
            }
            setStartVelocity(velocity.coerceAtLeast(0f))
            setMinimumVisibleChange(SETTLED_PX)
            addEndListener { _, canceled, _, _ ->
                if (!canceled && dismiss) {
                    onDismissed?.invoke()
                } else if (!dismiss) {
                    dismissing = false
                }
            }
            start()
        }
    }

    private fun reset() {
        tracking = false
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null

        super.onDetachedFromWindow()
    }

    companion object {
        private const val HORIZONTAL_TOLERANCE = 1.2f
        private const val DISMISS_FRACTION = 0.22f
        private const val FLING_VELOCITY = 1400f
        private const val STIFFNESS = 200f
        private const val SETTLED_PX = 0.5f
        private const val SHRINK = 0.1f
        private const val FADE = 0.45f
    }
}
