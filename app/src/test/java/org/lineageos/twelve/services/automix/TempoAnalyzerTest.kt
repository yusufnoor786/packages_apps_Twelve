/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TempoAnalyzerTest {
    private val sampleRate = 22_050

    /**
     * A kick drum on every beat at the given tempo, with a bit of a hi-hat in between.
     */
    private fun drums(bpm: Double, seconds: Int, offsetMs: Double = 0.0): FloatArray {
        val out = FloatArray(sampleRate * seconds)
        val beat = 60.0 / bpm

        var time = offsetMs / 1000.0
        while (time < seconds) {
            val start = (time * sampleRate).toInt()
            for (i in 0 until (0.12 * sampleRate).toInt()) {
                if (start + i >= out.size) break
                val t = i.toDouble() / sampleRate
                out[start + i] += (sin(2 * PI * 60 * t) * exp(-t * 30)).toFloat()
            }
            time += beat
        }

        return out
    }

    @Test
    fun findsTheTempoOfADrumLoop() {
        for (bpm in listOf(90.0, 100.0, 120.0, 128.0, 140.0)) {
            val tempo = TempoAnalyzer.analyze(drums(bpm, 30), sampleRate)

            assertNotNull("no tempo at $bpm", tempo)
            assertEquals("tempo at $bpm", bpm, tempo!!.bpm, bpm * 0.015)
        }
    }

    @Test
    fun findsWhereTheBeatsFall() {
        val tempo = TempoAnalyzer.analyze(drums(120.0, 30, offsetMs = 200.0), sampleRate)!!
        val beatMs = 500.0

        // Onsets of the kick are felt a few milliseconds after it starts, the offset is modulo a beat
        val offset = tempo.beatOffsetMs % beatMs
        val distance = minOf(abs(offset - 200.0), beatMs - abs(offset - 200.0))
        assertTrue("offset $offset", distance < 60.0)
    }

    @Test
    fun silenceHasNoTempo() {
        assertNull(TempoAnalyzer.analyze(FloatArray(sampleRate * 20), sampleRate))
    }

    @Test
    fun tooShortHasNoTempo() {
        assertNull(TempoAnalyzer.analyze(drums(120.0, 2), sampleRate))
    }

    private fun abs(value: Double) = if (value < 0) -value else value
}
