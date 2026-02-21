package dev.kimsu.daenamutouchphone.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import dev.kimsu.daenamutouchphone.data.model.AppSettings
import dev.kimsu.daenamutouchphone.data.model.PrinterStatus
import dev.kimsu.daenamutouchphone.data.repository.SettingsRepository
import dev.kimsu.daenamutouchphone.network.BambuCloudApi
import dev.kimsu.daenamutouchphone.network.BambuMqttService
import dev.kimsu.daenamutouchphone.network.CloudPrinter
import dev.kimsu.daenamutouchphone.network.ConnectionState
import dev.kimsu.daenamutouchphone.network.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "XPTouch/ViewModel"

/**
 * Central ViewModel that binds the UI to the MQTT service and settings repository.
 *
 * Bambu Cloud login flow (mirrors ha-bambulab / bambu_cloud.py):
 *  1. [cloudLogin]              → email + password → may require 2nd factor
 *  2a. [submitEmailCode]        → 6-digit email code
 *  2b. [submitTfaCode]          → 6-digit TOTP authenticator code
 *  On [CloudLoginState.Success] the auth token is persisted and printer list can be fetched.
 */
class PrinterViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    val mqttService = BambuMqttService()

    /** Pending email used across login steps. */
    private var pendingEmail: String = ""
    /** tfaKey returned by the server when TOTP 2FA is required. */
    private var pendingTfaKey: String = ""

    // ── Settings ──────────────────────────────────────────────────────────────

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    fun saveSettings(newSettings: AppSettings) {
        viewModelScope.launch { settingsRepo.save(newSettings) }
    }

    // ── MQTT / Printer status ─────────────────────────────────────────────────

    val printerStatus: StateFlow<PrinterStatus> = mqttService.status
    val connectionState: StateFlow<ConnectionState> = mqttService.connectionState

    /**
     * Connect or reconnect using the given settings.
     *
     * Accepts an optional [overrideSettings] so the UI can pass the local draft directly,
     * avoiding a race condition where [settings] (DataStore-backed StateFlow) hasn't emitted
     * the newly saved value yet when this is called immediately after [saveSettings].
     *
     * Always uses Bambu Cloud MQTT (LAN mode has been removed):
     *  - Broker: us.mqtt.bambulab.com or cn.mqtt.bambulab.com : 8883
     *  - Username: JWT "username" claim (e.g. "u_12345678") or Preference API fallback
     *  - Password: the Bambu Cloud auth token
     *  - TLS: system trust store (CA-signed cert)
     *
     * MQTT username resolution order (mirrors ha-bambulab):
     *  1. Stored cloudMqttUsername (set by onLoginSuccess)
     *  2. Decoded from JWT token payload (username claim)
     *  3. Preference API GET /v1/design-user-service/my/preference → u_{uid}
     *     (required when the token is a cookie/opaque token from the TFA flow)
     */
    fun connect(overrideSettings: AppSettings? = null) {
        val s = overrideSettings ?: settings.value
        viewModelScope.launch(Dispatchers.IO) {
            val cloudHost = cloudMqttHost(s.cloudRegion)
            val authToken = s.cloudAuthToken
            val api = BambuCloudApi(region = s.cloudRegion)
            val mqttUser = resolveMqttUsername(s, authToken, api)

            Log.d(TAG, "connect: host=$cloudHost sn=${s.serialNumber} " +
                    "user=$mqttUser tokenLen=${authToken.length}")

            if (authToken.isBlank()) {
                Log.e(TAG, "connect: cloudAuthToken is blank — please log in first")
                return@launch
            }
            if (mqttUser.isBlank()) {
                Log.e(TAG, "connect: could not determine MQTT username — please log in again")
                return@launch
            }

            mqttService.connect(
                host = cloudHost,
                serialNumber = s.serialNumber,
                accessCode = authToken,
                lanOnlyMode = false,
                mqttUsername = mqttUser,
            )
        }
    }

    /**
     * Resolve the Bambu Cloud MQTT username using three methods in order:
     *  1. Stored value (cloudMqttUsername, set during login)
     *  2. JWT payload decode (username claim)
     *  3. Preference API fallback (for non-JWT tokens from TFA flow)
     */
    private fun resolveMqttUsername(s: AppSettings, authToken: String, api: BambuCloudApi): String {
        if (s.cloudMqttUsername.isNotBlank()) return s.cloudMqttUsername
        val fromJwt = extractMqttUsernameFromToken(authToken)
        if (fromJwt.isNotBlank()) return fromJwt
        Log.d(TAG, "resolveMqttUsername: token is not a JWT, falling back to Preference API")
        return api.fetchMqttUsername(authToken)
    }

    /** Returns the Bambu Cloud MQTT broker hostname for the given region. */
    private fun cloudMqttHost(region: String): String =
        if (region == "China") "cn.mqtt.bambulab.com" else "us.mqtt.bambulab.com"

    /**
     * Extracts the Bambu Cloud MQTT username from a JWT auth token.
     *
     * The JWT payload's `username` claim already carries the full MQTT username
     * (e.g. "u_12345678") — **no** `u_` prefix should be added by the caller.
     * This mirrors ha-bambulab's `_get_username_from_authentication_token()`.
     */
    private fun extractMqttUsernameFromToken(token: String): String = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return@runCatching ""
        val decoded = android.util.Base64.decode(
            payload.replace('-', '+').replace('_', '/'),
            android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        JsonParser.parseString(String(decoded)).asJsonObject.get("username")?.asString ?: ""
    }.getOrDefault("")

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) { mqttService.disconnect() }
    }

    // ── Print controls ────────────────────────────────────────────────────────

    private fun sn() = settings.value.serialNumber

    fun pausePrint() = viewModelScope.launch(Dispatchers.IO) { mqttService.pausePrint(sn()) }
    fun resumePrint() = viewModelScope.launch(Dispatchers.IO) { mqttService.resumePrint(sn()) }
    fun stopPrint() = viewModelScope.launch(Dispatchers.IO) { mqttService.stopPrint(sn()) }
    fun setSpeedLevel(level: Int) = viewModelScope.launch(Dispatchers.IO) { mqttService.setSpeedLevel(sn(), level) }
    fun toggleChamberLight() {
        viewModelScope.launch(Dispatchers.IO) {
            mqttService.toggleChamberLight(sn(), printerStatus.value.chamberLedOn)
        }
    }
    fun homeAxes() = viewModelScope.launch(Dispatchers.IO) { mqttService.homeAxes(sn()) }
    fun moveAxis(axis: String, delta: Float) = viewModelScope.launch(Dispatchers.IO) { mqttService.moveAxis(sn(), axis, delta) }
    fun setBedTemperature(target: Int) = viewModelScope.launch(Dispatchers.IO) { mqttService.setBedTemperature(sn(), target) }
    fun setNozzleTemperature(target: Int) = viewModelScope.launch(Dispatchers.IO) { mqttService.setNozzleTemperature(sn(), target) }

    // ── AMS filament controls ─────────────────────────────────────────────────

    /**
     * Update the filament settings for a specific AMS tray.
     * @param colorHex6 6-char RRGGBB hex string (no '#').
     */
    fun amsEditFilament(
        amsId: Int, trayId: Int,
        type: String, vendor: String,
        colorHex6: String, tempMin: Int, tempMax: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Bambu expects 8-char RRGGBBAA; append FF for full opacity.
            val colorRgba = colorHex6.uppercase().padEnd(6, '0') + "FF"
            mqttService.amsFilamentSetting(sn(), amsId, trayId, type, vendor, colorRgba, tempMin, tempMax)
        }
    }

    /** Retract the current filament from the nozzle back into the AMS. */
    fun amsUnload() = viewModelScope.launch(Dispatchers.IO) { mqttService.amsUnloadFilament(sn()) }

    /**
     * Load the filament in the given AMS tray to the nozzle.
     * Uses the tray's nozzleTempMin as target, or the current nozzle temp if unknown.
     */
    fun amsLoad(amsId: Int, trayId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val currTemp = printerStatus.value.nozzleTemperature.toInt().coerceAtLeast(20)
            val tray = printerStatus.value.amsStatus.units.getOrNull(amsId)?.trays?.getOrNull(trayId)
            val tarTemp = tray?.nozzleTempMin?.takeIf { it > 0 } ?: maxOf(currTemp, 190)
            val globalId = amsId * 4 + trayId
            mqttService.amsLoadFilament(sn(), globalId, currTemp, tarTemp)
        }
    }

    /**
     * Load the filament in the given AMS tray, automatically unloading the current
     * filament first if a different tray is active.
     *
     * Flow:
     * 1. If trayNow is a different tray (0-15): send unload command, then wait 1 s.
     * 2. Send load command for the target tray.
     */
    fun amsLoadWithUnload(amsId: Int, trayId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val globalId = amsId * 4 + trayId
            val trayNow = printerStatus.value.amsStatus.trayNow
            if (trayNow in 0..15 && trayNow != globalId) {
                mqttService.amsUnloadFilament(sn())
                delay(1_000L)
            }
            val currTemp = printerStatus.value.nozzleTemperature.toInt().coerceAtLeast(20)
            val tray = printerStatus.value.amsStatus.units
                .getOrNull(amsId)?.trays?.getOrNull(trayId)
            val tarTemp = tray?.nozzleTempMin?.takeIf { it > 0 } ?: maxOf(currTemp, 190)
            mqttService.amsLoadFilament(sn(), globalId, currTemp, tarTemp)
        }
    }

    // ── Bambu Cloud login ─────────────────────────────────────────────────────

    private val _cloudLoginState = MutableStateFlow<CloudLoginState>(CloudLoginState.Idle)
    val cloudLoginState: StateFlow<CloudLoginState> = _cloudLoginState.asStateFlow()

    private val _cloudPrinters = MutableStateFlow<List<CloudPrinter>>(emptyList())
    val cloudPrinters: StateFlow<List<CloudPrinter>> = _cloudPrinters.asStateFlow()

    /**
     * Step 1: initiate login with email and password.
     * On success the state transitions to [CloudLoginState.Success].
     * When 2FA is needed it transitions to [CloudLoginState.RequiresEmailCode] or
     * [CloudLoginState.RequiresTfa] — the UI should then call [submitEmailCode] or
     * [submitTfaCode] respectively.
     */
    fun cloudLogin(email: String, password: String) {
        pendingEmail = email
        _cloudLoginState.value = CloudLoginState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val api = BambuCloudApi(region = settings.value.cloudRegion)
            when (val result = api.login(email, password)) {
                is LoginResult.Success -> onLoginSuccess(api, email, result.token)
                is LoginResult.RequiresEmailCode -> {
                    // Request the code and tell the UI to show the code-entry field
                    api.requestEmailCode(email)
                    _cloudLoginState.value = CloudLoginState.RequiresEmailCode()
                }
                is LoginResult.RequiresTfa -> {
                    pendingTfaKey = result.tfaKey
                    _cloudLoginState.value = CloudLoginState.RequiresTfa()
                }
                is LoginResult.Error -> _cloudLoginState.value = CloudLoginState.Error(result.message)
                else -> _cloudLoginState.value = CloudLoginState.Error("Unexpected login response")
            }
        }
    }

    /**
     * Step 2a: submit the 6-digit code sent to the user's email.
     * Call only when [cloudLoginState] is [CloudLoginState.RequiresEmailCode].
     *
     * Keeps the code panel visible throughout by staying in RequiresEmailCode state
     * (with isLoading=true during the request, error= on failure).
     */
    fun submitEmailCode(code: String) {
        // Keep panel visible — just mark as loading (do NOT go to the generic Loading state)
        _cloudLoginState.value = CloudLoginState.RequiresEmailCode(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val api = BambuCloudApi(region = settings.value.cloudRegion)
            when (val result = api.verifyEmailCode(pendingEmail, code)) {
                is LoginResult.Success -> onLoginSuccess(api, pendingEmail, result.token)
                LoginResult.CodeExpired -> {
                    // Request a fresh code, then stay in panel with a message
                    api.requestEmailCode(pendingEmail)
                    _cloudLoginState.value = CloudLoginState.RequiresEmailCode(
                        error = "Code expired. A new code has been sent to your email."
                    )
                }
                LoginResult.CodeIncorrect -> {
                    _cloudLoginState.value = CloudLoginState.RequiresEmailCode(
                        error = "Incorrect code. Please try again."
                    )
                }
                is LoginResult.Error -> _cloudLoginState.value = CloudLoginState.RequiresEmailCode(
                    error = result.message
                )
                else -> _cloudLoginState.value = CloudLoginState.RequiresEmailCode(
                    error = "Unexpected verification response"
                )
            }
        }
    }

    /**
     * Step 2b: submit the 6-digit TOTP code from the authenticator app.
     * Call only when [cloudLoginState] is [CloudLoginState.RequiresTfa].
     *
     * Keeps the code panel visible throughout (same pattern as [submitEmailCode]).
     */
    fun submitTfaCode(code: String) {
        // Keep panel visible — stay in RequiresTfa state with isLoading=true
        _cloudLoginState.value = CloudLoginState.RequiresTfa(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val api = BambuCloudApi(region = settings.value.cloudRegion)
            when (val result = api.verifyTfaCode(pendingTfaKey, code)) {
                is LoginResult.Success -> onLoginSuccess(api, pendingEmail, result.token)
                is LoginResult.Error -> _cloudLoginState.value = CloudLoginState.RequiresTfa(
                    error = result.message
                )
                else -> _cloudLoginState.value = CloudLoginState.RequiresTfa(
                    error = "Unexpected 2FA response"
                )
            }
        }
    }

    /**
     * Reset the login state back to [CloudLoginState.Idle] and clear any pending 2FA state.
     * Call when the user taps "Back" during a 2FA step.
     */
    fun resetLoginState() {
        pendingEmail = ""
        pendingTfaKey = ""
        _cloudLoginState.value = CloudLoginState.Idle
    }

    /** Persist the auth token and mark login as complete. */
    private suspend fun onLoginSuccess(api: BambuCloudApi, email: String, token: String) {
        // Resolve MQTT username: JWT payload → Preference API fallback (for TFA cookie tokens)
        val mqttUsername = extractMqttUsernameFromToken(token).ifBlank {
            Log.d(TAG, "onLoginSuccess: token not a JWT, fetching username via Preference API")
            api.fetchMqttUsername(token)
        }
        Log.d(TAG, "onLoginSuccess: mqttUsername=$mqttUsername tokenLen=${token.length}")
        settingsRepo.save(
            settings.value.copy(
                cloudAuthToken = token,
                cloudEmail = email,
                cloudMqttUsername = mqttUsername,
            )
        )
        pendingEmail = ""
        pendingTfaKey = ""
        _cloudLoginState.value = CloudLoginState.Success
    }

    fun fetchCloudPrinters() {
        val token = settings.value.cloudAuthToken
        if (token.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val api = BambuCloudApi(region = settings.value.cloudRegion)
            _cloudPrinters.value = api.getDeviceList(token)
        }
    }

    /** Select a cloud printer and save its settings. */
    fun selectCloudPrinter(printer: CloudPrinter) {
        saveSettings(
            settings.value.copy(
                serialNumber = printer.serialNumber,
                printerName = printer.name,
                printerModel = printer.model,
                accessCode = printer.accessCode,
                lanOnlyMode = false,
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        mqttService.disconnect()
    }
}

sealed class CloudLoginState {
    data object Idle : CloudLoginState()
    data object Loading : CloudLoginState()
    /** Login succeeded; auth token saved. */
    data object Success : CloudLoginState()
    /**
     * Server sent a code to the user's email.
     * [isLoading] = true while the verification request is in-flight (panel stays visible).
     * [error] = non-null when the submitted code was wrong/expired.
     */
    data class RequiresEmailCode(val error: String? = null, val isLoading: Boolean = false) : CloudLoginState()
    /**
     * Server requires TOTP authenticator code.
     * [isLoading] = true while the verification request is in-flight (panel stays visible).
     * [error] = non-null when the submitted code was wrong.
     */
    data class RequiresTfa(val error: String? = null, val isLoading: Boolean = false) : CloudLoginState()
    data class Error(val message: String) : CloudLoginState()
}

