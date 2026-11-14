/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import androidx.media3.common.MediaMetadata
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.applyBackdropEffects
import org.lineageos.twelve.ui.expressive.morphForPlaying
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.models.Thumbnail
import kotlin.reflect.safeCast

/**
 * Mini player. It's a floating Material 3 card that is positioned by the hosting activity.
 */
class NowPlayingBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val artistNameTextView by lazy { findViewById<TextView>(R.id.artistNameTextView) }
    private val circularProgressIndicator by lazy { findViewById<CircularProgressIndicator>(R.id.circularProgressIndicator) }
    private val nextMaterialButton by lazy { findViewById<MaterialButton>(R.id.nextMaterialButton) }
    private val playPauseMaterialButton by lazy { findViewById<MaterialButton>(R.id.playPauseMaterialButton) }
    private val thumbnailImageView by lazy { findViewById<ImageView>(R.id.thumbnailImageView) }
    private val thumbnailMaskLayout by lazy { findViewById<View>(R.id.thumbnailMaskLayout) }
    private val backdropImageView by lazy { findViewById<ImageView>(R.id.backdropImageView) }

    // The shaped cover turns slowly while the music plays, like a record
    private var spinAnimator: ValueAnimator? = null
    private val titleTextView by lazy { findViewById<TextView>(R.id.titleTextView) }

    init {
        // Dark, tinted with the accent: shown until the cover is there and behind it
        setCardBackgroundColor(
            ColorUtils.blendARGB(
                MaterialColors.getColor(
                    context, androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY
                ),
                Color.BLACK,
                0.72f,
            )
        )
        radius = resources.getDimension(R.dimen.now_playing_bar_corner_radius)
        cardElevation = resources.getDimension(R.dimen.now_playing_bar_elevation)
        strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        strokeColor = ColorUtils.setAlphaComponent(Color.WHITE, 0x29)

        inflate(context, R.layout.now_playing_bar, this)

        backdropImageView.applyBackdropEffects()

        circularProgressIndicator.min = 0
    }

    fun setOnPlayPauseClickListener(l: OnClickListener?) =
        playPauseMaterialButton.setOnClickListener(l)

    fun setOnNextClickListener(l: OnClickListener?) = nextMaterialButton.setOnClickListener(l)

    fun updateIsPlaying(isPlaying: Boolean) {
        playPauseMaterialButton.setIconResource(
            when (isPlaying) {
                true -> R.drawable.avd_play_to_pause
                false -> R.drawable.avd_pause_to_play
            }
        )
        AnimatedVectorDrawable::class.safeCast(playPauseMaterialButton.icon)?.start()
        playPauseMaterialButton.morphForPlaying(isPlaying)

        when (isPlaying) {
            true -> spinAnimator?.takeIf { it.isStarted }?.resume() ?: run {
                spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = SPIN_DURATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        thumbnailMaskLayout.rotation = it.animatedValue as Float
                    }
                    start()
                }
            }

            false -> spinAnimator?.pause()
        }
    }

    override fun onDetachedFromWindow() {
        spinAnimator?.cancel()
        spinAnimator = null
        super.onDetachedFromWindow()
    }

    fun updateMediaMetadata(mediaMetadata: MediaMetadata) {
        val audioTitle = mediaMetadata.displayTitle
            ?: mediaMetadata.title
            ?: context.getString(R.string.unknown)
        if (titleTextView.text != audioTitle) {
            titleTextView.text = audioTitle
        }

        val artistName = mediaMetadata.artist
            ?: context.getString(R.string.artist_unknown)
        if (artistNameTextView.text != artistName) {
            artistNameTextView.text = artistName
        }
    }

    fun updateMediaArtwork(artwork: Thumbnail?) {
        thumbnailImageView.loadThumbnail(artwork, placeholder = R.drawable.ic_music_note)
        backdropImageView.loadThumbnail(artwork)
    }

    fun updateDurationCurrentPositionMs(durationMs: Long?, currentPositionMs: Long?) {
        val currentPositionSecs = currentPositionMs?.let { it / 1000 }?.toInt() ?: 0
        val durationSecs = durationMs?.let { it / 1000 }?.toInt()?.takeIf { it != 0 } ?: 1

        circularProgressIndicator.max = durationSecs
        circularProgressIndicator.setProgressCompat(currentPositionSecs, true)
    }

    private companion object {
        private const val SPIN_DURATION_MS = 18_000L
    }
}
