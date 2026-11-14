/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.card.MaterialCardView
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.ext.use
import org.lineageos.twelve.models.Thumbnail

/**
 * A poor man's Material Design 3 ListItem implementation.
 * @see <a href="https://m3.material.io/components/lists/overview">Material Design 3 docs</a>
 */
class ListItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val headlineTextView by lazy { findViewById<TextView>(R.id.headlineTextView) }
    private val leadingIconImageView by lazy { findViewById<ImageView>(R.id.leadingIconImageView) }
    private val leadingTextView by lazy { findViewById<TextView>(R.id.leadingTextView) }
    private val leadingViewContainerFrameLayout by lazy { findViewById<FrameLayout>(R.id.leadingViewContainerFrameLayout) }
    private val supportingTextView by lazy { findViewById<TextView>(R.id.supportingTextView) }
    private val trailingIconImageView by lazy { findViewById<ImageView>(R.id.trailingIconImageView) }
    private val trailingSupportingTextView by lazy { findViewById<TextView>(R.id.trailingSupportingTextView) }

    // The cover of the row, set up on its first use
    private var leadingCoverSetUp = false
    private val placeholderIcons = mutableMapOf<Int, android.graphics.drawable.Drawable?>()

    // Where the row stands in its group, nothing to change when it stays there
    private var groupState: Triple<Boolean, Boolean, Boolean>? = null

    // Colors of the text and icon on a plain row, a highlighted row uses its container's
    private val defaultHeadlineColors by lazy { headlineTextView.textColors }
    private val defaultLeadingIconTint by lazy { leadingIconImageView.imageTintList }
    private val trailingViewContainerFrameLayout by lazy { findViewById<FrameLayout>(R.id.trailingViewContainerFrameLayout) }

    private var cardCornerRadius: Float = 0f

    var leadingIconImage: Drawable?
        get() = leadingIconImageView.drawable
        set(value) {
            leadingIconImageView.setImageAndUpdateVisibility(value)
        }

    var leadingIconImageIsActivated: Boolean
        get() = leadingIconImageView.isActivated ?: false
        set(value) {
            leadingIconImageView?.isActivated = value
        }

    var leadingText: CharSequence?
        get() = leadingTextView.text
        set(value) {
            leadingTextView.setTextAndUpdateVisibility(value)
        }

    var leadingView: View?
        get() = leadingViewContainerFrameLayout.getChildAt(0)
        set(value) {
            leadingViewContainerFrameLayout.setChildAndUpdateVisibility(value)
        }

    var leadingViewIsVisible: Boolean
        get() = leadingViewContainerFrameLayout.isVisible
        set(value) {
            leadingViewContainerFrameLayout.updateVisibility(value)
        }

    var headlineText: CharSequence?
        get() = headlineTextView.text
        set(value) {
            headlineTextView.setTextAndUpdateVisibility(value)
        }

    var headlineTextIsActivated: Boolean
        get() = headlineTextView.isActivated
        set(value) {
            headlineTextView.isActivated = value
        }

    var supportingText: CharSequence?
        get() = supportingTextView.text
        set(value) {
            supportingTextView.setTextAndUpdateVisibility(value)
        }

    var trailingIconImage: Drawable?
        get() = trailingIconImageView.drawable
        set(value) {
            trailingIconImageView.setImageAndUpdateVisibility(value)
        }

    var trailingSupportingText: CharSequence?
        get() = trailingSupportingTextView.text
        set(value) {
            trailingSupportingTextView.setTextAndUpdateVisibility(value)
        }

    var trailingView: View?
        get() = trailingViewContainerFrameLayout.getChildAt(0)
        set(value) {
            trailingViewContainerFrameLayout.setChildAndUpdateVisibility(value)
        }

    var trailingViewIsVisible: Boolean
        get() = trailingViewContainerFrameLayout.isVisible
        set(value) {
            leadingViewContainerFrameLayout.updateVisibility(value)
        }

    var isDimmed: Boolean
        get() = !headlineTextView.isEnabled
        set(value) = setViewsProperty(View::setEnabled, !value)

    var hasRoundedCorners: Boolean = false
        set(value) {
            field = value

            super.setRadius(
                when (value) {
                    true -> cardCornerRadius
                    false -> 0f
                }
            )
        }

    init {
        setCardBackgroundColor(
            context.getColorStateList(R.color.list_item_background)
        )
        cardElevation = 0f
        radius = 0f
        strokeWidth = 0

        inflate(context, R.layout.list_item, this)

        context.obtainStyledAttributes(attrs, R.styleable.ListItem, 0, 0).use {
            cardCornerRadius = it.getDimension(
                R.styleable.ListItem_cardCornerRadius,
                resources.getDimension(R.dimen.list_item_default_corner_radius)
            )
            leadingIconImage = it.getDrawable(R.styleable.ListItem_leadingIconImage)
            leadingText = it.getString(R.styleable.ListItem_leadingText)
            it.getResourceId(R.styleable.ListItem_leadingViewLayout, 0).let { leadingViewLayout ->
                if (leadingViewLayout != 0) {
                    setLeadingView(leadingViewLayout)
                }
            }
            leadingViewIsVisible = it.getBoolean(R.styleable.ListItem_leadingViewIsVisible, true)
            headlineText = it.getString(R.styleable.ListItem_headlineText)
            supportingText = it.getString(R.styleable.ListItem_supportingText)
            trailingIconImage = it.getDrawable(R.styleable.ListItem_trailingIconImage)
            trailingSupportingText = it.getString(R.styleable.ListItem_trailingSupportingText)
            it.getResourceId(R.styleable.ListItem_trailingViewLayout, 0).let { trailingViewLayout ->
                if (trailingViewLayout != 0) {
                    setTrailingView(trailingViewLayout)
                }
            }
            trailingViewIsVisible = it.getBoolean(R.styleable.ListItem_trailingViewIsVisible, true)
            isDimmed = it.getBoolean(R.styleable.ListItem_isDimmed, false)
            hasRoundedCorners = it.getBoolean(R.styleable.ListItem_hasRoundedCorners, false)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)

        setViewsProperty(View::setEnabled, enabled)
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)

        setViewsProperty(View::setSelected, selected)
    }

    override fun setRadius(radius: Float) {
        super.setRadius(radius)

        cardCornerRadius = radius
    }

    fun setHeadlineText(@StringRes resId: Int) = headlineTextView.setTextAndUpdateVisibility(resId)
    fun setHeadlineText(@StringRes resId: Int, vararg formatArgs: Any) =
        headlineTextView.setTextAndUpdateVisibility(resId, *formatArgs)

    fun setLeadingIconImage(bm: Bitmap) = leadingIconImageView.setImageAndUpdateVisibility(bm)
    fun setLeadingIconImage(icon: Icon) = leadingIconImageView.setImageAndUpdateVisibility(icon)
    fun setLeadingIconImage(@DrawableRes resId: Int) =
        leadingIconImageView.setImageAndUpdateVisibility(resId)

    fun setLeadingIconImage(uri: Uri) = leadingIconImageView.setImageAndUpdateVisibility(uri)

    /**
     * Make the item a rounded card on a tonal surface, with room around it.
     *
     * @param highlighted Whether the card uses the accent container instead of the neutral one
     */
    fun setExpressiveCard(highlighted: Boolean = false) {
        val density = resources.displayMetrics.density

        radius = 28 * density
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(
                this,
                when (highlighted) {
                    true -> com.google.android.material.R.attr.colorPrimaryContainer
                    false -> com.google.android.material.R.attr.colorSurfaceContainerHigh
                }
            )
        )

        val margin = (4 * density).toInt()
        val sides = (12 * density).toInt()
        layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).also { it.setMargins(sides, margin, sides, margin) }
    }

    /**
     * Make the item one row of a connected group, the Material 3 Expressive list: the group has
     * big round corners on the outside and small ones between its rows, which stand 2dp apart.
     *
     * @param index Position of the row in its group
     * @param count Number of rows in the group
     */
    fun setGroupPosition(index: Int, count: Int, highlighted: Boolean = false) {
        val density = resources.displayMetrics.density
        val outer = OUTER_CORNER_DP * density
        val inner = INNER_CORNER_DP * density
        val isFirst = index <= 0
        val isLast = index >= count - 1

        val state = Triple(isFirst, isLast, highlighted)
        if (groupState == state && layoutParams != null) {
            return
        }
        groupState = state

        cardElevation = 0f
        strokeWidth = 0
        shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(if (isFirst) outer else inner)
            .setTopRightCornerSize(if (isFirst) outer else inner)
            .setBottomLeftCornerSize(if (isLast) outer else inner)
            .setBottomRightCornerSize(if (isLast) outer else inner)
            .build()
        setCardBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(
                this,
                when (highlighted) {
                    true -> com.google.android.material.R.attr.colorPrimaryContainer
                    false -> com.google.android.material.R.attr.colorSurfaceContainerHigh
                }
            )
        )
        val headlineColors = defaultHeadlineColors
        val leadingIconTint = defaultLeadingIconTint
        when (highlighted) {
            true -> {
                val onContainer = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnPrimaryContainer
                    )
                )
                headlineTextView.setTextColor(onContainer)
                leadingIconImageView.imageTintList = onContainer
            }

            false -> {
                headlineTextView.setTextColor(headlineColors)
                leadingIconImageView.imageTintList = leadingIconTint
            }
        }

        val sides = (16 * density).toInt()
        val gap = (1 * density).toInt()
        // A row of a list or of a plain layout, the parent's own kind of params are kept
        layoutParams = ((layoutParams as? android.view.ViewGroup.MarginLayoutParams)
            ?: androidx.recyclerview.widget.RecyclerView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )).also {
            it.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            it.setMargins(
                sides,
                if (isFirst) (8 * density).toInt() else gap,
                sides,
                if (isLast) (8 * density).toInt() else gap,
            )
        }
    }

    /**
     * Show the cover of an item in place of the leading icon, in a rounded square.
     *
     * @param placeholder Shown while the cover loads and when there is none
     */
    fun setLeadingCover(thumbnail: Thumbnail?, @DrawableRes placeholder: Int) {
        val density = resources.displayMetrics.density

        leadingIconImageView.apply {
            // The rounded square is set up once, a row only changes its picture afterwards
            if (!leadingCoverSetUp) {
                leadingCoverSetUp = true

                val size = (COVER_SIZE_DP * density).toInt()
                layoutParams = layoutParams.also {
                    it.width = size
                    it.height = size
                }
                imageTintList = null
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height, COVER_RADIUS_DP * density
                        )
                    }
                }
                // A cover that is not there is the icon, padded, on a tonal square
                setBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorSurfaceContainerHighest
                    )
                )
                setPadding(0, 0, 0, 0)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            isVisible = true

            // Icons are made once per row, not on every bind
            val icon = placeholderIcons.getOrPut(placeholder) {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(context, placeholder)
                    ?.mutate()?.also {
                        androidx.core.graphics.drawable.DrawableCompat.setTint(
                            it,
                            com.google.android.material.color.MaterialColors.getColor(
                                this, com.google.android.material.R.attr.colorOnSurfaceVariant
                            )
                        )
                    }
            }

            when (thumbnail) {
                null -> {
                    scaleType = ImageView.ScaleType.CENTER
                    setImageDrawable(icon)
                }

                else -> loadThumbnail(thumbnail, placeholder = null) {
                    placeholder(icon)
                    error(icon)
                    crossfade(true)
                }
            }
        }
    }

    fun setLeadingText(@StringRes resId: Int) = leadingTextView.setTextAndUpdateVisibility(resId)
    fun setLeadingText(@StringRes resId: Int, vararg formatArgs: Any) =
        leadingTextView.setTextAndUpdateVisibility(resId, *formatArgs)

    fun setLeadingView(@LayoutRes resId: Int) =
        leadingViewContainerFrameLayout.setChildAndUpdateVisibility(resId)

    fun setSupportingText(@StringRes resId: Int) =
        supportingTextView.setTextAndUpdateVisibility(resId)

    fun setSupportingText(@StringRes resId: Int, vararg formatArgs: Any) =
        supportingTextView.setTextAndUpdateVisibility(resId, *formatArgs)

    fun setTrailingIconImage(bm: Bitmap) = trailingIconImageView.setImageAndUpdateVisibility(bm)
    fun setTrailingIconImage(icon: Icon) = trailingIconImageView.setImageAndUpdateVisibility(icon)
    fun setTrailingIconImage(@DrawableRes resId: Int) =
        trailingIconImageView.setImageAndUpdateVisibility(resId)

    fun setTrailingIconImage(uri: Uri) = trailingIconImageView.setImageAndUpdateVisibility(uri)

    fun setTrailingSupportingText(@StringRes resId: Int) =
        trailingSupportingTextView.setTextAndUpdateVisibility(resId)

    fun setTrailingSupportingText(@StringRes resId: Int, vararg formatArgs: Any) =
        trailingSupportingTextView.setTextAndUpdateVisibility(resId, *formatArgs)

    fun setTrailingView(@LayoutRes resId: Int) =
        trailingViewContainerFrameLayout.setChildAndUpdateVisibility(resId)

    private inline fun <T> setViewsProperty(
        setter: View.(T) -> Unit,
        value: T,
    ) {
        headlineTextView.setter(value)
        leadingIconImageView.setter(value)
        leadingTextView.setter(value)
        leadingView?.setter(value)
        supportingTextView.setter(value)
        trailingIconImageView.setter(value)
        trailingSupportingTextView.setter(value)
        trailingView?.setter(value)
    }

    // FrameLayout utils

    private fun FrameLayout.updateVisibility(isVisible: Boolean) {
        this.isVisible = isVisible && isNotEmpty()
    }

    private fun FrameLayout.setChildAndUpdateVisibility(child: View?) {
        removeAllViews()
        child?.let {
            addView(it)
            // Sync up states
            it.isEnabled = isEnabled
            it.isSelected = isSelected
        }
        updateVisibility(true)
    }

    private fun FrameLayout.setChildAndUpdateVisibility(@LayoutRes resId: Int) {
        setChildAndUpdateVisibility(LayoutInflater.from(context).inflate(resId, this, false))
    }

    // ImageView utils

    private fun ImageView.setImageAndUpdateVisibility(bm: Bitmap) {
        setImageBitmap(bm)
        isVisible = true
    }

    private fun ImageView.setImageAndUpdateVisibility(drawable: Drawable?) {
        setImageDrawable(drawable)
        isVisible = drawable != null
    }

    private fun ImageView.setImageAndUpdateVisibility(icon: Icon) {
        setImageIcon(icon)
        isVisible = true
    }

    private fun ImageView.setImageAndUpdateVisibility(@DrawableRes resId: Int) {
        setImageResource(resId)
        isVisible = true
    }

    private fun ImageView.setImageAndUpdateVisibility(uri: Uri) {
        setImageURI(uri)
        isVisible = true
    }

    // TextView utils

    private fun TextView.setTextAndUpdateVisibility(text: CharSequence?) {
        this.text = text.also {
            isVisible = it != null
        }
    }

    private fun TextView.setTextAndUpdateVisibility(@StringRes resId: Int) =
        setTextAndUpdateVisibility(resources.getText(resId))

    private fun TextView.setTextAndUpdateVisibility(@StringRes resId: Int, vararg formatArgs: Any) =
        setTextAndUpdateVisibility(resources.getString(resId, *formatArgs))

    private companion object {
        private const val OUTER_CORNER_DP = 28f
        private const val INNER_CORNER_DP = 6f
        private const val COVER_SIZE_DP = 52
        private const val COVER_RADIUS_DP = 12f
    }
}
