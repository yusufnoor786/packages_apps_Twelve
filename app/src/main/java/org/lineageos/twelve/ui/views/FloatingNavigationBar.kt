/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.animation.LayoutTransition
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.lineageos.twelve.R
import org.lineageos.twelve.ui.expressive.emphasizedInterpolator
import org.lineageos.twelve.viewmodels.MainNavigationViewModel.Destination

/**
 * The navigation bar of the app, a pill floating above the content: the place shown is a filled
 * pill with its name, the others only an icon.
 */
class FloatingNavigationBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {
    private class Item(
        val destination: Destination,
        @DrawableRes val icon: Int,
        @StringRes val label: Int,
    )

    private val items = listOf(
        Item(Destination.HOME, R.drawable.ic_home, R.string.main_fragment_section_activity),
        Item(Destination.SEARCH, R.drawable.ic_search, R.string.main_fragment_section_search),
        Item(
            Destination.LIBRARY, R.drawable.ic_lineage_library, R.string.main_fragment_section_library
        ),
        Item(
            Destination.PLAYLISTS, R.drawable.ic_playlist_play,
            R.string.library_fragment_menu_playlists
        ),
    )

    private val density = resources.displayMetrics.density
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padding = (PADDING_DP * density).toInt()
        setPadding(padding, padding, padding, padding)
    }

    // Dark and tinted with the accent like the mini player, in light and in dark mode: it stands
    // out on any page, light lists as well as colored shelves
    private val barBackground = androidx.core.graphics.ColorUtils.blendARGB(
        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary),
        android.graphics.Color.BLACK,
        BACKGROUND_DARKNESS,
    )
    private val selectedForeground = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnPrimaryFixed
    )
    private val foreground = androidx.core.graphics.ColorUtils.setAlphaComponent(
        android.graphics.Color.WHITE, 0xD9
    )
    private val selectedBackground = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimaryFixed
    )

    private val pills = mutableMapOf<Destination, GradientDrawable>()
    private val views = items.associate { item -> item.destination to itemView(item) }
    private var selected: Destination? = null

    /**
     * Called when a place of the bar is tapped, also when it is the one shown.
     */
    var onDestinationSelected: (Destination) -> Unit = {}

    init {
        radius = HEIGHT_DP * density / 2
        cardElevation = ELEVATION_DP * density
        strokeWidth = density.toInt().coerceAtLeast(1)
        strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
            android.graphics.Color.WHITE, 0x29
        )
        setCardBackgroundColor(barBackground)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, (HEIGHT_DP * density).toInt()))
        views.values.forEach { row.addView(it) }
        select(Destination.HOME, animate = false)

        // The pills grow and shrink as labels come and go; a layout transition picks up from
        // where it is when the next tap comes, and always ends on the real sizes
        row.layoutTransition = sizeTransition()
        views.values.forEach { (it as LinearLayout).layoutTransition = sizeTransition() }
    }

    private fun sizeTransition() = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setDuration(ANIMATION_MS)
        listOf(
            LayoutTransition.CHANGING,
            LayoutTransition.CHANGE_APPEARING,
            LayoutTransition.CHANGE_DISAPPEARING,
        ).forEach { setInterpolator(it, emphasizedInterpolator) }
        // Labels show at once, only the sizes move
        setAnimator(LayoutTransition.APPEARING, null)
        setAnimator(LayoutTransition.DISAPPEARING, null)
    }

    /**
     * Show [destination] as the place the app is on.
     */
    fun select(destination: Destination, animate: Boolean = true) {
        if (destination == selected) {
            return
        }
        selected = destination

        val transitions = listOf(row) + views.values.map { it as LinearLayout }
        val saved = transitions.map { it.layoutTransition }
        if (!animate) {
            transitions.forEach { it.layoutTransition = null }
        }

        views.forEach { (itemDestination, view) ->
            val isSelected = itemDestination == destination
            view.isSelected = isSelected
            pills.getValue(itemDestination).setColor(
                if (isSelected) selectedBackground else android.graphics.Color.TRANSPARENT
            )
            val tint = ColorStateList.valueOf(if (isSelected) selectedForeground else foreground)
            view.findViewById<ImageView>(R.id.floatingNavigationIcon).imageTintList = tint
            view.findViewById<TextView>(R.id.floatingNavigationLabel).apply {
                setTextColor(tint)
                isVisible = isSelected
            }
        }

        if (!animate) {
            transitions.zip(saved).forEach { (view, transition) -> view.layoutTransition = transition }
        }
    }

    private fun itemView(item: Item): View {
        val itemHeight = ((HEIGHT_DP - 2 * PADDING_DP) * density).toInt()
        val horizontalPadding = (ITEM_PADDING_DP * density).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            minimumWidth = (MIN_ITEM_WIDTH_DP * density).toInt()
            val pill = GradientDrawable().apply { cornerRadius = itemHeight / 2f }
            pills[item.destination] = pill
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        android.graphics.Color.WHITE, 0x33
                    )
                ),
                pill,
                GradientDrawable().apply {
                    cornerRadius = itemHeight / 2f
                    setColor(android.graphics.Color.WHITE)
                },
            )
            contentDescription = context.getString(item.label)
            isClickable = true
            isFocusable = true
            setOnClickListener { onDestinationSelected(item.destination) }

            addView(
                ImageView(context).apply {
                    id = R.id.floatingNavigationIcon
                    setImageResource(item.icon)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams((ICON_DP * density).toInt(), (ICON_DP * density).toInt()),
            )
            addView(
                TextView(context).apply {
                    id = R.id.floatingNavigationLabel
                    setText(item.label)
                    setTextAppearance(
                        com.google.android.material.R.style
                            .TextAppearance_Material3_LabelLarge_Emphasized
                    )
                    maxLines = 1
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (LABEL_GAP_DP * density).toInt()
                },
            )

            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, itemHeight).apply {
                val gap = (ITEM_GAP_DP * density).toInt()
                marginStart = gap
                marginEnd = gap
            }
        }
    }

    companion object {
        const val HEIGHT_DP = 64f
        private const val PADDING_DP = 8f
        private const val ELEVATION_DP = 10f
        private const val BACKGROUND_DARKNESS = 0.72f
        private const val ITEM_PADDING_DP = 16f
        private const val MIN_ITEM_WIDTH_DP = 56f
        private const val ITEM_GAP_DP = 2f
        private const val ICON_DP = 24f
        private const val LABEL_GAP_DP = 8f
        private const val ANIMATION_MS = 300L
    }
}
