package dev.kimsu.daenamutouchphone.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WbIncandescent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kimsu.daenamutouchphone.data.model.PrintStatus
import dev.kimsu.daenamutouchphone.data.model.PrinterStatus
import dev.kimsu.daenamutouchphone.data.model.SpeedLevel
import dev.kimsu.daenamutouchphone.network.ConnectionState
import dev.kimsu.daenamutouchphone.ui.theme.StatusFailed
import dev.kimsu.daenamutouchphone.ui.theme.StatusFinished
import dev.kimsu.daenamutouchphone.ui.theme.StatusIdle
import dev.kimsu.daenamutouchphone.ui.theme.StatusPaused
import dev.kimsu.daenamutouchphone.ui.theme.StatusRunning
import dev.kimsu.daenamutouchphone.ui.theme.TempHot
import dev.kimsu.daenamutouchphone.viewmodel.PrinterViewModel

/**
 * Models WITHOUT a chamber temperature sensor.
 * Includes both raw MQTT project-name codes (C11, C12, N1, N2S) and the
 * dev_model_name values returned by the Bambu Cloud device-list API (BL-P001, BL-P002),
 * as well as the human-readable names that may appear in either field.
 */
private val NO_CHAMBER_MODELS = setOf(
    // Raw MQTT printer_type / project_name codes
    "C11",      // P1P
    "C12",      // P1S
    "N1",       // A1 Mini
    "N2S",      // A1
    // Cloud API dev_model_name codes
    "BL-P001",  // P1P
    "BL-P002",  // P1S
)

/**
 * Returns true when the printer model has a chamber temperature sensor.
 * Uses [modelKey] = settings.printerModel (preferred) or MQTT printer_type (fallback).
 * Both are normalised to uppercase before the call.
 * If the model is unknown (empty), returns true (safe default: show the card).
 */
private fun hasChamberSensor(modelKey: String): Boolean {
    if (modelKey.isEmpty()) return true
    return modelKey !in NO_CHAMBER_MODELS &&
            !modelKey.startsWith("P1") &&   // P1P, P1S, P1S Plus …
            !modelKey.startsWith("A1")       // A1, A1 MINI …
}

/**
 * Main home screen – Bambu Lab style.
 * Shows a circular progress donut (print %) on the left, job info on the right,
 * temperature cards, print controls, and speed selector.
 *
 * Chamber temperature is hidden for P1-series and A1-series printers which have
 * no chamber sensor, identified by either settings.printerModel or the MQTT
 * printer_type field (raw codes: C11=P1P, C12=P1S, N1=A1Mini, N2S=A1).
 */
@Composable
fun HomeScreen(viewModel: PrinterViewModel) {
    val status by viewModel.printerStatus.collectAsState()
    val connState by viewModel.connectionState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var showStopDialog by remember { mutableStateOf(false) }

    // Use cloud model name first (reliable), MQTT printer_type as fallback.
    val modelKey = settings.printerModel.uppercase().trim()
        .ifBlank { status.printerType.uppercase().trim() }
    val showChamber = hasChamberSensor(modelKey)

    if (showStopDialog) {
        StopConfirmDialog(
            onConfirm = {
                viewModel.stopPrint()
                showStopDialog = false
            },
            onDismiss = { showStopDialog = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConnectionStatusBar(connState, onReconnect = { viewModel.connect() })

        // ── Main print status card (circular donut + info) ────────────────────
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Circular donut progress
                Box(
                    modifier = Modifier.size(130.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { status.printPercent / 100f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 12.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${status.printPercent}%",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        PrintStatusChip(status.printStatus)
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Print info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = status.subtaskName.ifBlank {
                            status.gcodeFile.ifBlank { "No Job" }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status.totalLayers > 0) {
                        InfoRow(
                            icon = "≡",
                            text = "Layer ${status.currentLayer} / ${status.totalLayers}",
                        )
                    }
                    InfoRow(icon = "⏱", text = status.formattedTimeRemaining)
                    val modelLabel = settings.printerName.ifBlank { settings.printerModel }
                    if (modelLabel.isNotBlank()) {
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        // ── Temperature cards ─────────────────────────────────────────────────
        TemperatureRow(status, showChamber)

        // ── Print controls ────────────────────────────────────────────────────
        PrintControlRow(
            status = status,
            onPause = { viewModel.pausePrint() },
            onResume = { viewModel.resumePrint() },
            onStop = { showStopDialog = true },
            onToggleLight = { viewModel.toggleChamberLight() },
        )

        // ── Speed level ───────────────────────────────────────────────────────
        SpeedLevelRow(
            currentLevel = status.speedLevel,
            onSelect = { viewModel.setSpeedLevel(it.level) },
        )
    }
}

// ── Connection status bar ─────────────────────────────────────────────────────

@Composable
private fun ConnectionStatusBar(state: ConnectionState, onReconnect: () -> Unit) {
    val (label, color) = when (state) {
        ConnectionState.CONNECTED    -> "Connected"         to StatusRunning
        ConnectionState.CONNECTING   -> "Connecting…"       to StatusPaused
        ConnectionState.ERROR        -> "Connection error"  to StatusFailed
        ConnectionState.DISCONNECTED -> "Disconnected"      to StatusIdle
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = color) }
            Spacer(Modifier.width(6.dp))
            Text(label, color = color, fontWeight = FontWeight.Medium)
        }
        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING) {
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        } else if (state == ConnectionState.CONNECTING) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

// ── Small icon+text info row ──────────────────────────────────────────────────

@Composable
private fun InfoRow(icon: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Print status chip ─────────────────────────────────────────────────────────

@Composable
private fun PrintStatusChip(status: PrintStatus) {
    val (label, color) = when (status) {
        PrintStatus.RUNNING                   -> "Printing"  to StatusRunning
        PrintStatus.PAUSED                    -> "Paused"    to StatusPaused
        PrintStatus.FINISHED                  -> "Finished"  to StatusFinished
        PrintStatus.FAILED                    -> "Failed"    to StatusFailed
        PrintStatus.PREPARE                   -> "Preparing" to StatusPaused
        PrintStatus.IDLE, PrintStatus.UNKNOWN -> "Idle"      to StatusIdle
    }
    Text(
        text = label,
        color = color,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium,
    )
}

// ── Temperature row ───────────────────────────────────────────────────────────

@Composable
private fun TemperatureRow(status: PrinterStatus, showChamber: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TemperatureCard(
            label = "Nozzle",
            current = status.nozzleTemperature,
            target = status.nozzleTargetTemperature,
            modifier = Modifier.weight(1f),
        )
        TemperatureCard(
            label = "Bed",
            current = status.bedTemperature,
            target = status.bedTargetTemperature,
            modifier = Modifier.weight(1f),
        )
        if (showChamber) {
            TemperatureCard(
                label = "Chamber",
                current = status.chamberTemperature,
                target = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TemperatureCard(
    label: String,
    current: Double,
    target: Double?,
    modifier: Modifier = Modifier,
) {
    val tempColor = when {
        current > 150 -> TempHot
        current > 50  -> Color(0xFFFFA726)
        else          -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${current.toInt()}°",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tempColor,
                textAlign = TextAlign.Center,
            )
            if (target != null) {
                Text(
                    text = "→${target.toInt()}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Print control buttons ─────────────────────────────────────────────────────

@Composable
private fun PrintControlRow(
    status: PrinterStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onToggleLight: () -> Unit,
) {
    val isPrinting = status.printStatus == PrintStatus.RUNNING
    val isPaused   = status.printStatus == PrintStatus.PAUSED

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Pause / Resume
        if (isPrinting) {
            Button(onClick = onPause, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
                Spacer(Modifier.width(4.dp))
                Text("Pause")
            }
        } else if (isPaused) {
            Button(
                onClick = onResume,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = StatusRunning),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                Spacer(Modifier.width(4.dp))
                Text("Resume")
            }
        } else {
            OutlinedButton(
                onClick = {},
                modifier = Modifier.weight(1f),
                enabled = false,
            ) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Pause")
            }
        }

        // Stop
        Button(
            onClick = onStop,
            modifier = Modifier.weight(1f),
            enabled = isPrinting || isPaused,
            colors = ButtonDefaults.buttonColors(containerColor = StatusFailed),
        ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop")
            Spacer(Modifier.width(4.dp))
            Text("Stop")
        }

        // Light toggle
        IconButton(onClick = onToggleLight) {
            Icon(
                Icons.Default.WbIncandescent,
                contentDescription = "Toggle Light",
                tint = if (status.chamberLedOn) Color.Yellow
                       else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── Speed level selector ──────────────────────────────────────────────────────

@Composable
private fun SpeedLevelRow(currentLevel: SpeedLevel, onSelect: (SpeedLevel) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Print Speed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpeedLevel.entries.forEach { level ->
                    val selected = level == currentLevel
                    Button(
                        onClick = { onSelect(level) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                                             else MaterialTheme.colorScheme.surface,
                            contentColor   = if (selected) MaterialTheme.colorScheme.onPrimary
                                             else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(level.label.take(3), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun StopConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Stop Print?") },
        text    = { Text("This will stop the current print job. Continue?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = StatusFailed),
            ) { Text("Stop") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
