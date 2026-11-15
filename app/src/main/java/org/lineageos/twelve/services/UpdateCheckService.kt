/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lineageos.twelve.MainActivity
import org.lineageos.twelve.R
import org.lineageos.twelve.TwelveApplication
import org.lineageos.twelve.datasources.update.AppRelease
import org.lineageos.twelve.repositories.UpdateRepository

/**
 * Looks for an update about once a day, when there is a network, and tells about it with a
 * notification.
 */
class UpdateCheckService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val repository = (application as TwelveApplication).updateRepository
        if (!repository.autoCheck) {
            return false
        }

        job = scope.launch {
            val result = repository.check(force = true)
            if (result is UpdateRepository.CheckResult.Available &&
                repository.markNotified(result.release)
            ) {
                notify(this@UpdateCheckService, result.release)
            }
            jobFinished(params, result == UpdateRepository.CheckResult.Failed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 0x7e1e
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 0x7e1e
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /**
         * Extra of the intent of the notification: show the popup of the update.
         */
        const val EXTRA_SHOW_UPDATE = "org.lineageos.twelve.extra.SHOW_UPDATE"

        /**
         * Check every day from now on, or stop when [enabled] is false.
         */
        fun schedule(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return

            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) {
                return
            }

            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(CHECK_INTERVAL_MS)
                    .setPersisted(true)
                    .build()
            )
        }

        fun notify(context: Context, release: AppRelease) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.update_channel_description)
                }
            )

            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentTitle(
                    context.getString(R.string.update_notification_title, release.versionName)
                )
                .setContentText(context.getString(R.string.update_notification_text))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        release.changelog.ifBlank {
                            context.getString(R.string.update_notification_text)
                        }
                    )
                )
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setAutoCancel(true)
                .build()

            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
