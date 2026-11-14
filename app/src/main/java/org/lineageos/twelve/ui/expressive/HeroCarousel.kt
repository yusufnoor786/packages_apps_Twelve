/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.expressive

import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.twelve.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The hero carousel of Material 3 Expressive on a horizontal [RecyclerView]: one big item and a
 * narrow peek of the next one, with a gap between them. Each item is cut to the part of it that
 * is on screen, with round corners on every side, and its picture slides to stay centered in it,
 * so the items look like they grow and shrink as they scroll. Scrolling snaps to the start.
 *
 * @param imageIds Views of an item that follow the visible part, the pictures
 * @param fadingIds Views of an item that fade out as it gets narrow, the texts
 */
class HeroCarousel(
    private val recyclerView: RecyclerView,
    private val imageIds: IntArray,
    private val fadingIds: IntArray,
) {
    private val density = recyclerView.resources.displayMetrics.density
    private val gap = (GAP_DP * density).toInt()
    private val small = SMALL_DP * density
    private val radius = CORNER_DP * density

    private val snapHelper = StartSnapHelper()

    private val decoration = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            val count = parent.adapter?.itemCount ?: 0
            outRect.set(0, 0, if (position in 0 until count - 1) gap else 0, 0)
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        // Called on scroll and after every layout
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = update()
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        update()
    }

    private var attached = false

    fun attach() {
        if (attached) {
            return
        }
        attached = true

        recyclerView.addItemDecoration(decoration)
        snapHelper.attachToRecyclerView(recyclerView)
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.post { update() }
    }

    fun detach() {
        if (!attached) {
            return
        }
        attached = false

        recyclerView.removeItemDecoration(decoration)
        snapHelper.attachToRecyclerView(null)
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        recyclerView.children.forEach { reset(it) }
    }

    /**
     * Width of one item: the large slot, leaving room for the peek of the next one.
     */
    fun itemWidth(): Int {
        val available = when (recyclerView.width > 0) {
            true -> recyclerView.width
            // Not laid out yet: the screen without the margins of the shelf
            false -> recyclerView.resources.displayMetrics.widthPixels -
                    (2 * SHELF_MARGIN_DP * density).toInt()
        } - recyclerView.paddingLeft - recyclerView.paddingRight

        return (available - small - gap).toInt()
            .coerceIn((MIN_WIDTH_DP * density).toInt(), (MAX_WIDTH_DP * density).toInt())
    }

    private fun update() {
        val start = recyclerView.paddingLeft.toFloat()
        val end = (recyclerView.width - recyclerView.paddingRight).toFloat()
        if (end <= start) {
            return
        }
        val width = itemWidth()

        recyclerView.children.forEach { child ->
            // The width follows the one of the list
            val layoutParams = child.layoutParams
            if (layoutParams.width != width) {
                layoutParams.width = width
                child.requestLayout()
                return@forEach
            }

            val left = child.left.toFloat()
            val visibleLeft = max(left, start) - left
            val visibleRight = min(left + child.width, end) - left
            // Gone past the edge: hidden, not left with the last visible part
            if (visibleRight - visibleLeft < 1f) {
                child.alpha = 0f
                return@forEach
            }
            child.alpha = 1f

            val window = (child.getTag(R.id.tag_carousel_window) as? WindowOutline)
                ?: WindowOutline(radius).also {
                    child.setTag(R.id.tag_carousel_window, it)
                    child.outlineProvider = it
                }
            if (window.left != visibleLeft || window.right != visibleRight) {
                window.left = visibleLeft
                window.right = visibleRight
                child.invalidateOutline()
            }
            if (!child.clipToOutline) {
                child.clipToOutline = true
            }

            // The picture stays centered in the visible part
            val offset = (visibleLeft + visibleRight) / 2f - child.width / 2f
            imageIds.forEach { id -> child.findViewById<View>(id)?.translationX = offset }

            // Texts only make sense on a wide item
            val visibleWidth = visibleRight - visibleLeft
            val fraction = ((visibleWidth - small) / (width - small)).coerceIn(0f, 1f)
            val alpha = fraction * fraction
            fadingIds.forEach { id -> child.findViewById<View>(id)?.alpha = alpha }
        }
    }

    private fun reset(child: View) {
        child.setTag(R.id.tag_carousel_window, null)
        child.outlineProvider = ViewOutlineProvider.BACKGROUND
        child.clipToOutline = false
        child.alpha = 1f
        imageIds.forEach { id -> child.findViewById<View>(id)?.translationX = 0f }
        fadingIds.forEach { id -> child.findViewById<View>(id)?.alpha = 1f }
    }

    /**
     * A round rectangle around the visible part of an item.
     */
    private class WindowOutline(private val radius: Float) : ViewOutlineProvider() {
        var left = 0f
        var right = 0f

        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(
                left.toInt(),
                0,
                right.toInt(),
                view.height,
                min(radius, (right - left) / 2f),
            )
        }
    }

    /**
     * Snaps an item to the start of the list instead of its center.
     */
    private class StartSnapHelper : LinearSnapHelper() {
        override fun calculateDistanceToFinalSnap(
            layoutManager: RecyclerView.LayoutManager,
            targetView: View,
        ): IntArray {
            val helper = OrientationHelper.createHorizontalHelper(layoutManager)
            return intArrayOf(
                helper.getDecoratedStart(targetView) - helper.startAfterPadding,
                0,
            )
        }

        override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
            val linearLayoutManager = layoutManager as? LinearLayoutManager ?: return null

            // At the end the last item is shown whole, there is nothing to snap to
            if (linearLayoutManager.findLastCompletelyVisibleItemPosition() ==
                linearLayoutManager.itemCount - 1
            ) {
                return null
            }

            val helper = OrientationHelper.createHorizontalHelper(layoutManager)
            return (0 until layoutManager.childCount)
                .mapNotNull { layoutManager.getChildAt(it) }
                .minByOrNull { abs(helper.getDecoratedStart(it) - helper.startAfterPadding) }
        }
    }

    private companion object {
        private const val GAP_DP = 8f
        private const val SMALL_DP = 56f
        private const val CORNER_DP = 32f
        private const val SHELF_MARGIN_DP = 12f
        private const val MIN_WIDTH_DP = 200f
        private const val MAX_WIDTH_DP = 420f
    }
}
