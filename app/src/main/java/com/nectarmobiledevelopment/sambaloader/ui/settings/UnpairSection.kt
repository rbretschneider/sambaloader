package com.nectarmobiledevelopment.sambaloader.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Returns the app to its unpaired state so it can be set up again —
 * against a different server, or the same one rebuilt from scratch.
 */
@Composable
internal fun UnpairSection(onConfirm: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    SectionCard(title = "Server") {
        Text(
            "Disconnects this phone and forgets its backup history, so you " +
                "can pair again from scratch.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Your photos are not touched — only what this app remembers.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { showConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Unpair from server", color = MaterialTheme.colorScheme.error)
        }
    }

    if (!showConfirm) {
        return
    }
    AlertDialog(
        onDismissRequest = { showConfirm = false },
        title = { Text("Unpair from server?") },
        text = {
            // Spelled out because the second half surprises people: a new
            // server has never seen this library, so the app has to forget
            // what it already sent or the first sync would send nothing.
            Text(
                "This phone will stop backing up until you pair it again.\n\n" +
                    "Its identity key and the record of everything already " +
                    "uploaded are erased, so pairing again re-uploads your " +
                    "whole camera roll. The server skips anything it already " +
                    "has, so nothing is duplicated.\n\n" +
                    "No photos are deleted.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                showConfirm = false
                onConfirm()
            }) {
                Text("Unpair", color = UNPAIR_RED)
            }
        },
        dismissButton = {
            TextButton(onClick = { showConfirm = false }) {
                Text("Cancel")
            }
        },
    )
}

private val UNPAIR_RED = Color(0xFFB00020)
