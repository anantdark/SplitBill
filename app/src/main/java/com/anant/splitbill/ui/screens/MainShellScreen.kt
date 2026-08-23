package com.anant.splitbill.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.MemberBalance
import com.anant.splitbill.data.model.RoomDashboard
import com.anant.splitbill.data.model.ThemeMode
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.ui.util.rememberDismissKeyboard
import com.anant.splitbill.ui.viewmodel.UpdateUiState
import android.net.Uri

enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("Home", Icons.Filled.Home),
    History("History", Icons.Filled.History),
    Settings("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShellScreen(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    dashboard: RoomDashboard?,
    entries: List<EntryEntity>,
    settings: AppSettings,
    members: List<MemberBalance>,
    versionName: String,
    busy: Boolean,
    cloudSyncing: Boolean,
    cloudBackupAvailable: Boolean,
    onRecordRecharge: () -> Unit,
    onShare: () -> Unit,
    onDeleteRechargeGroup: (groupId: String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onCrashReporting: (Boolean) -> Unit,
    onDefaultMember: (String) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onSyncCloud: () -> Unit,
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onAutoCheckUpdatesChange: (Boolean) -> Unit,
    onCloudAutoSyncChanged: (Boolean) -> Unit,
    onPullCloud: () -> Unit,
    onDeveloperModeToggled: (Boolean) -> Unit,
    onDeveloperUnlockHint: (remainingTaps: Int) -> Unit,
    onDeveloperUnlockHintDismiss: () -> Unit = {},
    onEasterEggTriggered: () -> Unit = {},
    onAnantTapHint: (remainingTaps: Int) -> Unit = {},
    onAnantTapHintDismiss: () -> Unit = {},
    onAnantTapWhenUnlocked: () -> Unit = {},
    onRoomIdCopied: () -> Unit,
    onInvite: () -> Unit,
    onRegenerateSupportId: () -> Unit,
    onMongoOverrides: (String, String) -> Unit,
    onHeartDoubleTapHeartbeat: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dismissKeyboard = rememberDismissKeyboard()
    var visitedTabs by remember { mutableStateOf(setOf(selectedTab)) }
    LaunchedEffect(selectedTab) {
        visitedTabs = visitedTabs + selectedTab
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            MainTab.Home -> dashboard?.roomName ?: "SplitBill"
                            MainTab.History -> "History & usage"
                            MainTab.Settings -> "Settings"
                        }
                    )
                },
                actions = {
                    if (selectedTab == MainTab.Home) {
                        IconButton(
                            onClick = {
                                dismissKeyboard()
                                onInvite()
                            },
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = "Invite to room")
                        }
                        IconButton(onClick = onShare) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Share balances")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            dismissKeyboard()
                            onSelectTab(tab)
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == MainTab.Home) {
                ExtendedFloatingActionButton(
                    onClick = {
                        dismissKeyboard()
                        onRecordRecharge()
                    },
                    icon = { Icon(Icons.Filled.ElectricMeter, contentDescription = null) },
                    text = { Text("Log readings") },
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            MainTab.entries.forEach { tab ->
                if (tab !in visitedTabs) return@forEach
                val isSelected = tab == selectedTab
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (isSelected) 1f else 0f)
                        .graphicsLayer { alpha = if (isSelected) 1f else 0f }
                ) {
                    when (tab) {
                        MainTab.Home -> CloudRefreshableTab(
                            enabled = cloudBackupAvailable && settings.supportId.isNotBlank(),
                            isRefreshing = cloudSyncing,
                            onRefresh = onSyncCloud,
                        ) {
                            DashboardScreen(
                                dashboard = dashboard,
                                onRecordRecharge = onRecordRecharge,
                                onShare = onShare,
                                embedded = true,
                            )
                        }
                        MainTab.History -> CloudRefreshableTab(
                            enabled = cloudBackupAvailable && settings.supportId.isNotBlank(),
                            isRefreshing = cloudSyncing,
                            onRefresh = onSyncCloud,
                        ) {
                            HistoryScreen(
                                entries = entries,
                                currencySymbol = dashboard?.currencySymbol ?: "Rs.",
                                busy = busy,
                                onDeleteRechargeGroup = onDeleteRechargeGroup,
                                embedded = true,
                            )
                        }
                        MainTab.Settings -> SettingsScreen(
                            settings = settings,
                            members = members,
                            versionName = versionName,
                            busy = busy,
                            onThemeMode = onThemeMode,
                            onDynamicColor = onDynamicColor,
                            onCrashReporting = onCrashReporting,
                            onDefaultMember = onDefaultMember,
                            onShare = onShare,
                            onExport = onExport,
                            onImport = onImport,
                            onSyncCloud = onSyncCloud,
                            updateState = updateState,
                            onCheckForUpdates = onCheckForUpdates,
                            onAutoCheckUpdatesChange = onAutoCheckUpdatesChange,
                            onCloudAutoSyncChanged = onCloudAutoSyncChanged,
                            onPullCloud = onPullCloud,
                            onDeveloperModeToggled = onDeveloperModeToggled,
                            onDeveloperUnlockHint = onDeveloperUnlockHint,
                            onDeveloperUnlockHintDismiss = onDeveloperUnlockHintDismiss,
                            onEasterEggTriggered = onEasterEggTriggered,
                            onAnantTapHint = onAnantTapHint,
                            onAnantTapHintDismiss = onAnantTapHintDismiss,
                            onAnantTapWhenUnlocked = onAnantTapWhenUnlocked,
                            onRoomIdCopied = onRoomIdCopied,
                            onInvite = onInvite,
                            onRegenerateSupportId = onRegenerateSupportId,
                            onMongoOverrides = onMongoOverrides,
                            onHeartDoubleTapHeartbeat = onHeartDoubleTapHeartbeat,
                            embedded = true,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudRefreshableTab(
    enabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier.fillMaxSize()) {
            content()
        }
        return
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        content()
    }
}
