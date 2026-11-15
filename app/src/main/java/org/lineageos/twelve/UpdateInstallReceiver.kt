/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * What the installer of Android says about an update: it asks the user to confirm it, then
 * whether it went in.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let {
                        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
            }

            // The app is replaced, nothing more to do here
            PackageInstaller.STATUS_SUCCESS -> Unit

            // Cancelled from the confirmation of Android
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit

            else -> Toast.makeText(
                context,
                context.getString(
                    R.string.update_install_failed,
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty(),
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
