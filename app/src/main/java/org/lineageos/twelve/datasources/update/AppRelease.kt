/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A version of the app published as a GitHub release.
 *
 * @param versionCode Read from the name of the APK, Twelve-<versionName>-<versionCode>.apk
 * @param sha256 The checksum of the APK, lower case hex: an APK that doesn't match is not installed
 */
data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val pageUrl: String,
)

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    // Twelve-2.1-3.apk
    private val apkName = Regex("""^Twelve-([0-9A-Za-z._+-]+?)-(\d+)\.apk$""")

    // "SHA-256: 0123abcd…" anywhere in the notes, for releases made before GitHub gave digests
    private val shaLine = Regex("""(?i)sha-?256\W{0,3}([0-9a-f]{64})""")

    /**
     * The release in the answer of the GitHub API, null when it has no usable APK: no APK named
     * like the app builds them, or no checksum to check it against.
     */
    fun parse(body: String): AppRelease? {
        val release = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        if (release.boolean("draft") == true || release.boolean("prerelease") == true) {
            return null
        }

        val notes = release.string("body").orEmpty()

        val (asset, match) = (release["assets"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstNotNullOfOrNull { asset ->
                asset.string("name")?.let { apkName.matchEntire(it) }?.let { asset to it }
            } ?: return null

        val sha256 = asset.string("digest")
            ?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
            ?: shaLine.find(notes)?.groupValues?.get(1)
            ?: return null

        return AppRelease(
            versionName = match.groupValues[1],
            versionCode = match.groupValues[2].toLongOrNull() ?: return null,
            changelog = cleanChangelog(notes),
            apkUrl = asset.string("browser_download_url") ?: return null,
            apkSize = asset.string("size")?.toLongOrNull() ?: 0L,
            sha256 = sha256.lowercase(),
            pageUrl = release.string("html_url").orEmpty(),
        )
    }

    // A first line like "What's new" or "Changelog": the popup has its own title
    private val leadingTitle = Regex(
        """^(what.?s new|changelog|changes|release notes)\W*$""", RegexOption.IGNORE_CASE
    )

    /**
     * The release notes as plain text: Markdown marks become bullets and plain lines, the
     * checksum line and a leading "What's new" are left out.
     */
    fun cleanChangelog(notes: String): String = notes
        .replace("\r\n", "\n")
        .lines()
        .filterNot { shaLine.containsMatchIn(it) }
        .map { line ->
            line.trimEnd()
                .replace(Regex("""^#{1,6}\s+"""), "")
                .replace(Regex("""^\s*[-*+]\s+"""), "• ")
                .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                .replace(Regex("""__(.+?)__"""), "$1")
                .replace(Regex("""`([^`]+)`"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        }
        .dropWhile { it.isBlank() }
        .let { lines ->
            when (lines.firstOrNull()?.trim()?.let { leadingTitle.matches(it) } == true) {
                true -> lines.drop(1)
                false -> lines
            }
        }
        .joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)
        ?.takeIf { it.content != "null" }
        ?.content

    private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)
        ?.content
        ?.toBooleanStrictOrNull()
}
