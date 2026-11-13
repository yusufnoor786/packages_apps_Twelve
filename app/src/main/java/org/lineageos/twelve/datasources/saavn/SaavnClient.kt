/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.lineageos.twelve.ext.executeAsync
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Result

/**
 * Client for the public JioSaavn web API.
 *
 * The endpoint names and request parameters were learnt from the MIT licensed
 * jiosaavn-api project by Sumit Kolhe (https://github.com/sumitkolhe/jiosaavn-api).
 *
 * @param okHttpClient The HTTP client to use
 */
class SaavnClient(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class CacheEntry(val element: JsonElement, val storedAt: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) =
            size > CACHE_SIZE
    }

    suspend fun searchSongs(query: String, page: Int = 1, count: Int = 20) =
        request("search.getResults", "q" to query, "p" to page, "n" to count)

    suspend fun searchAlbums(query: String, page: Int = 1, count: Int = 20) =
        request("search.getAlbumResults", "q" to query, "p" to page, "n" to count)

    suspend fun searchArtists(query: String, page: Int = 1, count: Int = 20) =
        request("search.getArtistResults", "q" to query, "p" to page, "n" to count)

    suspend fun searchPlaylists(query: String, page: Int = 1, count: Int = 20) =
        request("search.getPlaylistResults", "q" to query, "p" to page, "n" to count)

    suspend fun songs(ids: List<String>) = request("song.getDetails", "pids" to ids.joinToString(","))

    suspend fun album(id: String) = request("content.getAlbumDetails", "albumid" to id)

    suspend fun playlist(id: String, page: Int = 1, count: Int = 100) = request(
        "playlist.getDetails", "listid" to id, "p" to page, "n" to count
    )

    suspend fun artist(id: String, songs: Int = 20, albums: Int = 20) = request(
        "artist.getArtistPageDetails",
        "artistId" to id,
        "n_song" to songs,
        "n_album" to albums,
        "page" to 0,
        "category" to "",
        "sort_order" to "",
        "sort_by" to "",
    )

    suspend fun artistSongs(id: String, page: Int = 0) = request(
        "artist.getArtistMoreSong",
        "artistId" to id,
        "page" to page,
        "category" to "",
        "sort_order" to "",
        "sort_by" to "",
    )

    suspend fun modules(languages: String) = request(
        "content.getBrowseModules", "language" to languages
    )

    suspend fun trending(type: String, language: String) = request(
        "content.getTrending", "entity_type" to type, "entity_language" to language
    )

    private suspend fun request(
        method: String,
        vararg params: Pair<String, Any>,
    ): Result<JsonElement, Error> {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("__call", method)
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", "web6dot0")
            .apply {
                params.forEach { (key, value) -> addQueryParameter(key, value.toString()) }
            }
            .build()

        val cacheKey = url.toString()
        synchronized(cache) { cache[cacheKey] }?.takeIf {
            System.currentTimeMillis() - it.storedAt < CACHE_TTL_MS
        }?.let { return Result.Success(it.element) }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            okHttpClient.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    return Result.Failure(Error.IO)
                }

                val body = response.body?.string().orEmpty()
                val element = json.parseToJsonElement(body)

                // Errors are reported with a 200 and a "status": "failure" object
                if (element is JsonObject && element["status"]?.toString()?.contains("failure") == true) {
                    Result.Failure(Error.NOT_FOUND)
                } else {
                    synchronized(cache) {
                        cache[cacheKey] = CacheEntry(element, System.currentTimeMillis())
                    }
                    Result.Success(element)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.Failure(Error.IO, e)
        } catch (e: SerializationException) {
            Result.Failure(Error.DESERIALIZATION, e)
        } catch (e: IllegalArgumentException) {
            Result.Failure(Error.DESERIALIZATION, e)
        }
    }

    companion object {
        private const val CACHE_SIZE = 80
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val BASE_URL = "https://www.jiosaavn.com/api.php"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/134.0.0.0 Mobile Safari/537.36"
    }
}
