/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.recyclerview

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.blur
import org.lineageos.twelve.models.Lyrics
import kotlin.math.abs

/**
 * Lyrics list where the line that is being sung is sharp, big and bright and the other lines
 * fade, shrink and blur the further they are from it.
 *
 * @param onLineClick Called when the user taps a line
 */
class LyricsAdapter(
    private val onLineClick: (Lyrics.Line) -> Unit,
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {
    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView) {
        var animator: ValueAnimator? = null

        /**
         * Current focus of the line, 1 when it's the one being sung, 0 when it's far away.
         */
        var focus = 0f

        /**
         * Current blur radius in pixels.
         */
        var blurRadius = 0f
    }

    private var lines: List<Lyrics.Line> = emptyList()

    /**
     * The line being sung, null if the lyrics aren't synchronized.
     */
    private var activeIndex: Int? = null

    fun submit(newLines: List<Lyrics.Line>, newActiveIndex: Int?) {
        val sameLines = newLines.size == lines.size
                && newLines.indices.all { newLines[it].text == lines[it].text }

        lines = newLines
        activeIndex = newActiveIndex

        when (sameLines) {
            true -> notifyItemRangeChanged(0, lines.size, PAYLOAD_FOCUS)
            false -> notifyDataSetChanged()
        }
    }

    override fun getItemCount() = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.lyrics_line, parent, false) as TextView
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]

        holder.textView.text = line.text
        holder.textView.setOnClickListener { onLineClick(line) }
        holder.textView.isClickable = line.durationMs != null

        holder.animator?.cancel()
        holder.focus = targetFocus(position)
        holder.blurRadius = targetBlur(position)
        render(holder)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }

        holder.textView.text = lines[position].text

        val fromFocus = holder.focus
        val fromBlur = holder.blurRadius
        val toFocus = targetFocus(position)
        val toBlur = targetBlur(position)

        holder.animator?.cancel()
        holder.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FOCUS_ANIMATION_MS
            interpolator = FOCUS_INTERPOLATOR
            addUpdateListener {
                val fraction = it.animatedFraction
                holder.focus = fromFocus + (toFocus - fromFocus) * fraction
                holder.blurRadius = fromBlur + (toBlur - fromBlur) * fraction
                render(holder)
            }
            start()
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.animator?.cancel()
        holder.animator = null

        super.onViewRecycled(holder)
    }

    /**
     * 1 for the active line, decreasing with the distance from it. Lyrics that aren't
     * synchronized are shown evenly.
     */
    private fun targetFocus(position: Int) = when (val active = activeIndex) {
        null -> UNSYNCED_FOCUS
        else -> when (abs(position - active)) {
            0 -> 1f
            else -> 0f
        }
    }

    private fun targetBlur(position: Int) = when (val active = activeIndex) {
        null -> 0f
        else -> (abs(position - active) * BLUR_PER_LINE).coerceAtMost(MAX_BLUR) *
                when (position == active) {
                    true -> 0f
                    false -> 1f
                }
    }

    private fun render(holder: ViewHolder) {
        val view = holder.textView
        val focus = holder.focus

        view.alpha = INACTIVE_ALPHA + (1f - INACTIVE_ALPHA) * focus
        val scale = INACTIVE_SCALE + (1f - INACTIVE_SCALE) * focus
        view.pivotX = 0f
        view.pivotY = view.height / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.blur(holder.blurRadius * view.resources.displayMetrics.density)
    }

    companion object {
        private val PAYLOAD_FOCUS = Any()

        private const val FOCUS_ANIMATION_MS = 450L
        private val FOCUS_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

        private const val INACTIVE_ALPHA = 0.38f
        private const val INACTIVE_SCALE = 0.86f
        private const val UNSYNCED_FOCUS = 0.85f

        private const val BLUR_PER_LINE = 1.4f
        private const val MAX_BLUR = 5f
    }
}
