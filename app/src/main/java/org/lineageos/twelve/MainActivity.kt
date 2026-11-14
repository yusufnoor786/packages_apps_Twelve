/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.DialogFragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.slideDown
import org.lineageos.twelve.ext.slideUp
import org.lineageos.twelve.ext.updateBarsVisibility
import org.lineageos.twelve.fragments.AlbumFragment
import org.lineageos.twelve.fragments.ArtistFragment
import org.lineageos.twelve.fragments.GenreFragment
import org.lineageos.twelve.fragments.PlaylistFragment
import org.lineageos.twelve.models.MediaType
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.ui.views.NowPlayingBar
import org.lineageos.twelve.viewmodels.FullscreenViewModel
import org.lineageos.twelve.viewmodels.IntentsViewModel
import org.lineageos.twelve.viewmodels.NowPlayingViewModel
import kotlin.reflect.cast

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    // View models
    private val fullscreenViewModel by viewModels<FullscreenViewModel>()
    private val intentsViewModel by viewModels<IntentsViewModel>()
    private val nowPlayingViewModel by viewModels<NowPlayingViewModel>()

    // Views
    private val frameLayout by lazy { findViewById<FrameLayout>(R.id.linearLayout) }
    private val homeSnapshotImageView by lazy { findViewById<ImageView>(R.id.homeSnapshotImageView) }
    private val nowPlayingBar by lazy { findViewById<NowPlayingBar>(R.id.nowPlayingBar) }

    // NavController
    private val navHostFragment by lazy {
        NavHostFragment::class.cast(
            supportFragmentManager.findFragmentById(R.id.navHostFragment)
        )
    }
    private val navController by lazy { navHostFragment.navController }

    // Intents
    private val intentListener = Consumer<Intent> { intentsViewModel.onIntent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navigationBarsInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )

            systemInsets = insets
            updateNowPlayingBarPlacement()

            // This translates to bottom padding + space taken by the floating now playing bar.
            // The navigation bars insets are left untouched so that the main screen can still
            // place its own navigation bar at the very bottom
            WindowInsetsCompat.Builder(windowInsets)
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(
                        insets.left,
                        insets.top,
                        insets.right,
                        insets.bottom + when (nowPlayingBar.isVisible) {
                            true -> nowPlayingBarAreaHeight
                            false -> 0
                        }
                    )
                )
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBarsInsets)
                .build()
        }

        // Keep the floating now playing bar above the navigation bar of the main screen
        frameLayout.viewTreeObserver.addOnGlobalLayoutListener {
            updateNowPlayingBarPlacement()
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            frameLayout.post { updateNowPlayingBarPlacement() }

            // The screen below the player is rebuilt when it is closed, keep showing it meanwhile
            if (destination.id !in nowPlayingRelatedRouteIds && homeSnapshotImageView.isVisible) {
                // Covers the screen while it is rebuilt
                homeSnapshotImageView.bringToFront()

                frameLayout.postDelayed({
                    if (navController.currentDestination?.id !in nowPlayingRelatedRouteIds) {
                        homeSnapshotImageView.isVisible = false
                        homeSnapshotImageView.setImageDrawable(null)

                        // Back below everything, where the next player slides over it
                        frameLayout.removeView(homeSnapshotImageView)
                        frameLayout.addView(homeSnapshotImageView, 0)
                    }
                }, HOME_SNAPSHOT_HIDE_DELAY_MS)
            }
        }

        // Intents
        intentsViewModel.onIntent(intent)
        addOnNewIntentListener(intentListener)

        // Now playing bar
        nowPlayingBar.setOnClickListener {
            navigateToNowPlayingFragment()
        }

        nowPlayingBar.setOnPlayPauseClickListener {
            nowPlayingViewModel.togglePlayPause()
        }

        nowPlayingBar.setOnNextClickListener {
            nowPlayingViewModel.seekToNext()
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
            }
        }
    }

    override fun onDestroy() {
        removeOnNewIntentListener(intentListener)

        super.onDestroy()
    }

    private fun CoroutineScope.loadData() {
        launch {
            fullscreenViewModel.fullscreenMode.collectLatest {
                window.updateBarsVisibility(systemBars = !it)
            }
        }

        launch {
            intentsViewModel.parsedIntent.collectLatest { parsedIntent ->
                parsedIntent?.handle {
                    when (it.action) {
                        IntentsViewModel.ParsedIntent.Action.MAIN -> {
                            // We don't need to do anything
                        }

                        IntentsViewModel.ParsedIntent.Action.OPEN_NOW_PLAYING -> {
                            navigateToNowPlayingFragment()
                        }

                        IntentsViewModel.ParsedIntent.Action.VIEW -> {
                            if (it.contents.isEmpty()) {
                                Log.i(LOG_TAG, "No content to view")
                                return@handle
                            }

                            val isSingleItem = it.contents.size == 1
                            if (!isSingleItem) {
                                Log.i(LOG_TAG, "Cannot handle multiple items")
                                return@handle
                            }

                            val content = it.contents.first()

                            when (content.type) {
                                MediaType.ALBUM -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_album,
                                    AlbumFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )

                                MediaType.ARTIST -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_artist,
                                    ArtistFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )

                                MediaType.AUDIO -> Log.i(LOG_TAG, "Audio not supported")

                                MediaType.GENRE -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_genre,
                                    GenreFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )

                                MediaType.PLAYLIST -> navController.navigateSafe(
                                    R.id.action_mainFragment_to_fragment_playlist,
                                    PlaylistFragment.createBundle(content.uri),
                                    NavOptions.Builder()
                                        .setPopUpTo(R.id.fragment_main, false)
                                        .build(),
                                )
                            }
                        }
                    }
                }
            }
        }

        launch {
            nowPlayingViewModel.durationCurrentPositionMs.collectLatest {
                nowPlayingBar.updateDurationCurrentPositionMs(it.first, it.second)
            }
        }

        launch {
            nowPlayingViewModel.isPlaying.collectLatest {
                nowPlayingBar.updateIsPlaying(it)
            }
        }

        launch {
            combine(
                nowPlayingViewModel.mediaItem,
                navController.visibleEntries,
            ) { mediaItem, visibleEntries ->
                mediaItem != null && run {
                    var shouldBeVisible = true

                    for (i in visibleEntries.lastIndex downTo 0) {
                        when (val destination = visibleEntries[i].destination) {
                            is DialogFragmentNavigator.Destination -> continue

                            else -> {
                                shouldBeVisible = destination.id !in nowPlayingRelatedRouteIds
                                break
                            }
                        }
                    }

                    shouldBeVisible
                }
            }
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)
                .collectLatest { shouldBeVisible ->
                    when (shouldBeVisible) {
                        true -> nowPlayingBar.slideUp()
                        false -> nowPlayingBar.slideDown()
                    }

                    frameLayout.requestApplyInsets()
                }
        }

        launch {
            nowPlayingViewModel.mediaMetadata.collectLatest {
                nowPlayingBar.updateMediaMetadata(it)
            }
        }

        launch {
            nowPlayingViewModel.mediaArtwork.collectLatest {
                when (it) {
                    null -> {
                        // Do nothing
                    }

                    is Result.Success -> {
                        nowPlayingBar.updateMediaArtwork(it.data)
                    }

                    is Result.Failure -> {
                        Log.e(
                            LOG_TAG,
                            "Error while getting media artwork: ${it.error}",
                            it.throwable
                        )
                    }
                }
            }
        }
    }

    /**
     * Total vertical space taken by the floating now playing bar, including its margins.
     */
    private val nowPlayingBarAreaHeight by lazy {
        resources.getDimensionPixelSize(R.dimen.now_playing_bar_height) +
                2 * resources.getDimensionPixelSize(R.dimen.now_playing_bar_margin_vertical)
    }

    private var systemInsets = Insets.NONE

    /**
     * Float the now playing bar above the bottom navigation bar of the main screen, or above the
     * system bars everywhere else.
     */
    private fun updateNowPlayingBarPlacement() {
        val horizontalMargin = resources.getDimensionPixelSize(
            R.dimen.now_playing_bar_margin_horizontal
        )
        val verticalMargin = resources.getDimensionPixelSize(
            R.dimen.now_playing_bar_margin_vertical
        )

        var bottomBase = systemInsets.bottom
        var startBase = systemInsets.left

        val navigationBarView = frameLayout.findViewById<View>(R.id.navigationBarView)
            ?.takeIf { navController.currentDestination?.id == R.id.mainFragment }
            ?.takeIf { it.isShown && it.width > 0 }

        if (navigationBarView != null) {
            when (navigationBarView.width >= frameLayout.width / 2) {
                // Bottom navigation bar
                true -> bottomBase = maxOf(bottomBase, frameLayout.height - navigationBarView.top)

                // Navigation rail
                false -> startBase = maxOf(startBase, navigationBarView.right)
            }
        }

        val layoutParams = nowPlayingBar.layoutParams as FrameLayout.LayoutParams
        val start = startBase + horizontalMargin
        val end = systemInsets.right + horizontalMargin
        val bottom = bottomBase + verticalMargin

        if (layoutParams.marginStart != start
            || layoutParams.marginEnd != end
            || layoutParams.bottomMargin != bottom
        ) {
            layoutParams.marginStart = start
            layoutParams.marginEnd = end
            layoutParams.bottomMargin = bottom
            nowPlayingBar.layoutParams = layoutParams
        }
    }

    private var isOpeningNowPlaying = false

    private fun navigateToNowPlayingFragment() {
        if (isOpeningNowPlaying) {
            return
        }
        isOpeningNowPlaying = true

        // Copied from the window before the player covers the screen, images that live on the
        // GPU can't be drawn any other way
        val width = frameLayout.width
        val height = frameLayout.height
        if (width <= 0 || height <= 0) {
            openNowPlaying()
            return
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    homeSnapshotImageView.setImageBitmap(bitmap)
                    homeSnapshotImageView.isVisible = true
                }
                openNowPlaying()
            }, Handler(Looper.getMainLooper()))
        }.onFailure { openNowPlaying() }
    }

    private fun openNowPlaying() {
        isOpeningNowPlaying = false

        navController.navigate(
            R.id.fragment_now_playing,
            null,
            NavOptions.Builder()
                .setPopUpTo(R.id.fragment_now_playing, true)
                .build(),
        )
    }

    companion object {
        private val LOG_TAG = MainActivity::class.simpleName!!

        private const val HOME_SNAPSHOT_HIDE_DELAY_MS = 650L

        /**
         * Now playing related route IDs.
         */
        private val nowPlayingRelatedRouteIds = setOf(
            R.id.lyricsFragment,
            R.id.nowPlayingFragment,
            R.id.queueFragment,
        )

        /**
         * Open now playing fragment.
         * Type: [Boolean]
         */
        const val EXTRA_OPEN_NOW_PLAYING = "extra_now_playing"
    }
}
