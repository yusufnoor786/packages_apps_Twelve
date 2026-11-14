/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.ClipboardManager
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.utils.SharedPlaylist
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.setProgressCompat
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.SortingStrategy
import org.lineageos.twelve.ui.views.PullToRefreshLayout
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.ui.recyclerview.UniqueItemDiffCallback
import org.lineageos.twelve.ui.views.ListItem
import org.lineageos.twelve.ui.views.SortingChip
import org.lineageos.twelve.utils.PermissionsChecker
import org.lineageos.twelve.utils.PermissionsUtils
import org.lineageos.twelve.viewmodels.PlaylistsViewModel

/**
 * View all music playlists.
 */
class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {
    // View models
    private val viewModel by viewModels<PlaylistsViewModel>()

    // Views
    private val createNewPlaylistButton by getViewProperty<Button>(R.id.createNewPlaylistButton)
    private val importPlaylistChip by getViewProperty<Chip>(R.id.importPlaylistChip)

    private var importCodeEditText: EditText? = null

    private val openPlaylistFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val text = runCatching {
                requireContext().contentResolver.openInputStream(it)?.use { input ->
                    input.readBytes().decodeToString()
                }
            }.getOrNull()

            importPlaylist(text.orEmpty())
        }
    }
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val pullToRefreshLayout by getViewProperty<PullToRefreshLayout>(R.id.pullToRefreshLayout)
    private val recyclerView by getViewProperty<RecyclerView>(R.id.recyclerView)
    private val sortingChip by getViewProperty<SortingChip>(R.id.sortingChip)

    // Recyclerview
    private val addNewPlaylistItem = Playlist.Builder(Uri.EMPTY).build()
    private val adapter = object : SimpleListAdapter<Playlist, ListItem>(
        UniqueItemDiffCallback(),
        ::ListItem,
    ) {
        override fun ViewHolder.onBindView(item: Playlist) {
            when (item === addNewPlaylistItem) {
                true -> {
                    view.setOnClickListener {
                        findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_create_playlist_dialog,
                            CreatePlaylistDialogFragment.createBundle(
                                providerIdentifier = viewModel.navigationProvider.value?.identifier
                            )
                        )
                    }
                    view.setOnLongClickListener(null)

                    view.setLeadingIconImage(R.drawable.ic_playlist_add)
                    view.setHeadlineText(R.string.create_playlist)
                }

                false -> {
                    view.setOnClickListener {
                        findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_playlist,
                            PlaylistFragment.createBundle(item.uri)
                        )
                    }
                    view.setOnLongClickListener {
                        findNavController().navigateSafe(
                            R.id.action_mainFragment_to_fragment_media_item_bottom_sheet_dialog,
                            MediaItemBottomSheetDialogFragment.createBundle(item.uri)
                        )
                        true
                    }

                    view.setLeadingIconImage(
                        when (item.type) {
                            Playlist.Type.PLAYLIST -> R.drawable.ic_playlist_play
                            Playlist.Type.FAVORITES -> R.drawable.ic_favorite
                        }
                    )
                    view.headlineText = item.name ?: getString(
                        when (item.type) {
                            Playlist.Type.PLAYLIST -> R.string.playlist_unknown
                            Playlist.Type.FAVORITES -> R.string.favorites_playlist
                        }
                    )
                }
            }
        }
    }

    // Permissions
    private val permissionsChecker = PermissionsChecker(
        this, PermissionsUtils.mainPermissions
    )

    /**
     * Ask for the code of a playlist, or for the file it was saved in.
     */
    private fun showImportPlaylistDialog() {
        val context = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()

        val editText = EditText(context).apply {
            hint = getString(R.string.import_playlist_code)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START

            // A code that was just copied is what the user is here for
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val copied = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (SharedPlaylist.parse(copied) != null) {
                setText(copied)
            }
        }
        importCodeEditText = editText

        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.import_playlist)
            .setMessage(R.string.import_playlist_hint)
            .setView(container)
            .setPositiveButton(R.string.import_playlist_positive) { _, _ ->
                importPlaylist(editText.text.toString())
            }
            .setNeutralButton(R.string.import_playlist_from_file) { _, _ ->
                openPlaylistFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importPlaylist(text: String) {
        val playlist = SharedPlaylist.parse(text)
        if (playlist == null) {
            Toast.makeText(requireContext(), R.string.import_playlist_invalid, Toast.LENGTH_LONG)
                .show()
            return
        }

        viewModel.importOnlinePlaylist(playlist.name, playlist.songs.map { it.id })
        Toast.makeText(
            requireContext(),
            getString(R.string.import_playlist_done, playlist.name, playlist.songs.size),
            Toast.LENGTH_LONG,
        ).show()
    }

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

        sortingChip.setSortingStrategies(
            sortedMapOf(
                SortingStrategy.CREATION_DATE to R.string.sort_by_creation_date,
                SortingStrategy.MODIFICATION_DATE to R.string.sort_by_last_modified,
                SortingStrategy.NAME to R.string.sort_by_name,
                SortingStrategy.PLAY_COUNT to R.string.sort_by_play_count,
            )
        )
        sortingChip.setOnSortingRuleSelectedListener {
            viewModel.setSortingRule(it)
        }

        recyclerView.adapter = adapter

        pullToRefreshLayout.onRefresh = { viewModel.refreshContent() }

        importPlaylistChip.setOnClickListener {
            showImportPlaylistDialog()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Playlists that come from outside are playlists of the online catalog
                viewModel.navigationProvider.collectLatest {
                    importPlaylistChip.isVisible = it?.type == ProviderType.SAAVN
                }
            }
        }

        createNewPlaylistButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_mainFragment_to_fragment_create_playlist_dialog,
                CreatePlaylistDialogFragment.createBundle(
                    providerIdentifier = viewModel.navigationProvider.value?.identifier
                )
            )
        }

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
        coroutineScope {
            launch {
                viewModel.playlists.collectLatest {
                    linearProgressIndicator.setProgressCompat(it)

                    when (it) {
                        is FlowResult.Loading -> {
                            // Do nothing
                        }

                        is FlowResult.Success -> {
                            pullToRefreshLayout.finishRefresh()
                            val isEmpty = it.data.isEmpty()

                            adapter.submitList(
                                when (isEmpty) {
                                    true -> emptyList()
                                    false -> listOf(
                                        addNewPlaylistItem,
                                        *it.data.toTypedArray(),
                                    )
                                }
                            )

                            recyclerView.isVisible = !isEmpty
                            noElementsLinearLayout.isVisible = isEmpty
                        }

                        is FlowResult.Failure -> {
                            pullToRefreshLayout.finishRefresh()
                            Log.e(
                                LOG_TAG,
                                "Failed to load playlists, error: ${it.error}",
                                it.throwable
                            )

                            adapter.submitList(emptyList())

                            recyclerView.isVisible = false
                            noElementsLinearLayout.isVisible = true
                        }
                    }
                }
            }

            launch {
                viewModel.sortingRule.collectLatest {
                    sortingChip.setSortingRule(it)
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = PlaylistsFragment::class.simpleName!!
    }
}
