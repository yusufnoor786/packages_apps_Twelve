/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.lineageos.twelve.datasources.saavn.StoredLibrary
import org.lineageos.twelve.datasources.saavn.StoredPlaylist

@Serializable
data class SyncPlaylist(
    val name: String,
    val songIds: List<String> = emptyList(),
    val modifiedAt: Long = 0L,
)

/**
 * What an account keeps: the favorites and playlists of the online catalog and some settings.
 * Every change carries its time, so that two devices can be merged without losing anything:
 * removals are kept as well, for a while, so that they win over older additions.
 */
@Serializable
data class SyncDocument(
    val version: Int = 1,
    val favorites: Map<String, Long> = emptyMap(),
    val removedFavorites: Map<String, Long> = emptyMap(),
    val playlists: Map<String, SyncPlaylist> = emptyMap(),
    val deletedPlaylists: Map<String, Long> = emptyMap(),
    val settings: Map<String, JsonElement> = emptyMap(),
    val settingsModifiedAt: Long = 0L,
) {
    companion object {
        // Removals older than this are forgotten, every device has seen them by then
        const val TOMBSTONE_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

        /**
         * Both documents in one: the latest change of everything wins.
         */
        fun merge(a: SyncDocument, b: SyncDocument): SyncDocument {
            // Favorites: added and removed times of each song, the latest says what it is
            val added = maxTimes(a.favorites, b.favorites)
            val removed = maxTimes(a.removedFavorites, b.removedFavorites)
            val favorites = added.filter { (id, at) -> at > (removed[id] ?: Long.MIN_VALUE) }
            val removedFavorites = removed.filter { (id, at) -> at >= (added[id] ?: Long.MIN_VALUE) }

            // Playlists: the latest version of each, unless it was deleted after it
            val deleted = maxTimes(a.deletedPlaylists, b.deletedPlaylists)
            val playlists = (a.playlists.keys + b.playlists.keys).mapNotNull { uid ->
                val newest = listOfNotNull(a.playlists[uid], b.playlists[uid])
                    .maxBy { it.modifiedAt }
                when (newest.modifiedAt > (deleted[uid] ?: Long.MIN_VALUE)) {
                    true -> uid to newest
                    false -> null
                }
            }.toMap()
            val deletedPlaylists = deleted.filterKeys { it !in playlists }

            // Settings: all of the latest ones, an empty set never wins
            val settingsFromB = b.settings.isNotEmpty() &&
                    (a.settings.isEmpty() || b.settingsModifiedAt > a.settingsModifiedAt)
            val settings = if (settingsFromB) b else a

            return SyncDocument(
                favorites = favorites,
                removedFavorites = removedFavorites,
                playlists = playlists,
                deletedPlaylists = deletedPlaylists,
                settings = settings.settings,
                settingsModifiedAt = settings.settingsModifiedAt,
            )
        }

        private fun maxTimes(a: Map<String, Long>, b: Map<String, Long>) =
            (a.keys + b.keys).associateWith { maxOf(a[it] ?: Long.MIN_VALUE, b[it] ?: Long.MIN_VALUE) }

        /**
         * The document of a library and its settings.
         */
        fun of(
            library: StoredLibrary,
            settings: Map<String, JsonElement>,
            settingsModifiedAt: Long,
        ) = SyncDocument(
            favorites = library.favorites.associateWith { library.favoriteTimes[it] ?: 1L },
            removedFavorites = library.removedFavorites,
            playlists = library.playlists.filter { it.uid.isNotEmpty() }.associate {
                it.uid to SyncPlaylist(it.name, it.songIds, it.modifiedAt)
            },
            deletedPlaylists = library.deletedPlaylists,
            settings = settings,
            settingsModifiedAt = settingsModifiedAt,
        )
    }

    /**
     * Without the removals that are old enough to be forgotten.
     */
    fun pruned(now: Long) = copy(
        removedFavorites = removedFavorites.filterValues { now - it < TOMBSTONE_MAX_AGE_MS },
        deletedPlaylists = deletedPlaylists.filterValues { now - it < TOMBSTONE_MAX_AGE_MS },
    )

    /**
     * [library] made to hold what this document holds. Playlists keep their place and their
     * number on this device, new ones are added at the end.
     */
    fun applyTo(library: StoredLibrary): StoredLibrary {
        var nextId = library.nextPlaylistId
        val known = library.playlists.associateBy { it.uid }

        val kept = library.playlists.mapNotNull { local ->
            playlists[local.uid]?.let {
                local.copy(name = it.name, songIds = it.songIds, modifiedAt = it.modifiedAt)
            }
        }
        val added = playlists.filterKeys { it !in known }
            .entries
            .sortedBy { it.value.modifiedAt }
            .map { (uid, playlist) ->
                StoredPlaylist(nextId++, playlist.name, playlist.songIds, playlist.modifiedAt, uid)
            }

        return library.copy(
            favorites = favorites.entries.sortedByDescending { it.value }.map { it.key },
            favoriteTimes = favorites,
            removedFavorites = removedFavorites,
            playlists = kept + added,
            nextPlaylistId = nextId,
            deletedPlaylists = deletedPlaylists,
        )
    }
}
