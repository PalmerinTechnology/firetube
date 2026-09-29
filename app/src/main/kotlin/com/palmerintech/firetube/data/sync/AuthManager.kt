package com.palmerintech.firetube.data.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/** Optional Google sign-in (via Firebase Auth), used only to sync the library between devices. */
class AuthManager(context: Context) {

    /**
     * The OAuth web client id the google-services plugin generates once Google sign-in is enabled
     * in the Firebase console. Without it, sign-in is hidden.
     */
    @SuppressLint("DiscouragedApi")
    private val webClientId: String? = context.resources
        .getIdentifier("default_web_client_id", "string", context.packageName)
        .takeIf { it != 0 }
        ?.let(context::getString)

    /** False in builds without google-services.json (e.g. forks): no Firebase at all. */
    private val firebaseConfigured = FirebaseApp.getApps(context).isNotEmpty()

    val available: Boolean get() = firebaseConfigured && webClientId != null

    private val auth by lazy { Firebase.auth }
    private val _user = MutableStateFlow(if (firebaseConfigured) auth.currentUser else null)
    val user: StateFlow<FirebaseUser?> = _user

    init {
        if (firebaseConfigured) auth.addAuthStateListener { _user.value = it.currentUser }
    }

    /** Must be called with an Activity context (Credential Manager shows UI). */
    suspend fun signIn(activityContext: Context) {
        val clientId = checkNotNull(webClientId) { "Google sign-in isn't configured" }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: NoCredentialException) {
            throw IllegalStateException("No Google account on this device", e)
        }
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type"
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    suspend fun signOut(context: Context) {
        if (!firebaseConfigured) return
        auth.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
