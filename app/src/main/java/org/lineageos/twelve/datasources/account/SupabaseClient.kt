/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.twelve.datasources.account

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.lineageos.twelve.ext.executeAsync
import java.io.IOException

/**
 * A signed in account. [expiresAt] is in seconds, like the server gives it.
 */
@Serializable
data class AccountSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String?,
    val provider: String?,
    // From the profile of the account, GitHub fills them in
    val name: String? = null,
    val userName: String? = null,
    val avatarUrl: String? = null,
)

enum class AccountError {
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    WEAK_PASSWORD,
    RATE_LIMITED,
    SESSION_EXPIRED,
    NOT_SET_UP,
    NETWORK,
    UNKNOWN,
}

sealed interface AccountResult<out T> {
    data class Success<T>(val value: T) : AccountResult<T>
    data class Failure(val error: AccountError, val message: String? = null) : AccountResult<Nothing>
}

/**
 * The Supabase project the accounts live in: sign in (password, or GitHub in the
 * browser with PKCE) and the row of the account in the twelve_library table.
 *
 * @param url The project URL
 * @param key The publishable key of the project, meant to be in apps: what an account can reach
 *   is decided by the row level security of the database
 */
class SupabaseClient(
    private val okHttpClient: OkHttpClient,
    private val url: String,
    private val key: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * The sign in providers turned on in the project, for example "email" and "github".
     */
    suspend fun providers(): AccountResult<Set<String>> = call(
        Request.Builder().url("$url/auth/v1/settings").get()
    ) { body ->
        ((body as? JsonObject)?.get("external") as? JsonObject)
            ?.filterValues { (it as? JsonPrimitive)?.content == "true" }
            ?.keys
            .orEmpty()
    }

    /**
     * Create an account. The session is null when the address has to be confirmed first.
     */
    suspend fun signUp(email: String, password: String): AccountResult<AccountSession?> = call(
        Request.Builder().url("$url/auth/v1/signup").post(
            buildJsonObject {
                put("email", email)
                put("password", password)
            }.toBody()
        )
    ) { body -> session(body) }

    suspend fun signIn(email: String, password: String): AccountResult<AccountSession> =
        callForSession(
            Request.Builder().url("$url/auth/v1/token?grant_type=password").post(
                buildJsonObject {
                    put("email", email)
                    put("password", password)
                }.toBody()
            )
        )

    suspend fun refresh(refreshToken: String): AccountResult<AccountSession> = callForSession(
        Request.Builder().url("$url/auth/v1/token?grant_type=refresh_token").post(
            buildJsonObject { put("refresh_token", refreshToken) }.toBody()
        )
    )

    /**
     * Where the browser goes to sign in with [provider]; it comes back to [redirectTo] with a
     * code, see [exchangeCode].
     */
    fun authorizeUri(provider: String, redirectTo: String, codeChallenge: String): Uri =
        Uri.parse("$url/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", provider)
            .appendQueryParameter("redirect_to", redirectTo)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build()

    suspend fun exchangeCode(code: String, codeVerifier: String): AccountResult<AccountSession> =
        callForSession(
            Request.Builder().url("$url/auth/v1/token?grant_type=pkce").post(
                buildJsonObject {
                    put("auth_code", code)
                    put("code_verifier", codeVerifier)
                }.toBody()
            )
        )

    suspend fun signOut(session: AccountSession): AccountResult<Unit> = call(
        Request.Builder().url("$url/auth/v1/logout").post("{}".toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer ${session.accessToken}")
    ) { }

    /**
     * The library kept for the account, null when there is none yet.
     */
    suspend fun readLibrary(session: AccountSession): AccountResult<SyncDocument?> = call(
        Request.Builder()
            .url("$url/rest/v1/twelve_library?select=data&user_id=eq.${session.userId}")
            .get()
            .header("Authorization", "Bearer ${session.accessToken}")
    ) { body ->
        ((body as? JsonArray)?.firstOrNull() as? JsonObject)?.get("data")?.let {
            json.decodeFromJsonElement(serializer<SyncDocument>(), it)
        }
    }

    suspend fun writeLibrary(session: AccountSession, document: SyncDocument): AccountResult<Unit> =
        call(
            Request.Builder()
                .url("$url/rest/v1/twelve_library?on_conflict=user_id")
                .post(
                    buildJsonObject {
                        put("user_id", session.userId)
                        put("data", json.encodeToJsonElement(serializer<SyncDocument>(), document))
                    }.toBody()
                )
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
        ) { }

    /**
     * Delete the account and everything kept for it.
     */
    suspend fun deleteAccount(session: AccountSession): AccountResult<Unit> = call(
        Request.Builder().url("$url/rest/v1/rpc/delete_my_account")
            .post("{}".toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer ${session.accessToken}")
    ) { }

    private suspend fun callForSession(request: Request.Builder): AccountResult<AccountSession> =
        when (val result = call(request) { session(it) }) {
            is AccountResult.Success -> result.value?.let { AccountResult.Success(it) }
                ?: AccountResult.Failure(AccountError.UNKNOWN)

            is AccountResult.Failure -> result
        }

    private fun session(body: JsonElement?): AccountSession? {
        val obj = body as? JsonObject ?: return null
        val accessToken = obj.string("access_token") ?: return null
        val refreshToken = obj.string("refresh_token") ?: return null
        val user = obj["user"] as? JsonObject ?: return null

        return AccountSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = obj.string("expires_at")?.toLongOrNull()
                ?: (System.currentTimeMillis() / 1000 +
                        (obj.string("expires_in")?.toLongOrNull() ?: 3600)),
            userId = user.string("id") ?: return null,
            email = null,
            provider = null,
        ).withUser(user)
    }

    /**
     * [this] with what the user object of the server says about the account.
     */
    private fun AccountSession.withUser(user: JsonObject): AccountSession {
        val metadata = user["user_metadata"] as? JsonObject

        return copy(
            email = user.string("email") ?: email,
            provider = (user["app_metadata"] as? JsonObject)?.string("provider") ?: provider,
            // The name chosen in the app wins over the one of the provider
            name = metadata?.let {
                it.string(NAME_KEY) ?: it.string("full_name") ?: it.string("name")
            }?.takeIf { it.isNotBlank() },
            userName = metadata?.let {
                it.string("user_name") ?: it.string("preferred_username")
            }?.takeIf { it.isNotBlank() },
            avatarUrl = metadata?.let { it.string("avatar_url") ?: it.string("picture") },
        )
    }

    /**
     * The account as the server knows it now: its name, picture and email.
     */
    suspend fun user(session: AccountSession): AccountResult<AccountSession> = call(
        Request.Builder().url("$url/auth/v1/user").get()
            .header("Authorization", "Bearer ${session.accessToken}")
    ) { body -> (body as? JsonObject)?.let { session.withUser(it) } ?: session }

    /**
     * Change the name shown for the account.
     */
    suspend fun updateName(session: AccountSession, name: String): AccountResult<AccountSession> =
        call(
            Request.Builder().url("$url/auth/v1/user").put(
                buildJsonObject {
                    put("data", buildJsonObject { put(NAME_KEY, name) })
                }.toBody()
            ).header("Authorization", "Bearer ${session.accessToken}")
        ) { body -> (body as? JsonObject)?.let { session.withUser(it) } ?: session }

    private suspend fun <T> call(
        builder: Request.Builder,
        parse: (JsonElement?) -> T,
    ): AccountResult<T> {
        val request = builder
            .header("apikey", key)
            .header("Accept", "application/json")
            .build()

        return try {
            okHttpClient.newCall(request).executeAsync().use { response ->
                val text = response.body?.string().orEmpty()
                val body = text.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) }

                when (response.isSuccessful) {
                    true -> AccountResult.Success(parse(body))
                    false -> AccountResult.Failure(error(response.code, body), message(body))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AccountResult.Failure(AccountError.NETWORK, e.message)
        } catch (e: SerializationException) {
            AccountResult.Failure(AccountError.UNKNOWN, e.message)
        } catch (e: IllegalArgumentException) {
            AccountResult.Failure(AccountError.UNKNOWN, e.message)
        }
    }

    private fun error(code: Int, body: JsonElement?): AccountError {
        val obj = body as? JsonObject
        val errorCode = obj?.string("error_code") ?: obj?.string("error") ?: obj?.string("code")

        return when {
            errorCode == "invalid_credentials" || errorCode == "invalid_grant" ->
                AccountError.INVALID_CREDENTIALS

            errorCode == "email_not_confirmed" -> AccountError.EMAIL_NOT_CONFIRMED
            errorCode == "weak_password" -> AccountError.WEAK_PASSWORD
            errorCode?.startsWith("over_") == true || code == 429 -> AccountError.RATE_LIMITED
            errorCode == "refresh_token_not_found" || errorCode == "session_not_found" ||
                    errorCode == "refresh_token_already_used" || code == 401 ->
                AccountError.SESSION_EXPIRED

            // The table or the function of schema.sql are missing
            errorCode == "PGRST205" || errorCode == "PGRST202" || code == 404 ->
                AccountError.NOT_SET_UP

            else -> AccountError.UNKNOWN
        }
    }

    private fun message(body: JsonElement?) = (body as? JsonObject)?.let {
        it.string("msg") ?: it.string("message") ?: it.string("error_description")
    }

    private fun JsonObject.toBody() = toString().toRequestBody(jsonMediaType)

    private companion object {
        // Kept apart from the name of the provider, which a sign in with it would replace
        private const val NAME_KEY = "twelve_name"
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString || it.content != "null" }
        ?.content
}
