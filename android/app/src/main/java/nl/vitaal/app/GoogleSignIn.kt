package nl.vitaal.app

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

/**
 * Inloggen met Google via Credential Manager.
 * Google krijgt de SHA-256 van de nonce; Supabase krijgt de ruwe nonce en controleert de hash in het ID-token.
 */
object GoogleSignIn {
    class Cancelled : Exception("Inloggen met Google afgebroken")

    /** Geeft (idToken, rawNonce) terug. */
    suspend fun idToken(activity: Activity): Pair<String, String> {
        if (Config.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            throw IllegalStateException("Inloggen met Google is nog niet ingesteld (GOOGLE_WEB_CLIENT_ID in Api.kt).")
        }
        val rawNonce = UUID.randomUUID().toString()
        val hashed = MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(Config.GOOGLE_WEB_CLIENT_ID)
            .setNonce(hashed)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            throw Cancelled()
        } catch (e: NoCredentialException) {
            throw IllegalStateException("Geen Google-account gevonden op dit toestel.")
        }

        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken to rawNonce
        }
        throw IllegalStateException("Onverwacht type inloggegevens van Google.")
    }

    /** Bij uitloggen: zodat de volgende keer weer een account gekozen kan worden. */
    suspend fun clear(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
        }
    }
}
