/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.lineageos.twelve.R
import org.lineageos.twelve.ui.expressive.ShapeMaskLayout
import org.lineageos.twelve.ui.expressive.ExpressiveShape

/**
 * An artist: a round avatar with the name below it.
 */
class ArtistCircleItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : BaseMediaItemView(context, attrs, defStyleAttr, R.layout.artist_circle_item) {
    init {
        setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    fun setShape(shape: ExpressiveShape) {
        findViewById<ShapeMaskLayout>(R.id.shapeMaskLayout).shape = shape
    }
}

/**
 * A genre: a tile in one of the colors of the theme, the same genre always has the same one.
 */
class GenreTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(
    context, attrs, com.google.android.material.R.attr.materialCardViewFilledStyle
) {
    private val nameTextView by lazy { findViewById<TextView>(R.id.genreNameTextView) }
    private val iconImageView by lazy { findViewById<ImageView>(R.id.genreIconImageView) }

    init {
        inflate(context, R.layout.genre_tile_item, this)

        radius = 28 * resources.displayMetrics.density
        cardElevation = 0f
        strokeWidth = 0
    }

    fun setGenre(name: String?) {
        nameTextView.text = name ?: context.getString(R.string.unknown)

        val (container, onContainer) = PALETTE[(name?.hashCode() ?: 0).mod(PALETTE.size)]
        setCardBackgroundColor(MaterialColors.getColor(this, container))
        val foreground = MaterialColors.getColor(this, onContainer)
        nameTextView.setTextColor(foreground)
        iconImageView.setImageDrawable(ExpressiveShape.forKey(name).drawable())
        iconImageView.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
    }

    private companion object {
        private val PALETTE = listOf(
            com.google.android.material.R.attr.colorPrimaryContainer to
                    com.google.android.material.R.attr.colorOnPrimaryContainer,
            com.google.android.material.R.attr.colorSecondaryContainer to
                    com.google.android.material.R.attr.colorOnSecondaryContainer,
            com.google.android.material.R.attr.colorTertiaryContainer to
                    com.google.android.material.R.attr.colorOnTertiaryContainer,
            com.google.android.material.R.attr.colorSurfaceContainerHighest to
                    com.google.android.material.R.attr.colorOnSurface,
        )
    }
}
