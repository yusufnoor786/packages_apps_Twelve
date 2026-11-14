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
 * The now playing widget of 2×4 cells: big cover, title and the three buttons. It shows a smaller layout if it is
 * made smaller by the user.
 */
class NowPlayingTallAppWidgetProvider : BaseAppWidgetProvider<NowPlayingTallAppWidgetProvider>(Companion) {
    companion object : AppWidgetUpdater<NowPlayingTallAppWidgetProvider>(
        NowPlayingTallAppWidgetProvider::class,
        R.layout.app_widget_now_playing_tall,
        mapOf(
            SizeF(0f, 0f) to R.layout.app_widget_now_playing_tiny,
            SizeF(100f, 40f) to R.layout.app_widget_now_playing_mini,
            SizeF(110f, 110f) to R.layout.app_widget_now_playing_compact,
            SizeF(110f, 230f) to R.layout.app_widget_now_playing_tall,
        ),
    ) {
        override suspend fun update(context: Context, views: List<RemoteViews>) =
            NowPlayingAppWidgetProvider.fill(context, views)
    }
}
