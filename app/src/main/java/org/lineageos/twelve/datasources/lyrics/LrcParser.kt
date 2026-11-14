/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.lyrics

import org.lineageos.twelve.models.Lyrics

/**
 * Reads lyrics in the LRC format ("[mm:ss.xx] text") and plain text.
 */
object LrcParser {
    private val timestampRegex = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseSynced(raw: String): Lyrics? {
        val builder = Lyrics.Builder()
        var hasLines = false

        raw.lineSequence().forEach { line ->
            val stamps = timestampRegex.findAll(line).toList()
            if (stamps.isEmpty()) {
                return@forEach
            }

            val text = line.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { stamp ->
                val minutes = stamp.groupValues[1].toLong()
                val seconds = stamp.groupValues[2].toLong()
                val fraction = stamp.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }

                builder.addLine(text, (minutes * 60 + seconds) * 1000 + millis)
                hasLines = true
            }
        }

        return builder.takeIf { hasLines }?.build()
    }

    fun parsePlain(raw: String): Lyrics? {
        val lines = raw.lines().map { it.trim() }
        if (lines.none { it.isNotEmpty() }) {
            return null
        }

        return Lyrics.Builder().apply {
            lines.forEach { addLine(it) }
        }.build()
    }
}
