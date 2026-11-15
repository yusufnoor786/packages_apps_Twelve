/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.repositories

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import org.lineageos.twelve.R
import org.lineageos.twelve.datasources.account.AccountError
import org.lineageos.twelve.datasources.account.AccountResult
import org.lineageos.twelve.datasources.account.AccountSession
import org.lineageos.twelve.datasources.account.SessionStore
import org.lineageos.twelve.datasources.account.SupabaseClient
import org.lineageos.twelve.datasources.account.SyncDocument
import org.lineageos.twelve.datasources.saavn.SaavnLibraryStore
import org.lineageos.twelve.datasources.saavn.StoredLibrary
import org.lineageos.twelve.ext.AUTOMIX_KEY
import org.lineageos.twelve.ext.AUTOPLAY_KEY
import org.lineageos.twelve.ext.CROSSFADE_SECONDS_KEY
import org.lineageos.twelve.ext.LYRICS_OFFSET_MS_KEY
import org.lineageos.twelve.ext.ONLINE_AUDIO_QUALITY_KEY
import org.lineageos.twelve.ext.ONLINE_AUDIO_QUALITY_MOBILE_KEY
import org.lineageos.twelve.ext.ONLINE_LANGUAGES_KEY
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The account of the user: signing in with an email and a password or with GitHub in
 * the browser, and keeping the favorites, the playlists and some settings of the online catalog
 * the same on every device signed in to it.
 *
 * Without an account everything stays on the device, as before.
 */
class AccountRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val libraryStore: SaavnLibraryStore,
    private val sharedPreferences: SharedPreferences,
) {
    sealed interface State {
        data object SignedOut : State
        data class SignedIn(
            val email: String?,
            val provider: String?,
            val name: String?,
            val userName: String?,
            val avatarUrl: String?,
        ) : State
    }

    sealed interface SyncState {
        data object Idle : SyncState
        data object Syncing : SyncState
        data class Synced(val at: Long) : SyncState
        data class Failed(val error: AccountError) : SyncState
    }

    private val client = SupabaseClient(
        OkHttpClient(),
        context.getString(R.string.account_supabase_url).trimEnd('/'),
        context.getString(R.string.account_supabase_key),
    )
    private val sessionStore = SessionStore(context)

    /**
     * Whether the build has a project to keep accounts in.
     */
    val isAvailable = context.getString(R.string.account_supabase_url).isNotBlank() &&
            context.getString(R.string.account_supabase_key).isNotBlank()

    private var session: AccountSession? = sessionStore.read()
    private val sessionMutex = Mutex()
    private val syncMutex = Mutex()

    private val mutableState = MutableStateFlow(session.toState())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val mutableSyncState = MutableStateFlow<SyncState>(
        sessionStore.lastSyncAt.takeIf { it > 0 }?.let { SyncState.Synced(it) } ?: SyncState.Idle
    )
    val syncState: StateFlow<SyncState> = mutableSyncState.asStateFlow()

    // What the last sync left: changes of our own are not changes to send
    private var lastSyncedLibrary: StoredLibrary? = null
    private var applyingSettings = false
    private var started = false

    // Kept in a field: the preferences only hold a weak reference to it
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in SYNCED_SETTINGS && !applyingSettings) {
            sessionStore.settingsModifiedAt = System.currentTimeMillis()
            requestSync()
        }
    }

    /**
     * Sync now and on every change from now on.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started || !isAvailable) {
            return
        }
        started = true

        sharedPreferences.registerOnSharedPreferenceChangeListener(settingsListener)

        scope.launch {
            libraryStore.library
                .drop(1)
                .filter { it != lastSyncedLibrary }
                .debounce(CHANGES_DEBOUNCE_MS)
                .collect { requestSync() }
        }

        requestSync()
        scope.launch { refreshProfile() }
    }

    /**
     * Read the name and the picture of the account again, they may have changed elsewhere.
     */
    private suspend fun refreshProfile() {
        val session = validSession() ?: return
        val result = client.user(session) as? AccountResult.Success ?: return
        sessionMutex.withLock {
            // Still the same sign in: the tokens of a refresh in between must stay
            this.session?.takeIf { it.userId == result.value.userId }?.let {
                setSession(
                    it.copy(
                        email = result.value.email,
                        name = result.value.name,
                        userName = result.value.userName,
                        avatarUrl = result.value.avatarUrl,
                    )
                )
            }
        }
    }

    /**
     * The sign in methods turned on in the project.
     */
    suspend fun providers(): Set<String> = when (val result = client.providers()) {
        is AccountResult.Success -> result.value
        is AccountResult.Failure -> emptySet()
    }

    suspend fun signIn(email: String, password: String): AccountResult<Unit> =
        when (val result = client.signIn(email.trim(), password)) {
            is AccountResult.Success -> {
                onSignedIn(result.value)
                AccountResult.Success(Unit)
            }

            is AccountResult.Failure -> result
        }

    /**
     * Create an account: true when it is signed in at once, false when the address has to be
     * confirmed first, from the email the project sends.
     */
    suspend fun signUp(email: String, password: String): AccountResult<Boolean> =
        when (val result = client.signUp(email.trim(), password)) {
            is AccountResult.Success -> {
                result.value?.let { onSignedIn(it) }
                AccountResult.Success(result.value != null)
            }

            is AccountResult.Failure -> result
        }

    /**
     * Where the browser has to go to sign in with [provider], "github". It comes back
     * to the app on [REDIRECT_URI], see [completeBrowserSignIn].
     */
    fun browserSignInUri(provider: String): Uri {
        val verifier = Base64.encodeToString(
            ByteArray(VERIFIER_BYTES).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        sessionStore.pendingCodeVerifier = verifier

        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

        return client.authorizeUri(provider, REDIRECT_URI, challenge)
    }

    suspend fun completeBrowserSignIn(uri: Uri): AccountResult<Unit> {
        (uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error"))?.let {
            return AccountResult.Failure(AccountError.UNKNOWN, it)
        }

        val code = uri.getQueryParameter("code")
            ?: return AccountResult.Failure(AccountError.UNKNOWN)
        val verifier = sessionStore.pendingCodeVerifier
            ?: return AccountResult.Failure(AccountError.UNKNOWN)

        return when (val result = client.exchangeCode(code, verifier)) {
            is AccountResult.Success -> {
                sessionStore.pendingCodeVerifier = null
                onSignedIn(result.value)
                AccountResult.Success(Unit)
            }

            is AccountResult.Failure -> result
        }
    }

    /**
     * Change the name shown for the account, on every device.
     */
    suspend fun updateName(name: String): AccountResult<Unit> {
        val session = validSession() ?: return AccountResult.Failure(AccountError.SESSION_EXPIRED)

        return when (val result = client.updateName(session, name.trim())) {
            is AccountResult.Success -> {
                sessionMutex.withLock { setSession(result.value) }
                AccountResult.Success(Unit)
            }

            is AccountResult.Failure -> result
        }
    }

    /**
     * Sign out. The last changes are sent to the account first, then the favorites and the
     * playlists leave the device with it: they are back when signing in again.
     */
    suspend fun signOut() {
        if (session != null) {
            sync()
        }
        sessionMutex.withLock { session }?.let { client.signOut(it) }
        forgetSession()
        clearLibrary()
    }

    /**
     * Delete the account, what is kept for it, and its favorites and playlists on the device.
     */
    suspend fun deleteAccount(): AccountResult<Unit> {
        val session = validSession() ?: return AccountResult.Failure(AccountError.SESSION_EXPIRED)

        return when (val result = client.deleteAccount(session)) {
            is AccountResult.Success -> {
                forgetSession()
                clearLibrary()
                result
            }

            is AccountResult.Failure -> result
        }
    }

    private suspend fun clearLibrary() = syncMutex.withLock {
        libraryStore.clear()
        lastSyncedLibrary = libraryStore.library.value
        sessionStore.libraryOwner = null
    }

    fun requestSync() {
        if (session == null) {
            return
        }

        scope.launch { sync() }
    }

    private suspend fun sync(): AccountResult<Unit> = syncMutex.withLock {
        val session = validSession() ?: return@withLock AccountResult.Failure(
            AccountError.SESSION_EXPIRED
        )

        mutableSyncState.value = SyncState.Syncing

        val remote = when (val result = client.readLibrary(session)) {
            is AccountResult.Success -> result.value
            is AccountResult.Failure -> return@withLock failed(result)
        }

        val now = System.currentTimeMillis()

        // Signing in to an account that has a library: the device shows that library, what was on
        // it before belonged to no account or to another one
        if (remote != null && sessionStore.libraryOwner != session.userId) {
            replaceLocally(remote)
            sessionStore.libraryOwner = session.userId
            sessionStore.lastSyncAt = now
            mutableSyncState.value = SyncState.Synced(now)
            return@withLock AccountResult.Success(Unit)
        }

        val local = SyncDocument.of(
            libraryStore.library.value, localSettings(), sessionStore.settingsModifiedAt
        )
        val merged = SyncDocument.merge(local, remote ?: SyncDocument()).pruned(now)

        applyLocally(merged)

        if (merged != remote) {
            when (val result = client.writeLibrary(session, merged)) {
                is AccountResult.Success -> Unit
                is AccountResult.Failure -> return@withLock failed(result)
            }
        }

        sessionStore.libraryOwner = session.userId
        sessionStore.lastSyncAt = now
        mutableSyncState.value = SyncState.Synced(now)
        AccountResult.Success(Unit)
    }

    /**
     * Put the library and the settings of the account in place of those on the device.
     */
    private suspend fun replaceLocally(remote: SyncDocument) {
        libraryStore.replace { remote.applyTo(StoredLibrary(nextPlaylistId = it.nextPlaylistId)) }
        lastSyncedLibrary = libraryStore.library.value

        if (remote.settings.isNotEmpty() && remote.settings != localSettings()) {
            withContext(Dispatchers.Main) {
                applyingSettings = true
                try {
                    sharedPreferences.edit(commit = true) { putSettings(remote.settings) }
                } finally {
                    applyingSettings = false
                }
            }
        }
        sessionStore.settingsModifiedAt = remote.settingsModifiedAt
    }

    private suspend fun applyLocally(merged: SyncDocument) {
        // Merged again with what is there now: a change made during the sync is not lost
        libraryStore.replace { latest ->
            SyncDocument.merge(SyncDocument.of(latest, emptyMap(), 0L), merged).applyTo(latest)
        }
        lastSyncedLibrary = libraryStore.library.value

        if (merged.settingsModifiedAt > sessionStore.settingsModifiedAt &&
            merged.settings != localSettings()
        ) {
            // On the main thread with commit: the listener runs before the flag goes down
            withContext(Dispatchers.Main) {
                applyingSettings = true
                try {
                    sharedPreferences.edit(commit = true) { putSettings(merged.settings) }
                } finally {
                    applyingSettings = false
                }
            }
        }
        sessionStore.settingsModifiedAt = maxOf(
            sessionStore.settingsModifiedAt, merged.settingsModifiedAt
        )
    }

    private fun failed(failure: AccountResult.Failure): AccountResult.Failure {
        if (failure.error == AccountError.SESSION_EXPIRED) {
            scope.launch { forgetSession() }
        }
        mutableSyncState.value = SyncState.Failed(failure.error)
        return failure
    }

    /**
     * The session, refreshed when it is about to expire. Null when it can't be used any more.
     */
    private suspend fun validSession(): AccountSession? = sessionMutex.withLock {
        val current = session ?: return@withLock null
        if (current.expiresAt - System.currentTimeMillis() / 1000 > REFRESH_MARGIN_S) {
            return@withLock current
        }

        when (val result = client.refresh(current.refreshToken)) {
            is AccountResult.Success -> result.value.also { setSession(it) }

            is AccountResult.Failure -> when (result.error) {
                // Offline: try with what we have, the server tells if it is too old
                AccountError.NETWORK -> current
                else -> {
                    setSession(null)
                    null
                }
            }
        }
    }

    private suspend fun onSignedIn(newSession: AccountSession) {
        sessionMutex.withLock { setSession(newSession) }
        requestSync()
    }

    private suspend fun forgetSession() {
        sessionMutex.withLock { setSession(null) }
        mutableSyncState.value = SyncState.Idle
        sessionStore.lastSyncAt = 0L
    }

    private fun setSession(newSession: AccountSession?) {
        session = newSession
        sessionStore.write(newSession)
        mutableState.value = newSession.toState()
    }

    private fun AccountSession?.toState() = when (this) {
        null -> State.SignedOut
        else -> State.SignedIn(email, provider, name, userName, avatarUrl)
    }

    private fun localSettings(): Map<String, JsonElement> = SYNCED_SETTINGS
        .filterKeys { sharedPreferences.contains(it) }
        .mapNotNull { (key, type) ->
            runCatching {
                key to when (type) {
                    SettingType.BOOLEAN -> JsonPrimitive(sharedPreferences.getBoolean(key, false))
                    SettingType.INT -> JsonPrimitive(sharedPreferences.getInt(key, 0))
                    SettingType.STRING -> JsonPrimitive(sharedPreferences.getString(key, null))
                    SettingType.STRING_SET -> JsonArray(
                        sharedPreferences.getStringSet(key, null).orEmpty().sorted()
                            .map { JsonPrimitive(it) }
                    )
                }
            }.getOrNull()
        }
        .toMap()

    private fun SharedPreferences.Editor.putSettings(settings: Map<String, JsonElement>) {
        settings.forEach { (key, value) ->
            when (SYNCED_SETTINGS[key]) {
                SettingType.BOOLEAN -> (value as? JsonPrimitive)?.booleanOrNull?.let {
                    putBoolean(key, it)
                }

                SettingType.INT -> (value as? JsonPrimitive)?.intOrNull?.let { putInt(key, it) }
                SettingType.STRING -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let {
                    putString(key, it.content)
                }

                SettingType.STRING_SET -> (value as? JsonArray)?.let { array ->
                    putStringSet(
                        key,
                        array.mapNotNull { (it as? JsonPrimitive)?.content }.toSet(),
                    )
                }

                null -> Unit
            }
        }
    }

    private enum class SettingType { BOOLEAN, INT, STRING, STRING_SET }

    companion object {
        /**
         * Where GitHub sign in comes back to. It has to be in the redirect URLs of the
         * project.
         */
        const val REDIRECT_URI = "org.lineageos.twelve://login-callback"

        // The settings that follow the account, those that make sense on every device
        private val SYNCED_SETTINGS = mapOf(
            ONLINE_LANGUAGES_KEY to SettingType.STRING_SET,
            ONLINE_AUDIO_QUALITY_KEY to SettingType.STRING,
            ONLINE_AUDIO_QUALITY_MOBILE_KEY to SettingType.STRING,
            AUTOPLAY_KEY to SettingType.BOOLEAN,
            AUTOMIX_KEY to SettingType.BOOLEAN,
            CROSSFADE_SECONDS_KEY to SettingType.INT,
            LYRICS_OFFSET_MS_KEY to SettingType.INT,
        )

        private const val CHANGES_DEBOUNCE_MS = 2_000L
        private const val REFRESH_MARGIN_S = 60L
        private const val VERIFIER_BYTES = 48
    }
}
