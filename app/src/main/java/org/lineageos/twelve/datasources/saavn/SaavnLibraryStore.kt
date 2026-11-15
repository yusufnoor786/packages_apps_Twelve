/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredPlaylist(
    val id: Long,
    val name: String,
    val songIds: List<String> = emptyList(),
    val modifiedAt: Long = 0L,
    // The same on every device of an account, the id is only known to this one
    val uid: String = "",
)

@Serializable
data class StoredLibrary(
    val favorites: List<String> = emptyList(),
    val playlists: List<StoredPlaylist> = emptyList(),
    val nextPlaylistId: Long = 1L,
    // When each favorite was added, and when songs stopped being favorites
    val favoriteTimes: Map<String, Long> = emptyMap(),
    val removedFavorites: Map<String, Long> = emptyMap(),
    // Playlists deleted, by uid: an account sync must not bring them back
    val deletedPlaylists: Map<String, Long> = emptyMap(),
)

/**
 * The user's favorites and playlists for the online catalog. The catalog is read only, so those
 * are kept on the device.
 *
 * @param file The file used to persist the library
 */
class SaavnLibraryStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val mutableLibrary = MutableStateFlow(load().withIdentity().also { save(it) })
    val library: StateFlow<StoredLibrary> = mutableLibrary.asStateFlow()

    val favoritesIds get() = library.value.favorites

    @Synchronized
    fun setFavorite(songId: String, favorite: Boolean) = update { library ->
        val now = System.currentTimeMillis()
        when (favorite) {
            true -> library.copy(
                favorites = listOf(songId) + library.favorites.filterNot { it == songId },
                favoriteTimes = library.favoriteTimes + (songId to now),
                removedFavorites = library.removedFavorites - songId,
            )

            false -> library.copy(
                favorites = library.favorites.filterNot { it == songId },
                favoriteTimes = library.favoriteTimes - songId,
                removedFavorites = library.removedFavorites + (songId to now),
            )
        }
    }

    @Synchronized
    fun createPlaylist(name: String): Long {
        var createdId = 0L
        update { library ->
            createdId = library.nextPlaylistId
            library.copy(
                playlists = library.playlists + StoredPlaylist(
                    createdId, name, emptyList(), System.currentTimeMillis(), newUid()
                ),
                nextPlaylistId = createdId + 1,
            )
        }
        return createdId
    }

    @Synchronized
    fun createPlaylist(name: String, songIds: List<String>): Long {
        var createdId = 0L
        update { library ->
            createdId = library.nextPlaylistId
            library.copy(
                playlists = library.playlists + StoredPlaylist(
                    createdId, name, songIds.distinct(), System.currentTimeMillis(), newUid()
                ),
                nextPlaylistId = createdId + 1,
            )
        }
        return createdId
    }

    @Synchronized
    fun renamePlaylist(playlistId: Long, name: String) = updatePlaylist(playlistId) {
        it.copy(name = name)
    }

    @Synchronized
    fun deletePlaylist(playlistId: Long) = update { library ->
        val deleted = library.playlists.firstOrNull { it.id == playlistId }
        library.copy(
            playlists = library.playlists.filterNot { it.id == playlistId },
            deletedPlaylists = deleted?.uid?.takeIf { it.isNotEmpty() }
                ?.let { library.deletedPlaylists + (it to System.currentTimeMillis()) }
                ?: library.deletedPlaylists,
        )
    }

    /**
     * Put a library that came from the account in place of this one, see the account sync.
     */
    @Synchronized
    fun replace(transform: (StoredLibrary) -> StoredLibrary) = update(transform)

    @Synchronized
    fun addToPlaylist(playlistId: Long, songId: String) = updatePlaylist(playlistId) {
        when (songId in it.songIds) {
            true -> it
            false -> it.copy(songIds = it.songIds + songId)
        }
    }

    @Synchronized
    fun removeFromPlaylist(playlistId: Long, songId: String) = updatePlaylist(playlistId) {
        it.copy(songIds = it.songIds.filterNot { id -> id == songId })
    }

    fun playlist(playlistId: Long) = library.value.playlists.firstOrNull { it.id == playlistId }

    private fun updatePlaylist(playlistId: Long, transform: (StoredPlaylist) -> StoredPlaylist) =
        update { library ->
            library.copy(
                playlists = library.playlists.map {
                    when (it.id == playlistId) {
                        true -> transform(it).copy(modifiedAt = System.currentTimeMillis())
                        false -> it
                    }
                }
            )
        }

    private inline fun update(transform: (StoredLibrary) -> StoredLibrary) {
        val updated = transform(mutableLibrary.value)
        if (updated == mutableLibrary.value) {
            return
        }

        mutableLibrary.value = updated
        save(updated)
    }

    /**
     * Libraries saved before the account sync get what it needs: an uid for each playlist and a
     * time for each favorite (an old one, so that a removal anywhere wins over it).
     */
    private fun StoredLibrary.withIdentity() = copy(
        playlists = playlists.map { if (it.uid.isEmpty()) it.copy(uid = newUid()) else it },
        favoriteTimes = favorites.associateWith { favoriteTimes[it] ?: 1L },
    )

    private fun newUid() = java.util.UUID.randomUUID().toString()

    private fun load() = runCatching {
        json.decodeFromString<StoredLibrary>(file.readText())
    }.getOrDefault(StoredLibrary())

    private fun save(library: StoredLibrary) {
        runCatching {
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(serializer<StoredLibrary>(), library))
            if (!temporary.renameTo(file)) {
                file.writeText(json.encodeToString(serializer<StoredLibrary>(), library))
                temporary.delete()
            }
        }
    }
}
