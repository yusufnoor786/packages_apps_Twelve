/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * A scroll view that grows with its content up to [maxHeightFraction] of the screen, then
 * scrolls: what comes below it in a dialog stays on screen.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    var maxHeightFraction = DEFAULT_MAX_HEIGHT_FRACTION
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val limit = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> maxHeight
            else -> minOf(available, maxHeight)
        }

        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    private companion object {
        private const val DEFAULT_MAX_HEIGHT_FRACTION = 0.3f
    }
}
