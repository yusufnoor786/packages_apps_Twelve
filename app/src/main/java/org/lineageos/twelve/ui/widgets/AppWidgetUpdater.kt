/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlin.reflect.KClass

/**
 * A helper class used to update a widget.
 *
 * @param layoutResId The layout used where the widget can't pick a layout for its size
 * @param responsiveLayouts The layouts for the sizes the widget can have, in dp. The launcher
 *   shows the biggest one that fits when the user resizes the widget (Android 12 and later)
 */
abstract class AppWidgetUpdater<T : AppWidgetProvider>(
    private val appWidgetProviderKClass: KClass<T>,
    @LayoutRes val layoutResId: Int,
    private val responsiveLayouts: Map<SizeF, Int> = emptyMap(),
) {
    /**
     * Fill [views] (the same widget in all of its layouts) with the current state.
     */
    abstract suspend fun update(context: Context, views: List<RemoteViews>)

    // The responsive RemoteViews are only used from Android 12, see `responsive`
    @SuppressLint("NewApi")
    suspend fun update(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) {
            return
        }

        val responsive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && responsiveLayouts.isNotEmpty()

        // The state is read once, every size gets the same
        val bySize = when (responsive) {
            true -> responsiveLayouts.mapValues { RemoteViews(context.packageName, it.value) }
            false -> mapOf(SizeF(0f, 0f) to RemoteViews(context.packageName, layoutResId))
        }
        update(context, bySize.values.toList())

        val views = when (responsive) {
            true -> RemoteViews(bySize)
            false -> bySize.values.first()
        }

        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    suspend fun update(context: Context) = AppWidgetManager.getInstance(context).let {
        update(
            context,
            it,
            it.getAppWidgetIds(
                ComponentName(context, appWidgetProviderKClass.java)
            ),
        )
    }

    /**
     * Load an image as a bitmap that can be put in a widget.
     */
    suspend fun loadBitmap(context: Context, data: Any, sizePx: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(Size(sizePx, sizePx))
            .allowHardware(false)
            .build()

        return (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }
}
