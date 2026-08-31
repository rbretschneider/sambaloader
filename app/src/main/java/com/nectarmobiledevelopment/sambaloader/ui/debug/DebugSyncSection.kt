package com.nectarmobiledevelopment.sambaloader.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nectarmobiledevelopment.sambaloader.core.data.asset.AssetState

@Composable
fun DebugSyncSection(viewModel: DebugSyncViewModel = hiltViewModel()) {
    val counts by viewModel.countsByState.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Debug: sync pipeline", style = MaterialTheme.typography.titleMedium)
            Button(onClick = viewModel::scanNow) {
                Text("Scan now")
            }
            if (counts.isEmpty()) {
                Text("No assets tracked yet.")
            } else {
                for (state in AssetState.entries) {
                    val count = counts[state] ?: continue
                    Text("${state.name}: $count")
                }
            }
        }
    }
}
