/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.saavn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaavnParserTest {
    private fun fixture(name: String): JsonElement {
        val text = javaClass.getResource("/saavn/$name.json")!!.readText()
        return Json.parseToJsonElement(text)
    }

    @Test
    fun decodesHtmlEntities() {
        assertEquals("Tu Mil Jaaye (From \"KING\")", SaavnParser.decodeHtml("Tu Mil Jaaye (From &quot;KING&quot;)"))
        assertEquals("Rock & Roll", SaavnParser.decodeHtml("Rock &amp; Roll"))
        assertEquals("It's", SaavnParser.decodeHtml("It&#039;s"))
        assertEquals("plain", SaavnParser.decodeHtml("plain"))
    }

    @Test
    fun upscalesImages() {
        assertEquals(
            "https://c.saavncdn.com/248/Evolve-150x150.jpg".replace("150x150", "500x500"),
            SaavnParser.image("http://c.saavncdn.com/248/Evolve-150x150.jpg"),
        )
        assertNull(SaavnParser.image(""))
        assertNull(SaavnParser.image(null))
    }

    @Test
    fun parsesSongSearchResults() {
        val songs = SaavnParser.results(fixture("search_songs")).mapNotNull { SaavnParser.song(it) }

        assertEquals(3, songs.size)
        val song = songs.first()
        assertEquals("BeXBcbVK", song.id)
        assertEquals("Believer", song.title)
        assertEquals("Imagine Dragons", song.artists.first().name)
        assertEquals("599452", song.artists.first().id)
        assertEquals("13435951", song.albumId)
        assertEquals("Evolve", song.albumName)
        assertEquals(204L, song.durationSeconds)
        assertEquals(2018, song.year)
        assertTrue(song.has320kbps)
        assertNotNull(song.encryptedMediaUrl)
        assertTrue(song.image!!.contains("500x500"))
    }

    @Test
    fun parsesOtherSearchResults() {
        val albums = SaavnParser.results(fixture("search_albums")).mapNotNull { SaavnParser.album(it) }
        assertEquals(3, albums.size)
        assertTrue(albums.all { it.title.isNotEmpty() && it.artists.isNotEmpty() })

        val artists = SaavnParser.results(fixture("search_artists")).mapNotNull { SaavnParser.artist(it) }
        assertEquals("Arijit Singh", artists.first().name)
        assertEquals("459320", artists.first().id)

        val playlists = SaavnParser.results(fixture("search_playlists"))
            .mapNotNull { SaavnParser.playlist(it) }
        assertEquals("Non-Stop Party", playlists.first().title)
        assertEquals(50, playlists.first().songCount)
    }

    @Test
    fun parsesSongDetails() {
        val songs = SaavnParser.results(fixture("song")).mapNotNull { SaavnParser.song(it) }

        assertEquals(1, songs.size)
        assertEquals("BeXBcbVK", songs.first().id)
    }

    @Test
    fun decryptsPlaybackUrl() {
        val song = SaavnParser.results(fixture("search_songs")).mapNotNull { SaavnParser.song(it) }
            .first()
        val encrypted = song.encryptedMediaUrl

        assertEquals(
            "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_320.mp4",
            SaavnStream.playbackUrl(encrypted, true, SaavnStream.Quality.HIGH),
        )
        assertEquals(
            "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_160.mp4",
            SaavnStream.playbackUrl(encrypted, false, SaavnStream.Quality.HIGH),
        )
        assertEquals(
            "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_160.mp4",
            SaavnStream.playbackUrl(encrypted, true, SaavnStream.Quality.MEDIUM),
        )
        assertEquals(
            "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_96.mp4",
            SaavnStream.playbackUrl(encrypted, true, SaavnStream.Quality.LOW),
        )
        assertNull(SaavnStream.playbackUrl(null, true))
        assertNull(SaavnStream.playbackUrl("not base64!", true))
    }

    @Test
    fun parsesAlbumPage() {
        val page = SaavnParser.albumPage(fixture("album"))!!

        assertEquals("Evolve", page.album.title)
        assertEquals("Imagine Dragons", page.album.artists.first().name)
        assertEquals(2018, page.album.year)
        assertEquals(3, page.songs.size)
        assertTrue(page.songs.all { it.encryptedMediaUrl != null })
    }

    @Test
    fun parsesPlaylistPage() {
        val page = SaavnParser.playlistPage(fixture("playlist"))!!

        assertEquals("Non-Stop Party", page.playlist.title)
        assertEquals(50, page.playlist.songCount)
        assertEquals(3, page.songs.size)
    }

    @Test
    fun parsesArtistPage() {
        val page = SaavnParser.artistPage(fixture("artist"))!!

        assertEquals("Arijit Singh", page.artist.name)
        assertEquals("459320", page.artist.id)
        assertEquals(3, page.topSongs.size)
        assertEquals(3, page.albums.size)
        assertEquals(2, page.singles.size)
        assertTrue(page.playlists.isNotEmpty())
    }

    @Test
    fun parsesHomeShelves() {
        val shelves = SaavnParser.shelves(fixture("modules"))

        assertEquals(SaavnParser.SHELF_KEYS, shelves.map { it.key })
        assertTrue(shelves.all { it.items.isNotEmpty() })
        assertTrue(shelves.first { it.key == "charts" }.items.all { it is SaavnPlaylist })
    }

    @Test
    fun parsesTrending() {
        val songs = SaavnParser.results(fixture("trending")).mapNotNull { SaavnParser.song(it) }

        assertEquals(3, songs.size)
        assertTrue(songs.all { it.artists.isNotEmpty() })
    }

    @Test
    fun skipsMalformedEntries() {
        val broken = Json.parseToJsonElement("""{"results":[{"type":"song"},{"id":"x"},42]}""")

        assertTrue(SaavnParser.results(broken).mapNotNull { SaavnParser.song(it) }.isEmpty())
        assertNull(SaavnParser.albumPage(JsonObject(emptyMap())))
    }

    @Test
    fun parsesSyncedLyrics() {
        val lyrics = LyricsClient.parseSynced("[00:12.50] First line\n[01:02.5]Second\n[xx] ignored")!!

        assertEquals(listOf("First line", "Second"), lyrics.lines.map { it.text })
        assertEquals(12_500L, lyrics.lines[0].durationMs!!.first)
        assertEquals(62_500L, lyrics.lines[1].durationMs!!.first)
        assertNull(LyricsClient.parseSynced("no timestamps"))
        assertNull(LyricsClient.parsePlain("  \n "))
        assertEquals(2, LyricsClient.parsePlain("a\nb")!!.lines.size)
    }

    @Test
    fun mobileDataHasItsOwnCeiling() {
        val high = SaavnStream.Quality.HIGH
        val medium = SaavnStream.Quality.MEDIUM
        val low = SaavnStream.Quality.LOW

        assertEquals(high, SaavnStream.Quality.forNetwork(high, medium, metered = false))
        assertEquals(medium, SaavnStream.Quality.forNetwork(high, medium, metered = true))
        // Mobile data is never better than what Wi-Fi is allowed
        assertEquals(low, SaavnStream.Quality.forNetwork(low, high, metered = true))
    }
}
