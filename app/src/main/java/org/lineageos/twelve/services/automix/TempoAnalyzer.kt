/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds the tempo of a piece of music: the loudness is followed in short steps, the places where
 * it jumps up are the onsets (drum hits, chords), and the tempo is the spacing at which those
 * onsets repeat the most.
 */
object TempoAnalyzer {
    /**
     * The result of listening to a piece of music.
     *
     * @param bpm Beats per minute, in the range of [MIN_BPM] to [MAX_BPM]
     * @param confidence 0 to 1, how much the onsets agree with the tempo
     * @param beatOffsetMs Where a beat falls, relative to the start of the piece
     */
    data class Tempo(val bpm: Double, val confidence: Double, val beatOffsetMs: Double)

    const val MIN_BPM = 70.0
    const val MAX_BPM = 180.0

    /**
     * Length of one step of the loudness curve.
     */
    const val HOP_MS = 10.0

    /**
     * @param mono Samples between -1 and 1
     */
    fun analyze(mono: FloatArray, sampleRate: Int): Tempo? {
        val hop = (sampleRate * HOP_MS / 1000.0).toInt().coerceAtLeast(1)
        val onsets = onsetEnvelope(mono, hop)
        if (onsets.size < MIN_ENVELOPE_STEPS) {
            return null
        }

        // Autocorrelation of the onsets, only over the lags a tempo can have
        val minLag = (60_000.0 / MAX_BPM / HOP_MS).toInt()
        val maxLag = (60_000.0 / MIN_BPM / HOP_MS).toInt()

        val energy = onsets.sumOf { it * it }
        if (energy <= 1e-9) {
            return null
        }

        val correlation = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var sum = 0.0
            for (i in lag until onsets.size) {
                sum += onsets[i] * onsets[i - lag]
            }
            correlation[lag] = sum / energy
        }

        // Tempos people count in are favoured a little, a slow tempo also correlates at half
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60_000.0 / (lag * HOP_MS)
            val prior = 1.0 - 0.15 * abs(bpm - PREFERRED_BPM) / PREFERRED_BPM
            val score = (correlation[lag] + 0.5 * correlation.getOrElse(lag * 2) { 0.0 }) * prior
            val isPeak = correlation[lag] >= correlation[lag - 1] && correlation[lag] >= correlation[lag + 1]
            if (isPeak && score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) {
            return null
        }

        // The peak sits between two steps, find where
        val refined = bestLag + parabolicOffset(
            correlation[bestLag - 1], correlation[bestLag], correlation[bestLag + 1]
        )
        val beatMs = refined * HOP_MS

        return Tempo(
            bpm = 60_000.0 / beatMs,
            confidence = (correlation[bestLag] / max(1e-9, correlation.maxOrNull() ?: 1.0))
                .coerceIn(0.0, 1.0) * correlation[bestLag].coerceIn(0.0, 1.0).let { sqrt(it) },
            beatOffsetMs = beatOffset(onsets, refined),
        )
    }

    /**
     * How much the loudness of every step grew from the one before it.
     */
    internal fun onsetEnvelope(mono: FloatArray, hop: Int): DoubleArray {
        val steps = mono.size / hop
        val loudness = DoubleArray(steps)
        for (step in 0 until steps) {
            var sum = 0.0
            for (i in step * hop until (step + 1) * hop) {
                sum += mono[i] * mono[i].toDouble()
            }
            loudness[step] = sqrt(sum / hop)
        }

        val onsets = DoubleArray(steps)
        for (i in 1 until steps) {
            onsets[i] = max(0.0, loudness[i] - loudness[i - 1])
        }

        // Without the slow trend: only what stands out of its surroundings counts
        val smoothed = DoubleArray(steps)
        val radius = 25
        for (i in 0 until steps) {
            var sum = 0.0
            var count = 0
            for (j in max(0, i - radius)..minOf(steps - 1, i + radius)) {
                sum += onsets[j]
                count++
            }
            smoothed[i] = max(0.0, onsets[i] - sum / count)
        }

        return smoothed
    }

    private fun parabolicOffset(left: Double, middle: Double, right: Double): Double {
        val denominator = left - 2 * middle + right
        return when (abs(denominator) < 1e-12) {
            true -> 0.0
            false -> (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
        }
    }

    /**
     * Where, within one beat, the onsets pile up.
     */
    private fun beatOffset(onsets: DoubleArray, beatSteps: Double): Double {
        val bins = 32
        val histogram = DoubleArray(bins)
        for (i in onsets.indices) {
            val phase = (i % beatSteps) / beatSteps
            histogram[(phase * bins).toInt().coerceIn(0, bins - 1)] += onsets[i]
        }

        val peak = histogram.indices.maxByOrNull { histogram[it] } ?: 0
        return (peak + 0.5) / bins * beatSteps * HOP_MS
    }

    private const val PREFERRED_BPM = 120.0
    private const val MIN_ENVELOPE_STEPS = 400
}
