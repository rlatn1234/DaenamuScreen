package dev.kimsu.daenamutouchphone.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val TAG = "XPTouch/Cloud"

/**
 * Wraps the Bambu Cloud REST API for authentication and device listing.
 *
 * Reference: ha-bambulab (https://github.com/greghesp/ha-bambulab)
 * Endpoints differ by region:
 *   Global → https://api.bambulab.com  / https://bambulab.com
 *   China  → https://api.bambulab.cn   / https://bambulab.cn
 *
 * Login flow (mirrors bambu_cloud.py):
 *  1. POST /v1/user-service/user/login {account, password, apiError:""}
 *     → accessToken present          : direct success  → [LoginResult.Success]
 *     → loginType == "verifyCode"    : email code required → [LoginResult.RequiresEmailCode]
 *     → loginType == "tfa"  + tfaKey : TOTP required       → [LoginResult.RequiresTfa]
 *  2a. (verifyCode) POST /v1/user-service/user/sendemail/code {email, type:"codeLogin"}
 *      then POST /v1/user-service/user/login {account, code}
 *  2b. (tfa) POST /api/sign-in/tfa {tfaKey, tfaCode}
 */
class BambuCloudApi(private val region: String = "Global") {

    private val apiBaseUrl: String
        get() = if (region == "China") "https://api.bambulab.cn" else "https://api.bambulab.com"

    /** Web base URL used for TFA endpoint (not the API subdomain). */
    private val webBaseUrl: String
        get() = if (region == "China") "https://bambulab.cn" else "https://bambulab.com"

    private val httpClient = OkHttpClient()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Standard headers required by the Bambu Cloud API.
     * Without these (especially User-Agent and the X-BBL-* fields) the request is
     * treated as an unknown client and Cloudflare may return HTTP 403, or the API
     * returns a response that has neither `accessToken` nor `loginType` — both of
     * which prevent the 2FA flow from starting.
     * Source: https://github.com/greghesp/ha-bambulab
     */
    private fun bambuHeaders(): Map<String, String> = mapOf(
        "User-Agent"            to "bambu_network_agent/01.09.05.01",
        "X-BBL-Client-Name"     to "OrcaSlicer",
        "X-BBL-Client-Type"     to "slicer",
        "X-BBL-Client-Version"  to "01.09.05.51",
        "X-BBL-Language"        to "en-US",
        "X-BBL-OS-Type"         to "linux",
        "X-BBL-OS-Version"      to "6.2.0",
        "X-BBL-Agent-Version"   to "01.09.05.01",
        "X-BBL-Agent-OS-Type"   to "linux",
        "Accept"                to "application/json",
    )

    /** Apply [bambuHeaders] to a [Request.Builder]. */
    private fun Request.Builder.addBambuHeaders(): Request.Builder = apply {
        bambuHeaders().forEach { (k, v) -> header(k, v) }
    }

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Initiate login with email and password.
     * Returns a [LoginResult] indicating either success or which second factor is needed.
     */
    fun login(email: String, password: String): LoginResult {
        // apiError field is required by Bambu Cloud (matches ha-bambulab reference)
        val body = JsonObject().apply {
            addProperty("account", email)
            addProperty("password", password)
            addProperty("apiError", "")
        }
        val req = Request.Builder()
            .url("$apiBaseUrl/v1/user-service/user/login")
            .addBambuHeaders()
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                val bodyText = resp.body?.string() ?: ""
                if (!resp.isSuccessful && resp.code != 400) {
                    Log.w(TAG, "login failed: HTTP ${resp.code}")
                    return LoginResult.Error("Login failed (HTTP ${resp.code})")
                }
                val root = runCatching { JsonParser.parseString(bodyText).asJsonObject }.getOrNull()
                    ?: return LoginResult.Error("Unexpected server response")

                val accessToken = root.get("accessToken")?.asString
                if (!accessToken.isNullOrBlank()) {
                    return LoginResult.Success(accessToken)
                }

                when (root.get("loginType")?.asString) {
                    "verifyCode" -> LoginResult.RequiresEmailCode
                    "tfa" -> {
                        val tfaKey = root.get("tfaKey")?.asString ?: ""
                        LoginResult.RequiresTfa(tfaKey)
                    }
                    else -> {
                        Log.w(TAG, "Unknown login response: $bodyText")
                        LoginResult.Error("Login failed. Check email / password.")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "login IO error: ${e.message}")
            LoginResult.Error("Network error: ${e.message}")
        }
    }

    /**
     * Request a new email verification code to be sent to [email].
     * Call this before presenting the code-entry UI for [LoginResult.RequiresEmailCode].
     * @return true on success.
     */
    fun requestEmailCode(email: String): Boolean {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("type", "codeLogin")
        }
        val req = Request.Builder()
            .url("$apiBaseUrl/v1/user-service/user/sendemail/code")
            .addBambuHeaders()
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                Log.d(TAG, "requestEmailCode: HTTP ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: IOException) {
            Log.e(TAG, "requestEmailCode IO error: ${e.message}")
            false
        }
    }

    /**
     * Verify the email verification code submitted by the user.
     * @return [LoginResult.Success] on success, or an appropriate error.
     */
    fun verifyEmailCode(email: String, code: String): LoginResult {
        val body = JsonObject().apply {
            addProperty("account", email)
            addProperty("code", code)
        }
        val req = Request.Builder()
            .url("$apiBaseUrl/v1/user-service/user/login")
            .addBambuHeaders()
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                val bodyText = resp.body?.string() ?: ""
                val root = runCatching { JsonParser.parseString(bodyText).asJsonObject }.getOrNull()

                if (resp.code == 400 && root != null) {
                    return when (root.get("code")?.asInt) {
                        1 -> LoginResult.CodeExpired
                        2 -> LoginResult.CodeIncorrect
                        else -> LoginResult.Error("Verification failed (code ${root.get("code")?.asInt})")
                    }
                }
                if (!resp.isSuccessful) {
                    return LoginResult.Error("Verification failed (HTTP ${resp.code})")
                }
                val accessToken = root?.get("accessToken")?.asString
                if (!accessToken.isNullOrBlank()) {
                    LoginResult.Success(accessToken)
                } else {
                    LoginResult.Error("No token in response")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "verifyEmailCode IO error: ${e.message}")
            LoginResult.Error("Network error: ${e.message}")
        }
    }

    /**
     * Verify a TOTP / authenticator-app 2FA code.
     * [tfaKey] is the value returned in [LoginResult.RequiresTfa].
     * @return [LoginResult.Success] on success, or an appropriate error.
     *
     * Note: The TFA endpoint returns the token as a **cookie** named "token",
     * so we extract it from the Set-Cookie response header.
     */
    fun verifyTfaCode(tfaKey: String, tfaCode: String): LoginResult {
        val body = JsonObject().apply {
            addProperty("tfaKey", tfaKey)
            addProperty("tfaCode", tfaCode)
        }
        val req = Request.Builder()
            .url("$webBaseUrl/api/sign-in/tfa")
            .addBambuHeaders()
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                // Read the body once upfront
                val bodyText = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.w(TAG, "verifyTfaCode failed: HTTP ${resp.code}")
                    return LoginResult.Error("2FA verification failed (HTTP ${resp.code})")
                }
                // The TFA endpoint sets the auth token as the "token" cookie
                val setCookie = resp.headers("Set-Cookie")
                val tokenFromCookie = setCookie
                    .flatMap { it.split(";") }
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("token=") }
                    ?.removePrefix("token=")
                if (!tokenFromCookie.isNullOrBlank()) {
                    return LoginResult.Success(tokenFromCookie)
                }
                // Some server versions return it in the body JSON instead
                val tokenFromBody = runCatching {
                    JsonParser.parseString(bodyText).asJsonObject.get("accessToken")?.asString
                }.getOrNull()
                if (!tokenFromBody.isNullOrBlank()) {
                    LoginResult.Success(tokenFromBody)
                } else {
                    LoginResult.Error("2FA succeeded but no token received")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "verifyTfaCode IO error: ${e.message}")
            LoginResult.Error("Network error: ${e.message}")
        }
    }

    // ── Device list / account details ─────────────────────────────────────────

    /**
     * Fetch the MQTT username for the authenticated account via the Preference API.
     * Used as a fallback when the auth token is NOT a JWT (e.g. a cookie token from
     * the TFA flow), so the username cannot be decoded from the token payload.
     *
     * Reference: ha-bambulab `_get_username_from_authentication_token()` non-JWT path.
     * Endpoint: GET /v1/design-user-service/my/preference  → { "uid": "12345678", … }
     * MQTT username = "u_{uid}"
     *
     * @return "u_{uid}" on success, "" on failure.
     */
    fun fetchMqttUsername(authToken: String): String {
        val req = Request.Builder()
            .url("$apiBaseUrl/v1/design-user-service/my/preference")
            .addBambuHeaders()
            .header("Authorization", "Bearer $authToken")
            .get()
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchMqttUsername failed: HTTP ${resp.code}")
                    return ""
                }
                val uid = runCatching {
                    JsonParser.parseString(resp.body?.string() ?: "")
                        .asJsonObject.get("uid")?.asString
                }.getOrElse {
                    Log.w(TAG, "fetchMqttUsername: malformed JSON response: ${it.message}")
                    null
                } ?: run {
                    Log.w(TAG, "fetchMqttUsername: 'uid' field missing in response")
                    return ""
                }
                "u_$uid"
            }
        } catch (e: IOException) {
            Log.e(TAG, "fetchMqttUsername IO error: ${e.message}")
            ""
        }
    }

    /**
     * Fetch the list of printers bound to the account.
     * @return List of [CloudPrinter] on success, or empty list on failure.
     */
    fun getDeviceList(authToken: String): List<CloudPrinter> {
        val req = Request.Builder()
            .url("$apiBaseUrl/v1/iot-service/api/user/bind")
            .addBambuHeaders()
            .header("Authorization", "Bearer $authToken")
            .get()
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getDeviceList failed: HTTP ${resp.code}")
                    return emptyList()
                }
                val root = JsonParser.parseString(resp.body?.string()).asJsonObject
                val devicesArray = root.getAsJsonArray("devices") ?: return emptyList()
                devicesArray.mapNotNull { elem ->
                    val obj = elem.asJsonObject
                    CloudPrinter(
                        serialNumber = obj.get("dev_id")?.asString ?: return@mapNotNull null,
                        name = obj.get("name")?.asString ?: "",
                        model = obj.get("dev_model_name")?.asString ?: "",
                        accessCode = obj.get("dev_access_code")?.asString ?: "",
                    )
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "getDeviceList IO error: ${e.message}")
            emptyList()
        }
    }
}

// ── Result types ─────────────────────────────────────────────────────────────

sealed class LoginResult {
    /** Login succeeded; [token] is the Bearer auth token. */
    data class Success(val token: String) : LoginResult()
    /** Server requires an email verification code before continuing. */
    data object RequiresEmailCode : LoginResult()
    /** Server requires a TOTP / authenticator-app code; [tfaKey] must be passed back. */
    data class RequiresTfa(val tfaKey: String) : LoginResult()
    /** The submitted code has expired — a new code was automatically requested. */
    data object CodeExpired : LoginResult()
    /** The submitted code was incorrect. */
    data object CodeIncorrect : LoginResult()
    /** A non-recoverable error occurred. */
    data class Error(val message: String) : LoginResult()
}

data class CloudPrinter(
    val serialNumber: String,
    val name: String,
    val model: String,
    val accessCode: String,
)


