/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.applyBackdropEffects
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.loadBackdrop
import org.lineageos.twelve.ext.updateMargin
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.Lyrics
import org.lineageos.twelve.models.Result
import org.lineageos.twelve.ui.recyclerview.CenterSmoothScroller
import org.lineageos.twelve.ui.recyclerview.LyricsAdapter
import org.lineageos.twelve.viewmodels.LyricsViewModel
import org.lineageos.twelve.viewmodels.NowPlayingViewModel

typealias LineToState = Pair<Lyrics.Line, NowPlayingViewModel.LyricsLineState>

/**
 * Show lyrics of currently playing audio.
 */
class LyricsFragment : Fragment(R.layout.fragment_lyrics) {
    // View models
    private val viewModel by viewModels<LyricsViewModel>()

    // Views
    private val backdropImageView by getViewProperty<ImageView>(R.id.backdropImageView)
    private val followCurrentLineExtendedFloatingActionButton by getViewProperty<ExtendedFloatingActionButton>(
        R.id.followCurrentLineExtendedFloatingActionButton
    )
    private val noElementsNestedScrollView by getViewProperty<NestedScrollView>(R.id.noElementsNestedScrollView)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)

    // RecyclerView
    private val adapter by lazy {
        LyricsAdapter { line -> viewModel.seekToLine(line) }
    }
    private val scrollListener by lazy {
        object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                val isScrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING
                        || newState == RecyclerView.SCROLL_STATE_SETTLING

                if (isScrolling && recyclerView.layoutManager?.isSmoothScrolling == false) {
                    viewModel.setPositionSynced(false)
                }
            }
        }
    }

    private var systemBarsBottomInset = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backdropImageView.applyBackdropEffects()

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

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { _, windowInsets ->
            systemBarsBottomInset = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            ).bottom

            updateRecyclerViewPadding()

            windowInsets
        }

        // Leave room above the first line and below the last one so that any line can be centered
        recyclerView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                updateRecyclerViewPadding()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(noElementsNestedScrollView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            followCurrentLineExtendedFloatingActionButton
        ) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updateMargin(
                insets,
                bottom = true,
            )

            windowInsets
        }

        toolbar.setupWithNavController(findNavController())

        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(scrollListener)

        followCurrentLineExtendedFloatingActionButton.setOnClickListener {
            viewModel.setPositionSynced(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private fun updateRecyclerViewPadding() {
        val height = recyclerView.height.takeIf { it > 0 } ?: return

        val top = height / 3
        val bottom = height / 2 + systemBarsBottomInset

        if (recyclerView.paddingTop != top || recyclerView.paddingBottom != bottom) {
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                top,
                recyclerView.paddingRight,
                bottom,
            )
        }
    }

    private fun CoroutineScope.loadData() {
        launch {
            viewModel.mediaArtwork.collectLatest {
                if (it is Result.Success) {
                    backdropImageView.loadBackdrop(it.data)
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
                        val (lyricsWithState, currentIndex) = it.data

                        adapter.submit(lyricsWithState.map { (line, _) -> line }, currentIndex)

                        currentIndex.takeIf { viewModel.positionSynced.value }?.let { index ->
                            CenterSmoothScroller(recyclerView.context).apply {
                                targetPosition = index
                            }.also { smoothScroller ->
                                recyclerView.layoutManager?.startSmoothScroll(smoothScroller)
                            }
                        }

                        val isEmpty = lyricsWithState.isEmpty()

                        recyclerView.isVisible = !isEmpty
                        noElementsNestedScrollView.isVisible = isEmpty
                    }

                    is FlowResult.Failure -> {
                        Log.e(
                            LOG_TAG,
                            "Error while loading lyrics, error: ${it.error}",
                            it.throwable
                        )

                        adapter.submit(emptyList(), null)

                        recyclerView.isVisible = false
                        noElementsNestedScrollView.isVisible = true
                    }
                }
            }
        }

        launch {
            viewModel.positionSynced.collectLatest {
                followCurrentLineExtendedFloatingActionButton.isVisible = !it
            }
        }
    }

    companion object {
        private val LOG_TAG = LyricsFragment::class.simpleName!!
    }
}
