package com.nectarmobiledevelopment.sambaloader.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DebugIdentitySection(viewModel: DebugIdentityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Debug: device identity", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = viewModel::generateIdentity,
                enabled = state !is DebugIdentityState.Working,
            ) {
                Text("Generate identity")
            }
            when (val current = state) {
                is DebugIdentityState.Idle -> Text("No identity generated yet.")
                is DebugIdentityState.Working -> CircularProgressIndicator()
                is DebugIdentityState.Generated -> {
                    Text("Backing: ${current.securityLevel}")
                    Text(
                        text = if (current.isKeyNonExtractable) {
                            "Private key: non-extractable ✔"
                        } else {
                            "Private key: EXTRACTABLE — this must never happen"
                        },
                    )
                    Text(
                        text = "Public key SHA-256: ${current.publicKeyFingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = current.csrPreview,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is DebugIdentityState.Error -> Text("Failed: ${current.message}")
            }
        }
    }
}
