/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {
    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun release(
        assets: String,
        body: String = "## What's new\\n- **Faster** search\\n- Fixes",
        prerelease: Boolean = false,
    ) = """
        {
          "tag_name": "v2.1",
          "html_url": "https://github.com/owner/repo/releases/tag/v2.1",
          "draft": false,
          "prerelease": $prerelease,
          "body": "$body",
          "assets": [$assets]
        }
    """.trimIndent()

    private fun asset(name: String, digest: String? = "sha256:$sha") = """
        {
          "name": "$name",
          "size": 7577748,
          ${digest?.let { "\"digest\": \"$it\"," } ?: ""}
          "browser_download_url": "https://github.com/owner/repo/releases/download/v2.1/$name"
        }
    """.trimIndent()

    @Test
    fun readsTheVersionFromTheApkName() {
        val parsed = ReleaseParser.parse(release(asset("Twelve-2.1-3.apk")))!!

        assertEquals("2.1", parsed.versionName)
        assertEquals(3L, parsed.versionCode)
        assertEquals(7577748L, parsed.apkSize)
        assertEquals(sha, parsed.sha256)
        assertEquals(
            "https://github.com/owner/repo/releases/download/v2.1/Twelve-2.1-3.apk",
            parsed.apkUrl,
        )
    }

    @Test
    fun picksTheApkAmongOtherFiles() {
        val parsed = ReleaseParser.parse(
            release(asset("notes.txt") + "," + asset("Twelve-2.1-3.apk"))
        )

        assertEquals(3L, parsed?.versionCode)
    }

    @Test
    fun takesTheChecksumFromTheNotesWithoutADigest() {
        val parsed = ReleaseParser.parse(
            release(
                asset("Twelve-2.1-3.apk", digest = null),
                body = "- Fixes\\n\\nSHA-256: ${sha.uppercase()}",
            )
        )!!

        assertEquals(sha, parsed.sha256)
        assertEquals("• Fixes", parsed.changelog)
    }

    @Test
    fun refusesWhatCannotBeChecked() {
        assertNull(ReleaseParser.parse(release(asset("Twelve-2.1-3.apk", digest = null))))
        assertNull(ReleaseParser.parse(release(asset("app-release.apk"))))
        assertNull(ReleaseParser.parse(release(asset("Twelve-2.1-3.apk"), prerelease = true)))
        assertNull(ReleaseParser.parse("not json"))
    }

    @Test
    fun turnsMarkdownIntoPlainText() {
        assertEquals(
            "• Faster search\n• Fixes in lyrics, see the README",
            ReleaseParser.cleanChangelog(
                "## What's new\r\n* **Faster** search\r\n- Fixes in `lyrics`, see " +
                        "[the README](https://example.com)\r\n\r\n\r\n"
            ),
        )
    }
}
