/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.lineageos.twelve.R
import org.lineageos.twelve.datasources.account.AccountError
import org.lineageos.twelve.datasources.account.AccountResult
import coil3.request.transformations
import org.lineageos.twelve.ui.expressive.ExpressiveShape
import org.lineageos.twelve.ui.expressive.ShapeTransformation
import org.lineageos.twelve.ext.loadThumbnail
import org.lineageos.twelve.repositories.AccountRepository

/**
 * A sentence for the user about what went wrong.
 */
fun Context.messageFor(failure: AccountResult.Failure): String = when (failure.error) {
    AccountError.INVALID_CREDENTIALS -> getString(R.string.account_error_invalid_credentials)
    AccountError.EMAIL_NOT_CONFIRMED -> getString(R.string.account_error_email_not_confirmed)
    AccountError.WEAK_PASSWORD -> getString(R.string.account_error_weak_password)
    AccountError.RATE_LIMITED -> getString(R.string.account_error_rate_limited)
    AccountError.SESSION_EXPIRED -> getString(R.string.account_error_session_expired)
    AccountError.NOT_SET_UP -> getString(R.string.account_error_not_set_up)
    AccountError.NETWORK -> getString(R.string.account_error_network)
    AccountError.UNKNOWN -> failure.message ?: getString(R.string.account_error_unknown)
}

/**
 * What the account row says: who is signed in and when the library was last synced.
 */
fun Context.accountSummary(
    state: AccountRepository.State,
    syncState: AccountRepository.SyncState,
): String = when (state) {
    AccountRepository.State.SignedOut -> getString(R.string.account_signed_out_summary)

    is AccountRepository.State.SignedIn -> {
        val who = state.name ?: state.email ?: getString(R.string.account_unknown_email)
        listOfNotNull(getString(R.string.account_signed_in_as, who), syncStatus(syncState))
            .joinToString("\n")
    }
}

/**
 * When the library was last synced, or what keeps it from being synced.
 */
fun Context.syncStatus(syncState: AccountRepository.SyncState): String? = when (syncState) {
    AccountRepository.SyncState.Idle -> null
    AccountRepository.SyncState.Syncing -> getString(R.string.account_syncing)
    is AccountRepository.SyncState.Synced -> when {
        System.currentTimeMillis() - syncState.at < DateUtils.MINUTE_IN_MILLIS ->
            getString(R.string.account_synced_now)

        else -> getString(
            R.string.account_synced,
            DateUtils.getRelativeTimeSpanString(
                syncState.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
        )
    }

    is AccountRepository.SyncState.Failed -> getString(
        R.string.account_sync_failed,
        messageFor(AccountResult.Failure(syncState.error)),
    )
}

/**
 * The name to show for an account: the one chosen, the one of GitHub, or the start of the email.
 */
fun AccountRepository.State.SignedIn.displayName(context: Context) =
    name ?: userName ?: email?.substringBefore('@')
    ?: context.getString(R.string.account_unknown_email)

/**
 * Sign in with GitHub or an email and a password, or create
 * an account. The dialog stays open on errors and says what to do.
 */
fun Fragment.showAccountSignInDialog(repository: AccountRepository) {
    val context = requireContext()
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_account_sign_in, null)

    val githubButton = view.findViewById<Button>(R.id.githubButton)
    val emailInputLayout = view.findViewById<TextInputLayout>(R.id.emailInputLayout)
    val emailEditText = view.findViewById<TextInputEditText>(R.id.emailEditText)
    val passwordInputLayout = view.findViewById<TextInputLayout>(R.id.passwordInputLayout)
    val passwordEditText = view.findViewById<TextInputEditText>(R.id.passwordEditText)
    val signInButton = view.findViewById<Button>(R.id.signInButton)
    val createAccountButton = view.findViewById<Button>(R.id.createAccountButton)
    val messageTextView = view.findViewById<TextView>(R.id.messageTextView)
    val progressIndicator = view.findViewById<LinearProgressIndicator>(R.id.progressIndicator)

    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.account_sign_in_title)
        .setView(view)
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    fun setBusy(busy: Boolean) {
        progressIndicator.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        listOf(
            signInButton,
            createAccountButton,
            githubButton,
        ).forEach { it?.isEnabled = !busy }
    }

    fun showMessage(message: String?, isError: Boolean = true) {
        messageTextView.text = message
        messageTextView.isVisible = message != null
        messageTextView.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(
                messageTextView,
                when (isError) {
                    true -> androidx.appcompat.R.attr.colorError
                    false -> androidx.appcompat.R.attr.colorPrimary
                },
            )
        )
    }

    // Both fields filled in, or nothing is sent
    fun credentials(): Pair<String, String>? {
        val email = emailEditText.text?.toString()?.trim().orEmpty()
        val password = passwordEditText.text?.toString().orEmpty()

        emailInputLayout.error = when {
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                context.getString(R.string.account_error_email_format)

            else -> null
        }
        passwordInputLayout.error = when {
            password.length < MIN_PASSWORD_LENGTH -> context.getString(
                R.string.account_password_helper
            )

            else -> null
        }

        return (email to password).takeIf {
            emailInputLayout.error == null && passwordInputLayout.error == null
        }
    }

    // Asked once the dialog shows, null until the project answered
    var providers: Set<String>? = null

    fun openBrowser(provider: String) {
        // Not switched on in the project: the browser would only show an error
        if (providers?.let { provider !in it } == true) {
            showMessage(
                context.getString(
                    R.string.account_error_provider_off,
                    context.getString(R.string.account_provider_github),
                )
            )
            return
        }

        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, repository.browserSignInUri(provider))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
            dialog.dismiss()
        } catch (e: ActivityNotFoundException) {
            showMessage(context.getString(R.string.account_error_no_browser))
        }
    }

    githubButton.setOnClickListener { openBrowser("github") }

    dialog.setOnShowListener {
        signInButton.setOnClickListener {
            val (email, password) = credentials() ?: return@setOnClickListener
            setBusy(true)
            showMessage(null)
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = repository.signIn(email, password)) {
                    is AccountResult.Success -> {
                        Toast.makeText(context, R.string.account_signed_in, Toast.LENGTH_SHORT)
                            .show()
                        dialog.dismiss()
                    }

                    is AccountResult.Failure -> {
                        setBusy(false)
                        showMessage(context.messageFor(result))
                    }
                }
            }
        }

        createAccountButton.setOnClickListener {
            val (email, password) = credentials() ?: return@setOnClickListener
            setBusy(true)
            showMessage(null)
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = repository.signUp(email, password)) {
                    is AccountResult.Success -> when (result.value) {
                        true -> {
                            Toast.makeText(
                                context, R.string.account_signed_in, Toast.LENGTH_SHORT
                            ).show()
                            dialog.dismiss()
                        }

                        // The address has to be confirmed, then sign in with the same password
                        false -> {
                            setBusy(false)
                            showMessage(
                                context.getString(R.string.account_confirm_email, email),
                                isError = false,
                            )
                        }
                    }

                    is AccountResult.Failure -> {
                        setBusy(false)
                        showMessage(context.messageFor(result))
                    }
                }
            }
        }

        // Which sign in methods the project has switched on, to explain when one is not
        viewLifecycleOwner.lifecycleScope.launch {
            providers = repository.providers().takeIf { it.isNotEmpty() }
        }
    }

    dialog.show()
}

/**
 * The signed in account: who it is, how its library is synced, and what can be done with it.
 * It follows the account while it is open.
 */
fun Fragment.showAccountDialog(repository: AccountRepository) {
    val context = requireContext()
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_account, null)

    val initialTextView = view.findViewById<TextView>(R.id.initialTextView)
    val avatarImageView = view.findViewById<ImageView>(R.id.avatarImageView)
    val nameTextView = view.findViewById<TextView>(R.id.nameTextView)
    val userNameTextView = view.findViewById<TextView>(R.id.userNameTextView)
    val emailTextView = view.findViewById<TextView>(R.id.emailTextView)
    val providerTextView = view.findViewById<TextView>(R.id.providerTextView)
    val syncStatusTextView = view.findViewById<TextView>(R.id.syncStatusTextView)
    val syncNowButton = view.findViewById<Button>(R.id.syncNowButton)
    val changeNameButton = view.findViewById<Button>(R.id.changeNameButton)
    val signOutButton = view.findViewById<Button>(R.id.signOutButton)
    val deleteAccountButton = view.findViewById<Button>(R.id.deleteAccountButton)

    // The settings theme tints images, a picture keeps its colors
    avatarImageView.imageTintList = null

    // The letter sits on the shape, the picture is cut to it
    initialTextView.background = ExpressiveShape.COOKIE_9.drawable().apply {
        setTint(
            com.google.android.material.color.MaterialColors.getColor(
                initialTextView, com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
    }

    val dialog = MaterialAlertDialogBuilder(context)
        .setView(view)
        .setNegativeButton(R.string.account_close, null)
        .create()

    var shownAvatar: String? = null

    fun bind(state: AccountRepository.State.SignedIn) {
        val displayName = state.displayName(context)
        nameTextView.text = displayName
        initialTextView.text = displayName.firstOrNull()?.uppercase().orEmpty()

        userNameTextView.text = state.userName?.let { "@$it" }
        userNameTextView.isVisible = state.userName != null
        emailTextView.text = state.email
        emailTextView.isVisible = state.email != null

        providerTextView.text = context.getString(
            when (state.provider) {
                "github" -> R.string.account_signed_in_with_github
                else -> R.string.account_signed_in_with_email
            }
        )
        providerTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            when (state.provider) {
                "github" -> R.drawable.ic_github_small
                else -> 0
            },
            0, 0, 0,
        )

        if (state.avatarUrl != shownAvatar) {
            shownAvatar = state.avatarUrl
            avatarImageView.isVisible = state.avatarUrl != null
            state.avatarUrl?.let {
                avatarImageView.loadThumbnail(android.net.Uri.parse(it)) {
                    transformations(ShapeTransformation(ExpressiveShape.COOKIE_9))
                }
            }
        }
    }

    // The account and its sync, live while the dialog is open
    val job = viewLifecycleOwner.lifecycleScope.launch {
        launch {
            repository.state.collect { state ->
                when (state) {
                    is AccountRepository.State.SignedIn -> bind(state)
                    AccountRepository.State.SignedOut -> dialog.dismiss()
                }
            }
        }
        launch {
            repository.syncState.collect { syncState ->
                syncStatusTextView.text = context.syncStatus(syncState)
                    ?: context.getString(R.string.account_sync_summary)
                syncNowButton.isEnabled = syncState != AccountRepository.SyncState.Syncing
            }
        }
    }
    dialog.setOnDismissListener { job.cancel() }

    syncNowButton.setOnClickListener { repository.requestSync() }

    changeNameButton.setOnClickListener {
        (repository.state.value as? AccountRepository.State.SignedIn)?.let {
            showChangeNameDialog(repository, it.displayName(context))
        }
    }

    signOutButton.setOnClickListener {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.signOut()
            Toast.makeText(context, R.string.account_signed_out, Toast.LENGTH_SHORT).show()
        }
    }

    deleteAccountButton.setOnClickListener { showDeleteAccountDialog(repository) }

    dialog.show()
}

private fun Fragment.showChangeNameDialog(repository: AccountRepository, current: String) {
    val context = requireContext()
    val inputLayout = TextInputLayout(
        context, null, com.google.android.material.R.attr.textInputOutlinedStyle
    ).apply {
        hint = context.getString(R.string.account_name)
        counterMaxLength = MAX_NAME_LENGTH
        isCounterEnabled = true
    }
    val editText = TextInputEditText(inputLayout.context).apply {
        setText(current)
        setSingleLine()
        setSelection(text?.length ?: 0)
    }
    inputLayout.addView(editText)

    val density = context.resources.displayMetrics.density
    val container = android.widget.FrameLayout(context).apply {
        setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        addView(inputLayout)
    }

    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.account_change_name)
        .setView(container)
        .setPositiveButton(R.string.account_save, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val name = editText.text?.toString()?.trim().orEmpty()
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
                inputLayout.error = context.getString(R.string.account_error_name)
                return@setOnClickListener
            }

            button.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                when (val result = repository.updateName(name)) {
                    is AccountResult.Success -> dialog.dismiss()
                    is AccountResult.Failure -> {
                        button.isEnabled = true
                        inputLayout.error = context.messageFor(result)
                    }
                }
            }
        }
    }

    dialog.show()
}

private fun Fragment.showDeleteAccountDialog(repository: AccountRepository) {
    val context = requireContext()

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.account_delete_title)
        .setMessage(R.string.account_delete_message)
        .setPositiveButton(R.string.account_delete_confirm) { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                val message = when (val result = repository.deleteAccount()) {
                    is AccountResult.Success -> context.getString(R.string.account_deleted)
                    is AccountResult.Failure -> context.messageFor(result)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private const val MIN_PASSWORD_LENGTH = 6
private const val MAX_NAME_LENGTH = 40
