/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.lineageos.twelve.R
import org.lineageos.twelve.ext.ONLINE_MODE_KEY
import org.lineageos.twelve.ext.onlineMode
import org.lineageos.twelve.ext.permissionsFlow
import org.lineageos.twelve.ext.permissionsGranted
import org.lineageos.twelve.ext.preferenceFlow

/**
 * A coroutine-based class that checks main app permissions.
 */
class PermissionsChecker private constructor(
    caller: ActivityResultCaller,
    private val getContext: () -> Context,
    private val getActivity: () -> Activity,
    private val getLifecycle: () -> Lifecycle,
    private val permissions: Array<String>,
    private val optional: Boolean = false,
    @StringRes private val permissionsDeniedStringResId: Int? = R.string.app_permissions_toast,
) {
    constructor(
        fragment: Fragment,
        permissions: Array<String>,
        optional: Boolean = false,
        @StringRes permissionsDeniedStringResId: Int? = R.string.app_permissions_toast,
    ) : this(
        fragment,
        fragment::requireContext,
        fragment::requireActivity,
        fragment::lifecycle,
        permissions,
        optional,
        permissionsDeniedStringResId,
    )

    constructor(
        activity: AppCompatActivity,
        permissions: Array<String>,
        optional: Boolean = false,
        @StringRes permissionsDeniedStringResId: Int? = R.string.app_permissions_toast,
    ) : this(
        activity,
        { activity },
        { activity },
        activity::lifecycle,
        permissions,
        optional,
        permissionsDeniedStringResId,
    )

    private val channel = Channel<Unit>(1)

    private val activityResultLauncher = caller.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val context = getContext()

        if (it.isNotEmpty()) {
            if (!context.permissionsGranted(permissions)) {
                permissionsDeniedStringResId?.let { permissionsDeniedStringResId ->
                    Toast.makeText(
                        context,
                        permissionsDeniedStringResId,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (!optional) {
                    getActivity().finish()
                }
            } else {
                channel.trySend(Unit)
            }
        }
    }

    /**
     * Whether these are the permissions needed to read the device's media. Those aren't required
     * while the online catalog is in use.
     */
    private val isLocalMediaAccess = permissions.contentEquals(PermissionsUtils.mainPermissions)

    suspend fun withPermissionsGranted(unit: suspend () -> Unit) = combine(
        getContext().permissionsFlow(getLifecycle(), permissions).distinctUntilChanged(),
        PreferenceManager.getDefaultSharedPreferences(getContext())
            .preferenceFlow(ONLINE_MODE_KEY) { onlineMode },
    ) { status, onlineMode ->
        status to (onlineMode && isLocalMediaAccess)
    }.distinctUntilChanged().collectLatest { (status, skipChecks) ->
        val (_, denied) = status

        if (denied.isNotEmpty() && !skipChecks) {
            checkPermissions()
        } else {
            unit()
        }
    }

    private suspend fun checkPermissions() {
        activityResultLauncher.launch(permissions)

        return channel.receive()
    }
}
