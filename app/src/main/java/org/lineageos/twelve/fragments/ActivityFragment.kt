/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.setProgressCompat
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.ActivityTab
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Artist
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.Genre
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.ui.recyclerview.UniqueItemDiffCallback
import org.lineageos.twelve.ui.views.ActivityTabView
import org.lineageos.twelve.ui.views.PullToRefreshLayout
import org.lineageos.twelve.utils.PermissionsChecker
import org.lineageos.twelve.utils.TimestampFormatter
import org.lineageos.twelve.ui.views.ListItem
import org.lineageos.twelve.utils.PermissionsUtils
import org.lineageos.twelve.viewmodels.ActivityViewModel

/**
 * User activity, notifications and recommendations.
 */
class ActivityFragment : Fragment(R.layout.fragment_activity) {
    // View models
    private val viewModel by viewModels<ActivityViewModel>()

    // Views
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val pullToRefreshLayout by getViewProperty<PullToRefreshLayout>(R.id.pullToRefreshLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)

    // RecyclerView
    private val adapter by lazy {
        object : SimpleListAdapter<ActivityTab, ActivityTabView>(
            UniqueItemDiffCallback(),
            ::ActivityTabView,
        ) {
            override fun ViewHolder.onBindView(item: ActivityTab) {
                view.setOnItemClickListener { mediaItem ->
                    when (mediaItem) {
                        is Album -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_album,
                            AlbumFragment.createBundle(mediaItem.uri)
                        )

                        is Artist -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_artist,
                            ArtistFragment.createBundle(mediaItem.uri)
                        )

                        is Audio -> viewModel.playAudio(listOf(mediaItem), 0)

                        is Genre -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_genre,
                            GenreFragment.createBundle(mediaItem.uri)
                        )

                        is Playlist -> findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_playlist,
                            PlaylistFragment.createBundle(mediaItem.uri)
                        )
                    }
                }
                view.setOnItemLongClickListener { mediaItem ->
                    findNavController().navigateSafe(
                        R.id.action_mainFragment_to_fragment_media_item_bottom_sheet_dialog,
                        MediaItemBottomSheetDialogFragment.createBundle(mediaItem.uri)
                    )
                    true
                }

                view.setActivityTab(
                    item,
                    when (bindingAdapterPosition) {
                        0 -> ActivityTabView.Style.HERO
                        else -> ActivityTabView.Style.entries[
                            1 + (bindingAdapterPosition - 1) % (ActivityTabView.Style.entries.size - 1)
                        ]
                    },
                )
            }
        }
    }

    // The songs of the device are a plain list
    private val songsAdapter by lazy {
        object : SimpleListAdapter<Audio, ListItem>(
            UniqueItemDiffCallback(),
            ::ListItem,
        ) {
            override val groupedRows = true

            override fun ViewHolder.onBindView(item: Audio) {
                view.setGroupPosition(bindingAdapterPosition, itemCount)
                view.setLeadingCover(item.thumbnail, R.drawable.ic_music_note)
                view.headlineText = item.title
                item.artistName?.also {
                    view.supportingText = it
                } ?: view.setSupportingText(R.string.artist_unknown)
                view.trailingSupportingText = item.durationMs?.let {
                    TimestampFormatter.formatTimestampMillis(it)
                }

                view.setOnClickListener {
                    viewModel.playAudio(currentList, bindingAdapterPosition)
                }
                view.setOnLongClickListener {
                    findNavController().navigateSafe(
                        R.id.action_mainFragment_to_fragment_media_item_bottom_sheet_dialog,
                        MediaItemBottomSheetDialogFragment.createBundle(item.uri)
                    )
                    true
                }
            }
        }
    }

    // Permissions
    private val permissionsChecker = PermissionsChecker(
        this, PermissionsUtils.mainPermissions
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            v.updatePadding(
                insets,
                start = true,
                end = true,
                bottom = true,
            )

            windowInsets
        }

        recyclerView.setItemViewCacheSize(SECTION_CACHE_SIZE)
        recyclerView.adapter = adapter

        pullToRefreshLayout.onRefresh = { viewModel.refreshContent() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsChecker.withPermissionsGranted {
                    loadData()
                }
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private suspend fun loadData() {
        viewModel.activity.collectLatest {
            linearProgressIndicator.setProgressCompat(it)

            when (it) {
                is FlowResult.Loading -> {
                    // Do nothing
                }

                is FlowResult.Success -> {
                    pullToRefreshLayout.finishRefresh()

                    val data = it.data

                    val songs = data.singleOrNull { it.id == ActivityTab.LOCAL_SONGS_ID }
                    when (songs) {
                        null -> {
                            if (recyclerView.adapter !== adapter) {
                                recyclerView.adapter = adapter
                            }
                            adapter.submitList(data)
                        }

                        else -> {
                            if (recyclerView.adapter !== songsAdapter) {
                                recyclerView.adapter = songsAdapter
                            }
                            songsAdapter.submitList(songs.items.filterIsInstance<Audio>())
                        }
                    }

                    val isEmpty = it.data.isEmpty()
                    recyclerView.isVisible = !isEmpty
                    noElementsLinearLayout.isVisible = isEmpty
                }

                is FlowResult.Failure -> {
                    pullToRefreshLayout.finishRefresh()

                    Log.e(LOG_TAG, "Failed to load activity, error: ${it.error}", it.throwable)

                    recyclerView.isVisible = false
                    noElementsLinearLayout.isVisible = true
                }
            }
        }
    }

    companion object {
        private const val SECTION_CACHE_SIZE = 12

        private val LOG_TAG = ActivityFragment::class.simpleName!!

    }
}
