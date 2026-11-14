/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.expressive

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.drawable.Drawable
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.google.android.material.shape.MaterialShapes

/**
 * The shapes of Material 3 Expressive, by the names used in the layouts.
 */
enum class ExpressiveShape(private val source: () -> RoundedPolygon) {
    CIRCLE({ MaterialShapes.CIRCLE }),
    SQUARE({ MaterialShapes.SQUARE }),
    SLANTED_SQUARE({ MaterialShapes.SLANTED_SQUARE }),
    ARCH({ MaterialShapes.ARCH }),
    FAN({ MaterialShapes.FAN }),
    ARROW({ MaterialShapes.ARROW }),
    SEMI_CIRCLE({ MaterialShapes.SEMI_CIRCLE }),
    OVAL({ MaterialShapes.OVAL }),
    PILL({ MaterialShapes.PILL }),
    TRIANGLE({ MaterialShapes.TRIANGLE }),
    DIAMOND({ MaterialShapes.DIAMOND }),
    CLAM_SHELL({ MaterialShapes.CLAM_SHELL }),
    PENTAGON({ MaterialShapes.PENTAGON }),
    GEM({ MaterialShapes.GEM }),
    SUNNY({ MaterialShapes.SUNNY }),
    VERY_SUNNY({ MaterialShapes.VERY_SUNNY }),
    COOKIE_4({ MaterialShapes.COOKIE_4 }),
    COOKIE_6({ MaterialShapes.COOKIE_6 }),
    COOKIE_7({ MaterialShapes.COOKIE_7 }),
    COOKIE_9({ MaterialShapes.COOKIE_9 }),
    COOKIE_12({ MaterialShapes.COOKIE_12 }),
    GHOSTISH({ MaterialShapes.GHOSTISH }),
    CLOVER_4({ MaterialShapes.CLOVER_4 }),
    CLOVER_8({ MaterialShapes.CLOVER_8 }),
    BURST({ MaterialShapes.BURST }),
    SOFT_BURST({ MaterialShapes.SOFT_BURST }),
    BOOM({ MaterialShapes.BOOM }),
    SOFT_BOOM({ MaterialShapes.SOFT_BOOM }),
    FLOWER({ MaterialShapes.FLOWER }),
    PUFFY({ MaterialShapes.PUFFY }),
    PUFFY_DIAMOND({ MaterialShapes.PUFFY_DIAMOND }),
    PIXEL_CIRCLE({ MaterialShapes.PIXEL_CIRCLE }),
    BUN({ MaterialShapes.BUN }),
    HEART({ MaterialShapes.HEART });

    /**
     * The shape fitted in a 1x1 square, the way paths are scaled to a view.
     */
    val polygon: RoundedPolygon by lazy { MaterialShapes.normalize(source(), true) }

    /**
     * A drawable of the shape, filling its bounds. Tint it to give it a color.
     */
    fun drawable(): Drawable = ShapePathDrawable(this)

    companion object {
        /**
         * Shapes that look good as a decoration, in an order that keeps neighbours different.
         */
        val decorative = listOf(
            COOKIE_9, SUNNY, CLOVER_4, FLOWER, SOFT_BURST, COOKIE_6, PUFFY, CLOVER_8, GEM,
            COOKIE_12, SOFT_BOOM,
        )

        /**
         * Shapes for faces and covers: round enough to hold a picture.
         */
        val avatars = listOf(
            COOKIE_9, SUNNY, COOKIE_12, FLOWER, CLOVER_8, PUFFY, COOKIE_7, SOFT_BURST,
        )

        /**
         * A shape for a picture, always the same one for the same key.
         */
        fun avatarFor(key: Any?) = avatars[(key?.hashCode() ?: 0).mod(avatars.size)]

        /**
         * A shape for something, always the same one for the same key.
         */
        fun forKey(key: Any?) = decorative[(key?.hashCode() ?: 0).mod(decorative.size)]
    }
}

/**
 * Path of [shape] scaled to a [width] by [height] box.
 */
fun ExpressiveShape.pathIn(width: Float, height: Float, out: Path = Path()): Path {
    out.reset()
    polygon.toPath(out)
    out.transform(Matrix().apply { setScale(width, height) })
    return out
}

/**
 * Path of the shape between [from] and [to] at [progress] (0 to 1), scaled to a box.
 */
fun Morph.pathIn(progress: Float, width: Float, height: Float, out: Path = Path()): Path {
    out.reset()
    toPath(progress, out)
    out.transform(Matrix().apply { setScale(width, height) })
    return out
}
