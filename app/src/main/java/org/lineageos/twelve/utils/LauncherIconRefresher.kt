/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit
import org.lineageos.twelve.R

/**
 * The launcher icon is drawn in the accent color of the device, but launchers keep the icons
 * they drew and only draw them again when the app changes. When the accent color is not the one
 * the icon was last drawn with, a component of the app is switched on and back off: Android tells
 * the launchers the app changed and they draw its icon again.
 */
object LauncherIconRefresher {
    private const val PREFERENCES_NAME = "launcher_icon"
    private const val KEY_ACCENT = "accent"

    /**
     * Ask the launchers to draw the icon again when the accent color changed since last time.
     */
    fun refreshIfNeeded(context: Context) {
        val accent = context.getColor(R.color.launcher_icon_accent)
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        // The first time the icon was just drawn, after the install or update
        if (!preferences.contains(KEY_ACCENT)) {
            preferences.edit { putInt(KEY_ACCENT, accent) }
            return
        }
        if (preferences.getInt(KEY_ACCENT, accent) == accent) {
            return
        }
        preferences.edit { putInt(KEY_ACCENT, accent) }

        val component = ComponentName(context, IconRefreshReceiver::class.java)
        val packageManager = context.packageManager
        runCatching {
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * Does nothing: it is only switched on and off, see [refreshIfNeeded].
     */
    class IconRefreshReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Unit
    }
}
