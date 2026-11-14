/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import org.lineageos.twelve.R

/**
 * A big item of a carousel: the artwork fills it and the text sits on top of it.
 */
class HeroMediaItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : BaseMediaItemView(context, attrs, defStyleAttr, R.layout.hero_media_item_view) {
    init {
        radius = 32 * resources.displayMetrics.density
        setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
}
