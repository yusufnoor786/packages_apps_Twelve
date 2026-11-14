/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import org.lineageos.twelve.R

class HorizontalMediaItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : BaseMediaItemView(context, attrs, defStyleAttr, R.layout.horizontal_media_item_view) {
    init {
        // The card is the layout itself, the view around it only holds the margins
        setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        radius = 32 * resources.displayMetrics.density
        cardElevation = 0f
        strokeWidth = 0
    }
}
