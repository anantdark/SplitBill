package com.anant.splitbill

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.anant.splitbill.crash.CrashReporter
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.ui.components.AnantEasterEggDialog
import com.anant.splitbill.ui.screens.CrashReportingOptInScreen
import com.anant.splitbill.ui.screens.DashboardScreen
import com.anant.splitbill.ui.screens.HistoryScreen
import com.anant.splitbill.ui.screens.OnboardingScreen
import com.anant.splitbill.ui.screens.RecordRechargeScreen
import com.anant.splitbill.ui.screens.SettingsScreen
import com.anant.splitbill.ui.theme.SplitBillTheme
import com.anant.splitbill.ui.util.dismissKeyboardOnTap
import com.anant.splitbill.ui.viewmodel.AppDestination
import com.anant.splitbill.ui.viewmodel.MainViewModel
import com.anant.splitbill.ui.viewmodel.MainViewModelFactory
import com.anant.splitbill.data.backup.mongo.MongoUriVault

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
                val snackbar = remember { SnackbarHostState() }
                Box(modifier = Modifier.fillMaxSize().dismissKeyboardOnTap()) {
                    val viewModel: MainViewModel = viewModel(
                        factory = MainViewModelFactory(
                            app.repository,
                            app.settingsRepository,
                            app.backupManager
                        )
                    )
                    SplitBillNavHost(viewModel = viewModel, settings = settings, snackbar = snackbar)
                    SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable
private fun SplitBillNavHost(
    viewModel: MainViewModel,
    settings: AppSettings,
    snackbar: SnackbarHostState
) {
    val needsOnboarding by viewModel.needsOnboarding.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val showEasterEgg by viewModel.showEasterEgg.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingCrashChoice by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbar.showSnackbar(it)
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
            if (!settings.crashReportingPromptCompleted && !BuildConfig.IS_FDROID && pendingCrashChoice == null) {
                CrashReportingOptInScreen(
                    initialEnabled = settings.crashReportingEnabled,
                    onContinue = { enabled ->
                        pendingCrashChoice = enabled
                        CrashReporter.setReportingEnabled(enabled)
                        viewModel.completeCrashReportingOptIn(enabled)
                    }
                )
            } else {
                OnboardingScreen(
                    isSaving = busy,
                    isRestoring = busy,
                    cloudRestoreAvailable = MongoUriVault.isAvailable(),
                    onComplete = viewModel::completeOnboarding,
                    onRestoreLocal = viewModel::restoreFromLocal,
                    onRestoreCloud = viewModel::restoreFromCloud,
                    onJoinRoom = viewModel::joinRoom,
                    onHeartDoubleTap = viewModel::triggerHeartCelebration
                )
            }
        }
        false -> when (destination) {
            AppDestination.Dashboard -> DashboardScreen(
                dashboard = dashboard,
                onRecordRecharge = viewModel::openRecordRecharge,
                onHistory = { viewModel.navigateTo(AppDestination.History) },
                onSettings = { viewModel.navigateTo(AppDestination.Settings) },
                onShare = { viewModel.shareBalances(context) }
            )
            AppDestination.History -> HistoryScreen(
                entries = entries,
                currencySymbol = dashboard?.currencySymbol ?: "Rs.",
                busy = busy,
                onBack = { viewModel.navigateTo(AppDestination.Dashboard) },
                onRevertLastGroup = viewModel::revertLastGroup
            )
            AppDestination.RecordRecharge -> RecordRechargeScreen(
                members = dashboard?.members.orEmpty(),
                currencySymbol = dashboard?.currencySymbol ?: "Rs.",
                busy = busy,
                onBack = { viewModel.navigateTo(AppDestination.Dashboard) },
                onSubmit = viewModel::recordReadingsAndRecharge
            )
            AppDestination.Settings -> SettingsScreen(
                settings = settings,
                versionName = BuildConfig.VERSION_NAME,
                busy = busy,
                onBack = { viewModel.navigateTo(AppDestination.Dashboard) },
                onThemeMode = viewModel::setThemeMode,
                onDynamicColor = viewModel::setDynamicColor,
                onCrashReporting = viewModel::setCrashReporting,
                onShare = { viewModel.shareBalances(context) },
                onExport = viewModel::exportLocalBackup,
                onImport = { uri: Uri -> viewModel.importLocalBackup(uri) { null } },
                onSyncCloud = viewModel::syncCloudBackup,
                onDeveloperUnlock = viewModel::unlockDeveloperMode,
                onRegenerateSupportId = viewModel::regenerateSupportId,
                onMongoOverrides = viewModel::updateMongoOverrides,
                onHeartDoubleTap = viewModel::triggerHeartCelebration
            )
        }
    }
}
