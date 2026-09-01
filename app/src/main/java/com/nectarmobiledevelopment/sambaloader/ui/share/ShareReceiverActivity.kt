package com.nectarmobiledevelopment.sambaloader.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nectarmobiledevelopment.sambaloader.ui.theme.SambaloaderTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Receives images and videos from the system share sheet.
 *
 * This screen stays in front until the bytes are copied, and that is
 * deliberate: the read grant on a shared URI lives only as long as this
 * task, so finishing early — or handing the URI to a worker — would leave
 * nothing to read. Once the copy is safe, the normal pipeline uploads it.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: ShareReceiverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = incomingUris()
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            SambaloaderTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ShareProgress(state)
                if (state is ShareUiState.Finished) {
                    Toast.makeText(
                        this,
                        (state as ShareUiState.Finished).message,
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }
            }
        }
        viewModel.importAll(uris)
    }

    /** Both the single- and multi-select share actions land here. */
    private fun incomingUris(): List<String> {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        return uris.map { it.toString() }.distinct()
    }
}

@Suppress("DEPRECATION") // the typed overloads only exist from API 33
private fun Intent.parcelableExtra(name: String): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        getParcelableExtra(name)
    }
}

@Suppress("DEPRECATION") // the typed overloads only exist from API 33
private fun Intent.parcelableArrayListExtra(name: String): List<Uri> {
    val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        getParcelableArrayListExtra<Uri>(name)
    }
    return values.orEmpty()
}

@androidx.compose.runtime.Composable
private fun ShareProgress(state: ShareUiState) {
    Surface(shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                when (state) {
                    is ShareUiState.Importing -> state.message
                    is ShareUiState.Finished -> state.message
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
