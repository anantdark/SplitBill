package com.anant.splitbill.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anant.splitbill.BuildConfig
import com.anant.splitbill.crash.CrashReporter
import com.anant.splitbill.crash.HeartbeatInfo
import com.anant.splitbill.crash.HeartbeatKind
import com.anant.splitbill.data.backup.BackupErrorMessages
import com.anant.splitbill.data.backup.BackupImportResult
import com.anant.splitbill.data.backup.BackupManager
import com.anant.splitbill.data.backup.mongo.MongoUriVault
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.RoomDashboard
import com.anant.splitbill.data.model.ThemeMode
import com.anant.splitbill.data.remote.UpdateCheckResult
import com.anant.splitbill.data.remote.UpdateChecker
import com.anant.splitbill.data.repository.SplitBillRepository
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.data.settings.SettingsRepository
import com.anant.splitbill.ui.screens.MainTab
import com.anant.splitbill.util.BackupShare
import com.anant.splitbill.util.ShareUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

sealed class AppDestination {
    /** Bottom-nav host (Home / History / Settings). */
    data object Main : AppDestination()
    data object RecordRecharge : AppDestination()
    /** Pick which household member is “you” for default recharge logging. */
    data object PickDefaultMember : AppDestination()
}

@Immutable
data class UpdateUiState(
    val isChecking: Boolean = false,
    val updateInfo: UpdateCheckResult.Available? = null,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    val backupCompleted: Boolean = false,
    val isExportingBackup: Boolean = false,
    val pendingDownloadUrlAfterBackup: String? = null,
    val pendingDownloadFileName: String? = null,
    val backupStatusMessage: String? = null,
    val backupStatusIsError: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val repository: SplitBillRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    private val _destination = MutableStateFlow<AppDestination>(AppDestination.Main)
    val destination: StateFlow<AppDestination> = _destination.asStateFlow()

    private val _mainTab = MutableStateFlow(MainTab.Home)
    val mainTab: StateFlow<MainTab> = _mainTab.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _needsOnboarding = MutableStateFlow<Boolean?>(null)
    val needsOnboarding: StateFlow<Boolean?> = _needsOnboarding.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _showEasterEgg = MutableStateFlow(false)
    val showEasterEgg: StateFlow<Boolean> = _showEasterEgg.asStateFlow()

    val dashboard: StateFlow<RoomDashboard?> = settings
        .flatMapLatest { s ->
            val roomId = s.activeRoomId
            if (roomId.isNullOrBlank()) flowOf(null) else repository.observeDashboard(roomId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val entries: StateFlow<List<EntryEntity>> = settings
        .flatMapLatest { s ->
            val roomId = s.activeRoomId
            if (roomId.isNullOrBlank()) flowOf(emptyList()) else repository.observeEntries(roomId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            var current = settingsRepository.current()
            _needsOnboarding.value = !current.onboardingComplete
            if (current.activeRoomId.isNullOrBlank()) {
                val count = repository.roomCount()
                if (count > 0) {
                    val rooms = repository.observeRooms().first()
                    rooms.firstOrNull()?.let { room ->
                        settingsRepository.update { it.copy(activeRoomId = room.id) }
                    }
                }
            }
            current = settingsRepository.current()
            if (current.onboardingComplete) {
                _destination.value = destinationAfterSelfCheck()
            }
        }
    }

    /** Dashboard if a valid default member is set; otherwise ask who “you” are. */
    private suspend fun destinationAfterSelfCheck(): AppDestination {
        val s = settingsRepository.current()
        val roomId = s.activeRoomId ?: return AppDestination.Main
        val members = repository.observeDashboard(roomId).first()?.members.orEmpty()
        if (members.isEmpty()) return AppDestination.Main
        val self = s.defaultMemberId
        val valid = !self.isNullOrBlank() && members.any { it.memberId == self }
        return if (valid) AppDestination.Main else AppDestination.PickDefaultMember
    }

    fun navigateTo(destination: AppDestination) {
        _destination.value = destination
    }

    fun selectMainTab(tab: MainTab) {
        _mainTab.value = tab
        _destination.value = AppDestination.Main
    }

    fun goHome() {
        selectMainTab(MainTab.Home)
    }

    /** Opens the meter log form. Cloud sync runs after a new entry is saved. */
    fun openRecordRecharge() {
        _destination.value = AppDestination.RecordRecharge
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    fun completeCrashReportingOptIn(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    crashReportingEnabled = enabled,
                    crashReportingPromptCompleted = true,
                )
            }
            com.anant.splitbill.crash.CrashReporter.setReportingEnabled(enabled)
        }
    }

    fun completeOnboarding(roomName: String, memberNames: List<String>, defaultMemberName: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val roomId = settingsRepository.ensureSupportId()
                val members = repository.createRoom(roomName, memberNames, roomId = roomId)
                val selfName = defaultMemberName.trim()
                val defaultId = members.firstOrNull { it.name.equals(selfName, ignoreCase = true) }?.id
                    ?: members.firstOrNull()?.id
                settingsRepository.update {
                    it.copy(
                        onboardingComplete = true,
                        activeRoomId = roomId,
                        supportId = roomId,
                        defaultMemberId = defaultId,
                    )
                }
                // Seed cloud so others can join with this Room ID.
                backupManager.pushCloud()
                _needsOnboarding.value = false
                _destination.value = AppDestination.Main
                _userMessage.value = "Room ready — share Room ID to invite others"
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't create room"
            }
            _busy.value = false
        }
    }

    fun setDefaultMemberId(memberId: String) {
        viewModelScope.launch {
            val id = memberId.trim()
            if (id.isBlank()) return@launch
            val name = dashboard.value?.members?.firstOrNull { it.memberId == id }?.name
            settingsRepository.update { it.copy(defaultMemberId = id) }
            if (_destination.value == AppDestination.PickDefaultMember) {
                _destination.value = AppDestination.Main
            } else if (name != null) {
                _userMessage.value = "You're set as $name"
            }
        }
    }

    /**
     * Join / restore an existing household by Room ID (same as the host's Support ID).
     * Sets this device's Support ID, pulls cloud data, and adopts that room.
     */
    fun joinRoom(roomId: String) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val id = roomId.trim()
                require(id.isNotBlank()) { "Enter a Room ID" }
                when (val result = backupManager.joinFromCloud(id)) {
                    is BackupImportResult.Success -> {
                        com.anant.splitbill.crash.CrashReporter.setSupportId(id)
                        // Joining another device's room — re-pick who “you” are.
                        settingsRepository.update { it.copy(defaultMemberId = null) }
                        _needsOnboarding.value = false
                        _destination.value = AppDestination.PickDefaultMember
                        _userMessage.value = "Joined room (${result.recordCount} records)"
                    }
                    BackupImportResult.WrongPassword ->
                        error(BackupErrorMessages.INCORRECT_PASSWORD)
                    BackupImportResult.Corrupt ->
                        error(BackupErrorMessages.BACKUP_CORRUPT)
                    BackupImportResult.PasswordRequired ->
                        error(BackupErrorMessages.INCORRECT_PASSWORD)
                    BackupImportResult.Unrecognized,
                    BackupImportResult.NotFound ->
                        error("No cloud room found for that Room ID")
                    is BackupImportResult.Failed ->
                        error(result.message)
                }
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't join room"
            }
            _busy.value = false
        }
    }

    fun restoreFromLocal(uri: Uri, passwordProvider: suspend () -> CharArray?) {
        viewModelScope.launch {
            _busy.value = true
            var attempts = 0
            var result = backupManager.importFrom(uri, passwordProvider)
            while (result is BackupImportResult.WrongPassword && attempts < 5) {
                attempts++
                result = backupManager.importFrom(uri, passwordProvider)
            }
            when (result) {
                is BackupImportResult.Success -> {
                    val rooms = repository.observeRooms().first()
                    val active = rooms.firstOrNull()?.id
                    settingsRepository.update {
                        it.copy(onboardingComplete = true, activeRoomId = active)
                    }
                    _needsOnboarding.value = false
                    _destination.value = destinationAfterSelfCheck()
                    _userMessage.value = "Restored ${result.recordCount} records"
                }
                BackupImportResult.WrongPassword ->
                    _userMessage.value = BackupErrorMessages.IMPORT_TOO_MANY_ATTEMPTS
                BackupImportResult.PasswordRequired ->
                    _userMessage.value = BackupErrorMessages.INCORRECT_PASSWORD
                BackupImportResult.Corrupt ->
                    _userMessage.value = BackupErrorMessages.BACKUP_CORRUPT
                BackupImportResult.Unrecognized ->
                    _userMessage.value = BackupErrorMessages.NOT_VALID_BACKUP
                BackupImportResult.NotFound,
                is BackupImportResult.Failed ->
                    _userMessage.value = BackupErrorMessages.NOT_VALID_BACKUP
            }
            _busy.value = false
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            _busy.value = true
            val roomId = settingsRepository.current().supportId
            when (val result = backupManager.downloadCloud()) {
                is BackupImportResult.Success -> {
                    val supportId = settingsRepository.ensureSupportId()
                    repository.alignPrimaryRoomId(supportId)
                    settingsRepository.update {
                        it.copy(
                            onboardingComplete = true,
                            activeRoomId = supportId,
                            supportId = supportId,
                        )
                    }
                    _needsOnboarding.value = false
                    _destination.value = destinationAfterSelfCheck()
                    _userMessage.value = "Cloud restore complete (${result.recordCount} records)"
                }
                BackupImportResult.NotFound ->
                    _userMessage.value = BackupErrorMessages.cloudBackupNotFound(roomId)
                BackupImportResult.WrongPassword ->
                    _userMessage.value = BackupErrorMessages.INCORRECT_PASSWORD
                BackupImportResult.Corrupt ->
                    _userMessage.value = BackupErrorMessages.BACKUP_CORRUPT
                is BackupImportResult.Failed ->
                    _userMessage.value = result.message
                else ->
                    _userMessage.value = BackupErrorMessages.NOT_VALID_BACKUP
            }
            _busy.value = false
        }
    }

    fun recordReadingsAndRecharge(
        readings: Map<String, Double>,
        rechargeMemberId: String,
        rechargeAmount: Double
    ) {
        viewModelScope.launch {
            val roomId = settings.value.activeRoomId ?: return@launch
            val self = dashboard.value?.members
                ?.firstOrNull { it.memberId == settings.value.defaultMemberId }
            _busy.value = true
            runCatching {
                repository.recordReadingsAndRecharge(
                    roomId = roomId,
                    readings = readings,
                    rechargeMemberId = rechargeMemberId,
                    rechargeAmount = rechargeAmount,
                    loggedByMemberId = self?.memberId,
                    loggedByMemberName = self?.name,
                )
                _destination.value = AppDestination.Main
                _mainTab.value = MainTab.Home
                if (settingsRepository.current().cloudAutoUploadEnabled) {
                    backupManager.syncCloud().onFailure { e ->
                        _userMessage.value =
                            e.message?.let { "Saved locally, but cloud sync failed: $it" }
                                ?: "Saved locally, but cloud sync failed"
                    }
                }
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't save readings"
            }
            _busy.value = false
        }
    }


    fun softDeleteRechargeGroup(groupId: String) {
        viewModelScope.launch {
            val roomId = settings.value.activeRoomId ?: return@launch
            _busy.value = true
            runCatching {
                backupManager.softDeleteRechargeAndSync(roomId, groupId).getOrThrow()
                val settings = settingsRepository.current()
                val info = HeartbeatInfo(
                    roomCount = runCatching { repository.roomCount() }.getOrDefault(0),
                    entryCount = runCatching { repository.totalEntryCount() }.getOrDefault(0),
                    isDeveloper = settings.developerModeUnlocked,
                )
                CrashReporter.sendHeartbeat(info, HeartbeatKind.DELETE)
                _userMessage.value = "Recharge marked deleted — others will see it on sync"
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't delete"
            }
            _busy.value = false
        }
    }

    fun shareBalances(context: android.content.Context) {
        val dash = dashboard.value ?: return
        ShareUtils.shareText(context, repository.buildShareText(dash))
    }

    fun inviteToRoom(context: android.content.Context) {
        viewModelScope.launch {
            val id = settingsRepository.current().supportId.trim()
            if (id.isBlank()) {
                _userMessage.value = "Room ID is not set yet"
                return@launch
            }
            val roomName = dashboard.value?.roomName
            ShareUtils.shareInvite(context, roomId = id, roomName = roomName)
        }
    }

    fun exportLocalBackup(uri: Uri, password: CharArray? = null) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val count = backupManager.exportTo(uri, password)
                _userMessage.value = "Exported $count records"
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Export failed"
            }
            _busy.value = false
        }
    }

    fun importLocalBackup(uri: Uri, passwordProvider: suspend () -> CharArray?) {
        restoreFromLocal(uri, passwordProvider)
    }

    fun syncCloudBackup() {
        viewModelScope.launch {
            _busy.value = true
            backupManager.syncCloud()
                .onSuccess { result ->
                    if (result.newlyDeletedEntries.isNotEmpty()) {
                        backupManager.markDeletionsNotified(
                            result.newlyDeletedEntries.map { it.id }
                        )
                    }
                    _userMessage.value = when {
                        result.newlyDeletedEntries.isNotEmpty() -> {
                            val n = result.newlyDeletedEntries
                                .map { it.groupId }
                                .distinct()
                                .size
                            "Synced — $n deleted recharge${if (n == 1) "" else "s"} from cloud"
                        }
                        result.newEntries.isNotEmpty() -> {
                            val groups = result.newEntries.map { it.groupId }.distinct().size
                            "Synced — $groups new update${if (groups == 1) "" else "s"} from cloud"
                        }
                        else -> "Cloud synced (${result.recordCount} records)"
                    }
                }
                .onFailure { e -> _userMessage.value = e.message ?: "Cloud sync failed" }
            _busy.value = false
        }
    }

    fun uploadCloudBackup() {
        syncCloudBackup()
    }

    fun downloadCloudBackup() {
        restoreFromCloud()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(themeMode = mode) }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(dynamicColor = enabled) }
        }
    }

    fun setCrashReporting(enabled: Boolean) {
        if (!BuildConfig.IS_FDROID) return
        viewModelScope.launch {
            settingsRepository.update { it.copy(crashReportingEnabled = enabled) }
            com.anant.splitbill.crash.CrashReporter.setReportingEnabled(enabled)
        }
    }

    fun setDeveloperModeUnlocked(unlocked: Boolean) {
        viewModelScope.launch {
            val was = settingsRepository.current().developerModeUnlocked
            if (was == unlocked) return@launch
            settingsRepository.update { it.copy(developerModeUnlocked = unlocked) }
            if (unlocked) _showEasterEgg.value = true
        }
    }

    fun dismissEasterEgg() {
        _showEasterEgg.value = false
    }

    fun regenerateSupportId() {
        viewModelScope.launch {
            val previous = settingsRepository.current().supportId
            val id = settingsRepository.regenerateSupportId()
            if (previous.isNotBlank()) {
                runCatching { repository.alignPrimaryRoomId(id) }
            }
            settingsRepository.update { it.copy(activeRoomId = id, supportId = id) }
            com.anant.splitbill.crash.CrashReporter.setSupportId(id)
            backupManager.pushCloud()
            _userMessage.value = "New Room ID created — share it so others can rejoin"
        }
    }

    fun updateMongoOverrides(dbName: String, collectionName: String) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    mongoDbName = dbName.ifBlank { AppSettings.DEFAULT_MONGO_DB_NAME },
                    mongoCollectionName = collectionName.ifBlank { AppSettings.DEFAULT_MONGO_COLLECTION },
                )
            }
            _userMessage.value = "Atlas overrides saved"
        }
    }

    fun setCloudBackupEnabled(enabled: Boolean) {
        // Cloud sync is always on — ignore off requests.
        if (!enabled) return
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(cloudBackupEnabled = true, cloudAutoUploadEnabled = true)
            }
        }
    }

    fun setCloudAutoUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(cloudAutoUploadEnabled = enabled) }
            _userMessage.value = if (enabled) {
                "Auto cloud sync enabled"
            } else {
                "Auto cloud sync off — use Pull or Sync now manually"
            }
        }
    }

    fun pullCloudChanges() {
        viewModelScope.launch {
            _busy.value = true
            backupManager.pullLatestFromCloud()
                .onSuccess { result ->
                    if (result.newlyDeletedEntries.isNotEmpty()) {
                        backupManager.markDeletionsNotified(
                            result.newlyDeletedEntries.map { it.id }
                        )
                    }
                    _userMessage.value = when {
                        result.newlyDeletedEntries.isNotEmpty() -> {
                            val n = result.newlyDeletedEntries.map { it.groupId }.distinct().size
                            "Pulled — $n deleted recharge${if (n == 1) "" else "s"} from cloud"
                        }
                        result.newEntries.isNotEmpty() -> {
                            val groups = result.newEntries.map { it.groupId }.distinct().size
                            "Pulled — $groups new update${if (groups == 1) "" else "s"} from cloud"
                        }
                        else -> "Pulled from cloud — already up to date"
                    }
                }
                .onFailure { e -> _userMessage.value = e.message ?: "Cloud pull failed" }
            _busy.value = false
        }
    }

    fun setCloudPassword(password: CharArray) {
        viewModelScope.launch {
            backupManager.setCloudPassword(password)
            _userMessage.value = "Cloud backup password saved"
        }
    }

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    fun checkForUpdates(currentVersionCode: Int, silent: Boolean = false) {
        if (BuildConfig.IS_FDROID) return
        if (_updateState.value.isChecking) return
        viewModelScope.launch {
            _updateState.update {
                it.copy(
                    isChecking = true,
                    statusMessage = if (silent) it.statusMessage else null,
                    statusIsError = if (silent) it.statusIsError else false,
                )
            }
            when (val result = updateChecker.checkForUpdate(currentVersionCode)) {
                is UpdateCheckResult.Available -> _updateState.update {
                    it.copy(
                        isChecking = false,
                        updateInfo = result,
                        statusMessage = null,
                        statusIsError = false,
                        backupCompleted = false,
                        isExportingBackup = false,
                        pendingDownloadUrlAfterBackup = null,
                        pendingDownloadFileName = null,
                        backupStatusMessage = null,
                        backupStatusIsError = false,
                    )
                }
                UpdateCheckResult.UpToDate -> _updateState.update {
                    it.copy(
                        isChecking = false,
                        updateInfo = null,
                        statusMessage = if (silent) null else "You're on the latest version",
                        statusIsError = false,
                        backupCompleted = false,
                        isExportingBackup = false,
                        pendingDownloadUrlAfterBackup = null,
                        pendingDownloadFileName = null,
                        backupStatusMessage = null,
                        backupStatusIsError = false,
                    )
                }
                is UpdateCheckResult.Error -> _updateState.update {
                    it.copy(
                        isChecking = false,
                        updateInfo = null,
                        statusMessage = if (silent) null else result.message,
                        statusIsError = !silent,
                        backupCompleted = false,
                        isExportingBackup = false,
                        pendingDownloadUrlAfterBackup = null,
                        pendingDownloadFileName = null,
                        backupStatusMessage = null,
                        backupStatusIsError = false,
                    )
                }
            }
        }
    }

    fun dismissUpdatePrompt() {
        val state = _updateState.value
        if (state.isExportingBackup) return
        _updateState.update {
            it.copy(
                updateInfo = null,
                statusMessage = null,
                statusIsError = false,
                backupCompleted = false,
                isExportingBackup = false,
                pendingDownloadUrlAfterBackup = null,
                pendingDownloadFileName = null,
                backupStatusMessage = null,
                backupStatusIsError = false,
            )
        }
    }

    fun onUpdateDownloadStarted() {
        _updateState.update {
            it.copy(
                updateInfo = null,
                statusMessage = "APK download started — check your Downloads folder",
                statusIsError = false,
                backupCompleted = false,
                isExportingBackup = false,
                pendingDownloadUrlAfterBackup = null,
                pendingDownloadFileName = null,
                backupStatusMessage = null,
                backupStatusIsError = false,
            )
        }
    }

    fun failOpenUpdateDownload(message: String) {
        _updateState.update {
            it.copy(
                statusMessage = message,
                statusIsError = true,
                pendingDownloadUrlAfterBackup = null,
                pendingDownloadFileName = null,
                backupCompleted = false,
            )
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(autoCheckUpdates = enabled) }
        }
    }

    fun beginExportBackupAndUpdate(context: Context, downloadUrl: String, fileName: String) {
        if (_updateState.value.isExportingBackup) return
        if (_updateState.value.backupCompleted) return
        _updateState.update {
            it.copy(
                pendingDownloadUrlAfterBackup = downloadUrl,
                pendingDownloadFileName = fileName,
                backupStatusMessage = null,
                backupStatusIsError = false,
            )
        }
        exportBackupForUpdate(context)
    }

    fun exportBackupForUpdate(context: Context) {
        if (_updateState.value.isExportingBackup) return
        if (_updateState.value.backupCompleted) return
        val useCloud = settings.value.cloudBackupEnabled && MongoUriVault.isAvailable()
        viewModelScope.launch {
            _updateState.update {
                it.copy(
                    isExportingBackup = true,
                    backupStatusMessage = null,
                    backupStatusIsError = false,
                )
            }
            val result = if (useCloud) {
                runCatching { backupManager.pushCloud().getOrThrow() }
            } else {
                runCatching {
                    val (file, count) = backupManager.exportToShareCache()
                    BackupShare.shareJsonFile(
                        context.applicationContext,
                        file,
                        chooserTitle = "Share SplitBill backup",
                    )
                    count
                }
            }
            result
                .onSuccess { count ->
                    val fresh = settingsRepository.settings.first().hasFreshSuccessfulBackup()
                    if (!fresh) {
                        _updateState.update {
                            it.copy(
                                isExportingBackup = false,
                                backupCompleted = false,
                                pendingDownloadUrlAfterBackup = null,
                                pendingDownloadFileName = null,
                                backupStatusMessage = "Backup timestamp missing or too old; download cancelled",
                                backupStatusIsError = true,
                            )
                        }
                        return@onSuccess
                    }
                    val message = if (useCloud) {
                        "Uploaded $count records to cloud backup"
                    } else {
                        "Shared $count records"
                    }
                    _updateState.update {
                        it.copy(
                            isExportingBackup = false,
                            backupCompleted = true,
                            backupStatusMessage = message,
                            backupStatusIsError = false,
                        )
                    }
                }
                .onFailure { e ->
                    val message = if (useCloud) {
                        "Cloud upload failed: ${BackupErrorMessages.cloudRestoreFailed(e.message)}"
                    } else {
                        "Export failed: ${e.message}"
                    }
                    _updateState.update {
                        it.copy(
                            isExportingBackup = false,
                            backupCompleted = false,
                            pendingDownloadUrlAfterBackup = null,
                            pendingDownloadFileName = null,
                            backupStatusMessage = message,
                            backupStatusIsError = true,
                        )
                    }
                }
        }
    }

    fun triggerHeartCelebration() {
        _showEasterEgg.value = true
    }

    fun markEasterEggDiscovered() {
        viewModelScope.launch {
            val current = settingsRepository.current()
            if (!current.easterEggDiscovered) {
                settingsRepository.update { it.copy(easterEggDiscovered = true) }
            }
        }
    }
}

class MainViewModelFactory(
    private val repository: SplitBillRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
    private val updateChecker: UpdateChecker,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(repository, settingsRepository, backupManager, updateChecker) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
