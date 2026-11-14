/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.recyclerview

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.core.view.isVisible
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.models.QueueItem
import org.lineageos.twelve.ui.views.QueueItemView
import java.util.Collections

/**
 * The queue shown in the player.
 *
 * @param onPlay Called with the position of the item the user wants to play
 * @param onRemove Called with the position of the item the user wants to remove
 * @param onStartDrag Called when the user grabs the handle of an item
 */
class QueueAdapter(
    private val onPlay: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onStartDrag: (androidx.recyclerview.widget.RecyclerView.ViewHolder) -> Unit,
) : SimpleListAdapter<QueueItem, QueueItemView>(
    UniqueItemDiffCallback(),
    ::QueueItemView,
) {
    /**
     * The queue as the adapter shows it, kept in sync while the user reorders items.
     */
    var currentQueue = mutableListOf<QueueItem>()
        private set

    fun submitQueue(queue: List<QueueItem>, commitCallback: Runnable? = null) {
        currentQueue = queue.toMutableList()
        submitList(currentQueue.toList(), commitCallback)
    }

    /**
     * Move an item while it's being dragged.
     */
    fun moveItem(from: Int, to: Int) {
        Collections.swap(currentQueue, from, to)
        notifyItemMoved(from, to)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun ViewHolder.onPrepareView() {
        view.dragHandleImageView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(this)
            }
            false
        }
    }

    override fun ViewHolder.onBindView(item: QueueItem) {
        val (mediaItem, isCurrent) = item
        val metadata = mediaItem.mediaMetadata

        view.titleTextView.text = metadata.title ?: view.context.getString(R.string.unknown)
        view.titleTextView.alpha = when (isCurrent) {
            true -> 1f
            false -> 0.9f
        }
        view.artistNameTextView.text = metadata.artist
            ?: view.context.getString(R.string.artist_unknown)

        view.thumbnailImageView.loadThumbnail(
            metadata.artworkUri ?: metadata.artworkData,
            placeholder = R.drawable.ic_music_note,
        )

        view.nowPlayingImageView.isVisible = isCurrent
        view.removeMaterialButton.isVisible = !isCurrent

        view.setOnClickListener {
            bindingAdapterPosition.takeIf { it >= 0 }?.let(onPlay)
        }
        view.removeMaterialButton.setOnClickListener {
            bindingAdapterPosition.takeIf { it >= 0 }?.let(onRemove)
        }
    }
}
