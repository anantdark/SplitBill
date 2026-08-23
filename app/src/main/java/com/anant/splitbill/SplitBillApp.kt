package com.anant.splitbill

import android.app.Application
import com.anant.splitbill.crash.CrashReporter
import com.anant.splitbill.crash.HeartbeatInfo
import com.anant.splitbill.data.backup.BackupManager
import com.anant.splitbill.data.backup.crypto.BackupPasswordStore
import com.anant.splitbill.data.backup.mongo.MongoBackupRepository
import com.anant.splitbill.data.remote.NetworkModule
import com.anant.splitbill.data.remote.UpdateChecker
import com.anant.splitbill.data.repository.SplitBillRepository
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.data.settings.SettingsRepository
import com.anant.splitbill.sync.CloudSyncWorker
import com.anant.splitbill.sync.SyncNotifier
import com.anant.splitbill.ui.widget.BalanceWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

    val updateChecker: UpdateChecker by lazy { UpdateChecker(NetworkModule.provideGithubApi()) }

    override fun onCreate() {
        super.onCreate()
        SyncNotifier.ensureChannel(this)
        CloudSyncWorker.enqueue(this)
        // DataStore only here — never touch Room on the main thread.
        val resolved = runBlocking(Dispatchers.IO) {
            settingsRepository.ensureSupportId()
            val current = settingsRepository.settings.first()
            val githubBuild = !BuildConfig.IS_FDROID
            settingsRepository.update {
                val normalized = it.copy(
                    supportId = it.supportId.ifBlank { current.supportId },
                    mongoDbName = sanitizeMongoDb(it.mongoDbName),
                    mongoCollectionName = sanitizeMongoCollection(it.mongoCollectionName),
                )
                if (githubBuild) {
                    normalized.copy(
                        crashReportingEnabled = true,
                        crashReportingPromptCompleted = true,
                    )
                } else {
                    normalized
                }
            }
            settingsRepository.settings.first()
        }
        val crashReportingEnabled = if (!BuildConfig.IS_FDROID) {
            true
        } else {
            resolved.crashReportingEnabled
        }
        CrashReporter.init(
            app = this,
            enabled = crashReportingEnabled,
            supportId = resolved.supportId
        )
        appScope.launch {
            settingsRepository.settings
                .map { settings ->
                    val enabled = if (!BuildConfig.IS_FDROID) {
                        true
                    } else {
                        settings.crashReportingEnabled
                    }
                    enabled to settings.supportId
                }
                .distinctUntilChanged()
                .collect { (enabled, supportId) ->
                    CrashReporter.setReportingEnabled(enabled)
                    CrashReporter.setSupportId(supportId)
                }
        }
        appScope.launch {
            val realigned = realignRoomIdIfNeeded()
            if (realigned) {
                // Rewrite cloud doc so joiners key off the same Room ID shown in Settings.
                runCatching { backupManager.pushCloud() }
            } else {
                maybeAutoSyncCloudBackup()
            }
        }
        appScope.launch {
            maybeSendUpdateHeartbeat()
        }
    }

    /**
     * Keep Room ID == Support ID so cloud sync / join share one key.
     * Must run off the main thread (Room forbids main-thread queries).
     */
    private suspend fun realignRoomIdIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val supportId = settingsRepository.ensureSupportId()
        runCatching {
            val before = repository.exportSnapshot().first
            val mismatched = before.isNotEmpty() && before.none { it.id == supportId }
            repository.alignPrimaryRoomId(supportId)
            settingsRepository.update {
                it.copy(
                    activeRoomId = if (before.isEmpty()) {
                        it.activeRoomId
                    } else {
                        supportId
                    },
                    supportId = supportId,
                    mongoDbName = sanitizeMongoDb(it.mongoDbName),
                    mongoCollectionName = sanitizeMongoCollection(it.mongoCollectionName),
                )
            }
            mismatched
        }.getOrDefault(false)
    }

    private suspend fun maybeAutoSyncCloudBackup() {
        if (!backupManager.shouldSyncOnAppStart()) return
        runCatching {
            val result = backupManager.syncCloud().getOrThrow()
            val currency = repository.exportSnapshot().first.firstOrNull()?.currencySymbol ?: "Rs."
            if (result.newEntries.isNotEmpty()) {
                SyncNotifier.notifyNewEntries(this@SplitBillApp, result.newEntries, currency)
            }
            if (result.newlyDeletedEntries.isNotEmpty()) {
                SyncNotifier.notifyDeletedEntries(this@SplitBillApp, result.newlyDeletedEntries, currency)
                backupManager.markDeletionsNotified(result.newlyDeletedEntries.map { it.id })
            }
            BalanceWidgetReceiver.requestUpdate(this@SplitBillApp)
        }
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

    private fun sanitizeMongoDb(raw: String): String {
        val value = raw.trim()
        return if (value.isEmpty() || value == "splitbill") {
            AppSettings.DEFAULT_MONGO_DB_NAME
        } else {
            value
        }
    }

    private fun sanitizeMongoCollection(raw: String): String {
        val value = raw.trim()
        return value.ifEmpty { AppSettings.DEFAULT_MONGO_COLLECTION }
    }
}
