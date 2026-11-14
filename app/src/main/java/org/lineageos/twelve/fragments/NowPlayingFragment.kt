/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.icu.text.DecimalFormat
import android.icu.text.DecimalFormatSymbols
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.bogerchan.niervisualizer.NierVisualizerManager
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.applyBackdropEffects
import org.lineageos.twelve.ext.blur
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.loadBackdrop
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.ext.updateValueAndRange
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.FlowResult.Companion.getOrNull
import org.lineageos.twelve.models.OutputConfiguration
import org.lineageos.twelve.models.PlaybackState
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.ui.recyclerview.CenterSmoothScroller
import org.lineageos.twelve.ui.recyclerview.LyricsAdapter
import org.lineageos.twelve.ui.visualizer.VisualizerNVDataSource
import org.lineageos.twelve.utils.PermissionsChecker
import org.lineageos.twelve.utils.PermissionsUtils
import org.lineageos.twelve.utils.TimestampFormatter
import org.lineageos.twelve.viewmodels.NowPlayingViewModel
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Now playing fragment.
 *
 * In portrait the lyrics are shown in place: the cover moves up to a small header and the lyrics
 * take its room, while the seek bar and the controls stay where they are. In landscape the
 * lyrics have a screen of their own.
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing) {
    // View models
    private val viewModel by viewModels<NowPlayingViewModel>()

    // Views
    private val albumArtConstraintLayout by getViewProperty<ConstraintLayout?>(R.id.albumArtConstraintLayout)
    private val albumArtImageView by getViewProperty<ImageView>(R.id.albumArtImageView)
    private val albumArtMaterialCardView by getViewProperty<MaterialCardView>(R.id.albumArtMaterialCardView)
    private val albumTitleTextView by getViewProperty<TextView>(R.id.albumTitleTextView)
    private val audioInformationMaterialButton by getViewProperty<MaterialButton>(R.id.audioInformationMaterialButton)
    private val audioTitleTextView by getViewProperty<TextView>(R.id.audioTitleTextView)
    private val backdropImageView by getViewProperty<ImageView>(R.id.backdropImageView)
    private val artistNameTextView by getViewProperty<TextView>(R.id.artistNameTextView)
    private val currentLyricsTextView by getViewProperty<TextView>(R.id.currentLyricsTextView)
    private val currentTimestampTextView by getViewProperty<TextView>(R.id.currentTimestampTextView)
    private val durationTimestampTextView by getViewProperty<TextView>(R.id.durationTimestampTextView)
    private val equalizerMaterialButton by getViewProperty<MaterialButton>(R.id.equalizerMaterialButton)
    private val fileTypeTextView by getViewProperty<TextView>(R.id.fileTypeTextView)
    private val hiResImageView by getViewProperty<ImageView>(R.id.hiResImageView)
    private val isFavoriteMaterialButton by getViewProperty<MaterialButton>(R.id.isFavoriteMaterialButton)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val lyricsToggleMaterialButton by getViewProperty<MaterialButton>(R.id.lyricsToggleMaterialButton)
    private val nestedScrollView by getViewProperty<NestedScrollView>(R.id.nestedScrollView)
    private val nextTrackMaterialButton by getViewProperty<MaterialButton>(R.id.nextTrackMaterialButton)
    private val outputDeviceImageView by getViewProperty<ImageView>(R.id.outputDeviceImageView)
    private val playPauseMaterialButton by getViewProperty<MaterialButton>(R.id.playPauseMaterialButton)
    private val playbackSpeedMaterialButton by getViewProperty<MaterialButton>(R.id.playbackSpeedMaterialButton)
    private val potentialIssuesImageView by getViewProperty<ImageView>(R.id.potentialIssuesImageView)
    private val previousTrackMaterialButton by getViewProperty<MaterialButton>(R.id.previousTrackMaterialButton)
    private val progressSlider by getViewProperty<Slider>(R.id.progressSlider)
    private val queueMaterialButton by getViewProperty<MaterialButton>(R.id.queueMaterialButton)
    private val repeatMarkerImageView by getViewProperty<ImageView>(R.id.repeatMarkerImageView)
    private val repeatMaterialButton by getViewProperty<MaterialButton>(R.id.repeatMaterialButton)
    private val shuffleMarkerImageView by getViewProperty<ImageView>(R.id.shuffleMarkerImageView)
    private val shuffleMaterialButton by getViewProperty<MaterialButton>(R.id.shuffleMaterialButton)
    private val statsMaterialCardView by getViewProperty<MaterialCardView>(R.id.statsMaterialCardView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val visualizerMaterialButton by getViewProperty<MaterialButton>(R.id.visualizerMaterialButton)
    private val visualizerSurfaceView by getViewProperty<SurfaceView>(R.id.visualizerSurfaceView)

    // Portrait only views
    private val compactCoverImageView by getViewProperty<ImageView?>(R.id.compactCoverImageView)
    private val compactCoverMaterialCardView by getViewProperty<MaterialCardView?>(R.id.compactCoverMaterialCardView)
    private val compactArtistTextView by getViewProperty<TextView?>(R.id.compactArtistTextView)
    private val compactHeaderLinearLayout by getViewProperty<LinearLayout?>(R.id.compactHeaderLinearLayout)
    private val compactOptionsMaterialButton by getViewProperty<MaterialButton?>(R.id.compactOptionsMaterialButton)
    private val compactTitleTextView by getViewProperty<TextView?>(R.id.compactTitleTextView)
    private val lyricsLinearLayout by getViewProperty<LinearLayout?>(R.id.lyricsLinearLayout)
    private val lyricsPreviewLinearLayout by getViewProperty<LinearLayout?>(R.id.lyricsPreviewLinearLayout)
    private val lyricsRecyclerView by getViewProperty<RecyclerView?>(R.id.lyricsRecyclerView)
    private val optionsMaterialButton by getViewProperty<MaterialButton?>(R.id.optionsMaterialButton)
    private val playerRootLinearLayout by getViewProperty<LinearLayout?>(R.id.playerRootLinearLayout)
    private val topFrameLayout by getViewProperty<FrameLayout?>(R.id.topFrameLayout)

    // Landscape only views
    private val lyricsMaterialCardView by getViewProperty<MaterialCardView?>(R.id.lyricsMaterialCardView)
    private val nextLyricsTextView by getViewProperty<TextView?>(R.id.nextLyricsTextView)
    private val previousLyricsTextView by getViewProperty<TextView?>(R.id.previousLyricsTextView)
    private val showLyricsMaterialButton by getViewProperty<MaterialButton?>(R.id.showLyricsMaterialButton)

    // Progress slider state
    private var isProgressSliderDragging = false

    // Lyrics
    private val lyricsAdapter by lazy {
        LyricsAdapter { line ->
            line.durationMs?.let { viewModel.seekToPosition(it.first) }
        }
    }
    private var hasLyrics = false
    private var isLyricsMode = false
    private var lyricsCurrentIndex: Int? = null
    private var lastCenteredLyricsIndex = -1
    private var isLyricsFollowing = true
    private val handler = Handler(Looper.getMainLooper())
    private val resumeLyricsFollowing = Runnable {
        isLyricsFollowing = true
        centerCurrentLyricsLine(animate = true)
    }
    private val lyricsBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setLyricsMode(false, animate = true)
        }
    }

    // AudioFX
    private val audioEffectsStartForResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Empty
        }

    // Visualizer
    private val visualizerManager = NierVisualizerManager()
    private val visualizerNVDataSource by lazy { VisualizerNVDataSource() }
    private var isVisualizerStarted = false

    // Permissions
    private val visualizerPermissionsChecker = PermissionsChecker(
        this,
        PermissionsUtils.visualizerPermissions,
        true,
        R.string.visualizer_permissions_toast,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        visualizerManager.init(visualizerNVDataSource)

        isLyricsMode = savedInstanceState?.getBoolean(STATE_LYRICS_MODE) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(STATE_LYRICS_MODE, isLyricsMode)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Blurred artwork backdrop
        backdropImageView.applyBackdropEffects()

        // Landscape lyrics preview, lines that are not playing are dimmed and blurred
        previousLyricsTextView?.blur(LYRICS_PREVIEW_BLUR_RADIUS)
        nextLyricsTextView?.blur(LYRICS_PREVIEW_BLUR_RADIUS)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                start = true,
                end = true,
            )

            windowInsets
        }

        albumArtConstraintLayout?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                v.updatePadding(
                    insets,
                    bottom = true,
                )

                windowInsets
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            playerRootLinearLayout ?: nestedScrollView
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            // The lyrics header sits below the toolbar
            compactHeaderLinearLayout?.let {
                val toolbarHeight = TypedValue().let { value ->
                    requireContext().theme.resolveAttribute(
                        android.R.attr.actionBarSize, value, true
                    )
                    TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
                }

                it.setPadding(
                    it.paddingLeft,
                    insets.top + toolbarHeight,
                    it.paddingRight,
                    it.paddingBottom,
                )
            }

            windowInsets
        }

        // Top bar
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        statsMaterialCardView.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_nowPlayingFragment_to_fragment_now_playing_stats_dialog
            )
        }

        // Visualizer
        visualizerSurfaceView.setZOrderOnTop(true)
        visualizerSurfaceView.holder.setFormat(PixelFormat.TRANSPARENT)

        // Audio information
        audioTitleTextView.isSelected = true
        artistNameTextView.isSelected = true
        albumTitleTextView.isSelected = true

        // Media controls
        progressSlider.setLabelFormatter {
            TimestampFormatter.formatTimestampMillis(it)
        }
        progressSlider.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    isProgressSliderDragging = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    isProgressSliderDragging = false
                    viewModel.seekToPosition(slider.value.roundToLong())
                }
            }
        )

        shuffleMaterialButton.setOnClickListener {
            viewModel.toggleShuffleMode()
        }

        previousTrackMaterialButton.setOnClickListener {
            viewModel.seekToPrevious()
        }

        playPauseMaterialButton.setOnClickListener {
            viewModel.togglePlayPause()
        }

        nextTrackMaterialButton.setOnClickListener {
            viewModel.seekToNext()
        }

        repeatMaterialButton.setOnClickListener {
            viewModel.toggleRepeatMode()
        }

        // Bottom bar buttons
        playbackSpeedMaterialButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_nowPlayingFragment_to_fragment_playback_control_bottom_sheet_dialog
            )
        }

        audioInformationMaterialButton.setOnClickListener {
            openAudioOptions()
        }

        optionsMaterialButton?.setOnClickListener {
            openAudioOptions()
        }

        compactOptionsMaterialButton?.setOnClickListener {
            openAudioOptions()
        }

        equalizerMaterialButton.setOnClickListener {
            // Open system equalizer
            viewModel.audioSessionId.value?.let { audioSessionId ->
                audioEffectsStartForResult.launch(
                    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, requireContext().packageName)
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    },
                    null
                )
            }
        }

        visualizerMaterialButton.setOnClickListener {
            viewModel.nextVisualizerType()
        }

        queueMaterialButton.setOnClickListener {
            findNavController().navigateSafe(R.id.action_nowPlayingFragment_to_fragment_queue)
        }

        isFavoriteMaterialButton.setOnClickListener {
            lifecycleScope.launch {
                isFavoriteMaterialButton.isEnabled = false
                viewModel.toggleFavorites()
                isFavoriteMaterialButton.isEnabled = true
            }
        }

        // Lyrics
        lyricsToggleMaterialButton.setOnClickListener {
            when (lyricsRecyclerView) {
                null -> showLyricsScreen()
                else -> setLyricsMode(!isLyricsMode, animate = true)
            }
        }

        lyricsPreviewLinearLayout?.setOnClickListener {
            setLyricsMode(true, animate = true)
        }

        showLyricsMaterialButton?.setOnClickListener {
            showLyricsScreen()
        }

        lyricsRecyclerView?.let { recyclerView ->
            recyclerView.adapter = lyricsAdapter

            recyclerView.addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                            // The user is reading elsewhere, stop following until they let go
                            isLyricsFollowing = false
                            handler.removeCallbacks(resumeLyricsFollowing)
                            handler.postDelayed(resumeLyricsFollowing, LYRICS_RESUME_FOLLOW_MS)
                        }
                    }
                }
            )

            // Leave room above the first line and below the last one, so that any line can be
            // placed at the reading position
            recyclerView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    val height = bottom - top

                    recyclerView.setPadding(
                        recyclerView.paddingLeft,
                        height / 6,
                        recyclerView.paddingRight,
                        height / 2,
                    )
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, lyricsBackPressedCallback
        )

        // Restore the lyrics mode without animations
        applyLyricsMode()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isPlaying.collectLatest { isPlaying ->
                        playPauseMaterialButton.setIconResource(
                            when (isPlaying) {
                                true -> R.drawable.ic_pause
                                false -> R.drawable.ic_play_arrow
                            }
                        )
                    }
                }

                launch {
                    viewModel.playbackState.collectLatest {
                        linearProgressIndicator.isVisible = it == PlaybackState.BUFFERING
                    }
                }

                launch {
                    viewModel.audio.collectLatest {
                        when (it) {
                            is FlowResult.Loading -> {
                                // Do nothing
                            }

                            is FlowResult.Success -> {
                                val audio = it.data

                                isFavoriteMaterialButton.isVisible = true
                                isFavoriteMaterialButton.setIconResource(
                                    when (audio.isFavorite) {
                                        true -> R.drawable.ic_heart_filled
                                        false -> R.drawable.ic_heart_unfilled
                                    }
                                )
                                isFavoriteMaterialButton.tooltipText = getString(
                                    when (audio.isFavorite) {
                                        true -> R.string.remove_from_favorites
                                        false -> R.string.add_to_favorites
                                    }
                                )
                            }

                            is FlowResult.Failure -> {
                                Log.e(
                                    LOG_TAG,
                                    "Error while loading audio, error: ${it.error}",
                                    it.throwable
                                )

                                isFavoriteMaterialButton.isVisible = false
                            }
                        }
                    }
                }

                launch {
                    viewModel.mediaMetadata.collectLatest { mediaMetadata ->
                        val audioTitle = mediaMetadata.displayTitle
                            ?: mediaMetadata.title
                            ?: getString(R.string.unknown)
                        if (audioTitleTextView.text != audioTitle) {
                            audioTitleTextView.text = audioTitle
                        }
                        compactTitleTextView?.text = audioTitle

                        val artistName = mediaMetadata.artist
                            ?: getString(R.string.artist_unknown)
                        if (artistNameTextView.text != artistName) {
                            artistNameTextView.text = artistName
                        }
                        compactArtistTextView?.text = artistName

                        val albumTitle = mediaMetadata.albumTitle
                            ?: getString(R.string.album_unknown)
                        if (albumTitleTextView.text != albumTitle) {
                            albumTitleTextView.text = albumTitle
                        }
                    }
                }

                launch {
                    viewModel.mediaArtwork.collectLatest {
                        when (it) {
                            null -> {
                                // Do nothing
                            }

                            is Result.Success -> {
                                albumArtImageView.loadThumbnail(
                                    it.data,
                                    placeholder = R.drawable.ic_music_note,
                                )
                                compactCoverImageView?.loadThumbnail(
                                    it.data,
                                    placeholder = R.drawable.ic_music_note,
                                )
                                backdropImageView.loadBackdrop(it.data)
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

                launch {
                    viewModel.playbackParameters.collectLatest {
                        playbackSpeedMaterialButton.text = getString(
                            R.string.playback_speed_format,
                            playbackSpeedFormatter.format(it.speed),
                        )
                    }
                }

                launch {
                    viewModel.displayFileType.collectLatest { displayFileType ->
                        fileTypeTextView.text = displayFileType ?: getString(
                            R.string.audio_file_type_unknown_short
                        )
                    }
                }

                launch {
                    viewModel.outputConfiguration.collectLatest { outputConfiguration ->
                        outputConfiguration?.device?.type?.also {
                            val deviceDrawableResId = when (it) {
                                OutputConfiguration.Device.Type.BUILTIN ->
                                    R.drawable.ic_mobile_speaker

                                OutputConfiguration.Device.Type.HEADPHONES ->
                                    R.drawable.ic_headphones

                                OutputConfiguration.Device.Type.EXTERNAL_SPEAKERS ->
                                    R.drawable.ic_speaker_group

                                OutputConfiguration.Device.Type.BLUETOOTH ->
                                    R.drawable.ic_bluetooth

                                OutputConfiguration.Device.Type.HDMI ->
                                    R.drawable.ic_settings_input_hdmi

                                OutputConfiguration.Device.Type.USB ->
                                    R.drawable.ic_usb

                                OutputConfiguration.Device.Type.REMOTE ->
                                    R.drawable.ic_cast

                                OutputConfiguration.Device.Type.HEARING_AID ->
                                    R.drawable.ic_hearing_aid
                            }
                            outputDeviceImageView.setImageResource(deviceDrawableResId)
                            outputDeviceImageView.isVisible = true
                        } ?: run {
                            outputDeviceImageView.isVisible = false
                        }

                        hiResImageView.isVisible = outputConfiguration?.verdict?.hiRes == true

                        potentialIssuesImageView.isVisible =
                            outputConfiguration?.verdict?.potentialIssues?.isNotEmpty() == true
                    }
                }

                launch {
                    viewModel.repeatMode.collectLatest {
                        repeatMaterialButton.setIconResource(
                            when (it) {
                                RepeatMode.NONE,
                                RepeatMode.ALL -> R.drawable.ic_repeat

                                RepeatMode.ONE -> R.drawable.ic_repeat_one
                            }
                        )
                        repeatMarkerImageView.isVisible = it != RepeatMode.NONE
                    }
                }

                launch {
                    viewModel.shuffleMode.collectLatest { shuffleModeEnabled ->
                        shuffleMarkerImageView.isVisible = shuffleModeEnabled
                    }
                }

                launch {
                    viewModel.durationCurrentPositionMs.collectLatest {
                        val durationMs = it.first ?: 0L
                        val currentPositionMs = it.second ?: 0L

                        val newValueTo = durationMs.toFloat().takeIf { it > 0 } ?: 1f
                        val newValue = currentPositionMs.toFloat()

                        progressSlider.updateValueAndRange(
                            value = newValue,
                            valueTo = newValueTo,
                            isDragging = isProgressSliderDragging,
                        )

                        currentTimestampTextView.text =
                            TimestampFormatter.formatTimestampMillis(currentPositionMs)

                        // The time left, like a real player shows it
                        durationTimestampTextView.text = when (durationMs > 0) {
                            true -> "-" + TimestampFormatter.formatTimestampMillis(
                                (durationMs - currentPositionMs).coerceAtLeast(0L)
                            )

                            false -> ""
                        }
                    }
                }

                launch {
                    viewModel.availableCommands.collectLatest {
                        shuffleMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_SHUFFLE_MODE
                        )

                        previousTrackMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SEEK_TO_PREVIOUS
                        )

                        playPauseMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_PLAY_PAUSE
                        )

                        nextTrackMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SEEK_TO_NEXT
                        )

                        repeatMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_REPEAT_MODE
                        )

                        playbackSpeedMaterialButton.isEnabled = it.contains(
                            Player.COMMAND_SET_SPEED_AND_PITCH
                        )
                    }
                }

                launch {
                    viewModel.audioSessionId.collectLatest {
                        visualizerNVDataSource.setAudioSessionId(it)
                    }
                }

                launch {
                    viewModel.isVisualizerEnabled.collectLatest { isVisualizerEnabled ->
                        visualizerSurfaceView.isVisible = isVisualizerEnabled

                        if (!isVisualizerEnabled) {
                            return@collectLatest
                        }

                        visualizerPermissionsChecker.withPermissionsGranted {
                            launch {
                                visualizerNVDataSource.workFlow.collect()
                            }

                            launch {
                                viewModel.currentVisualizerType.collectLatest {
                                    it.factory.invoke()?.let { renderers ->
                                        visualizerManager.start(visualizerSurfaceView, renderers)
                                        isVisualizerStarted = true
                                    } ?: run {
                                        if (isVisualizerStarted) {
                                            visualizerManager.stop()
                                            isVisualizerStarted = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.lyricsLines.collectLatest {
                        when (it) {
                            is FlowResult.Loading -> {
                                // Do nothing
                            }

                            is FlowResult.Success -> {
                                val (lyrics, currentIndex) = it.data

                                onLyricsChanged(
                                    hasLyrics = lyrics.isNotEmpty(),
                                    lines = lyrics.map { (line, _) -> line },
                                    currentIndex = currentIndex,
                                )
                            }

                            is FlowResult.Failure -> {
                                Log.e(
                                    LOG_TAG,
                                    "Error while loading lyrics: ${it.error}",
                                    it.throwable
                                )

                                onLyricsChanged(
                                    hasLyrics = false,
                                    lines = emptyList(),
                                    currentIndex = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isVisualizerStarted) {
            visualizerManager.resume()
        }
    }

    override fun onPause() {
        if (isVisualizerStarted) {
            visualizerManager.pause()
        }

        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(resumeLyricsFollowing)
        lyricsRecyclerView?.adapter = null

        if (isVisualizerStarted) {
            visualizerManager.stop()
        }
        isVisualizerStarted = false

        super.onDestroyView()
    }

    override fun onDestroy() {
        visualizerManager.release()

        super.onDestroy()
    }

    private fun openAudioOptions() {
        viewModel.audio.value.getOrNull()?.let {
            findNavController().navigateSafe(
                R.id.action_nowPlayingFragment_to_fragment_media_item_bottom_sheet_dialog,
                MediaItemBottomSheetDialogFragment.createBundle(
                    it.uri,
                    fromNowPlaying = true,
                )
            )
        }
    }

    private fun showLyricsScreen() {
        findNavController().navigateSafe(R.id.action_nowPlayingFragment_to_fragment_lyrics)
    }

    private fun onLyricsChanged(
        hasLyrics: Boolean,
        lines: List<org.lineageos.twelve.models.Lyrics.Line>,
        currentIndex: Int?,
    ) {
        this.hasLyrics = hasLyrics

        // Synchronized lyrics where no line started yet: nothing is in focus
        val focusIndex = currentIndex ?: when (lines.any { it.durationMs != null }) {
            true -> -1
            false -> null
        }
        lyricsCurrentIndex = focusIndex

        lyricsToggleMaterialButton.isVisible = hasLyrics
        if (!hasLyrics && isLyricsMode) {
            setLyricsMode(false, animate = true)
        }

        val index = currentIndex ?: 0
        currentLyricsTextView.text = lines.getOrNull(index)?.text
        lyricsPreviewLinearLayout?.isVisible = hasLyrics

        lyricsMaterialCardView?.isVisible = hasLyrics
        previousLyricsTextView?.text = lines.getOrNull(index - 1)?.text
        nextLyricsTextView?.text = lines.getOrNull(index + 1)?.text

        lyricsAdapter.submit(lines, focusIndex)
        centerCurrentLyricsLine(animate = true)
    }

    /**
     * Switch between the player and the lyrics.
     */
    private fun setLyricsMode(enabled: Boolean, animate: Boolean) {
        val recyclerView = lyricsRecyclerView ?: return
        val enabled = enabled && hasLyrics

        if (isLyricsMode == enabled) {
            return
        }

        val sceneRoot = topFrameLayout
        val bigCover = albumArtMaterialCardView
        val smallCover = compactCoverMaterialCardView

        if (animate && sceneRoot != null && smallCover != null) {
            // The cover flies between its two places while everything else cross fades
            TransitionManager.beginDelayedTransition(
                sceneRoot,
                TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER

                    addTransition(
                        MaterialContainerTransform().apply {
                            startView = if (enabled) bigCover else smallCover
                            endView = if (enabled) smallCover else bigCover
                            addTarget(endView!!)
                            scrimColor = Color.TRANSPARENT
                            setAllContainerColors(Color.TRANSPARENT)
                            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
                            duration = LYRICS_TRANSITION_MS
                        }
                    )

                    addTransition(
                        Fade().apply {
                            excludeTarget(bigCover, true)
                            excludeTarget(smallCover, true)
                            duration = LYRICS_TRANSITION_MS
                        }
                    )
                }
            )
        }

        isLyricsMode = enabled
        applyLyricsMode()

        if (enabled) {
            lastCenteredLyricsIndex = -1
            isLyricsFollowing = true
            recyclerView.post { centerCurrentLyricsLine(animate = false) }
        }
    }

    /**
     * Show the views of the current mode.
     */
    private fun applyLyricsMode() {
        if (lyricsRecyclerView == null) {
            return
        }

        val showLyrics = isLyricsMode && hasLyrics

        nestedScrollView.isVisible = !showLyrics
        lyricsLinearLayout?.isVisible = showLyrics
        lyricsPreviewLinearLayout?.isVisible = hasLyrics
        lyricsToggleMaterialButton.isChecked = showLyrics
        lyricsBackPressedCallback.isEnabled = showLyrics
    }

    /**
     * Scroll the line being sung to the reading position.
     */
    private fun centerCurrentLyricsLine(animate: Boolean) {
        val recyclerView = lyricsRecyclerView ?: return
        val index = lyricsCurrentIndex?.takeIf { it >= 0 } ?: return

        if (!isLyricsMode || !isLyricsFollowing || !recyclerView.isVisible) {
            return
        }

        if (animate && index == lastCenteredLyricsIndex) {
            return
        }
        lastCenteredLyricsIndex = index

        when (animate) {
            true -> CenterSmoothScroller(recyclerView.context).apply {
                targetPosition = index
            }.also { recyclerView.layoutManager?.startSmoothScroll(it) }

            false -> (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, recyclerView.paddingTop)
        }
    }

    companion object {
        private val LOG_TAG = NowPlayingFragment::class.simpleName!!

        private const val STATE_LYRICS_MODE = "lyrics_mode"

        private const val LYRICS_PREVIEW_BLUR_RADIUS = 3f
        private const val LYRICS_TRANSITION_MS = 420L
        private const val LYRICS_RESUME_FOLLOW_MS = 3500L

        private val decimalFormatSymbols = DecimalFormatSymbols(Locale.ROOT)

        private val playbackSpeedFormatter = DecimalFormat("0.#", decimalFormatSymbols)
    }
}
