/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.expressive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.graphics.shapes.Morph
import org.lineageos.twelve.R

/**
 * A container that cuts what it holds to one of the expressive shapes (a cookie, a clover, a
 * flower...). When the view it belongs to is pressed it can morph into another shape, the way
 * Material 3 Expressive gives feedback.
 */
class ShapeMaskLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var shape: ExpressiveShape = ExpressiveShape.COOKIE_9
        set(value) {
            if (field == value) {
                return
            }
            field = value
            morph = null
            invalidatePath()
        }

    /**
     * The shape it becomes while pressed, null to keep the same shape.
     */
    var pressedShape: ExpressiveShape? = ExpressiveShape.CIRCLE
        set(value) {
            field = value
            morph = null
            invalidatePath()
        }

    private var morph: Morph? = null
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var wasPressed = false

    private val path = Path()
    private var pathWidth = -1
    private var pathHeight = -1
    private var pathProgress = -1f

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.ShapeMaskLayout).apply {
            try {
                shape = ExpressiveShape.entries[
                    getInt(R.styleable.ShapeMaskLayout_maskShape, ExpressiveShape.COOKIE_9.ordinal)
                ]
                pressedShape = when (val index = getInt(R.styleable.ShapeMaskLayout_pressedShape, -2)) {
                    -2 -> ExpressiveShape.CIRCLE
                    -1 -> null
                    else -> ExpressiveShape.entries[index]
                }
            } finally {
                recycle()
            }
        }

        setWillNotDraw(false)

        // The masked content lives in its own layer: drawn again only when it changes, a
        // scroll just moves it
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun invalidatePath() {
        pathWidth = -1
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()

        // The pressed state comes from the view this belongs to
        val pressed = isPressed || (parent as? android.view.View)?.isPressed == true
        if (pressed != wasPressed) {
            wasPressed = pressed
            animateTo(if (pressed) 1f else 0f)
        }
    }

    private fun animateTo(target: Float) {
        if (pressedShape == null) {
            return
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = MORPH_MS
            interpolator = EMPHASIZED
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun currentPath(): Path {
        if (pathWidth == width && pathHeight == height && pathProgress == progress) {
            return path
        }

        pathWidth = width
        pathHeight = height
        pathProgress = progress

        val target = pressedShape
        when {
            target == null || progress <= 0f -> shape.pathIn(width.toFloat(), height.toFloat(), path)
            else -> {
                val currentMorph = morph ?: Morph(shape.polygon, target.polygon).also { morph = it }
                currentMorph.pathIn(progress, width.toFloat(), height.toFloat(), path)
            }
        }

        // Filled outside the shape: that is what gets erased
        path.fillType = Path.FillType.INVERSE_WINDING

        return path
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width == 0 || height == 0) {
            return
        }

        // The children are drawn, then everything outside the shape is erased, with soft edges
        super.dispatchDraw(canvas)
        canvas.drawPath(currentPath(), maskPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        private const val MORPH_MS = 350L

        // The emphasized easing of Material: quick start, long gentle landing
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
