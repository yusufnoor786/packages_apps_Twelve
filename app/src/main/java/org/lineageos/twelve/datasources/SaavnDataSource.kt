/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.lineageos.twelve.utils.ImportTrack
import org.lineageos.twelve.utils.PlaylistImport
import org.lineageos.twelve.R
import org.lineageos.twelve.datasources.saavn.LyricsClient
import org.lineageos.twelve.datasources.saavn.SaavnAlbum
import org.lineageos.twelve.datasources.saavn.SaavnArtist
import org.lineageos.twelve.datasources.saavn.SaavnClient
import org.lineageos.twelve.datasources.saavn.SaavnEntity
import org.lineageos.twelve.datasources.saavn.SaavnLibraryStore
import org.lineageos.twelve.datasources.saavn.SaavnParser
import org.lineageos.twelve.datasources.saavn.SaavnPlaylist
import org.lineageos.twelve.datasources.saavn.SaavnSong
import org.lineageos.twelve.datasources.saavn.SaavnStream
import org.lineageos.twelve.datasources.saavn.StoredLibrary
import org.lineageos.twelve.ext.onlineAudioQuality
import org.lineageos.twelve.ext.onlineAudioQualityMobile
import org.lineageos.twelve.ext.onlineLanguages
import org.lineageos.twelve.models.ActivityTab
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Artist
import org.lineageos.twelve.models.ArtistWorks
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.DataSourceInformation
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.Genre
import org.lineageos.twelve.models.GenreContent
import org.lineageos.twelve.models.LocalizedString
import org.lineageos.twelve.models.MediaItem
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.ProviderIdentifier
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.models.SortingRule
import org.lineageos.twelve.models.SortingStrategy
import org.lineageos.twelve.models.Thumbnail
import java.util.Collections
import java.util.Locale

/**
 * Online catalog data source (JioSaavn).
 *
 * Catalog items are addressed using `saavn://catalog/<type>/<id>` URIs. Playlists created by the
 * user and the favorites are stored on the device, see [SaavnLibraryStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaavnDataSource(
    private val client: SaavnClient,
    private val lyricsClient: LyricsClient,
    private val store: SaavnLibraryStore,
    private val sharedPreferences: SharedPreferences,
    private val connectivityManager: ConnectivityManager? = null,
) : MediaDataSource {
    private val songCache: MutableMap<String, SaavnSong> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SaavnSong>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SaavnSong>?) =
                size > SONG_CACHE_SIZE
        }
    )

    /**
     * Bumped to run the requests again, see [refresh].
     */
    private val refreshTick = MutableStateFlow(0)

    /**
     * Forget the cached content and load it again.
     */
    fun refresh() {
        client.clearCache()
        refreshTick.value++
    }

    /**
     * The best quality for the network the phone is on right now: mobile data has a ceiling of its
     * own, so that a song does not eat the plan.
     */
    private val maxQuality: SaavnStream.Quality
        get() = SaavnStream.Quality.forNetwork(
            sharedPreferences.onlineAudioQuality,
            sharedPreferences.onlineAudioQualityMobile,
            connectivityManager?.isActiveNetworkMetered == true,
        )

    private val languages: List<String>
        get() = sharedPreferences.onlineLanguages

    // Status

    override fun status(
        providerIdentifier: ProviderIdentifier,
    ): Flow<MediaRequestStatus<List<DataSourceInformation>>> = flow {
        emit(
            Result.Success(
                listOf(
                    DataSourceInformation(
                        "service",
                        LocalizedString.StringResIdLocalizedString(R.string.online_service),
                        LocalizedString.StringResIdLocalizedString(R.string.online_service_name),
                    ),
                    DataSourceInformation(
                        "languages",
                        LocalizedString.StringResIdLocalizedString(R.string.online_languages),
                        LocalizedString.StringLocalizedString(
                            languages.joinToString(", ") { it.titleCase() }
                        ),
                    ),
                )
            )
        )
    }

    // URIs

    override suspend fun mediaTypeOf(mediaItemUri: Uri) = when {
        !mediaItemUri.isCatalogUri() -> null
        else -> when (mediaItemUri.pathSegments.firstOrNull()) {
            PATH_ALBUM -> MediaType.ALBUM
            PATH_ARTIST -> MediaType.ARTIST
            PATH_AUDIO -> MediaType.AUDIO
            PATH_GENRE -> MediaType.GENRE
            PATH_PLAYLIST -> MediaType.PLAYLIST
            else -> null
        }
    }

    override fun providerOf(mediaItemUri: Uri): Flow<MediaRequestStatus<ProviderIdentifier>> =
        flow {
            emit(
                when (mediaItemUri.isCatalogUri()) {
                    true -> Result.Success(PROVIDER_IDENTIFIER)
                    false -> Result.Failure(Error.NOT_FOUND)
                }
            )
        }

    // Lists

    override fun activity(
        providerIdentifier: ProviderIdentifier,
    ): Flow<MediaRequestStatus<List<ActivityTab>>> = withLibrary { library ->
        coroutineScope {
            val favorites = library.favorites.toSet()
            val languages = languages

            val trendingSongs = async {
                languages.map { language ->
                    async { client.trending("song", language) }
                }.mapNotNull { it.await().data() }
                    .flatMap { SaavnParser.results(it) }
                    .mapNotNull { SaavnParser.song(it) }
                    .distinctBy { it.id }
            }
            val modules = async { client.modules(languages.joinToString(",")) }

            val shelves = modules.await().data()?.let { SaavnParser.shelves(it) }.orEmpty()
            val songs = trendingSongs.await()

            if (shelves.isEmpty() && songs.isEmpty()) {
                return@coroutineScope Result.Failure(Error.IO)
            }

            Result.Success(
                buildList {
                    if (songs.isNotEmpty()) {
                        add(
                            ActivityTab(
                                "trending_songs",
                                LocalizedString.StringResIdLocalizedString(
                                    R.string.online_activity_trending_songs
                                ),
                                songs.map { it.toAudio(favorites) },
                            )
                        )
                    }

                    shelves.forEach { shelf ->
                        add(
                            ActivityTab(
                                shelf.key,
                                LocalizedString.StringResIdLocalizedString(
                                    when (shelf.key) {
                                        "new_trending" -> R.string.online_activity_new_trending
                                        "new_albums" -> R.string.online_activity_new_albums
                                        "charts" -> R.string.online_activity_charts
                                        else -> R.string.online_activity_top_playlists
                                    }
                                ),
                                shelf.items.map { it.toMediaItem(favorites) },
                            )
                        )
                    }
                }
            )
        }
    }

    override fun albums(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Album>>> = withLibrary {
        coroutineScope {
            val trending = languages.map { async { client.trending("album", it) } }
            val modules = async { client.modules(languages.joinToString(",")) }

            val albums = (trending.mapNotNull { it.await().data() }
                .flatMap { SaavnParser.results(it) }
                .mapNotNull { SaavnParser.album(it) } +
                    modules.await().data()?.let { SaavnParser.shelves(it) }.orEmpty()
                        .flatMap { it.items }
                        .filterIsInstance<SaavnAlbum>())
                .distinctBy { it.id }

            when {
                albums.isEmpty() -> Result.Failure(Error.IO)
                else -> Result.Success(
                    albums.map { it.toAlbum() }.sorted(
                        sortingRule,
                        { it.title },
                        { it.artistName },
                        { it.year },
                    )
                )
            }
        }
    }

    override fun artists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Artist>>> = withLibrary {
        coroutineScope {
            val trending = languages.map { async { client.trending("song", it) } }
            val artists = trending.mapNotNull { it.await().data() }
                .flatMap { SaavnParser.results(it) }
                .mapNotNull { SaavnParser.song(it) }
                .onEach { songCache[it.id] = it }
                .flatMap { it.artists }
                .filter { it.id != null }
                .distinctBy { it.id }

            when {
                artists.isEmpty() -> Result.Failure(Error.IO)
                else -> Result.Success(
                    artists.map {
                        SaavnArtist(it.id!!, it.image, it.name).toArtist()
                    }.sorted(sortingRule, { it.name }, { it.name }, { null })
                )
            }
        }
    }

    override fun audios(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Audio>>> = withLibrary { library ->
        coroutineScope {
            val favorites = library.favorites.toSet()
            val trending = languages.map { async { client.trending("song", it) } }

            val songs = trending.mapNotNull { it.await().data() }
                .flatMap { SaavnParser.results(it) }
                .mapNotNull { SaavnParser.song(it) }
                .distinctBy { it.id }

            when {
                songs.isEmpty() -> Result.Failure(Error.IO)
                else -> Result.Success(
                    songs.map { it.toAudio(favorites) }.sorted(
                        sortingRule,
                        { it.title },
                        { it.artistName },
                        { it.year },
                    )
                )
            }
        }
    }

    override fun genres(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Genre>>> = flow {
        emit(
            Result.Success(
                LANGUAGES.map { language ->
                    Genre.Builder(genreUri(language))
                        .setName(language.titleCase())
                        .build()
                }.sorted(sortingRule, { it.name }, { null }, { null })
            )
        )
    }

    override fun playlists(
        providerIdentifier: ProviderIdentifier,
        sortingRule: SortingRule,
    ): Flow<MediaRequestStatus<List<Playlist>>> = withLibrary { library ->
        Result.Success(
            buildList {
                add(favoritesPlaylist)

                addAll(
                    library.playlists.sortedByDescending { it.modifiedAt }.map {
                        Playlist.Builder(userPlaylistUri(it.id))
                            .setName(it.name)
                            .setType(Playlist.Type.PLAYLIST)
                            .build()
                    }
                )
            }
        )
    }

    override fun search(
        providerIdentifier: ProviderIdentifier,
        query: String,
    ): Flow<MediaRequestStatus<List<MediaItem<*>>>> = withLibrary { library ->
        coroutineScope {
            val favorites = library.favorites.toSet()

            val songs = async { client.searchSongs(query, count = 15) }
            val albums = async { client.searchAlbums(query, count = 6) }
            val artists = async { client.searchArtists(query, count = 5) }
            val playlists = async { client.searchPlaylists(query, count = 5) }

            val results = listOf(songs, albums, artists, playlists).map { it.await() }

            if (results.all { it is Result.Failure }) {
                return@coroutineScope Result.Failure(Error.IO)
            }

            val items = buildList<MediaItem<*>> {
                results[0].data()?.let { SaavnParser.results(it) }.orEmpty()
                    .mapNotNull { SaavnParser.song(it) }
                    .forEach { add(it.toAudio(favorites)) }
                results[1].data()?.let { SaavnParser.results(it) }.orEmpty()
                    .mapNotNull { SaavnParser.album(it) }
                    .forEach { add(it.toAlbum()) }
                results[2].data()?.let { SaavnParser.results(it) }.orEmpty()
                    .mapNotNull { SaavnParser.artist(it) }
                    .forEach { add(it.toArtist()) }
                results[3].data()?.let { SaavnParser.results(it) }.orEmpty()
                    .mapNotNull { SaavnParser.playlist(it) }
                    .forEach { add(it.toPlaylist()) }
            }

            Result.Success(items)
        }
    }

    // Single items

    override fun audio(audioUri: Uri): Flow<MediaRequestStatus<Audio>> = withLibrary { library ->
        val id = audioUri.lastPathSegment ?: return@withLibrary Result.Failure(Error.NOT_FOUND)

        when (val song = song(id)) {
            null -> Result.Failure(Error.NOT_FOUND)
            else -> Result.Success(song.toAudio(library.favorites.toSet()))
        }
    }

    override fun album(
        albumUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Album, List<Audio>>>> = withLibrary { library ->
        val id = albumUri.lastPathSegment ?: return@withLibrary Result.Failure(Error.NOT_FOUND)
        val favorites = library.favorites.toSet()

        client.album(id).flatMapData { element ->
            val page = SaavnParser.albumPage(element) ?: return@flatMapData Result.Failure(
                Error.INVALID_RESPONSE
            )

            val album = page.album.toAlbum()
            val songs = page.songs.onEach { songCache[it.id] = it }

            Result.Success(
                album to songs.mapIndexed { index, song ->
                    song.toAudio(favorites, trackNumber = index + 1, fallbackThumbnail = album)
                }
            )
        }
    }

    override fun artist(
        artistUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Artist, ArtistWorks>>> = withLibrary { library ->
        val id = artistUri.lastPathSegment ?: return@withLibrary Result.Failure(Error.NOT_FOUND)
        val favorites = library.favorites.toSet()

        client.artist(id).flatMapData { element ->
            val page = SaavnParser.artistPage(element) ?: return@flatMapData Result.Failure(
                Error.INVALID_RESPONSE
            )

            page.topSongs.forEach { songCache[it.id] = it }

            Result.Success(
                page.artist.toArtist() to ArtistWorks(
                    albums = (page.albums + page.singles).distinctBy { it.id }.map { it.toAlbum() },
                    appearsInAlbum = emptyList(),
                    appearsInPlaylist = page.playlists.map { it.toPlaylist() },
                    audios = page.topSongs.map { it.toAudio(favorites) },
                )
            )
        }
    }

    override fun genre(
        genreUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Genre, GenreContent>>> = withLibrary { library ->
        val language = genreUri.lastPathSegment ?: return@withLibrary Result.Failure(
            Error.NOT_FOUND
        )
        val favorites = library.favorites.toSet()

        coroutineScope {
            val songs = async { client.trending("song", language) }
            val albums = async { client.trending("album", language) }
            val modules = async { client.modules(language) }

            val songItems = songs.await().data()?.let { SaavnParser.results(it) }.orEmpty()
                .mapNotNull { SaavnParser.song(it) }
                .onEach { songCache[it.id] = it }
            val albumItems = albums.await().data()?.let { SaavnParser.results(it) }.orEmpty()
                .mapNotNull { SaavnParser.album(it) }
            val playlistItems = modules.await().data()?.let { SaavnParser.shelves(it) }.orEmpty()
                .flatMap { it.items }
                .filterIsInstance<SaavnPlaylist>()
                .distinctBy { it.id }

            if (songItems.isEmpty() && albumItems.isEmpty() && playlistItems.isEmpty()) {
                return@coroutineScope Result.Failure(Error.IO)
            }

            Result.Success(
                Genre.Builder(genreUri).setName(language.titleCase()).build() to GenreContent(
                    appearsInAlbums = albumItems.map { it.toAlbum() },
                    appearsInPlaylists = playlistItems.map { it.toPlaylist() },
                    audios = songItems.map { it.toAudio(favorites) },
                )
            )
        }
    }

    override fun playlist(
        playlistUri: Uri,
    ): Flow<MediaRequestStatus<Pair<Playlist, List<Audio>>>> = withLibrary { library ->
        val favorites = library.favorites.toSet()
        val segments = playlistUri.pathSegments

        when {
            playlistUri == favoritesUri -> songs(library.favorites).map { songs ->
                favoritesPlaylist to songs.map { it.toAudio(favorites) }
            }

            segments.size == 3 && segments[1] == PATH_USER_PLAYLIST -> {
                val stored = segments[2].toLongOrNull()?.let { store.playlist(it) }
                    ?: return@withLibrary Result.Failure(Error.NOT_FOUND)

                songs(stored.songIds).map { songs ->
                    Playlist.Builder(playlistUri)
                        .setThumbnail(
                            songs.firstNotNullOfOrNull { it.image }
                                ?.toThumbnail(Thumbnail.Type.FRONT_COVER)
                        )
                        .setName(stored.name)
                        .setType(Playlist.Type.PLAYLIST)
                        .build() to songs.map { it.toAudio(favorites) }
                }
            }

            segments.size == 2 -> client.playlist(segments[1]).flatMapData { first ->
                val page = SaavnParser.playlistPage(first) ?: return@flatMapData Result.Failure(
                    Error.INVALID_RESPONSE
                )

                val songs = page.songs.toMutableList()
                val total = page.playlist.songCount ?: songs.size
                var pageNumber = 2
                while (songs.size < total && pageNumber <= MAX_PLAYLIST_PAGES) {
                    val more = client.playlist(segments[1], pageNumber).data()
                        ?.let { SaavnParser.playlistPage(it) }?.songs.orEmpty()
                    if (more.isEmpty()) {
                        break
                    }
                    songs.addAll(more)
                    pageNumber++
                }

                val distinctSongs = songs.distinctBy { it.id }.onEach { songCache[it.id] = it }
                val playlist = page.playlist.toPlaylist()

                Result.Success(
                    playlist to distinctSongs.map { it.toAudio(favorites, fallbackThumbnail = playlist) }
                )
            }

            else -> Result.Failure(Error.NOT_FOUND)
        }
    }

    override fun audioPlaylistsStatus(
        audioUri: Uri,
    ): Flow<MediaRequestStatus<List<Pair<Playlist, Boolean>>>> = withLibrary { library ->
        val id = audioUri.lastPathSegment ?: return@withLibrary Result.Failure(Error.NOT_FOUND)

        Result.Success(
            buildList {
                add(favoritesPlaylist to (id in library.favorites))

                library.playlists.sortedByDescending { it.modifiedAt }.forEach {
                    add(
                        Playlist.Builder(userPlaylistUri(it.id))
                            .setName(it.name)
                            .setType(Playlist.Type.PLAYLIST)
                            .build() to (id in it.songIds)
                    )
                }
            }
        )
    }

    override fun lyrics(audioUri: Uri): Flow<Result<org.lineageos.twelve.models.Lyrics, Error>> =
        flow {
            val song = audioUri.lastPathSegment?.let { song(it) }
            if (song == null) {
                emit(Result.Failure(Error.NOT_FOUND))
                return@flow
            }

            var found = false
            lyricsClient.lyrics(
                song.title,
                song.artists.firstOrNull()?.name,
                song.albumName,
                song.durationSeconds,
            ).collect {
                found = true
                emit(Result.Success(it))
            }

            if (!found) {
                emit(Result.Failure(Error.NOT_FOUND))
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Find the song of the catalog each track names.
     *
     * @param onProgress Called with the number of tracks looked at so far
     * @return The id of the song found for every track, null where there was none
     */
    suspend fun matchTracks(
        tracks: List<ImportTrack>,
        onProgress: (Int) -> Unit = {},
    ): List<String?> = coroutineScope {
        val gate = Semaphore(MATCH_PARALLELISM)
        var done = 0

        tracks.map { track ->
            async {
                gate.withPermit {
                    val query = listOfNotNull(
                        PlaylistImport.normalize(track.title),
                        track.artist?.split(',', '&')?.firstOrNull()?.let {
                            PlaylistImport.normalize(it)
                        },
                    ).joinToString(" ")

                    val candidates = client.searchSongs(query, count = MATCH_CANDIDATES).data()
                        ?.let { SaavnParser.results(it) }.orEmpty()
                        .mapNotNull { SaavnParser.song(it) }

                    val best = candidates.map { song ->
                        song to PlaylistImport.score(
                            track,
                            song.title,
                            song.artists.map { it.name },
                            song.durationSeconds,
                        )
                    }.maxByOrNull { it.second }

                    synchronized(this@SaavnDataSource) { onProgress(++done) }

                    best?.takeIf { it.second >= PlaylistImport.MINIMUM_SCORE }?.first?.also {
                        songCache[it.id] = it
                    }?.id
                }
            }
        }.map { it.await() }
    }

    /**
     * Add a playlist that came from outside: a file, a friend, another service.
     */
    fun importPlaylist(name: String, songIds: List<String>): Uri =
        userPlaylistUri(store.createPlaylist(name, songIds))

    // Playlists management

    override suspend fun createPlaylist(
        providerIdentifier: ProviderIdentifier,
        name: String,
    ): MediaRequestStatus<Uri> = Result.Success(userPlaylistUri(store.createPlaylist(name)))

    override suspend fun renamePlaylist(
        playlistUri: Uri,
        name: String,
    ): MediaRequestStatus<Unit> = userPlaylistId(playlistUri)?.let {
        store.renamePlaylist(it, name)
        Result.Success(Unit)
    } ?: Result.Failure(Error.NOT_IMPLEMENTED)

    override suspend fun deletePlaylist(playlistUri: Uri): MediaRequestStatus<Unit> =
        userPlaylistId(playlistUri)?.let {
            store.deletePlaylist(it)
            Result.Success(Unit)
        } ?: Result.Failure(Error.NOT_IMPLEMENTED)

    override suspend fun addAudioToPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ): MediaRequestStatus<Unit> {
        val audioId = audioUri.lastPathSegment ?: return Result.Failure(Error.NOT_FOUND)

        return when {
            playlistUri == favoritesUri -> {
                store.setFavorite(audioId, true)
                Result.Success(Unit)
            }

            else -> userPlaylistId(playlistUri)?.let {
                store.addToPlaylist(it, audioId)
                Result.Success(Unit)
            } ?: Result.Failure(Error.NOT_IMPLEMENTED)
        }
    }

    override suspend fun removeAudioFromPlaylist(
        playlistUri: Uri,
        audioUri: Uri,
    ): MediaRequestStatus<Unit> {
        val audioId = audioUri.lastPathSegment ?: return Result.Failure(Error.NOT_FOUND)

        return when {
            playlistUri == favoritesUri -> {
                store.setFavorite(audioId, false)
                Result.Success(Unit)
            }

            else -> userPlaylistId(playlistUri)?.let {
                store.removeFromPlaylist(it, audioId)
                Result.Success(Unit)
            } ?: Result.Failure(Error.NOT_IMPLEMENTED)
        }
    }

    override suspend fun onAudioPlayed(audioUri: Uri, positionMs: Long): MediaRequestStatus<Unit> =
        Result.Success(Unit)

    override suspend fun setFavorite(
        audioUri: Uri,
        isFavorite: Boolean,
    ): MediaRequestStatus<Unit> {
        val audioId = audioUri.lastPathSegment ?: return Result.Failure(Error.NOT_FOUND)
        store.setFavorite(audioId, isFavorite)
        return Result.Success(Unit)
    }

    override suspend fun suggestions(
        audioUri: Uri,
        limit: Int,
    ): MediaRequestStatus<List<Audio>> {
        val id = audioUri.lastPathSegment ?: return Result.Failure(Error.NOT_FOUND)
        val favorites = store.favoritesIds.toSet()

        return when (val response = client.suggestions(id, limit)) {
            is Result.Success -> SaavnParser.suggestions(response.data)
                .filter { it.id != id }
                .onEach { songCache[it.id] = it }
                .map { it.toAudio(favorites) }
                .let { Result.Success(it) }

            is Result.Failure -> response
        }
    }

    // Helpers

    /**
     * Re-run [block] every time the favorites or the playlists change. Network responses are
     * cached by the client so this is cheap.
     */
    private fun <T> withLibrary(
        block: suspend (StoredLibrary) -> Result<T, Error>,
    ): Flow<Result<T, Error>> = combine(store.library, refreshTick) { library, _ -> library }
        .mapLatest { library ->
            try {
                block(library)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failure(Error.IO, e)
            }
        }
        .flowOn(Dispatchers.IO)

    private fun Result<kotlinx.serialization.json.JsonElement, Error>.data() = when (this) {
        is Result.Success -> data
        is Result.Failure -> null
    }

    private inline fun <T> Result<kotlinx.serialization.json.JsonElement, Error>.flatMapData(
        block: (kotlinx.serialization.json.JsonElement) -> Result<T, Error>,
    ): Result<T, Error> = when (this) {
        is Result.Success -> block(data)
        is Result.Failure -> this
    }

    private inline fun <A, B> Result<A, Error>.map(block: (A) -> B): Result<B, Error> =
        when (this) {
            is Result.Success -> Result.Success(block(data))
            is Result.Failure -> this
        }

    private suspend fun song(id: String): SaavnSong? {
        songCache[id]?.let { return it }

        return songs(listOf(id)).let { it as? Result.Success }?.data?.firstOrNull()
    }

    /**
     * Resolve [ids] preserving their order. Songs that no longer exist are dropped.
     */
    private suspend fun songs(ids: List<String>): Result<List<SaavnSong>, Error> {
        if (ids.isEmpty()) {
            return Result.Success(emptyList())
        }

        val missing = ids.filterNot { it in songCache }.distinct()
        var failed = false

        missing.chunked(SONGS_PER_REQUEST).forEach { chunk ->
            when (val response = client.songs(chunk)) {
                is Result.Success -> SaavnParser.results(response.data)
                    .mapNotNull { SaavnParser.song(it) }
                    .forEach { songCache[it.id] = it }

                is Result.Failure -> failed = true
            }
        }

        val resolved = ids.mapNotNull { songCache[it] }

        return when {
            resolved.isEmpty() && failed -> Result.Failure(Error.IO)
            else -> Result.Success(resolved)
        }
    }

    private fun SaavnEntity.toMediaItem(favorites: Set<String>): MediaItem<*> = when (this) {
        is SaavnSong -> toAudio(favorites)
        is SaavnAlbum -> toAlbum()
        is SaavnArtist -> toArtist()
        is SaavnPlaylist -> toPlaylist()
    }

    private fun String.toThumbnail(type: Thumbnail.Type) = Thumbnail.Builder()
        .setUri(toUri())
        .setType(type)
        .build()

    private fun SaavnSong.toAudio(
        favorites: Set<String>,
        trackNumber: Int? = null,
        fallbackThumbnail: MediaItem<*>? = null,
    ) = Audio.Builder(audioUri(id))
        .setThumbnail(
            image?.toThumbnail(Thumbnail.Type.FRONT_COVER) ?: fallbackThumbnail?.thumbnail
        )
        .setPlaybackUri(
            SaavnStream.playbackUrl(encryptedMediaUrl, has320kbps, maxQuality)?.toUri()
        )
        .setMimeType("audio/mp4")
        .setTitle(title)
        .setType(Audio.Type.MUSIC)
        .setDurationMs(durationSeconds?.let { it * 1000 })
        .setArtistUri(artists.firstNotNullOfOrNull { it.id }?.let { artistUri(it) })
        .setArtistName(artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name })
        .setAlbumUri(albumId?.let { albumUri(it) })
        .setAlbumTitle(albumName)
        .setTrackNumber(trackNumber)
        .setGenreUri(language?.let { genreUri(it) })
        .setGenreName(language?.titleCase())
        .setYear(year)
        .setIsFavorite(id in favorites)
        .build()

    private fun SaavnAlbum.toAlbum() = Album.Builder(albumUri(id))
        .setThumbnail(image?.toThumbnail(Thumbnail.Type.FRONT_COVER))
        .setTitle(title)
        .setArtistUri(artists.firstNotNullOfOrNull { it.id }?.let { artistUri(it) })
        .setArtistName(artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name })
        .setYear(year)
        .build()

    private fun SaavnArtist.toArtist() = Artist.Builder(artistUri(id))
        .setThumbnail(image?.toThumbnail(Thumbnail.Type.BAND_ARTIST_LOGO))
        .setName(name)
        .build()

    private fun SaavnPlaylist.toPlaylist() = Playlist.Builder(playlistUri(id))
        .setThumbnail(image?.toThumbnail(Thumbnail.Type.FRONT_COVER))
        .setName(title)
        .setType(Playlist.Type.PLAYLIST)
        .build()

    private fun <T> List<T>.sorted(
        rule: SortingRule,
        name: (T) -> String?,
        artist: (T) -> String?,
        year: (T) -> Int?,
    ): List<T> {
        val sorted = when (rule.strategy) {
            SortingStrategy.NAME -> sortedBy { name(it)?.lowercase() ?: "" }
            SortingStrategy.ARTIST_NAME -> sortedBy { artist(it)?.lowercase() ?: "" }
            SortingStrategy.CREATION_DATE -> sortedBy { year(it) ?: 0 }
            SortingStrategy.RANDOM -> return shuffled()
            // The catalog already returns the most relevant items first
            SortingStrategy.MODIFICATION_DATE, SortingStrategy.PLAY_COUNT -> return this
        }

        return if (rule.reverse) sorted.reversed() else sorted
    }

    private fun String.titleCase() = replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

    private fun Uri.isCatalogUri() = scheme == SCHEME && authority == AUTHORITY

    private fun userPlaylistId(uri: Uri) = uri.pathSegments.takeIf {
        it.size == 3 && it[0] == PATH_PLAYLIST && it[1] == PATH_USER_PLAYLIST
    }?.get(2)?.toLongOrNull()

    private val favoritesUri = catalogUri(PATH_PLAYLIST, PATH_FAVORITES)

    private val favoritesPlaylist = Playlist.Builder(favoritesUri)
        .setType(Playlist.Type.FAVORITES)
        .build()

    companion object {
        /**
         * The only provider exposed by this data source.
         */
        val PROVIDER_IDENTIFIER = ProviderIdentifier(ProviderType.SAAVN, 0L)

        const val SCHEME = "saavn"
        private const val AUTHORITY = "catalog"

        private const val PATH_ALBUM = "album"
        private const val PATH_ARTIST = "artist"
        private const val PATH_AUDIO = "audio"
        private const val PATH_GENRE = "genre"
        private const val PATH_PLAYLIST = "playlist"
        private const val PATH_USER_PLAYLIST = "user"
        private const val PATH_FAVORITES = "favorites"

        private const val SONG_CACHE_SIZE = 600
        private const val SONGS_PER_REQUEST = 40
        private const val MAX_PLAYLIST_PAGES = 6

        /**
         * Languages offered as genres.
         */
        val LANGUAGES = listOf(
            "hindi", "english", "punjabi", "tamil", "telugu", "bengali", "marathi", "gujarati",
            "kannada", "malayalam", "bhojpuri", "haryanvi", "rajasthani", "assamese", "odia",
        )

        private fun catalogUri(vararg segments: String) = Uri.Builder()
            .scheme(SCHEME)
            .authority(AUTHORITY)
            .apply { segments.forEach { appendPath(it) } }
            .build()

        private fun audioUri(id: String) = catalogUri(PATH_AUDIO, id)
        private fun albumUri(id: String) = catalogUri(PATH_ALBUM, id)
        private fun artistUri(id: String) = catalogUri(PATH_ARTIST, id)
        private const val MATCH_PARALLELISM = 6
        private const val MATCH_CANDIDATES = 8

        private fun playlistUri(id: String) = catalogUri(PATH_PLAYLIST, id)
        private fun genreUri(language: String) = catalogUri(PATH_GENRE, language)
        private fun userPlaylistUri(id: Long) = catalogUri(
            PATH_PLAYLIST, PATH_USER_PLAYLIST, id.toString()
        )
    }
}
