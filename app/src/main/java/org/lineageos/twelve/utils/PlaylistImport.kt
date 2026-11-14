/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import java.text.Normalizer
import kotlin.math.abs

/**
 * A song of a playlist that comes from another service. It is only a name: the matching song of
 * the online catalog is looked up by it.
 */
data class ImportTrack(
    val title: String,
    val artist: String?,
    val durationSeconds: Long? = null,
)

/**
 * A playlist that comes from another service and has to be matched to the online catalog.
 */
data class ImportedPlaylist(
    val name: String,
    val tracks: List<ImportTrack>,
)

/**
 * Reading playlists written by other services and tools, and telling which song of the catalog is
 * the one they mean.
 */
object PlaylistImport {
    private val spotifyLink = Regex(
        """(?:open\.spotify\.com/(?:intl-[a-z]+/)?playlist/|spotify:playlist:)([A-Za-z0-9]{10,40})"""
    )

    /**
     * The id of the Spotify playlist a link or URI points to.
     */
    fun spotifyPlaylistId(text: String): String? =
        spotifyLink.find(text.trim())?.groupValues?.get(1)

    /**
     * Reads a playlist from what a user can paste or open: a CSV exported from Spotify (Exportify
     * and similar tools), or a list with one "Title - Artist" per line.
     */
    fun parseText(name: String, text: String): ImportedPlaylist? {
        val rows = parseCsv(text)
        val header = rows.firstOrNull()?.map { it.trim().lowercase() }

        val titleColumn = header?.indexOfFirst { it in TITLE_COLUMNS } ?: -1
        val tracks = when {
            titleColumn >= 0 -> {
                val artistColumn = header!!.indexOfFirst { it in ARTIST_COLUMNS }
                val durationColumn = header.indexOfFirst { it in DURATION_COLUMNS }

                rows.drop(1).mapNotNull { row ->
                    val title = row.getOrNull(titleColumn)?.trim().orEmpty()
                    if (title.isEmpty()) {
                        return@mapNotNull null
                    }

                    ImportTrack(
                        title,
                        row.getOrNull(artistColumn)?.trim()?.takeIf { it.isNotEmpty() },
                        row.getOrNull(durationColumn)?.trim()?.toLongOrNull()?.let {
                            // Exportify has milliseconds
                            if (it > 10_000) it / 1000 else it
                        },
                    )
                }
            }

            else -> text.lineSequence().mapNotNull { line ->
                val trimmed = line.trim().trimStart('-', '*', ' ')
                    .replace(Regex("""^\d+[.)]\s+"""), "")
                if (trimmed.isEmpty()) {
                    return@mapNotNull null
                }

                val parts = trimmed.split(" - ", " – ", " — ", limit = 2)
                ImportTrack(parts[0].trim(), parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() })
            }.toList()
        }

        return tracks.takeIf { it.isNotEmpty() }?.let {
            ImportedPlaylist(name.ifBlank { "Imported playlist" }, it)
        }
    }

    private val TITLE_COLUMNS = setOf("track name", "title", "name", "song", "song name")
    private val ARTIST_COLUMNS = setOf(
        "artist name(s)", "artist names", "artist name", "artist", "artists"
    )
    private val DURATION_COLUMNS = setOf("track duration (ms)", "duration (ms)", "duration")

    /**
     * A small CSV reader: quoted fields, commas and line breaks inside quotes.
     */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.clear()
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) {
                rows.add(row)
            }
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endField()
                !quoted && c == '\n' -> endRow()
                !quoted && c == '\r' -> Unit
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            endRow()
        }

        return rows
    }

    /**
     * Lower case, without accents, brackets, "feat." and punctuation: what is left of a name when
     * only the words matter.
     */
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

        return decomposed
            .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""\s+-\s+.*$"""), " ")
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
    }

    /**
     * How well a song of the catalog is the one a track names, from 0 up: the title has to match,
     * artists and length raise the score.
     */
    fun score(
        track: ImportTrack,
        title: String,
        artists: List<String>,
        durationSeconds: Long?,
    ): Double {
        val wantedTitle = normalize(track.title)
        val foundTitle = normalize(title)
        if (wantedTitle.isEmpty() || foundTitle.isEmpty()) {
            return 0.0
        }

        var score = when {
            wantedTitle == foundTitle -> 3.0
            foundTitle.startsWith(wantedTitle) || wantedTitle.startsWith(foundTitle) -> 1.5
            else -> return 0.0
        }

        val wantedArtists = track.artist?.split(',', '&', ';')
            ?.map { normalize(it) }?.filter { it.isNotEmpty() }.orEmpty()
        val foundArtists = artists.flatMap { it.split(',', '&', ';') }
            .map { normalize(it) }.filter { it.isNotEmpty() }
        if (wantedArtists.isNotEmpty() && foundArtists.isNotEmpty()) {
            score += when (wantedArtists.any { it in foundArtists }) {
                true -> 2.0
                false -> -1.0
            }
        }

        if (track.durationSeconds != null && durationSeconds != null) {
            val difference = abs(track.durationSeconds - durationSeconds)
            score += when {
                difference <= 3 -> 1.0
                difference <= 10 -> 0.4
                difference > 30 -> -1.0
                else -> 0.0
            }
        }

        return score
    }

    /**
     * Below this the best candidate is probably another song.
     */
    const val MINIMUM_SCORE = 2.0
}
