/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

/**
 * Plain representations of the entities returned by the online catalog. They are intentionally free
 * of Android types so that the parser can be unit tested on the JVM.
 */
sealed interface SaavnEntity {
    val id: String
    val image: String?
}

/**
 * A reference to an artist, [id] can be null when the catalog only gave us a name.
 */
data class SaavnArtistRef(
    val id: String?,
    val name: String,
    val image: String? = null,
)

data class SaavnSong(
    override val id: String,
    override val image: String?,
    val title: String,
    val artists: List<SaavnArtistRef>,
    val albumId: String?,
    val albumName: String?,
    val durationSeconds: Long?,
    val year: Int?,
    val language: String?,
    val encryptedMediaUrl: String?,
    val has320kbps: Boolean,
    val isExplicit: Boolean,
) : SaavnEntity

data class SaavnAlbum(
    override val id: String,
    override val image: String?,
    val title: String,
    val artists: List<SaavnArtistRef>,
    val year: Int?,
    val songCount: Int?,
) : SaavnEntity

data class SaavnArtist(
    override val id: String,
    override val image: String?,
    val name: String,
) : SaavnEntity

data class SaavnPlaylist(
    override val id: String,
    override val image: String?,
    val title: String,
    val description: String?,
    val songCount: Int?,
) : SaavnEntity

data class SaavnAlbumPage(
    val album: SaavnAlbum,
    val songs: List<SaavnSong>,
)

data class SaavnPlaylistPage(
    val playlist: SaavnPlaylist,
    val songs: List<SaavnSong>,
)

data class SaavnArtistPage(
    val artist: SaavnArtist,
    val topSongs: List<SaavnSong>,
    val albums: List<SaavnAlbum>,
    val singles: List<SaavnAlbum>,
    val playlists: List<SaavnPlaylist>,
)

/**
 * A titled row of entities, used to build the home page.
 */
data class SaavnShelf(
    val key: String,
    val items: List<SaavnEntity>,
)
