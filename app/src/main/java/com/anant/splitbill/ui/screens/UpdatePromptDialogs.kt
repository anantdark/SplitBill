package com.anant.splitbill.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anant.splitbill.BuildConfig
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.components.OutlinedButton
import com.anant.splitbill.ui.viewmodel.UpdateUiState
import com.anant.splitbill.util.ApkDownloader
import kotlinx.coroutines.delay

/** Update dialog — shown from [MainActivity] so startup checks work outside Settings. */
@Composable
fun UpdatePromptDialogs(
    updateState: UpdateUiState,
    cloudBackupEnabled: Boolean,
    onDismissUpdatePrompt: () -> Unit,
    onExportBackupAndDownload: (downloadUrl: String, fileName: String) -> Unit,
    onSkipBackupAndDownload: (downloadUrl: String, fileName: String) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    updateState.updateInfo?.let { info ->
        val highlights = remember(info.releaseNotes) { releaseNoteHighlights(info.releaseNotes) }
        val busy = updateState.isExportingBackup
        var skipCountdownSec by remember(info.versionCode, info.downloadUrl) { mutableIntStateOf(5) }
        LaunchedEffect(info.versionCode, info.downloadUrl) {
            skipCountdownSec = 5
            while (skipCountdownSec > 0) {
                delay(1_000)
                skipCountdownSec--
            }
        }
        val skipEnabled = !busy && skipCountdownSec == 0
        AlertDialog(
            onDismissRequest = { if (!busy) onDismissUpdatePrompt() },
            icon = {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("New version available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = info.versionName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Build ${info.versionCode}  ·  you have ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (highlights.isNotEmpty()) {
                        Text(
                            text = "What's new",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            highlights.forEach { line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("•", color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                    if (info.htmlUrl.isNotBlank()) {
                        TextButton(
                            onClick = { uriHandler.openUri(info.htmlUrl) },
                            modifier = Modifier.padding(start = 0.dp)
                        ) {
                            Text("View on GitHub")
                        }
                    }
                    Text(
                        text = if (cloudBackupEnabled) {
                            "Back up your data to the cloud before downloading."
                        } else {
                            "Export a local backup before downloading."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    updateState.backupStatusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (updateState.backupStatusIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    if (updateState.isExportingBackup) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val fileName = ApkDownloader.fileNameFor(info.versionName, info.versionCode)
                        Button(
                            onClick = { onExportBackupAndDownload(info.downloadUrl, fileName) },
                            enabled = !busy && !updateState.backupCompleted,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Export backup & download") }
                        OutlinedButton(
                            onClick = { onSkipBackupAndDownload(info.downloadUrl, fileName) },
                            enabled = skipEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (skipCountdownSec > 0) {
                                    "Skip backup & download ($skipCountdownSec)"
                                } else {
                                    "Skip backup & download"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }
}

private fun releaseNoteHighlights(raw: String, limit: Int = 6): List<String> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { line ->
            line.removePrefix("- ")
                .replace(Regex("""\s*\([0-9a-f]{7,40}\)\s*$"""), "")
                .trim()
        }
        .filter { it.isNotBlank() }
        .distinct()
        .take(limit)
        .toList()
