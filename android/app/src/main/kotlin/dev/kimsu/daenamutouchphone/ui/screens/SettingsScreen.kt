@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.kimsu.daenamutouchphone.data.model.AppSettings
import dev.kimsu.daenamutouchphone.network.CloudPrinter
import dev.kimsu.daenamutouchphone.viewmodel.CloudLoginState
import dev.kimsu.daenamutouchphone.viewmodel.PrinterViewModel

/**
 * Settings screen — Bambu Cloud only mode.
 * LAN mode has been removed; all connections go through Bambu Cloud MQTT.
 */
@Composable
fun SettingsScreen(viewModel: PrinterViewModel) {
    val settings by viewModel.settings.collectAsState()
    val loginState by viewModel.cloudLoginState.collectAsState()
    val cloudPrinters by viewModel.cloudPrinters.collectAsState()

    // Local editable copy of settings (no-key remember = DataStore re-emissions don't reset it).
    var draft by remember { mutableStateOf(settings) }
    // Seed draft from DataStore the first time a non-default value arrives.
    var seeded by remember { mutableStateOf(false) }
    if (!seeded && settings != AppSettings()) {
        draft = settings.copy(lanOnlyMode = false) // always cloud mode
        seeded = true
    }
    // Auth fields are written by the login flow; sync them into draft so that
    // "My Printers" button visibility and connect(draft) always have a fresh token.
    if (seeded && settings.cloudAuthToken.isNotBlank() &&
        settings.cloudAuthToken != draft.cloudAuthToken) {
        draft = draft.copy(
            cloudAuthToken = settings.cloudAuthToken,
            cloudMqttUsername = settings.cloudMqttUsername,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        // ── Cloud mode settings ───────────────────────────────────────────────
        CloudSettingsSection(
            draft = draft,
            onDraftChange = { draft = it },
            loginState = loginState,
            cloudPrinters = cloudPrinters,
            onLogin = { email, pwd -> viewModel.cloudLogin(email, pwd) },
            onSubmitEmailCode = { code -> viewModel.submitEmailCode(code) },
            onSubmitTfaCode = { code -> viewModel.submitTfaCode(code) },
            onResetLogin = { viewModel.resetLoginState() },
            onFetchPrinters = { viewModel.fetchCloudPrinters() },
            onSelectPrinter = { viewModel.selectCloudPrinter(it) },
        )

        // ── Printer name ──────────────────────────────────────────────────────
        SettingTextField(
            label = "Printer Name (optional)",
            value = draft.printerName,
            onValueChange = { draft = draft.copy(printerName = it) },
        )

        HorizontalDivider()

        // ── Save + Connect buttons ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { draft = settings.copy(lanOnlyMode = false) },
                modifier = Modifier.weight(1f),
            ) { Text("Reset") }
            Button(
                onClick = {
                    // Force lanOnlyMode=false so existing DataStore data with lanOnlyMode=true
                    // is overwritten on next save — completing the LAN-mode removal.
                    val toSave = draft.copy(lanOnlyMode = false)
                    viewModel.saveSettings(toSave)
                    viewModel.connect(toSave)
                },
                modifier = Modifier.weight(1f),
                enabled = draft.serialNumber.isNotBlank() && draft.cloudAuthToken.isNotBlank(),
            ) { Text("Save & Connect") }
        }
    }
}

// ── Cloud settings ────────────────────────────────────────────────────────────

@Composable
private fun CloudSettingsSection(
    draft: AppSettings,
    onDraftChange: (AppSettings) -> Unit,
    loginState: CloudLoginState,
    cloudPrinters: List<CloudPrinter>,
    onLogin: (String, String) -> Unit,
    onSubmitEmailCode: (String) -> Unit,
    onSubmitTfaCode: (String) -> Unit,
    onResetLogin: () -> Unit,
    onFetchPrinters: () -> Unit,
    onSelectPrinter: (CloudPrinter) -> Unit,
) {
    var email by remember(draft.cloudEmail) { mutableStateOf(draft.cloudEmail) }
    var password by remember { mutableStateOf("") }
    val regions = listOf("Global", "China")
    var regionIdx by remember(draft.cloudRegion) {
        mutableStateOf(if (draft.cloudRegion == "China") 1 else 0)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("Bambu Cloud", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }

            // When 2FA is in progress, show only the code-entry step (not email/password again)
            val awaitingCode = loginState is CloudLoginState.RequiresEmailCode ||
                    loginState is CloudLoginState.RequiresTfa

            if (!awaitingCode) {
                // Region picker
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    regions.forEachIndexed { idx, region ->
                        SegmentedButton(
                            selected = regionIdx == idx,
                            onClick = {
                                regionIdx = idx
                                onDraftChange(draft.copy(cloudRegion = region))
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = regions.size),
                        ) { Text(region) }
                    }
                }

                SettingTextField(
                    label = "Email",
                    value = email,
                    onValueChange = { email = it },
                    keyboardType = KeyboardType.Email,
                )
                SettingTextField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    isPassword = true,
                )
            }

            // ── 2FA / code-entry panels ───────────────────────────────────────
            if (loginState is CloudLoginState.RequiresEmailCode) {
                TwoFactorCodePanel(
                    title = "Email Verification",
                    subtitle = "A 6-digit code was sent to ${maskEmail(email)}. Enter it below.",
                    onSubmit = onSubmitEmailCode,
                    onCancel = onResetLogin,
                    isLoading = loginState.isLoading,
                    error = loginState.error,
                )
            }

            if (loginState is CloudLoginState.RequiresTfa) {
                TwoFactorCodePanel(
                    title = "Two-Factor Authentication",
                    subtitle = "Enter the 6-digit code from your authenticator app.",
                    onSubmit = onSubmitTfaCode,
                    onCancel = onResetLogin,
                    isLoading = loginState.isLoading,
                    error = loginState.error,
                )
            }

            // ── Status feedback ───────────────────────────────────────────────
            when (loginState) {
                CloudLoginState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Please wait…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is CloudLoginState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            loginState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                CloudLoginState.Success -> {
                    Text(
                        "✓ Logged in",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {}
            }

            // Login button — only when not in the middle of 2FA
            if (!awaitingCode) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onLogin(email, password) },
                        enabled = loginState !is CloudLoginState.Loading,
                        modifier = Modifier.weight(1f),
                    ) { Text("Login") }
                    if (draft.cloudAuthToken.isNotBlank()) {
                        OutlinedButton(
                            onClick = onFetchPrinters,
                            modifier = Modifier.weight(1f),
                        ) { Text("My Printers") }
                    }
                }
            }

            // Printer list from cloud
            if (cloudPrinters.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Select a printer:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                cloudPrinters.forEach { printer ->
                    val isSelected = printer.serialNumber == draft.serialNumber
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    printer.name.ifBlank { printer.serialNumber },
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    printer.model.ifBlank { printer.serialNumber },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                onSelectPrinter(printer)
                                onDraftChange(
                                    draft.copy(
                                        serialNumber = printer.serialNumber,
                                        printerName = printer.name,
                                        printerModel = printer.model,
                                        accessCode = printer.accessCode,
                                    )
                                )
                            }) {
                                Text(if (isSelected) "Selected" else "Select")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 2FA code-entry panel ──────────────────────────────────────────────────────

@Composable
private fun TwoFactorCodePanel(
    title: String,
    subtitle: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    isLoading: Boolean,
    error: String? = null,
) {
    // Reset the typed code whenever an error arrives so the user can enter a fresh code
    var code by remember(error) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() } },
            label = { Text("Verification Code") },
            placeholder = { Text("000000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            isError = error != null,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
            ) {
                Text("Back")
            }
            Button(
                onClick = { onSubmit(code) },
                enabled = code.length == 6 && !isLoading,
                modifier = Modifier.weight(1f),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Verify")
                }
            }
        }
    }
}

// ── Reusable input field ──────────────────────────────────────────────────────

/** Partially masks an email address for privacy: "u***@example.com". */
private fun maskEmail(email: String): String {
    val atIdx = email.indexOf('@')
    if (atIdx <= 1) return email
    return "${email[0]}***${email.substring(atIdx)}"
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) ({ Text(placeholder) }) else null,
        singleLine = true,
        visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = if (isPassword) {
            {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Hide" else "Show", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else null,
    )
}
