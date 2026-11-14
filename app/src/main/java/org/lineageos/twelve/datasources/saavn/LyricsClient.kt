/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.lineageos.twelve.datasources.lyrics.LrcParser
import org.lineageos.twelve.datasources.lyrics.LyricsProviders
import org.lineageos.twelve.datasources.lyrics.LyricsQuery
import org.lineageos.twelve.models.Lyrics

/**
 * Finds lyrics for a song. Word synchronized lyrics are preferred (lrc.red, BetterLyrics,
 * LyricsPlus), then line synchronized ones (LRCLIB, Unison, KuGou) and plain text last.
 */
class LyricsClient(
    okHttpClient: OkHttpClient,
) {
    private val providers = LyricsProviders(okHttpClient)

    // Lyrics of the songs that were opened recently, they don't change
    private val cache = object : LinkedHashMap<LyricsQuery, Lyrics?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricsQuery, Lyrics?>?) =
            size > CACHE_SIZE
    }

    /**
     * The lyrics of a song as they are found: the first thing that is found is handed out at
     * once, better lyrics follow while the slower providers answer.
     */
    fun lyrics(
        title: String,
        artist: String?,
        album: String?,
        durationSeconds: Long?,
    ): Flow<Lyrics> {
        val query = LyricsQuery(title, artist, album, durationSeconds?.times(1000))

        val cached = synchronized(cache) { cache[query] }
        if (cached != null && LyricsProviders.rank(cached) >= BEST_RANK) {
            return flowOf(cached)
        }

        return flow {
            cached?.let { emit(it) }

            providers.fetch(query).collect { lyrics ->
                synchronized(cache) { cache[query] = lyrics }
                emit(lyrics)
            }
        }
    }

    companion object {
        private const val CACHE_SIZE = 20
        private const val BEST_RANK = 3

        fun parseSynced(raw: String) = LrcParser.parseSynced(raw)

        fun parsePlain(raw: String) = LrcParser.parsePlain(raw)
    }
}
