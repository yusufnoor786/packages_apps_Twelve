/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButtonToggleGroup
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.viewmodels.MainNavigationViewModel

/**
 * Music library.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    // Views
    private val menuToggleGroup by getViewProperty<MaterialButtonToggleGroup>(R.id.menuToggleGroup)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    // The floating navigation bar has a place for the playlists
    private val mainNavigationViewModel by activityViewModels<MainNavigationViewModel>()

    // ViewPager2
    private enum class Menus(
        @IdRes val buttonId: Int,
        val fragment: () -> Fragment,
    ) {
        ALBUMS(
            R.id.albumsButton,
            { AlbumsFragment() },
        ),
        ARTISTS(
            R.id.artistsButton,
            { ArtistsFragment() },
        ),
        GENRES(
            R.id.genresButton,
            { GenresFragment() },
        ),
        PLAYLISTS(
            R.id.playlistsButton,
            { PlaylistsFragment() },
        ),
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager2.adapter = object : FragmentStateAdapter(
            childFragmentManager, viewLifecycleOwner.lifecycle
        ) {
            override fun getItemCount() = Menus.entries.size
            override fun createFragment(position: Int) = Menus.entries[position].fragment()
        }
        viewPager2.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT

        // The buttons and the pages follow each other
        menuToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            val position = Menus.entries.indexOfFirst { it.buttonId == checkedId }
            if (isChecked && position >= 0 && position != viewPager2.currentItem) {
                viewPager2.currentItem = position
            }
        }
        viewPager2.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                menuToggleGroup.check(Menus.entries[position].buttonId)
                mainNavigationViewModel.libraryShowsPlaylists.value =
                    Menus.entries[position] == Menus.PLAYLISTS
            }
        })
        menuToggleGroup.check(Menus.entries[viewPager2.currentItem].buttonId)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainNavigationViewModel.showPlaylistsRequest.collect { playlists ->
                    playlists ?: return@collect
                    mainNavigationViewModel.showPlaylistsRequest.value = null
                    val showsPlaylists = viewPager2.currentItem == Menus.PLAYLISTS.ordinal
                    when {
                        playlists && !showsPlaylists ->
                            viewPager2.setCurrentItem(Menus.PLAYLISTS.ordinal, false)

                        !playlists && showsPlaylists ->
                            viewPager2.setCurrentItem(Menus.ALBUMS.ordinal, false)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        viewPager2.adapter = null

        super.onDestroyView()
    }
}
