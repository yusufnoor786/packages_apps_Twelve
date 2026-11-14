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
 * The now playing widget for one row. Launchers that don't let the user shrink the big widget to
 * one row show this one in their list of widgets: title and the three buttons, down to a small
 * play button when it is made narrow.
 */
class NowPlayingRowAppWidgetProvider :
    BaseAppWidgetProvider<NowPlayingRowAppWidgetProvider>(Companion) {
    companion object : AppWidgetUpdater<NowPlayingRowAppWidgetProvider>(
        NowPlayingRowAppWidgetProvider::class,
        R.layout.app_widget_now_playing_strip,
        mapOf(
            SizeF(0f, 0f) to R.layout.app_widget_now_playing_tiny,
            SizeF(100f, 40f) to R.layout.app_widget_now_playing_mini,
            SizeF(200f, 36f) to R.layout.app_widget_now_playing_strip,
        ),
    ) {
        override suspend fun update(context: Context, views: List<RemoteViews>) =
            NowPlayingAppWidgetProvider.fill(context, views)
    }
}
