/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.repositories

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lineageos.twelve.R
import org.lineageos.twelve.UpdateInstallReceiver
import org.lineageos.twelve.datasources.update.AppRelease
import org.lineageos.twelve.datasources.update.ReleaseParser
import org.lineageos.twelve.ext.executeAsync
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Updates of the app published as GitHub releases: finding a newer version, downloading it,
 * checking it against its checksum and handing it to the installer of Android.
 *
 * Android only accepts an update signed like the installed app, so a release only installs on
 * the devices whose app came from the same build keys.
 */
class UpdateRepository(private val context: Context) {
    sealed interface CheckResult {
        data class Available(val release: AppRelease) : CheckResult
        data object UpToDate : CheckResult
        data object Failed : CheckResult
    }

    private val okHttpClient = OkHttpClient()
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val repository = context.getString(R.string.update_github_repository).trim()

    /**
     * Whether the build has a place to look for updates.
     */
    val isAvailable = repository.isNotEmpty()

    private val mutableAvailable = MutableStateFlow<AppRelease?>(null)

    /**
     * The newer version found by the last check, null when there is none.
     */
    val available: StateFlow<AppRelease?> = mutableAvailable.asStateFlow()

    val installedVersionName: String
        get() = packageInfo().versionName.orEmpty()

    private val installedVersionCode: Long
        get() = packageInfo().longVersionCode

    var autoCheck: Boolean
        get() = preferences.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = preferences.edit { putBoolean(KEY_AUTO_CHECK, value) }

    private var lastCheckAt: Long
        get() = preferences.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) = preferences.edit { putLong(KEY_LAST_CHECK_AT, value) }

    /**
     * Where the releases are read. A debug build can be pointed at a test feed with
     * `update_feed_url` in the update preferences.
     */
    private val feedUrl: String
        get() = preferences.getString(KEY_FEED_URL, null)
            ?.takeIf { isDebuggable }
            ?: "https://api.github.com/repos/$repository/releases/latest"

    val releasesPageUrl: String
        get() = "https://github.com/$repository/releases"

    private val isDebuggable =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Look for a newer version. [force] checks even when the last check is recent.
     */
    suspend fun check(force: Boolean = false): CheckResult {
        if (!isAvailable) {
            return CheckResult.UpToDate
        }
        if (!force && System.currentTimeMillis() - lastCheckAt < MIN_CHECK_INTERVAL_MS) {
            return mutableAvailable.value?.let { CheckResult.Available(it) }
                ?: CheckResult.UpToDate
        }

        val request = Request.Builder()
            .url(feedUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()

        val result = try {
            okHttpClient.newCall(request).executeAsync().use { response ->
                when {
                    // No release published yet
                    response.code == 404 -> CheckResult.UpToDate
                    !response.isSuccessful -> CheckResult.Failed
                    else -> ReleaseParser.parse(response.body?.string().orEmpty())
                        ?.takeIf { it.versionCode > installedVersionCode }
                        ?.let { CheckResult.Available(it) }
                        ?: CheckResult.UpToDate
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            CheckResult.Failed
        }

        if (result != CheckResult.Failed) {
            lastCheckAt = System.currentTimeMillis()
            mutableAvailable.value = (result as? CheckResult.Available)?.release
        }
        return result
    }

    /**
     * Whether the popup for [release] should show now: not when it was put off a moment ago.
     */
    fun shouldPrompt(release: AppRelease) =
        !(preferences.getLong(KEY_SNOOZED_CODE, -1L) == release.versionCode &&
                System.currentTimeMillis() - preferences.getLong(KEY_SNOOZED_AT, 0L) <
                SNOOZE_MS)

    /**
     * "Later": the popup of [release] stays away for a day.
     */
    fun snooze(release: AppRelease) = preferences.edit {
        putLong(KEY_SNOOZED_CODE, release.versionCode)
        putLong(KEY_SNOOZED_AT, System.currentTimeMillis())
    }

    /**
     * Whether the notification of [release] was shown already, it is shown once.
     */
    fun markNotified(release: AppRelease): Boolean {
        if (preferences.getLong(KEY_NOTIFIED_CODE, -1L) == release.versionCode) {
            return false
        }
        preferences.edit { putLong(KEY_NOTIFIED_CODE, release.versionCode) }
        return true
    }

    /**
     * Download [release] and check it. Null when it failed or the file is not the published one.
     *
     * @param onProgress From 0 to 1, or null when the size is not known
     */
    suspend fun download(
        release: AppRelease,
        onProgress: (Float?) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, UPDATES_DIRECTORY).apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(directory, "Twelve-${release.versionCode}.apk")

        try {
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val digest = MessageDigest.getInstance("SHA-256")
            okHttpClient.newCall(request).executeAsync().use { response ->
                val body = response.body ?: return@withContext null
                if (!response.isSuccessful) {
                    return@withContext null
                }
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize

                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read = 0L
                        var lastReported = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) {
                                break
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            read += count

                            val percent = if (total > 0) (read * 100 / total).toInt() else -1
                            if (percent != lastReported) {
                                lastReported = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(if (total > 0) read.toFloat() / total else null)
                                }
                            }
                        }
                    }
                }
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            when (sha256 == release.sha256) {
                true -> file
                false -> {
                    file.delete()
                    null
                }
            }
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    /**
     * Whether Android lets the app install updates; when not, the user allows it once from
     * [installPermissionIntent].
     */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * Hand [apk] to the installer of Android. It asks the user to confirm, see
     * [UpdateInstallReceiver].
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun packageInfo() = context.packageManager.getPackageInfo(
        context.packageName, PackageManager.PackageInfoFlags.of(0)
    )

    companion object {
        private const val PREFERENCES_NAME = "updates"
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_SNOOZED_CODE = "snoozed_code"
        private const val KEY_SNOOZED_AT = "snoozed_at"
        private const val KEY_NOTIFIED_CODE = "notified_code"
        private const val KEY_FEED_URL = "update_feed_url"

        private const val UPDATES_DIRECTORY = "updates"
        private const val USER_AGENT = "Twelve updater"
        private const val BUFFER_SIZE = 64 * 1024

        // GitHub allows 60 requests an hour without an account, a check every few hours is plenty
        private const val MIN_CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
        private const val SNOOZE_MS = 24L * 60 * 60 * 1000
    }
}
