package com.nectarmobiledevelopment.sambaloader.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nectarmobiledevelopment.sambaloader.oem.OemAutostartSettings

@Composable
fun HomeScreen(
    onPairClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permissions and battery settings change outside the app; re-check
    // whenever the user comes back so a fixed problem stops nagging (and
    // a newly-broken one starts).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            uiState.warning?.let { warning ->
                WarningCard(warning = warning, onAction = { onWarningAction(context, warning) })
            }
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
                ConfigCard(uiState)
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

/**
 * A problem that stops backups from working. Deliberately loud: the
 * failure modes it covers (partial photo access, OEM background kills)
 * are invisible otherwise — the app would look like it is working.
 */
@Composable
private fun WarningCard(warning: HomeUiState.Warning, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(warning.title, style = MaterialTheme.typography.titleMedium)
            Text(warning.detail, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) {
                Text(warning.actionLabel)
            }
        }
    }
}

/**
 * Sends the user where the problem can actually be fixed. Vendor
 * autostart screens are undocumented and vanish between OS versions, so
 * every launch falls back to the app's own settings page.
 */
private fun onWarningAction(context: Context, warning: HomeUiState.Warning) {
    val intent = when (warning) {
        HomeUiState.Warning.NO_MEDIA_ACCESS,
        HomeUiState.Warning.PARTIAL_MEDIA_ACCESS,
        -> appSettingsIntent(context)

        HomeUiState.Warning.SYNC_STALLED ->
            OemAutostartSettings.intentFor(Build.MANUFACTURER)
                ?.takeIf { it.resolveActivity(context.packageManager) != null }
                ?: batterySettingsIntent(context)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(appSettingsIntent(context)) } }
}

private fun appSettingsIntent(context: Context): Intent {
    return Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun batterySettingsIntent(context: Context): Intent {
    val ignoreOptimizations = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    return if (ignoreOptimizations.resolveActivity(context.packageManager) != null) {
        ignoreOptimizations.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        appSettingsIntent(context)
    }
}

@Composable
private fun StatusCard(uiState: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.titleSmall)
            StatusRow("Backed up", uiState.uploadedCount.toString())
            StatusRow("Waiting", uiState.pendingCount.toString())
            if (uiState.failedCount > 0) {
                StatusRow("Failed", uiState.failedCount.toString())
            }
            if (uiState.deletedCount > 0) {
                StatusRow("Freed on this phone", uiState.deletedCount.toString())
            }
            if (uiState.waitingForWifiCount > 0) {
                StatusRow("Waiting for Wi-Fi", uiState.waitingForWifiCount.toString())
            }
        }
    }
}

/** The current configuration, visible without opening Settings. */
@Composable
private fun ConfigCard(uiState: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Configuration", style = MaterialTheme.typography.titleSmall)
            StatusRow("Folders", uiState.backedUpFolderSummary)
            StatusRow("Delay", uiState.uploadDelaySummary)
            StatusRow("Wi-Fi required", uiState.wifiRequirementSummary)
            StatusRow("Charging required", uiState.requiresCharging.asYesNo())
        }
    }
}

private fun Boolean.asYesNo(): String = if (this) "Yes" else "No"

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}
