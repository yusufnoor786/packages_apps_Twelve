/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import com.google.android.material.color.MaterialColors
import org.lineageos.twelve.R

/**
 * A square cover with big rounded corners, the text below it. Lives on a colored container.
 */
class FeaturedMediaItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : BaseMediaItemView(
    context,
    attrs,
    com.google.android.material.R.attr.materialCardViewStyle,
    R.layout.featured_media_item_view,
) {
    init {
        setCardBackgroundColor(Color.TRANSPARENT)
    }
}

/**
 * A circular cover with the text centered below it.
 */
class RoundMediaItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : BaseMediaItemView(
    context,
    attrs,
    com.google.android.material.R.attr.materialCardViewStyle,
    R.layout.round_media_item_view,
) {
    init {
        setCardBackgroundColor(Color.TRANSPARENT)
    }
}

/**
 * A wide tile: a small cover and the text next to it, on a tonal surface.
 */
class TileMediaItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : BaseMediaItemView(
    context,
    attrs,
    com.google.android.material.R.attr.materialCardViewStyle,
    R.layout.tile_media_item_view,
) {
    init {
        radius = 28 * resources.displayMetrics.density
        setCardBackgroundColor(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
            )
        )
    }
}
