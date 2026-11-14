/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.lineageos.twelve.R
import org.lineageos.twelve.models.ActivityTab
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Artist
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.Genre
import org.lineageos.twelve.models.MediaItem
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.areContentsTheSame
import org.lineageos.twelve.models.areItemsTheSame
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter

/**
 * A shelf of the main screen. Every kind of shelf looks different, so that the screen is a
 * collection of shapes and colors and not a stack of identical rows:
 * - [Style.HERO]: a row of big artwork cards, no container
 * - [Style.FEATURED]: rounded covers on a primary container
 * - [Style.TILES]: two rows of wide tiles on a neutral container
 * - [Style.ROUND]: circular covers on a tertiary container
 */
class ActivityTabView(context: Context) : FrameLayout(context) {
    enum class Style { HERO, FEATURED, TILES, ROUND }

    // Views
    private val sectionCardView by lazy { findViewById<MaterialCardView>(R.id.sectionCardView) }
    private val recyclerView by lazy { findViewById<RecyclerView>(R.id.recyclerView) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.titleTextView) }

    private val density = resources.displayMetrics.density

    // RecyclerView
    private val adapters = mutableMapOf<Style, SimpleListAdapter<MediaItem<*>, BaseMediaItemView>>()
    private var currentStyle: Style? = null

    // Callbacks
    private var onItemClickListener: (item: MediaItem<*>) -> Unit = { _ -> }
    private var onItemLongClickListener: (item: MediaItem<*>) -> Boolean = { _ -> false }

    init {
        inflate(context, R.layout.view_activity_tab, this)

    }

    private fun adapterFor(style: Style) = adapters.getOrPut(style) {
        object : SimpleListAdapter<MediaItem<*>, BaseMediaItemView>(
            mediaItemDiffCallback,
            { context ->
                when (style) {
                    Style.HERO -> HeroMediaItemView(context)
                    Style.FEATURED -> FeaturedMediaItemView(context)
                    Style.TILES -> TileMediaItemView(context)
                    Style.ROUND -> RoundMediaItemView(context)
                }
            },
        ) {
            override fun ViewHolder.onPrepareView() {
                view.layoutParams = when (style) {
                    Style.HERO -> RecyclerView.LayoutParams(
                        (HERO_WIDTH_DP * density).toInt(),
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    Style.TILES -> RecyclerView.LayoutParams(
                        (TILE_WIDTH_DP * density).toInt(),
                        (TILE_HEIGHT_DP * density).toInt(),
                    ).also {
                        val margin = (4 * density).toInt()
                        it.setMargins(margin, margin, margin, margin)
                    }

                    else -> RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }

                view.setOnClickListener {
                    item?.let {
                        onItemClickListener(it)
                    }
                }
                view.setOnLongClickListener {
                    item?.let {
                        onItemLongClickListener(it)
                    } ?: false
                }
            }

            override fun ViewHolder.onBindView(item: MediaItem<*>) {
                (view as BaseMediaItemView).setItem(item)
            }
        }
    }

    fun setOnItemClickListener(listener: ((item: MediaItem<*>) -> Unit)?) {
        onItemClickListener = listener ?: {}
    }

    fun setOnItemLongClickListener(
        listener: ((item: MediaItem<*>) -> Boolean)?
    ) {
        onItemLongClickListener = listener ?: { false }
    }

    fun setActivityTab(activityTab: ActivityTab, style: Style = Style.FEATURED) {
        titleTextView.text = activityTab.title.getString(resources)

        if (currentStyle != style) {
            currentStyle = style
            applyStyle(style)
        }

        adapterFor(style).submitList(activityTab.items)
        recyclerView.isVisible = activityTab.items.isNotEmpty()
    }

    private fun applyStyle(style: Style) {
        // Container and text colors
        val containerAttr: Int?
        val onContainerAttr: Int
        when (style) {
            Style.HERO -> {
                containerAttr = null
                onContainerAttr = com.google.android.material.R.attr.colorOnSurface
            }

            Style.FEATURED -> {
                containerAttr = com.google.android.material.R.attr.colorPrimaryContainer
                onContainerAttr = com.google.android.material.R.attr.colorOnPrimaryContainer
            }

            Style.TILES -> {
                containerAttr = com.google.android.material.R.attr.colorSurfaceContainerLow
                onContainerAttr = com.google.android.material.R.attr.colorOnSurface
            }

            Style.ROUND -> {
                containerAttr = com.google.android.material.R.attr.colorTertiaryContainer
                onContainerAttr = com.google.android.material.R.attr.colorOnTertiaryContainer
            }
        }
        sectionCardView.setCardBackgroundColor(
            containerAttr?.let { MaterialColors.getColor(sectionCardView, it) } ?: Color.TRANSPARENT
        )
        titleTextView.setTextColor(MaterialColors.getColor(sectionCardView, onContainerAttr))

        // The shapes differ from shelf to shelf too
        sectionCardView.radius = when (style) {
            Style.HERO -> 0f
            Style.FEATURED -> 44f * density
            Style.TILES -> 28f * density
            Style.ROUND -> 56f * density
        }

        recyclerView.layoutParams = recyclerView.layoutParams.also {
            it.height = when (style) {
                Style.HERO -> (HERO_HEIGHT_DP * density).toInt()
                Style.TILES -> ((TILE_HEIGHT_DP + 8) * 2 * density).toInt()
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        recyclerView.onFlingListener = null
        recyclerView.layoutManager = when (style) {
            Style.TILES -> GridLayoutManager(context, 2, RecyclerView.HORIZONTAL, false)
            else -> LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        }
        recyclerView.adapter = adapterFor(style)
    }

    companion object {
        private const val HERO_WIDTH_DP = 276
        private const val HERO_HEIGHT_DP = 344
        private const val TILE_WIDTH_DP = 280
        private const val TILE_HEIGHT_DP = 84

        private val mediaItemDiffCallback = object : DiffUtil.ItemCallback<MediaItem<*>>() {
            override fun areItemsTheSame(
                oldItem: MediaItem<*>,
                newItem: MediaItem<*>,
            ) = when (oldItem) {
                is Album -> oldItem.areItemsTheSame<Album>(newItem)
                is Artist -> oldItem.areItemsTheSame<Artist>(newItem)
                is Audio -> oldItem.areItemsTheSame<Audio>(newItem)
                is Genre -> oldItem.areItemsTheSame<Genre>(newItem)
                is Playlist -> oldItem.areItemsTheSame<Playlist>(newItem)
            }

            override fun areContentsTheSame(
                oldItem: MediaItem<*>,
                newItem: MediaItem<*>,
            ) = when (oldItem) {
                is Album -> oldItem.areContentsTheSame<Album>(newItem)
                is Artist -> oldItem.areContentsTheSame<Artist>(newItem)
                is Audio -> oldItem.areContentsTheSame<Audio>(newItem)
                is Genre -> oldItem.areContentsTheSame<Genre>(newItem)
                is Playlist -> oldItem.areContentsTheSame<Playlist>(newItem)
            }
        }
    }
}
