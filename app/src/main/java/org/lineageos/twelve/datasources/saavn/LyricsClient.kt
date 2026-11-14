/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import okhttp3.OkHttpClient
import org.lineageos.twelve.datasources.lyrics.LrcParser
import org.lineageos.twelve.datasources.lyrics.LyricsProviders
import org.lineageos.twelve.datasources.lyrics.LyricsQuery
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.models.Result

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

    suspend fun lyrics(
        title: String,
        artist: String?,
        album: String?,
        durationSeconds: Long?,
    ): Result<Lyrics, Error> {
        val query = LyricsQuery(title, artist, album, durationSeconds?.times(1000))

        val cached = synchronized(cache) { cache[query] }
        val lyrics = cached ?: providers.fetch(query)?.also {
            synchronized(cache) { cache[query] = it }
        }

        return lyrics?.let { Result.Success(it) } ?: Result.Failure(Error.NOT_FOUND)
    }

    companion object {
        private const val CACHE_SIZE = 20

        fun parseSynced(raw: String) = LrcParser.parseSynced(raw)

        fun parsePlain(raw: String) = LrcParser.parsePlain(raw)
    }
}
