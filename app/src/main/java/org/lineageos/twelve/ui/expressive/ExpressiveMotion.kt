/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.expressive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.PathInterpolator
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.RelativeCornerSize
import org.lineageos.twelve.R

/**
 * The emphasized easing of Material 3 Expressive.
 */
val emphasizedInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

/**
 * Round the corners of the button: a circle while paused, a squircle while playing, the way the
 * Material 3 Expressive media buttons tell their state with their shape.
 */
fun MaterialButton.morphForPlaying(isPlaying: Boolean, animate: Boolean = true) {
    val target = if (isPlaying) 0.3f else 0.5f
    val from = (getTag(R.id.tag_corner_animator) as? ValueAnimator)?.let {
        it.cancel()
        it.animatedValue as Float
    } ?: (getTag(R.id.tag_corner_fraction) as? Float) ?: 0.5f

    fun apply(fraction: Float) {
        setTag(R.id.tag_corner_fraction, fraction)
        shapeAppearanceModel = shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(RelativeCornerSize(fraction))
            .build()
    }

    if (!animate || from == target) {
        apply(target)
        return
    }

    setTag(R.id.tag_corner_animator, ValueAnimator.ofFloat(from, target).apply {
        duration = 450
        interpolator = emphasizedInterpolator
        addUpdateListener { apply(it.animatedValue as Float) }
        start()
    })
}

/**
 * A Material shape filling its bounds, in the tint color.
 */
class ShapePathDrawable(private val shape: ExpressiveShape) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var tint: android.content.res.ColorStateList? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) {
            return
        }
        paint.color = tint?.getColorForState(state, tint!!.defaultColor) ?: android.graphics.Color.BLACK
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawPath(shape.pathIn(b.width().toFloat(), b.height().toFloat(), path), paint)
        canvas.restore()
    }

    override fun setTintList(tint: android.content.res.ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun isStateful() = tint?.isStateful == true

    override fun onStateChange(state: IntArray) = isStateful.also { if (it) invalidateSelf() }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
