/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import androidx.navigation.NavigatorState
import androidx.navigation.fragment.FragmentNavigator

/**
 * A [FragmentNavigator] that keeps the page below alive.
 *
 * The default navigator replaces the current page with the new one: the page below loses its
 * views and has to build them all again when going back, which takes a good part of a second on
 * a big page such as the home screen. This one puts the new page on top and hides the one below
 * it instead, so going back only shows it again. Everything else (back stack, arguments,
 * animations, state saving) is left to the default implementation.
 */
@Navigator.Name("fragment")
class KeepAliveFragmentNavigator(
    private val appContext: Context,
    private val manager: FragmentManager,
    private val containerId: Int,
) : FragmentNavigator(appContext, manager, containerId) {
    private val handler = Handler(Looper.getMainLooper())

    override fun onAttach(state: NavigatorState) {
        super.onAttach(state)

        // Pages that are only hidden never reach the states the default navigator waits for
        manager.addOnBackStackChangedListener(object : FragmentManager.OnBackStackChangedListener {
            override fun onBackStackChanged() {}

            override fun onBackStackChangeCommitted(fragment: Fragment, pop: Boolean) {
                handler.post { completeSettledTransitions() }
            }
        })
    }

    override fun navigate(
        entries: List<NavBackStackEntry>,
        navOptions: NavOptions?,
        navigatorExtras: Navigator.Extras?
    ) {
        if (manager.isStateSaved) {
            super.navigate(entries, navOptions, navigatorExtras)
            return
        }

        for (entry in entries) {
            val outgoing = state.backStack.value.lastOrNull()
            val outgoingFragment = outgoing?.let { manager.findFragmentByTag(it.id) }

            // The first page and anything unusual are left to the default navigator
            if (outgoingFragment == null || navOptions?.shouldRestoreState() == true) {
                super.navigate(listOf(entry), navOptions, navigatorExtras)
                continue
            }

            push(entry, outgoingFragment, navOptions, navigatorExtras)
        }
    }

    private fun push(
        entry: NavBackStackEntry,
        outgoingFragment: Fragment,
        navOptions: NavOptions?,
        navigatorExtras: Navigator.Extras?,
    ) {
        val destination = entry.destination as Destination
        var className = destination.className
        if (className[0] == '.') {
            className = appContext.packageName + className
        }
        val fragment = manager.fragmentFactory.instantiate(appContext.classLoader, className)
        fragment.arguments = entry.arguments

        val ft = manager.beginTransaction()

        val enter = navOptions?.enterAnim ?: -1
        val exit = navOptions?.exitAnim ?: -1
        val popEnter = navOptions?.popEnterAnim ?: -1
        val popExit = navOptions?.popExitAnim ?: -1
        if (enter != -1 || exit != -1 || popEnter != -1 || popExit != -1) {
            ft.setCustomAnimations(
                maxOf(enter, 0), maxOf(exit, 0), maxOf(popEnter, 0), maxOf(popExit, 0)
            )
        }

        ft.add(containerId, fragment, entry.id)
        // Hidden and not resumed: nothing runs for a page nobody sees, but its views stay
        ft.hide(outgoingFragment)
        ft.setMaxLifecycle(outgoingFragment, Lifecycle.State.STARTED)
        ft.setPrimaryNavigationFragment(fragment)
        ft.setReorderingAllowed(true)
        ft.addToBackStack(entry.id)

        if (navigatorExtras is Extras) {
            for ((view, name) in navigatorExtras.sharedElements) {
                ft.addSharedElement(view, name)
            }
        }

        ft.commit()
        state.pushWithTransition(entry)
    }

    /**
     * Tell the navigation state about pages that are settled: the one covered by another, and the
     * one that is shown again after a page above it went away.
     */
    private fun completeSettledTransitions() {
        val top = state.backStack.value.lastOrNull()

        state.transitionsInProgress.value.forEach { entry ->
            val fragment = manager.findFragmentByTag(entry.id) ?: return@forEach

            val settled = when (entry) {
                top -> !fragment.isHidden &&
                        fragment.lifecycle.currentState == Lifecycle.State.RESUMED

                else -> fragment.isHidden
            }
            if (settled) {
                state.markTransitionComplete(entry)
            }
        }
    }
}
