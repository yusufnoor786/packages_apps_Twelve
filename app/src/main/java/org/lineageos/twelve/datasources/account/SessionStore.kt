/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the session of the account encrypted with a key of the Android keystore. The key never
 * leaves the device: a copy of the app data, in a backup for example, can't be used anywhere else
 * and simply reads as signed out.
 */
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): AccountSession? = runCatching {
        val stored = preferences.getString(KEY_SESSION, null) ?: return null
        val (iv, data) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))

        json.decodeFromString(serializer<AccountSession>(), String(cipher.doFinal(data)))
    }.getOrNull()

    fun write(session: AccountSession?) {
        if (session == null) {
            preferences.edit { remove(KEY_SESSION) }
            return
        }

        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.doFinal(
                json.encodeToString(serializer<AccountSession>(), session).toByteArray()
            )

            preferences.edit {
                putString(
                    KEY_SESSION,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                            Base64.encodeToString(data, Base64.NO_WRAP)
                )
            }
        }
    }

    /**
     * Small values that are not secret: when the settings last changed, the last sync, the code
     * verifier of a browser sign in that is under way.
     */
    var settingsModifiedAt: Long
        get() = preferences.getLong(KEY_SETTINGS_MODIFIED_AT, 0L)
        set(value) = preferences.edit { putLong(KEY_SETTINGS_MODIFIED_AT, value) }

    var lastSyncAt: Long
        get() = preferences.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = preferences.edit { putLong(KEY_LAST_SYNC_AT, value) }

    var pendingCodeVerifier: String?
        get() = preferences.getString(KEY_CODE_VERIFIER, null)
        set(value) = preferences.edit {
            when (value) {
                null -> remove(KEY_CODE_VERIFIER)
                else -> putString(KEY_CODE_VERIFIER, value)
            }
        }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        private const val PREFERENCES_NAME = "account"
        private const val KEY_SESSION = "session"
        private const val KEY_SETTINGS_MODIFIED_AT = "settings_modified_at"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_CODE_VERIFIER = "code_verifier"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "twelve_account_session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
