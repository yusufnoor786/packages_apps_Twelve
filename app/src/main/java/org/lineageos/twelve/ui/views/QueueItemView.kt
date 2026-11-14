/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.lineageos.twelve.R

/**
 * A row of the queue: drag handle, artwork, title and artist, a marker for the song that is
 * playing and a button to remove it.
 */
class QueueItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    val dragHandleImageView: ImageView
    val thumbnailImageView: ImageView
    val titleTextView: TextView
    val artistNameTextView: TextView
    val nowPlayingImageView: ImageView
    val removeMaterialButton: MaterialButton

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val padding = resources.getDimensionPixelSize(R.dimen.queue_item_padding)
        setPaddingRelative(padding / 2, padding / 2, padding, padding / 2)

        LayoutInflater.from(context).inflate(R.layout.queue_item, this, true)

        dragHandleImageView = findViewById(R.id.dragHandleImageView)
        thumbnailImageView = findViewById(R.id.thumbnailImageView)
        titleTextView = findViewById(R.id.titleTextView)
        artistNameTextView = findViewById(R.id.artistNameTextView)
        nowPlayingImageView = findViewById(R.id.nowPlayingImageView)
        removeMaterialButton = findViewById(R.id.removeMaterialButton)

        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
}
