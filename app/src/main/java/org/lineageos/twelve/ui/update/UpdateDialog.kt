/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.datasources.update.AppRelease
import org.lineageos.twelve.repositories.UpdateRepository
import org.lineageos.twelve.ui.expressive.ExpressiveShape
import kotlin.math.roundToInt

/**
 * The popup of an update: what is new, then download and install it, or later. When Android has
 * to allow the app to install updates first, its settings open and the user taps the button
 * again once back.
 */
fun <T> T.showUpdateDialog(
    repository: UpdateRepository,
    release: AppRelease,
) where T : Activity, T : LifecycleOwner {
    val view = LayoutInflater.from(this).inflate(R.layout.dialog_update, null)

    val shapeImageView = view.findViewById<ImageView>(R.id.shapeImageView)
    val arrowImageView = view.findViewById<ImageView>(R.id.arrowImageView)
    val versionTextView = view.findViewById<TextView>(R.id.versionTextView)
    val changelogTextView = view.findViewById<TextView>(R.id.changelogTextView)
    val progressLayout = view.findViewById<android.view.View>(R.id.progressLayout)
    val progressIndicator = view.findViewById<LinearProgressIndicator>(R.id.progressIndicator)
    val progressTextView = view.findViewById<TextView>(R.id.progressTextView)
    val errorTextView = view.findViewById<TextView>(R.id.errorTextView)
    val updateButton = view.findViewById<Button>(R.id.updateButton)
    val laterButton = view.findViewById<Button>(R.id.laterButton)

    shapeImageView.setImageDrawable(ExpressiveShape.SUNNY.drawable())
    shapeImageView.imageTintList = ColorStateList.valueOf(
        MaterialColors.getColor(shapeImageView, com.google.android.material.R.attr.colorPrimaryContainer)
    )
    arrowImageView.imageTintList = ColorStateList.valueOf(
        MaterialColors.getColor(
            arrowImageView, com.google.android.material.R.attr.colorOnPrimaryContainer
        )
    )

    versionTextView.text = listOfNotNull(
        getString(R.string.update_version_change, repository.installedVersionName, release.versionName),
        release.apkSize.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(this, it) },
    ).joinToString(" · ")
    changelogTextView.text = release.changelog.ifBlank { getString(R.string.update_no_changelog) }

    val dialog = MaterialAlertDialogBuilder(this)
        .setView(view)
        .setCancelable(true)
        .create()

    var downloadJob: Job? = null

    fun showError(message: String?) {
        errorTextView.text = message
        errorTextView.isVisible = message != null
    }

    fun setDownloading(downloading: Boolean) {
        progressLayout.isVisible = downloading
        updateButton.isEnabled = !downloading
        laterButton.setText(if (downloading) android.R.string.cancel else R.string.update_later)
    }

    updateButton.setOnClickListener {
        showError(null)

        // Android asks once whether the app may install updates
        if (!repository.canInstall()) {
            Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            try {
                startActivity(repository.installPermissionIntent())
            } catch (e: ActivityNotFoundException) {
                showError(getString(R.string.update_install_failed, ""))
            }
            return@setOnClickListener
        }

        setDownloading(true)
        progressIndicator.isIndeterminate = true
        progressTextView.setText(R.string.update_downloading)

        downloadJob = lifecycleScope.launch {
            val apk = repository.download(release) { progress ->
                if (progress != null) {
                    if (progressIndicator.isIndeterminate) {
                        progressIndicator.isIndeterminate = false
                    }
                    val percent = (progress * 100).roundToInt()
                    progressIndicator.setProgressCompat(percent, true)
                    progressTextView.text = getString(R.string.update_downloading_percent, percent)
                }
            }

            if (apk == null) {
                setDownloading(false)
                showError(getString(R.string.update_download_failed))
                return@launch
            }

            progressIndicator.isIndeterminate = true
            progressTextView.setText(R.string.update_installing)
            try {
                repository.install(apk)
                dialog.dismiss()
            } catch (e: Exception) {
                setDownloading(false)
                showError(getString(R.string.update_install_failed, e.message.orEmpty()))
            }
        }
    }

    laterButton.setOnClickListener {
        when (val job = downloadJob?.takeIf { it.isActive }) {
            null -> {
                repository.snooze(release)
                dialog.dismiss()
            }

            else -> {
                job.cancel()
                setDownloading(false)
            }
        }
    }

    dialog.setOnCancelListener { repository.snooze(release) }
    dialog.setOnDismissListener { downloadJob?.cancel() }

    dialog.show()
}
