/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.content.ClipboardManager
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.datasources.spotify.SpotifyClient
import org.lineageos.twelve.ext.setProgressCompat
import org.lineageos.twelve.utils.ImportedPlaylist
import org.lineageos.twelve.utils.PlaylistImport
import org.lineageos.twelve.utils.SharedPlaylist
import org.lineageos.twelve.viewmodels.TwelveViewModel

/**
 * Asks for a playlist that comes from outside (a code, a file, a Spotify link, a list of songs)
 * and adds it to the online library.
 *
 * Create it where the fragment is created, it registers for the result of the file picker.
 */
class PlaylistImporter(
    private val fragment: Fragment,
    private val viewModel: () -> TwelveViewModel,
) {
    private val openPlaylistFileLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val text = runCatching {
                fragment.requireContext().contentResolver.openInputStream(it)?.use { input ->
                    input.readBytes().decodeToString()
                }
            }.getOrNull()

            importPlaylist(text.orEmpty())
        }
    }

    /**
     * Ask for the code of a playlist, or for the file it was saved in.
     */
    fun showDialog() {
        val context = fragment.requireContext()
        val padding = (24 * fragment.resources.displayMetrics.density).toInt()

        val editText = EditText(context).apply {
            hint = fragment.getString(R.string.import_playlist_code)
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
        
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.import_playlist)
            .setMessage(R.string.import_playlist_hint_all)
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

    /**
     * Import whatever was pasted or opened: a playlist of Twelve, a Spotify link, a list of songs.
     */
    private fun importPlaylist(text: String) {
        val context = fragment.requireContext()

        // A playlist of Twelve already has the songs
        SharedPlaylist.parse(text)?.let { playlist ->
            viewModel().importOnlinePlaylist(playlist.name, playlist.songs.map { it.id })
            Toast.makeText(
                context,
                fragment.getString(R.string.import_playlist_done, playlist.name, playlist.songs.size),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val spotifyId = PlaylistImport.spotifyPlaylistId(text)

            val playlist = when {
                spotifyId != null -> {
                    when (val result = viewModel().spotifyPlaylist(spotifyId)) {
                        is SpotifyClient.Result.Success -> result.playlist

                        SpotifyClient.Result.NotAuthorized -> {
                            Toast.makeText(
                                context, R.string.import_spotify_credentials, Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }

                        SpotifyClient.Result.NotFound -> {
                            Toast.makeText(
                                context, R.string.import_spotify_not_found, Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }

                        SpotifyClient.Result.Failed -> {
                            Toast.makeText(context, R.string.import_failed, Toast.LENGTH_LONG)
                                .show()
                            return@launch
                        }
                    }
                }

                else -> PlaylistImport.parseText(
                    fragment.getString(R.string.import_playlist), text
                )
            }

            if (playlist == null) {
                Toast.makeText(context, R.string.import_playlist_invalid, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }

            matchAndImport(playlist)
        }
    }

    private suspend fun matchAndImport(playlist: ImportedPlaylist) {
        val context = fragment.requireContext()

        val progressBar = LinearProgressIndicator(context).apply {
            max = playlist.tracks.size
            isIndeterminate = false
        }
        val container = FrameLayout(context).apply {
            val padding = (24 * fragment.resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(progressBar)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(playlist.name)
            .setMessage(fragment.getString(R.string.import_matching, 0, playlist.tracks.size))
            .setView(container)
            .setCancelable(false)
            .show()

        val result = try {
            viewModel().importOnlinePlaylist(playlist) { done, total ->
                fragment.view?.post {
                    progressBar.setProgressCompat(done, true)
                    dialog.setMessage(fragment.getString(R.string.import_matching, done, total))
                }
            }
        } finally {
            dialog.dismiss()
        }

        Toast.makeText(
            context,
            when (result.uri) {
                null -> fragment.getString(R.string.import_nothing_found)
                else -> fragment.getString(
                    R.string.import_matched, playlist.name, result.matched, result.total
                )
            },
            Toast.LENGTH_LONG,
        ).show()
    }

}
