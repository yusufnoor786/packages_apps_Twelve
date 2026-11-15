/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
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
import androidx.activity.OnBackPressedCallback
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.flow.MutableStateFlow
import org.lineageos.twelve.fragments.NowPlayingFragment
import org.lineageos.twelve.ui.update.showUpdateDialog
import org.lineageos.twelve.utils.LauncherIconRefresher
import org.lineageos.twelve.repositories.UpdateRepository
import org.lineageos.twelve.services.UpdateCheckService
import androidx.navigation.fragment.NavHostFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    private var rawWindowInsets: WindowInsetsCompat? = null

    private val nowPlayingContainer by lazy { findViewById<FrameLayout>(R.id.nowPlayingContainer) }
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

    // Updates
    private val updateRepository by lazy { (application as TwelveApplication).updateRepository }
    private var showUpdateOnStart = false
    private var updatePromptedThisRun = false
    private val updateIntentListener = Consumer<Intent> {
        if (it.getBooleanExtra(UpdateCheckService.EXTRA_SHOW_UPDATE, false)) {
            showUpdateOnStart = true
            checkForUpdate()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    /**
     * Ask once whether the app may show notifications, for the updates: playback does not need
     * it.
     */
    private fun askNotificationPermissionOnce() {
        if (!updateRepository.isAvailable ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val preferences = getSharedPreferences("updates", MODE_PRIVATE)
        if (preferences.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) {
            return
        }
        preferences.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Look for an update and show its popup, once a run unless the notification asked for it.
     */
    private fun checkForUpdate() {
        if (!updateRepository.isAvailable) {
            return
        }
        if (!updateRepository.autoCheck && !showUpdateOnStart) {
            return
        }

        lifecycleScope.launch {
            val result = updateRepository.check(force = showUpdateOnStart)
            val release = (result as? UpdateRepository.CheckResult.Available)?.release
                ?: return@launch

            val asked = showUpdateOnStart
            showUpdateOnStart = false
            if (asked || (!updatePromptedThisRun && updateRepository.shouldPrompt(release))) {
                updatePromptedThisRun = true
                showUpdateDialog(updateRepository, release)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        checkForUpdate()
        LauncherIconRefresher.refreshIfNeeded(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, nowPlayingBackCallback)

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
            rawWindowInsets = windowInsets
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

        // The player covers the mini player, it needs the plain insets and not the space reserved
        // for the mini player
        ViewCompat.setOnApplyWindowInsetsListener(nowPlayingContainer) { _, windowInsets ->
            rawWindowInsets ?: windowInsets
        }

        // Keep the floating now playing bar above the navigation bar of the main screen
        frameLayout.viewTreeObserver.addOnGlobalLayoutListener {
            updateNowPlayingBarPlacement()
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            frameLayout.post { updateNowPlayingBarPlacement() }

            // Something else was opened from the player (an album, the lyrics...), it goes away
            if (isNowPlayingOpen
                && destination !is DialogFragmentNavigator.Destination
                && destination.id != R.id.mainFragment
            ) {
                closeNowPlaying(animated = false)
            }
        }

        // Intents
        intentsViewModel.onIntent(intent)
        addOnNewIntentListener(intentListener)

        // Updates: the notification opens the popup, otherwise it shows when one is found
        showUpdateOnStart = intent?.getBooleanExtra(UpdateCheckService.EXTRA_SHOW_UPDATE, false)
            ?: false
        addOnNewIntentListener(updateIntentListener)
        askNotificationPermissionOnce()

        // Now playing bar
        nowPlayingBar.setOnClickListener {
            openNowPlaying()
        }

        // The player may have survived a configuration change
        if (supportFragmentManager.findFragmentById(R.id.nowPlayingContainer) != null) {
            isNowPlayingOpen = true
            nowPlayingContainer.isVisible = true
            isNowPlayingOpenFlow.value = true
            nowPlayingBackCallback.isEnabled = true
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
        removeOnNewIntentListener(updateIntentListener)

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
                            openNowPlaying()
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
            nowPlayingViewModel.mediaItem.map { it != null }
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

    private var isNowPlayingOpen = false
    private val isNowPlayingOpenFlow = MutableStateFlow(false)

    private val nowPlayingBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeNowPlaying()
        }
    }

    /**
     * Show the player above the main screen. The main screen stays alive below it, closing the
     * player is instant.
     */
    fun openNowPlaying() {
        if (isNowPlayingOpen) {
            return
        }

        isNowPlayingOpen = true
        isNowPlayingOpenFlow.value = true
        nowPlayingBackCallback.isEnabled = true

        nowPlayingContainer.animate().cancel()
        nowPlayingContainer.translationY = nowPlayingContainer.height.toFloat()
        nowPlayingContainer.isVisible = true

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.nowPlayingContainer, NowPlayingFragment())
            .commitNow()

        nowPlayingContainer.animate()
            .translationY(0f)
            .setDuration(OPEN_ANIMATION_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /**
     * Hide the player.
     *
     * @param animated Whether it slides down, it doesn't when it was already dragged away
     */
    fun closeNowPlaying(animated: Boolean = true) {
        if (!isNowPlayingOpen) {
            return
        }

        isNowPlayingOpen = false
        isNowPlayingOpenFlow.value = false
        nowPlayingBackCallback.isEnabled = false

        val finish = {
            supportFragmentManager.findFragmentById(R.id.nowPlayingContainer)?.let {
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .remove(it)
                    .commitAllowingStateLoss()
            }

            nowPlayingContainer.isVisible = false
            nowPlayingContainer.translationY = 0f
        }

        nowPlayingContainer.animate().cancel()
        when (animated) {
            true -> nowPlayingContainer.animate()
                .translationY(nowPlayingContainer.height.toFloat())
                .setDuration(CLOSE_ANIMATION_MS)
                .setInterpolator(AccelerateInterpolator(1.4f))
                .withEndAction(finish)
                .start()

            false -> finish()
        }
    }

    companion object {
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

        private val LOG_TAG = MainActivity::class.simpleName!!

        private const val OPEN_ANIMATION_MS = 340L
        private const val CLOSE_ANIMATION_MS = 260L

        /**
         * Open now playing fragment.
         * Type: [Boolean]
         */
        const val EXTRA_OPEN_NOW_PLAYING = "extra_now_playing"
    }
}
