/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.lineageos.twelve.datasources.account.AccountResult
import org.lineageos.twelve.ui.account.messageFor

/**
 * Where the browser comes back to after signing in with GitHub. It has nothing to show:
 * the sign in is finished in the background and the screen below, the settings, follows it.
 */
class AccountCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val application = applicationContext as TwelveApplication

        if (uri != null) {
            MainScope().launch {
                val message = when (val result =
                    application.accountRepository.completeBrowserSignIn(uri)) {
                    is AccountResult.Success -> application.getString(R.string.account_signed_in)
                    is AccountResult.Failure -> application.messageFor(result)
                }
                Toast.makeText(application, message, Toast.LENGTH_LONG).show()
            }
        }

        finish()
    }
}
