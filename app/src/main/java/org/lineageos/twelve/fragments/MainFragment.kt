/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.fragments

import android.content.Intent
import android.widget.Toast
import android.widget.PopupMenu
import android.view.Gravity
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.search.SearchView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.viewmodels.MainNavigationViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.fragment.app.activityViewModels
import org.lineageos.twelve.ui.expressive.ExpressiveShape
import org.lineageos.twelve.SettingsActivity
import org.lineageos.twelve.ext.getViewProperty
import org.lineageos.twelve.ext.isLandscape
import org.lineageos.twelve.ext.isRtl
import org.lineageos.twelve.ext.navigateSafe
import org.lineageos.twelve.ext.scheduleHideSoftInput
import org.lineageos.twelve.ext.setProgressCompat
import org.lineageos.twelve.ext.updatePadding
import org.lineageos.twelve.models.Album
import org.lineageos.twelve.models.Artist
import org.lineageos.twelve.models.Audio
import org.lineageos.twelve.models.FlowResult
import org.lineageos.twelve.models.Genre
import org.lineageos.twelve.models.MediaItem
import org.lineageos.twelve.models.Playlist
import org.lineageos.twelve.models.Result.Companion.onError
import org.lineageos.twelve.models.areContentsTheSame
import org.lineageos.twelve.models.areItemsTheSame
import org.lineageos.twelve.ui.recyclerview.SimpleListAdapter
import org.lineageos.twelve.ui.views.ListItem
import org.lineageos.twelve.viewmodels.MainViewModel

/**
 * The home page.
 */
class MainFragment : Fragment(R.layout.fragment_main) {
    // View models
    private val viewModel by viewModels<MainViewModel>()
    private val mainNavigationViewModel by activityViewModels<MainNavigationViewModel>()

    // Views
    private val modeToggleGroup by getViewProperty<MaterialButtonToggleGroup>(R.id.modeToggleGroup)
    private val playRandomSongsButton by getViewProperty<MaterialButton>(R.id.playRandomSongsButton)
    private val providerMaterialButton by getViewProperty<MaterialButton>(R.id.providerMaterialButton)
    private val refreshMaterialButton by getViewProperty<MaterialButton>(R.id.refreshMaterialButton)
    private val importMaterialButton by getViewProperty<MaterialButton>(R.id.importMaterialButton)
    private val greetingTextView by getViewProperty<TextView>(R.id.greetingTextView)
    private val brandShapeImageView by getViewProperty<ImageView>(R.id.brandShapeImageView)

    private val playlistImporter = PlaylistImporter(this) { viewModel }
    private val searchLinearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.searchLinearProgressIndicator)
    private val searchNoElementsLinearLayout by getViewProperty<LinearLayout>(R.id.searchNoElementsLinearLayout)
    private val searchRecyclerView by getViewProperty<RecyclerView>(R.id.searchRecyclerView)
    private val searchView by getViewProperty<SearchView>(R.id.searchView)
    private val searchFilterScrollView by getViewProperty<View>(R.id.searchFilterScrollView)
    private val searchFilterToggleGroup by getViewProperty<MaterialButtonToggleGroup>(R.id.searchFilterToggleGroup)
    private val searchStartLinearLayout by getViewProperty<LinearLayout>(R.id.searchStartLinearLayout)
    private val searchStartShapeImageView by getViewProperty<ImageView>(R.id.searchStartShapeImageView)
    private val searchNoElementsShapeImageView by getViewProperty<ImageView>(R.id.searchNoElementsShapeImageView)

    // Search results as they came, before the filter
    private var searchResults: List<MediaItem<*>> = listOf()
    private val settingsMaterialButton by getViewProperty<MaterialButton>(R.id.settingsMaterialButton)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    // Layout
    private var playRandomSongsButtonBottomMargin = 0

    // System services
    private val inputMethodManager: InputMethodManager
        get() = requireContext().getSystemService(InputMethodManager::class.java)

    // RecyclerView
    private val searchAdapter by lazy {
        object : SimpleListAdapter<MediaItem<*>, ListItem>(
            searchDiffCallback,
            ::ListItem
        ) {
            override val groupedRows = true

            override fun ViewHolder.onBindView(item: MediaItem<*>) {
                view.setOnLongClickListener {
                    findNavController().navigateSafe(
                        R.id.action_mainFragment_to_fragment_media_item_bottom_sheet_dialog,
                        MediaItemBottomSheetDialogFragment.createBundle(item.uri)
                    )
                    true
                }

                view.setGroupPosition(bindingAdapterPosition, itemCount)

                when (item) {
                    is Album -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_album,
                                AlbumFragment.createBundle(item.uri)
                            )
                        }

                        view.setLeadingCover(item.thumbnail, R.drawable.ic_album)
                        view.headlineText = item.title
                        view.supportingText = item.artistName
                    }

                    is Artist -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_artist,
                                ArtistFragment.createBundle(item.uri)
                            )
                        }

                        view.setLeadingCover(item.thumbnail, R.drawable.ic_person)
                        view.headlineText = item.name
                        view.supportingText = null
                    }

                    is Audio -> {
                        // Nothing playing: it plays. Something playing: ask what to do
                        view.setOnClickListener {
                            when (viewModel.isPlayingNow) {
                                false -> viewModel.playAudio(listOf(item), 0)
                                true -> showSearchPlayMenu(view, item)
                            }
                        }

                        view.setLeadingCover(item.thumbnail, R.drawable.ic_music_note)
                        view.headlineText = item.title
                        view.supportingText = item.artistName
                    }

                    is Genre -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_genre,
                                GenreFragment.createBundle(item.uri)
                            )
                        }

                        view.setLeadingCover(null, R.drawable.ic_genres)
                        view.headlineText = item.name
                        view.supportingText = null
                    }

                    is Playlist -> {
                        view.setOnClickListener {
                            findNavController().navigateSafe(
                                R.id.action_mainFragment_to_fragment_playlist,
                                PlaylistFragment.createBundle(item.uri)
                            )
                        }

                        view.setLeadingCover(item.thumbnail, R.drawable.ic_playlist_play)
                        view.headlineText = item.name
                        view.supportingText = null
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The mark of the app, a note in a Material shape
        brandShapeImageView.setImageDrawable(ExpressiveShape.COOKIE_9.drawable())
        brandShapeImageView.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                brandShapeImageView, com.google.android.material.R.attr.colorPrimaryContainer
            )
        )

        playRandomSongsButtonBottomMargin =
            (playRandomSongsButton.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: 0

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            v.updatePadding(
                insets,
                start = !resources.configuration.isLandscape,
                end = true,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(viewPager2) { v, windowInsets ->
            val displayCutoutInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.displayCutout()
            )
            // The bottom includes the floating navigation and now playing bars, see MainActivity
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            (playRandomSongsButton.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                // Above the floating bars and the system navigation bar
                val bottomMargin = playRandomSongsButtonBottomMargin + systemBarsInsets.bottom

                if (it.bottomMargin != bottomMargin) {
                    it.bottomMargin = bottomMargin
                    playRandomSongsButton.layoutParams = it
                }
            }

            fun adjustInsets(insets: Insets, portraitBottom: Int = 0) = Insets.of(
                when (!v.isRtl && resources.configuration.isLandscape) {
                    true -> 0
                    false -> insets.left
                },
                insets.top,
                when (v.isRtl && resources.configuration.isLandscape) {
                    true -> 0
                    false -> insets.right
                },
                when (resources.configuration.isLandscape) {
                    true -> insets.bottom
                    false -> portraitBottom
                },
            )

            WindowInsetsCompat.Builder(windowInsets)
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    adjustInsets(systemBarsInsets, systemBarsInsets.bottom),
                )
                .setInsets(
                    WindowInsetsCompat.Type.displayCutout(),
                    adjustInsets(displayCutoutInsets),
                )
                .build()
        }

        ViewCompat.setOnApplyWindowInsetsListener(searchRecyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // The last results scroll above the floating bars, their space is in the insets
            v.updatePadding(
                left = insets.left,
                right = insets.right,
                bottom = insets.bottom,
            )

            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(searchNoElementsLinearLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.updatePadding(
                insets,
                start = true,
                end = true,
                bottom = true,
            )

            windowInsets
        }

        // On back pressed
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(searchView.isShowing) {
                override fun handleOnBackStarted(backEvent: BackEventCompat) {
                    searchView.startBackProgress(backEvent)
                }

                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    searchView.updateBackProgress(backEvent)
                }

                override fun handleOnBackPressed() {
                    searchView.handleBackInvoked()
                }

                override fun handleOnBackCancelled() {
                    searchView.cancelBackProgress()
                }
            }.also {
                searchView.addTransitionListener { searchView, _, newState ->
                    val isShowing = newState in listOf(
                        SearchView.TransitionState.SHOWN,
                        SearchView.TransitionState.SHOWING,
                    )

                    it.isEnabled = isShowing

                    // Clear search query if hidden
                    if (!isShowing) {
                        searchView.clearText()
                    }
                }
            }
        )

        toolbar.setupWithNavController(findNavController())

        providerMaterialButton.setOnClickListener {
            findNavController().navigateSafe(
                R.id.action_mainFragment_to_fragment_provider_selector_dialog
            )
        }
        providerMaterialButton.setOnLongClickListener {
            viewModel.navigationProvider.value?.let {
                findNavController().navigateSafe(
                    R.id.action_mainFragment_to_fragment_provider_information_bottom_sheet_dialog,
                    ManageProviderFragment.createBundle(providerIdentifier = it.identifier),
                )
                true
            } ?: false
        }

        modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                viewModel.setOnlineMode(checkedId == R.id.onlineModeButton)
            }
        }

        greetingTextView.setText(
            when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
                in 5..11 -> R.string.greeting_morning
                in 12..16 -> R.string.greeting_afternoon
                in 17..21 -> R.string.greeting_evening
                else -> R.string.greeting_night
            }
        )

        importMaterialButton.setOnClickListener {
            playlistImporter.showDialog()
        }

        refreshMaterialButton.setOnClickListener {
            viewModel.refreshOnline()
        }

        settingsMaterialButton.setOnClickListener {
            val intent = Intent(context, SettingsActivity::class.java)
            startActivity(intent)
        }

        playRandomSongsButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.playAllAudios().onError {
                    Snackbar.make(
                        requireView(),
                        it.toString(),
                        Snackbar.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        // View pager
        viewPager2.isUserInputEnabled = false
        viewPager2.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = Page.entries.size
            override fun createFragment(position: Int) = Page.entries[position].createFragment()
        }
        viewPager2.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT

        // The floating navigation bar of the activity picks the page, and shows the one here
        viewPager2.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage.value = Page.entries[position]
            }
        })
        searchView.addTransitionListener { _, _, newState ->
            searchShowing.value = newState in listOf(
                SearchView.TransitionState.SHOWN,
                SearchView.TransitionState.SHOWING,
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainNavigationViewModel.requests.collect { destination ->
                        if (destination != MainNavigationViewModel.Destination.SEARCH &&
                            searchView.isShowing
                        ) {
                            searchView.hide()
                        }
                        when (destination) {
                            MainNavigationViewModel.Destination.HOME ->
                                viewPager2.setCurrentItem(Page.ACTIVITY.ordinal, false)

                            MainNavigationViewModel.Destination.SEARCH -> searchView.show()

                            MainNavigationViewModel.Destination.LIBRARY -> {
                                mainNavigationViewModel.showPlaylistsRequest.value = false
                                viewPager2.setCurrentItem(Page.LIBRARY.ordinal, false)
                            }

                            MainNavigationViewModel.Destination.PLAYLISTS -> {
                                mainNavigationViewModel.showPlaylistsRequest.value = true
                                viewPager2.setCurrentItem(Page.LIBRARY.ordinal, false)
                            }
                        }
                    }
                }
                launch {
                    combine(
                        currentPage,
                        searchShowing,
                        mainNavigationViewModel.libraryShowsPlaylists,
                    ) { page, search, playlists ->
                        when {
                            search -> MainNavigationViewModel.Destination.SEARCH
                            page == Page.ACTIVITY -> MainNavigationViewModel.Destination.HOME
                            playlists -> MainNavigationViewModel.Destination.PLAYLISTS
                            else -> MainNavigationViewModel.Destination.LIBRARY
                        }
                    }.collect { mainNavigationViewModel.current.value = it }
                }
            }
        }

        // Search
        searchRecyclerView.adapter = searchAdapter

        searchView.editText.addTextChangedListener { text ->
            viewModel.setSearchQuery(text.toString())
            showSearchResults()
        }

        // Search, the expressive way: a shape greets, filters pick the kind of result
        searchView.findViewById<View>(
            com.google.android.material.R.id.open_search_view_divider
        )?.isVisible = false
        // A plain surface, so the rows and the filters stand out on it
        val searchSurface = MaterialColors.getColor(
            searchView, com.google.android.material.R.attr.colorSurface
        )
        listOf(
            com.google.android.material.R.id.open_search_view_background,
            com.google.android.material.R.id.open_search_view_status_bar_spacer,
        ).forEach { searchView.findViewById<View>(it)?.setBackgroundColor(searchSurface) }
        searchStartShapeImageView.setImageDrawable(ExpressiveShape.COOKIE_12.drawable())
        searchStartShapeImageView.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                searchStartShapeImageView, com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
        searchNoElementsShapeImageView.setImageDrawable(ExpressiveShape.SOFT_BURST.drawable())
        searchNoElementsShapeImageView.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                searchNoElementsShapeImageView,
                com.google.android.material.R.attr.colorTertiaryContainer,
            )
        )
        searchFilterToggleGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                showSearchResults()
                searchRecyclerView.scrollToPosition(0)
            }
        }
        searchView.editText.setOnEditorActionListener { _, _, _ ->
            inputMethodManager.scheduleHideSoftInput(searchView.editText, 0)
            searchView.editText.clearFocus()
            viewModel.setSearchQuery(searchView.editText.text.toString(), true)
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.onlineMode.collectLatest { onlineMode ->
                        val checkedId = when (onlineMode) {
                            true -> R.id.onlineModeButton
                            false -> R.id.offlineModeButton
                        }

                        if (modeToggleGroup.checkedButtonId != checkedId) {
                            modeToggleGroup.check(checkedId)
                        }

                        // The provider chooser is only meaningful for the offline sources
                        providerMaterialButton.isVisible = !onlineMode
                        refreshMaterialButton.isVisible = onlineMode
                        importMaterialButton.isVisible = onlineMode
                    }
                }

                launch {
                    viewModel.navigationProvider.collectLatest {
                        it?.let {
                            providerMaterialButton.text = it.name
                            providerMaterialButton.setIconResource(it.type.iconDrawableResId)
                        } ?: run {
                            providerMaterialButton.setText(R.string.no_provider)
                            providerMaterialButton.setIconResource(R.drawable.ic_warning)
                        }
                    }
                }

                launch {
                    viewModel.searchResults.collectLatest {
                        searchLinearProgressIndicator.setProgressCompat(it)

                        when (it) {
                            is FlowResult.Loading -> {
                                // Do nothing
                            }

                            is FlowResult.Success -> {
                                searchResults = it.data
                                showSearchResults()
                            }

                            is FlowResult.Failure -> {
                                Log.e(
                                    LOG_TAG,
                                    "Failed to load search results, error: ${it.error}",
                                    it.throwable
                                )

                                searchResults = listOf()
                                showSearchResults()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Ask whether a song found while something plays replaces it or joins the queue.
     */
    private fun showSearchPlayMenu(anchor: View, audio: Audio) {
        PopupMenu(requireContext(), anchor, Gravity.END).apply {
            menu.add(0, MENU_PLAY_NOW, 0, R.string.audio_play_now)
                .setIcon(R.drawable.ic_play_arrow)
            menu.add(0, MENU_PLAY_NEXT, 1, R.string.audio_play_next)
                .setIcon(R.drawable.ic_queue_play_next)
            menu.add(0, MENU_ADD_TO_QUEUE, 2, R.string.audio_add_to_queue)
                .setIcon(R.drawable.ic_add_to_queue)
            setForceShowIcon(true)

            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_PLAY_NOW -> viewModel.playAudio(listOf(audio), 0)

                    MENU_PLAY_NEXT -> {
                        viewModel.playNext(listOf(audio))
                        Toast.makeText(
                            requireContext(), R.string.audio_playing_next, Toast.LENGTH_SHORT
                        ).show()
                    }

                    MENU_ADD_TO_QUEUE -> {
                        viewModel.addToQueue(listOf(audio))
                        Toast.makeText(
                            requireContext(), R.string.audio_added_to_queue, Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                true
            }
        }.show()
    }

    /**
     * Show the search results of the chosen kind, or what fits when there are none.
     */
    private fun showSearchResults() {
        val hasQuery = searchView.editText.text.isNotEmpty()
        val results = searchResults.takeIf { hasQuery } ?: listOf()
        val filtered = when (searchFilterToggleGroup.checkedButtonId) {
            R.id.searchFilterSongsButton -> results.filterIsInstance<Audio>()
            R.id.searchFilterAlbumsButton -> results.filterIsInstance<Album>()
            R.id.searchFilterArtistsButton -> results.filterIsInstance<Artist>()
            R.id.searchFilterPlaylistsButton -> results.filterIsInstance<Playlist>()
            else -> results
        }

        searchAdapter.submitList(filtered)

        searchStartLinearLayout.isVisible = !hasQuery
        searchFilterScrollView.isVisible = hasQuery && results.isNotEmpty()
        searchRecyclerView.isVisible = filtered.isNotEmpty()
        searchNoElementsLinearLayout.isVisible = hasQuery && filtered.isEmpty() &&
                viewModel.searchResults.value !is FlowResult.Loading
    }

    override fun onDestroyView() {
        // View pager
        viewPager2.adapter = null

        // Search
        searchRecyclerView.adapter = null

        super.onDestroyView()
    }

    private enum class Page(
        val createFragment: () -> Fragment,
    ) {
        ACTIVITY(::ActivityFragment),
        LIBRARY(::LibraryFragment),
    }

    // What the floating navigation bar highlights
    private val currentPage = MutableStateFlow(Page.ACTIVITY)
    private val searchShowing = MutableStateFlow(false)

    companion object {
        private val LOG_TAG = MainFragment::class.simpleName!!

        private const val MENU_PLAY_NOW = 1
        private const val MENU_PLAY_NEXT = 2
        private const val MENU_ADD_TO_QUEUE = 3

        private val searchDiffCallback = object : DiffUtil.ItemCallback<MediaItem<*>>() {
            override fun areItemsTheSame(
                oldItem: MediaItem<*>,
                newItem: MediaItem<*>
            ) = when (oldItem) {
                is Album -> oldItem.areItemsTheSame(newItem)
                is Artist -> oldItem.areItemsTheSame(newItem)
                is Audio -> oldItem.areItemsTheSame(newItem)
                is Genre -> oldItem.areItemsTheSame(newItem)
                is Playlist -> oldItem.areItemsTheSame(newItem)
            }

            override fun areContentsTheSame(
                oldItem: MediaItem<*>,
                newItem: MediaItem<*>
            ) = when (oldItem) {
                is Album -> oldItem.areContentsTheSame(newItem)
                is Artist -> oldItem.areContentsTheSame(newItem)
                is Audio -> oldItem.areContentsTheSame(newItem)
                is Genre -> oldItem.areContentsTheSame(newItem)
                is Playlist -> oldItem.areContentsTheSame(newItem)
            }
        }
    }
}
