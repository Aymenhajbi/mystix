package ma.mystix.atelys.mobile

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Result of one call. [Failure.retryable] is true when the server was not reached or did not answer. */
sealed interface ApiResult {
    data class Ok(val status: Int, val body: JSONObject) : ApiResult
    data class Failure(val status: Int?, val errorCode: String, val retryable: Boolean) : ApiResult
}

/**
 * Minimal client of the Mystix API (HttpURLConnection, org.json: no extra dependency). The session token is kept in
 * the private preferences of the app and never written to a log. TODO(lot E): encrypted storage (Android Keystore).
 */
class ApiClient(context: Context) {
    private val prefs = context.getSharedPreferences("atelys.session", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("serverUrl", null) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString("serverUrl", value.trim().trimEnd('/')).apply()

    val signedInAs: String? get() = prefs.getString("email", null)?.takeIf { prefs.contains("token") }

    fun signIn(email: String, password: CharArray): ApiResult {
        val body = JSONObject().put("email", email.trim()).put("password", String(password))
        password.fill(' ')
        val result = call("POST", "/api/v1/auth/login", body, authenticated = false)
        if (result is ApiResult.Ok) {
            prefs.edit().putString("token", result.body.getString("token")).putString("email", email.trim()).apply()
        }
        return result
    }

    fun signOut() {
        runCatching { call("POST", "/api/v1/auth/logout", JSONObject(), authenticated = true) }
        prefs.edit().remove("token").remove("email").apply()
    }

    fun call(method: String, path: String, body: JSONObject?, authenticated: Boolean = true): ApiResult {
        val token = prefs.getString("token", null)
        if (authenticated && token == null) return ApiResult.Failure(401, "AUTH_REQUIRED", retryable = false)
        val connection = try {
            URL(serverUrl + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return ApiResult.Failure(null, "SERVER_URL_INVALID", retryable = false)
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = text.takeIf { it.trimStart().startsWith("{") }?.let { JSONObject(it) } ?: JSONObject()
            if (status in 200..299) {
                ApiResult.Ok(status, json)
            } else {
                if (status == 401) prefs.edit().remove("token").apply()
                ApiResult.Failure(status, json.optString("errorCode", "HTTP_$status"),
                    retryable = status == 429 || status >= 500)
            }
        } catch (e: IOException) {
            // Timeout or network cut: the outcome is unknown, the caller keeps the operation and retries.
            ApiResult.Failure(null, "NETWORK_UNAVAILABLE", retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Emulator address of the developer's machine; changed on the terminal from the diagnostic screen. */
        const val DEFAULT_SERVER = "http://10.0.2.2:8080"
    }
}
