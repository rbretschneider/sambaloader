package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(
    onPairClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Sambaloader", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            uiState.serverHost?.let { host ->
                Text(text = "Server: $host", style = MaterialTheme.typography.bodySmall)
            }

            if (!uiState.isEnrolled) {
                Button(onClick = onPairClick) {
                    Text("Pair with server")
                }
            } else {
                StatusCard(uiState)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::syncNow) {
                        Text("Back up now")
                    }
                    OutlinedButton(onClick = onSettingsClick) {
                        Text("Settings")
                    }
                }
            }

            Text(text = "v${uiState.appVersion}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusCard(uiState: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusRow("Backed up", uiState.uploadedCount.toString())
            StatusRow("Waiting", uiState.pendingCount.toString())
            if (uiState.failedCount > 0) {
                StatusRow("Failed", uiState.failedCount.toString())
            }
            if (uiState.deletedCount > 0) {
                StatusRow("Freed on this phone", uiState.deletedCount.toString())
            }
            StatusRow("Backing up", uiState.backedUpFolderSummary)
            StatusRow("Network", if (uiState.isWifiOnly) "Wi-Fi only" else "Wi-Fi or cellular")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
