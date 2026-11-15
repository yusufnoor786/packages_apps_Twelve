/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The places of the floating navigation bar, shared by the activity that shows the bar and the
 * main screen that shows the places.
 */
class MainNavigationViewModel : ViewModel() {
    enum class Destination { HOME, SEARCH, LIBRARY, PLAYLISTS }

    /**
     * The place the main screen shows, for the bar to highlight it.
     */
    val current = MutableStateFlow(Destination.HOME)

    private val mutableRequests = MutableSharedFlow<Destination>(extraBufferCapacity = 1)

    /**
     * Places the bar asks the main screen to go to.
     */
    val requests = mutableRequests.asSharedFlow()

    fun request(destination: Destination) {
        mutableRequests.tryEmit(destination)
    }

    /**
     * Whether the library shows its playlists, they have a place of their own on the bar.
     */
    val libraryShowsPlaylists = MutableStateFlow(false)

    /**
     * The library is asked to show its playlists (true) or to leave them for its albums (false),
     * it sets this back to null once it did.
     */
    val showPlaylistsRequest = MutableStateFlow<Boolean?>(null)
}
