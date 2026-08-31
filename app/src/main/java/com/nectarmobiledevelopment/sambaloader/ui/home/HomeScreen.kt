package com.nectarmobiledevelopment.sambaloader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nectarmobiledevelopment.sambaloader.BuildConfig
import com.nectarmobiledevelopment.sambaloader.ui.debug.DebugIdentitySection
import com.nectarmobiledevelopment.sambaloader.ui.debug.DebugSyncSection

@Composable
fun HomeScreen(
    onPairClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Sambaloader",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "v${uiState.appVersion}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!uiState.isEnrolled) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onPairClick) {
                    Text("Pair with server")
                }
            }
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(32.dp))
                DebugSyncSection()
                Spacer(modifier = Modifier.height(16.dp))
                DebugIdentitySection()
            }
        }
    }
}
