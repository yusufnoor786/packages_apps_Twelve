/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.lineageos.twelve.ext.executeAsync
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.models.Result

/**
 * Fetches lyrics from LRCLIB (https://lrclib.net), a free community lyrics database that doesn't
 * require an API key. Synchronized lyrics are preferred.
 */
class LyricsClient(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lyrics(
        title: String,
        artist: String?,
        album: String?,
        durationSeconds: Long?,
    ): Result<Lyrics, Error> {
        val exact = get(
            "get",
            "track_name" to title,
            "artist_name" to artist,
            "album_name" to album,
            "duration" to durationSeconds?.toString(),
        ) as? JsonObject

        val match = exact ?: (get(
            "search",
            "track_name" to title,
            "artist_name" to artist,
        ) as? JsonArray)?.firstNotNullOfOrNull { it as? JsonObject }
        ?: return Result.Failure(Error.NOT_FOUND)

        val synced = match.string("syncedLyrics")
        val plain = match.string("plainLyrics")

        return when {
            synced != null -> parseSynced(synced)
            plain != null -> parsePlain(plain)
            else -> null
        }?.let { Result.Success(it) } ?: Result.Failure(Error.NOT_FOUND)
    }

    private suspend fun get(path: String, vararg params: Pair<String, String?>) = try {
        val url = "$BASE_URL/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) ->
                value?.takeIf { it.isNotBlank() }?.let { addQueryParameter(key, it) }
            }
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        okHttpClient.newCall(request).executeAsync().use { response ->
            when (response.isSuccessful) {
                true -> json.parseToJsonElement(response.body?.string().orEmpty())
                false -> null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val USER_AGENT = "Twelve (https://github.com/LineageOS/android_packages_apps_Twelve)"

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
}
