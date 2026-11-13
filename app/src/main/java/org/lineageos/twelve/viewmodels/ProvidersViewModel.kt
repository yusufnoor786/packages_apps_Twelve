/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.lineageos.twelve.models.Provider
import org.lineageos.twelve.models.ProviderType
import org.lineageos.twelve.models.areItemsTheSame

class ProvidersViewModel(application: Application) : TwelveViewModel(application) {
    val navigationProvider = mediaRepository.navigationProvider
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            null,
        )

    val providersToIsCurrent = combine(
        providersRepository.allProviders,
        navigationProvider,
    ) { allVisibleProviders, navigationProvider ->
        // The online catalog is chosen with the mode switch
        allVisibleProviders.filter { it.type != ProviderType.SAAVN }.map { provider ->
            provider to provider.areItemsTheSame(navigationProvider)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            listOf(),
        )

    fun setNavigationProvider(provider: Provider) {
        mediaRepository.setNavigationProvider(provider.identifier)
    }
}
