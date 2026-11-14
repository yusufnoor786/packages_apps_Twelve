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

class AutomixPlannerTest {
    private fun tempo(bpm: Double, confidence: Double = 0.6, offset: Double = 100.0) =
        TempoAnalyzer.Tempo(bpm, confidence, offset)

    @Test
    fun closeTemposAreMatchedOnABeat() {
        val plan = AutomixPlanner.planFor(tempo(120.0), tempo(125.0, offset = 1234.0), 12_000)!!

        // The incoming song is slowed to the tempo of the one that ends
        assertEquals(120.0 / 125.0, plan.incomingSpeed.toDouble(), 0.001)
        // It starts on one of its beats
        assertEquals(1234.0 % (60_000.0 / 125.0), plan.incomingStartMs.toDouble(), 1.0)
        assertEquals(500.0, plan.outgoingBeatMs!!, 0.001)
        // A whole number of beats
        assertEquals(0.0, plan.fadeMs % 500.0, 0.001)
    }

    @Test
    fun doubleAndHalfTimeAreMatchedToo() {
        val double = AutomixPlanner.planFor(tempo(70.0), tempo(138.0), 12_000)!!
        assertEquals(70.0 / (138.0 / 2), double.incomingSpeed.toDouble(), 0.001)

        val half = AutomixPlanner.planFor(tempo(140.0), tempo(72.0), 12_000)!!
        assertEquals(140.0 / (72.0 * 2), half.incomingSpeed.toDouble(), 0.001)
    }

    @Test
    fun farTemposAreLeftToTheCrossfade() {
        assertNull(AutomixPlanner.planFor(tempo(100.0), tempo(128.0), 12_000))
    }

    @Test
    fun guessedTemposAreLeftToTheCrossfade() {
        assertNull(AutomixPlanner.planFor(tempo(120.0, confidence = 0.1), tempo(121.0), 12_000))
        assertNull(AutomixPlanner.planFor(tempo(120.0), tempo(121.0, confidence = 0.1), 12_000))
    }

    @Test
    fun theBlendNeverOutlastsWhatWasAsked() {
        val plan = AutomixPlanner.planFor(tempo(90.0), tempo(90.0), 5_000)!!

        assertNotNull(plan)
        assertTrue(plan.fadeMs <= 5_000)
    }
}
