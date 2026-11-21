/*
 * Copyright (C) 2026 Loop Habit Tracker contributors.
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without any implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.isoron.uhabits.R

/**
 * Google Sign-In through Firebase Auth, using the modern Credential Manager
 * API (androidx.credentials).
 *
 * Why Credential Manager instead of the classic GoogleSignInClient:
 * GoogleSignInClient (com.google.android.gms.auth.api.signin) is deprecated
 * for new sign-in flows and Google recommends Credential Manager, which is
 * the only supported way to request a Google ID token on current devices.
 * The androidx.credentials library backports the flow to API 26+ through
 * Google Play services (its AAR declares minSdk 23), so the app's minSdk 28
 * is fine.
 *
 * The sign-in needs the OAuth "Web client ID" of the Firebase project
 * (see res/values/firebase.xml). It must be filled in before sign-in works;
 * until then [signIn] fails fast with a clear error instead of crashing.
 */
class FirebaseAuthManager(context: Context) {

    private val appContext = context.applicationContext
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val credentialManager: CredentialManager = CredentialManager.create(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    fun isSignedIn(): Boolean = currentUser != null

    /**
     * Opens the Google account picker and signs the user in to Firebase.
     * Must be called from a coroutine running on the main thread, with the
     * foreground [Activity] (Credential Manager needs it to show its UI).
     *
     * @return the signed-in [FirebaseUser] on success, or a failure whose
     * message is safe to show to the user.
     */
    suspend fun signIn(activity: Activity): Result<FirebaseUser> {
        val webClientId = appContext.getString(R.string.google_web_client_id)
        if (webClientId.isBlank() || webClientId.startsWith("REPLACE_WITH")) {
            return Result.failure(
                IllegalStateException(
                    "Google sign-in is not configured yet: the Web client ID " +
                        "in the app settings is still a placeholder."
                )
            )
        }
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val response = credentialManager.getCredential(activity, request)
            val credential = response.credential
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Result.failure(
                    IllegalStateException("Unexpected credential type returned.")
                )
            }
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val idToken = googleCredential.idToken
            val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(firebaseCredential).await()
            val user = result.user
            if (user == null) {
                Result.failure(IllegalStateException("Firebase sign-in returned no user."))
            } else {
                Result.success(user)
            }
        } catch (e: NoCredentialException) {
            Result.failure(
                IllegalStateException(
                    "No Google account is available on this device, or sign-in " +
                        "was dismissed before choosing an account."
                )
            )
        } catch (e: GetCredentialException) {
            Result.failure(
                IllegalStateException(
                    "Google sign-in failed: ${e.message ?: "unknown error"}"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Signs the user out of Firebase and clears the saved Credential Manager
     * state, so the next sign-in shows the account picker again.
     * Local habit data is never touched by sign-out.
     */
    fun signOut() {
        auth.signOut()
        // clearCredentialState is a suspend function; run it in the
        // background. It is best-effort: the Firebase sign-out above is
        // what matters, local habit data is never touched by sign-out.
        scope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                // Ignore: the user is already signed out of Firebase.
            }
        }
    }
}
