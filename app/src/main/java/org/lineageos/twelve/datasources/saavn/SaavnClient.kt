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
import java.io.File
import java.security.MessageDigest
import org.lineageos.twelve.ext.executeAsync
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Result

/**
 * Client for the public JioSaavn web API.
 *
 * The endpoint names and request parameters were learnt from the MIT licensed
 * jiosaavn-api project by Sumit Kolhe (https://github.com/sumitkolhe/jiosaavn-api).
 *
 * Responses are cached in memory and, when a [cacheDirectory] is given, on disk. Home page
 * content therefore stays the same between launches until the user refreshes it or it gets old,
 * and what was already seen is still available without a connection.
 *
 * @param okHttpClient The HTTP client to use
 * @param cacheDirectory Where to persist the responses
 */
class SaavnClient(
    private val okHttpClient: OkHttpClient,
    private val cacheDirectory: File? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class CacheEntry(val element: JsonElement, val storedAt: Long)

    /**
     * Forget everything that was stored, the next requests will go to the network.
     */
    fun clearCache() {
        synchronized(cache) { cache.clear() }

        cacheDirectory?.listFiles()?.forEach { it.delete() }
    }

    private fun maxAgeMs(method: String) = when {
        method.startsWith("content.getBrowseModules") || method.startsWith("content.getTrending") ->
            HOME_MAX_AGE_MS

        method.startsWith("search.") -> SEARCH_MAX_AGE_MS
        method.startsWith("webradio.") -> SUGGESTIONS_MAX_AGE_MS
        else -> DETAILS_MAX_AGE_MS
    }

    private fun diskFile(key: String) = cacheDirectory?.let { File(it, key.sha1()) }

    private fun readDisk(key: String): CacheEntry? = runCatching {
        val file = diskFile(key)?.takeIf { it.isFile } ?: return null
        val text = file.readText()
        val separator = text.indexOf('\n')

        CacheEntry(
            json.parseToJsonElement(text.substring(separator + 1)),
            text.substring(0, separator).toLong(),
        )
    }.getOrNull()

    private fun writeDisk(key: String, entry: CacheEntry) {
        runCatching {
            val directory = cacheDirectory ?: return
            directory.mkdirs()

            // Keep the cache small, the oldest responses go first
            directory.listFiles()?.takeIf { it.size > DISK_MAX_FILES }
                ?.sortedBy { it.lastModified() }
                ?.take(DISK_TRIM_FILES)
                ?.forEach { it.delete() }

            File(directory, key.sha1()).writeText(
                "${entry.storedAt}\n${entry.element}"
            )
        }
    }

    private fun String.sha1() = MessageDigest.getInstance("SHA-1")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

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

    /**
     * Songs similar to the given one. The catalog builds a "station" around the song and then
     * serves its next tracks.
     */
    suspend fun suggestions(songId: String, count: Int = 10): Result<JsonElement, Error> {
        val station = request(
            "webradio.createEntityStation",
            context = CONTEXT_ANDROID,
            params = arrayOf(
                "entity_id" to "[\"$songId\"]",
                "entity_type" to "queue",
            ),
        )

        val stationId = ((station as? Result.Success)?.data as? JsonObject)
            ?.get("stationid")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
            ?: return Result.Failure(Error.NOT_FOUND)

        return request(
            "webradio.getSong",
            context = CONTEXT_ANDROID,
            params = arrayOf(
                "stationid" to stationId,
                "k" to count,
            ),
        )
    }

    private suspend fun request(
        method: String,
        vararg params: Pair<String, Any>,
    ) = request(method, CONTEXT_WEB, arrayOf(*params))

    private suspend fun request(
        method: String,
        context: String,
        params: Array<out Pair<String, Any>>,
    ): Result<JsonElement, Error> {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("__call", method)
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", context)
            .apply {
                params.forEach { (key, value) -> addQueryParameter(key, value.toString()) }
            }
            .build()

        val cacheKey = url.toString()
        val maxAgeMs = maxAgeMs(method)
        val cached = synchronized(cache) { cache[cacheKey] } ?: readDisk(cacheKey)
        if (cached != null && System.currentTimeMillis() - cached.storedAt < maxAgeMs) {
            synchronized(cache) { cache[cacheKey] = cached }
            return Result.Success(cached.element)
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            okHttpClient.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    // Better something old than nothing
                    return cached?.let { Result.Success(it.element) } ?: Result.Failure(Error.IO)
                }

                val body = response.body?.string().orEmpty()
                val element = json.parseToJsonElement(body)

                // Errors are reported with a 200 and a "status": "failure" object
                if (element is JsonObject && element["status"]?.toString()?.contains("failure") == true) {
                    Result.Failure(Error.NOT_FOUND)
                } else {
                    CacheEntry(element, System.currentTimeMillis()).let {
                        synchronized(cache) { cache[cacheKey] = it }
                        writeDisk(cacheKey, it)
                    }
                    Result.Success(element)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Offline: show what was seen last time
            cached?.let { Result.Success(it.element) } ?: Result.Failure(Error.IO, e)
        } catch (e: SerializationException) {
            Result.Failure(Error.DESERIALIZATION, e)
        } catch (e: IllegalArgumentException) {
            Result.Failure(Error.DESERIALIZATION, e)
        }
    }

    companion object {
        private const val CONTEXT_WEB = "web6dot0"
        private const val CONTEXT_ANDROID = "android"
        private const val CACHE_SIZE = 80
        private const val HOME_MAX_AGE_MS = 12 * 60 * 60 * 1000L
        private const val SEARCH_MAX_AGE_MS = 30 * 60 * 1000L
        private const val DETAILS_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val SUGGESTIONS_MAX_AGE_MS = 10 * 60 * 1000L
        private const val DISK_MAX_FILES = 300
        private const val DISK_TRIM_FILES = 60
        private const val BASE_URL = "https://www.jiosaavn.com/api.php"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/134.0.0.0 Mobile Safari/537.36"
    }
}
