/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Converts the raw JSON documents of the online catalog to [SaavnEntity] objects.
 * Every function is tolerant: malformed or unknown entries are skipped instead of failing the
 * whole page.
 */
object SaavnParser {
    private val sizeRegex = Regex("(50x50|150x150|250x250)")
    private val numericEntityRegex = Regex("&#(x?)([0-9a-fA-F]+);")

    fun decodeHtml(value: String): String {
        if (!value.contains('&')) {
            return value
        }

        return numericEntityRegex.replace(value) { match ->
            val radix = if (match.groupValues[1].isEmpty()) 10 else 16
            match.groupValues[2].toIntOrNull(radix)?.let { String(Character.toChars(it)) }
                ?: match.value
        }
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    /**
     * Returns the image in the requested resolution and forces HTTPS.
     */
    fun image(url: String?, size: Int = 500): String? {
        if (url.isNullOrBlank()) {
            return null
        }

        return url.replace(sizeRegex, "${size}x$size").replaceFirst("http://", "https://")
    }

    fun results(root: JsonElement?): List<JsonObject> {
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["results"] ?: root["songs"] ?: root["list"]) as? JsonArray
            else -> null
        }

        return array?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    fun entity(element: JsonObject): SaavnEntity? = when (element.string("type")) {
        "song" -> song(element)
        "album" -> album(element)
        "playlist" -> playlist(element)
        "artist" -> artist(element)
        else -> null
    }

    fun entities(elements: List<JsonObject>) = elements.mapNotNull { entity(it) }

    fun song(element: JsonObject): SaavnSong? {
        val id = element.string("id") ?: return null
        val title = element.string("title")?.let(::decodeHtml) ?: return null
        val moreInfo = element.obj("more_info")

        return SaavnSong(
            id = id,
            image = image(element.string("image")),
            title = title,
            artists = artists(element, moreInfo),
            albumId = moreInfo?.string("album_id"),
            albumName = moreInfo?.string("album")?.let(::decodeHtml),
            durationSeconds = moreInfo?.string("duration")?.toLongOrNull(),
            year = element.string("year")?.toIntOrNull(),
            language = element.string("language"),
            encryptedMediaUrl = moreInfo?.string("encrypted_media_url"),
            has320kbps = moreInfo?.string("320kbps") == "true",
            isExplicit = element.string("explicit_content") == "1",
        )
    }

    fun album(element: JsonObject): SaavnAlbum? {
        val id = element.string("id") ?: return null
        val title = element.string("title")?.let(::decodeHtml) ?: return null
        val moreInfo = element.obj("more_info")

        return SaavnAlbum(
            id = id,
            image = image(element.string("image")),
            title = title,
            artists = artists(element, moreInfo),
            year = element.string("year")?.toIntOrNull(),
            songCount = (moreInfo?.string("song_count") ?: element.string("list_count"))
                ?.toIntOrNull(),
            language = element.string("language") ?: moreInfo?.string("language"),
        )
    }

    fun playlist(element: JsonObject): SaavnPlaylist? {
        val id = element.string("id") ?: return null
        val title = element.string("title")?.let(::decodeHtml) ?: return null
        val moreInfo = element.obj("more_info")

        return SaavnPlaylist(
            id = id,
            image = image(element.string("image")),
            title = title,
            description = element.string("header_desc")
                ?.let(::decodeHtml)
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            songCount = (
                    moreInfo?.string("song_count")
                        ?: element.string("list_count")
                        ?: element.string("subtitle")?.substringBefore(' ')
                    )?.toIntOrNull(),
        )
    }

    fun artist(element: JsonObject): SaavnArtist? {
        val id = element.string("id") ?: element.string("artistId") ?: return null
        val name = (element.string("name") ?: element.string("title"))
            ?.let(::decodeHtml) ?: return null

        return SaavnArtist(
            id = id,
            image = image(element.string("image")),
            name = name,
        )
    }

    /**
     * Parse the songs of a station, they are keyed by their position.
     */
    fun suggestions(root: JsonElement): List<SaavnSong> = (root as? JsonObject)
        ?.entries
        ?.filter { it.key != "stationid" }
        ?.mapNotNull { (_, value) -> ((value as? JsonObject)?.get("song") as? JsonObject)?.let(::song) }
        .orEmpty()

    fun albumPage(root: JsonElement): SaavnAlbumPage? {
        val obj = root as? JsonObject ?: return null
        val album = album(obj) ?: return null

        return SaavnAlbumPage(album, results(obj).mapNotNull { song(it) })
    }

    fun playlistPage(root: JsonElement): SaavnPlaylistPage? {
        val obj = root as? JsonObject ?: return null
        val playlist = playlist(obj) ?: return null

        return SaavnPlaylistPage(playlist, results(obj).mapNotNull { song(it) })
    }

    fun artistPage(root: JsonElement): SaavnArtistPage? {
        val obj = root as? JsonObject ?: return null
        val artist = artist(obj) ?: return null

        fun list(key: String) = (obj[key] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

        return SaavnArtistPage(
            artist = artist,
            topSongs = list("topSongs").mapNotNull { song(it) },
            albums = list("topAlbums").mapNotNull { album(it) },
            singles = list("singles").mapNotNull { album(it) },
            playlists = (list("featured_artist_playlist") + list("dedicated_artist_playlist"))
                .mapNotNull { playlist(it) }
                .distinctBy { it.id },
        )
    }

    /**
     * Parse the home page modules, keeping only the ones we know how to show.
     */
    fun shelves(root: JsonElement): List<SaavnShelf> {
        val obj = root as? JsonObject ?: return emptyList()

        return SHELF_KEYS.mapNotNull { key ->
            val items = entities(results(obj[key]))
            items.takeIf { it.isNotEmpty() }?.let { SaavnShelf(key, it) }
        }
    }

    private fun artists(element: JsonObject, moreInfo: JsonObject?): List<SaavnArtistRef> {
        val artistMap = moreInfo?.obj("artistMap")

        for (key in listOf("primary_artists", "featured_artists", "artists")) {
            val refs = (artistMap?.get(key) as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.mapNotNull { artist ->
                    val name = artist.string("name")?.let(::decodeHtml) ?: return@mapNotNull null
                    SaavnArtistRef(artist.string("id"), name, image(artist.string("image")))
                }
                ?.distinctBy { it.id ?: it.name }
                .orEmpty()

            if (refs.isNotEmpty()) {
                return refs
            }
        }

        // The subtitle looks like "Artist 1, Artist 2 - Album" for songs, "Artist 1, Artist 2"
        // for albums
        val subtitle = element.string("subtitle")?.let(::decodeHtml) ?: return emptyList()
        val type = element.string("type")
        val names = when (type) {
            "song" -> subtitle.substringBefore(" - ")
            "album" -> subtitle
            else -> return emptyList()
        }

        return names.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { SaavnArtistRef(null, it) }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    /**
     * Home page modules in display order.
     */
    val SHELF_KEYS = listOf("new_trending", "new_albums", "charts", "top_playlists")
}
