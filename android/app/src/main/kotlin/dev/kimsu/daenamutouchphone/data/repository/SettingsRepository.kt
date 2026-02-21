package dev.kimsu.daenamutouchphone.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.kimsu.daenamutouchphone.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xptouch_settings")

/**
 * Persists [AppSettings] using Jetpack DataStore.
 * Mirrors xtouch_settings_loadSettings / xtouch_settings_save from settings.h.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PRINTER_HOST = stringPreferencesKey("printer_host")
        val ACCESS_CODE = stringPreferencesKey("access_code")
        val SERIAL_NUMBER = stringPreferencesKey("serial_number")
        val PRINTER_NAME = stringPreferencesKey("printer_name")
        val PRINTER_MODEL = stringPreferencesKey("printer_model")
        val LAN_ONLY_MODE = booleanPreferencesKey("lan_only_mode")
        val CLOUD_REGION = stringPreferencesKey("cloud_region")
        val CLOUD_EMAIL = stringPreferencesKey("cloud_email")
        val CLOUD_AUTH_TOKEN = stringPreferencesKey("cloud_auth_token")
        val CLOUD_MQTT_USERNAME = stringPreferencesKey("cloud_mqtt_username")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            printerHost = prefs[Keys.PRINTER_HOST] ?: "",
            accessCode = prefs[Keys.ACCESS_CODE] ?: "",
            serialNumber = prefs[Keys.SERIAL_NUMBER] ?: "",
            printerName = prefs[Keys.PRINTER_NAME] ?: "",
            printerModel = prefs[Keys.PRINTER_MODEL] ?: "",
            lanOnlyMode = prefs[Keys.LAN_ONLY_MODE] ?: true,
            cloudRegion = prefs[Keys.CLOUD_REGION] ?: "Global",
            cloudEmail = prefs[Keys.CLOUD_EMAIL] ?: "",
            cloudAuthToken = prefs[Keys.CLOUD_AUTH_TOKEN] ?: "",
            cloudMqttUsername = prefs[Keys.CLOUD_MQTT_USERNAME] ?: "",
        )
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PRINTER_HOST] = settings.printerHost
            prefs[Keys.ACCESS_CODE] = settings.accessCode
            prefs[Keys.SERIAL_NUMBER] = settings.serialNumber
            prefs[Keys.PRINTER_NAME] = settings.printerName
            prefs[Keys.PRINTER_MODEL] = settings.printerModel
            prefs[Keys.LAN_ONLY_MODE] = settings.lanOnlyMode
            prefs[Keys.CLOUD_REGION] = settings.cloudRegion
            prefs[Keys.CLOUD_EMAIL] = settings.cloudEmail
            prefs[Keys.CLOUD_AUTH_TOKEN] = settings.cloudAuthToken
            prefs[Keys.CLOUD_MQTT_USERNAME] = settings.cloudMqttUsername
        }
    }
}
