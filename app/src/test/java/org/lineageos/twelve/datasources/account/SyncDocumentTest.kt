/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.account

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.twelve.datasources.saavn.StoredLibrary
import org.lineageos.twelve.datasources.saavn.StoredPlaylist

class SyncDocumentTest {
    @Test
    fun favoritesOfBothDevicesAreKept() {
        val merged = SyncDocument.merge(
            SyncDocument(favorites = mapOf("a" to 10L)),
            SyncDocument(favorites = mapOf("b" to 20L)),
        )

        assertEquals(setOf("a", "b"), merged.favorites.keys)
    }

    @Test
    fun aLaterRemovalWinsOverAnOlderFavorite() {
        val merged = SyncDocument.merge(
            SyncDocument(favorites = mapOf("a" to 10L)),
            SyncDocument(removedFavorites = mapOf("a" to 20L)),
        )

        assertFalse("a" in merged.favorites)
        assertEquals(20L, merged.removedFavorites["a"])
    }

    @Test
    fun aFavoriteAddedAgainAfterItsRemovalComesBack() {
        val merged = SyncDocument.merge(
            SyncDocument(favorites = mapOf("a" to 30L)),
            SyncDocument(removedFavorites = mapOf("a" to 20L)),
        )

        assertTrue("a" in merged.favorites)
        assertFalse("a" in merged.removedFavorites)
    }

    @Test
    fun theNewestVersionOfAPlaylistWins() {
        val merged = SyncDocument.merge(
            SyncDocument(playlists = mapOf("p" to SyncPlaylist("Old", listOf("a"), 10L))),
            SyncDocument(playlists = mapOf("p" to SyncPlaylist("New", listOf("a", "b"), 20L))),
        )

        assertEquals(SyncPlaylist("New", listOf("a", "b"), 20L), merged.playlists["p"])
    }

    @Test
    fun aDeletedPlaylistDoesNotComeBack() {
        val merged = SyncDocument.merge(
            SyncDocument(playlists = mapOf("p" to SyncPlaylist("Mix", emptyList(), 10L))),
            SyncDocument(deletedPlaylists = mapOf("p" to 20L)),
        )

        assertTrue(merged.playlists.isEmpty())
        assertEquals(20L, merged.deletedPlaylists["p"])
    }

    @Test
    fun aPlaylistChangedAfterItsDeletionStays() {
        val merged = SyncDocument.merge(
            SyncDocument(playlists = mapOf("p" to SyncPlaylist("Mix", emptyList(), 30L))),
            SyncDocument(deletedPlaylists = mapOf("p" to 20L)),
        )

        assertTrue("p" in merged.playlists)
        assertFalse("p" in merged.deletedPlaylists)
    }

    @Test
    fun theNewestSettingsWinAndEmptySettingsNever() {
        val older = SyncDocument(
            settings = mapOf("automix" to JsonPrimitive(false)), settingsModifiedAt = 10L
        )
        val newer = SyncDocument(
            settings = mapOf("automix" to JsonPrimitive(true)), settingsModifiedAt = 20L
        )

        assertEquals(newer.settings, SyncDocument.merge(older, newer).settings)
        assertEquals(newer.settings, SyncDocument.merge(newer, older).settings)
        assertEquals(older.settings, SyncDocument.merge(older, SyncDocument()).settings)
        assertEquals(older.settings, SyncDocument.merge(SyncDocument(), older).settings)
    }

    @Test
    fun oldRemovalsAreForgotten() {
        val now = SyncDocument.TOMBSTONE_MAX_AGE_MS * 2
        val pruned = SyncDocument(
            removedFavorites = mapOf("old" to 1L, "new" to now - 1000L),
            deletedPlaylists = mapOf("old" to 1L),
        ).pruned(now)

        assertEquals(setOf("new"), pruned.removedFavorites.keys)
        assertTrue(pruned.deletedPlaylists.isEmpty())
    }

    @Test
    fun applyingKeepsLocalNumbersAndAddsNewPlaylistsAtTheEnd() {
        val local = StoredLibrary(
            favorites = listOf("a"),
            playlists = listOf(StoredPlaylist(5L, "Mine", listOf("a"), 10L, "p1")),
            nextPlaylistId = 6L,
            favoriteTimes = mapOf("a" to 10L),
        )
        val document = SyncDocument(
            favorites = mapOf("a" to 10L, "b" to 20L),
            playlists = mapOf(
                "p1" to SyncPlaylist("Mine, renamed", listOf("a", "b"), 30L),
                "p2" to SyncPlaylist("From the other phone", listOf("c"), 25L),
            ),
        )

        val applied = document.applyTo(local)

        assertEquals(listOf("b", "a"), applied.favorites)
        assertEquals(
            listOf(
                StoredPlaylist(5L, "Mine, renamed", listOf("a", "b"), 30L, "p1"),
                StoredPlaylist(6L, "From the other phone", listOf("c"), 25L, "p2"),
            ),
            applied.playlists,
        )
        assertEquals(7L, applied.nextPlaylistId)
    }

    @Test
    fun aLibraryAndItsDocumentRoundTrip() {
        val library = StoredLibrary(
            favorites = listOf("b", "a"),
            playlists = listOf(StoredPlaylist(1L, "Mix", listOf("a"), 10L, "p1")),
            nextPlaylistId = 2L,
            favoriteTimes = mapOf("a" to 10L, "b" to 20L),
            removedFavorites = mapOf("c" to 5L),
            deletedPlaylists = mapOf("p0" to 3L),
        )

        assertEquals(library, SyncDocument.of(library, emptyMap(), 0L).applyTo(library))
    }
}
