/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTransferTest {
    private val playlist = SharedPlaylist(
        name = "Road trip",
        songs = listOf(
            SharedPlaylist.Song("abc123", "Tum Kya Mile", "Arijit Singh"),
            SharedPlaylist.Song("def456", "Kesariya", "Arijit Singh"),
        ),
    )

    @Test
    fun codeRoundTrips() {
        val parsed = SharedPlaylist.parse(playlist.toCode())

        assertEquals(playlist, parsed)
    }

    @Test
    fun fileRoundTrips() {
        assertEquals(playlist, SharedPlaylist.parse(playlist.toJson()))
    }

    @Test
    fun codeInsideAMessageIsFound() {
        val parsed = SharedPlaylist.parse("Hey, listen to this!\n\n" + playlist.toShareText() + "\nbye")

        assertEquals(playlist, parsed)
    }

    @Test
    fun garbageIsNotAPlaylist() {
        assertNull(SharedPlaylist.parse("hello"))
        assertNull(SharedPlaylist.parse("twelve-playlist:@@@"))
        assertNull(SharedPlaylist.parse("{\"format\":\"other\",\"name\":\"x\",\"songs\":[]}"))
    }

    @Test
    fun duplicatesAndBlankIdsAreDropped() {
        val messy = playlist.copy(
            songs = playlist.songs + SharedPlaylist.Song("abc123") + SharedPlaylist.Song(" "),
        )

        assertEquals(2, SharedPlaylist.parse(messy.toJson())!!.songs.size)
    }

    @Test
    fun aBigPlaylistStaysShortEnoughToSend() {
        val big = SharedPlaylist(
            "Big",
            (1..100).map { SharedPlaylist.Song("id$it" + "x".repeat(6), "Title $it", "Artist") },
        )

        assertTrue(big.toCode().length < 5000)
    }
}
