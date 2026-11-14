/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.lineageos.twelve.ext.executeAsync
import org.lineageos.twelve.utils.ImportTrack
import org.lineageos.twelve.utils.ImportedPlaylist

/**
 * Reads public playlists through the official Spotify Web API. Spotify asks every app to bring its
 * own client id and secret (free, from developer.spotify.com), they are entered in the settings,
 * nothing is shared with anyone else.
 */
class SpotifyClient(
    private val okHttpClient: OkHttpClient,
    private val clientId: String,
    private val clientSecret: String,
) {
    sealed interface Result {
        data class Success(val playlist: ImportedPlaylist) : Result

        /** No client id or secret, or Spotify did not accept them. */
        data object NotAuthorized : Result

        /** The playlist is private, was removed or the link is wrong. */
        data object NotFound : Result

        data object Failed : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun playlist(playlistId: String): Result {
        // The public page of the playlist needs nothing, the API only helps for long playlists
        val embedded = embedded(playlistId)
        if (embedded != null) {
            return Result.Success(embedded)
        }

        if (clientId.isBlank() || clientSecret.isBlank()) {
            return Result.NotFound
        }

        return try {
            val token = token() ?: return Result.NotAuthorized

            val first = get(
                "https://api.spotify.com/v1/playlists/$playlistId".toHttpUrl().newBuilder()
                    .addQueryParameter("fields", "name,tracks(next,items(track(name,duration_ms,artists(name))))")
                    .build()
                    .toString(),
                token,
            ) ?: return Result.NotFound

            val name = (first["name"] as? JsonPrimitive)?.contentOrNull ?: "Spotify playlist"
            val tracks = mutableListOf<ImportTrack>()

            var page: JsonObject? = first["tracks"] as? JsonObject
            var pages = 0
            while (page != null && pages < MAX_PAGES) {
                tracks += readTracks(page["items"] as? JsonArray)

                val next = (page["next"] as? JsonPrimitive)?.contentOrNull ?: break
                page = get(next, token)
                pages++
            }

            Result.Success(ImportedPlaylist(name, tracks))
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Result.Failed
        } catch (_: IllegalArgumentException) {
            Result.Failed
        }
    }

    private suspend fun embedded(playlistId: String) = try {
        val request = Request.Builder()
            .url(SpotifyEmbed.url(playlistId))
            .header("Accept-Language", "en")
            .build()

        okHttpClient.newCall(request).executeAsync().use { response ->
            when (response.isSuccessful) {
                true -> SpotifyEmbed.parse(response.body?.string().orEmpty())
                false -> null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }

    private fun readTracks(items: JsonArray?) = items.orEmpty().mapNotNull { item ->
        val track = item.jsonObject["track"] as? JsonObject ?: return@mapNotNull null
        val title = (track["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null

        ImportTrack(
            title,
            (track["artists"] as? JsonArray)?.mapNotNull {
                (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull
            }?.joinToString(", ")?.takeIf { it.isNotEmpty() },
            (track["duration_ms"] as? JsonPrimitive)?.longOrNull?.div(1000),
        )
    }

    private suspend fun token(): String? {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", Credentials.basic(clientId.trim(), clientSecret.trim()))
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()

        return okHttpClient.newCall(request).executeAsync().use { response ->
            when (response.isSuccessful) {
                true -> (json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["access_token"]
                        as? JsonPrimitive)?.contentOrNull

                false -> null
            }
        }
    }

    private suspend fun get(url: String, token: String): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        return okHttpClient.newCall(request).executeAsync().use { response ->
            when (response.isSuccessful) {
                true -> json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                false -> null
            }
        }
    }

    private companion object {
        private const val MAX_PAGES = 50
    }
}
