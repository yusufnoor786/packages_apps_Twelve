/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.navigation

import androidx.navigation.NavHostController
import androidx.navigation.fragment.NavHostFragment

/**
 * A navigation host whose pages stay alive below the one on top, see
 * [KeepAliveFragmentNavigator].
 */
class KeepAliveNavHostFragment : NavHostFragment() {
    override fun onCreateNavHostController(navHostController: NavHostController) {
        super.onCreateNavHostController(navHostController)

        // Same name as the default one: it takes its place
        navHostController.navigatorProvider.addNavigator(
            KeepAliveFragmentNavigator(requireContext(), childFragmentManager, id)
        )
    }
}
