package nl.vitaal.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object Config {
    const val SUPABASE_URL = "https://qtythirnpokfptchavgy.supabase.co"
    // Publishable key: mag in de app staan; RLS beschermt de data.
    const val SUPABASE_KEY = "sb_publishable_rjGuOZAOLKJ99uwAkP3S8A_aHkkyzGh"
    // Web client-ID uit Google Cloud (OAuth-client van het type "Webapplicatie").
    // Leeg = knop "Inloggen met Google" meldt dat het nog niet is ingesteld.
    const val GOOGLE_WEB_CLIENT_ID = "972563994932-1vuetru38odbjgq7gsp0qdfl5kgjbbkp.apps.googleusercontent.com"
}

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Kleine client voor Supabase Auth, PostgREST en Edge Functions.
 * De sessie staat in app-privé opslag; de tokens zijn versleuteld met de Android Keystore (SecureStore).
 */
class Api(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vitaal_auth", Context.MODE_PRIVATE)
    private val secure = SecureStore(prefs)

    val isSignedIn: Boolean get() = prefs.contains(REFRESH)
    val userId: String? get() = prefs.getString(USER, null)

    suspend fun signIn(email: String, password: String) {
        val res = call("POST", "/auth/v1/token?grant_type=password",
            buildJsonObject { put("email", email); put("password", password) }, auth = false)
        saveSession(res.jsonObject)
    }

    /** true = meteen ingelogd, false = eerst e-mailadres bevestigen. */
    suspend fun signUp(email: String, password: String): Boolean {
        val res = call("POST", "/auth/v1/signup",
            buildJsonObject { put("email", email); put("password", password) }, auth = false).jsonObject
        return if (res["access_token"] != null) { saveSession(res); true } else false
    }

    /** Inloggen met een Google ID-token uit Credential Manager. */
    suspend fun signInWithGoogle(idToken: String, rawNonce: String) {
        val res = call("POST", "/auth/v1/token?grant_type=id_token", buildJsonObject {
            put("provider", "google"); put("id_token", idToken); put("nonce", rawNonce)
        }, auth = false)
        saveSession(res.jsonObject)
    }

    /** Stuurt een inlogcode per e-mail (maakt zo nodig een account aan). */
    suspend fun sendLoginCode(email: String) {
        call("POST", "/auth/v1/otp", buildJsonObject { put("email", email); put("create_user", true) }, auth = false)
    }

    suspend fun sendRecovery(email: String) {
        call("POST", "/auth/v1/recover", buildJsonObject { put("email", email) }, auth = false)
    }

    suspend fun resendSignup(email: String) {
        call("POST", "/auth/v1/resend", buildJsonObject { put("type", "signup"); put("email", email) }, auth = false)
    }

    /** Controleert een 6-cijferige code. type: signup, email of recovery. */
    suspend fun verifyCode(email: String, code: String, type: String) {
        val res = call("POST", "/auth/v1/verify", buildJsonObject {
            put("type", type); put("email", email); put("token", code)
        }, auth = false)
        saveSession(res.jsonObject)
    }

    suspend fun updatePassword(password: String) {
        call("PUT", "/auth/v1/user", buildJsonObject { put("password", password) })
    }

    fun signOut() = prefs.edit().clear().apply()

    suspend fun rpc(name: String, body: JsonObject): JsonElement = call("POST", "/rest/v1/rpc/$name", body)
    suspend fun patch(path: String, body: JsonObject) { call("PATCH", "/rest/v1/$path", body, prefer = "return=minimal") }
    suspend fun upsert(table: String, body: JsonElement) {
        call("POST", "/rest/v1/$table", body, prefer = "resolution=merge-duplicates,return=minimal")
    }
    suspend fun function(name: String, body: JsonObject = JsonObject(emptyMap())): JsonObject =
        call("POST", "/functions/v1/$name", body).let { if (it is JsonObject) it else JsonObject(emptyMap()) }

    private suspend fun accessToken(): String = lock.withLock {
        val current = secure.get(ACCESS)
        if (current != null && System.currentTimeMillis() / 1000 < prefs.getLong(EXPIRES, 0) - 60) {
            return@withLock current
        }
        val refresh = secure.get(REFRESH) ?: run { signOut(); throw ApiException(401, "Je bent uitgelogd. Log opnieuw in.") }
        try {
            val res = call("POST", "/auth/v1/token?grant_type=refresh_token",
                buildJsonObject { put("refresh_token", refresh) }, auth = false)
            saveSession(res.jsonObject)
        } catch (e: ApiException) {
            if (e.code == 400 || e.code == 401) signOut()
            throw e
        }
        secure.get(ACCESS) ?: throw ApiException(401, "Niet ingelogd")
    }

    private suspend fun call(
        method: String,
        path: String,
        body: JsonElement?,
        auth: Boolean = true,
        prefer: String? = null,
    ): JsonElement {
        val token = if (auth) accessToken() else null
        return withContext(Dispatchers.IO) {
            val b = Request.Builder()
                .url(Config.SUPABASE_URL + path)
                .header("apikey", Config.SUPABASE_KEY)
            if (token != null) b.header("Authorization", "Bearer $token")
            if (prefer != null) b.header("Prefer", prefer)
            val rb = (body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON)
            b.method(method, if (method == "GET") null else rb)
            http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, errorText(text, r.code))
                if (text.isBlank()) JsonNull else Json.parseToJsonElement(text)
            }
        }
    }

    private fun errorText(text: String, code: Int): String = try {
        val o = Json.parseToJsonElement(text).jsonObject
        listOf("msg", "message", "error_description", "error")
            .firstNotNullOfOrNull { o[it]?.jsonPrimitive?.content } ?: "Fout $code"
    } catch (_: Exception) {
        "Fout $code"
    }

    private fun saveSession(o: JsonObject) {
        val expiresAt = o["expires_at"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() / 1000 + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600))
        secure.put(ACCESS, o["access_token"]!!.jsonPrimitive.content)
        secure.put(REFRESH, o["refresh_token"]!!.jsonPrimitive.content)
        prefs.edit()
            .putLong(EXPIRES, expiresAt)
            .putString(USER, o["user"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            .apply()
    }

    companion object {
        private const val ACCESS = "access"
        private const val REFRESH = "refresh"
        private const val EXPIRES = "expires_at"
        private const val USER = "user_id"
        private val JSON = "application/json".toMediaType()
        private val lock = Mutex()
        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
