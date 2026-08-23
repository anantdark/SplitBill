package com.anant.splitbill

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anant.splitbill.data.backup.mongo.MongoUriVault
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.ui.RequestSyncNotificationPermission
import com.anant.splitbill.ui.components.AnantEasterEggDialog
import com.anant.splitbill.ui.screens.MainShellScreen
import com.anant.splitbill.ui.screens.MainTab
import com.anant.splitbill.ui.screens.OnboardingScreen
import com.anant.splitbill.ui.screens.PickDefaultMemberScreen
import com.anant.splitbill.ui.screens.RecordRechargeScreen
import com.anant.splitbill.ui.screens.UpdatePromptDialogs
import com.anant.splitbill.ui.theme.SplitBillTheme
import com.anant.splitbill.ui.util.dismissKeyboardOnTap
import com.anant.splitbill.ui.viewmodel.AppDestination
import com.anant.splitbill.ui.viewmodel.MainViewModel
import com.anant.splitbill.ui.viewmodel.MainViewModelFactory
import com.anant.splitbill.util.ApkDownloader
import com.anant.splitbill.util.SystemToast
import kotlinx.coroutines.delay

class MainActivity : androidx.activity.ComponentActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SplitBillApp
        setContent {
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            SplitBillTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                Box(modifier = Modifier.fillMaxSize().dismissKeyboardOnTap()) {
                    val viewModel: MainViewModel = viewModel(
                        factory = MainViewModelFactory(
                            app.repository,
                            app.settingsRepository,
                            app.backupManager,
                            app.updateChecker,
                        )
                    )
                    SplitBillNavHost(viewModel = viewModel, settings = settings)
                }
            }
        }
    }
}

@Composable
private fun SplitBillNavHost(
    viewModel: MainViewModel,
    settings: AppSettings,
) {
    val needsOnboarding by viewModel.needsOnboarding.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val mainTab by viewModel.mainTab.collectAsStateWithLifecycle()
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val showEasterEgg by viewModel.showEasterEgg.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var startupUpdateChecked by remember { mutableStateOf(false) }

    fun startUpdateDownload(downloadUrl: String, fileName: String) {
        try {
            ApkDownloader.enqueue(context, downloadUrl, fileName)
            viewModel.onUpdateDownloadStarted()
            SystemToast.show(context, "Downloading $fileName")
        } catch (e: Exception) {
            viewModel.failOpenUpdateDownload(
                e.message?.takeIf { it.isNotBlank() } ?: "Could not start download"
            )
        }
    }

    LaunchedEffect(settings.onboardingComplete, settings.autoCheckUpdates) {
        if (!settings.onboardingComplete || !settings.autoCheckUpdates || startupUpdateChecked) return@LaunchedEffect
        startupUpdateChecked = true
        delay(1_500)
        viewModel.checkForUpdates(BuildConfig.VERSION_CODE, silent = true)
    }

    LaunchedEffect(
        updateState.backupCompleted,
        updateState.pendingDownloadUrlAfterBackup,
        updateState.pendingDownloadFileName,
        settings.lastSuccessfulBackupAt,
    ) {
        val url = updateState.pendingDownloadUrlAfterBackup ?: return@LaunchedEffect
        val fileName = updateState.pendingDownloadFileName ?: return@LaunchedEffect
        if (!updateState.backupCompleted) return@LaunchedEffect
        if (!settings.hasFreshSuccessfulBackup()) return@LaunchedEffect
        startUpdateDownload(url, fileName)
    }

    UpdatePromptDialogs(
        updateState = updateState,
        cloudBackupEnabled = settings.cloudBackupEnabled && MongoUriVault.isAvailable(),
        onDismissUpdatePrompt = viewModel::dismissUpdatePrompt,
        onExportBackupAndDownload = { downloadUrl, fileName ->
            viewModel.beginExportBackupAndUpdate(context, downloadUrl, fileName)
        },
        onSkipBackupAndDownload = ::startUpdateDownload,
    )

    LaunchedEffect(userMessage) {
        userMessage?.let {
            SystemToast.show(context, it)
            viewModel.consumeUserMessage()
        }
    }

    if (showEasterEgg) {
        AnantEasterEggDialog(onDismiss = viewModel::dismissEasterEgg)
    }

    when (needsOnboarding) {
        null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        true -> {
            OnboardingScreen(
                isSaving = busy,
                isRestoring = busy,
                cloudRestoreAvailable = MongoUriVault.isAvailable(),
                onComplete = viewModel::completeOnboarding,
                onRestoreLocal = viewModel::restoreFromLocal,
                onJoinRoom = viewModel::joinRoom,
            )
        }
        false -> {
            RequestSyncNotificationPermission()
            BackHandler(
                enabled = when (destination) {
                    AppDestination.Main -> mainTab != MainTab.Home
                    AppDestination.PickDefaultMember -> false
                    AppDestination.RecordRecharge -> true
                }
            ) {
                when (destination) {
                    AppDestination.RecordRecharge -> viewModel.goHome()
                    AppDestination.Main -> viewModel.selectMainTab(MainTab.Home)
                    AppDestination.PickDefaultMember -> Unit
                }
            }
            when (destination) {
                AppDestination.Main -> MainShellScreen(
                    selectedTab = mainTab,
                    onSelectTab = viewModel::selectMainTab,
                    dashboard = dashboard,
                    entries = entries,
                    settings = settings,
                    members = dashboard?.members.orEmpty(),
                    versionName = BuildConfig.VERSION_NAME,
                    busy = busy,
                    onRecordRecharge = viewModel::openRecordRecharge,
                    onShare = { viewModel.shareBalances(context) },
                    onDeleteRechargeGroup = viewModel::softDeleteRechargeGroup,
                    onThemeMode = viewModel::setThemeMode,
                    onDynamicColor = viewModel::setDynamicColor,
                    onCrashReporting = viewModel::setCrashReporting,
                    onDefaultMember = viewModel::setDefaultMemberId,
                    onExport = viewModel::exportLocalBackup,
                    onImport = { uri: Uri -> viewModel.importLocalBackup(uri) { null } },
                    onSyncCloud = viewModel::syncCloudBackup,
                    updateState = updateState,
                    onCheckForUpdates = { viewModel.checkForUpdates(BuildConfig.VERSION_CODE) },
                    onAutoCheckUpdatesChange = viewModel::setAutoCheckUpdates,
                    onCloudAutoSyncChanged = viewModel::setCloudAutoUploadEnabled,
                    onPullCloud = viewModel::pullCloudChanges,
                    onDeveloperModeToggled = { unlocked ->
                        viewModel.setDeveloperModeUnlocked(unlocked)
                        SystemToast.show(
                            context,
                            if (unlocked) "Developer settings unlocked"
                            else "Developer settings hidden",
                        )
                    },
                    onDeveloperUnlockHint = { remaining ->
                        SystemToast.show(context, "$remaining taps to go")
                    },
                    onDeveloperUnlockHintDismiss = { },
                    onEasterEggTriggered = {
                        viewModel.triggerHeartCelebration()
                        viewModel.markEasterEggDiscovered()
                    },
                    onAnantTapHint = { remaining ->
                        SystemToast.show(context, "$remaining taps to go")
                    },
                    onAnantTapHintDismiss = { },
                    onAnantTapWhenUnlocked = {
                        SystemToast.show(context, "Don't be greedy")
                    },
                    onRoomIdCopied = {
                        SystemToast.show(context, "Room ID copied")
                    },
                    onInvite = { viewModel.inviteToRoom(context) },
                    onRegenerateSupportId = viewModel::regenerateSupportId,
                    onMongoOverrides = viewModel::updateMongoOverrides,
                    onHeartDoubleTapHeartbeat = viewModel::sendHeartbeatFromLoveTap
                )
                AppDestination.RecordRecharge -> RecordRechargeScreen(
                    members = dashboard?.members.orEmpty(),
                    currencySymbol = dashboard?.currencySymbol ?: "Rs.",
                    busy = busy,
                    defaultMemberId = settings.defaultMemberId,
                    onBack = viewModel::goHome,
                    onSubmit = viewModel::recordReadingsAndRecharge
                )
                AppDestination.PickDefaultMember -> {
                    val members = dashboard?.members.orEmpty()
                    if (members.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        PickDefaultMemberScreen(
                            members = members,
                            initialMemberId = settings.defaultMemberId,
                            onConfirm = viewModel::setDefaultMemberId
                        )
                    }
                }
            }
        }
    }
}
