/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services.automix

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.lineageos.twelve.services.CrossfadeController
import kotlin.math.abs
import kotlin.math.pow

/**
 * Automix (beta): lets one song run into the next like a DJ would.
 *
 * Before a song ends the tail of it and the head of the next one are listened to for their tempo.
 * When the tempos are close (also at double or half the speed) the next song starts at the tempo
 * of the one that is ending, on a beat, and glides back to its own during the blend, which lasts a
 * whole number of beats. When they are not, or nothing could be heard, the blend is a plain
 * crossfade.
 *
 * What it does not do: it does not line up bars (no downbeat detection) or keys.
 */
class AutomixPlanner(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private data class Analysis(val tail: TempoAnalyzer.Tempo?, val head: TempoAnalyzer.Tempo?)

    private val tails = LruCache<String, TempoAnalyzer.Tempo?>(MAX_CACHED)
    private val heads = LruCache<String, TempoAnalyzer.Tempo?>(MAX_CACHED)
    private var job: Job? = null

    /**
     * Start listening to what is about to be mixed, it takes a few seconds.
     */
    fun prepare(current: MediaItem?, next: MediaItem?) {
        job?.cancel()
        if (current == null || next == null) {
            return
        }

        job = scope.launch(Dispatchers.IO) {
            if (current.mediaId !in tails) {
                tails[current.mediaId] = analyze(current, fromEnd = true)
            }
            if (next.mediaId !in heads) {
                heads[next.mediaId] = analyze(next, fromEnd = false)
            }

            Log.d(
                LOG_TAG,
                "Tempo of ${current.mediaId} (tail): ${tails[current.mediaId]}, " +
                        "${next.mediaId} (head): ${heads[next.mediaId]}"
            )
        }
    }

    private fun analyze(item: MediaItem, fromEnd: Boolean): TempoAnalyzer.Tempo? {
        val uri = item.localConfiguration?.uri ?: return null

        val window = AudioWindowDecoder.decode(
            context,
            uri,
            startMs = when (fromEnd) {
                true -> -WINDOW_MS
                false -> 0L
            },
            lengthMs = WINDOW_MS,
        ) ?: return null

        return TempoAnalyzer.analyze(window.mono, window.sampleRate)?.let { tempo ->
            // The grid is relative to the window, make it relative to the song
            tempo.copy(beatOffsetMs = tempo.beatOffsetMs + window.windowStartMs)
        }
    }

    /**
     * The transition from [outgoing] to [incoming]; null when there is nothing better than a plain
     * crossfade to offer.
     */
    fun plan(
        outgoing: MediaItem,
        incoming: MediaItem?,
        defaultFadeMs: Long,
    ): CrossfadeController.TransitionPlan? {
        val out = tails[outgoing.mediaId] ?: return null
        val into = incoming?.let { heads[it.mediaId] } ?: return null

        return planFor(out, into, defaultFadeMs)
    }

    companion object {
        private const val LOG_TAG = "AutomixPlanner"

        private const val WINDOW_MS = 30_000L
        private const val MAX_CACHED = 24

        /**
         * The transition between two songs whose tempos are known.
         */
        fun planFor(
            out: TempoAnalyzer.Tempo,
            into: TempoAnalyzer.Tempo,
            defaultFadeMs: Long,
        ): CrossfadeController.TransitionPlan? {
        if (out.confidence < MIN_CONFIDENCE || into.confidence < MIN_CONFIDENCE) {
            return null
        }

        // Compare at the closest of same, double or half speed
        val ratio = listOf(1.0, 2.0, 0.5)
            .map { factor -> out.bpm / (into.bpm * factor) to factor }
            .minByOrNull { abs(it.first - 1.0) }!!
        val speed = ratio.first
        if (abs(speed - 1.0) > MAX_TEMPO_CHANGE) {
            return null
        }

        // Two bars of the outgoing song at the most, shorter if the song is about to end
        val beatMs = 60_000.0 / out.bpm
        val beats = (defaultFadeMs / beatMs).toInt().coerceIn(MIN_BEATS, MAX_BEATS)
        val fadeMs = (beats * beatMs).toLong().coerceAtMost(defaultFadeMs.coerceAtLeast(MIN_FADE_MS))

        // The incoming song starts on one of its beats, a beat of its own tempo
        val incomingBeatMs = 60_000.0 / (into.bpm * ratio.second)
        val firstBeat = into.beatOffsetMs % incomingBeatMs

        return CrossfadeController.TransitionPlan(
            fadeMs = fadeMs,
            incomingSpeed = speed.toFloat(),
            incomingStartMs = firstBeat.toLong(),
            outgoingBeatMs = beatMs,
            outgoingBeatOffsetMs = out.beatOffsetMs,
        )
        }

        private const val MIN_CONFIDENCE = 0.25

        // A tempo is stretched by this much at the most, beyond it sounds like it
        private const val MAX_TEMPO_CHANGE = 0.08

        private const val MIN_BEATS = 8
        private const val MAX_BEATS = 32
        private const val MIN_FADE_MS = 4_000L
    }

    private class LruCache<K, V>(private val size: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = this.size > size
        }

        operator fun contains(key: K) = synchronized(map) { map.containsKey(key) }
        operator fun get(key: K) = synchronized(map) { map[key] }
        operator fun set(key: K, value: V) = synchronized(map) { map[key] = value }
    }

}
