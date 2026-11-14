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
 * The now playing widget of 2×2 cells: title and the three buttons. It shows a smaller layout if it is
 * made smaller by the user.
 */
class NowPlayingCompactAppWidgetProvider : BaseAppWidgetProvider<NowPlayingCompactAppWidgetProvider>(Companion) {
    companion object : AppWidgetUpdater<NowPlayingCompactAppWidgetProvider>(
        NowPlayingCompactAppWidgetProvider::class,
        R.layout.app_widget_now_playing_compact,
        mapOf(
            SizeF(0f, 0f) to R.layout.app_widget_now_playing_tiny,
            SizeF(100f, 40f) to R.layout.app_widget_now_playing_mini,
            SizeF(110f, 110f) to R.layout.app_widget_now_playing_compact,
        ),
    ) {
        override suspend fun update(context: Context, views: List<RemoteViews>) =
            NowPlayingAppWidgetProvider.fill(context, views)
    }
}
