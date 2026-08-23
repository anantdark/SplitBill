package com.anant.splitbill.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anant.splitbill.data.backup.BackupErrorMessages
import com.anant.splitbill.data.backup.BackupImportResult
import com.anant.splitbill.data.backup.BackupManager
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.RoomDashboard
import com.anant.splitbill.data.model.ThemeMode
import com.anant.splitbill.data.repository.SplitBillRepository
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.data.settings.SettingsRepository
import com.anant.splitbill.util.ShareUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

sealed class AppDestination {
    data object Dashboard : AppDestination()
    data object History : AppDestination()
    data object RecordRecharge : AppDestination()
    data object Settings : AppDestination()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val repository: SplitBillRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _destination = MutableStateFlow<AppDestination>(AppDestination.Dashboard)
    val destination: StateFlow<AppDestination> = _destination.asStateFlow()

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
            val current = settingsRepository.current()
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
        }
    }

    fun navigateTo(destination: AppDestination) {
        _destination.value = destination
    }

    /** Opens the meter log form after pulling any newer cloud data. */
    fun openRecordRecharge() {
        viewModelScope.launch {
            _busy.value = true
            val pull = backupManager.pullLatestFromCloud()
            _busy.value = false
            pull.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't sync earlier readings from cloud"
                return@launch
            }
            _destination.value = AppDestination.RecordRecharge
        }
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

    fun completeOnboarding(roomName: String, memberNames: List<String>) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val roomId = settingsRepository.ensureSupportId()
                repository.createRoom(roomName, memberNames, roomId = roomId)
                settingsRepository.update {
                    it.copy(
                        onboardingComplete = true,
                        activeRoomId = roomId,
                        supportId = roomId,
                    )
                }
                // Seed cloud so others can join with this Room ID.
                backupManager.pushCloud()
                _needsOnboarding.value = false
                _destination.value = AppDestination.Dashboard
                _userMessage.value = "Room ready — share Room ID to invite others"
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't create room"
            }
            _busy.value = false
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
                settingsRepository.setSupportId(id)
                com.anant.splitbill.crash.CrashReporter.setSupportId(id)
                when (val result = backupManager.downloadCloud()) {
                    is BackupImportResult.Success -> {
                        repository.alignPrimaryRoomId(id)
                        settingsRepository.update {
                            it.copy(
                                onboardingComplete = true,
                                activeRoomId = id,
                                supportId = id,
                            )
                        }
                        _needsOnboarding.value = false
                        _destination.value = AppDestination.Dashboard
                        _userMessage.value = "Joined room (${result.recordCount} records)"
                    }
                    else -> error("No cloud room found for that Room ID")
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
            }
            _busy.value = false
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            _busy.value = true
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
                    _userMessage.value = "Cloud restore complete (${result.recordCount} records)"
                }
                BackupImportResult.WrongPassword ->
                    _userMessage.value = BackupErrorMessages.INCORRECT_PASSWORD
                BackupImportResult.Corrupt ->
                    _userMessage.value = BackupErrorMessages.BACKUP_CORRUPT
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
            _busy.value = true
            runCatching {
                // Pull earlier cloud data before writing so we don't overwrite remote history.
                backupManager.pullLatestFromCloud().getOrThrow()
                repository.recordReadingsAndRecharge(roomId, readings, rechargeMemberId, rechargeAmount)
                _destination.value = AppDestination.Dashboard
                // Best-effort upload of the new readings.
                backupManager.pushCloud().onFailure { e ->
                    _userMessage.value =
                        e.message?.let { "Saved locally, but cloud upload failed: $it" }
                            ?: "Saved locally, but cloud upload failed"
                }
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't save readings"
            }
            _busy.value = false
        }
    }


    fun revertLastGroup() {
        viewModelScope.launch {
            val roomId = settings.value.activeRoomId ?: return@launch
            _busy.value = true
            runCatching {
                repository.revertLastGroup(roomId)
                _userMessage.value = "Last entry group reverted"
            }.onFailure { e ->
                _userMessage.value = e.message ?: "Couldn't revert"
            }
            _busy.value = false
        }
    }

    fun shareBalances(context: android.content.Context) {
        val dash = dashboard.value ?: return
        ShareUtils.shareText(context, repository.buildShareText(dash))
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
                .onSuccess { count -> _userMessage.value = "Cloud synced ($count records)" }
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
        viewModelScope.launch {
            settingsRepository.update { it.copy(crashReportingEnabled = enabled) }
            com.anant.splitbill.crash.CrashReporter.setReportingEnabled(enabled)
        }
    }

    fun unlockDeveloperMode() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(developerModeUnlocked = true) }
            _showEasterEgg.value = true
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
        if (!enabled) return
        viewModelScope.launch {
            settingsRepository.update { it.copy(cloudAutoUploadEnabled = true) }
        }
    }

    fun setCloudPassword(password: CharArray) {
        viewModelScope.launch {
            backupManager.setCloudPassword(password)
            _userMessage.value = "Cloud backup password saved"
        }
    }

    fun triggerHeartCelebration() {
        _showEasterEgg.value = true
    }
}

class MainViewModelFactory(
    private val repository: SplitBillRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(repository, settingsRepository, backupManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
