package dev.kimsu.daenamutouchphone.data.model

/**
 * Mirrors the AMS tray data tracked in trays.h / ams.h.
 */
data class AmsTray(
    /** 0-based AMS unit index. */
    val amsIndex: Int,
    /** 1-based tray index within the AMS unit. */
    val trayIndex: Int,
    /** RGB hex colour string (6 chars, no '#'). Empty when slot is empty. */
    val colorHex: String = "",
    /** Filament type string, e.g. "PLA", "PETG". */
    val filamentType: String = "",
    /** Filament manufacturer/vendor, e.g. "Bambu", "eSUN". */
    val filamentVendor: String = "",
    /** Recommended nozzle temperature (mid-point of min/max). */
    val nozzleTemp: Int = 0,
    /** Minimum nozzle temperature for this filament. */
    val nozzleTempMin: Int = 0,
    /** Maximum nozzle temperature for this filament. */
    val nozzleTempMax: Int = 0,
    /** true when a spool is physically detected in this slot. */
    val isLoaded: Boolean = false,
)

data class AmsUnit(
    val index: Int,
    /** Humidity level 1-5 (higher = drier). */
    val humidity: Int = 0,
    val temperatureCelsius: Float = 0f,
    val trays: List<AmsTray> = emptyList(),
)

data class AmsStatus(
    val units: List<AmsUnit> = emptyList(),
    /** Currently active tray index (0-15) or 254/255 for none/virtual. */
    val trayNow: Int = 255,
    /** Previously active tray index. */
    val trayPrev: Int = 255,
    /** Target tray during a filament change. */
    val trayTarget: Int = 255,
    /** true if at least one AMS unit is detected. */
    val hasAms: Boolean = false,
    /** true if the virtual external spool is supported. */
    val hasVirtualTray: Boolean = false,
    val virtualTrayColorHex: String = "",
    val virtualTrayType: String = "",
)
