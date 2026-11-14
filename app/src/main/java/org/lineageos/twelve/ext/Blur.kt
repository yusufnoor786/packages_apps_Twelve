/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ext

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.ImageView
import coil3.request.crossfade

/**
 * Blur this view's content. Needs Android 12, it does nothing on older versions, where callers
 * rely on opacity alone.
 *
 * @param radius The blur radius in pixels. Use 0 to remove the effect
 */
fun View.blur(radius: Float) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return
    }

    setRenderEffect(
        when {
            radius < MIN_BLUR_RADIUS -> null
            else -> RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        }
    )
}

/**
 * Prepare an image view that shows the artwork as a blurred, saturated and darkened backdrop.
 */
fun ImageView.applyBackdropEffects() {
    scaleType = ImageView.ScaleType.CENTER_CROP

    // More color, less brightness: keeps the text on top readable and the backdrop alive
    colorFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            setSaturation(BACKDROP_SATURATION)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        BACKDROP_BRIGHTNESS, 0f, 0f, 0f, 0f,
                        0f, BACKDROP_BRIGHTNESS, 0f, 0f, 0f,
                        0f, 0f, BACKDROP_BRIGHTNESS, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
    )

    blur(BACKDROP_BLUR_RADIUS)
}

/**
 * Load the artwork as a backdrop. A tiny version is enough since it's blurred, which also makes
 * the result smooth on devices that can't blur in hardware.
 */
fun ImageView.loadBackdrop(data: Any?) = loadThumbnail(data) {
    size(BACKDROP_BITMAP_SIZE)
    crossfade(BACKDROP_CROSSFADE_MS)
}

private const val MIN_BLUR_RADIUS = 0.5f

private const val BACKDROP_BLUR_RADIUS = 48f
private const val BACKDROP_BITMAP_SIZE = 96
private const val BACKDROP_CROSSFADE_MS = 500
private const val BACKDROP_SATURATION = 1.4f
private const val BACKDROP_BRIGHTNESS = 0.75f
