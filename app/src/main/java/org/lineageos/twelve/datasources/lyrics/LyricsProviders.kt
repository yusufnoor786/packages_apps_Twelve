/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.lyrics

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.lineageos.twelve.ext.executeAsync
import org.lineageos.twelve.models.Lyrics
import kotlin.math.abs

/**
 * What is known about the song lyrics are wanted for.
 */
data class LyricsQuery(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
) {
    val durationSeconds get() = durationMs?.let { (it / 1000).toString() }

    /**
     * The song as the databases know it. The catalog this app plays from files songs as
     * "Tum Kya Mile (From \"Rocky Aur Rani\")" by "A, B, C", lyrics databases know "Tum Kya Mile"
     * by its first artist.
     */
    fun forSearch() = copy(
        title = title.cleanTitle(),
        artist = artist?.split(',', '&', ';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private val TITLE_PACKAGING = listOf(
    Regex("""\s*[(\[]\s*(from|feat|ft|featuring|with|original|official)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
    Regex("""\s+-\s+(from|feat|ft|featuring|original|official)\b.*$""", RegexOption.IGNORE_CASE),
)

private fun String.cleanTitle(): String {
    var name = this
    TITLE_PACKAGING.forEach { name = it.replace(name, " ") }
    return name.replace(Regex("""\s+"""), " ").trim().ifBlank { trim() }
}

/**
 * A place lyrics can be fetched from.
 */
interface LyricsProvider {
    val name: String

    /**
     * Whether the provider is expected to know when each word is sung.
     */
    val wordSynced: Boolean

    suspend fun fetch(query: LyricsQuery): Lyrics?
}

/**
 * Small HTTP helper shared by the providers, failures are just missing lyrics.
 */
internal class LyricsHttp(private val okHttpClient: OkHttpClient) {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun text(baseUrl: String, vararg params: Pair<String, String?>): String? = try {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) ->
                value?.takeIf { it.isNotBlank() }?.let { addQueryParameter(key, it) }
            }
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).executeAsync().use { response ->
            when (response.isSuccessful) {
                true -> response.body?.string()
                false -> null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    suspend fun json(baseUrl: String, vararg params: Pair<String, String?>): JsonElement? =
        text(baseUrl, *params)?.let {
            try {
                json.parseToJsonElement(it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    companion object {
        const val USER_AGENT =
            "Twelve (https://github.com/LineageOS/android_packages_apps_Twelve)"
    }
}

internal fun JsonElement?.obj() = this as? JsonObject
internal fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * lrc.red: community TTML lyrics with word timings, looked up by title and artist.
 */
internal class LrcRedProvider(private val http: LyricsHttp) : LyricsProvider {
    override val name = "lrc.red"
    override val wordSynced = true

    override suspend fun fetch(query: LyricsQuery): Lyrics? {
        val artist = query.artist ?: return null

        val hits = http.json(
            "https://lrc.red/match.json",
            "track" to query.title,
            "artist" to artist,
            "album" to query.album,
            "duration" to query.durationSeconds,
        ).obj()?.get("hits") as? JsonArray ?: return null

        val hit = hits.mapNotNull { it as? JsonObject }.firstOrNull { candidate ->
            val duration = (candidate["duration"] as? JsonPrimitive)?.doubleOrNull
            query.durationMs == null || duration == null ||
                    abs(duration * 1000 - query.durationMs) <= DURATION_TOLERANCE_MS
        } ?: return null

        val isrc = hit["isrc"].str() ?: return null
        val ttml = http.json("https://lrc.red/s/$isrc.song.json").obj()?.get("source").str()
            ?: return null

        return TtmlParser.parse(ttml)
    }

    companion object {
        private const val DURATION_TOLERANCE_MS = 6000
    }
}

/**
 * BetterLyrics: TTML lyrics, usually with word timings.
 */
internal class BetterLyricsProvider(private val http: LyricsHttp) : LyricsProvider {
    override val name = "BetterLyrics"
    override val wordSynced = true

    override suspend fun fetch(query: LyricsQuery): Lyrics? {
        val artist = query.artist ?: return null

        val ttml = http.json(
            "https://lyrics-api.boidu.dev/getLyrics",
            "s" to query.title,
            "a" to artist,
            "al" to query.album,
            "d" to query.durationSeconds,
        ).obj()?.get("ttml").str() ?: return null

        return TtmlParser.parse(ttml)
    }
}

/**
 * LyricsPlus: aggregates several catalogs (Apple Music, Musixmatch, QQ, Deezer...) and serves
 * their syllable timings in its own JSON format.
 */
internal class LyricsPlusProvider(private val http: LyricsHttp) : LyricsProvider {
    override val name = "LyricsPlus"
    override val wordSynced = true

    override suspend fun fetch(query: LyricsQuery): Lyrics? {
        val root = http.json(
            "https://lyricsplus.binimum.org/v2/lyrics/get",
            "title" to query.title,
            "artist" to query.artist,
            "album" to query.album,
            "duration" to query.durationSeconds,
            "source" to SOURCES,
        ).obj() ?: return null

        val entries = (root["lyrics"] ?: root["data"].obj()?.get("lyrics")) as? JsonArray
            ?: return null
        val lineOnly = root["type"].str().equals("Line", ignoreCase = true)

        val lines = mutableListOf<Lyrics.Line>()
        entries.forEach { element ->
            val entry = element.obj() ?: return@forEach

            val start = entry["time"].number()?.toLong() ?: return@forEach
            val duration = entry["duration"].number()?.toLong() ?: 0L
            val end = entry["endTime"].number()?.toLong() ?: (start + duration)
            val text = entry["text"].str().orEmpty()
            val singer = entry["element"].obj()?.get("singer").str()
            val isOpposite = singer != null && singer.lowercase().let {
                it == "v2" || it.endsWith("2")
            }

            val syllables = (entry["syllabus"] ?: entry["words"]) as? JsonArray

            if (lineOnly || syllables.isNullOrEmpty()) {
                if (text.isNotBlank()) {
                    lines += Lyrics.Line(text.trim(), start..maxOf(start, end), isOpposite = isOpposite)
                }
                return@forEach
            }

            val main = mutableListOf<Lyrics.Word>()
            val background = mutableListOf<Lyrics.Word>()
            syllables.forEach { syllable ->
                val s = syllable.obj() ?: return@forEach
                val sylStart = s["time"].number()?.toLong() ?: start
                val sylEnd = sylStart + (s["duration"].number()?.toLong() ?: 0L)
                val sylText = (s["text"] as? JsonPrimitive)?.contentOrNull ?: return@forEach

                val word = Lyrics.Word(sylText, sylStart, maxOf(sylStart, sylEnd))
                when ((s["isBackground"] as? JsonPrimitive)?.contentOrNull == "true") {
                    true -> background += word
                    false -> main += word
                }
            }

            fun MutableList<Lyrics.Word>.toLine(isBackground: Boolean): Lyrics.Line? {
                if (isEmpty()) {
                    return null
                }

                // Parentheses around background vocals are drawn by the player
                val cleaned = toMutableList()
                if (isBackground) {
                    cleaned[0] = cleaned[0].copy(text = cleaned[0].text.removePrefix("("))
                    cleaned[cleaned.lastIndex] = cleaned.last()
                        .let { it.copy(text = it.text.removeSuffix(")")) }
                }
                cleaned[cleaned.lastIndex] = cleaned.last().let { it.copy(text = it.text.trimEnd()) }

                return Lyrics.Line(
                    text = cleaned.joinToString("") { it.text },
                    durationMs = cleaned.first().startMs..cleaned.maxOf { it.endMs },
                    words = cleaned,
                    isBackground = isBackground,
                    isOpposite = isOpposite,
                )
            }

            main.toLine(false)?.let { lines += it }
            background.toLine(true)?.let { lines += it }
        }

        return lines.takeIf { it.isNotEmpty() }?.let { found ->
            Lyrics(found.withIndex().sortedWith(
                compareBy({ it.value.durationMs?.first ?: Long.MAX_VALUE }, { it.index })
            ).map { it.value })
        }
    }

    private fun JsonElement?.number() = (this as? JsonPrimitive)?.doubleOrNull

    companion object {
        private const val SOURCES =
            "apple,lyricsplus,musixmatch,spotify,qq,deezer,musixmatch-word"
    }
}

/**
 * Unison: lyrics submitted by the BetterLyrics community, TTML or LRC.
 */
internal class UnisonProvider(private val http: LyricsHttp) : LyricsProvider {
    override val name = "Unison"
    override val wordSynced = false

    override suspend fun fetch(query: LyricsQuery): Lyrics? {
        val artist = query.artist ?: return null

        val data = http.json(
            "https://unison.boidu.dev/lyrics",
            "song" to query.title,
            "artist" to artist,
            "album" to query.album,
            "duration" to query.durationSeconds,
        ).obj()?.takeIf { it["success"].str() == "true" }?.get("data").obj() ?: return null

        val lyrics = data["lyrics"].str() ?: return null

        return when {
            data["format"].str() == "ttml" -> TtmlParser.parse(lyrics)
            data["syncType"].str() == "plain" -> LrcParser.parsePlain(lyrics)
            else -> LrcParser.parseSynced(lyrics)
        }
    }
}

/**
 * LRCLIB: free community database of synchronized and plain lyrics.
 */
internal class LrclibProvider(private val http: LyricsHttp) : LyricsProvider {
    override val name = "LRCLIB"
    override val wordSynced = false

    override suspend fun fetch(query: LyricsQuery): Lyrics? {
        val exact = http.json(
            "https://lrclib.net/api/get",
            "track_name" to query.title,
            "artist_name" to query.artist,
            "album_name" to query.album,
            "duration" to query.durationSeconds,
        ).obj()

        val match = exact ?: (http.json(
            "https://lrclib.net/api/search",
            "track_name" to query.title,
            "artist_name" to query.artist,
        ) as? JsonArray)?.firstNotNullOfOrNull { it as? JsonObject } ?: return null

        return match["syncedLyrics"].str()?.let { LrcParser.parseSynced(it) }
            ?: match["plainLyrics"].str()?.let { LrcParser.parsePlain(it) }
    }
}

/**
 * KuGou: large catalog, mostly Asian music, serves LRC.
 */
internal class KuGouProvider(private val http: LyricsHttp) : LyricsProvider {
    override val name = "KuGou"
    override val wordSynced = false

    override suspend fun fetch(query: LyricsQuery): Lyrics? {
        val keyword = listOfNotNull(query.title, query.artist).joinToString(" ")

        val candidate = http.json(
            "https://lyrics.kugou.com/search",
            "ver" to "1",
            "man" to "yes",
            "client" to "pc",
            "keyword" to keyword,
            "duration" to query.durationMs?.toString(),
        ).obj()?.get("candidates")?.jsonArray
            ?.firstNotNullOfOrNull { it as? JsonObject } ?: return null

        val id = candidate["id"].str() ?: return null
        val accessKey = candidate["accesskey"].str() ?: return null

        val content = http.json(
            "https://lyrics.kugou.com/download",
            "ver" to "1",
            "client" to "pc",
            "id" to id,
            "accesskey" to accessKey,
            "fmt" to "lrc",
            "charset" to "utf8",
        ).obj()?.get("content").str() ?: return null

        val lrc = runCatching {
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null

        return LrcParser.parseSynced(lrc)
    }
}

/**
 * Asks all the providers at the same time and hands out what they find as it arrives, better
 * lyrics replace worse ones: word synchronized, then line synchronized, then plain. The first
 * thing found is on screen as soon as the fastest provider answers.
 */
internal class LyricsProviders(okHttpClient: OkHttpClient) {
    private val http = LyricsHttp(okHttpClient)

    private val providers = listOf<LyricsProvider>(
        LrcRedProvider(http),
        BetterLyricsProvider(http),
        LyricsPlusProvider(http),
        LrclibProvider(http),
        UnisonProvider(http),
        KuGouProvider(http),
    )

    fun fetch(rawQuery: LyricsQuery): Flow<Lyrics> = channelFlow {
        val query = rawQuery.forSearch()
        val lock = Mutex()
        var bestRank = 0

        providers.forEach { provider ->
            launch {
                val lyrics = try {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { provider.fetch(query) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: return@launch

                val rank = rank(lyrics)
                if (lyrics.lines.isEmpty() || rank == 0) {
                    return@launch
                }

                lock.withLock {
                    if (rank > bestRank) {
                        bestRank = rank
                        send(lyrics)
                    }
                }
            }
        }
    }

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 7_000L

        /**
         * How good lyrics are: 3 for word timings, 2 for line timings, 1 for plain text.
         */
        fun rank(lyrics: Lyrics) = when {
            lyrics.lines.any { it.words.isNotEmpty() } -> 3
            lyrics.lines.any { it.durationMs != null } -> 2
            lyrics.lines.isNotEmpty() -> 1
            else -> 0
        }
    }
}
