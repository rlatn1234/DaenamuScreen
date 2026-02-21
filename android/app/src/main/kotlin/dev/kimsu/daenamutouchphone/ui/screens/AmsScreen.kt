@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kimsu.daenamutouchphone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kimsu.daenamutouchphone.data.model.AmsStatus
import dev.kimsu.daenamutouchphone.data.model.AmsUnit
import dev.kimsu.daenamutouchphone.data.model.AmsTray
import dev.kimsu.daenamutouchphone.ui.theme.BambuGreen
import dev.kimsu.daenamutouchphone.viewmodel.PrinterViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AMS (Automatic Material System) screen — Bambu Lab style.
 *
 * Each AMS unit shows its 4 trays in a horizontal row. Each tray is rendered
 * as a coloured block (spool visualization) with the filament type and number
 * below it, matching the layout used on the Bambu Lab X1 touchscreen.
 *
 * Tapping a tray opens a FilamentEditDialog where the user can change type
 * (dropdown), manufacturer (editable dropdown auto-filled from MQTT), colour
 * and temperature range.
 *
 * Holding a tray for 5 seconds loads that filament. If another tray is
 * currently loaded it is unloaded first.
 *
 * A global "Unload" button at the top retracts the current filament back
 * into the AMS.
 */
@Composable
fun AmsScreen(viewModel: PrinterViewModel) {
    val status by viewModel.printerStatus.collectAsState()
    val amsStatus = status.amsStatus

    // Tray currently being edited (null = dialog hidden)
    var editingTray by remember { mutableStateOf<AmsTray?>(null) }

    editingTray?.let { tray ->
        val globalId = tray.amsIndex * 4 + (tray.trayIndex - 1)
        val isActive = amsStatus.trayNow == globalId
        FilamentEditDialog(
            tray = tray,
            isActive = isActive,
            onLoad = {
                viewModel.amsLoad(tray.amsIndex, tray.trayIndex - 1)
                editingTray = null
            },
            onSave = { type, vendor, colorHex, tempMin, tempMax ->
                viewModel.amsEditFilament(
                    tray.amsIndex, tray.trayIndex - 1,
                    type, vendor, colorHex, tempMin, tempMax,
                )
                editingTray = null
            },
            onDismiss = { editingTray = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!amsStatus.hasAms && !amsStatus.hasVirtualTray) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No AMS detected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Column
        }

        // ── Active tray label + Unload button ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActiveTrayLabel(amsStatus)
            OutlinedButton(
                onClick = { viewModel.amsUnload() },
                enabled = amsStatus.trayNow in 0..15,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("Unload", fontSize = 13.sp)
            }
        }

        // ── AMS units ────────────────────────────────────────────────────────
        amsStatus.units.forEach { unit ->
            AmsUnitCard(
                unit = unit,
                trayNow = amsStatus.trayNow,
                onTrayClick = { tray -> editingTray = tray },
                onTrayLongPress = { tray ->
                    viewModel.amsLoadWithUnload(tray.amsIndex, tray.trayIndex - 1)
                },
            )
        }

        // ── Virtual external spool ───────────────────────────────────────────
        if (amsStatus.hasVirtualTray) {
            VirtualTrayCard(
                colorHex = amsStatus.virtualTrayColorHex,
                filamentType = amsStatus.virtualTrayType,
                isActive = amsStatus.trayNow == 254 || amsStatus.trayNow == 255,
            )
        }
    }
}

// ── Active tray label ─────────────────────────────────────────────────────────

@Composable
private fun ActiveTrayLabel(amsStatus: AmsStatus) {
    val label = when {
        amsStatus.trayNow in 0..15 -> {
            val amsIdx  = amsStatus.trayNow / 4
            val trayIdx = amsStatus.trayNow % 4 + 1
            "Active: AMS ${amsIdx + 1}  ·  Tray $trayIdx"
        }
        amsStatus.trayNow == 254 || amsStatus.trayNow == 255 -> "Active: External spool"
        else -> "Active: None"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = BambuGreen,
        fontWeight = FontWeight.SemiBold,
    )
}

// ── AMS unit card ─────────────────────────────────────────────────────────────

@Composable
private fun AmsUnitCard(
    unit: AmsUnit,
    trayNow: Int,
    onTrayClick: (AmsTray) -> Unit,
    onTrayLongPress: (AmsTray) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AMS ${unit.index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (unit.humidity > 0) {
                        Text(
                            text = "💧 ${unit.humidity * 20}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (unit.temperatureCelsius > 0f) {
                        Text(
                            text = "🌡 ${unit.temperatureCelsius.toInt()}°C",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 4-slot horizontal tray row (Bambu style) ─────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (slotIdx in 0 until 4) {
                    val tray = unit.trays.getOrNull(slotIdx)
                    val globalTrayIdx = unit.index * 4 + slotIdx
                    if (tray != null) {
                        BambuTraySlot(
                            tray = tray,
                            isActive = trayNow == globalTrayIdx,
                            modifier = Modifier.weight(1f),
                            onClick = { onTrayClick(tray) },
                            onLongPress = { onTrayLongPress(tray) },
                        )
                    } else {
                        EmptyTraySlot(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ── Bambu-style tray slot (coloured block) ────────────────────────────────────

/**
 * A single AMS tray slot rendered as a Bambu-style coloured spool block.
 *
 * - Single tap: opens the filament edit dialog via [onClick].
 * - Hold for 5 seconds: triggers [onLongPress] (load this tray, unload first if needed).
 *   A circular progress ring is shown during the hold to indicate progress.
 */
@Composable
private fun BambuTraySlot(
    tray: AmsTray,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val borderColor = if (isActive) BambuGreen else MaterialTheme.colorScheme.surfaceVariant
    val slotBg = if (tray.colorHex.isNotBlank()) parseHexColor(tray.colorHex)
                 else MaterialTheme.colorScheme.surfaceVariant

    // Hold-to-load progress (0f = not pressing, >0f = holding)
    var holdProgress by remember(tray.amsIndex, tray.trayIndex) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerInput(tray.amsIndex, tray.trayIndex, onClick, onLongPress) {
                detectTapGestures(
                    onPress = { _ ->
                        var triggered = false
                        // Update progress ring ~60 fps while finger is down
                        val progressJob = scope.launch {
                            val start = System.currentTimeMillis()
                            while (true) {
                                delay(16L)
                                holdProgress = ((System.currentTimeMillis() - start) / 5000f)
                                    .coerceIn(0f, 1f)
                            }
                        }
                        // Trigger long-press action after 5 seconds
                        val triggerJob = scope.launch {
                            delay(5000L)
                            triggered = true
                            holdProgress = 0f
                            onLongPress()
                        }
                        val released = tryAwaitRelease()
                        progressJob.cancel()
                        triggerJob.cancel()
                        holdProgress = 0f
                        // If finger lifted before 5 s and nothing was triggered → open dialog
                        if (released && !triggered) onClick()
                    }
                )
            }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Coloured rectangle representing the spool
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(slotBg),
            contentAlignment = Alignment.Center,
        ) {
            when {
                holdProgress > 0f -> {
                    // 5-second hold progress ring
                    CircularProgressIndicator(
                        progress = { holdProgress },
                        modifier = Modifier.size(40.dp),
                        color = BambuGreen,
                        trackColor = Color.Black.copy(alpha = 0.25f),
                        strokeWidth = 4.dp,
                    )
                }
                isActive -> {
                    // Active indicator overlay
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                tray.colorHex.isBlank() -> {
                    // Empty-slot icon
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                }
            }
        }

        // Filament type label
        Text(
            text = tray.filamentType.ifBlank { if (tray.isLoaded) "?" else "" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (isActive) BambuGreen else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Tray number
        Text(
            text = "${tray.trayIndex}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyTraySlot(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "—",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontSize = 18.sp,
            )
        }
        // Reserve the same vertical space as BambuTraySlot's type-label + tray-number rows
        // so all slots have equal height (≈ 4 dp padding + labelMedium + 4 dp gap + labelSmall)
        Spacer(Modifier.height(36.dp))
    }
}

// ── Virtual external spool card ───────────────────────────────────────────────

@Composable
private fun VirtualTrayCard(colorHex: String, filamentType: String, isActive: Boolean) {
    val borderColor = if (isActive) BambuGreen else Color.Transparent
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(colorHex))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "External Spool",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = filamentType.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = BambuGreen,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Filament edit dialog ──────────────────────────────────────────────────────

private val FILAMENT_TYPES = listOf(
    "PLA", "PETG", "ABS", "ASA", "PA", "PA-CF",
    "PC", "PET-CF", "PLA-CF", "TPU", "PVA",
)

/** Well-known filament manufacturers for the vendor dropdown. */
private val FILAMENT_VENDORS = listOf(
    "Bambu Lab", "Polymaker", "eSUN", "Hatchbox", "SUNLU", "Prusament",
    "ColorFabb", "Fiberlogy", "FormFutura", "Overture", "ERYONE", "3DFuel",
)

private fun defaultMinTemp(type: String): Int = when (type.uppercase()) {
    "PLA", "PLA-CF"   -> 190
    "PETG", "PET-CF"  -> 220
    "ABS"             -> 220
    "ASA"             -> 230
    "PA", "PA-CF"     -> 270
    "PC"              -> 250
    "TPU"             -> 210
    else              -> 190
}

private fun defaultMaxTemp(type: String): Int = when (type.uppercase()) {
    "PLA", "PLA-CF"   -> 230
    "PETG", "PET-CF"  -> 250
    "ABS"             -> 260
    "ASA"             -> 270
    "PA", "PA-CF"     -> 300
    "PC"              -> 280
    "TPU"             -> 230
    else              -> 230
}

@Composable
private fun FilamentEditDialog(
    tray: AmsTray,
    isActive: Boolean,
    onLoad: () -> Unit,
    onSave: (type: String, vendor: String, colorHex: String, tempMin: Int, tempMax: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember(tray) {
        mutableStateOf(tray.filamentType.ifBlank { "PLA" })
    }
    var vendor by remember(tray) { mutableStateOf(tray.filamentVendor) }
    var colorHex by remember(tray) {
        mutableStateOf(tray.colorHex.ifBlank { "FFFFFF" })
    }
    var tempMin by remember(tray) {
        mutableStateOf(
            tray.nozzleTempMin.takeIf { it > 0 }?.toString()
                ?: defaultMinTemp(tray.filamentType.ifBlank { "PLA" }).toString()
        )
    }
    var tempMax by remember(tray) {
        mutableStateOf(
            tray.nozzleTempMax.takeIf { it > 0 }?.toString()
                ?: defaultMaxTemp(tray.filamentType.ifBlank { "PLA" }).toString()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tray ${tray.trayIndex}  ·  AMS ${tray.amsIndex + 1}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ── Filament type dropdown ────────────────────────────────────
                FilamentTypeDropdown(
                    selected = selectedType,
                    onSelect = { type ->
                        selectedType = type
                        if (tray.nozzleTempMin == 0) {
                            tempMin = defaultMinTemp(type).toString()
                            tempMax = defaultMaxTemp(type).toString()
                        }
                    },
                )

                // ── Manufacturer (editable dropdown, auto-filled from MQTT) ──
                VendorDropdown(
                    value = vendor,
                    onValueChange = { vendor = it },
                )

                // ── Colour ───────────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(colorHex))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    OutlinedTextField(
                        value = colorHex,
                        onValueChange = { raw ->
                            // Allow only uppercase hex digits, max 6 chars (RRGGBB)
                            val cleaned = raw.uppercase()
                                .filter { c -> c in '0'..'9' || c in 'A'..'F' }
                                .take(6)
                            colorHex = cleaned
                        },
                        label = { Text("Color (RRGGBB)") },
                        prefix = { Text("#") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                // ── Temperature range ────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tempMin,
                        onValueChange = {
                            tempMin = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = { Text("Min °C") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = tempMax,
                        onValueChange = {
                            tempMax = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = { Text("Max °C") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                // ── Load to nozzle (hidden for the already-active tray) ──────
                if (!isActive) {
                    Button(
                        onClick = onLoad,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                        ),
                    ) {
                        Text("Load to Nozzle")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    selectedType, vendor, colorHex,
                    tempMin.toIntOrNull() ?: defaultMinTemp(selectedType),
                    tempMax.toIntOrNull() ?: defaultMaxTemp(selectedType),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Dropdown composables ──────────────────────────────────────────────────────

/** Read-only dropdown for selecting a filament type. */
@Composable
private fun FilamentTypeDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Filament Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            FILAMENT_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type) },
                    onClick = { onSelect(type); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * Editable dropdown for the filament manufacturer.
 *
 * The field is pre-filled with the value received via MQTT ([AmsTray.filamentVendor]).
 * The user can also type a custom name or pick one of [FILAMENT_VENDORS].
 */
@Composable
private fun VendorDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text("Manufacturer") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true,
        )
        val suggestions = FILAMENT_VENDORS.filter {
            value.isBlank() || it.contains(value, ignoreCase = true)
        }
        if (suggestions.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                suggestions.forEach { vendor ->
                    DropdownMenuItem(
                        text = { Text(vendor) },
                        onClick = { onValueChange(vendor); expanded = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

// ── Colour helper ─────────────────────────────────────────────────────────────

/** Parse a 6-char RGB hex string (no '#') to [Color]. Returns gray for invalid/empty. */
internal fun parseHexColor(hex: String): Color {
    if (hex.length < 6) return Color(0xFF888888)
    return runCatching {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        Color(r, g, b)
    }.getOrElse { Color(0xFF888888) }
}
