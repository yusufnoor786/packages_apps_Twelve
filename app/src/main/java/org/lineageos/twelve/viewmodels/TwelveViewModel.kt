/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import org.lineageos.twelve.TwelveApplication
import org.lineageos.twelve.ext.applicationContext
import org.lineageos.twelve.ext.eventsFlow
import org.lineageos.twelve.ext.resources
import org.lineageos.twelve.ext.shuffleModeEnabled
import org.lineageos.twelve.ext.typedRepeatMode
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.services.PlaybackService
import org.lineageos.twelve.utils.SessionTokenUtils

/**
 * Base view model for all app view models.
 * Here we keep the shared stuff every fragment could use, like access to the repository and
 * the media controller to interact with the playback service.
 */
abstract class TwelveViewModel(application: Application) : AndroidViewModel(application) {
    protected val mediaRepository = getApplication<TwelveApplication>().mediaRepository

    /**
     * Forget what was loaded from the network, the lists load again.
     */
    fun refreshContent() {
        mediaRepository.refreshOnline()
    }

    /**
     * Add a playlist that came from outside to the online library.
     */
    fun importOnlinePlaylist(name: String, songIds: List<String>) =
        mediaRepository.importOnlinePlaylist(name, songIds)

    suspend fun importOnlinePlaylist(
        playlist: org.lineageos.twelve.utils.ImportedPlaylist,
        onProgress: (done: Int, total: Int) -> Unit,
    ) = mediaRepository.importOnlinePlaylist(playlist, onProgress)

    suspend fun spotifyPlaylist(playlistId: String) = mediaRepository.spotifyPlaylist(playlistId)

    val hasSpotifyCredentials: Boolean
        get() = !sharedPreferences.getString(
            org.lineageos.twelve.ext.SPOTIFY_CLIENT_ID_KEY, null
        ).isNullOrBlank() && !sharedPreferences.getString(
            org.lineageos.twelve.ext.SPOTIFY_CLIENT_SECRET_KEY, null
        ).isNullOrBlank()
    protected val providersRepository = getApplication<TwelveApplication>().providersRepository
    protected val outputConfigurationRepository =
        getApplication<TwelveApplication>().outputConfigurationRepository

    @Suppress("EmptyMethod")
    final override fun <T : Application> getApplication() = super.getApplication<T>()

    protected val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(application)!!
    }

    private val sessionToken by lazy {
        SessionTokenUtils.forLibraryService(
            applicationContext,
            ComponentName(applicationContext, PlaybackService::class.java)
        )
    }

    protected val mediaControllerFlow = channelFlow {
        val mediaController = MediaController.Builder(applicationContext, sessionToken)
            .buildAsync()
            .await()

        trySend(mediaController)

        awaitClose {
            mediaController.release()
        }
    }
        .flowOn(Dispatchers.Main)
        .shareIn(
            viewModelScope,
            SharingStarted.Eagerly,
            replay = 1
        )

    protected val mediaController = mediaControllerFlow
        .stateIn(
            viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    protected val eventsFlow = mediaControllerFlow
        .flatMapLatest { it.eventsFlow() }
        .shareIn(
            viewModelScope,
            SharingStarted.Eagerly,
            replay = 1
        )

    protected var shuffleModeEnabled: Boolean
        get() = mediaController.value?.shuffleModeEnabled ?: false
        set(value) {
            mediaController.value?.apply {
                shuffleModeEnabled = value
                sharedPreferences.shuffleModeEnabled = value
            }
        }

    protected var typedRepeatMode: RepeatMode
        get() = mediaController.value?.typedRepeatMode ?: RepeatMode.NONE
        set(value) {
            mediaController.value?.apply {
                typedRepeatMode = value
                sharedPreferences.typedRepeatMode = value
            }
        }

    /**
     * Whether music is playing, or about to once it buffered.
     */
    val isPlayingNow: Boolean
        get() = mediaController.value?.let {
            it.isPlaying || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING)
        } ?: false

    fun playAudio(audios: List<Audio>, position: Int) {
        mediaController.value?.apply {
            // Initialize shuffle and repeat modes
            shuffleModeEnabled = sharedPreferences.shuffleModeEnabled
            typedRepeatMode = sharedPreferences.typedRepeatMode

            setMediaItems(audios.map { it.toMedia3MediaItem(resources) }, true)
            prepare()
            seekToDefaultPosition(position)
            play()
        }
    }

    fun addToQueue(audios: List<Audio>) {
        mediaController.value?.apply {
            addMediaItems(audios.map { it.toMedia3MediaItem(resources) })

            if (mediaItemCount == audios.count()) {
                play()
            }
        }
    }

    fun playNext(audios: List<Audio>) {
        mediaController.value?.apply {
            addMediaItems(
                currentMediaItemIndex + 1,
                audios.map { it.toMedia3MediaItem(resources) },
            )

            if (mediaItemCount == audios.count()) {
                play()
            }
        }
    }
}
