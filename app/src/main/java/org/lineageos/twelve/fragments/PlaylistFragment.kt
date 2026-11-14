/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.lineageos.twelve.utils.SharedPlaylist
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.Bundle
import org.lineageos.twelve.ext.getParcelable
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.setProgressCompat
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.Error
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.ui.dialogs.EditTextMaterialAlertDialogBuilder
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.ui.recyclerview.UniqueItemDiffCallback
import org.lineageos.twelve.ui.views.FullscreenLoadingProgressBar
import org.lineageos.twelve.ui.views.ListItem
import org.lineageos.twelve.utils.PermissionsChecker
import org.lineageos.twelve.utils.PermissionsUtils
import org.lineageos.twelve.utils.TimestampFormatter
import org.lineageos.twelve.viewmodels.PlaylistViewModel

/**
 * Single playlist viewer.
 */
class PlaylistFragment : CollapsingToolbarLayoutFragment(R.layout.fragment_playlist) {
    // View models
    private val viewModel by viewModels<PlaylistViewModel>()

    // Views
    private val addToQueueButton by getViewProperty<MaterialButton>(R.id.addToQueue)
    override val appBarLayout by getViewProperty<AppBarLayout>(R.id.appBarLayout)
    override val coordinatorLayout by getViewProperty<CoordinatorLayout>(R.id.coordinatorLayout)
    private val fullscreenLoadingProgressBar by getViewProperty<FullscreenLoadingProgressBar>(R.id.fullscreenLoadingProgressBar)
    private val infoNestedScrollView by getViewProperty<NestedScrollView?>(R.id.infoNestedScrollView)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsNestedScrollView by getViewProperty<NestedScrollView>(R.id.noElementsNestedScrollView)
    private val playAllButton by getViewProperty<FloatingActionButton>(R.id.playAllButton)
    private val playlistNameTextView by getViewProperty<TextView>(R.id.playlistNameTextView)
    private val playButtonsLinearLayout by getViewProperty<LinearLayout>(R.id.playButtonsLinearLayout)
    private val playNextButton by getViewProperty<MaterialButton>(R.id.playNextButton)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val shufflePlayButton by getViewProperty<MaterialButton>(R.id.shufflePlayButton)
    private val thumbnailImageView by getViewProperty<ImageView>(R.id.thumbnailImageView)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val tracksInfoTextView by getViewProperty<TextView>(R.id.tracksInfoTextView)

    // Songs that can be shared, null when the playlist can't leave the app
    private var sharablePlaylist: SharedPlaylist? = null

    private val savePlaylistLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val playlist = sharablePlaylist ?: return@registerForActivityResult
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(playlist.toJson().toByteArray())
                }
            }
        }
    }

    // Menu items
    private val deletePlaylistMenuItem get() = toolbar.menu.findItem(R.id.deletePlaylist)
    private val renamePlaylistMenuItem get() = toolbar.menu.findItem(R.id.renamePlaylist)
    private val sharePlaylistMenuItem get() = toolbar.menu.findItem(R.id.sharePlaylist)
    private val savePlaylistToFileMenuItem get() = toolbar.menu.findItem(R.id.savePlaylistToFile)

    // Recyclerview
    private val adapter by lazy {
        object : SimpleListAdapter<Audio, ListItem>(
            UniqueItemDiffCallback(),
            ::ListItem,
        ) {
            override fun ViewHolder.onBindView(item: Audio) {
                view.setLeadingCover(item.thumbnail, R.drawable.ic_music_note)
                view.setOnClickListener {
                    viewModel.playPlaylist(bindingAdapterPosition)
                }
                view.setOnLongClickListener {
                    findNavController().navigateSafe(
                        R.id.action_playlistFragment_to_fragment_media_item_bottom_sheet_dialog,
                        MediaItemBottomSheetDialogFragment.createBundle(
                            item.uri,
                            playlistUri = playlistUri,
                        )
                    )
                    true
                }

                view.headlineText = item.title
                item.artistName?.also {
                    view.supportingText = it
                } ?: view.setSupportingText(R.string.artist_unknown)
                view.trailingSupportingText = item.durationMs?.let {
                    TimestampFormatter.formatTimestampMillis(it)
                }
            }
        }
    }

    // Arguments
    private val playlistUri: Uri
        get() = requireArguments().getParcelable(ARG_PLAYLIST_URI, Uri::class)!!

    // Permissions
    private val permissionsChecker = PermissionsChecker(
        this, PermissionsUtils.mainPermissions
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        infoNestedScrollView?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                v.updatePadding(
                    insets,
                    bottom = true,
                )

                windowInsets
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(noElementsNestedScrollView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(playButtonsLinearLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                bottom = true,
            )

            windowInsets
        }

        toolbar.setupWithNavController(findNavController())
        toolbar.inflateMenu(R.menu.fragment_playlist_toolbar)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.renamePlaylist -> {
                    showRenamePlaylistAlertDialog()
                    true
                }

                R.id.deletePlaylist -> {
                    showDeletePlaylistAlertDialog()
                    true
                }

                R.id.sharePlaylist -> {
                    sharePlaylist()
                    true
                }

                R.id.savePlaylistToFile -> {
                    sharablePlaylist?.let { playlist ->
                        savePlaylistLauncher.launch(
                            playlist.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_") +
                                    SharedPlaylist.FILE_EXTENSION
                        )
                    }
                    true
                }

                else -> false
            }
        }

        recyclerView.adapter = adapter

        addToQueueButton.setOnClickListener {
            viewModel.addToQueue()
        }

        playAllButton.setOnClickListener {
            viewModel.playPlaylist()
        }

        playNextButton.setOnClickListener {
            viewModel.playNext()
        }

        shufflePlayButton.setOnClickListener {
            viewModel.shufflePlayPlaylist()
        }

        viewModel.loadPlaylist(playlistUri)

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

    private fun CoroutineScope.loadData() {
        launch {
            viewModel.playlist.collectLatest {
                linearProgressIndicator.setProgressCompat(it)

                when (it) {
                    is FlowResult.Loading -> {
                        // Do nothing
                    }

                    is FlowResult.Success -> {
                        val (playlist, audios) = it.data

                        val playlistName = playlist.name ?: getString(
                            when (playlist.type) {
                                Playlist.Type.PLAYLIST -> R.string.playlist_unknown
                                Playlist.Type.FAVORITES -> R.string.favorites_playlist
                            }
                        )
                        toolbar.title = playlistName
                        playlistNameTextView.text = playlistName

                        thumbnailImageView.loadThumbnail(
                            playlist.thumbnail,
                            placeholder = when (playlist.type) {
                                Playlist.Type.PLAYLIST -> R.drawable.ic_playlist_play
                                Playlist.Type.FAVORITES -> R.drawable.ic_favorite
                            }
                        )

                        val totalDurationMs = audios.sumOf { audio ->
                            audio.durationMs ?: 0L
                        }
                        val totalDurationMinutes = (totalDurationMs / 1000 / 60).toInt()

                        val tracksCount = resources.getQuantityString(
                            R.plurals.tracks_count,
                            audios.size,
                            audios.size
                        )
                        val tracksDuration = resources.getQuantityString(
                            R.plurals.tracks_duration,
                            totalDurationMinutes,
                            totalDurationMinutes
                        )
                        tracksInfoTextView.text = getString(
                            R.string.tracks_info,
                            tracksCount, tracksDuration
                        )

                        adapter.submitList(audios)

                        // Only songs of the online catalog mean something on another phone
                        val onlineSongs = audios.filter { it.uri.scheme == ONLINE_SCHEME }
                        sharablePlaylist = onlineSongs.takeIf { it.isNotEmpty() }?.let { songs ->
                            SharedPlaylist(
                                name = playlist.name ?: getString(R.string.favorites_playlist),
                                songs = songs.mapNotNull { audio ->
                                    audio.uri.lastPathSegment?.let { id ->
                                        SharedPlaylist.Song(id, audio.title, audio.artistName)
                                    }
                                },
                            )
                        }
                        sharePlaylistMenuItem.isVisible = sharablePlaylist != null
                        savePlaylistToFileMenuItem.isVisible = sharablePlaylist != null

                        val isEmpty = audios.isEmpty()
                        recyclerView.isVisible = !isEmpty
                        noElementsNestedScrollView.isVisible = isEmpty
                        when (isEmpty) {
                            true -> {
                                playButtonsLinearLayout.isVisible = false
                            }

                            false -> {
                                playButtonsLinearLayout.isVisible = true
                            }
                        }
                    }

                    is FlowResult.Failure -> {
                        Log.e(
                            LOG_TAG,
                            "Error loading playlist, error: ${it.error}",
                            it.throwable
                        )

                        toolbar.title = ""
                        playlistNameTextView.text = ""

                        adapter.submitList(listOf())

                        recyclerView.isVisible = false
                        noElementsNestedScrollView.isVisible = true
                        playButtonsLinearLayout.isVisible = false

                        if (it.error == Error.NOT_FOUND) {
                            // Get out of here
                            findNavController().navigateUp()
                        }
                    }
                }
            }
        }

        launch {
            viewModel.playlistMetadataCanBeEdited.collectLatest { playlistMetadataCanBeEdited ->
                renamePlaylistMenuItem.isVisible = playlistMetadataCanBeEdited
                deletePlaylistMenuItem.isVisible = playlistMetadataCanBeEdited
            }
        }
    }

    private fun sharePlaylist() {
        val playlist = sharablePlaylist ?: run {
            Toast.makeText(requireContext(), R.string.share_playlist_empty, Toast.LENGTH_SHORT)
                .show()
            return
        }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, playlist.name)
                    putExtra(Intent.EXTRA_TEXT, playlist.toShareText())
                },
                getString(R.string.share_playlist),
            )
        )
    }

    private fun showRenamePlaylistAlertDialog() {
        EditTextMaterialAlertDialogBuilder(requireContext())
            .setText(toolbar.title.toString())
            .setPositiveButton(R.string.rename_playlist_positive) { text ->
                viewLifecycleOwner.lifecycleScope.launch {
                    fullscreenLoadingProgressBar.withProgress {
                        viewModel.renamePlaylist(text)
                    }
                }
            }
            .setTitle(R.string.rename_playlist)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeletePlaylistAlertDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_playlist)
            .setMessage(R.string.delete_playlist_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    fullscreenLoadingProgressBar.withProgress {
                        viewModel.deletePlaylist()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private val LOG_TAG = PlaylistFragment::class.simpleName!!

        private const val ONLINE_SCHEME = "saavn"

        private const val ARG_PLAYLIST_URI = "playlist_uri"

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         * @param playlistUri The URI of the playlist to display
         */
        fun createBundle(
            playlistUri: Uri,
        ) = Bundle {
            putParcelable(ARG_PLAYLIST_URI, playlistUri)
        }
    }
}
