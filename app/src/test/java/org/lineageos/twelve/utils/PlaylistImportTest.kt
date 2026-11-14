/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportTest {
    @Test
    fun spotifyLinksAreRecognized() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            PlaylistImport.spotifyPlaylistId(
                "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"
            )
        )
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            PlaylistImport.spotifyPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")
        )
        assertNull(PlaylistImport.spotifyPlaylistId("https://example.com/playlist/xyz"))
    }

    @Test
    fun exportifyCsvIsRead() {
        val csv = "Track URI,Track Name,Artist Name(s),Track Duration (ms)\n" +
                "spotify:track:1,\"Tum Kya Mile (From \"\"Rocky\"\")\",\"Pritam, Arijit Singh\",257000\n" +
                "spotify:track:2,Kesariya,Arijit Singh,268000\n"

        val playlist = PlaylistImport.parseText("Mine", csv)!!

        assertEquals(2, playlist.tracks.size)
        assertEquals("Tum Kya Mile (From \"Rocky\")", playlist.tracks[0].title)
        assertEquals("Pritam, Arijit Singh", playlist.tracks[0].artist)
        assertEquals(257L, playlist.tracks[0].durationSeconds)
    }

    @Test
    fun plainListsAreRead() {
        val playlist = PlaylistImport.parseText(
            "List", "1. Kesariya - Arijit Singh\n- Apna Bana Le – Arijit Singh\n\nChaleya"
        )!!

        assertEquals(3, playlist.tracks.size)
        assertEquals("Kesariya", playlist.tracks[0].title)
        assertEquals("Arijit Singh", playlist.tracks[1].artist)
        assertNull(playlist.tracks[2].artist)
    }

    @Test
    fun theRightSongWinsTheMatch() {
        val track = ImportTrack("Tum Kya Mile", "Arijit Singh", 257)

        val exact = PlaylistImport.score(
            track, "Tum Kya Mile (From \"Rocky Aur Rani\")", listOf("Pritam", "Arijit Singh"), 258
        )
        val cover = PlaylistImport.score(track, "Tum Kya Mile", listOf("Some Cover Band"), 190)
        val other = PlaylistImport.score(track, "Kesariya", listOf("Arijit Singh"), 257)

        assertTrue(exact >= PlaylistImport.MINIMUM_SCORE)
        assertTrue(exact > cover)
        assertEquals(0.0, other, 0.0)
    }

    @Test
    fun aTitleThatOnlyStartsTheSameIsNotTheSong() {
        val track = ImportTrack("Home (with Machine Gun Kelly)", "mgk", 202)

        val other = PlaylistImport.score(track, "home bittersweet home", listOf("MGK"), 193)
        val same = PlaylistImport.score(track, "Home", listOf("mgk", "X Ambassadors"), 203)

        assertTrue(other < PlaylistImport.MINIMUM_SCORE)
        assertTrue(same >= PlaylistImport.MINIMUM_SCORE)
    }

    @Test
    fun accentsAndPackagingDoNotMatter() {
        assertEquals(
            PlaylistImport.normalize("Café del Mar (feat. X) - Remastered"),
            PlaylistImport.normalize("cafe del mar")
        )
    }
}
