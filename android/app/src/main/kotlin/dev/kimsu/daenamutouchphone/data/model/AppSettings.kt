package dev.kimsu.daenamutouchphone.data.model

/**
 * Mirrors XTouchConfig from types.h – the connection and runtime configuration stored on device.
 */
data class AppSettings(
    /** IP address of the Bambu Lab printer (LAN mode). */
    val printerHost: String = "",
    /** 8-character access code shown on the printer screen. */
    val accessCode: String = "",
    /** Printer serial number (e.g. 01P00C…). */
    val serialNumber: String = "",
    /** Human-readable name for the printer. */
    val printerName: String = "",
    /** Printer model string (e.g. "BL-P001"). */
    val printerModel: String = "",
    /** When true, skip Bambu Cloud and connect directly over LAN. Kept for DataStore compatibility; always false at runtime. */
    val lanOnlyMode: Boolean = false,
    /** Bambu Cloud region: "Global" or "China". */
    val cloudRegion: String = "Global",
    /** Bambu Cloud account email (used only in cloud mode). */
    val cloudEmail: String = "",
    /** Bambu Cloud auth token (persisted after login). */
    val cloudAuthToken: String = "",
    /**
     * Bambu Cloud MQTT username (e.g. "u_12345678") derived from the JWT after login.
     * Stored separately so it is available without re-decoding the JWT on every connect.
     */
    val cloudMqttUsername: String = "",
)
