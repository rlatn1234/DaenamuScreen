package dev.kimsu.daenamutouchphone.data.model

/**
 * Print state string values sent by the printer in "gcode_state".
 * Mirrors XTouchPrintStatus enum from types.h.
 */
enum class PrintStatus {
    IDLE, RUNNING, PAUSED, FINISHED, PREPARE, FAILED, UNKNOWN
}

/** Mirrors XTouchPrintingSpeedLevel from types.h. */
enum class SpeedLevel(val level: Int, val label: String) {
    SILENT(1, "Silent"),
    NORMAL(2, "Normal"),
    SPORT(3, "Sport"),
    LUDICROUS(4, "Ludicrous");

    companion object {
        fun fromLevel(level: Int) = entries.firstOrNull { it.level == level } ?: NORMAL
    }
}

/**
 * Complete snapshot of the Bambu Lab printer status.
 * Mirrors BambuMQTTPayload (XTouchBambuStatus) from types.h.
 */
data class PrinterStatus(
    // ── Connection ──────────────────────────────────────────────────
    val connected: Boolean = false,

    // ── Print job ───────────────────────────────────────────────────
    val printStatus: PrintStatus = PrintStatus.IDLE,
    val subtaskName: String = "",
    val gcodeFile: String = "",
    /** Print progress 0-100. */
    val printPercent: Int = 0,
    /** Estimated remaining time in seconds. */
    val leftTimeSecs: Int = 0,
    val currentLayer: Int = 0,
    val totalLayers: Int = 0,
    val gcodeFilePreparePercent: Int = 0,

    // ── Temperatures ────────────────────────────────────────────────
    val bedTemperature: Double = 0.0,
    val bedTargetTemperature: Double = 0.0,
    val nozzleTemperature: Double = 0.0,
    val nozzleTargetTemperature: Double = 0.0,
    val chamberTemperature: Double = 0.0,
    val frameTemperature: Double = 0.0,

    // ── Fans ────────────────────────────────────────────────────────
    /** Part cooling fan speed 0-255. */
    val coolingFanSpeed: Int = 0,
    /** Auxiliary fan speed 0-255. */
    val auxFanSpeed: Int = 0,
    /** Chamber exhaust fan speed 0-255. */
    val chamberFanSpeed: Int = 0,

    // ── Speed ───────────────────────────────────────────────────────
    val speedLevel: SpeedLevel = SpeedLevel.NORMAL,
    /** Speed magnitude percentage (e.g. 100 = 100%). */
    val speedMagnitude: Int = 100,

    // ── Hardware ────────────────────────────────────────────────────
    val chamberLedOn: Boolean = false,
    val wifiSignalDbm: Int = 0,
    val printerType: String = "",
    val nozzleDiameter: Float = 0.4f,
    val nozzleType: String = "",

    // ── Camera ──────────────────────────────────────────────────────
    val hasIpCamera: Boolean = false,
    val cameraRecording: Boolean = false,
    val cameraTimelapse: Boolean = false,
    val cameraImageUrl: String = "",

    // ── AMS ─────────────────────────────────────────────────────────
    val amsStatus: AmsStatus = AmsStatus(),

    // ── Error / HMS ─────────────────────────────────────────────────
    val hmsMessages: List<String> = emptyList(),
) {
    /** Remaining time formatted as "HHh MMm" or "MMm SSs". */
    val formattedTimeRemaining: String
        get() {
            if (leftTimeSecs <= 0) return "--"
            val h = leftTimeSecs / 3600
            val m = (leftTimeSecs % 3600) / 60
            val s = leftTimeSecs % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }
}
