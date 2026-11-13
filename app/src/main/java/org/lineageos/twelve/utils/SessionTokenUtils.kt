/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.utils

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionToken

/**
 * Helpers to get a [SessionToken] for our own playback service.
 */
object SessionTokenUtils {
    private val LOG_TAG = SessionTokenUtils::class.simpleName!!

    /**
     * Get the [SessionToken] of a [androidx.media3.session.MediaLibraryService] of this app.
     *
     * [SessionToken] normally finds the service by asking the package manager which services
     * handle the media3 actions. Some ROMs hide the services of regular apps from those queries,
     * even from the app that declares them, which makes the lookup fail. When that happens the
     * token is built directly from the component we already know.
     */
    @OptIn(UnstableApi::class)
    fun forLibraryService(context: Context, component: ComponentName): SessionToken = try {
        SessionToken(context, component)
    } catch (e: IllegalArgumentException) {
        Log.w(LOG_TAG, "Session token lookup failed, building it from the component", e)

        runCatching {
            val implClass = Class.forName("androidx.media3.session.SessionTokenImplBase")
            val implBundle = Bundle().apply {
                putInt(
                    stringField(implClass, "FIELD_UID"),
                    context.packageManager.getApplicationInfo(component.packageName, 0).uid
                )
                putInt(stringField(implClass, "FIELD_TYPE"), SessionToken.TYPE_LIBRARY_SERVICE)
                putInt(
                    stringField(implClass, "FIELD_LIBRARY_VERSION"),
                    SessionToken.UNKNOWN_SESSION_VERSION
                )
                putInt(
                    stringField(implClass, "FIELD_INTERFACE_VERSION"),
                    SessionToken.UNKNOWN_INTERFACE_VERSION
                )
                putString(stringField(implClass, "FIELD_PACKAGE_NAME"), component.packageName)
                putString(stringField(implClass, "FIELD_SERVICE_NAME"), component.className)
                putParcelable(stringField(implClass, "FIELD_COMPONENT_NAME"), component)
            }

            val tokenClass = SessionToken::class.java
            SessionToken.fromBundle(
                Bundle().apply {
                    putInt(stringField(tokenClass, "FIELD_IMPL_TYPE"), IMPL_TYPE_BASE)
                    putBundle(stringField(tokenClass, "FIELD_IMPL"), implBundle)
                }
            )
        }.getOrElse {
            Log.e(LOG_TAG, "Failed to build the session token", it)
            throw e
        }
    }

    private fun stringField(clazz: Class<*>, name: String) = clazz.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(null) as String

    /**
     * Value of SessionToken's private IMPL_TYPE_BASE constant.
     */
    private const val IMPL_TYPE_BASE = 0
}
