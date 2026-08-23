package com.anant.splitbill.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.backup.mongo.MongoUriVault
import com.anant.splitbill.data.model.ThemeMode
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.components.CraftedWithLoveCredit
import com.anant.splitbill.ui.components.OutlinedButton
import com.anant.splitbill.ui.util.dismissKeyboardOnTap

private const val DEVELOPER_UNLOCK_TAPS = 31
private const val DEVELOPER_HINT_START = DEVELOPER_UNLOCK_TAPS - 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    versionName: String,
    busy: Boolean,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onCrashReporting: (Boolean) -> Unit,
    onShare: () -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onSyncCloud: () -> Unit,
    onDeveloperUnlock: () -> Unit,
    onRegenerateSupportId: () -> Unit,
    onMongoOverrides: (String, String) -> Unit,
    onHeartDoubleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var packageTapCount by remember { mutableIntStateOf(0) }
    var developerHint by remember { mutableStateOf<String?>(null) }
    var mongoDb by remember(settings.mongoDbName) { mutableStateOf(settings.mongoDbName) }
    var mongoColl by remember(settings.mongoCollectionName) {
        mutableStateOf(settings.mongoCollectionName)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExport) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .dismissKeyboardOnTap(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            SettingsSection("Appearance") {
                ThemeMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeMode(mode) }
                        )
                        Text(mode.name.lowercase().replaceFirstChar { it.titlecase() })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dynamic color (Material You)")
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = onDynamicColor
                    )
                }
            }

            if (!BuildConfig.IS_FDROID) {
                SettingsSection("Privacy") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Crash reports", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Anonymous only — no balances or names",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.crashReportingEnabled,
                            onCheckedChange = onCrashReporting
                        )
                    }
                }
            }

            
            SettingsSection("Room") {
                val clipboard = LocalClipboardManager.current
                Text("Room ID", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Share this ID so others can join and sync the same meter log. " +
                        "It is also your cloud Support ID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = settings.supportId.ifBlank { "Not set yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                OutlinedButton(
                    onClick = {
                        val id = settings.supportId
                        if (id.isNotBlank()) {
                            clipboard.setText(AnnotatedString(id))
                        }
                    },
                    enabled = settings.supportId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copy Room ID") }
            }

SettingsSection("Backup") {
                OutlinedButton(
                    onClick = { exportLauncher.launch("SplitBill-backup.json") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export local backup") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import local backup") }
                OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Text("Share balances")
                }

                if (MongoUriVault.isAvailable()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Cloud",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Encrypted backups keyed by your Room ID. " +
                            "Uses the FitBuddy cloud proxy with a SplitBill database name — no new cluster required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Cloud sync is always on. On open (and when you tap Sync), " +
                            "the app pulls the latest cloud snapshot then pushes this device — " +
                            "payloads are gzip-compressed and encrypted with your Room ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = cloudBackupStatusText(settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onSyncCloud,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sync now") }
                } else {
                    Text(
                        text = "Cloud sync unavailable in this build. " +
                            "Set SPLITBILL_BACKUP_API_KEY_BLOB in local.properties (same proxy as FitBuddy).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (settings.developerModeUnlocked) {
                SettingsSection("Developer") {
                    Text("Room ID (Support ID)", fontWeight = FontWeight.SemiBold)
                    Text(
                        settings.supportId,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    OutlinedButton(onClick = onRegenerateSupportId, modifier = Modifier.fillMaxWidth()) {
                        Text("Regenerate Room ID")
                    }
                    if (MongoUriVault.isAvailable()) {
                        OutlinedTextField(
                            value = mongoDb,
                            onValueChange = { mongoDb = it },
                            label = { Text("Mongo DB name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mongoColl,
                            onValueChange = { mongoColl = it },
                            label = { Text("Mongo collection") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { onMongoOverrides(mongoDb, mongoColl) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save Mongo overrides") }
                    }
                }
            }

            SettingsSection("About") {
                Text(
                    text = "SplitBill $versionName",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        packageTapCount++
                        when {
                            packageTapCount >= DEVELOPER_UNLOCK_TAPS -> {
                                packageTapCount = 0
                                developerHint = null
                                onDeveloperUnlock()
                            }
                            packageTapCount >= DEVELOPER_HINT_START -> {
                                developerHint = "${DEVELOPER_UNLOCK_TAPS - packageTapCount} taps to unlock developer mode"
                            }
                        }
                    }
                )
                Text(
                    text = BuildConfig.APPLICATION_ID,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                developerHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            CraftedWithLoveCredit(
                onHeartDoubleTap = onHeartDoubleTap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}


private fun cloudBackupStatusText(settings: AppSettings): String = when {
    settings.lastCloudBackupAtEpochMs <= 0L -> "Waiting for first sync"
    else -> {
        val agoMs = System.currentTimeMillis() - settings.lastCloudBackupAtEpochMs
        val hours = agoMs / (60L * 60L * 1000L)
        when {
            hours < 1L -> "Last sync less than an hour ago"
            hours < 24L -> "Last sync ${hours}h ago"
            else -> "Last sync ${hours / 24L}d ago"
        }
    }
}
