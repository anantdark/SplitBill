package com.anant.splitbill.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.backup.mongo.MongoUriVault
import com.anant.splitbill.data.model.MemberBalance
import com.anant.splitbill.data.model.ThemeMode
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.components.CraftedWithLoveCredit
import com.anant.splitbill.ui.components.RainbowCreditBadge
import com.anant.splitbill.ui.components.OutlinedButton
import androidx.compose.ui.text.style.TextDecoration
import com.anant.splitbill.ui.util.dismissKeyboardOnTap
import com.anant.splitbill.ui.viewmodel.UpdateUiState
import kotlinx.coroutines.delay

private const val DEVELOPER_UNLOCK_TAPS = 31
private const val DEVELOPER_HINT_START = DEVELOPER_UNLOCK_TAPS - 5
private const val EASTER_EGG_TAP_TARGET = 31
private const val EASTER_EGG_HINT_START = 25
private const val GITHUB_URL = "https://github.com/anantdark"
private const val SPLITBILL_SITE_URL = "https://anantdark.github.io/SplitBill/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    members: List<MemberBalance> = emptyList(),
    versionName: String,
    busy: Boolean,
    onBack: (() -> Unit)? = null,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onCrashReporting: (Boolean) -> Unit,
    onDefaultMember: (String) -> Unit = {},
    onShare: () -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onSyncCloud: () -> Unit,
    updateState: UpdateUiState = UpdateUiState(),
    onCheckForUpdates: () -> Unit = {},
    onAutoCheckUpdatesChange: (Boolean) -> Unit = {},
    onCloudAutoSyncChanged: (Boolean) -> Unit = {},
    onPullCloud: () -> Unit = {},
    onDeveloperModeToggled: (Boolean) -> Unit,
    onDeveloperUnlockHint: (remainingTaps: Int) -> Unit = {},
    onDeveloperUnlockHintDismiss: () -> Unit = {},
    onEasterEggTriggered: () -> Unit = {},
    onAnantTapHint: (remainingTaps: Int) -> Unit = {},
    onAnantTapHintDismiss: () -> Unit = {},
    onAnantTapWhenUnlocked: () -> Unit = {},
    onRoomIdCopied: () -> Unit = {},
    onInvite: () -> Unit = {},
    onRegenerateSupportId: () -> Unit,
    onMongoOverrides: (String, String) -> Unit,
    onHeartDoubleTap: () -> Unit,
    embedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var packageTapCount by remember { mutableIntStateOf(0) }
    var anantTapCount by remember { mutableIntStateOf(0) }
    val developerUnlocked = settings.developerModeUnlocked

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExport) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    LaunchedEffect(packageTapCount) {
        if (packageTapCount in 1 until DEVELOPER_UNLOCK_TAPS) {
            delay(2_000)
            packageTapCount = 0
            onDeveloperUnlockHintDismiss()
        }
    }

    LaunchedEffect(anantTapCount) {
        if (anantTapCount in 1 until EASTER_EGG_TAP_TARGET) {
            delay(2_000)
            anantTapCount = 0
            onAnantTapHintDismiss()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                )
            }
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
                Button(
                    onClick = onInvite,
                    enabled = settings.supportId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Invite to room") }
                OutlinedButton(
                    onClick = {
                        val id = settings.supportId
                        if (id.isNotBlank()) {
                            clipboard.setText(AnnotatedString(id))
                            onRoomIdCopied()
                        }
                    },
                    enabled = settings.supportId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copy Room ID") }
            }

            if (members.isNotEmpty()) {
                SettingsSection("Who's using this phone") {
                    Text(
                        text = "Pick yourself from the room roster. Recharges you log are " +
                            "credited to this member by default — same choice as during setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    members.forEach { member ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDefaultMember(member.memberId) }
                        ) {
                            RadioButton(
                                selected = settings.defaultMemberId == member.memberId,
                                onClick = { onDefaultMember(member.memberId) }
                            )
                            Text(
                                text = member.name,
                                fontWeight = if (settings.defaultMemberId == member.memberId) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        }
                    }
                }
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
                        text = "Syncs on app start and about every hour when online. " +
                            "You’ll get a notification when new logs arrive or a recharge is deleted. " +
                            "Backups are gzip-compressed (not encrypted) and keyed by your Room ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Cloud sync is always on. On open (and when you tap Sync), " +
                            "the app pulls the latest cloud snapshot then pushes this device.",
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
                        text = "Cloud sync unavailable in this build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SettingsSection("Updates") {
                if (BuildConfig.IS_FDROID) {
                    val uriHandler = LocalUriHandler.current
                    Text(
                        text = "Updates are handled by F-Droid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "To get in-app updates, install from GitHub releases",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://github.com/anantdark/SplitBill/releases")
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Check for updates automatically", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Looks for a newer GitHub release shortly after startup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.autoCheckUpdates,
                            onCheckedChange = onAutoCheckUpdatesChange
                        )
                    }
                    OutlinedButton(
                        onClick = onCheckForUpdates,
                        enabled = !updateState.isChecking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (updateState.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Checking…")
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Check for updates")
                        }
                    }
                    updateState.statusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (updateState.statusIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            SettingsSection("About", initiallyExpanded = true) {
                AboutRow("App", "SplitBill")
                AboutRow(
                    label = "Version",
                    value = "$versionName (${BuildConfig.VERSION_CODE})"
                )
                AboutRow(
                    label = "Package",
                    value = BuildConfig.APPLICATION_ID,
                    onValueClick = {
                        packageTapCount++
                        when {
                            packageTapCount >= DEVELOPER_UNLOCK_TAPS -> {
                                packageTapCount = 0
                                onDeveloperUnlockHintDismiss()
                                onDeveloperModeToggled(!developerUnlocked)
                            }
                            packageTapCount >= DEVELOPER_HINT_START -> {
                                onDeveloperUnlockHint(DEVELOPER_UNLOCK_TAPS - packageTapCount)
                            }
                        }
                    }
                )
                AboutRow(
                    label = "Created by",
                    valueContent = {
                        val onAnantClick = {
                            if (settings.easterEggDiscovered) {
                                onAnantTapWhenUnlocked()
                            } else {
                                anantTapCount++
                                when {
                                    anantTapCount >= EASTER_EGG_TAP_TARGET -> {
                                        anantTapCount = 0
                                        onAnantTapHintDismiss()
                                        onEasterEggTriggered()
                                    }
                                    anantTapCount >= EASTER_EGG_HINT_START -> {
                                        onAnantTapHint(EASTER_EGG_TAP_TARGET - anantTapCount)
                                    }
                                }
                            }
                        }
                        RainbowCreditBadge(name = "Anant", onClick = onAnantClick)
                    }
                )
                AboutLinkRow("Website", "anantdark.github.io/SplitBill", SPLITBILL_SITE_URL)
                AboutLinkRow("GitHub", "github.com/anantdark", GITHUB_URL)
            }

            if (developerUnlocked) {
                SettingsSection("Developer") {
                    Text(
                        text = "Debug tools. Tap Package 31 times again to hide this card.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("Backup & identity", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Room ID is also the cloud Support ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        settings.supportId.ifBlank { "Not set" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    OutlinedButton(
                        onClick = onRegenerateSupportId,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Regenerate Room ID") }

                    if (MongoUriVault.isAvailable()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Cloud sync", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "When off, startup and hourly sync are skipped. " +
                                "Pull fetches cloud changes without uploading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Auto cloud sync")
                            Switch(
                                checked = settings.cloudAutoUploadEnabled,
                                onCheckedChange = onCloudAutoSyncChanged,
                                enabled = !busy
                            )
                        }
                        OutlinedButton(
                            onClick = onPullCloud,
                            enabled = !busy && settings.supportId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Pull from cloud") }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Cloud (Atlas)", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Device-local overrides. Defaults: " +
                                "${AppSettings.DEFAULT_MONGO_DB_NAME} / " +
                                AppSettings.DEFAULT_MONGO_COLLECTION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var mongoDbDraft by remember(settings.mongoDbName) {
                            mutableStateOf(
                                settings.mongoDbName.ifBlank { AppSettings.DEFAULT_MONGO_DB_NAME }
                            )
                        }
                        var mongoCollDraft by remember(settings.mongoCollectionName) {
                            mutableStateOf(
                                settings.mongoCollectionName.ifBlank {
                                    AppSettings.DEFAULT_MONGO_COLLECTION
                                }
                            )
                        }
                        OutlinedTextField(
                            value = mongoDbDraft,
                            onValueChange = { mongoDbDraft = it },
                            label = { Text("Database name") },
                            placeholder = { Text(AppSettings.DEFAULT_MONGO_DB_NAME) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mongoCollDraft,
                            onValueChange = { mongoCollDraft = it },
                            label = { Text("Collection name") },
                            placeholder = { Text(AppSettings.DEFAULT_MONGO_COLLECTION) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onMongoOverrides(
                                    mongoDbDraft.trim()
                                        .ifBlank { AppSettings.DEFAULT_MONGO_DB_NAME },
                                    mongoCollDraft.trim()
                                        .ifBlank { AppSettings.DEFAULT_MONGO_COLLECTION }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save Atlas db / collection") }
                    }
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
private fun SettingsSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
    onValueClick: (() -> Unit)? = null
) {
    AboutRow(
        label = label,
        valueContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = if (onValueClick != null) {
                    Modifier.clickable(onClick = onValueClick)
                } else {
                    Modifier
                }
            )
        }
    )
}

@Composable
private fun AboutRow(
    label: String,
    valueContent: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        valueContent()
    }
}

@Composable
private fun AboutLinkRow(label: String, value: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(url) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Filled.Code,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun cloudBackupStatusText(settings: AppSettings): String {
    val deviceCount = runCatching {
        com.anant.splitbill.data.backup.RoomSyncMeta.decodeDevices(settings.roomDevicesJson).size
    }.getOrDefault(0)
    val devicesPart = when {
        deviceCount <= 0 -> null
        deviceCount == 1 -> "1 device in this room"
        else -> "$deviceCount devices in this room"
    }
    val syncPart = when {
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
    return listOfNotNull(syncPart, devicesPart).joinToString(" · ")
}
