/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.view.ViewGroup
import com.google.android.material.carousel.MaskableFrameLayout
import org.lineageos.twelve.models.MediaItem

/**
 * The root a carousel needs for its items, it masks them as they slide in and out of the screen.
 *
 * @param hero Whether the item is a big one that shows the artwork edge to edge
 */
class CarouselMediaItemView(
    context: Context,
    hero: Boolean,
) : MaskableFrameLayout(context) {
    private val mediaItemView: BaseMediaItemView = when (hero) {
        true -> HeroMediaItemView(context)
        false -> HorizontalMediaItemView(context)
    }

    init {
        addView(
            mediaItemView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    fun setItem(item: MediaItem<*>) = mediaItemView.setItem(item)
}
