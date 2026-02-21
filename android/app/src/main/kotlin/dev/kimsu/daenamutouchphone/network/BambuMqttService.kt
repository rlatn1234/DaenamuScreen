package dev.kimsu.daenamutouchphone.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.kimsu.daenamutouchphone.data.model.AmsStatus
import dev.kimsu.daenamutouchphone.data.model.AmsUnit
import dev.kimsu.daenamutouchphone.data.model.AmsTray
import dev.kimsu.daenamutouchphone.data.model.PrintStatus
import dev.kimsu.daenamutouchphone.data.model.PrinterStatus
import dev.kimsu.daenamutouchphone.data.model.SpeedLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val TAG = "XPTouch/MQTT"
private const val MQTT_PORT = 8883
private const val MQTT_USERNAME = "bblp"
private const val MQTT_QOS = 0

/**
 * Handles TLS-MQTT communication with a Bambu Lab printer.
 *
 * Protocol reference: reverse-engineered from the ESP32 firmware (mqtt.h / device.h).
 * - Broker: tcp://<printerHost>:8883 (TLS, self-signed cert on LAN)
 * - Username: "bblp"
 * - Password: access code (8-char code shown on printer screen)
 * - Subscribe: device/<serialNumber>/report
 * - Publish:   device/<serialNumber>/request
 */
class BambuMqttService {

    private var mqttClient: MqttClient? = null
    private var sequenceId: Long = 0L

    private val _status = MutableStateFlow(PrinterStatus())
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Open a TLS-MQTT connection to the printer.
     * In LAN mode Bambu printers use a self-signed certificate, so we accept any cert.
     * For production cloud connections the system trust-store is used instead.
     *
     * @param host         Printer IP / hostname.
     * @param serialNumber Printer serial number.
     * @param accessCode   8-char LAN access code.
     * @param lanOnlyMode  When true, bypass certificate validation (self-signed LAN cert).
     */
    fun connect(
        host: String,
        serialNumber: String,
        accessCode: String,
        lanOnlyMode: Boolean,
        mqttUsername: String = MQTT_USERNAME,
    ) {
        if (host.isBlank() || serialNumber.isBlank() || accessCode.isBlank()) {
            Log.w(TAG, "connect() called with incomplete settings – skipping")
            return
        }

        // Clean up any existing connection first
        disconnect()

        _connectionState.value = ConnectionState.CONNECTING
        _status.value = _status.value.copy(connected = false)

        val clientId = "xptouch_${UUID.randomUUID().toString().take(8)}"
        val brokerUrl = "ssl://$host:$MQTT_PORT"

        try {
            val opts = MqttConnectOptions().apply {
                userName = mqttUsername
                password = accessCode.toCharArray()
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
                isAutomaticReconnect = true
                if (lanOnlyMode) {
                    // Bambu LAN printers use a self-signed certificate; trust all certs.
                    socketFactory = buildTrustAllSslContext().socketFactory
                }
            }

            val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _status.value = _status.value.copy(connected = false)
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    processMessage(topic, String(message.payload))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client.connect(opts)
            val reportTopic = "device/$serialNumber/report"
            client.subscribe(reportTopic, MQTT_QOS)
            Log.i(TAG, "Connected and subscribed to $reportTopic")

            mqttClient = client
            _connectionState.value = ConnectionState.CONNECTED
            _status.value = _status.value.copy(connected = true)

            // Ask printer for full status immediately
            sendPushAll(serialNumber)

        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect failed: ${e.message}", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun disconnect() {
        runCatching {
            mqttClient?.takeIf { it.isConnected }?.disconnect()
            mqttClient?.close()
        }
        mqttClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _status.value = _status.value.copy(connected = false)
    }

    // ── Outgoing commands ─────────────────────────────────────────────────────

    /** Request the printer to push its full current status. */
    fun sendPushAll(serialNumber: String) {
        val json = """{"pushing":{"command":"pushall","version":1,"push_target":1,"sequence_id":${nextSeq()}}, "user_id":"123456789"}"""
        publish(serialNumber, json)
    }

    /** Pause the running print. */
    fun pausePrint(serialNumber: String) = publish(serialNumber, printAction("pause", serialNumber))

    /** Resume a paused print. */
    fun resumePrint(serialNumber: String) = publish(serialNumber, printAction("resume", serialNumber))

    /** Stop the running print. */
    fun stopPrint(serialNumber: String) = publish(serialNumber, printAction("stop", serialNumber))

    /**
     * Change the printing speed level.
     * @param level 1=Silent, 2=Normal, 3=Sport, 4=Ludicrous
     */
    fun setSpeedLevel(serialNumber: String, level: Int) {
        val json = """{"print":{"command":"print_speed","sequence_id":${nextSeq()},"param":"$level"}}"""
        publish(serialNumber, json)
    }

    /** Toggle the chamber light on/off. */
    fun toggleChamberLight(serialNumber: String, currentlyOn: Boolean) {
        val mode = if (currentlyOn) "off" else "on"
        val json = """{"system":{"sequence_id":${nextSeq()},"command":"ledctrl","led_node":"chamber_light","led_mode":"$mode","led_on_time":500,"led_off_time":500,"loop_times":1,"interval_time":1000}}"""
        publish(serialNumber, json)
    }

    /** Send an arbitrary GCode line to the printer. */
    fun sendGCodeLine(serialNumber: String, gcode: String) {
        val escaped = gcode.replace("\"", "\\\"")
        val json = """{"print":{"command":"gcode_line","sequence_id":${nextSeq()},"param":"$escaped","user_id":"123456789"}}"""
        publish(serialNumber, json)
    }

    /** Set bed target temperature (GCode M140). */
    fun setBedTemperature(serialNumber: String, targetCelsius: Int) =
        sendGCodeLine(serialNumber, "M140 S$targetCelsius\n")

    /** Set nozzle target temperature (GCode M104). */
    fun setNozzleTemperature(serialNumber: String, targetCelsius: Int) =
        sendGCodeLine(serialNumber, "M104 S$targetCelsius\n")

    /** Home all axes (GCode G28). */
    fun homeAxes(serialNumber: String) = sendGCodeLine(serialNumber, "G28 \n")

    /**
     * Move a single axis by [deltaMillimeters] mm.
     * @param axis "X", "Y", or "Z"
     */
    fun moveAxis(serialNumber: String, axis: String, deltaMillimeters: Float) {
        val speed = if (axis == "Z") 1500 else 3000
        // The trailing spaces after "M211 S" and "G91" are intentional – they match the
        // exact G-code strings used in the ESP32 firmware (device.h xtouch_device_move_axis).
        val cmd = "M211 S \nM211 X1 Y1 Z1\nM1002 push_ref_mode\nG91 \nG1 ${axis}${deltaMillimeters} F${speed}\nM1002 pop_ref_mode\nM211 R\n"
        sendGCodeLine(serialNumber, cmd)
    }

    /**
     * Update AMS tray filament properties (type, colour, temperature range).
     *
     * @param colorRgba 8-char RRGGBBAA hex string (e.g. "00AE42FF" for Bambu green).
     */
    fun amsFilamentSetting(
        serialNumber: String,
        amsId: Int,
        trayId: Int,
        type: String,
        vendor: String,
        colorRgba: String,
        tempMin: Int,
        tempMax: Int,
    ) {
        val cmd = JsonObject().apply {
            add("print", JsonObject().apply {
                addProperty("sequence_id", nextSeq())
                addProperty("command", "ams_filament_setting")
                addProperty("ams_id", amsId)
                addProperty("tray_id", trayId)
                addProperty("tray_info_idx", filamentTypeToInfoIdx(type))
                addProperty("tray_color", colorRgba)
                addProperty("nozzle_temp_min", tempMin)
                addProperty("nozzle_temp_max", tempMax)
                addProperty("tray_type", type)
                if (vendor.isNotBlank()) addProperty("tray_sub_brands", vendor)
            })
        }
        publish(serialNumber, cmd.toString())
    }

    /**
     * Unload filament from the nozzle back into the AMS.
     * Uses the dedicated Bambu MQTT command (not M702 GCode) for reliable operation.
     */
    fun amsUnloadFilament(serialNumber: String) {
        val cmd = JsonObject().apply {
            add("print", JsonObject().apply {
                addProperty("sequence_id", nextSeq())
                addProperty("command", "unload_filament")
                addProperty("param", "")
            })
        }
        publish(serialNumber, cmd.toString())
    }

    /**
     * Load (change to) a specific AMS tray.
     *
     * @param globalTrayId  0-based global tray index: ams_id * 4 + tray_id (0-15).
     * @param currTemp      Current nozzle temperature in °C.
     * @param tarTemp       Target nozzle temperature for the new filament in °C.
     */
    fun amsLoadFilament(serialNumber: String, globalTrayId: Int, currTemp: Int, tarTemp: Int) {
        val cmd = JsonObject().apply {
            add("print", JsonObject().apply {
                addProperty("sequence_id", nextSeq())
                addProperty("command", "ams_change_filament")
                addProperty("target", globalTrayId)
                addProperty("curr_temp", currTemp)
                addProperty("tar_temp", tarTemp)
            })
        }
        publish(serialNumber, cmd.toString())
    }

    /** Map a filament type string to Bambu's tray_info_idx code. */
    private fun filamentTypeToInfoIdx(type: String): String = when (type.uppercase()) {
        "PLA"    -> "GFA00"
        "PLA-CF" -> "GFA01"
        "PETG"   -> "GFB00"
        "PET-CF" -> "GFB01"
        "ABS"    -> "GFC00"
        "ASA"    -> "GFD00"
        "PA"     -> "GFF00"
        "PA-CF"  -> "GFF01"
        "PC"     -> "GFG00"
        "TPU"    -> "GFH00"
        "PVA"    -> "GFJ00"
        else     -> "GFA00"
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun publish(serialNumber: String, payload: String) {
        val client = mqttClient ?: run {
            Log.w(TAG, "publish() – not connected")
            return
        }
        if (!client.isConnected) {
            Log.w(TAG, "publish() – client not connected")
            return
        }
        val topic = "device/$serialNumber/request"
        runCatching {
            client.publish(topic, MqttMessage(payload.toByteArray()).also { it.qos = MQTT_QOS })
        }.onFailure { Log.e(TAG, "publish failed: ${it.message}") }
    }

    private fun printAction(action: String, serialNumber: String): String =
        """{"print":{"command":"$action","param":"","sequence_id":${nextSeq()}}}"""

    private fun nextSeq(): Long {
        sequenceId = (sequenceId + 1) % Long.MAX_VALUE
        return sequenceId
    }

    // ── Incoming message parsing ──────────────────────────────────────────────

    private fun processMessage(topic: String, rawJson: String) {
        try {
            val root = JsonParser.parseString(rawJson).asJsonObject
            if (root.has("print")) {
                val print = root.getAsJsonObject("print")
                val command = print.getString("command")
                if (command == "push_status") {
                    parsePushStatus(print)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processMessage parse error: ${e.message}")
        }
    }

    @Suppress("ComplexMethod", "LongMethod")
    private fun parsePushStatus(print: JsonObject) {
        var current = _status.value

        // ── Print state ──────────────────────────────────
        print.getStringOrNull("gcode_state")?.let {
            current = current.copy(printStatus = parsePrintState(it))
        }
        print.getStringOrNull("subtask_name")?.let { current = current.copy(subtaskName = it) }
        print.getStringOrNull("gcode_file")?.let { current = current.copy(gcodeFile = it) }

        // ── Progress ─────────────────────────────────────
        print.getIntOrNull("mc_percent")?.let { current = current.copy(printPercent = it) }
        print.getIntOrNull("mc_remaining_time")?.let { current = current.copy(leftTimeSecs = it * 60) }
        print.getIntOrNull("layer_num")?.let { current = current.copy(currentLayer = it) }
        print.getIntOrNull("total_layer_num")?.let { current = current.copy(totalLayers = it) }

        // ── Temperatures ─────────────────────────────────
        print.getDoubleOrNull("bed_temper")?.let { current = current.copy(bedTemperature = it) }
        print.getDoubleOrNull("bed_target_temper")?.let { current = current.copy(bedTargetTemperature = it) }
        print.getDoubleOrNull("nozzle_temper")?.let { current = current.copy(nozzleTemperature = it) }
        print.getDoubleOrNull("nozzle_target_temper")?.let { current = current.copy(nozzleTargetTemperature = it) }
        print.getDoubleOrNull("chamber_temper")?.let { current = current.copy(chamberTemperature = it) }
        print.getDoubleOrNull("frame_temper")?.let { current = current.copy(frameTemperature = it) }

        // ── Fans ─────────────────────────────────────────
        if (print.has("fan_gear")) {
            val gear = print.get("fan_gear").asLong
            current = current.copy(
                coolingFanSpeed = ((gear and 0x000000FFL) shr 0).toInt(),
                auxFanSpeed = ((gear and 0x0000FF00L) shr 8).toInt(),
                chamberFanSpeed = ((gear and 0x00FF0000L) shr 16).toInt(),
            )
        } else {
            print.getIntOrNull("cooling_fan_speed")?.let { current = current.copy(coolingFanSpeed = it) }
            print.getIntOrNull("big_fan1_speed")?.let { current = current.copy(auxFanSpeed = it) }
            print.getIntOrNull("big_fan2_speed")?.let { current = current.copy(chamberFanSpeed = it) }
        }

        // ── Speed ─────────────────────────────────────────
        print.getIntOrNull("spd_lvl")?.let { current = current.copy(speedLevel = SpeedLevel.fromLevel(it)) }
        print.getIntOrNull("spd_mag")?.let { current = current.copy(speedMagnitude = it) }

        // ── Light ─────────────────────────────────────────
        if (print.has("lights_report") && print.getAsJsonArray("lights_report").size() > 0) {
            val mode = print.getAsJsonArray("lights_report")[0].asJsonObject.getString("mode")
            current = current.copy(chamberLedOn = mode == "on")
        }

        // ── WiFi ──────────────────────────────────────────
        print.getStringOrNull("wifi_signal")?.let {
            val dbm = it.replace("dBm", "").trim().toIntOrNull() ?: 0
            current = current.copy(wifiSignalDbm = dbm)
        }

        // ── Nozzle info ───────────────────────────────────
        print.getStringOrNull("printer_type")?.let { current = current.copy(printerType = it) }
        if (print.has("nozzle_diameter")) {
            val nd = runCatching { print.get("nozzle_diameter").asFloat }.getOrNull()
            if (nd != null) current = current.copy(nozzleDiameter = nd)
        }
        print.getStringOrNull("nozzle_type")?.let { current = current.copy(nozzleType = it) }

        // ── Camera ────────────────────────────────────────
        if (print.has("ipcam")) {
            val ipcam = print.getAsJsonObject("ipcam")
            ipcam.getStringOrNull("ipcam_dev")?.let { current = current.copy(hasIpCamera = it == "1") }
            ipcam.getStringOrNull("ipcam_record")?.let { current = current.copy(cameraRecording = it == "enable") }
            ipcam.getStringOrNull("timelapse")?.let { current = current.copy(cameraTimelapse = it == "enable") }
        }
        print.getStringOrNull("url")?.let { current = current.copy(cameraImageUrl = it) }

        // ── AMS ───────────────────────────────────────────
        if (print.has("ams")) {
            current = current.copy(amsStatus = parseAmsStatus(print, current.amsStatus))
        }
        if (print.has("vt_tray")) {
            val vt = print.getAsJsonObject("vt_tray")
            val color = vt.getStringOrNull("tray_color")?.take(6) ?: ""
            val type = vt.getStringOrNull("tray_type") ?: ""
            current = current.copy(
                amsStatus = current.amsStatus.copy(
                    hasVirtualTray = true,
                    virtualTrayColorHex = color,
                    virtualTrayType = type,
                )
            )
        }

        _status.value = current
    }

    private fun parseAmsStatus(print: JsonObject, previous: AmsStatus): AmsStatus {
        val amsObj = print.getAsJsonObject("ams")
        val trayNow = amsObj.getIntOrNull("tray_now") ?: previous.trayNow
        val trayPrev = amsObj.getIntOrNull("tray_pre") ?: previous.trayPrev
        val trayTarget = amsObj.getIntOrNull("tray_tar") ?: previous.trayTarget

        val amsList = if (amsObj.has("ams") && amsObj.get("ams").isJsonArray) {
            amsObj.getAsJsonArray("ams")
        } else null

        val units = amsList?.mapIndexed { amsIdx, amsElem ->
            val amsUnit = amsElem.asJsonObject
            val humidity = amsUnit.getIntOrNull("humidity") ?: 0
            val temp = runCatching { amsUnit.get("temp").asFloat }.getOrNull() ?: 0f
            val trays = amsUnit.getAsJsonArray("tray")?.mapIndexed { trayIdx, trayElem ->
                val tray = trayElem.asJsonObject
                val color = tray.getStringOrNull("tray_color")?.take(6) ?: ""
                val type = tray.getStringOrNull("tray_type") ?: ""
                val vendor = tray.getStringOrNull("tray_sub_brands") ?: ""
                val nMax = tray.getIntOrNull("nozzle_temp_max") ?: 0
                val nMin = tray.getIntOrNull("nozzle_temp_min") ?: 0
                val loaded = tray.getIntOrNull("n") ?: 0
                AmsTray(
                    amsIndex = amsIdx,
                    trayIndex = trayIdx + 1,
                    colorHex = color,
                    filamentType = type,
                    filamentVendor = vendor,
                    nozzleTemp = if (nMax > 0) (nMax + nMin) / 2 else 0,
                    nozzleTempMin = nMin,
                    nozzleTempMax = nMax,
                    isLoaded = loaded != 0,
                )
            } ?: emptyList()
            AmsUnit(index = amsIdx, humidity = humidity, temperatureCelsius = temp, trays = trays)
        } ?: previous.units

        return previous.copy(
            units = units,
            trayNow = trayNow,
            trayPrev = trayPrev,
            trayTarget = trayTarget,
            hasAms = units.isNotEmpty(),
        )
    }

    // ── TLS helpers ───────────────────────────────────────────────────────────

    /**
     * Build an [SSLContext] that trusts all certificates.
     * This is required for Bambu Lab LAN printers which use self-signed certificates.
     * NOTE: Only used when [lanOnlyMode] = true.  Cloud connections use the system store.
     */
    private fun buildTrustAllSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").also {
            it.init(null, arrayOf(trustAll), null)
        }
    }

    // ── JsonObject extension helpers ──────────────────────────────────────────

    private fun JsonObject.getString(key: String): String? =
        runCatching { if (has(key)) get(key).asString else null }.getOrNull()

    private fun JsonObject.getStringOrNull(key: String): String? = getString(key)

    private fun JsonObject.getIntOrNull(key: String): Int? =
        runCatching { if (has(key)) get(key).asInt else null }.getOrNull()

    private fun JsonObject.getDoubleOrNull(key: String): Double? =
        runCatching { if (has(key)) get(key).asDouble else null }.getOrNull()

    private fun parsePrintState(state: String): PrintStatus = when (state.uppercase()) {
        "IDLE" -> PrintStatus.IDLE
        "RUNNING" -> PrintStatus.RUNNING
        "PAUSE" -> PrintStatus.PAUSED
        "FINISH" -> PrintStatus.FINISHED
        "PREPARE" -> PrintStatus.PREPARE
        "FAILED" -> PrintStatus.FAILED
        else -> PrintStatus.UNKNOWN
    }
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
