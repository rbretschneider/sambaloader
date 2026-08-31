package com.nectarmobiledevelopment.sambaloader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nectarmobiledevelopment.sambaloader.core.data.settings.WifiRequirement

/**
 * Chooses when uploads are allowed to run: whether mobile data may be
 * used at all, for how big a file, and whether the phone must be charging.
 */
@Composable
internal fun NetworkSection(
    state: SettingsUiState,
    onWifiRequirementChange: (WifiRequirement) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onRequiresChargingChange: (Boolean) -> Unit,
) {
    SectionCard(title = "When to back up") {
        Text("Wi-Fi required", style = MaterialTheme.typography.bodyLarge)
        for (requirement in WifiRequirement.entries) {
            val (label, description) = requirement.describe(state.largeFileThresholdMb)
            RadioRow(
                label = label,
                description = description,
                selected = state.wifiRequirement == requirement,
                onSelect = { onWifiRequirementChange(requirement) },
            )
        }

        if (state.wifiRequirement == WifiRequirement.FOR_LARGE_FILES) {
            Text("Anything this size or larger waits for Wi-Fi:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sizeMb in WifiRequirement.LARGE_FILE_CHOICES_MB) {
                    FilterChip(
                        selected = state.largeFileThresholdMb == sizeMb,
                        onClick = { onThresholdChange(sizeMb) },
                        label = { Text("$sizeMb MB") },
                    )
                }
            }
            Text(
                "Photos are usually 2–4 MB; a minute of 4K video is around 350 MB.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ToggleRow(
            label = "Only while charging",
            description = "Saves battery, but photos wait until you plug in.",
            checked = state.requiresCharging,
            onCheckedChange = onRequiresChargingChange,
        )
        Text(
            "\"Back up now\" on the home screen always runs, regardless of these settings.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Label and explanation for one Wi-Fi choice. */
private fun WifiRequirement.describe(thresholdMb: Int): Pair<String, String> = when (this) {
    WifiRequirement.ALWAYS ->
        "Always" to "Nothing is uploaded on mobile data."
    WifiRequirement.FOR_LARGE_FILES ->
        "Only for large files" to
            "Photos upload straight away; anything $thresholdMb MB or bigger waits for Wi-Fi."
    WifiRequirement.NEVER ->
        "Never" to "Upload on any connection, including mobile data."
}

@Composable
private fun RadioRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
