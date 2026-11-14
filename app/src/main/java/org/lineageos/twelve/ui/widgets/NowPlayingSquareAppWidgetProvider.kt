/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.widgets

import android.content.Context
import android.util.SizeF
import android.widget.RemoteViews
import org.lineageos.twelve.R

/**
 * The now playing widget of 1×1 cells: a play button on the cover. It shows a smaller layout if it is
 * made smaller by the user.
 */
class NowPlayingSquareAppWidgetProvider : BaseAppWidgetProvider<NowPlayingSquareAppWidgetProvider>(Companion) {
    companion object : AppWidgetUpdater<NowPlayingSquareAppWidgetProvider>(
        NowPlayingSquareAppWidgetProvider::class,
        R.layout.app_widget_now_playing_tiny,
        mapOf(
            SizeF(0f, 0f) to R.layout.app_widget_now_playing_tiny,
        ),
    ) {
        override suspend fun update(context: Context, views: List<RemoteViews>) =
            NowPlayingAppWidgetProvider.fill(context, views)
    }
}
