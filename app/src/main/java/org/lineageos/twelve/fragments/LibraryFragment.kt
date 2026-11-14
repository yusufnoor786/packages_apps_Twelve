/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.ChipGroup
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.getViewProperty

/**
 * Music library.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    // Views
    private val chipGroup by getViewProperty<ChipGroup>(R.id.chipGroup)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    // ViewPager2
    private enum class Menus(
        @IdRes val chipId: Int,
        val fragment: () -> Fragment,
    ) {
        ALBUMS(
            R.id.albumsChip,
            { AlbumsFragment() },
        ),
        ARTISTS(
            R.id.artistsChip,
            { ArtistsFragment() },
        ),
        GENRES(
            R.id.genresChip,
            { GenresFragment() },
        ),
        PLAYLISTS(
            R.id.playlistsChip,
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
        viewPager2.offscreenPageLimit = 1

        // The chips and the pages follow each other
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val position = Menus.entries.indexOfFirst { it.chipId in checkedIds }
            if (position >= 0 && position != viewPager2.currentItem) {
                viewPager2.currentItem = position
            }
        }
        viewPager2.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                chipGroup.check(Menus.entries[position].chipId)
            }
        })
        chipGroup.check(Menus.entries[viewPager2.currentItem].chipId)
    }

    override fun onDestroyView() {
        viewPager2.adapter = null

        super.onDestroyView()
    }
}
