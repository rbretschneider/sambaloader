package com.nectarmobiledevelopment.sambaloader.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState
import com.nectarmobiledevelopment.sambaloader.core.data.settings.SyncSettings

@Composable
fun DebugSyncSection(viewModel: DebugSyncViewModel = hiltViewModel()) {
    val counts by viewModel.countsByState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Debug: sync pipeline", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::scanNow) {
                    Text("Scan now")
                }
                Button(onClick = viewModel::deleteNow) {
                    Text("Delete now")
                }
            }
            if (counts.isEmpty()) {
                Text("No assets tracked yet.")
            } else {
                for (state in AssetState.entries) {
                    val count = counts[state] ?: continue
                    Text("${state.name}: $count")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.isLocalDeletionEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setLocalDeletion(enabled, settings.retentionDays)
                    },
                )
                Text("Delete local copies after ${settings.retentionDays} day(s)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (days in SyncSettings.RETENTION_CHOICES_DAYS) {
                    OutlinedButton(onClick = {
                        viewModel.setLocalDeletion(settings.isLocalDeletionEnabled, days)
                    }) {
                        Text("$days")
                    }
                }
            }
            Text(
                text = if (viewModel.canDeleteSilently()) {
                    "All-files access: granted"
                } else {
                    "All-files access: NOT granted — deletion stays pending"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
