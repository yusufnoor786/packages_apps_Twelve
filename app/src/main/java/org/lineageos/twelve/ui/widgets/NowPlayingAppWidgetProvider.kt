/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.widgets

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.media3.session.MediaController
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.guava.await
import org.lineageos.twelve.MainActivity
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.typedPlaybackState
import org.lineageos.twelve.models.PlaybackState
import org.lineageos.twelve.services.PlaybackService
import org.lineageos.twelve.utils.SessionTokenUtils

/**
 * The now playing widget. The cover, a little blurred, fills it and the layout adapts to the size
 * the user gives to the widget:
 * - 1x1: a play button
 * - 2x1: title and play
 * - 4x1 and thinner: title and the three buttons in a row
 * - 2x2: title and the three buttons
 * - 2x4: the cover, the title and the three buttons
 * - 4x2: cover tile, title and the three buttons
 * - 4x3 and 4x4: big cover tile, big title and big buttons
 */
class NowPlayingAppWidgetProvider : BaseAppWidgetProvider<NowPlayingAppWidgetProvider>(Companion) {
    companion object : AppWidgetUpdater<NowPlayingAppWidgetProvider>(
        NowPlayingAppWidgetProvider::class,
        R.layout.app_widget_now_playing,
        // The widget gets the biggest of these that fits in it, sizes are in dp
        mapOf(
            SizeF(0f, 0f) to R.layout.app_widget_now_playing_tiny,
            SizeF(100f, 50f) to R.layout.app_widget_now_playing_mini,
            SizeF(200f, 36f) to R.layout.app_widget_now_playing_strip,
            SizeF(110f, 110f) to R.layout.app_widget_now_playing_compact,
            SizeF(110f, 230f) to R.layout.app_widget_now_playing_tall,
            SizeF(250f, 120f) to R.layout.app_widget_now_playing,
            SizeF(250f, 200f) to R.layout.app_widget_now_playing_large,
        ),
    ) {
        private const val COVER_SIZE_PX = 320
        private const val BLUR_STEP_PX = 40
        private const val BACKDROP_SIZE_PX = 192

        override suspend fun update(context: Context, views: List<RemoteViews>) =
            fill(context, views)

        /**
         * Fill the layouts of a now playing widget with what is playing now.
         */
        suspend fun fill(context: Context, views: List<RemoteViews>) {
            withMediaController(context) { mediaController ->
                val mediaMetadata = mediaController.mediaMetadata
                val isBuffering = mediaController.typedPlaybackState == PlaybackState.BUFFERING

                val openNowPlayingPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                fun servicePendingIntent(requestCode: Int, action: String) =
                    PendingIntent.getForegroundService(
                        context,
                        requestCode,
                        Intent(context, PlaybackService::class.java).apply {
                            this.action = action
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                // Loaded once, all the layouts show it
                val hasSong = mediaMetadata.title != null
                val cover = when (isBuffering) {
                    true -> null
                    false -> (mediaMetadata.artworkData ?: mediaMetadata.artworkUri)
                        ?.let { loadBitmap(context, it, COVER_SIZE_PX) }
                }
                val backdrop = cover?.let { blurred(it) } ?: plainBackdrop(context)

                views.forEach { remoteViews ->
                    remoteViews.apply {
                        setOnClickPendingIntent(R.id.linearLayout, openNowPlayingPendingIntent)

                        // Nothing is queued: say so, the widget still opens the app
                        setTextViewText(
                            R.id.titleTextView,
                            mediaMetadata.title ?: context.getString(R.string.widget_idle_title)
                        )
                        setTextViewText(
                            R.id.artistNameTextView,
                            when (hasSong) {
                                true -> mediaMetadata.artist ?: ""
                                false -> context.getString(R.string.widget_idle_subtitle)
                            }
                        )

                        setOnClickPendingIntent(
                            R.id.playPauseImageButton,
                            servicePendingIntent(0, PlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
                        )
                        setOnClickPendingIntent(
                            R.id.previousImageButton,
                            servicePendingIntent(1, PlaybackService.ACTION_SKIP_PREVIOUS)
                        )
                        setOnClickPendingIntent(
                            R.id.nextImageButton,
                            servicePendingIntent(2, PlaybackService.ACTION_SKIP_NEXT)
                        )

                        setImageViewResource(
                            R.id.playPauseImageButton,
                            when (mediaController.playWhenReady) {
                                true -> R.drawable.ic_pause
                                false -> R.drawable.ic_play_arrow
                            }
                        )
                        setViewVisibility(
                            R.id.bufferingProgressBar,
                            when (isBuffering) {
                                true -> View.VISIBLE
                                false -> View.GONE
                            }
                        )

                        if (!isBuffering) {
                            when (cover) {
                                null -> setImageViewResource(
                                    R.id.thumbnailImageView, R.drawable.ic_music_note
                                )

                                else -> setImageViewBitmap(R.id.thumbnailImageView, cover)
                            }
                            setImageViewBitmap(R.id.backdropImageView, backdrop)
                        }
                    }
                }
            }
        }

        /**
         * A soft version of the cover: shrunk and stretched back, a little at a time so that it
         * is smooth and not blocky.
         */
        private fun blurred(source: Bitmap): Bitmap {
            var bitmap = Bitmap.createScaledBitmap(source, BLUR_STEP_PX, BLUR_STEP_PX, true)
            bitmap = Bitmap.createScaledBitmap(bitmap, BLUR_STEP_PX * 2, BLUR_STEP_PX * 2, true)
            return Bitmap.createScaledBitmap(bitmap, BACKDROP_SIZE_PX, BACKDROP_SIZE_PX, true)
        }

        /**
         * What fills the widget when there is no cover: the accent color of the theme, darker.
         */
        private fun plainBackdrop(context: Context): Bitmap {
            val themed = DynamicColors.wrapContextIfAvailable(
                context, R.style.Theme_Twelve_AppWidgetContainer
            )
            val accent = MaterialColors.getColor(
                themed, androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY
            )

            return Bitmap.createBitmap(
                intArrayOf(ColorUtils.blendARGB(accent, Color.BLACK, 0.35f)), 1, 1,
                Bitmap.Config.ARGB_8888,
            )
        }

        private suspend fun withMediaController(
            context: Context,
            block: suspend (MediaController) -> Unit,
        ) {
            // Some ROMs hide the service from package queries, see SessionTokenUtils
            val sessionToken = SessionTokenUtils.forLibraryService(
                context,
                ComponentName(context, PlaybackService::class.java)
            )

            val mediaController = MediaController.Builder(
                context.applicationContext,
                sessionToken
            )
                .buildAsync()
                .await()

            block(mediaController)

            mediaController.release()
        }
    }
}
