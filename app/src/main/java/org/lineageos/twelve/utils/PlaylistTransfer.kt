/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A playlist that can leave the app: saved in a file for later, or sent to a friend as a short
 * piece of text. Songs are the ids of the online catalog, which are the same on every phone, the
 * title and artist are there to show what is inside and to find a song again if its id changes.
 */
@Serializable
data class SharedPlaylist(
    val name: String,
    val songs: List<Song>,
    val format: String = FORMAT,
    val version: Int = VERSION,
) {
    @Serializable
    data class Song(
        val id: String,
        val title: String? = null,
        val artist: String? = null,
    )

    /**
     * The playlist as a file.
     */
    fun toJson(): String = json.encodeToString(serializer(), this)

    /**
     * The playlist as a code, short enough for a message: the file, compressed.
     */
    fun toCode(): String {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(toJson().toByteArray()) }
        }.toByteArray()

        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * What to send to a friend: a line they can read and the code to import.
     */
    fun toShareText(): String = buildString {
        append(name)
        append(" · ")
        append(songs.size)
        append(" songs\n\n")
        append(toCode())
    }

    companion object {
        const val FORMAT = "twelve-playlist"
        const val VERSION = 1
        const val CODE_PREFIX = "twelve-playlist:"
        const val FILE_EXTENSION = ".twelve-playlist.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        private val codeRegex = Regex("""twelve-playlist:([A-Za-z0-9_-]+)""")

        /**
         * Reads a playlist from anything it was sent as: the file, the code, or a message with the
         * code somewhere inside.
         */
        fun parse(text: String): SharedPlaylist? {
            val trimmed = text.trim()

            val decoded = when {
                trimmed.startsWith("{") -> trimmed
                else -> codeRegex.find(trimmed)?.groupValues?.get(1)?.let(::fromCode)
            } ?: return null

            return runCatching { json.decodeFromString(serializer(), decoded) }
                .getOrNull()
                ?.takeIf { it.format == FORMAT && it.songs.isNotEmpty() }
                ?.let { playlist ->
                    playlist.copy(
                        name = playlist.name.trim().ifEmpty { "Playlist" },
                        songs = playlist.songs.filter { it.id.isNotBlank() }.distinctBy { it.id },
                    )
                }
        }

        private fun fromCode(code: String) = runCatching {
            val bytes = Base64.getUrlDecoder().decode(code)
            GZIPInputStream(bytes.inputStream()).use { it.readBytes().decodeToString() }
        }.getOrNull()
    }
}
