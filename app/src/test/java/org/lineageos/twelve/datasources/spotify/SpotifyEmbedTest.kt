/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyEmbedTest {
    private val page = """
        <html><body><script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"state":{"data":{"entity":{
          "name":"Test Share PlayList",
          "trackList":[
            {"title":"Silence","subtitle":"Marshmello, Khalid","duration":180822},
            {"title":"Home (with Machine Gun Kelly)","subtitle":"mgk, Bebe Rexha","duration":202804},
            {"title":"  ","subtitle":"nobody"}
          ]}}}}}}
        </script></body></html>
    """.trimIndent()

    @Test
    fun tracksAreRead() {
        val playlist = SpotifyEmbed.parse(page)!!

        assertEquals("Test Share PlayList", playlist.name)
        assertEquals(2, playlist.tracks.size)
        assertEquals("Silence", playlist.tracks[0].title)
        assertEquals("Marshmello, Khalid", playlist.tracks[0].artist)
        assertEquals(180L, playlist.tracks[0].durationSeconds)
    }

    @Test
    fun otherPagesAreNotPlaylists() {
        assertNull(SpotifyEmbed.parse("<html>nothing</html>"))
        assertNull(SpotifyEmbed.parse("<script id=\"__NEXT_DATA__\">{}</script>"))
    }
}
