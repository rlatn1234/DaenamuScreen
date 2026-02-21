package dev.kimsu.daenamutouchphone.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kimsu.daenamutouchphone.data.model.PrinterStatus
import dev.kimsu.daenamutouchphone.viewmodel.PrinterViewModel
import kotlin.math.roundToInt

/**
 * Control screen – mirrors ui_controlScreen.c from the ESP32 firmware.
 * Provides:
 *  - XY and Z axis jog controls
 *  - Bed temperature target control
 *  - Nozzle temperature target control
 *  - Fan speed display (read-only; set via GCode)
 */
@Composable
fun ControlScreen(viewModel: PrinterViewModel) {
    val status by viewModel.printerStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Axis jog panel
        AxisJogCard(
            onLeft = { viewModel.moveAxis("X", -it) },
            onRight = { viewModel.moveAxis("X", it) },
            onUp = { viewModel.moveAxis("Y", it) },
            onDown = { viewModel.moveAxis("Y", -it) },
            onBedUp = { viewModel.moveAxis("Z", it) },
            onBedDown = { viewModel.moveAxis("Z", -it) },
            onHome = { viewModel.homeAxes() },
        )

        // Nozzle temperature
        TemperatureControlCard(
            label = "Nozzle Temperature",
            currentTemp = status.nozzleTemperature,
            targetTemp = status.nozzleTargetTemperature,
            minTemp = 0,
            maxTemp = 300,
            onSetTarget = { viewModel.setNozzleTemperature(it) },
        )

        // Bed temperature
        TemperatureControlCard(
            label = "Bed Temperature",
            currentTemp = status.bedTemperature,
            targetTemp = status.bedTargetTemperature,
            minTemp = 0,
            maxTemp = 120,
            onSetTarget = { viewModel.setBedTemperature(it) },
        )

        // Fan speeds (read-only display)
        FanStatusCard(status)
    }
}

// ── Axis jog card ─────────────────────────────────────────────────────────────

@Composable
private fun AxisJogCard(
    onLeft: (Float) -> Unit,
    onRight: (Float) -> Unit,
    onUp: (Float) -> Unit,
    onDown: (Float) -> Unit,
    onBedUp: (Float) -> Unit,
    onBedDown: (Float) -> Unit,
    onHome: () -> Unit,
) {
    // Step sizes: 0.1, 1, 10 mm
    val steps = listOf(0.1f, 1f, 10f)
    var selectedStepIdx by remember { mutableIntStateOf(1) }
    val step = steps[selectedStepIdx]

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Axis Control",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            // Step selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Step:",
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelMedium,
                )
                steps.forEachIndexed { idx, s ->
                    val selected = idx == selectedStepIdx
                    Button(
                        onClick = { selectedStepIdx = idx },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.size(width = 60.dp, height = 36.dp),
                    ) {
                        Text("$s".trimEnd('0').trimEnd('.') + "mm")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // XY jog pad
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FilledTonalButton(onClick = { onUp(step) }) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Y+")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onLeft(step) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "X-")
                    }
                    FilledTonalButton(onClick = onHome) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    FilledTonalButton(onClick = { onRight(step) }) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "X+")
                    }
                }
                FilledTonalButton(onClick = { onDown(step) }) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Y-")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Z jog (bed up/down)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Z:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onBedUp(step) }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Z+")
                    Text("Up")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onBedDown(step) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Z-")
                    Text("Down")
                }
            }
        }
    }
}

// ── Temperature control card ───────────────────────────────────────────────────

@Composable
private fun TemperatureControlCard(
    label: String,
    currentTemp: Double,
    targetTemp: Double,
    minTemp: Int,
    maxTemp: Int,
    onSetTarget: (Int) -> Unit,
) {
    var sliderValue by remember(targetTemp) { mutableFloatStateOf(targetTemp.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "${currentTemp.toInt()}°C → ${sliderValue.roundToInt()}°C",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = minTemp.toFloat()..maxTemp.toFloat(),
                steps = maxTemp - minTemp - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${minTemp}°C", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(
                    onClick = { onSetTarget(sliderValue.roundToInt()) },
                ) {
                    Text("Set ${sliderValue.roundToInt()}°C")
                }
                Text("${maxTemp}°C", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Fan speed display ─────────────────────────────────────────────────────────

@Composable
private fun FanStatusCard(status: PrinterStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Fan Speeds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            FanRow("Part Cooling", status.coolingFanSpeed)
            FanRow("Auxiliary", status.auxFanSpeed)
            FanRow("Chamber", status.chamberFanSpeed)
        }
    }
}

@Composable
private fun FanRow(label: String, speed: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            "${(speed / 255f * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
