/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredPlaylist(
    val id: Long,
    val name: String,
    val songIds: List<String> = emptyList(),
    val modifiedAt: Long = 0L,
)

@Serializable
data class StoredLibrary(
    val favorites: List<String> = emptyList(),
    val playlists: List<StoredPlaylist> = emptyList(),
    val nextPlaylistId: Long = 1L,
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

    private val mutableLibrary = MutableStateFlow(load())
    val library: StateFlow<StoredLibrary> = mutableLibrary.asStateFlow()

    val favoritesIds get() = library.value.favorites

    @Synchronized
    fun setFavorite(songId: String, favorite: Boolean) = update { library ->
        library.copy(
            favorites = when (favorite) {
                true -> listOf(songId) + library.favorites.filterNot { it == songId }
                false -> library.favorites.filterNot { it == songId }
            }
        )
    }

    @Synchronized
    fun createPlaylist(name: String): Long {
        var createdId = 0L
        update { library ->
            createdId = library.nextPlaylistId
            library.copy(
                playlists = library.playlists + StoredPlaylist(
                    createdId, name, emptyList(), System.currentTimeMillis()
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
                    createdId, name, songIds.distinct(), System.currentTimeMillis()
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
        library.copy(playlists = library.playlists.filterNot { it.id == playlistId })
    }

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

    private fun load() = runCatching {
        json.decodeFromString<StoredLibrary>(file.readText())
    }.getOrDefault(StoredLibrary())

    private fun save(library: StoredLibrary) {
        runCatching {
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(StoredLibrary.serializer(), library))
            if (!temporary.renameTo(file)) {
                file.writeText(json.encodeToString(StoredLibrary.serializer(), library))
                temporary.delete()
            }
        }
    }
}
