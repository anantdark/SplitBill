package com.anant.splitbill

import android.app.Application
import com.anant.splitbill.crash.CrashReporter
import com.anant.splitbill.crash.HeartbeatInfo
import com.anant.splitbill.data.backup.BackupManager
import com.anant.splitbill.data.backup.crypto.BackupPasswordStore
import com.anant.splitbill.data.backup.mongo.MongoBackupRepository
import com.anant.splitbill.data.repository.SplitBillRepository
import com.anant.splitbill.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SplitBillApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val repository: SplitBillRepository by lazy { SplitBillRepository(this) }

    val backupManager: BackupManager by lazy {
        BackupManager(
            context = this,
            repository = repository,
            settingsRepository = settingsRepository,
            passwordStore = BackupPasswordStore(this),
            mongoRepository = MongoBackupRepository(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        val settings = runBlocking {
            val supportId = settingsRepository.ensureSupportId()
            // Keep Room ID == Support ID so cloud sync / join share one key.
            runCatching {
                repository.alignPrimaryRoomId(supportId)
                settingsRepository.update {
                    it.copy(activeRoomId = it.activeRoomId ?: supportId, supportId = supportId)
                }
            }
            settingsRepository.settings.first()
        }
        CrashReporter.init(
            app = this,
            enabled = settings.crashReportingEnabled,
            supportId = settings.supportId
        )
        appScope.launch {
            settingsRepository.settings
                .map { it.crashReportingEnabled to it.supportId }
                .distinctUntilChanged()
                .collect { (enabled, supportId) ->
                    CrashReporter.setReportingEnabled(enabled)
                    CrashReporter.setSupportId(supportId)
                }
        }
        appScope.launch {
            maybeAutoSyncCloudBackup()
        }
        appScope.launch {
            maybeSendUpdateHeartbeat()
        }
    }

    private suspend fun maybeAutoSyncCloudBackup() {
        if (!backupManager.shouldAutoUploadNow()) return
        runCatching { backupManager.syncCloud() }
    }

    private suspend fun maybeSendUpdateHeartbeat() {
        val settings = settingsRepository.settings.first()
        val roomCount = runCatching { repository.roomCount() }.getOrDefault(0)
        val entryCount = runCatching { repository.totalEntryCount() }.getOrDefault(0)
        val info = HeartbeatInfo(
            roomCount = roomCount,
            entryCount = entryCount,
            isDeveloper = settings.developerModeUnlocked
        )
        val current = BuildConfig.VERSION_CODE
        val previous = settingsRepository.lastKnownVersionCode()
        if (previous == null) {
            settingsRepository.setLastKnownVersionCode(current)
        } else if (current > previous) {
            if (CrashReporter.sendDailyHeartbeat(info)) {
                settingsRepository.setLastKnownVersionCode(current)
            }
        } else if (current != previous) {
            settingsRepository.setLastKnownVersionCode(current)
        }
    }
}
