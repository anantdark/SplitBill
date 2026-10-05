package com.anant.splitbill.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import com.anant.splitbill.data.backup.CloudHistoryConflict

/** Ask whether to replace local room data with the cloud snapshot after a history conflict. */
@Composable
fun CloudOverwriteDialog(
    conflict: CloudHistoryConflict?,
    onOverwrite: () -> Unit,
    onKeepLocal: () -> Unit,
) {
    if (conflict == null) return

    AlertDialog(
        onDismissRequest = onKeepLocal,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        icon = {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Cloud sync conflict") },
        text = { Text(conflict.promptMessage) },
        confirmButton = {
            TextButton(onClick = onOverwrite) {
                Text("Overwrite from cloud")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepLocal) {
                Text("Keep local")
            }
        },
    )
}
