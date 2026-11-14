/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.lineageos.twelve.utils.ImportTrack
import org.lineageos.twelve.utils.ImportedPlaylist

/**
 * What Spotify shows on the page it offers for embedding a public playlist in another site: the
 * name of the playlist and its songs (title, artists, length). Anybody can open it, no account or
 * key is needed. Only the names are read, the audio comes from the online catalog.
 */
object SpotifyEmbed {
    fun url(playlistId: String) = "https://open.spotify.com/embed/playlist/$playlistId"

    private val json = Json { ignoreUnknownKeys = true }

    private val nextData = Regex(
        """<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL
    )

    fun parse(html: String): ImportedPlaylist? = runCatching {
        val data = nextData.find(html)?.groupValues?.get(1) ?: return null

        val entity = json.parseToJsonElement(data).jsonObject["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject
            ?.get("state")?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("entity")?.jsonObject ?: return null

        val tracks = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val title = (track["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            ImportTrack(
                title.trim(),
                (track["subtitle"] as? JsonPrimitive)?.contentOrNull
                    ?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() },
                (track["duration"] as? JsonPrimitive)?.longOrNull?.div(1000),
            )
        }

        tracks.takeIf { it.isNotEmpty() }?.let {
            ImportedPlaylist(
                (entity["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { name -> name.isNotBlank() }
                    ?: "Spotify playlist",
                it,
            )
        }
    }.getOrNull()
}
