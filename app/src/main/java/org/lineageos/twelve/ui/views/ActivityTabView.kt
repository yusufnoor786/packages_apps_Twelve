/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.google.android.material.carousel.UncontainedCarouselStrategy
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
 * A shelf of the main screen: a title and a carousel of items. The first shelf is the big one, its
 * items are tall and show the artwork edge to edge.
 */
class ActivityTabView(context: Context) : FrameLayout(context) {
    // Views
    private val recyclerView by lazy { findViewById<RecyclerView>(R.id.recyclerView) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.titleTextView) }

    private val density = resources.displayMetrics.density

    // RecyclerView
    private val itemAdapter = createAdapter(hero = false) {
        RecyclerView.LayoutParams((160 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private val heroAdapter = createAdapter(hero = true) {
        RecyclerView.LayoutParams((300 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var isHero: Boolean? = null

    // Callbacks
    private var onItemClickListener: (item: MediaItem<*>) -> Unit = { _ -> }
    private var onItemLongClickListener: (item: MediaItem<*>) -> Boolean = { _ -> false }

    init {
        inflate(context, R.layout.view_activity_tab, this)

        CarouselSnapHelper().attachToRecyclerView(recyclerView)
    }

    private fun createAdapter(
        hero: Boolean,
        layoutParams: () -> RecyclerView.LayoutParams,
    ): SimpleListAdapter<MediaItem<*>, CarouselMediaItemView> = object : SimpleListAdapter<MediaItem<*>, CarouselMediaItemView>(
        mediaItemDiffCallback,
        { CarouselMediaItemView(it, hero) },
    ) {
        override fun ViewHolder.onPrepareView() {
            view.layoutParams = layoutParams()
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
            view.setItem(item)
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

    /**
     * @param hero Whether this is the big shelf at the top
     */
    fun setActivityTab(activityTab: ActivityTab, hero: Boolean = false) {
        titleTextView.text = activityTab.title.getString(resources)

        if (isHero != hero) {
            isHero = hero

            recyclerView.updateLayoutParams {
                height = ((if (hero) HERO_HEIGHT_DP else ITEM_HEIGHT_DP) * density).toInt()
            }
            recyclerView.layoutManager = CarouselLayoutManager(
                when (hero) {
                    true -> MultiBrowseCarouselStrategy()
                    false -> UncontainedCarouselStrategy()
                }
            )
            recyclerView.adapter = when (hero) {
                true -> heroAdapter
                false -> itemAdapter
            }
        }

        when (hero) {
            true -> heroAdapter.submitList(activityTab.items)
            false -> itemAdapter.submitList(activityTab.items)
        }
        recyclerView.isVisible = activityTab.items.isNotEmpty()
    }

    private inline fun View.updateLayoutParams(block: ViewGroup.LayoutParams.() -> Unit) {
        layoutParams = layoutParams.apply(block)
    }

    companion object {
        private const val HERO_HEIGHT_DP = 300
        private const val ITEM_HEIGHT_DP = 236

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
