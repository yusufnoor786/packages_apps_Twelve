/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.expressive

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.min

/**
 * Cuts a picture to a Material shape, centered and cropped to a square, for the places where a
 * [ShapeMaskLayout] can't be used.
 */
class ShapeTransformation(private val shape: ExpressiveShape) : Transformation() {
    override val cacheKey = "${ShapeTransformation::class.java.name}-${shape.name}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val side = min(input.width, input.height).coerceAtMost(MAX_SIDE)
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)

        val scale = side.toFloat() / min(input.width, input.height)
        val shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(
                        (side - input.width * scale) / 2f,
                        (side - input.height * scale) / 2f,
                    )
                }
            )
        }

        Canvas(output).drawPath(
            shape.pathIn(side.toFloat(), side.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader },
        )

        return output
    }

    private companion object {
        private const val MAX_SIDE = 512
    }
}
