package com.nectarmobiledevelopment.sambaloader.ui.pairing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nectarmobiledevelopment.sambaloader.BuildConfig

@Composable
fun PairingScreen(
    onFinished: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            when (val current = state) {
                is PairingUiState.Scanning -> ScanningStep(
                    step = current,
                    onScanned = viewModel::onQrScanned,
                    onDevFetch = viewModel::onDevFetch,
                )
                is PairingUiState.ConfirmFingerprint -> ConfirmStep(
                    step = current,
                    onConfirm = viewModel::onFingerprintConfirmed,
                    onReject = viewModel::onFingerprintRejected,
                )
                is PairingUiState.Enrolling -> EnrollingStep()
                is PairingUiState.Done -> DoneStep(current, onFinished)
                is PairingUiState.Failed -> FailedStep(current, viewModel::onRetry)
            }
        }
    }
}

@Composable
private fun ScanningStep(
    step: PairingUiState.Scanning,
    onScanned: (String) -> Unit,
    onDevFetch: (String) -> Unit,
) {
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onScanned)
    }
    Text("Pair with server", style = MaterialTheme.typography.headlineSmall)
    Text(
        "On the server's admin page, click \"Enroll a device\", then scan the QR code it shows.",
        style = MaterialTheme.typography.bodyMedium,
    )
    step.problem?.let { problem ->
        Text(
            text = PairingMessages.forProblem(problem),
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the enrollment QR code")
                .setBeepEnabled(false),
        )
    }) {
        Text("Scan QR code")
    }
    if (BuildConfig.DEBUG) {
        ManualEntry(onScanned, onDevFetch)
    }
}

/** Debug builds only: paste the payload, or pull it from a dev server. */
@Composable
private fun ManualEntry(onScanned: (String) -> Unit, onDevFetch: (String) -> Unit) {
    var pasted by rememberSaveable { mutableStateOf("") }
    var devHost by rememberSaveable { mutableStateOf("10.0.2.2") }
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text("Debug: paste payload JSON") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    OutlinedButton(onClick = { onScanned(pasted) }, enabled = pasted.isNotBlank()) {
        Text("Use pasted payload")
    }
    OutlinedTextField(
        value = devHost,
        onValueChange = { devHost = it },
        label = { Text("Debug: dev server host") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedButton(onClick = { onDevFetch(devHost) }, enabled = devHost.isNotBlank()) {
        Text("Fetch payload from dev server")
    }
}

@Composable
private fun ConfirmStep(
    step: PairingUiState.ConfirmFingerprint,
    onConfirm: (String) -> Unit,
    onReject: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(step.suggestedLabel) }
    Text("Confirm the server", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Compare this fingerprint with the one shown on the admin page. " +
            "Only continue if they match exactly.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Server: ${step.payload.apiBaseUrl.host}")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = PairingMessages.displayFingerprint(step.payload.caFingerprint),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("Device name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onReject) {
            Text("Doesn't match")
        }
        Button(onClick = { onConfirm(label) }) {
            Text("It matches — enroll")
        }
    }
}

@Composable
private fun EnrollingStep() {
    CircularProgressIndicator()
    Text("Enrolling this device…")
}

@Composable
private fun DoneStep(step: PairingUiState.Done, onFinished: () -> Unit) {
    Text("Paired ✔", style = MaterialTheme.typography.headlineSmall)
    Text("This device is now paired with ${step.serverHost}.")
    if (step.verifiedDeviceCn != null) {
        Text("Server verified the connection as \"${step.verifiedDeviceCn}\".")
    } else {
        Text(
            "The pairing is saved, but the verification call did not go through yet. " +
                "It will be retried automatically.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = onFinished) {
        Text("Done")
    }
}

@Composable
private fun FailedStep(step: PairingUiState.Failed, onRetry: () -> Unit) {
    Text("Pairing failed", style = MaterialTheme.typography.headlineSmall)
    Text(
        text = PairingMessages.forError(step.error),
        color = MaterialTheme.colorScheme.error,
    )
    Button(onClick = onRetry) {
        Text("Try again")
    }
}
