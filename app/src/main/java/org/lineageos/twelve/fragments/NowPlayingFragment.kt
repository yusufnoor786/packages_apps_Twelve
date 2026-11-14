/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
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
import androidx.navigation.Navigation
import org.lineageos.twelve.MainActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.transition.ChangeBounds
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.lineageos.twelve.ext.lyricsOffsetMs
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.bogerchan.niervisualizer.NierVisualizerManager
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.AUTOPLAY_KEY
import org.lineageos.twelve.ext.applyBackdropEffects
import org.lineageos.twelve.ext.autoplay
import org.lineageos.twelve.ext.blur
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.loadBackdrop
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.ext.updateValueAndRange
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.FlowResult.Companion.getOrNull
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.models.OutputConfiguration
import org.lineageos.twelve.models.PlaybackState
import org.lineageos.twelve.models.RepeatMode
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.ui.recyclerview.CenterSmoothScroller
import org.lineageos.twelve.ui.recyclerview.LyricsAdapter
import org.lineageos.twelve.ui.recyclerview.QueueAdapter
import org.lineageos.twelve.ui.views.SwipeToDismissLayout
import org.lineageos.twelve.ui.visualizer.VisualizerNVDataSource
import org.lineageos.twelve.utils.PermissionsChecker
import org.lineageos.twelve.utils.PermissionsUtils
import org.lineageos.twelve.utils.TimestampFormatter
import org.lineageos.twelve.viewmodels.NowPlayingViewModel
import org.lineageos.twelve.viewmodels.QueueViewModel
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Now playing fragment.
 *
 * In portrait the lyrics and the queue are shown in place: the cover moves up to a small header
 * and the list takes its room, while the seek bar and the controls stay where they are. The
 * player slides down to the mini player when it's swiped down, and the queue opens when the
 * cover is swiped up. In landscape the lyrics and the queue have screens of their own.
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing) {
    private enum class Mode {
        PLAYER,
        LYRICS,
        QUEUE,
    }

    // View models
    private val viewModel by viewModels<NowPlayingViewModel>()
    private val queueViewModel by viewModels<QueueViewModel>()

    // Views
    private val albumArtConstraintLayout by getViewProperty<ConstraintLayout?>(R.id.albumArtConstraintLayout)
    private val albumArtImageView by getViewProperty<ImageView>(R.id.albumArtImageView)
    private val albumArtMaterialCardView by getViewProperty<MaterialCardView>(R.id.albumArtMaterialCardView)
    private val albumTitleTextView by getViewProperty<TextView>(R.id.albumTitleTextView)
    private val audioTitleTextView by getViewProperty<TextView>(R.id.audioTitleTextView)
    private val backdropImageView by getViewProperty<ImageView>(R.id.backdropImageView)
    private val artistNameTextView by getViewProperty<TextView>(R.id.artistNameTextView)
    private val currentLyricsTextView by getViewProperty<TextView>(R.id.currentLyricsTextView)
    private val currentTimestampTextView by getViewProperty<TextView>(R.id.currentTimestampTextView)
    private val durationTimestampTextView by getViewProperty<TextView>(R.id.durationTimestampTextView)
    private val fileTypeTextView by getViewProperty<TextView>(R.id.fileTypeTextView)
    private val hiResImageView by getViewProperty<ImageView>(R.id.hiResImageView)
    private val isFavoriteMaterialButton by getViewProperty<MaterialButton>(R.id.isFavoriteMaterialButton)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val lyricsToggleMaterialButton by getViewProperty<MaterialButton>(R.id.lyricsToggleMaterialButton)
    private val nestedScrollView by getViewProperty<NestedScrollView>(R.id.nestedScrollView)
    private val nextTrackMaterialButton by getViewProperty<MaterialButton>(R.id.nextTrackMaterialButton)
    private val outputDeviceImageView by getViewProperty<ImageView>(R.id.outputDeviceImageView)
    private val playPauseMaterialButton by getViewProperty<MaterialButton>(R.id.playPauseMaterialButton)
    private val potentialIssuesImageView by getViewProperty<ImageView>(R.id.potentialIssuesImageView)
    private val previousTrackMaterialButton by getViewProperty<MaterialButton>(R.id.previousTrackMaterialButton)
    private val progressSlider by getViewProperty<Slider>(R.id.progressSlider)
    private val queueMaterialButton by getViewProperty<MaterialButton>(R.id.queueMaterialButton)
    private val repeatMarkerImageView by getViewProperty<ImageView>(R.id.repeatMarkerImageView)
    private val repeatMaterialButton by getViewProperty<MaterialButton>(R.id.repeatMaterialButton)
    private val shuffleMarkerImageView by getViewProperty<ImageView>(R.id.shuffleMarkerImageView)
    private val shuffleMaterialButton by getViewProperty<MaterialButton>(R.id.shuffleMaterialButton)
    private val statsMaterialCardView by getViewProperty<MaterialCardView>(R.id.statsMaterialCardView)
    private val swipeToDismissLayout by getViewProperty<SwipeToDismissLayout>(R.id.swipeToDismissLayout)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val visualizerSurfaceView by getViewProperty<SurfaceView>(R.id.visualizerSurfaceView)

    // Portrait only views
    private val autoplayChip by getViewProperty<Chip?>(R.id.autoplayChip)
    private val compactCoverImageView by getViewProperty<ImageView?>(R.id.compactCoverImageView)
    private val compactCoverMaterialCardView by getViewProperty<MaterialCardView?>(R.id.compactCoverMaterialCardView)
    private val compactArtistTextView by getViewProperty<TextView?>(R.id.compactArtistTextView)
    private val compactHeaderLinearLayout by getViewProperty<LinearLayout?>(R.id.compactHeaderLinearLayout)
    private val compactOptionsMaterialButton by getViewProperty<MaterialButton?>(R.id.compactOptionsMaterialButton)
    private val compactTitleTextView by getViewProperty<TextView?>(R.id.compactTitleTextView)
    private val lyricsPreviewLinearLayout by getViewProperty<LinearLayout?>(R.id.lyricsPreviewLinearLayout)
    private val lyricsRecyclerView by getViewProperty<RecyclerView?>(R.id.lyricsRecyclerView)
    private val modeLinearLayout by getViewProperty<LinearLayout?>(R.id.modeLinearLayout)
    private val optionsMaterialButton by getViewProperty<MaterialButton?>(R.id.optionsMaterialButton)
    private val playerRootLinearLayout by getViewProperty<LinearLayout?>(R.id.playerRootLinearLayout)
    private val controlsLinearLayout by getViewProperty<LinearLayout?>(R.id.controlsLinearLayout)
    private val queueLinearLayout by getViewProperty<LinearLayout?>(R.id.queueLinearLayout)
    private val queueRecyclerView by getViewProperty<RecyclerView?>(R.id.queueRecyclerView)
    private val topFrameLayout by getViewProperty<FrameLayout?>(R.id.topFrameLayout)

    // Landscape only views
    private val lyricsMaterialCardView by getViewProperty<MaterialCardView?>(R.id.lyricsMaterialCardView)
    private val nextLyricsTextView by getViewProperty<TextView?>(R.id.nextLyricsTextView)
    private val previousLyricsTextView by getViewProperty<TextView?>(R.id.previousLyricsTextView)
    private val showLyricsMaterialButton by getViewProperty<MaterialButton?>(R.id.showLyricsMaterialButton)

    // Progress slider state
    private var isProgressSliderDragging = false

    // Modes
    private var mode = Mode.PLAYER
    private var hasLyrics = false

    // Lyrics
    private val lyricsAdapter by lazy {
        LyricsAdapter { line ->
            when (controlsHidden) {
                true -> setControlsHidden(false)
                false -> line.durationMs?.let { viewModel.seekToPosition(it.first) }
            }
        }
    }
    private var lyricsCurrentIndex: Int? = null

    // The controls leave while lyrics are read and playback runs, and return on a tap
    private var controlsHidden = false
    private var isPlayingNow = false
    private val hideControls = Runnable { setControlsHidden(true) }

    private fun setControlsHidden(hidden: Boolean, animate: Boolean = true) {
        val controls = controlsLinearLayout ?: return
        val root = playerRootLinearLayout ?: return

        handler.removeCallbacks(hideControls)

        val target = hidden && mode == Mode.LYRICS && isPlayingNow
        if (target == controlsHidden) {
            if (!target) {
                scheduleHideControls()
            }
            return
        }

        controlsHidden = target

        if (animate) {
            TransitionManager.beginDelayedTransition(
                root,
                TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds())
                    addTransition(Fade())
                    duration = CONTROLS_TRANSITION_MS
                }
            )
        }
        controls.isVisible = !target

        if (!target) {
            scheduleHideControls()
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControls)
        if (mode == Mode.LYRICS && isPlayingNow) {
            handler.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS)
        }
    }

    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    // The lyrics follow the player every frame, a few updates per second are not enough
    private var isLyricsFrameLoopRunning = false
    private var lyricsLines: List<Lyrics.Line> = emptyList()

    // Start times of the lines that carry the focus (background vocals don't)
    private var lyricsFocusStarts = LongArray(0)
    private var lyricsFocusIndices = IntArray(0)

    private val lyricsFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isLyricsFrameLoopRunning) {
                return
            }

            viewModel.currentPositionMs()?.let { playerPositionMs ->
                val positionMs = playerPositionMs + sharedPreferences.lyricsOffsetMs
                lyricsAdapter.setPosition(positionMs)

                val index = focusedLineAt(positionMs + LYRICS_FOCUS_LEAD_MS)
                if (index != lyricsCurrentIndex) {
                    lyricsCurrentIndex = index
                    lyricsAdapter.submit(lyricsLines, index)
                    centerCurrentLyricsLine(animate = true)
                }
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * The line that is being sung or was sung last, -1 before the first one, null if the lyrics
     * aren't synchronized.
     */
    private fun focusedLineAt(positionMs: Long): Int? {
        if (lyricsFocusStarts.isEmpty()) {
            return null
        }

        var low = 0
        var high = lyricsFocusStarts.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when (lyricsFocusStarts[middle] <= positionMs) {
                true -> {
                    found = middle
                    low = middle + 1
                }

                false -> high = middle - 1
            }
        }

        return when (found) {
            -1 -> -1
            else -> lyricsFocusIndices[found]
        }
    }

    private fun updateLyricsFrameLoop() {
        val shouldRun = mode == Mode.LYRICS && hasLyrics && isResumed
        if (shouldRun == isLyricsFrameLoopRunning) {
            return
        }

        isLyricsFrameLoopRunning = shouldRun
        when (shouldRun) {
            true -> Choreographer.getInstance().postFrameCallback(lyricsFrameCallback)
            false -> Choreographer.getInstance().removeFrameCallback(lyricsFrameCallback)
        }
    }

    private var lastCenteredLyricsIndex = -1
    private var isLyricsFollowing = true
    private val handler = Handler(Looper.getMainLooper())
    private val resumeLyricsFollowing = Runnable {
        isLyricsFollowing = true
        centerCurrentLyricsLine(animate = true)
    }

    // Queue
    private val queueAdapter: QueueAdapter by lazy {
        QueueAdapter(
            onPlay = { queueViewModel.playItem(it) },
            onRemove = { queueViewModel.removeItem(it) },
            onStartDrag = { queueItemTouchHelper.startDrag(it) },
        )
    }
    private val queueItemTouchHelper: ItemTouchHelper by lazy {
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0,
            ) {
                override fun isLongPressDragEnabled() = false

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition

                    queueAdapter.moveItem(from, to)
                    queueViewModel.moveItem(from, to)

                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // Not supported, items are removed with their button
                }
            }
        )
    }
    private var queueScrolled = false

    private val modeBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setMode(Mode.PLAYER, animate = true)
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

        mode = savedInstanceState?.getString(STATE_MODE)
            ?.let { name -> Mode.entries.firstOrNull { it.name == name } }
            ?: Mode.PLAYER
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(STATE_MODE, mode.name)
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

            // The small header sits below the toolbar
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
            closePlayer()
        }

        statsMaterialCardView.setOnClickListener {
            navigateMain(R.id.fragment_now_playing_stats_bottom_sheet_dialog)
        }

        // Swipe down to minimize the player
        swipeToDismissLayout.canStartDrag = ::canStartDismissDrag
        swipeToDismissLayout.onDismissed = {
            // Already out of the screen
            (requireActivity() as MainActivity).closeNowPlaying(animated = false)
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

        // Options, the extra actions of the player live there
        optionsMaterialButton?.setOnClickListener {
            openAudioOptions()
        }

        compactOptionsMaterialButton?.setOnClickListener {
            openAudioOptions()
        }

        parentFragmentManager.setFragmentResultListener(
            MediaItemBottomSheetDialogFragment.RESULT_PLAYER_ACTION,
            viewLifecycleOwner,
        ) { _, result ->
            when (result.getString(MediaItemBottomSheetDialogFragment.RESULT_KEY_ACTION)) {
                MediaItemBottomSheetDialogFragment.ACTION_PLAYBACK_SPEED ->
                    navigateMain(R.id.fragment_playback_control_bottom_sheet_dialog)

                MediaItemBottomSheetDialogFragment.ACTION_LYRICS_TIMING -> showLyricsTimingDialog()

                MediaItemBottomSheetDialogFragment.ACTION_EQUALIZER -> openEqualizer()

                MediaItemBottomSheetDialogFragment.ACTION_VISUALIZER ->
                    viewModel.nextVisualizerType()
            }
        }

        // Bottom bar buttons
        queueMaterialButton.setOnClickListener {
            when (queueRecyclerView) {
                null -> navigateMain(R.id.fragment_queue)

                else -> setMode(
                    when (mode) {
                        Mode.QUEUE -> Mode.PLAYER
                        else -> Mode.QUEUE
                    },
                    animate = true,
                )
            }
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
                else -> setMode(
                    when (mode) {
                        Mode.LYRICS -> Mode.PLAYER
                        else -> Mode.LYRICS
                    },
                    animate = true,
                )
            }
        }

        lyricsPreviewLinearLayout?.setOnClickListener {
            setMode(Mode.LYRICS, animate = true)
        }

        showLyricsMaterialButton?.setOnClickListener {
            showLyricsScreen()
        }

        // Tapping the small cover goes back to the player
        compactCoverMaterialCardView?.setOnClickListener {
            setMode(Mode.PLAYER, animate = true)
        }

        // Swiping the cover up opens the queue
        setupCoverSwipe()

        modeLinearLayout?.setOnClickListener {
            if (controlsHidden) {
                setControlsHidden(false)
            }
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

        queueRecyclerView?.let { recyclerView ->
            recyclerView.adapter = queueAdapter
            queueItemTouchHelper.attachToRecyclerView(recyclerView)
        }

        autoplayChip?.let { chip ->
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(
                requireContext()
            )

            chip.isChecked = sharedPreferences.autoplay
            chip.setOnCheckedChangeListener { _, isChecked ->
                sharedPreferences.edit {
                    putBoolean(AUTOPLAY_KEY, isChecked)
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, modeBackPressedCallback
        )

        // Restore the mode without animations
        applyMode()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isPlaying.collectLatest { isPlaying ->
                        isPlayingNow = isPlaying
                        // Paused: the controls come back and stay
                        when (isPlaying) {
                            false -> setControlsHidden(false)
                            true -> scheduleHideControls()
                        }
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

                launch {
                    queueViewModel.queue.collectLatest { queue ->
                        queueAdapter.submitQueue(queue) {
                            val current = queue.indexOfFirst { item -> item.isCurrent }
                            if (current >= 0 && !queueScrolled && mode == Mode.QUEUE) {
                                queueRecyclerView?.scrollToPosition(current)
                                queueScrolled = true
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateLyricsFrameLoop()
        if (isVisualizerStarted) {
            visualizerManager.resume()
        }
    }

    override fun onPause() {
        isLyricsFrameLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(lyricsFrameCallback)

        if (isVisualizerStarted) {
            visualizerManager.pause()
        }

        super.onPause()
    }

    override fun onDestroyView() {
        isLyricsFrameLoopRunning = false
        Choreographer.getInstance().removeFrameCallback(lyricsFrameCallback)
        handler.removeCallbacks(resumeLyricsFollowing)
        handler.removeCallbacks(hideControls)
        lyricsRecyclerView?.adapter = null
        queueItemTouchHelper.attachToRecyclerView(null)
        queueRecyclerView?.adapter = null

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
            navigateMain(
                R.id.fragment_media_item_bottom_sheet_dialog,
                MediaItemBottomSheetDialogFragment.createBundle(
                    it.uri,
                    fromNowPlaying = true,
                )
            )
        }
    }

    private fun openEqualizer() {
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

    private fun showLyricsScreen() {
        navigateMain(R.id.fragment_lyrics)
    }

    /**
     * Lets the user nudge the lyrics when they don't match the audio.
     */
    private fun showLyricsTimingDialog() {
        val context = requireContext()
        val slider = Slider(context).apply {
            valueFrom = LYRICS_OFFSET_MIN_MS
            valueTo = LYRICS_OFFSET_MAX_MS
            stepSize = LYRICS_OFFSET_STEP_MS
            value = sharedPreferences.lyricsOffsetMs.toFloat()
                .coerceIn(LYRICS_OFFSET_MIN_MS, LYRICS_OFFSET_MAX_MS)
            setLabelFormatter { getString(R.string.lyrics_timing_format, it.toInt()) }
        }

        // Applied while the slider moves, to see the effect
        slider.addOnChangeListener { _, value, _ ->
            sharedPreferences.lyricsOffsetMs = value.toInt()
        }

        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(slider)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lyrics_timing)
            .setMessage(R.string.lyrics_timing_hint)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun closePlayer() {
        (requireActivity() as MainActivity).closeNowPlaying()
    }

    /**
     * The player is an overlay of the activity, what it opens belongs to the main navigation.
     */
    private fun navigateMain(destinationId: Int, args: Bundle? = null) {
        runCatching {
            Navigation.findNavController(requireActivity(), R.id.navHostFragment)
                .navigate(destinationId, args)
        }.onFailure { Log.e(LOG_TAG, "Cannot open destination", it) }
    }

    /**
     * Whether a drag that starts with this touch is allowed to minimize the player. Lists keep
     * their own vertical gestures.
     */
    private fun canStartDismissDrag(event: MotionEvent): Boolean {
        fun View.isTouched() = isVisible && Rect().let {
            getGlobalVisibleRect(it) && it.contains(event.rawX.toInt(), event.rawY.toInt())
        }

        return when {
            // Items are reordered by dragging them
            queueRecyclerView?.isTouched() == true -> false
            lyricsRecyclerView?.isTouched() == true ->
                lyricsRecyclerView?.canScrollVertically(-1) != true

            nestedScrollView.isTouched() -> !nestedScrollView.canScrollVertically(-1)
            else -> true
        }
    }

    /**
     * Swiping the cover up shows the queue in its place. The scroll view around the cover steals
     * the gesture as soon as it moves, so the whole gesture is tracked in screen coordinates from
     * both views.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupCoverSwipe() {
        if (queueRecyclerView == null) {
            return
        }

        var tracking = false
        var startX = 0f
        var startY = 0f
        var velocityTracker: VelocityTracker? = null

        fun finish() {
            tracking = false
            velocityTracker?.recycle()
            velocityTracker = null
        }

        fun onTouch(event: MotionEvent, fromCover: Boolean): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    finish()
                    if (fromCover) {
                        tracking = true
                        startX = event.rawX
                        startY = event.rawY
                        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    }
                }

                MotionEvent.ACTION_MOVE -> if (tracking) {
                    velocityTracker?.addMovement(event)
                }

                MotionEvent.ACTION_UP -> if (tracking) {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val dy = startY - event.rawY
                    val dx = startX - event.rawX
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    finish()

                    if (dy > COVER_SWIPE_DISTANCE_PX && dy > abs(dx) &&
                        velocityY < -COVER_SWIPE_VELOCITY
                    ) {
                        setMode(Mode.QUEUE, animate = true)
                    }
                }

                MotionEvent.ACTION_CANCEL -> if (!fromCover) {
                    finish()
                }
            }

            return fromCover || tracking
        }

        albumArtMaterialCardView.setOnTouchListener { _, event -> onTouch(event, true) }
        nestedScrollView.setOnTouchListener { _, event -> onTouch(event, false) }
    }

    private fun onLyricsChanged(
        hasLyrics: Boolean,
        lines: List<Lyrics.Line>,
        currentIndex: Int?,
    ) {
        this.hasLyrics = hasLyrics

        if (lines != lyricsLines) {
            lyricsLines = lines
            val focusLines = lines.withIndex().filter { (_, line) ->
                !line.isBackground && line.durationMs != null
            }
            lyricsFocusStarts = LongArray(focusLines.size) { focusLines[it].value.durationMs!!.first }
            lyricsFocusIndices = IntArray(focusLines.size) { focusLines[it].index }
        } else if (isLyricsFrameLoopRunning) {
            // The frame loop knows better
            return
        }

        // Synchronized lyrics where no line started yet: nothing is in focus
        val focusIndex = currentIndex ?: when (lines.any { it.durationMs != null }) {
            true -> -1
            false -> null
        }
        lyricsCurrentIndex = focusIndex

        lyricsToggleMaterialButton.isVisible = hasLyrics
        if (!hasLyrics && mode == Mode.LYRICS) {
            setMode(Mode.PLAYER, animate = true)
        }

        val index = currentIndex ?: 0
        // Instrumental gaps are lines without text, show the next line that has some
        currentLyricsTextView.text = lines.drop(index.coerceAtLeast(0))
            .firstOrNull { it.text.isNotBlank() }?.text
            ?: lines.getOrNull(index)?.text
        lyricsPreviewLinearLayout?.isVisible = hasLyrics

        lyricsMaterialCardView?.isVisible = hasLyrics
        previousLyricsTextView?.text = lines.getOrNull(index - 1)?.text
        nextLyricsTextView?.text = lines.getOrNull(index + 1)?.text

        lyricsAdapter.submit(lines, focusIndex)
        updateLyricsFrameLoop()
        centerCurrentLyricsLine(animate = true)
    }

    /**
     * Switch between the player, the lyrics and the queue.
     */
    private fun setMode(newMode: Mode, animate: Boolean) {
        val sceneRoot = topFrameLayout ?: return
        val smallCover = compactCoverMaterialCardView ?: return
        val bigCover = albumArtMaterialCardView

        val target = when {
            newMode == Mode.LYRICS && !hasLyrics -> Mode.PLAYER
            else -> newMode
        }

        if (mode == target) {
            return
        }

        val wasPlayer = mode == Mode.PLAYER
        val isPlayer = target == Mode.PLAYER

        if (animate) {
            TransitionManager.beginDelayedTransition(
                sceneRoot,
                TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER

                    // The cover flies between its two places while everything else cross fades
                    if (wasPlayer != isPlayer) {
                        addTransition(
                            MaterialContainerTransform().apply {
                                startView = if (isPlayer) smallCover else bigCover
                                endView = if (isPlayer) bigCover else smallCover
                                addTarget(endView!!)
                                scrimColor = Color.TRANSPARENT
                                setAllContainerColors(Color.TRANSPARENT)
                                fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
                                duration = MODE_TRANSITION_MS
                            }
                        )
                    }

                    addTransition(
                        Fade().apply {
                            excludeTarget(bigCover, true)
                            excludeTarget(smallCover, true)
                            duration = MODE_TRANSITION_MS
                        }
                    )
                }
            )
        }

        mode = target
        applyMode()

        if (target == Mode.LYRICS) {
            lastCenteredLyricsIndex = -1
            isLyricsFollowing = true
            lyricsRecyclerView?.post { centerCurrentLyricsLine(animate = false) }
        }

        if (target == Mode.QUEUE) {
            queueViewModel.queue.value.indexOfFirst { it.isCurrent }.takeIf { it >= 0 }?.let {
                queueRecyclerView?.post { queueRecyclerView?.scrollToPosition(it) }
            }
            queueScrolled = true
        }
    }

    /**
     * Show the views of the current mode.
     */
    private fun applyMode() {
        if (modeLinearLayout == null) {
            return
        }

        val mode = when {
            mode == Mode.LYRICS && !hasLyrics -> Mode.PLAYER
            else -> mode
        }

        // The controls are only ever away while lyrics are shown
        if (mode != Mode.LYRICS) {
            handler.removeCallbacks(hideControls)
            controlsHidden = false
            controlsLinearLayout?.isVisible = true
        } else {
            scheduleHideControls()
        }

        nestedScrollView.isVisible = mode == Mode.PLAYER
        modeLinearLayout?.isVisible = mode != Mode.PLAYER
        lyricsRecyclerView?.isVisible = mode == Mode.LYRICS
        queueLinearLayout?.isVisible = mode == Mode.QUEUE
        lyricsPreviewLinearLayout?.isVisible = hasLyrics
        lyricsToggleMaterialButton.isChecked = mode == Mode.LYRICS
        queueMaterialButton.isChecked = mode == Mode.QUEUE
        modeBackPressedCallback.isEnabled = mode != Mode.PLAYER
        updateLyricsFrameLoop()
    }

    /**
     * Scroll the line being sung to the reading position.
     */
    private fun centerCurrentLyricsLine(animate: Boolean) {
        val recyclerView = lyricsRecyclerView ?: return
        val index = lyricsCurrentIndex?.takeIf { it >= 0 } ?: return

        if (mode != Mode.LYRICS || !isLyricsFollowing || !recyclerView.isVisible) {
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

            false -> (recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, recyclerView.paddingTop)
        }
    }

    companion object {
        private val LOG_TAG = NowPlayingFragment::class.simpleName!!

        private const val STATE_MODE = "mode"

        // A line takes the focus a moment before it is sung, its animation is done when it starts
        private const val LYRICS_FOCUS_LEAD_MS = 120L
        private const val CONTROLS_HIDE_DELAY_MS = 3500L
        private const val CONTROLS_TRANSITION_MS = 320L

        private const val LYRICS_OFFSET_MIN_MS = -2000f
        private const val LYRICS_OFFSET_MAX_MS = 2000f
        private const val LYRICS_OFFSET_STEP_MS = 50f

        private const val LYRICS_PREVIEW_BLUR_RADIUS = 3f
        private const val MODE_TRANSITION_MS = 420L
        private const val LYRICS_RESUME_FOLLOW_MS = 3500L
        private const val COVER_SWIPE_DISTANCE_PX = 120f
        private const val COVER_SWIPE_VELOCITY = 500f
    }
}
