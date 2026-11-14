/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlParserTest {
    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <head><metadata>
            <ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/>
          </metadata></head>
          <body><div>
            <p begin="1.5" end="4.0" ttm:agent="v1"><span begin="1.5" end="2.0">Hello</span> <span begin="2.0" end="2.5">dear</span> <span begin="2.5" end="3.0">good</span><span begin="3.0" end="4.0">bye</span></p>
            <p begin="5.0" end="7.0" ttm:agent="v2"><span begin="5.0" end="6.0">Answer</span><span ttm:role="x-bg"><span begin="6.0" end="7.0">(oh)</span></span></p>
            <p begin="1:10.5" end="1:12">Whole line only</p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun wordsKeepTheirTiming() {
        val lines = TtmlParser.parse(ttml)!!.lines

        val first = lines.first()
        assertEquals("Hello dear goodbye", first.text)
        assertEquals(4, first.words.size)
        assertEquals(1500L..4000L, first.durationMs)
        assertEquals(2500L, first.words[2].startMs)
        assertFalse(first.isOpposite)
    }

    @Test
    fun otherSingerIsOppositeAndBackgroundIsSeparate() {
        val lines = TtmlParser.parse(ttml)!!.lines

        val answer = lines.first { it.text == "Answer" }
        assertTrue(answer.isOpposite)

        val background = lines.first { it.isBackground }
        assertEquals("oh", background.text)
        assertEquals(6000L..7000L, background.durationMs)
    }

    @Test
    fun lineWithoutSpansIsSynchronizedAsAWhole() {
        val line = TtmlParser.parse(ttml)!!.lines.last()

        assertEquals("Whole line only", line.text)
        assertTrue(line.words.isEmpty())
        assertEquals(70500L..72000L, line.durationMs)
    }

    @Test
    fun invalidInputIsNotLyrics() {
        assertNull(TtmlParser.parse("not xml"))
        assertNull(TtmlParser.parse("<tt><body/></tt>"))
        assertNotNull(TtmlParser.parseTime("12.5s"))
        assertEquals(62500L, TtmlParser.parseTime("1:02.5"))
    }
}
