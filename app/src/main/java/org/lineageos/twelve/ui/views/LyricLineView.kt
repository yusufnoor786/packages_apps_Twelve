/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import org.lineageos.twelve.models.Lyrics
import kotlin.math.max
import kotlin.math.min

/**
 * One line of lyrics. When the line knows when each of its words is sung, the words are filled
 * one after the other with a soft edge while the line is being sung.
 */
class LyricLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val horizontalPadding = (28 * density).toInt()
    private val feather = 14 * density

    var line: Lyrics.Line? = null
        private set

    private var layout: StaticLayout? = null
    private var layoutWidth = -1
    private var segments: List<Segment> = emptyList()

    /**
     * A part of a word that lives on one layout line.
     */
    private class Segment(
        val word: Int,
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
        val rtl: Boolean,
        val startFraction: Float,
        val endFraction: Float,
    )

    private var positionMs = -1L
    private var sweeping = false

    private val doneClip = Path()

    private val baseColor = resolveColor(com.google.android.material.R.attr.colorOnSurface)

    fun setLine(newLine: Lyrics.Line) {
        if (line == newLine) {
            return
        }

        line = newLine
        layout = null
        layoutWidth = -1
        segments = emptyList()

        val sizeSp = when (newLine.isBackground) {
            true -> BACKGROUND_TEXT_SP
            false -> TEXT_SP
        }
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics
        )

        requestLayout()
        invalidate()
    }

    /**
     * Playback position, only matters to lines with timed words.
     */
    fun setPosition(newPositionMs: Long) {
        val current = line ?: return
        if (current.words.isEmpty()) {
            return
        }

        val range = current.durationMs ?: return
        val wasSweeping = sweeping
        val nowSweeping = newPositionMs in (range.first - SWEEP_MARGIN_MS)..(range.last + SWEEP_MARGIN_MS)

        positionMs = newPositionMs
        sweeping = nowSweeping

        // Inside the line the fill moves every frame; around it one last frame is enough
        if (nowSweeping || wasSweeping) {
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = max(1, width - horizontalPadding * 2)
        val built = buildLayout(textWidth)

        val verticalPadding = verticalPadding()
        setMeasuredDimension(width, (built?.height ?: 0) + verticalPadding * 2)
    }

    private fun verticalPadding() = when (line?.isBackground == true) {
        true -> (4 * density).toInt()
        false -> (13 * density).toInt()
    }

    private fun buildLayout(textWidth: Int): StaticLayout? {
        val current = line ?: return null
        if (layout != null && layoutWidth == textWidth) {
            return layout
        }

        val alignment = when (current.isOpposite) {
            true -> Layout.Alignment.ALIGN_OPPOSITE
            false -> Layout.Alignment.ALIGN_NORMAL
        }

        val built = StaticLayout.Builder
            .obtain(current.text, 0, current.text.length, paint, textWidth)
            .setAlignment(alignment)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()

        layout = built
        layoutWidth = textWidth
        segments = buildSegments(current, built)

        return built
    }

    /**
     * Where each word is, so the fill can follow it.
     */
    private fun buildSegments(current: Lyrics.Line, built: StaticLayout): List<Segment> {
        if (current.words.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<Segment>()
        var offset = 0

        current.words.forEachIndexed { index, word ->
            val start = offset
            // The space that follows a word isn't painted
            val end = (start + word.text.trimEnd().length).coerceAtMost(current.text.length)
            offset += word.text.length

            if (end <= start) {
                return@forEachIndexed
            }

            val firstLine = built.getLineForOffset(start)
            val lastLine = built.getLineForOffset(end - 1)

            val pieces = (firstLine..lastLine).map { lineIndex ->
                val from = max(start, built.getLineStart(lineIndex))
                val to = min(end, built.getLineEnd(lineIndex))
                val rtl = built.getParagraphDirection(lineIndex) == Layout.DIR_RIGHT_TO_LEFT
                val a = built.getPrimaryHorizontal(from)
                val b = when (to >= built.getLineEnd(lineIndex)) {
                    true -> if (rtl) built.getLineLeft(lineIndex) else built.getLineRight(lineIndex)
                    false -> built.getPrimaryHorizontal(to)
                }

                Triple(lineIndex, min(a, b), max(a, b)) to rtl
            }

            val total = pieces.sumOf { (box, _) -> (box.third - box.second).toDouble() }
                .toFloat().coerceAtLeast(1f)
            var covered = 0f

            pieces.forEach { (box, rtl) ->
                val width = box.third - box.second
                result += Segment(
                    word = index,
                    left = box.second,
                    right = box.third,
                    top = built.getLineTop(box.first).toFloat(),
                    bottom = built.getLineBottom(box.first).toFloat(),
                    rtl = rtl,
                    startFraction = covered / total,
                    endFraction = (covered + width) / total,
                )
                covered += width
            }
        }

        return result
    }

    override fun onDraw(canvas: Canvas) {
        val built = layout ?: return
        val current = line ?: return

        canvas.save()
        canvas.translate(horizontalPadding.toFloat(), verticalPadding().toFloat())

        paint.shader = null
        paint.color = baseColor

        if (!sweeping || segments.isEmpty()) {
            built.draw(canvas)
            canvas.restore()
            return
        }

        // Words that wait are dim, the ones already sung are fully lit
        paint.color = ColorUtils.setAlphaComponent(baseColor, DIM_ALPHA)
        built.draw(canvas)

        paint.color = baseColor

        doneClip.rewind()
        var hasDone = false
        val partial = mutableListOf<Pair<Segment, Float>>()

        segments.forEach { segment ->
            val word = current.words[segment.word]
            val span = max(1L, word.endMs - word.startMs)
            val progress = ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f)
            val local = ((progress - segment.startFraction) /
                    max(0.0001f, segment.endFraction - segment.startFraction)).coerceIn(0f, 1f)

            when {
                local >= 1f -> {
                    doneClip.addRect(
                        segment.left, segment.top, segment.right, segment.bottom, Path.Direction.CW
                    )
                    hasDone = true
                }

                local > 0f -> partial += segment to local
            }
        }

        if (hasDone) {
            canvas.save()
            canvas.clipPath(doneClip)
            built.draw(canvas)
            canvas.restore()
        }

        partial.forEach { (segment, local) ->
            val width = segment.right - segment.left
            val edge = when (segment.rtl) {
                true -> segment.right - width * local
                false -> segment.left + width * local
            }
            val soft = min(feather, width)

            val clear = ColorUtils.setAlphaComponent(baseColor, 0)
            paint.shader = when (segment.rtl) {
                true -> LinearGradient(edge + soft, 0f, edge, 0f, baseColor, clear, Shader.TileMode.CLAMP)
                false -> LinearGradient(edge - soft, 0f, edge, 0f, baseColor, clear, Shader.TileMode.CLAMP)
            }

            canvas.save()
            canvas.clipRect(segment.left, segment.top, segment.right, segment.bottom)
            built.draw(canvas)
            canvas.restore()
        }

        paint.shader = null
        canvas.restore()
    }

    private fun resolveColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    companion object {
        private const val TEXT_SP = 30f
        private const val BACKGROUND_TEXT_SP = 20f
        private const val DIM_ALPHA = 105
        private const val SWEEP_MARGIN_MS = 300L
    }
}
